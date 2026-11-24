package dev.ankicraft.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final Set<String> collapsedDecks = new HashSet<>();
    private final Set<String> decksWithChildren = new HashSet<>();
    private final Deque<List<String>> selectionHistory = new ArrayDeque<>();

    private boolean onlyDue = false;
    private int scrollOffset = 0;
    private int cursorIndex = 0;
    private String statusMessage = "";
    private int statusColor = 0xFF94A3B8;

    private int cardX, cardY, cardW, cardH;
    private int listTop, listBottom;
    private static final int ROW_H = 24;
    private int visibleRows = 8;

    public DeckSelectScreen(Screen parent) {
        super(Component.literal("AnkiCraft – Decks auswählen"));
        this.parent = parent;
        this.manager = AnkiManager.getInstance();
        rebuildDeckTree("");
    }

    private void rebuildDeckTree(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<AnkiDeck> all = manager.getDecks();

        decksWithChildren.clear();
        for (AnkiDeck d : all) {
            int sep = d.name.lastIndexOf("::");
            if (sep > 0) {
                decksWithChildren.add(d.name.substring(0, sep));
            }
        }

        filteredDecks = new ArrayList<>();
        for (AnkiDeck d : all) {
            if (onlyDue && d.totalDue() <= 0) continue;
            if (!q.isEmpty()) {
                if (d.name.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredDecks.add(d);
                }
            } else if (!isHiddenByCollapsedAncestor(d.name)) {
                filteredDecks.add(d);
            }
        }
        if (cursorIndex >= filteredDecks.size()) {
            cursorIndex = filteredDecks.isEmpty() ? -1 : 0;
        }
    }

    private boolean isHiddenByCollapsedAncestor(String deckName) {
        int idx = 0;
        while ((idx = deckName.indexOf("::", idx)) != -1) {
            String ancestor = deckName.substring(0, idx);
            if (collapsedDecks.contains(ancestor)) {
                return true;
            }
            idx += 2;
        }
        return false;
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
            scrollOffset = 0;
            cursorIndex = 0;
            rebuildDeckTree(q);
            init();
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal(onlyDue ? "Nur fällige: AN" : "Nur fällige: AUS"), btn -> {
            onlyDue = !onlyDue;
            scrollOffset = 0;
            rebuildDeckTree(searchBox != null ? searchBox.getValue() : "");
            init();
        }).bounds(cardX + 18 + searchW, cardY + 34, 88, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Gefilterte ±"), btn -> {
            toggleAllFiltered();
            init();
        }).bounds(cardX + 110 + searchW, cardY + 34, 76, 20).build());

        // Visible deck rows as custom-styled AbstractWidgets (native click + hover + sleek dark look)
        int maxScroll = Math.max(0, filteredDecks.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= filteredDecks.size()) break;
            AnkiDeck deck = filteredDecks.get(idx);
            int rowY = listTop + i * ROW_H;
            addRenderableWidget(new DeckRowWidget(cardX + 14, rowY + 1, cardW - 28, ROW_H - 2, idx, deck));
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
                rebuildDeckTree(searchBox != null ? searchBox.getValue() : "");
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

    private void pushUndoSnapshot() {
        selectionHistory.push(new ArrayList<>(manager.getConfig().selectedDecks));
    }

    private void undoDeckSelection() {
        if (selectionHistory.isEmpty()) return;
        manager.getConfig().selectedDecks.clear();
        manager.getConfig().selectedDecks.addAll(selectionHistory.pop());
        manager.getConfig().save();
        statusMessage = "Auswahl rückgängig gemacht.";
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
        boolean selecting = !selected.contains(deck.name);
        String prefix = deck.name + "::";

        if (selecting) {
            if (!selected.contains(deck.name)) {
                selected.add(deck.name);
            }
            for (AnkiDeck d : manager.getDecks()) {
                if (d.name.startsWith(prefix) && !selected.contains(d.name)) {
                    selected.add(d.name);
                }
            }
        } else {
            selected.removeIf(name -> name.equals(deck.name) || name.startsWith(prefix));
        }
        manager.getConfig().save();
    }

    private void toggleCollapse(String deckName) {
        if (!decksWithChildren.contains(deckName)) return;
        if (collapsedDecks.contains(deckName)) {
            collapsedDecks.remove(deckName);
        } else {
            collapsedDecks.add(deckName);
        }
        rebuildDeckTree(searchBox != null ? searchBox.getValue() : "");
        init();
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
        manager.getConfig().save();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.hasControlDown() && (key == InputConstants.KEY_Z || key == InputConstants.KEY_Y)) {
            undoDeckSelection();
            return true;
        }
        if (!filteredDecks.isEmpty() && cursorIndex >= 0 && cursorIndex < filteredDecks.size()) {
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
            if (key == InputConstants.KEY_LEFT) {
                String name = filteredDecks.get(cursorIndex).name;
                if (decksWithChildren.contains(name) && !collapsedDecks.contains(name)) {
                    toggleCollapse(name);
                    return true;
                }
            }
            if (key == InputConstants.KEY_RIGHT) {
                String name = filteredDecks.get(cursorIndex).name;
                if (decksWithChildren.contains(name) && collapsedDecks.contains(name)) {
                    toggleCollapse(name);
                    return true;
                }
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

        // Scrollbar if needed
        if (filteredDecks.size() > visibleRows) {
            int trackH = listBottom - listTop;
            int barH = Math.max(16, (int) ((double) visibleRows * trackH / filteredDecks.size()));
            int maxScroll = filteredDecks.size() - visibleRows;
            int barY = listTop + (int) ((double) scrollOffset / maxScroll * (trackH - barH));
            int barX = cardX + cardW - 9;
            gfx.fill(barX, listTop, barX + 3, listBottom, 0x33FFFFFF);
            gfx.fill(barX, barY, barX + 3, barY + barH, ACCENT_SKY);
        }

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }

    private class DeckRowWidget extends AbstractWidget {
        private final int deckIndex;
        private final AnkiDeck deck;

        DeckRowWidget(int x, int y, int width, int height, int deckIndex, AnkiDeck deck) {
            super(x, y, width, height, Component.literal(deck.shortName()));
            this.deckIndex = deckIndex;
            this.deck = deck;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
            List<String> selected = manager.getConfig().selectedDecks;
            boolean isSelected = selected.contains(deck.name);
            boolean hasChildren = decksWithChildren.contains(deck.name);
            boolean isCollapsed = collapsedDecks.contains(deck.name);
            boolean isCursor = (deckIndex == cursorIndex);

            boolean partial = false;
            if (!isSelected && hasChildren) {
                String prefix = deck.name + "::";
                for (String s : selected) {
                    if (s.startsWith(prefix)) {
                        partial = true;
                        break;
                    }
                }
            }

            int bg = isSelected ? BG_ROW_SELECTED : (this.isHovered() ? BG_ROW_HOVER : BG_ROW);
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();

            gfx.fill(x, y, x + w, y + h, bg);
            if (isCursor || this.isHovered()) {
                gfx.fill(x, y, x + 2, y + h, ACCENT_SKY);
            }

            int indent = Math.min(4, deck.depth()) * 12;
            int curX = x + 6 + indent;

            if (hasChildren) {
                String arrow = isCollapsed ? "▸" : "▾";
                gfx.text(font, arrow, curX, y + 7, ACCENT_SKY, false);
            }
            curX += 12;

            String check = isSelected ? "[✓]" : (partial ? "[-]" : "[ ]");
            int checkColor = isSelected ? ACCENT_SKY : (partial ? 0xFFF59E0B : 0xFF94A3B8);
            gfx.text(font, check, curX, y + 7, checkColor, false);
            curX += 22;

            String dueStr = deck.totalDue() + " fällig";
            int dueW = font.width(dueStr);
            int maxNameW = (x + w - 10 - dueW - 8) - curX;
            String label = deck.shortName();
            if (font.width(label) > maxNameW && maxNameW > 12) {
                label = font.plainSubstrByWidth(label, maxNameW - 6) + "…";
            }
            gfx.text(font, label, curX, y + 7, 0xFFF1F5F9, false);

            int dueColor = deck.totalDue() > 0 ? 0xFF34D399 : 0xFF64748B;
            gfx.text(font, dueStr, x + w - 8 - dueW, y + 7, dueColor, false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            cursorIndex = deckIndex;
            boolean hasChildren = decksWithChildren.contains(deck.name);
            int indent = Math.min(4, deck.depth()) * 12;
            int arrowEnd = getX() + 6 + indent + 13;

            if (hasChildren && event.x() <= arrowEnd) {
                toggleCollapse(deck.name);
            } else {
                toggleDeckAt(deckIndex);
                init();
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
