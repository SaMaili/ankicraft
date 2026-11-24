package dev.ankicraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.anki.AnkiConnectClient;
import dev.ankicraft.client.anki.AnkiConnectClient.ScheduledCard;
import dev.ankicraft.client.anki.AnkiModels.AnkiCard;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import dev.ankicraft.client.anki.AnkiModels.PendingReview;
import dev.ankicraft.client.anki.CardCache;
import dev.ankicraft.client.anki.SleepCardsClient;
import dev.ankicraft.client.config.AnkiCraftConfig;
import dev.ankicraft.client.render.AudioPlayer;
import dev.ankicraft.client.render.CardContentRenderer;
import dev.ankicraft.client.render.LatexRenderer;
import dev.ankicraft.client.ui.QuizScreen;
import dev.ankicraft.network.AnkiCraftNetwork.QuizResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnkiManager {
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static AnkiManager instance;

    private final AnkiCraftConfig config;
    private final AnkiConnectClient ankiClient;
    private final SleepCardsClient sleepCardsClient;
    private final CardCache cache;
    private final LatexRenderer latexRenderer;
    private final CardContentRenderer contentRenderer;
    private final AudioPlayer audioPlayer;

    private final Deque<AnkiCard> cardQueue = new ArrayDeque<>();
    private List<AnkiDeck> decks = new ArrayList<>();

    private boolean ankiConnected = false;
    private boolean syncing = false;
    private boolean serverSupportsAnkiCraft = false;

    private int streak = 0;
    private int reviewedToday = 0;
    private volatile long sleepCardsChallengeUntilMs = 0L;
    private volatile boolean pendingChallengeFocus = false;
    private volatile boolean learningActive = false;
    private volatile AnkiDeck currentStudyDeck = null;
    private volatile String lastSleepCardsMessage = "";
    private volatile long lastSleepCardsMessageUntilMs = 0L;

    public AnkiManager() {
        this.config = AnkiCraftConfig.load();
        this.ankiClient = new AnkiConnectClient(config);
        this.sleepCardsClient = new SleepCardsClient(
                config,
                this::showSleepCardsMessage,
                this::handleSleepCardsCommands,
                this::handleSleepCardsReply
        );
        this.cache = CardCache.load();
        this.latexRenderer = new LatexRenderer();
        this.contentRenderer = new CardContentRenderer(latexRenderer, ankiClient);
        this.audioPlayer = new AudioPlayer(ankiClient);

        if (!cache.decks.isEmpty()) {
            this.decks = new ArrayList<>(cache.decks);
        }
        if (!cache.cards.isEmpty()) {
            List<AnkiCard> copy = new ArrayList<>(cache.cards);
            Collections.shuffle(copy);
            cardQueue.addAll(copy);
        }

        this.sleepCardsClient.start();
    }

    public static AnkiManager getInstance() {
        if (instance == null) {
            instance = new AnkiManager();
        }
        return instance;
    }

    public AnkiCraftConfig getConfig() {
        return config;
    }

    public SleepCardsClient getSleepCardsClient() {
        return sleepCardsClient;
    }

    public CardContentRenderer getContentRenderer() {
        return contentRenderer;
    }

    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public record DueSummary(int newCount, int learnCount, int reviewCount) {
        public int total() {
            return newCount + learnCount + reviewCount;
        }
    }

    public List<AnkiDeck> getDecks() {
        return decks;
    }

    public DueSummary getDueSummary() {
        List<AnkiDeck> activeDecks = getStudyDecks();
        int n = 0;
        int l = 0;
        int r = 0;
        for (AnkiDeck d : activeDecks) {
            n += Math.max(0, d.newCount);
            l += Math.max(0, d.learnCount);
            r += Math.max(0, d.reviewCount);
        }
        if (n + l + r == 0 && !cardQueue.isEmpty()) {
            r = cardQueue.size();
        }
        return new DueSummary(n, l, r);
    }

    public boolean isAnkiConnected() {
        return ankiConnected;
    }

    public boolean isSyncing() {
        return syncing;
    }

    public boolean isServerSupportsAnkiCraft() {
        return serverSupportsAnkiCraft;
    }

    public void setServerSupportsAnkiCraft(boolean supported) {
        this.serverSupportsAnkiCraft = supported;
    }

    public int getStreak() {
        return streak;
    }

    public int getReviewedToday() {
        return reviewedToday;
    }

    public int getQueuedCount() {
        return cardQueue.size();
    }

    public int getPendingReviewCount() {
        return cache.pendingReviews.size();
    }

    public CompletableFuture<Boolean> syncNow() {
        if (syncing) {
            return CompletableFuture.completedFuture(ankiConnected);
        }
        syncing = true;

        return ankiClient.testConnection().thenCompose(connected -> {
            this.ankiConnected = connected;
            if (!connected) {
                syncing = false;
                return CompletableFuture.completedFuture(false);
            }

            return flushPendingReviews()
                    .thenCompose(v -> config.autoSyncAnkiWeb
                            ? ankiClient.syncAnkiWeb().thenApply(ok -> (Void) null)
                            : CompletableFuture.completedFuture(null))
                    .thenCompose(v -> ankiClient.fetchDecks())
                    .thenCompose(fetchedDecks -> {
                        this.decks = new ArrayList<>(fetchedDecks);
                        cache.decks = new ArrayList<>(fetchedDecks);
                        return ankiClient.fetchDueCards(config.selectedDecks, 60);
                    })
                    .thenCompose(fetchedCards -> ankiClient.fetchNextScheduledCard(getStudyDecks(), currentStudyDeck)
                            .thenApply(scheduled -> {
                                for (AnkiCard card : fetchedCards) {
                                    if (card.deckId == 0L) {
                                        card.deckId = resolveDeckId(card.deckName);
                                    }
                                }
                                List<AnkiCard> shuffled = new ArrayList<>(fetchedCards);
                                Collections.shuffle(shuffled);
                                synchronized (cardQueue) {
                                    cardQueue.clear();
                                    if (scheduled != null && scheduled.card() != null) {
                                        currentStudyDeck = scheduled.studyDeck();
                                        AnkiCard head = scheduled.card();
                                        if (head.deckId == 0L) {
                                            head.deckId = resolveDeckId(head.deckName);
                                        }
                                        shuffled.removeIf(c -> c.cardId == head.cardId);
                                        cardQueue.addFirst(head);
                                    }
                                    cardQueue.addAll(shuffled);
                                }
                                cache.cards = new ArrayList<>(fetchedCards);
                                cache.lastSyncMs = System.currentTimeMillis();
                                cache.save();
                                syncing = false;
                                return true;
                            }))
                    .exceptionally(ex -> {
                        AnkiCraftMod.LOGGER.warn("AnkiCraft sync failed", ex);
                        syncing = false;
                        return false;
                    });
        });
    }

    private CompletableFuture<Void> flushPendingReviews() {
        List<PendingReview> toFlush;
        synchronized (cache) {
            if (cache.pendingReviews.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            toFlush = new ArrayList<>(cache.pendingReviews);
        }

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        List<PendingReview> succeeded = Collections.synchronizedList(new ArrayList<>());
        for (PendingReview pr : toFlush) {
            chain = chain.thenCompose(v -> ankiClient.answerCard(pr.cardId, pr.ease).thenAccept(ok -> {
                if (ok) succeeded.add(pr);
            }));
        }
        return chain.thenRun(() -> {
            if (!succeeded.isEmpty()) {
                synchronized (cache) {
                    cache.pendingReviews.removeAll(succeeded);
                    cache.save();
                }
            }
        });
    }

    public AnkiCard pollNextCard() {
        synchronized (cardQueue) {
            return cardQueue.pollFirst();
        }
    }

    public void requeueCard(AnkiCard card) {
        if (card == null) return;
        synchronized (cardQueue) {
            cardQueue.addLast(card);
        }
    }

    public void pushCardToFront(AnkiCard card) {
        if (card == null) return;
        synchronized (cardQueue) {
            cardQueue.addFirst(card);
        }
    }

    public void undoLastReview(AnkiCard card, int previousStreak, int previousReviewedToday) {
        this.streak = Math.max(0, previousStreak);
        this.reviewedToday = Math.max(0, previousReviewedToday);
        if (card != null) {
            synchronized (cache) {
                cache.pendingReviews.removeIf(pr -> pr.cardId == card.cardId);
                if (cache.cards.stream().noneMatch(c -> c.cardId == card.cardId)) {
                    cache.cards.add(card);
                }
                cache.save();
            }
        }
    }

    public void submitAnswer(AnkiCard card, int ease) {
        if (card == null) return;
        reviewedToday++;

        if (ease >= 3) {
            streak++;
        } else if (ease == 1) {
            streak = 0;
            requeueCard(card);
        }

        // Remove from offline cache list if not 'Again', and always persist cache
        synchronized (cache) {
            if (ease > 1) {
                cache.cards.removeIf(c -> c.cardId == card.cardId);
            }
            cache.save();
        }
        updateLocalDeckCounts(card, ease);

        // Send to AnkiConnect or queue offline
        if (ankiConnected) {
            ankiClient.answerCard(card.cardId, ease).thenAccept(ok -> {
                if (!ok) {
                    queueOfflineReview(card.cardId, ease);
                } else if (Boolean.TRUE.equals(ankiClient.getSchedulerApiSupported())) {
                    ankiClient.fetchNextScheduledCard(getStudyDecks(), currentStudyDeck).thenAccept(scheduled -> {
                        if (scheduled != null && scheduled.card() != null) {
                            currentStudyDeck = scheduled.studyDeck();
                            AnkiCard next = scheduled.card();
                            if (next.deckId == 0L) {
                                next.deckId = resolveDeckId(next.deckName);
                            }
                            synchronized (cardQueue) {
                                cardQueue.removeIf(c -> c.cardId == next.cardId);
                                cardQueue.addFirst(next);
                            }
                        }
                    });
                }
            });
        } else {
            queueOfflineReview(card.cardId, ease);
        }

        // Report answer event to SleepCards private server
        long deckId = card.deckId != 0L ? card.deckId : resolveDeckId(card.deckName);
        sleepCardsClient.enqueueAnswer(card.cardId, card.noteId, deckId, ease, card.cardType);

        // Dispatch rewards / feedback
        Minecraft mc = Minecraft.getInstance();
        if (serverSupportsAnkiCraft && ClientPlayNetworking.canSend(QuizResultPayload.TYPE)) {
            ClientPlayNetworking.send(new QuizResultPayload(
                    ease,
                    streak,
                    config.rewardsEnabled,
                    config.penaltiesEnabled
            ));
        } else if (mc.player != null) {
            // Pure Vanilla server / client-only feedback
            if (ease >= 3) {
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.45f, 1.2f);
                mc.player.sendOverlayMessage(
                        Component.literal("✓ AnkiCraft: Streak " + streak + "!")
                                .withStyle(ChatFormatting.GREEN)
                );
            } else if (ease == 1) {
                mc.player.sendOverlayMessage(
                        Component.literal("↻ Karte wiederholt (Streak zurückgesetzt)")
                                .withStyle(ChatFormatting.GOLD)
                );
            }
        }
    }

    private void queueOfflineReview(long cardId, int ease) {
        synchronized (cache) {
            cache.pendingReviews.add(new PendingReview(cardId, ease, System.currentTimeMillis()));
            cache.save();
        }
    }

    private void updateLocalDeckCounts(AnkiCard card, int ease) {
        if (card == null || card.deckName == null) {
            return;
        }
        for (AnkiDeck d : decks) {
            if (card.deckName.equals(d.name) || card.deckName.startsWith(d.name + "::")) {
                if (ease == 1) {
                    if (card.cardType == 0 && d.newCount > 0) {
                        d.newCount--;
                        d.learnCount++;
                    } else if (card.cardType == 2 && d.reviewCount > 0) {
                        d.reviewCount--;
                        d.learnCount++;
                    }
                } else {
                    if (card.cardType == 0) {
                        if (d.newCount > 0) d.newCount--;
                    } else if (card.cardType == 1 || card.cardType == 3) {
                        if (d.learnCount > 0) d.learnCount--;
                    } else {
                        if (d.reviewCount > 0) d.reviewCount--;
                    }
                }
            }
        }
    }

    private List<AnkiDeck> getStudyDecks() {
        List<AnkiDeck> result = new ArrayList<>();
        if (config.selectedDecks.isEmpty()) {
            for (AnkiDeck d : decks) {
                if (!d.name.contains("::")) {
                    result.add(d);
                }
            }
            return result.isEmpty() ? new ArrayList<>(decks) : result;
        }
        for (AnkiDeck d : decks) {
            if (config.selectedDecks.contains(d.name) && !hasSelectedAncestor(d.name)) {
                result.add(d);
            }
        }
        return result;
    }

    private boolean hasSelectedAncestor(String deckName) {
        int idx = 0;
        while ((idx = deckName.indexOf("::", idx)) != -1) {
            if (config.selectedDecks.contains(deckName.substring(0, idx))) {
                return true;
            }
            idx += 2;
        }
        return false;
    }

    private long resolveDeckId(String deckName) {
        if (deckName == null || deckName.isEmpty()) {
            return 0L;
        }
        for (AnkiDeck d : decks) {
            if (deckName.equals(d.name)) {
                return d.id;
            }
        }
        return 0L;
    }

    private void showSleepCardsMessage(String msg) {
        if (msg == null || msg.isBlank() || !config.sleepCardsShowMessages) {
            return;
        }
        this.lastSleepCardsMessage = msg;
        this.lastSleepCardsMessageUntilMs = System.currentTimeMillis() + 5000L;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendOverlayMessage(
                        Component.literal("[SleepCards] " + msg).withStyle(ChatFormatting.AQUA)
                );
            }
        });
    }

    public String getActiveSleepCardsMessage() {
        if (!lastSleepCardsMessage.isEmpty() && System.currentTimeMillis() < lastSleepCardsMessageUntilMs) {
            return lastSleepCardsMessage;
        }
        return "";
    }

    private void handleSleepCardsCommands(JsonArray commands) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            for (JsonElement el : commands) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                String kind = c.has("type") && c.get("type").isJsonPrimitive()
                        ? c.get("type").getAsString()
                        : null;

                if ("focus".equals(kind)) {
                    sleepCardsChallengeUntilMs = System.currentTimeMillis() + 300_000L;
                    reportSleepCardsStatus();
                    boolean startReview = !c.has("start_review") || c.get("start_review").getAsBoolean();
                    if (startReview && mc.player != null && !(mc.gui.screen() instanceof QuizScreen)) {
                        if (mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen) {
                            pendingChallengeFocus = true;
                        } else {
                            openServerChallengeQuiz(mc);
                        }
                    }
                } else if ("close".equals(kind) || "unfocus".equals(kind) || "done".equals(kind)) {
                    sleepCardsChallengeUntilMs = 0L;
                    pendingChallengeFocus = false;
                    if (mc.gui.screen() instanceof QuizScreen qs) {
                        qs.onClose();
                    }
                }

                if (c.has("text") && c.get("text").isJsonPrimitive()) {
                    showSleepCardsMessage(c.get("text").getAsString());
                }
            }
        });
    }

    public void flushAndSaveBeforeClose() {
        synchronized (cache) {
            cache.save();
        }
        config.save();
        sleepCardsClient.wakeWorker();
        if (ankiConnected) {
            flushPendingReviews()
                    .thenCompose(v -> config.autoSyncAnkiWeb
                            ? ankiClient.syncAnkiWeb()
                            : CompletableFuture.completedFuture(false))
                    .thenCompose(v -> ankiClient.fetchDecks())
                    .thenAccept(fetchedDecks -> {
                        if (fetchedDecks != null && !fetchedDecks.isEmpty()) {
                            this.decks = new ArrayList<>(fetchedDecks);
                            cache.decks = new ArrayList<>(fetchedDecks);
                            cache.save();
                        }
                    });
        }
    }

    public boolean consumePendingChallengeFocus() {
        if (pendingChallengeFocus) {
            pendingChallengeFocus = false;
            return true;
        }
        return false;
    }

    public void openServerChallengeQuiz(Minecraft mc) {
        boolean needSync = getQueuedCount() == 0;
        mc.gui.setScreen(new QuizScreen(true, null));
        if (needSync) {
            syncNow().thenRun(() -> mc.execute(() -> {
                if (mc.gui.screen() instanceof QuizScreen qs) {
                    qs.reloadAfterDeckChange();
                }
            }));
        }
    }

    public boolean isLearningActive() {
        return learningActive;
    }

    public void reportLearningState(boolean active) {
        if (this.learningActive == active) {
            return;
        }
        this.learningActive = active;
        if (!config.sleepCardsEnabled || config.sleepCardsToken == null || config.sleepCardsToken.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null
                    && mc.player.connection != null
                    && !mc.player.isDeadOrDying()
                    && mc.options.chatVisibility().get() != ChatVisiblity.HIDDEN) {
                mc.player.connection.sendCommand(active ? "anki lernen" : "anki fertig");
            }
        });
    }

    public void resetLearningState() {
        this.learningActive = false;
        this.sleepCardsChallengeUntilMs = 0L;
        this.pendingChallengeFocus = false;
    }

    public void reportNoDueCards() {
        if (!config.sleepCardsEnabled || config.sleepCardsToken == null || config.sleepCardsToken.isBlank()) {
            return;
        }
        sleepCardsClient.enqueueStatus(0, resolveReportedDeckName());
    }

    private String resolveReportedDeckName() {
        String targetDeck = config.sleepCardsDeck != null ? config.sleepCardsDeck.trim() : "";
        if (!targetDeck.isEmpty()) {
            for (AnkiDeck d : decks) {
                if (d.name.equals(targetDeck) || d.name.startsWith(targetDeck + "::")) {
                    return d.name;
                }
            }
        }
        if (!config.selectedDecks.isEmpty()) {
            return config.selectedDecks.get(0);
        }
        if (!decks.isEmpty()) {
            return decks.get(0).name;
        }
        return targetDeck.isEmpty() ? "Default" : targetDeck;
    }

    private void reportSleepCardsStatus() {
        String targetDeck = config.sleepCardsDeck != null ? config.sleepCardsDeck.trim() : "";
        String reportedDeck = "";
        int due = 0;

        if (!targetDeck.isEmpty()) {
            for (AnkiDeck d : decks) {
                if (d.name.equals(targetDeck) || d.name.startsWith(targetDeck + "::")) {
                    due += d.totalDue();
                    if (reportedDeck.isEmpty()) {
                        reportedDeck = d.name;
                    }
                }
            }
        }

        if (reportedDeck.isEmpty()) {
            if (!config.selectedDecks.isEmpty()) {
                for (AnkiDeck d : decks) {
                    if (config.selectedDecks.contains(d.name)) {
                        due += d.totalDue();
                    }
                }
                reportedDeck = config.selectedDecks.get(0);
            } else if (!decks.isEmpty()) {
                for (AnkiDeck d : decks) {
                    due += d.totalDue();
                }
                reportedDeck = decks.get(0).name;
            } else {
                due = getQueuedCount();
                reportedDeck = targetDeck.isEmpty() ? "Default" : targetDeck;
            }
        }

        sleepCardsClient.enqueueStatus(due, reportedDeck);
    }

    private void handleSleepCardsReply(JsonObject reply) {
        if (System.currentTimeMillis() > sleepCardsChallengeUntilMs) {
            return;
        }
        Boolean finished = null;
        if (reply.has("done") && reply.get("done").isJsonPrimitive()) {
            finished = reply.get("done").getAsBoolean();
        } else if (reply.has("remaining") && reply.get("remaining").isJsonPrimitive()) {
            try {
                finished = reply.get("remaining").getAsInt() <= 0;
            } catch (Exception ignored) {
            }
        } else if (reply.has("message") && reply.get("message").isJsonPrimitive()) {
            Matcher m = PROGRESS_PATTERN.matcher(reply.get("message").getAsString());
            if (m.find()) {
                try {
                    int doneCount = Integer.parseInt(m.group(1));
                    int totalCount = Integer.parseInt(m.group(2));
                    if (totalCount > 0) {
                        finished = doneCount >= totalCount;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (finished == null) {
            return;
        }

        final boolean isDone = finished;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (isDone) {
                sleepCardsChallengeUntilMs = 0L;
                pendingChallengeFocus = false;
                if (mc.gui.screen() instanceof QuizScreen qs) {
                    qs.onClose();
                }
            } else if (mc.player != null && !(mc.gui.screen() instanceof QuizScreen)) {
                if (mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen) {
                    pendingChallengeFocus = true;
                } else {
                    openServerChallengeQuiz(mc);
                }
            }
        });
    }
}
