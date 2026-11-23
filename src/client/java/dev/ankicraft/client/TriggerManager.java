package dev.ankicraft.client;

import dev.ankicraft.client.config.AnkiCraftConfig;
import dev.ankicraft.client.ui.QuizScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

public class TriggerManager {
    private static TriggerManager instance;

    private int tickCounter = 0;
    private int autoSyncTickCounter = 0;
    private int blocksBrokenSinceQuiz = 0;
    private long lastQuizMs = 0L;

    public static TriggerManager getInstance() {
        if (instance == null) {
            instance = new TriggerManager();
        }
        return instance;
    }

    public void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        AnkiManager manager = AnkiManager.getInstance();
        AnkiCraftConfig cfg = manager.getConfig();

        // Auto-sync periodically
        autoSyncTickCounter++;
        int syncTicks = Math.max(1, cfg.autoSyncMinutes) * 60 * 20;
        if (autoSyncTickCounter >= syncTicks) {
            autoSyncTickCounter = 0;
            manager.syncNow();
        }

        // Don't interrupt if a screen is already open
        if (mc.gui.screen() != null) {
            return;
        }

        if (cfg.timerEnabled) {
            tickCounter++;
            if (tickCounter >= cfg.quizIntervalSeconds * 20) {
                if (isSafeToQuiz(mc.player)) {
                    openQuiz(mc, cfg.gatekeepingEnabled, true, null);
                } else {
                    // Retry in 5 seconds if player is currently hurt/busy
                    tickCounter -= 100;
                }
            }
        }
    }

    public void onBlockBroken(Minecraft mc) {
        AnkiCraftConfig cfg = AnkiManager.getInstance().getConfig();
        if (!cfg.blockBreakTriggerEnabled || mc.gui.screen() != null) {
            return;
        }
        blocksBrokenSinceQuiz++;
        if (blocksBrokenSinceQuiz >= cfg.blocksPerQuiz) {
            blocksBrokenSinceQuiz = 0;
            openQuiz(mc, cfg.gatekeepingEnabled, true, null);
        }
    }

    public boolean onBedInteract(Minecraft mc) {
        AnkiCraftConfig cfg = AnkiManager.getInstance().getConfig();
        if (!cfg.sleepTriggerEnabled || mc.gui.screen() != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastQuizMs < 15_000L) {
            return false;
        }
        if (AnkiManager.getInstance().getQueuedCount() == 0) {
            return false;
        }
        openQuiz(mc, cfg.gatekeepingEnabled, true, null);
        return cfg.gatekeepingEnabled;
    }

    public void openManualQuiz(Minecraft mc) {
        AnkiCraftConfig cfg = AnkiManager.getInstance().getConfig();
        openQuiz(mc, false, !cfg.manualQuizDoesNotResetTimer, null);
    }

    public void openQuiz(Minecraft mc, boolean gatekeep, boolean resetTimer, Runnable onPassed) {
        if (resetTimer) {
            this.tickCounter = 0;
            this.blocksBrokenSinceQuiz = 0;
            this.lastQuizMs = System.currentTimeMillis();
        }
        mc.gui.setScreen(new QuizScreen(gatekeep, onPassed));
    }

    public int getSecondsUntilNextQuiz() {
        AnkiCraftConfig cfg = AnkiManager.getInstance().getConfig();
        int totalTicks = cfg.quizIntervalSeconds * 20;
        int remaining = Math.max(0, totalTicks - tickCounter);
        return remaining / 20;
    }

    public void resetTimers() {
        tickCounter = 0;
        blocksBrokenSinceQuiz = 0;
    }

    private static boolean isSafeToQuiz(Player player) {
        return player.hurtTime == 0 && !player.isUnderWater();
    }
}
