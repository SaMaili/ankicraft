package dev.ankicraft.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DeckSelectScreen extends Screen {
    private static final int BG_CARD = 0xEE0F172A;
    private static final int BG_ROW = 0xAA1E293B;
    private static final int BG_ROW_HOVER = 0xCC334155;
    private static final int BG_ROW_SELECTED = 0xCC0369A1;
    private static final int BORDER_COLOR = 0xFF334155;
    private static final int ACCENT_SKY = 0xFF38BDF8;

    private final Screen parent;
    private final AnkiManager manager;
    private EditBox searchBox;
    private List<AnkiDeck> filteredDecks = new ArrayList<>();
    private double scrollY = 0;
    private int cursorIndex = 0;
    private String statusMessage = "";
    private int statusColor = 0xFF94A3B8;

    private int cardX, cardY, cardW, cardH;
    private int listTop, listBottom;
    private static final int ROW_H = 24;

    public DeckSelectScreen(Screen parent) {
        super(Component.literal("AnkiCraft – Decks auswählen"));
        this.parent = parent;
        this.manager = AnkiManager.getInstance();
        filterDecks("");
    }

    private void filterDecks(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<AnkiDeck> all = manager.getDecks();
        filteredDecks = new ArrayList<>();
        for (AnkiDeck d : all) {
            if (q.isEmpty() || d.name.toLowerCase(Locale.ROOT).contains(q)) {
                filteredDecks.add(d);
            }
        }
        scrollY = 0;
        cursorIndex = filteredDecks.isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        this.cardW = Math.min(480, this.width - 32);
        this.cardH = Math.min(360, this.height - 28);
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = (this.height - this.cardH) / 2;
        this.listTop = cardY + 62;
        this.listBottom = cardY + cardH - 44;

        String prevSearch = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, cardX + 16, cardY + 34, cardW - 32, 20, Component.literal("Deck suchen..."));
        searchBox.setHint(Component.literal("Decks filtern (z. B. Algorithmen)..."));
        searchBox.setValue(prevSearch);
        searchBox.setResponder(this::filterDecks);
        addRenderableWidget(searchBox);

        int footerY = cardY + cardH - 32;
        int bw = (cardW - 44) / 3;

        addRenderableWidget(Button.builder(Component.literal("Alle zurücksetzen"), btn -> {
            manager.getConfig().selectedDecks.clear();
            manager.getConfig().save();
            statusMessage = "Auswahl zurückgesetzt (alle Decks aktiv).";
            statusColor = 0xFF38BDF8;
        }).bounds(cardX + 14, footerY, bw, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Sync mit Anki"), btn -> {
            btn.active = false;
            statusMessage = "Verbinde mit AnkiConnect...";
            statusColor = 0xFF38BDF8;
            manager.syncNow().thenAccept(ok -> this.minecraft.execute(() -> {
                filterDecks(searchBox != null ? searchBox.getValue() : "");
                btn.active = true;
                if (ok) {
                    statusMessage = "Sync erfolgreich! " + manager.getDecks().size() + " Deck(s) geladen.";
                    statusColor = 0xFF34D399;
                } else {
                    statusMessage = "AnkiConnect nicht erreichbar (" + manager.getConfig().ankiConnectUrl + ")";
                    statusColor = 0xFFF87171;
                }
            }));
        }).bounds(cardX + 22 + bw, footerY, bw, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Fertig & Sync"), btn -> {
            btn.active = false;
            statusMessage = "Lade Karten für gewählte Decks...";
            statusColor = 0xFF38BDF8;
            manager.getConfig().save();
            manager.syncNow().thenRun(() -> this.minecraft.execute(() -> {
                if (parent instanceof QuizScreen qs) {
                    qs.reloadAfterDeckChange();
                }
                onClose();
            }));
        }).bounds(cardX + 30 + bw * 2, footerY, bw, 22).build());
    }

    private void toggleDeckAt(int idx) {
        if (idx < 0 || idx >= filteredDecks.size()) return;
        AnkiDeck deck = filteredDecks.get(idx);
        List<String> selected = manager.getConfig().selectedDecks;
        if (selected.contains(deck.name)) {
            selected.remove(deck.name);
        } else {
            selected.add(deck.name);
        }
        manager.getConfig().save();
    }

    private void ensureCursorVisible() {
        if (cursorIndex < 0 || filteredDecks.isEmpty()) return;
        int rowTop = cursorIndex * ROW_H;
        int rowBottom = rowTop + ROW_H;
        int viewH = listBottom - listTop;
        if (rowTop < scrollY) {
            scrollY = rowTop;
        } else if (rowBottom > scrollY + viewH) {
            scrollY = rowBottom - viewH;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (!filteredDecks.isEmpty()) {
            if (key == InputConstants.KEY_DOWN) {
                cursorIndex = (cursorIndex + 1) % filteredDecks.size();
                ensureCursorVisible();
                return true;
            }
            if (key == InputConstants.KEY_UP) {
                cursorIndex = (cursorIndex <= 0) ? filteredDecks.size() - 1 : cursorIndex - 1;
                ensureCursorVisible();
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                toggleDeckAt(cursorIndex);
                return true;
            }
            if (key == InputConstants.KEY_SPACE && (searchBox == null || !searchBox.isFocused())) {
                toggleDeckAt(cursorIndex);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.y() >= listTop && event.y() <= listBottom
                && event.x() >= cardX + 14 && event.x() <= cardX + cardW - 14) {
            int idx = (int) ((event.y() - listTop + scrollY) / ROW_H);
            if (idx >= 0 && idx < filteredDecks.size()) {
                cursorIndex = idx;
                toggleDeckAt(idx);
                if (searchBox != null) {
                    searchBox.setFocused(false);
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollYDelta) {
        int totalH = filteredDecks.size() * ROW_H;
        int viewH = listBottom - listTop;
        if (totalH > viewH) {
            scrollY = Math.max(0, Math.min(totalH - viewH, scrollY - scrollYDelta * 20));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollYDelta);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(cardX, cardY, cardX + cardW, cardY + cardH, BG_CARD);
        gfx.fill(cardX, cardY, cardX + cardW, cardY + 1, BORDER_COLOR);
        gfx.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, BORDER_COLOR);
        gfx.fill(cardX, cardY, cardX + 1, cardY + cardH, BORDER_COLOR);
        gfx.fill(cardX + cardW - 1, cardY, cardX + cardW, cardY + cardH, BORDER_COLOR);

        String subtitle = manager.getConfig().selectedDecks.isEmpty()
                ? "Aktiv: Alle Decks"
                : "Aktiv: " + manager.getConfig().selectedDecks.size() + " Deck(s)";
        gfx.text(font, "Anki Decks auswählen", cardX + 16, cardY + 12, 0xFFF8FAFC, false);
        gfx.text(font, subtitle, cardX + cardW - 16 - font.width(subtitle), cardY + 12, ACCENT_SKY, false);

        if (filteredDecks.isEmpty()) {
            String msg = statusMessage.isEmpty()
                    ? "Keine Decks gefunden. Klicke auf 'Sync mit Anki'."
                    : statusMessage;
            int col = statusMessage.isEmpty() ? 0xFF94A3B8 : statusColor;
            gfx.centeredText(font, msg, cardX + cardW / 2, (listTop + listBottom) / 2, col);
        } else {
            gfx.enableScissor(cardX + 12, listTop, cardX + cardW - 12, listBottom);
            List<String> selected = manager.getConfig().selectedDecks;

            for (int i = 0; i < filteredDecks.size(); i++) {
                int ry = listTop + i * ROW_H - (int) scrollY;
                if (ry + ROW_H < listTop || ry > listBottom) continue;

                AnkiDeck deck = filteredDecks.get(i);
                boolean isSelected = selected.contains(deck.name);
                boolean isHovered = mouseX >= cardX + 16 && mouseX <= cardX + cardW - 16
                        && mouseY >= ry + 1 && mouseY <= ry + ROW_H - 1
                        && mouseY >= listTop && mouseY <= listBottom;
                boolean isCursor = (i == cursorIndex);

                int bg = isSelected ? BG_ROW_SELECTED : (isHovered ? BG_ROW_HOVER : BG_ROW);
                gfx.fill(cardX + 16, ry + 1, cardX + cardW - 16, ry + ROW_H - 1, bg);
                if (isCursor || isHovered) {
                    gfx.fill(cardX + 16, ry + 1, cardX + 18, ry + ROW_H - 1, ACCENT_SKY);
                }

                int indent = Math.min(4, deck.depth()) * 12;
                String check = isSelected ? "[✓]" : "[ ]";
                gfx.text(font, check, cardX + 22 + indent, ry + 7, isSelected ? ACCENT_SKY : 0xFF94A3B8, false);
                gfx.text(font, deck.shortName(), cardX + 44 + indent, ry + 7, 0xFFF1F5F9, false);

                String dueStr = deck.totalDue() + " fällig";
                int dueColor = deck.totalDue() > 0 ? 0xFF34D399 : 0xFF64748B;
                gfx.text(font, dueStr, cardX + cardW - 24 - font.width(dueStr), ry + 7, dueColor, false);
            }
            gfx.disableScissor();
        }

        if (!statusMessage.isEmpty() && !filteredDecks.isEmpty()) {
            gfx.centeredText(font, statusMessage, cardX + cardW / 2, listBottom + 3, statusColor);
        }

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }
}
