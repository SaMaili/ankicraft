package dev.ankicraft.client;

import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.anki.AnkiConnectClient;
import dev.ankicraft.client.anki.AnkiConnectClient.ScheduledCard;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class AnkiManager {
    private static AnkiManager instance;

    private final AnkiCraftConfig config;
    private final AnkiConnectClient ankiClient;
    private final CardCache cache;
    private final LatexRenderer latexRenderer;
    private final CardContentRenderer contentRenderer;

    private volatile List<AnkiDeck> decks = List.of();
    private volatile CompletableFuture<Boolean> deckSync = CompletableFuture.completedFuture(false);
    private CompletableFuture<Void> schedulerOperation = CompletableFuture.completedFuture(null);
    private volatile boolean ankiConnected = false;
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
        if (!cache.pendingReviews.isEmpty()) {
            AnkiCraftMod.LOGGER.warn("{} legacy offline ratings are preserved in the cache but require manual reconciliation.",
                    cache.pendingReviews.size());
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
        return !deckSync.isDone();
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

    public synchronized CompletableFuture<Boolean> syncNow() {
        if (isSyncing()) return deckSync;
        deckSync = ankiClient.fetchDecks().thenApply(fetchedDecks -> {
            decks = List.copyOf(fetchedDecks);
            cache.decks = decks;
            cache.lastSyncMs = System.currentTimeMillis();
            cache.save();
            ankiConnected = true;
            return true;
        }).exceptionally(ex -> {
            ankiConnected = false;
            AnkiCraftMod.LOGGER.warn("AnkiCraft deck sync failed", ex);
            return false;
        });
        return deckSync;
    }

    public CompletableFuture<ScheduledCard> fetchNextCard() {
        List<String> selected = List.copyOf(config.selectedDecks);
        return runSchedulerOperation(() -> {
            CompletableFuture<Boolean> ready = decks.isEmpty()
                    ? syncNow() : CompletableFuture.completedFuture(true);
            return ready.thenCompose(connected -> {
                if (!connected) {
                    return CompletableFuture.failedFuture(new IllegalStateException("AnkiConnect is unavailable."));
                }
                return ankiClient.fetchNextCard(studyDecks(decks, selected));
            }).whenComplete((card, error) -> ankiConnected = error == null);
        });
    }

    static List<AnkiDeck> studyDecks(List<AnkiDeck> decks, List<String> selected) {
        // A parent deck's native queue already applies its settings to its subdecks.
        List<AnkiDeck> candidates = decks.stream()
                .filter(deck -> selected.isEmpty() || selected.contains(deck.name))
                .toList();
        return candidates.stream()
                .filter(deck -> candidates.stream().noneMatch(parent -> deck.name.startsWith(parent.name + "::")))
                .toList();
    }

    public CompletableFuture<Void> submitAnswer(ScheduledCard card, int ease) {
        return runSchedulerOperation(() -> ankiClient.answerCard(card, ease));
    }

    private synchronized <T> CompletableFuture<T> runSchedulerOperation(Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> next = schedulerOperation.thenCompose(v -> operation.get());
        schedulerOperation = next.handle((result, error) -> null);
        return next;
    }

    public void recordAcceptedAnswer(int ease) {
        reviewedToday++;
        if (ease >= 3) {
            streak++;
        } else if (ease == 1) {
            streak = 0;
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

}
