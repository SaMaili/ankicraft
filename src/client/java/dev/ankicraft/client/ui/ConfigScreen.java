package dev.ankicraft.client.ui;

import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.config.AnkiCraftConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ConfigScreen extends Screen {
    private static final int BG_CARD = 0xEE0F172A;
    private static final int BORDER_COLOR = 0xFF334155;

    private final Screen parent;
    private final AnkiCraftConfig config;
    private EditBox urlBox;
    private int cardX, cardY, cardW, cardH;

    public ConfigScreen(Screen parent) {
        super(Component.literal("AnkiCraft – Einstellungen"));
        this.parent = parent;
        this.config = AnkiManager.getInstance().getConfig();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        this.cardW = Math.min(480, this.width - 32);
        this.cardH = Math.min(368, this.height - 28);
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = (this.height - this.cardH) / 2;

        urlBox = new EditBox(font, cardX + 16, cardY + 46, cardW - 32, 20, Component.literal("AnkiConnect URL"));
        urlBox.setValue(config.ankiConnectUrl);
        urlBox.setResponder(v -> config.ankiConnectUrl = v);
        addRenderableWidget(urlBox);

        int colW = (cardW - 44) / 2;
        int leftX = cardX + 16;
        int rightX = cardX + 28 + colW;
        int y = cardY + 78;
        int step = 26;

        addRenderableWidget(Button.builder(Component.literal("Timer-Quiz: " + onOff(config.timerEnabled)), btn -> {
            config.timerEnabled = !config.timerEnabled;
            btn.setMessage(Component.literal("Timer-Quiz: " + onOff(config.timerEnabled)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Intervall: " + formatInterval(config.quizIntervalSeconds)), btn -> {
            config.quizIntervalSeconds = nextInterval(config.quizIntervalSeconds);
            btn.setMessage(Component.literal("Intervall: " + formatInterval(config.quizIntervalSeconds)));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Block-Trigger: " + onOff(config.blockBreakTriggerEnabled)), btn -> {
            config.blockBreakTriggerEnabled = !config.blockBreakTriggerEnabled;
            btn.setMessage(Component.literal("Block-Trigger: " + onOff(config.blockBreakTriggerEnabled)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Blöcke/Quiz: " + config.blocksPerQuiz), btn -> {
            config.blocksPerQuiz = nextBlocks(config.blocksPerQuiz);
            btn.setMessage(Component.literal("Blöcke/Quiz: " + config.blocksPerQuiz));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Bett-/Schlaf-Trigger: " + onOff(config.sleepTriggerEnabled)), btn -> {
            config.sleepTriggerEnabled = !config.sleepTriggerEnabled;
            btn.setMessage(Component.literal("Bett-/Schlaf-Trigger: " + onOff(config.sleepTriggerEnabled)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Singleplayer pausieren: " + onOff(config.pauseInSingleplayer)), btn -> {
            config.pauseInSingleplayer = !config.pauseInSingleplayer;
            btn.setMessage(Component.literal("Singleplayer pausieren: " + onOff(config.pauseInSingleplayer)));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Belohnungen (XP/Buffs): " + onOff(config.rewardsEnabled)), btn -> {
            config.rewardsEnabled = !config.rewardsEnabled;
            btn.setMessage(Component.literal("Belohnungen (XP/Buffs): " + onOff(config.rewardsEnabled)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Strafen (Nochmal): " + onOff(config.penaltiesEnabled)), btn -> {
            config.penaltiesEnabled = !config.penaltiesEnabled;
            btn.setMessage(Component.literal("Strafen (Nochmal): " + onOff(config.penaltiesEnabled)));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Gatekeeping-Modus: " + onOff(config.gatekeepingEnabled)), btn -> {
            config.gatekeepingEnabled = !config.gatekeepingEnabled;
            btn.setMessage(Component.literal("Gatekeeping-Modus: " + onOff(config.gatekeepingEnabled)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("HUD-Overlay: " + onOff(config.showHudOverlay)), btn -> {
            config.showHudOverlay = !config.showHudOverlay;
            btn.setMessage(Component.literal("HUD-Overlay: " + onOff(config.showHudOverlay)));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("LaTeX-Größe: " + String.format("%.2fx", config.latexScale)), btn -> {
            config.latexScale = nextScale(config.latexScale);
            btn.setMessage(Component.literal("LaTeX-Größe: " + String.format("%.2fx", config.latexScale)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Timer bei [K] behalten: " + onOff(config.manualQuizDoesNotResetTimer)), btn -> {
            config.manualQuizDoesNotResetTimer = !config.manualQuizDoesNotResetTimer;
            btn.setMessage(Component.literal("Timer bei [K] behalten: " + onOff(config.manualQuizDoesNotResetTimer)));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Decks auswählen..."), btn ->
                this.minecraft.gui.setScreen(new DeckSelectScreen(this))
        ).bounds(leftX, y, cardW - 32, 20).build());

        int footerY = cardY + cardH - 32;
        addRenderableWidget(Button.builder(Component.literal("Speichern & Zurück"), btn -> {
            config.save();
            onClose();
        }).bounds(cardX + cardW / 2 - 75, footerY, 150, 22).build());
    }

    @Override
    public void onClose() {
        config.save();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(cardX, cardY, cardX + cardW, cardY + cardH, BG_CARD);
        gfx.fill(cardX, cardY, cardX + cardW, cardY + 1, BORDER_COLOR);
        gfx.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, BORDER_COLOR);
        gfx.fill(cardX, cardY, cardX + 1, cardY + cardH, BORDER_COLOR);
        gfx.fill(cardX + cardW - 1, cardY, cardX + cardW, cardY + cardH, BORDER_COLOR);

        gfx.text(font, "AnkiCraft Einstellungen", cardX + 16, cardY + 12, 0xFFF8FAFC, false);
        gfx.text(font, "AnkiConnect URL:", cardX + 16, cardY + 34, 0xFF94A3B8, false);

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }

    private static String onOff(boolean v) {
        return v ? "AN" : "AUS";
    }

    private static String formatInterval(int sec) {
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m";
    }

    private static int nextInterval(int cur) {
        int[] vals = {60, 120, 180, 300, 600, 900};
        for (int v : vals) {
            if (v > cur) return v;
        }
        return vals[0];
    }

    private static int nextBlocks(int cur) {
        int[] vals = {16, 32, 64, 128};
        for (int v : vals) {
            if (v > cur) return v;
        }
        return vals[0];
    }

    private static float nextScale(float cur) {
        if (cur < 0.9f) return 1.0f;
        if (cur < 1.15f) return 1.25f;
        if (cur < 1.4f) return 1.5f;
        return 0.8f;
    }
}
