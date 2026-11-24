package dev.ankicraft.client.anki;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ankicraft.AnkiCraftMod;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnkiConnectClient {
    private static final Pattern FIELD_SOUND_PATTERN = Pattern.compile("(?i)\\[sound:([^\\]]+)\\]");
    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
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

    public CompletableFuture<Boolean> syncAnkiWeb() {
        return invoke("sync", null, Duration.ofSeconds(15))
                .thenApply(res -> true)
                .exceptionally(ex -> {
                    AnkiCraftMod.LOGGER.debug("AnkiWeb sync via AnkiConnect skipped/failed: {}", ex.getMessage());
                    return false;
                });
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

    public CompletableFuture<List<AnkiCard>> fetchDueCards(List<String> selectedDecks, int limit) {
        StringBuilder query = new StringBuilder();
        if (selectedDecks != null && !selectedDecks.isEmpty()) {
            query.append("(");
            for (int i = 0; i < selectedDecks.size(); i++) {
                if (i > 0) query.append(" OR ");
                String escaped = selectedDecks.get(i).replace("\"", "\\\"");
                query.append("deck:\"").append(escaped).append("\"");
            }
            query.append(") ");
        }
        query.append("(is:due OR is:new)");

        JsonObject findParams = new JsonObject();
        findParams.addProperty("query", query.toString());

        return invoke("findCards", findParams).thenCompose(idsEl -> {
            if (idsEl == null || !idsEl.isJsonArray()) {
                return CompletableFuture.completedFuture(List.of());
            }
            JsonArray allIds = idsEl.getAsJsonArray();
            if (allIds.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            JsonArray slice = new JsonArray();
            int count = Math.min(limit, allIds.size());
            for (int i = 0; i < count; i++) {
                slice.add(allIds.get(i));
            }
            JsonObject infoParams = new JsonObject();
            infoParams.add("cards", slice);

            return invoke("cardsInfo", infoParams).thenApply(infoEl -> {
                List<AnkiCard> cards = new ArrayList<>();
                if (infoEl == null || !infoEl.isJsonArray()) return cards;

                for (JsonElement el : infoEl.getAsJsonArray()) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    long cardId = obj.has("cardId") ? obj.get("cardId").getAsLong() : 0L;
                    long noteId = obj.has("note") ? obj.get("note").getAsLong() : 0L;
                    long deckId = obj.has("deckId") ? obj.get("deckId").getAsLong() : 0L;
                    int cardType = obj.has("type") ? obj.get("type").getAsInt() : 2;
                    String deckName = obj.has("deckName") ? obj.get("deckName").getAsString() : "Default";
                    String qHtml = obj.has("question") ? obj.get("question").getAsString() : "";
                    String aHtml = obj.has("answer") ? obj.get("answer").getAsString() : "";

                    List<String> nextReviews = new ArrayList<>();
                    if (obj.has("nextReviews") && obj.get("nextReviews").isJsonArray()) {
                        for (JsonElement nr : obj.getAsJsonArray("nextReviews")) {
                            nextReviews.add(nr.getAsString());
                        }
                    }
                    List<String> soundFiles = extractSoundFiles(obj);
                    if (cardId != 0L) {
                        cards.add(new AnkiCard(cardId, noteId, deckId, cardType, deckName, qHtml, aHtml, nextReviews, soundFiles));
                    }
                }
                return cards;
            });
        });
    }

    public CompletableFuture<Boolean> answerCard(long cardId, int ease) {
        JsonObject answerObj = new JsonObject();
        answerObj.addProperty("cardId", cardId);
        answerObj.addProperty("ease", ease);

        JsonArray answers = new JsonArray();
        answers.add(answerObj);

        JsonObject params = new JsonObject();
        params.add("answers", answers);

        return invoke("answerCards", params).thenApply(res -> {
            if (res != null && res.isJsonArray() && !res.getAsJsonArray().isEmpty()) {
                return res.getAsJsonArray().get(0).getAsBoolean();
            }
            return false;
        }).exceptionally(ex -> {
            AnkiCraftMod.LOGGER.warn("Failed to submit answer for card {}", cardId, ex);
            return false;
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
        return invoke(action, params, Duration.ofSeconds(5));
    }

    private CompletableFuture<JsonElement> invoke(String action, JsonObject params, Duration timeout) {
        JsonObject body = new JsonObject();
        body.addProperty("action", action);
        body.addProperty("version", 6);
        if (config.ankiConnectApiKey != null && !config.ankiConnectApiKey.isBlank()) {
            body.addProperty("key", config.ankiConnectApiKey.trim());
        }
        if (params != null) {
            body.add("params", params);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.ankiConnectUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("AnkiConnect HTTP " + resp.statusCode());
                    }
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    if (json.has("error") && !json.get("error").isJsonNull()) {
                        throw new RuntimeException("AnkiConnect error: " + json.get("error").getAsString());
                    }
                    return json.get("result");
                });
    }

    private static List<String> extractSoundFiles(JsonObject cardObj) {
        List<String> sounds = new ArrayList<>();
        if (!cardObj.has("fields") || !cardObj.get("fields").isJsonObject()) {
            return sounds;
        }
        JsonObject fieldsObj = cardObj.getAsJsonObject("fields");
        List<JsonObject> ordered = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : fieldsObj.entrySet()) {
            if (e.getValue().isJsonObject()) {
                ordered.add(e.getValue().getAsJsonObject());
            }
        }
        ordered.sort(Comparator.comparingInt(f -> getInt(f, "order", 0)));
        for (JsonObject field : ordered) {
            if (!field.has("value") || !field.get("value").isJsonPrimitive()) continue;
            Matcher m = FIELD_SOUND_PATTERN.matcher(field.get("value").getAsString());
            while (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty() && !sounds.contains(name)) {
                    sounds.add(name);
                }
            }
        }
        return sounds;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }
}
