package dev.ankicraft.client.anki;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ankicraft.client.anki.AnkiModels.AnkiCard;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import dev.ankicraft.client.config.AnkiCraftConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AnkiConnectClient {
    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final AnkiCraftConfig config;

    public AnkiConnectClient(AnkiCraftConfig config) {
        this.config = config;
    }

    public CompletableFuture<Boolean> testConnection() {
        return invoke("version", null)
                .thenApply(res -> res != null && !res.isJsonNull())
                .exceptionally(ex -> false);
    }

    public CompletableFuture<List<AnkiDeck>> fetchDecks() {
        return invoke("deckNamesAndIds", null).thenCompose(namesEl -> {
            if (namesEl == null || !namesEl.isJsonObject()) {
                return CompletableFuture.completedFuture(List.of());
            }
            JsonObject namesObj = namesEl.getAsJsonObject();
            JsonArray deckNames = new JsonArray();
            for (String name : namesObj.keySet()) {
                deckNames.add(name);
            }
            JsonObject statsParams = new JsonObject();
            statsParams.add("decks", deckNames);

            return invoke("getDeckStats", statsParams).thenApply(statsEl -> {
                List<AnkiDeck> result = new ArrayList<>();
                JsonObject statsObj = (statsEl != null && statsEl.isJsonObject()) ? statsEl.getAsJsonObject() : new JsonObject();

                for (Map.Entry<String, JsonElement> entry : namesObj.entrySet()) {
                    String name = entry.getKey();
                    long id = entry.getValue().getAsLong();
                    int newCount = 0;
                    int learnCount = 0;
                    int reviewCount = 0;

                    JsonElement statEl = statsObj.get(String.valueOf(id));
                    if (statEl != null && statEl.isJsonObject()) {
                        JsonObject s = statEl.getAsJsonObject();
                        newCount = getInt(s, "new_count", 0);
                        learnCount = getInt(s, "learn_count", 0);
                        reviewCount = getInt(s, "review_count", 0);
                    }
                    result.add(new AnkiDeck(id, name, newCount, learnCount, reviewCount));
                }
                result.sort(Comparator.comparing(d -> d.name.toLowerCase()));
                return result;
            });
        });
    }

    public record ScheduledCard(AnkiCard card, AnkiDeck studyDeck) {}

    public CompletableFuture<ScheduledCard> fetchNextCard(List<AnkiDeck> studyDecks, AnkiDeck currentStudyDeck) {
        CompletableFuture<ScheduledCard> next = currentStudyDeck == null
                ? CompletableFuture.completedFuture(null)
                : readQueueHead(currentStudyDeck);
        for (AnkiDeck deck : studyDecks) {
            if (currentStudyDeck != null && deck.id == currentStudyDeck.id) continue;
            next = next.thenCompose(card -> card != null
                    ? CompletableFuture.completedFuture(card)
                    : selectStudyDeck(deck).thenCompose(v -> readQueueHead(deck)));
        }
        return next;
    }

    private CompletableFuture<ScheduledCard> readQueueHead(AnkiDeck deck) {
        return queueHead().thenCompose(cardId -> cardId == null
                ? CompletableFuture.completedFuture(null)
                : renderCard(deck, cardId));
    }

    private CompletableFuture<JsonElement> selectStudyDeck(AnkiDeck deck) {
        JsonObject reference = new JsonObject();
        reference.addProperty("id", Long.toString(deck.id));
        JsonObject params = new JsonObject();
        params.addProperty("view", "deckOverview");
        params.add("deck", reference);
        return invokeScheduler("application.navigate", params);
    }

    private CompletableFuture<String> queueHead() {
        JsonObject params = new JsonObject();
        params.addProperty("fetchLimit", 1);
        return invokeScheduler("scheduling.getQueue", params).thenApply(result -> {
            JsonArray cards = result.getAsJsonObject().getAsJsonArray("cards");
            return cards.isEmpty() ? null : cards.get(0).getAsJsonObject().get("cardId").getAsString();
        });
    }

    private CompletableFuture<ScheduledCard> renderCard(AnkiDeck studyDeck, String cardId) {
        JsonArray ids = new JsonArray();
        ids.add(Long.parseLong(cardId));
        JsonObject params = new JsonObject();
        params.add("cards", ids);
        return invoke("cardsInfo", params).thenApply(info -> {
            JsonObject card = info.getAsJsonArray().get(0).getAsJsonObject();
            String deckName = card.get("deckName").getAsString();
            if (!deckName.equals(studyDeck.name) && !deckName.startsWith(studyDeck.name + "::")) {
                throw new IllegalStateException("Anki’s study deck changed. Reload the quiz.");
            }

            List<String> intervals = new ArrayList<>();
            for (JsonElement interval : card.getAsJsonArray("nextReviews")) {
                intervals.add(interval.getAsString());
            }
            AnkiCard rendered = new AnkiCard(Long.parseLong(cardId), card.get("note").getAsLong(),
                    deckName, card.get("question").getAsString(), card.get("answer").getAsString(), intervals);
            return new ScheduledCard(rendered, studyDeck);
        });
    }

    public CompletableFuture<Void> answerCard(ScheduledCard scheduled, int ease) {
        long cardId = scheduled.card().cardId;
        return queueHead().thenCompose(currentId -> {
            if (!Long.toString(cardId).equals(currentId)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Anki’s next scheduled card changed. Reload the quiz."));
            }

            // AnkiConnect computes and submits the scheduling states within one call.
            JsonObject answer = new JsonObject();
            answer.addProperty("cardId", cardId);
            answer.addProperty("ease", ease);
            JsonArray answers = new JsonArray();
            answers.add(answer);
            JsonObject params = new JsonObject();
            params.add("answers", answers);
            return invoke("answerCards", params).thenAccept(result -> {
                if (!result.getAsJsonArray().get(0).getAsBoolean()) {
                    throw new IllegalStateException("Anki did not accept the rating.");
                }
            });
        });
    }

    public CompletableFuture<byte[]> retrieveMediaFile(String filename) {
        JsonObject params = new JsonObject();
        params.addProperty("filename", filename);
        return invoke("retrieveMediaFile", params).thenApply(res -> {
            if (res == null || res.isJsonNull() || !res.isJsonPrimitive()) {
                return null;
            }
            String b64 = res.getAsString();
            if (b64.isEmpty()) return null;
            return Base64.getDecoder().decode(b64);
        }).exceptionally(ex -> null);
    }

    private CompletableFuture<JsonElement> invoke(String action, JsonObject params) {
        JsonObject body = new JsonObject();
        body.addProperty("action", action);
        body.addProperty("version", 6);
        if (config.ankiConnectApiKey != null && !config.ankiConnectApiKey.isBlank()) {
            body.addProperty("key", config.ankiConnectApiKey.trim());
        }
        if (params != null) {
            body.add("params", params);
        }

        return send(URI.create(config.ankiConnectUrl), body, false);
    }

    private CompletableFuture<JsonElement> invokeScheduler(String method, JsonObject params) {
        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", method);
        body.add("params", params);
        return send(URI.create(config.ankiConnectUrl).resolve("/api/v1"), body, true);
    }

    private CompletableFuture<JsonElement> send(URI uri, JsonObject body, boolean schedulerApi) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        if (schedulerApi && config.ankiConnectApiKey != null && !config.ankiConnectApiKey.isBlank()) {
            request.header("Authorization", "Bearer " + config.ankiConnectApiKey.trim());
        }

        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("AnkiConnect HTTP " + resp.statusCode());
                    }
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    JsonElement error = json.get("error");
                    if (error != null && !error.isJsonNull()) {
                        String message = error.isJsonObject()
                                ? error.getAsJsonObject().get("message").getAsString()
                                : error.getAsString();
                        throw new IllegalStateException("AnkiConnect: " + message);
                    }
                    return json.get("result");
                });
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }
}
