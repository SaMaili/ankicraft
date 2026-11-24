package dev.ankicraft.client.ui;

import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.anki.SleepCardsClient;
import dev.ankicraft.client.config.AnkiCraftConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SleepCardsConfigScreen extends Screen {
    private static final int BG_CARD = 0xEE0F172A;
    private static final int BORDER_COLOR = 0xFF334155;

    private final Screen parent;
    private final AnkiCraftConfig config;
    private EditBox serverUrlBox;
    private EditBox tokenBox;
    private EditBox deckBox;
    private int cardX, cardY, cardW, cardH;

    public SleepCardsConfigScreen(Screen parent) {
        super(Component.literal("SleepCards & Auto-Sync"));
        this.parent = parent;
        this.config = AnkiManager.getInstance().getConfig();
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        this.cardW = Math.min(480, this.width - 32);
        this.cardH = Math.min(366, this.height - 28);
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = (this.height - this.cardH) / 2;

        int leftX = cardX + 16;
        int fullW = cardW - 32;
        int colW = (cardW - 44) / 2;
        int rightX = cardX + 28 + colW;

        serverUrlBox = new EditBox(font, leftX, cardY + 46, fullW, 20, Component.literal("Server URL"));
        serverUrlBox.setMaxLength(256);
        serverUrlBox.setValue(config.sleepCardsServerUrl);
        serverUrlBox.setResponder(v -> config.sleepCardsServerUrl = v);
        addRenderableWidget(serverUrlBox);

        tokenBox = new EditBox(font, leftX, cardY + 86, fullW, 20, Component.literal("Token"));
        tokenBox.setMaxLength(256);
        tokenBox.setValue(config.sleepCardsToken);
        tokenBox.setResponder(v -> config.sleepCardsToken = v);
        addRenderableWidget(tokenBox);

        deckBox = new EditBox(font, leftX, cardY + 126, fullW, 20, Component.literal("Standard-Deck"));
        deckBox.setMaxLength(128);
        deckBox.setValue(config.sleepCardsDeck);
        deckBox.setResponder(v -> config.sleepCardsDeck = v);
        addRenderableWidget(deckBox);

        int y = cardY + 158;
        int step = 26;

        addRenderableWidget(Button.builder(Component.literal("SleepCards: " + onOff(config.sleepCardsEnabled)), btn -> {
            config.sleepCardsEnabled = !config.sleepCardsEnabled;
            btn.setMessage(Component.literal("SleepCards: " + onOff(config.sleepCardsEnabled)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Server-Hinweise: " + onOff(config.sleepCardsShowMessages)), btn -> {
            config.sleepCardsShowMessages = !config.sleepCardsShowMessages;
            btn.setMessage(Component.literal("Server-Hinweise: " + onOff(config.sleepCardsShowMessages)));
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("AnkiWeb Auto-Sync: " + onOff(config.autoSyncAnkiWeb)), btn -> {
            config.autoSyncAnkiWeb = !config.autoSyncAnkiWeb;
            btn.setMessage(Component.literal("AnkiWeb Auto-Sync: " + onOff(config.autoSyncAnkiWeb)));
        }).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Jetzt syncen"), btn -> {
            btn.active = false;
            AnkiManager.getInstance().syncNow().thenRun(() ->
                    this.minecraft.execute(() -> btn.active = true)
            );
        }).bounds(rightX, y, colW, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Audio Auto-Play: " + onOff(config.autoPlayAudio)), btn -> {
            config.autoPlayAudio = !config.autoPlayAudio;
            btn.setMessage(Component.literal("Audio Auto-Play: " + onOff(config.autoPlayAudio)));
        }).bounds(leftX, y, fullW, 20).build());

        int footerY = cardY + cardH - 32;
        addRenderableWidget(Button.builder(Component.literal("Speichern & Zurück"), btn -> {
            config.save();
            AnkiManager.getInstance().getSleepCardsClient().wakeWorker();
            onClose();
        }).bounds(cardX + cardW / 2 - 75, footerY, 150, 22).build());
    }

    @Override
    public void onClose() {
        config.save();
        AnkiManager.getInstance().getSleepCardsClient().wakeWorker();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(cardX, cardY, cardX + cardW, cardY + cardH, BG_CARD);
        gfx.fill(cardX, cardY, cardX + cardW, cardY + 1, BORDER_COLOR);
        gfx.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, BORDER_COLOR);
        gfx.fill(cardX, cardY, cardX + 1, cardY + cardH, BORDER_COLOR);
        gfx.fill(cardX + cardW - 1, cardY, cardX + cardW, cardY + cardH, BORDER_COLOR);

        gfx.text(font, "SleepCards & Auto-Sync", cardX + 16, cardY + 12, 0xFFF8FAFC, false);
        gfx.text(font, "Server URL:", cardX + 16, cardY + 34, 0xFF94A3B8, false);
        gfx.text(font, "Persönlicher Token:", cardX + 16, cardY + 74, 0xFF94A3B8, false);
        gfx.text(font, "Standard-Deck (bei Server-Challenge):", cardX + 16, cardY + 114, 0xFF94A3B8, false);

        SleepCardsClient sc = AnkiManager.getInstance().getSleepCardsClient();
        String statusLine = "Status: " + sc.getLastStatus() + "  |  Queue: " + sc.getQueueSize();
        gfx.text(font, statusLine, cardX + 16, cardY + cardH - 54, 0xFF38BDF8, false);

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }

    private static String onOff(boolean v) {
        return v ? "AN" : "AUS";
    }
}
