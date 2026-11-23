package dev.ankicraft.client;

import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.anki.AnkiConnectClient;
import dev.ankicraft.client.anki.AnkiModels.AnkiCard;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import dev.ankicraft.client.anki.AnkiModels.PendingReview;
import dev.ankicraft.client.anki.CardCache;
import dev.ankicraft.client.config.AnkiCraftConfig;
import dev.ankicraft.client.render.CardContentRenderer;
import dev.ankicraft.client.render.LatexRenderer;
import dev.ankicraft.network.AnkiCraftNetwork.QuizResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AnkiManager {
    private static AnkiManager instance;

    private final AnkiCraftConfig config;
    private final AnkiConnectClient ankiClient;
    private final CardCache cache;
    private final LatexRenderer latexRenderer;
    private final CardContentRenderer contentRenderer;

    private final Deque<AnkiCard> cardQueue = new ArrayDeque<>();
    private List<AnkiDeck> decks = new ArrayList<>();

    private boolean ankiConnected = false;
    private boolean syncing = false;
    private boolean serverSupportsAnkiCraft = false;

    private int streak = 0;
    private int reviewedToday = 0;

    public AnkiManager() {
        this.config = AnkiCraftConfig.load();
        this.ankiClient = new AnkiConnectClient(config);
        this.cache = CardCache.load();
        this.latexRenderer = new LatexRenderer();
        this.contentRenderer = new CardContentRenderer(latexRenderer, ankiClient);

        if (!cache.decks.isEmpty()) {
            this.decks = new ArrayList<>(cache.decks);
        }
        if (!cache.cards.isEmpty()) {
            List<AnkiCard> copy = new ArrayList<>(cache.cards);
            Collections.shuffle(copy);
            cardQueue.addAll(copy);
        }
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

    public CardContentRenderer getContentRenderer() {
        return contentRenderer;
    }

    public List<AnkiDeck> getDecks() {
        return decks;
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
                    .thenCompose(v -> ankiClient.fetchDecks())
                    .thenCompose(fetchedDecks -> {
                        this.decks = new ArrayList<>(fetchedDecks);
                        cache.decks = new ArrayList<>(fetchedDecks);
                        return ankiClient.fetchDueCards(config.selectedDecks, 60);
                    })
                    .thenApply(fetchedCards -> {
                        List<AnkiCard> shuffled = new ArrayList<>(fetchedCards);
                        Collections.shuffle(shuffled);
                        synchronized (cardQueue) {
                            cardQueue.clear();
                            cardQueue.addAll(shuffled);
                        }
                        cache.cards = new ArrayList<>(fetchedCards);
                        cache.lastSyncMs = System.currentTimeMillis();
                        cache.save();
                        syncing = false;
                        return true;
                    })
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

    public void submitAnswer(AnkiCard card, int ease) {
        if (card == null) return;
        reviewedToday++;

        if (ease >= 3) {
            streak++;
        } else if (ease == 1) {
            streak = 0;
            requeueCard(card);
        }

        // Remove from offline cache list if not 'Again'
        if (ease > 1) {
            synchronized (cache) {
                cache.cards.removeIf(c -> c.cardId == card.cardId);
                cache.save();
            }
        }

        // Send to AnkiConnect or queue offline
        if (ankiConnected) {
            ankiClient.answerCard(card.cardId, ease).thenAccept(ok -> {
                if (!ok) {
                    queueOfflineReview(card.cardId, ease);
                }
            });
        } else {
            queueOfflineReview(card.cardId, ease);
        }

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
}
