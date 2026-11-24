package dev.ankicraft.client.anki;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.config.AnkiCraftConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Integrates the SleepCards Anki add-on protocol directly into AnkiCraft so players
 * do not need to switch windows to Anki Desktop for private server challenges.
 */
public class SleepCardsClient {
    public static final int PROTOCOL_VERSION = 2;
    public static final int ADDON_VERSION = 3;
    private static final int BATCH_SIZE = 50;
    private static final int MAX_QUEUE = 5000;
    private static final int TIMEOUT_SECONDS = 10;
    private static final int POLL_TIMEOUT_SECONDS = 45;
    private static final int PRESENCE_PORT = 47865;
    private static final String USER_AGENT = "SleepCards-Anki/" + ADDON_VERSION;

    private static final Gson GSON = new Gson();
    private static final Type QUEUE_LIST_TYPE = new TypeToken<List<JsonObject>>() {}.getType();
    private static final Path QUEUE_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("ankicraft")
            .resolve("sleepcards_queue.json");

    private final AnkiCraftConfig config;
    private final Consumer<String> messageHandler;
    private final Consumer<JsonArray> commandHandler;
    private final Consumer<JsonObject> replyHandler;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    private final Object queueLock = new Object();
    private final Object wakeMonitor = new Object();
    private final List<JsonObject> queue = new ArrayList<>();
    private boolean woken = false;
    private boolean authWarned = false;
    private volatile boolean started = false;
    private volatile String lastStatus = "Nicht gestartet";

    public SleepCardsClient(
            AnkiCraftConfig config,
            Consumer<String> messageHandler,
            Consumer<JsonArray> commandHandler,
            Consumer<JsonObject> replyHandler
    ) {
        this.config = config;
        this.messageHandler = messageHandler;
        this.commandHandler = commandHandler;
        this.replyHandler = replyHandler;
        loadQueue();
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;

        Thread worker = new Thread(this::runWorker, "SleepCardsWorker");
        worker.setDaemon(true);
        worker.start();

        Thread poller = new Thread(this::runPoller, "SleepCardsPoll");
        poller.setDaemon(true);
        poller.start();

        Thread presence = new Thread(this::runPresenceServer, "SleepCardsPresence");
        presence.setDaemon(true);
        presence.start();

        synchronized (queueLock) {
            if (!queue.isEmpty()) {
                wakeWorker();
            }
        }
    }

    public void wakeWorker() {
        synchronized (wakeMonitor) {
            woken = true;
            wakeMonitor.notifyAll();
        }
    }

    public int getQueueSize() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    public String getLastStatus() {
        if (!config.sleepCardsEnabled) {
            return "Deaktiviert";
        }
        if (getBaseUrl().isEmpty() || getToken().isEmpty()) {
            return "Nicht konfiguriert (Token fehlt)";
        }
        return lastStatus;
    }

