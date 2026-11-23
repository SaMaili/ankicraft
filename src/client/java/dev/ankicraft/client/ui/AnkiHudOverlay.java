package dev.ankicraft.client.ui;

import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.TriggerManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class AnkiHudOverlay {
    private AnkiHudOverlay() {}

    public static void render(GuiGraphicsExtractor gfx, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) {
            return;
        }
        AnkiManager manager = AnkiManager.getInstance();
        if (!manager.getConfig().showHudOverlay) {
            return;
        }

        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        String deckSummary = manager.getConfig().selectedDecks.isEmpty()
                ? "Alle Decks"
                : manager.getConfig().selectedDecks.size() + " Deck(s)";
        String timerStr = manager.getConfig().timerEnabled
                ? " | " + formatSec(TriggerManager.getInstance().getSecondsUntilNextQuiz())
                : "";
        String text = "Anki: " + deckSummary + " | Streak " + manager.getStreak() + timerStr;

        int textW = font.width(text);
        int boxW = textW + 22;
        int boxH = 18;
        int x = screenW - boxW - 8;
        int y = 8;

        gfx.fill(x, y, x + boxW, y + boxH, 0xAA0F172A);
        gfx.fill(x, y, x + boxW, y + 1, 0xFF334155);
        gfx.fill(x, y + boxH - 1, x + boxW, y + boxH, 0xFF334155);

        int dotColor = manager.isAnkiConnected() ? 0xFF10B981 : 0xFFF59E0B;
        gfx.fill(x + 6, y + 6, x + 11, y + 11, dotColor);
        gfx.text(font, text, x + 15, y + 5, 0xFFF1F5F9, false);
    }

    private static String formatSec(int s) {
        int m = s / 60;
        int rem = s % 60;
        return String.format("%d:%02d", m, rem);
    }
}
