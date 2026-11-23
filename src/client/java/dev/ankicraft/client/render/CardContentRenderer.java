package dev.ankicraft.client.render;

import dev.ankicraft.client.anki.AnkiConnectClient;
import dev.ankicraft.client.render.CardContentParser.*;
import dev.ankicraft.client.render.LatexRenderer.RenderedTexture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

public class CardContentRenderer {
    private static final int TEXT_COLOR = 0xFFF1F5F9;
    private static final int BULLET_COLOR = 0xFF38BDF8;
    private static final int CODE_BG = 0xFF0F172A;
    private static final int CODE_BORDER = 0xFF334155;
    private static final int CODE_TEXT = 0xFFA5F3FC;
    private static final int CLOZE_BG = 0x4438BDF8;
    private static final int CLOZE_TEXT = 0xFF7DD3FC;
    private static final int DIVIDER_COLOR = 0xFF334155;

    private final LatexRenderer latexRenderer;
    private final AnkiConnectClient ankiClient;

    public CardContentRenderer(LatexRenderer latexRenderer, AnkiConnectClient ankiClient) {
        this.latexRenderer = latexRenderer;
        this.ankiClient = ankiClient;
    }

    public int renderAndMeasure(
            GuiGraphicsExtractor gfx,
            Font font,
            List<ContentBlock> blocks,
            int x,
            int y,
            int maxWidth,
            float latexScale,
            boolean draw
    ) {
        int curY = y;
        float inlineFontSize = 15.0f * latexScale;
        float displayFontSize = 19.0f * latexScale;

        for (ContentBlock block : blocks) {
            switch (block) {
                case DividerBlock ignored -> {
                    if (draw) {
                        gfx.fill(x, curY + 4, x + maxWidth, curY + 5, DIVIDER_COLOR);
                    }
                    curY += 10;
                }
                case DisplayMathBlock math -> {
                    RenderedTexture tex = latexRenderer.getOrRenderLatex(math.latex(), displayFontSize, TEXT_COLOR);
                    if (!tex.failed() && tex.textureId() != null) {
                        int drawW = tex.width();
                        int drawH = tex.height();
                        if (drawW > maxWidth - 16) {
                            float ratio = (float) (maxWidth - 16) / drawW;
                            drawW = Math.round(drawW * ratio);
                            drawH = Math.round(drawH * ratio);
                        }
                        if (draw) {
                            int boxX = x + (maxWidth - drawW) / 2;
                            gfx.fill(boxX - 6, curY + 1, boxX + drawW + 6, curY + drawH + 7, 0x330F172A);
                            gfx.blit(RenderPipelines.GUI_TEXTURED, tex.textureId(), boxX, curY + 4, 0f, 0f, drawW, drawH, drawW, drawH);
                        }
                        curY += drawH + 12;
                    } else {
                        String fallback = "$$" + math.latex() + "$$";
                        if (draw) {
                            gfx.centeredText(font, fallback, x + maxWidth / 2, curY + 2, 0xFFFCA5A5);
                        }
                        curY += font.lineHeight + 8;
                    }
                }
                case CodeBlock code -> {
                    String[] lines = code.code().split("\n");
                    List<String> wrapped = new ArrayList<>();
                    for (String line : lines) {
                        wrapped.addAll(wrapPlain(font, line, maxWidth - 16));
                    }
                    int boxH = wrapped.size() * (font.lineHeight + 2) + 10;
                    if (draw) {
                        gfx.fill(x, curY, x + maxWidth, curY + boxH, CODE_BG);
                        gfx.fill(x, curY, x + maxWidth, curY + 1, CODE_BORDER);
                        gfx.fill(x, curY + boxH - 1, x + maxWidth, curY + boxH, CODE_BORDER);
                        gfx.fill(x, curY, x + 1, curY + boxH, CODE_BORDER);
                        gfx.fill(x + maxWidth - 1, curY, x + maxWidth, curY + boxH, CODE_BORDER);

                        int ly = curY + 5;
                        for (String wl : wrapped) {
                            gfx.text(font, wl, x + 8, ly, CODE_TEXT, false);
                            ly += font.lineHeight + 2;
                        }
                    }
                    curY += boxH + 6;
                }
                case ImageBlock img -> {
                    RenderedTexture tex = latexRenderer.getOrLoadImage(img.filename(), ankiClient);
                    if (tex != null && !tex.failed() && tex.textureId() != null) {
                        int drawW = tex.width();
                        int drawH = tex.height();
                        int maxImgW = maxWidth - 12;
                        int maxImgH = 150;
                        if (drawW > maxImgW) {
                            float r = (float) maxImgW / drawW;
                            drawW = Math.round(drawW * r);
                            drawH = Math.round(drawH * r);
                        }
                        if (drawH > maxImgH) {
                            float r = (float) maxImgH / drawH;
                            drawW = Math.round(drawW * r);
                            drawH = Math.round(drawH * r);
                        }
                        if (draw) {
                            int ix = x + (maxWidth - drawW) / 2;
                            gfx.blit(RenderPipelines.GUI_TEXTURED, tex.textureId(), ix, curY + 2, 0f, 0f, drawW, drawH, drawW, drawH);
                        }
                        curY += drawH + 8;
                    } else {
                        if (draw) {
                            gfx.centeredText(font, "[Bild: " + img.filename() + "]", x + maxWidth / 2, curY + 2, 0xFF94A3B8);
                        }
                        curY += font.lineHeight + 6;
                    }
                }
                case ParagraphBlock para -> {
                    int startX = para.isBullet() ? x + 12 : x;
                    int availW = para.isBullet() ? maxWidth - 12 : maxWidth;
                    if (draw && para.isBullet()) {
                        gfx.text(font, "•", x + 2, curY + 1, BULLET_COLOR, false);
                    }
                    curY += renderParagraph(gfx, font, para.spans(), startX, curY, availW, inlineFontSize, draw);
                    curY += 5;
                }
            }
        }
        return curY - y;
    }

