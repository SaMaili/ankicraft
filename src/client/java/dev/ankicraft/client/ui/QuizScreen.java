package dev.ankicraft.client.ui;

import dev.ankicraft.client.AnkiManager;
import dev.ankicraft.client.anki.AnkiModels.AnkiCard;
import dev.ankicraft.client.config.AnkiCraftConfig;
import dev.ankicraft.client.render.CardContentParser;
import dev.ankicraft.client.render.CardContentParser.AudioBlock;
import dev.ankicraft.client.render.CardContentParser.ContentBlock;
import dev.ankicraft.client.render.CardContentRenderer;
import dev.ankicraft.client.render.CardContentRenderer.AudioHitbox;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QuizScreen extends Screen {
    private static final int BG_CARD = 0xEE0F172A;
    private static final int BG_HEADER = 0xFF1E293B;
    private static final int BORDER_COLOR = 0xFF334155;
    private static final int ACCENT_SKY = 0xFF38BDF8;
    private static final int ACCENT_EMERALD = 0xFF10B981;
    private static final int ACCENT_AMBER = 0xFFF59E0B;
    private static final int ACCENT_ROSE = 0xFFF43F5E;
    private static final int TEXT_MUTED = 0xFF94A3B8;

    private record UndoSnapshot(AnkiCard card, int prevStreak, int prevReviewedToday) {}

    private final AnkiManager manager;
    private final boolean gatekeeping;
    private final Runnable onGatekeepPassed;
    private final Deque<UndoSnapshot> undoStack = new ArrayDeque<>();

    private AnkiCard currentCard;
    private List<ContentBlock> questionBlocks = List.of();
    private List<ContentBlock> answerBlocks = List.of();
    private final List<AudioHitbox> audioHitboxes = new ArrayList<>();
    private boolean answerShown = false;
    private final Set<Integer> heldKeys = new HashSet<>();
    private final long screenOpenedMs = System.currentTimeMillis();
    private long answerRevealedMs = 0L;
    private double scrollY = 0;
    private int contentHeight = 0;
    private String undoBanner = "";
    private long undoBannerUntilMs = 0L;

    private int cardX, cardY, cardW, cardH;
    private int viewTop, viewBottom, viewLeft, viewWidth;

    public QuizScreen(boolean gatekeeping, Runnable onGatekeepPassed) {
        super(Component.literal("AnkiCraft Quiz"));
        this.manager = AnkiManager.getInstance();
        this.gatekeeping = gatekeeping;
        this.onGatekeepPassed = onGatekeepPassed;
        if (InputConstants.isKeyDown(InputConstants.KEY_SPACE)) {
            heldKeys.add(InputConstants.KEY_SPACE);
        }
        manager.reportLearningState(true);
        loadNextCard();
    }

    public void reloadAfterDeckChange() {
        loadNextCard();
        init();
    }

    private void loadNextCard() {
        manager.getAudioPlayer().stop();
        this.currentCard = manager.pollNextCard();
        this.answerShown = false;
        this.scrollY = 0;
        if (currentCard != null) {
            this.questionBlocks = CardContentParser.parse(currentCard.questionHtml, currentCard.soundFiles);
            String ansHtml = extractAnswerPortion(currentCard.answerHtml);
            this.answerBlocks = CardContentParser.parse(ansHtml, currentCard.soundFiles);
            if (this.answerBlocks.isEmpty() && currentCard.answerHtml != null) {
                this.answerBlocks = CardContentParser.parse(currentCard.answerHtml, currentCard.soundFiles);
            }
            if (manager.getConfig().autoPlayAudio) {
                playFirstAudio(this.questionBlocks);
            }
        } else {
            this.questionBlocks = List.of();
            this.answerBlocks = List.of();
            manager.reportNoDueCards();
        }
    }

    private void playFirstAudio(List<ContentBlock> blocks) {
        for (ContentBlock b : blocks) {
            if (b instanceof AudioBlock audio) {
                manager.getAudioPlayer().play(audio.filename());
                return;
            }
        }
    }

    private static String extractAnswerPortion(String answerHtml) {
        if (answerHtml == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)<hr[^>]*\\bid\\s*=\\s*[\"']?answer[\"']?[^>]*>")
                .matcher(answerHtml);
        if (m.find()) {
            String after = answerHtml.substring(m.end()).trim();
            if (!after.isEmpty()) {
                return after;
            }
        }
        return answerHtml;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        int topReserve = 42; // Platz oben für Server-BossBar / XP-Anzeige lassen
        int bottomMargin = 10;
        this.cardW = Math.min(580, this.width - 24);
        this.cardH = Math.min(390, Math.max(200, this.height - topReserve - bottomMargin));
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = topReserve + Math.max(0, (this.height - topReserve - bottomMargin - this.cardH) / 2);

        this.viewLeft = cardX + 18;
        this.viewWidth = cardW - 36;
        this.viewTop = cardY + 38;
        this.viewBottom = cardY + cardH - 58;

        // Top-right utility buttons
        int topBtnY = cardY + 7;
        boolean locked = isGatekeepActive();
        if (currentCard != null && hasAnyAudio()) {
            int audioX = locked ? (cardX + cardW - 62) : (cardX + cardW - 174);
            addRenderableWidget(Button.builder(Component.literal("▶ Audio"), btn -> replayCurrentAudio())
                    .bounds(audioX, topBtnY, 52, 20).build());
        }
        if (!locked) {
            addRenderableWidget(Button.builder(Component.literal("Decks"), btn ->
                    this.minecraft.gui.setScreen(new DeckSelectScreen(this))
            ).bounds(cardX + cardW - 118, topBtnY, 52, 20).build());

            addRenderableWidget(Button.builder(Component.literal("⚙"), btn ->
                    this.minecraft.gui.setScreen(new ConfigScreen(this))
            ).bounds(cardX + cardW - 62, topBtnY, 24, 20).build());

            addRenderableWidget(Button.builder(Component.literal("✕"), btn -> onClose())
                    .bounds(cardX + cardW - 34, topBtnY, 24, 20).build());
        }

        int footerY = cardY + cardH - 34;
        if (currentCard == null) {
            addRenderableWidget(Button.builder(Component.literal("Mit Anki syncen"), btn -> {
                btn.active = false;
                manager.syncNow().thenRun(() -> this.minecraft.execute(() -> {
                    loadNextCard();
                    init();
                }));
            }).bounds(cardX + cardW / 2 - 110, footerY, 130, 22).build());

            addRenderableWidget(Button.builder(Component.literal("Schließen"), btn -> onClose())
                    .bounds(cardX + cardW / 2 + 28, footerY, 84, 22).build());
            return;
        }

        if (!answerShown) {
            int btnW = 190;
            addRenderableWidget(Button.builder(Component.literal("Antwort zeigen [Leertaste]"), btn -> revealAnswer())
                    .bounds(cardX + (cardW - btnW) / 2, footerY, btnW, 22).build());
        } else {
            int gap = 8;
            int totalW = cardW - 32;
            int bw = (totalW - gap * 3) / 4;
            int bx = cardX + 16;

            addRenderableWidget(Button.builder(labelWithInterval("1 Nochmal", 0), btn -> rateCard(1))
                    .bounds(bx, footerY, bw, 22).build());
            addRenderableWidget(Button.builder(labelWithInterval("2 Schwer", 1), btn -> rateCard(2))
                    .bounds(bx + (bw + gap), footerY, bw, 22).build());
            addRenderableWidget(Button.builder(labelWithInterval("3 Gut", 2), btn -> rateCard(3))
                    .bounds(bx + (bw + gap) * 2, footerY, bw, 22).build());
            addRenderableWidget(Button.builder(labelWithInterval("4 Einfach", 3), btn -> rateCard(4))
                    .bounds(bx + (bw + gap) * 3, footerY, bw, 22).build());
        }
    }

    private Component labelWithInterval(String base, int idx) {
        if (currentCard != null && idx < currentCard.nextReviews.size()) {
            String raw = currentCard.nextReviews.get(idx);
            if (raw != null) {
                // Strip Unicode bidi isolates (FSI/PDI/LRM/RLM) and non-breaking spaces
                String interval = raw.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "")
                        .replace('\u00A0', ' ')
                        .replaceAll("(?i)\\s*Min\\.?\\b", "m")
                        .replaceAll("(?i)\\s*Std\\.?\\b", "h")
                        .replaceAll("(?i)\\s*Tage?\\b", "d")
                        .replaceAll("(?i)\\s*Tg\\.?\\b", "d")
                        .replaceAll("(?i)\\s*T\\.?", "d")
                        .replaceAll("(?i)\\s*Monate?\\b", "mo")
                        .replaceAll("(?i)\\s*Mon\\.?\\b", "mo")
                        .replaceAll("(?i)\\s*Jahre?\\b", "y")
                        .replaceAll("(?i)\\s*J\\.?", "y")
                        .trim();
                if (!interval.isBlank()) {
                    return Component.literal(base + " (" + interval + ")");
                }
            }
        }
        return Component.literal(base);
    }

    private boolean hasAnyAudio() {
        if (questionBlocks.stream().anyMatch(b -> b instanceof AudioBlock)) {
            return true;
        }
        return answerShown && answerBlocks.stream().anyMatch(b -> b instanceof AudioBlock);
    }

    private void replayCurrentAudio() {
        if (answerShown && answerBlocks.stream().anyMatch(b -> b instanceof AudioBlock)) {
            playFirstAudio(answerBlocks);
        } else {
            playFirstAudio(questionBlocks);
        }
    }

    private void revealAnswer() {
        if (!answerShown && currentCard != null) {
            answerShown = true;
            answerRevealedMs = System.currentTimeMillis();
            if (manager.getConfig().autoPlayAudio) {
                playFirstAudio(answerBlocks);
            }
            init();
        }
    }

    private void rateCard(int ease) {
        if (currentCard == null) return;
        AnkiCard rated = currentCard;
        this.currentCard = null;
        undoStack.push(new UndoSnapshot(rated, manager.getStreak(), manager.getReviewedToday()));
        manager.submitAnswer(rated, ease);

        if (gatekeeping && ease >= 3) {
            if (onGatekeepPassed != null) {
                onGatekeepPassed.run();
            }
            onClose();
            return;
        }

        // Keep quizzing continuously until the user closes with K / Esc / X
        loadNextCard();
        init();
    }

    private void undoLastCard() {
        if (undoStack.isEmpty()) return;
        UndoSnapshot snap = undoStack.pop();
        if (currentCard != null) {
            manager.pushCardToFront(currentCard);
        }
        manager.undoLastReview(snap.card(), snap.prevStreak(), snap.prevReviewedToday());
        this.currentCard = snap.card();
        this.answerShown = true;
        this.scrollY = 0;
        this.undoBanner = "↺ Karte rückgängig gemacht";
        this.undoBannerUntilMs = System.currentTimeMillis() + 2500L;
        this.questionBlocks = CardContentParser.parse(currentCard.questionHtml, currentCard.soundFiles);
        String ansHtml = extractAnswerPortion(currentCard.answerHtml);
        this.answerBlocks = CardContentParser.parse(ansHtml, currentCard.soundFiles);
        if (this.answerBlocks.isEmpty() && currentCard.answerHtml != null) {
            this.answerBlocks = CardContentParser.parse(currentCard.answerHtml, currentCard.soundFiles);
        }
        init();
    }

    public boolean isGatekeepActive() {
        return gatekeeping && currentCard != null;
    }

    @Override
    public void onClose() {
        manager.getAudioPlayer().stop();
        if (currentCard != null) {
            manager.pushCardToFront(currentCard);
            currentCard = null;
        }
        manager.flushAndSaveBeforeClose();
        manager.reportLearningState(false);
        super.onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !isGatekeepActive();
    }

    @Override
    public boolean isPauseScreen() {
        return manager.getConfig().pauseInSingleplayer;
    }

    @Override
    public void tick() {
        super.tick();
        if (!heldKeys.isEmpty()) {
            heldKeys.removeIf(k -> !InputConstants.isKeyDown(k));
        }
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        heldKeys.remove(event.key());
        return super.keyReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.hasControlDown() && (key == InputConstants.KEY_Z || key == InputConstants.KEY_Y)) {
            undoLastCard();
            return true;
        }
        if (key == InputConstants.KEY_K && !isGatekeepActive()) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_R && currentCard != null) {
            replayCurrentAudio();
            return true;
        }
        if (currentCard != null && isCardActionKey(key)) {
            // Ignore held / OS-repeated keys and brief accidental presses right as the popup appears
            if (!heldKeys.add(key)) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - screenOpenedMs < 250L) {
                return true;
            }
            if (!answerShown && (key == InputConstants.KEY_SPACE || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)) {
                revealAnswer();
                return true;
            }
            if (answerShown) {
                if (key == InputConstants.KEY_SPACE) {
                    if (now - answerRevealedMs >= 250L) {
                        rateCard(3);
                    }
                    return true;
                }
                if (key == InputConstants.KEY_3 || key == InputConstants.KEY_NUMPAD3) { rateCard(3); return true; }
                if (key == InputConstants.KEY_1 || key == InputConstants.KEY_NUMPAD1) { rateCard(1); return true; }
                if (key == InputConstants.KEY_2 || key == InputConstants.KEY_NUMPAD2) { rateCard(2); return true; }
                if (key == InputConstants.KEY_4 || key == InputConstants.KEY_NUMPAD4) { rateCard(4); return true; }
            }
        }
        return super.keyPressed(event);
    }

    private static boolean isCardActionKey(int key) {
        return key == InputConstants.KEY_SPACE
                || key == InputConstants.KEY_RETURN
                || key == InputConstants.KEY_NUMPADENTER
                || key == InputConstants.KEY_1 || key == InputConstants.KEY_NUMPAD1
                || key == InputConstants.KEY_2 || key == InputConstants.KEY_NUMPAD2
                || key == InputConstants.KEY_3 || key == InputConstants.KEY_NUMPAD3
                || key == InputConstants.KEY_4 || key == InputConstants.KEY_NUMPAD4;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && event.y() >= viewTop && event.y() <= viewBottom) {
            for (AudioHitbox hb : audioHitboxes) {
                if (event.x() >= hb.x() && event.x() <= hb.x() + hb.width()
                        && event.y() >= hb.y() && event.y() <= hb.y() + hb.height()) {
                    manager.getAudioPlayer().play(hb.filename());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollYDelta) {
        int viewH = viewBottom - viewTop;
        if (contentHeight > viewH) {
            this.scrollY = Math.max(0, Math.min(contentHeight - viewH, this.scrollY - scrollYDelta * 22));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollYDelta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        // Kein Blur / kein Vollbild-Dimming, damit die Server-BossBar oben klar sichtbar bleibt
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        // Card container
        gfx.fill(cardX, cardY, cardX + cardW, cardY + cardH, BG_CARD);
        drawBorder(gfx, cardX, cardY, cardW, cardH, isGatekeepActive() ? ACCENT_AMBER : BORDER_COLOR);

        // Header bar
        gfx.fill(cardX + 1, cardY + 1, cardX + cardW - 1, cardY + 32, BG_HEADER);
        gfx.fill(cardX + 1, cardY + 32, cardX + cardW - 1, cardY + 33, BORDER_COLOR);

        // Status dot + Deck title
        int dotColor = manager.isAnkiConnected() ? ACCENT_EMERALD : ACCENT_AMBER;
        gfx.fill(cardX + 12, cardY + 13, cardX + 18, cardY + 19, dotColor);

        String title = currentCard != null ? currentCard.deckName : "AnkiCraft";
        if (font.width(title) > cardW - 220) {
            title = font.plainSubstrByWidth(title, cardW - 230) + "…";
        }
        gfx.text(font, title, cardX + 24, cardY + 12, 0xFFF8FAFC, false);

        // Streak pill
        String streakText = "Streak: " + manager.getStreak();
        int streakX = cardX + cardW - (currentCard != null && hasAnyAudio() ? 238 : 184);
        if (streakX > cardX + 24 + font.width(title) + 8) {
            gfx.text(font, streakText, streakX, cardY + 12, manager.getStreak() > 0 ? ACCENT_SKY : TEXT_MUTED, false);
        }

        // Content area
        if (currentCard == null) {
            gfx.centeredText(font, "Keine fälligen Karten im ausgewählten Deck!", cardX + cardW / 2, cardY + cardH / 2 - 18, 0xFFF8FAFC);
            String hint = manager.isAnkiConnected()
                    ? "Wähle ein anderes Deck oder synchronisiere erneut."
                    : "Starte Anki Desktop (mit AnkiConnect) und klicke auf Sync.";
            gfx.centeredText(font, hint, cardX + cardW / 2, cardY + cardH / 2 - 2, TEXT_MUTED);
        } else {
            AnkiCraftConfig cfg = manager.getConfig();
            CardContentRenderer renderer = manager.getContentRenderer();
            String playingAudio = manager.getAudioPlayer().getPlayingFilename();
            audioHitboxes.clear();

            int drawY = viewTop - (int) scrollY;
            gfx.enableScissor(viewLeft - 4, viewTop, viewLeft + viewWidth + 4, viewBottom);

            int qH = renderer.renderAndMeasure(gfx, font, questionBlocks, viewLeft, drawY, viewWidth, cfg.latexScale, true, audioHitboxes, playingAudio);
            int total = qH;

            if (answerShown) {
                int divY = drawY + qH + 6;
                gfx.fill(viewLeft, divY, viewLeft + viewWidth, divY + 1, ACCENT_SKY);
                gfx.text(font, "Antwort", viewLeft, divY + 6, ACCENT_SKY, false);
                int aH = renderer.renderAndMeasure(gfx, font, answerBlocks, viewLeft, divY + 20, viewWidth, cfg.latexScale, true, audioHitboxes, playingAudio);
                total += 26 + aH;
            }
            this.contentHeight = total;
            gfx.disableScissor();

            // Scrollbar
            int viewH = viewBottom - viewTop;
            if (contentHeight > viewH) {
                int barH = Math.max(20, (int) ((double) viewH * viewH / contentHeight));
                int barY = viewTop + (int) ((scrollY / (contentHeight - viewH)) * (viewH - barH));
                int barX = cardX + cardW - 8;
                gfx.fill(barX, viewTop, barX + 3, viewBottom, 0x33FFFFFF);
                gfx.fill(barX, barY, barX + 3, barY + barH, ACCENT_SKY);
            }
        }

        // Anki-style due counter bar above footer
        AnkiManager.DueSummary due = manager.getDueSummary();
        int dueBarY = cardY + cardH - 55;
        String newStr = due.newCount() + " Neu";
        String learnStr = due.learnCount() + " Lernen";
        String revStr = due.reviewCount() + " Wiederh.";
        String totalStr = " | Noch fällig: " + due.total();
        int sepW = font.width("  ");
        int totalW = font.width(newStr) + sepW + font.width(learnStr) + sepW + font.width(revStr) + font.width(totalStr);
        int dx = cardX + (cardW - totalW) / 2;
        gfx.text(font, newStr, dx, dueBarY, ACCENT_SKY, false);
        dx += font.width(newStr) + sepW;
        gfx.text(font, learnStr, dx, dueBarY, ACCENT_ROSE, false);
        dx += font.width(learnStr) + sepW;
        gfx.text(font, revStr, dx, dueBarY, ACCENT_EMERALD, false);
        dx += font.width(revStr);
        gfx.text(font, totalStr, dx, dueBarY, TEXT_MUTED, false);

        // Footer accent line
        gfx.fill(cardX + 1, cardY + cardH - 42, cardX + cardW - 1, cardY + cardH - 41, BORDER_COLOR);
        String scMsg = manager.getActiveSleepCardsMessage();
        String bannerText = (!undoBanner.isEmpty() && System.currentTimeMillis() < undoBannerUntilMs)
                ? undoBanner
                : scMsg;
        if (!bannerText.isEmpty()) {
            int bw = font.width(bannerText) + 16;
            int bx = cardX + (cardW - bw) / 2;
            int by = cardY + cardH - 74;
            gfx.fill(bx, by, bx + bw, by + 15, 0xDD0369A1);
            gfx.centeredText(font, bannerText, cardX + cardW / 2, by + 3, 0xFFF8FAFC);
        }
        if (answerShown && currentCard != null) {
            int footerY = cardY + cardH - 34;
            int gap = 8;
            int bw = (cardW - 32 - gap * 3) / 4;
            int bx = cardX + 16;
            gfx.fill(bx, footerY - 2, bx + bw, footerY, ACCENT_ROSE);
            gfx.fill(bx + (bw + gap), footerY - 2, bx + (bw + gap) + bw, footerY, ACCENT_AMBER);
            gfx.fill(bx + (bw + gap) * 2, footerY - 2, bx + (bw + gap) * 2 + bw, footerY, ACCENT_EMERALD);
            gfx.fill(bx + (bw + gap) * 3, footerY - 2, bx + (bw + gap) * 3 + bw, footerY, ACCENT_SKY);
        }

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }

    private static void drawBorder(GuiGraphicsExtractor gfx, int x, int y, int w, int h, int color) {
        gfx.fill(x, y, x + w, y + 1, color);
        gfx.fill(x, y + h - 1, x + w, y + h, color);
        gfx.fill(x, y, x + 1, y + h, color);
        gfx.fill(x + w - 1, y, x + w, y + h, color);
    }
}