    public void enqueueAnswer(long cardId, long noteId, long deckId, int ease, int cardType) {
        if (!config.sleepCardsEnabled) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "answer");
        event.addProperty("id", UUID.randomUUID().toString().replace("-", ""));
        event.addProperty("ts", System.currentTimeMillis());
        event.addProperty("card_id", cardId);
        event.addProperty("note_id", noteId);
        event.addProperty("deck_id", deckId);
        event.addProperty("ease", ease);
        event.addProperty("card_type", cardType);
        enqueue(event);
    }

    public void enqueueStatus(Integer due, String deck) {
        if (!config.sleepCardsEnabled) {
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "status");
        event.addProperty("id", UUID.randomUUID().toString().replace("-", ""));
        event.addProperty("ts", System.currentTimeMillis());
        if (due != null) {
            event.addProperty("due", due);
        } else {
            event.add("due", JsonNull.INSTANCE);
        }
        event.addProperty("deck", deck != null ? deck : "");
        enqueue(event);
    }

    private void enqueue(JsonObject event) {
        synchronized (queueLock) {
            queue.add(event);
            if (queue.size() > MAX_QUEUE) {
                queue.subList(0, queue.size() - MAX_QUEUE).clear();
            }
            saveQueue();
        }
        wakeWorker();
    }

    private String getBaseUrl() {
        String raw = config.sleepCardsServerUrl == null ? "" : config.sleepCardsServerUrl.trim();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private String getToken() {
        return config.sleepCardsToken == null ? "" : config.sleepCardsToken.trim();
    }

    private void loadQueue() {
        if (!Files.exists(QUEUE_FILE)) {
            return;
        }
        try {
            String raw = Files.readString(QUEUE_FILE);
            List<JsonObject> loaded = GSON.fromJson(raw, QUEUE_LIST_TYPE);
            if (loaded != null) {
                synchronized (queueLock) {
                    queue.clear();
                    int start = Math.max(0, loaded.size() - MAX_QUEUE);
                    queue.addAll(loaded.subList(start, loaded.size()));
                }
            }
        } catch (Exception e) {
            AnkiCraftMod.LOGGER.warn("Failed to load SleepCards queue", e);
        }
    }

    private void saveQueue() {
        // Caller holds queueLock
        try {
            Files.createDirectories(QUEUE_FILE.getParent());
            Path tmp = QUEUE_FILE.resolveSibling("sleepcards_queue.json.tmp");
            Files.writeString(tmp, GSON.toJson(queue));
            try {
                Files.move(tmp, QUEUE_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(tmp, QUEUE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AnkiCraftMod.LOGGER.debug("Failed to save SleepCards queue", e);
        }
    }

    private record PostResult(String status, JsonObject reply) {}

    private PostResult postBatch(List<JsonObject> batch) {
        String base = getBaseUrl();
        String token = getToken();
        if (!config.sleepCardsEnabled || base.isEmpty() || token.isEmpty()) {
            return new PostResult("unconfigured", new JsonObject());
        }

        JsonArray eventsArr = new JsonArray();
        for (JsonObject ev : batch) {
            eventsArr.add(ev);
        }

        JsonObject body = new JsonObject();
        body.addProperty("v", PROTOCOL_VERSION);
        body.addProperty("addon_version", ADDON_VERSION);
        body.addProperty("sent_at", System.currentTimeMillis());
        body.add("events", eventsArr);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/events"))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                lastStatus = "Verbunden";
                String raw = resp.body();
                if (raw != null && !raw.isBlank()) {
                    try {
                        JsonElement el = JsonParser.parseString(raw);
                        if (el.isJsonObject()) {
                            return new PostResult("ok", el.getAsJsonObject());
                        }
                    } catch (Exception ignored) {
                    }
                }
                return new PostResult("ok", new JsonObject());
            } else if (code == 401 || code == 403) {
                lastStatus = "Token ungültig (" + code + ")";
                return new PostResult("auth", new JsonObject());
            } else if (code >= 400 && code < 500) {
                lastStatus = "Abgelehnt (" + code + ")";
                return new PostResult("rejected", new JsonObject());
            } else {
                lastStatus = "Serverfehler (" + code + ")";
                return new PostResult("retry", new JsonObject());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PostResult("retry", new JsonObject());
        } catch (Exception e) {
            lastStatus = "Verbindungsfehler";
            return new PostResult("retry", new JsonObject());
        }
    }

    private void runWorker() {
        int backoff = 2;
        while (true) {
            synchronized (wakeMonitor) {
                if (!woken) {
                    try {
                        wakeMonitor.wait(30_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                woken = false;
            }

            while (true) {
                List<JsonObject> batch;
                synchronized (queueLock) {
                    if (queue.isEmpty()) {
                        break;
                    }
                    int count = Math.min(BATCH_SIZE, queue.size());
                    batch = new ArrayList<>(queue.subList(0, count));
                }

                PostResult res = postBatch(batch);
                switch (res.status()) {
                    case "ok", "rejected" -> {
                        boolean empty;
                        synchronized (queueLock) {
                            int toRemove = Math.min(batch.size(), queue.size());
                            queue.subList(0, toRemove).clear();
                            saveQueue();
                            empty = queue.isEmpty();
                        }
                        backoff = 2;
                        authWarned = false;
                        if ("ok".equals(res.status()) && empty) {
                            if (replyHandler != null && res.reply() != null) {
                                replyHandler.accept(res.reply());
                            }
                            if (res.reply().has("message")) {
                                JsonElement msgEl = res.reply().get("message");
                                if (msgEl != null && msgEl.isJsonPrimitive()) {
                                    showMessage(msgEl.getAsString());
                                }
                            }
                        }
                    }
                    case "auth" -> {
                        if (!authWarned) {
                            authWarned = true;
                            showMessage("SleepCards: Token ungueltig. Bitte Einstellungen pruefen.");
                        }
                        break;
                    }
                    case "unconfigured" -> {
                        // Nothing to send until configured
                        break;
                    }
                    default -> {
                        try {
                            Thread.sleep(backoff * 1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        backoff = Math.min(backoff * 2, 60);
                    }
                }
                if (!"ok".equals(res.status()) && !"rejected".equals(res.status())) {
                    break;
                }
            }
        }
    }

    private void runPoller() {
        while (true) {
            String base = getBaseUrl();
            String token = getToken();
            if (!config.sleepCardsEnabled || base.isEmpty() || token.isEmpty()) {
                sleepSeconds(10);
                continue;
            }

            long startedAt = System.currentTimeMillis();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/poll"))
                        .timeout(Duration.ofSeconds(POLL_TIMEOUT_SECONDS))
                        .header("Authorization", "Bearer " + token)
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) {
                    lastStatus = "Verbunden";
                    String raw = resp.body();
                    JsonArray commands = null;
                    if (raw != null && !raw.isBlank()) {
                        try {
                            JsonElement el = JsonParser.parseString(raw);
                            if (el.isJsonObject() && el.getAsJsonObject().has("commands")) {
                                JsonElement cmdEl = el.getAsJsonObject().get("commands");
                                if (cmdEl.isJsonArray()) {
                                    commands = cmdEl.getAsJsonArray();
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (commands != null && !commands.isEmpty()) {
                        if (commandHandler != null) {
                            commandHandler.accept(commands);
                        }
                    } else if (System.currentTimeMillis() - startedAt < 2000L) {
                        // Avoid tight loop if server responds immediately with no commands
                        sleepSeconds(10);
                    }
                } else if (code == 204) {
                    lastStatus = "Verbunden";
                    if (System.currentTimeMillis() - startedAt < 500L) {
                        sleepSeconds(2);
                    }
                } else if (code == 401 || code == 403) {
                    lastStatus = "Token ungültig (" + code + ")";
                    sleepSeconds(30);
                } else {
                    lastStatus = "Poll-Fehler (" + code + ")";
                    sleepSeconds(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastStatus = "Verbindungsfehler";
                sleepSeconds(10);
            }
        }
    }

    private void showMessage(String msg) {
        if (msg == null || msg.isBlank() || !config.sleepCardsShowMessages) {
            return;
        }
        if (messageHandler != null) {
            messageHandler.accept(msg);
        }
    }

    private void runPresenceServer() {
        try (ServerSocket server = new ServerSocket(PRESENCE_PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                try (Socket client = server.accept()) {
                    boolean active = config.sleepCardsEnabled && !getBaseUrl().isEmpty() && !getToken().isEmpty();
                    client.getOutputStream().write((active ? "1\n" : "0\n").getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            AnkiCraftMod.LOGGER.debug("SleepCards presence socket unavailable on port {}", PRESENCE_PORT, e);
        }
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
