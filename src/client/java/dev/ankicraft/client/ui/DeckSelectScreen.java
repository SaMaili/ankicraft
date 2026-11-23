package dev.ankicraft.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class DeckSelectScreen extends Screen {
    private static final int BG_CARD = 0xEE0F172A;
    private static final int BORDER_COLOR = 0xFF334155;
    private static final int ACCENT_SKY = 0xFF38BDF8;

    private final Screen parent;
    private final AnkiManager manager;
    private EditBox searchBox;
    private List<AnkiDeck> filteredDecks = new ArrayList<>();
    private final Deque<List<String>> selectionHistory = new ArrayDeque<>();

    private boolean onlyDue = false;
    private int scrollOffset = 0;
    private int cursorIndex = 0;
    private String statusMessage = "";
    private int statusColor = 0xFF94A3B8;

    private int cardX, cardY, cardW, cardH;
    private int listTop, listBottom;
    private static final int ROW_H = 22;
    private int visibleRows = 8;

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
            if (onlyDue && d.totalDue() <= 0) continue;
            if (q.isEmpty() || d.name.toLowerCase(Locale.ROOT).contains(q)) {
                filteredDecks.add(d);
            }
        }
        scrollOffset = 0;
        cursorIndex = filteredDecks.isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        this.cardW = Math.min(520, this.width - 28);
        this.cardH = Math.min(380, this.height - 24);
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = (this.height - this.cardH) / 2;
        this.listTop = cardY + 64;
        this.listBottom = cardY + cardH - 46;
        this.visibleRows = Math.max(1, (listBottom - listTop) / ROW_H);

        String prevSearch = searchBox != null ? searchBox.getValue() : "";
        int searchW = cardW - 196;
        searchBox = new EditBox(font, cardX + 14, cardY + 34, searchW, 20, Component.literal("Deck suchen..."));
        searchBox.setHint(Component.literal("Decks filtern..."));
        searchBox.setValue(prevSearch);
        searchBox.setResponder(q -> {
            filterDecks(q);
            rebuildDeckButtons();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        // Quick filter: Only Due
        addRenderableWidget(Button.builder(Component.literal(onlyDue ? "Nur fällige: AN" : "Nur fällige: AUS"), btn -> {
            onlyDue = !onlyDue;
            filterDecks(searchBox != null ? searchBox.getValue() : "");
            init();
        }).bounds(cardX + 18 + searchW, cardY + 34, 88, 20).build());

        // Select / Deselect all filtered decks
        addRenderableWidget(Button.builder(Component.literal("Gefilterte ±"), btn -> {
            toggleAllFiltered();
            init();
        }).bounds(cardX + 110 + searchW, cardY + 34, 76, 20).build());

        // Real Button widgets for every visible deck row (100% reliable click + hover + controller support)
        int maxScroll = Math.max(0, filteredDecks.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        List<String> selected = manager.getConfig().selectedDecks;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filteredDecks.size()) break;

            AnkiDeck deck = filteredDecks.get(idx);
            boolean isSelected = selected.contains(deck.name);
            boolean isCursor = (idx == cursorIndex);

            String indent = "  ".repeat(Math.min(3, deck.depth()));
            String prefix = isSelected ? "[✓] " : "[ ] ";
            String cursorMark = isCursor ? "▸ " : "";
            String duePart = " (" + deck.totalDue() + ")";
            String label = cursorMark + indent + prefix + deck.shortName() + duePart;

            int rowY = listTop + i * ROW_H;
            final int targetIdx = idx;
            addRenderableWidget(Button.builder(Component.literal(label), btn -> {
                cursorIndex = targetIdx;
                toggleDeckAt(targetIdx);
                init();
            }).bounds(cardX + 14, rowY, cardW - 28, ROW_H - 2).build());
        }

        // Footer buttons
        int footerY = cardY + cardH - 32;
        int bw = (cardW - 44) / 3;

        addRenderableWidget(Button.builder(Component.literal("Alle zurücksetzen"), btn -> {
            pushUndoSnapshot();
            manager.getConfig().selectedDecks.clear();
            manager.getConfig().save();
            statusMessage = "Auswahl zurückgesetzt (alle Decks aktiv).";
            statusColor = ACCENT_SKY;
            init();
        }).bounds(cardX + 14, footerY, bw, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Sync mit Anki"), btn -> {
            btn.active = false;
            statusMessage = "Verbinde mit AnkiConnect...";
            statusColor = ACCENT_SKY;
            manager.syncNow().thenAccept(ok -> this.minecraft.execute(() -> {
                filterDecks(searchBox != null ? searchBox.getValue() : "");
                if (ok) {
                    statusMessage = "Sync erfolgreich! " + manager.getDecks().size() + " Deck(s) geladen.";
                    statusColor = 0xFF34D399;
                } else {
                    statusMessage = "AnkiConnect nicht erreichbar (" + manager.getConfig().ankiConnectUrl + ")";
                    statusColor = 0xFFF87171;
                }
                init();
            }));
        }).bounds(cardX + 22 + bw, footerY, bw, 22).build());

        addRenderableWidget(Button.builder(Component.literal("Fertig & Sync"), btn -> {
            btn.active = false;
            statusMessage = "Lade Karten für gewählte Decks...";
            statusColor = ACCENT_SKY;
            manager.getConfig().save();
            manager.syncNow().thenRun(() -> this.minecraft.execute(() -> {
                if (parent instanceof QuizScreen qs) {
                    qs.reloadAfterDeckChange();
                }
                onClose();
            }));
        }).bounds(cardX + 30 + bw * 2, footerY, bw, 22).build());
    }

    private void rebuildDeckButtons() {
        init();
    }

    private void pushUndoSnapshot() {
        selectionHistory.push(new ArrayList<>(manager.getConfig().selectedDecks));
    }

    private void undoDeckSelection() {
        if (selectionHistory.isEmpty()) return;
        manager.getConfig().selectedDecks.clear();
        manager.getConfig().selectedDecks.addAll(selectionHistory.pop());
        manager.getConfig().save();
        statusMessage = "Auswahl rückgängig gemacht (Strg+Z).";
        statusColor = ACCENT_SKY;
        init();
    }

    private void toggleAllFiltered() {
        if (filteredDecks.isEmpty()) return;
        pushUndoSnapshot();
        List<String> selected = manager.getConfig().selectedDecks;
        boolean allSelected = filteredDecks.stream().allMatch(d -> selected.contains(d.name));
        if (allSelected) {
            for (AnkiDeck d : filteredDecks) {
                selected.remove(d.name);
            }
        } else {
            for (AnkiDeck d : filteredDecks) {
                if (!selected.contains(d.name)) {
                    selected.add(d.name);
                }
            }
        }
        manager.getConfig().save();
    }

    private void toggleDeckAt(int idx) {
        if (idx < 0 || idx >= filteredDecks.size()) return;
        pushUndoSnapshot();
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
        if (cursorIndex < scrollOffset) {
            scrollOffset = cursorIndex;
        } else if (cursorIndex >= scrollOffset + visibleRows) {
            scrollOffset = cursorIndex - visibleRows + 1;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.hasControlDown() && key == InputConstants.KEY_Z) {
            undoDeckSelection();
            return true;
        }
        if (!filteredDecks.isEmpty()) {
            if (key == InputConstants.KEY_DOWN) {
                cursorIndex = (cursorIndex + 1) % filteredDecks.size();
                ensureCursorVisible();
                init();
                return true;
            }
            if (key == InputConstants.KEY_UP) {
                cursorIndex = (cursorIndex <= 0) ? filteredDecks.size() - 1 : cursorIndex - 1;
                ensureCursorVisible();
                init();
                return true;
            }
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                toggleDeckAt(cursorIndex);
                init();
                return true;
            }
            if (key == InputConstants.KEY_SPACE && (searchBox == null || !searchBox.isFocused())) {
                toggleDeckAt(cursorIndex);
                init();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollYDelta) {
        int maxScroll = Math.max(0, filteredDecks.size() - visibleRows);
        if (maxScroll > 0) {
            int delta = scrollYDelta > 0 ? -1 : 1;
            int next = Math.max(0, Math.min(maxScroll, scrollOffset + delta));
            if (next != scrollOffset) {
                scrollOffset = next;
                init();
            }
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
        } else if (!statusMessage.isEmpty()) {
            gfx.centeredText(font, statusMessage, cardX + cardW / 2, listBottom + 3, statusColor);
        }

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }
}