    private int renderParagraph(
            GuiGraphicsExtractor gfx,
            Font font,
            List<InlineSpan> spans,
            int startX,
            int startY,
            int maxWidth,
            float inlineFontSize,
            boolean draw
    ) {
        boolean hasFurigana = spans.stream().anyMatch(s -> s.type() == SpanType.FURIGANA);
        int rubyTopPad = hasFurigana ? 7 : 0;
        int curX = startX;
        int curY = startY;
        int lineH = font.lineHeight + 4 + rubyTopPad;

        for (InlineSpan span : spans) {
            if (span.type() == SpanType.FURIGANA) {
                String base = span.content();
                String reading = span.extra();
                int baseW = font.width(base);
                float rubyScale = 0.6f;
                int rubyW = Math.round(font.width(reading) * rubyScale);
                int slotW = Math.max(baseW, rubyW);

                if (curX > startX && curX + slotW > startX + maxWidth) {
                    curX = startX;
                    curY += lineH;
                    lineH = font.lineHeight + 4 + rubyTopPad;
                }
                if (draw) {
                    int baseX = curX + (slotW - baseW) / 2;
                    int rubyX = curX + (slotW - rubyW) / 2;
                    gfx.text(font, base, baseX, curY + rubyTopPad, TEXT_COLOR, false);

                    gfx.pose().pushMatrix();
                    gfx.pose().translate(rubyX, curY);
                    gfx.pose().scale(rubyScale, rubyScale);
                    gfx.text(font, reading, 0, 0, 0xFF93C5FD, false);
                    gfx.pose().popMatrix();
                }
                curX += slotW + 2;
                continue;
            }
            if (span.type() == SpanType.INLINE_MATH) {
                RenderedTexture tex = latexRenderer.getOrRenderLatex(span.content(), inlineFontSize, TEXT_COLOR);
                if (!tex.failed() && tex.textureId() != null) {
                    int w = tex.width();
                    int h = tex.height();
                    if (w > maxWidth) {
                        float r = (float) maxWidth / w;
                        w = Math.round(w * r);
                        h = Math.round(h * r);
                    }
                    if (curX > startX && curX + w > startX + maxWidth) {
                        curX = startX;
                        curY += lineH;
                        lineH = font.lineHeight + 4 + rubyTopPad;
                    }
                    lineH = Math.max(lineH, h + 4 + rubyTopPad);
                    if (draw) {
                        int yOffset = rubyTopPad + Math.max(0, (font.lineHeight - h) / 2);
                        gfx.blit(RenderPipelines.GUI_TEXTURED, tex.textureId(), curX, curY + yOffset, 0f, 0f, w, h, w, h);
                    }
                    curX += w + 3;
                    continue;
                }
            }

            String text = span.content();
            String[] words = text.split("(?<=\\s)|(?=\\s)");
            for (String word : words) {
                if (word.isEmpty()) continue;
                int wordW = font.width(word);
                if (curX > startX && curX + wordW > startX + maxWidth && !word.isBlank()) {
                    curX = startX;
                    curY += lineH;
                    lineH = font.lineHeight + 4 + rubyTopPad;
                }
                if (curX == startX && word.isBlank()) {
                    continue;
                }
                if (draw) {
                    int textY = curY + rubyTopPad;
                    switch (span.type()) {
                        case CODE -> {
                            gfx.fill(curX - 1, textY - 1, curX + wordW + 1, textY + font.lineHeight + 1, CODE_BG);
                            gfx.text(font, word, curX, textY, CODE_TEXT, false);
                        }
                        case CLOZE -> {
                            gfx.fill(curX - 1, textY - 1, curX + wordW + 1, textY + font.lineHeight + 1, CLOZE_BG);
                            MutableComponent comp = Component.literal(word).withStyle(ChatFormatting.BOLD);
                            gfx.text(font, comp, curX, textY, CLOZE_TEXT, false);
                        }
                        case BOLD -> {
                            MutableComponent comp = Component.literal(word).withStyle(ChatFormatting.BOLD);
                            gfx.text(font, comp, curX, textY, TEXT_COLOR, false);
                        }
                        case ITALIC -> {
                            MutableComponent comp = Component.literal(word).withStyle(ChatFormatting.ITALIC);
                            gfx.text(font, comp, curX, textY, 0xFFCBD5E1, false);
                        }
                        default -> gfx.text(font, word, curX, textY, TEXT_COLOR, false);
                    }
                }
                curX += wordW;
            }
        }
        return (curY - startY) + lineH;
    }

    private static List<String> wrapPlain(Font font, String line, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (line.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (font.width(current.toString() + c) > maxWidth && !current.isEmpty()) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }
}
