package dev.ankicraft.client.render;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CardContentParser {
    private CardContentParser() {}

    public sealed interface ContentBlock permits ParagraphBlock, DisplayMathBlock, CodeBlock, ImageBlock, DividerBlock {}

    public record InlineSpan(SpanType type, String content, String extra) {
        public InlineSpan(SpanType type, String content) {
            this(type, content, "");
        }
    }

    public enum SpanType {
        TEXT,
        BOLD,
        ITALIC,
        CODE,
        CLOZE,
        INLINE_MATH,
        FURIGANA
    }

    public record ParagraphBlock(List<InlineSpan> spans, boolean isBullet) implements ContentBlock {}
    public record DisplayMathBlock(String latex) implements ContentBlock {}
    public record CodeBlock(String code) implements ContentBlock {}
    public record ImageBlock(String filename) implements ContentBlock {}
    public record DividerBlock() implements ContentBlock {}

    private static final Pattern STYLE_SCRIPT_PATTERN = Pattern.compile("(?is)<(style|script)[^>]*>.*?</\\1>");
    private static final Pattern SOUND_PATTERN = Pattern.compile("(?i)\\[sound:[^\\]]+\\]");
    private static final Pattern RUBY_INNER_PAIR = Pattern.compile("(?is)(.*?)<rt[^>]*>(.*?)</rt>");
    private static final Pattern BLOCK_TOKEN_PATTERN = Pattern.compile(
            "(?is)" +
            "(\\$\\$(.+?)\\$\\$)" +
            "|(\\\\\\[(.+?)\\\\\\])" +
            "|(\\[\\$\\$\\](.+?)\\[/\\$\\$\\])" +
            "|(<pre[^>]*>(.*?)</pre>)" +
            "|(<img[^>]*?src\\s*=\\s*(?:[\"']([^\"']+)[\"']|([^\\s>]+))[^>]*>)" +
            "|(<hr\\s*/?>)"
    );

    private static final Pattern INLINE_PATTERN = Pattern.compile(
            "(?is)" +
            "(?<!\\\\)\\$([^$\\n]+?)(?<!\\\\)\\$" +
            "|\\\\\\((.+?)\\\\\\)" +
            "|\\[\\$\\](.+?)\\[/\\$\\]" +
            "|\\[latex\\](.+?)\\[/latex\\]" +
            "|<ruby[^>]*>(.*?)</ruby>" +
            "|([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}々ヶ]+)\\[([\\p{IsHiragana}\\p{IsKatakana}ー・]+)\\]" +
            "|<code[^>]*>(.*?)</code>" +
            "|<span[^>]*class=[\"'][^\"']*cloze[^\"']*[\"'][^>]*>(.*?)</span>" +
            "|<(b|strong)[^>]*>(.*?)</\\9>" +
            "|<(i|em)[^>]*>(.*?)</\\11>"
    );

    public static List<ContentBlock> parse(String html) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return blocks;
        }

        String cleaned = STYLE_SCRIPT_PATTERN.matcher(html).replaceAll("");
        cleaned = SOUND_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n");

        Matcher m = BLOCK_TOKEN_PATTERN.matcher(cleaned);
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() > lastEnd) {
                parseHtmlTextChunk(cleaned.substring(lastEnd, m.start()), blocks);
            }
            if (m.group(2) != null) {
                blocks.add(new DisplayMathBlock(unescapeHtml(m.group(2).trim())));
            } else if (m.group(4) != null) {
                blocks.add(new DisplayMathBlock(unescapeHtml(m.group(4).trim())));
            } else if (m.group(6) != null) {
                blocks.add(new DisplayMathBlock(unescapeHtml(m.group(6).trim())));
            } else if (m.group(8) != null) {
                String code = stripTags(m.group(8).replaceAll("(?i)<br\\s*/?>", "\n"));
                blocks.add(new CodeBlock(unescapeHtml(code).strip()));
            } else if (m.group(10) != null || m.group(11) != null) {
                String rawSrc = m.group(10) != null ? m.group(10) : m.group(11);
                blocks.add(new ImageBlock(normalizeImageSrc(rawSrc)));
            } else if (m.group(12) != null) {
                blocks.add(new DividerBlock());
            }
            lastEnd = m.end();
        }
        if (lastEnd < cleaned.length()) {
            parseHtmlTextChunk(cleaned.substring(lastEnd), blocks);
        }
        return blocks;
    }

    private static void parseHtmlTextChunk(String chunk, List<ContentBlock> out) {
        String s = chunk
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|tr)>", "\n")
                .replaceAll("(?i)<(p|div|h[1-6]|tr)[^>]*>", "\n")
                .replaceAll("(?i)<li[^>]*>", "\n• ")
                .replaceAll("(?i)</li>", "\n");

        String[] lines = s.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            boolean isBullet = false;
            if (trimmed.startsWith("• ")) {
                isBullet = true;
                trimmed = trimmed.substring(2).trim();
            }
            List<InlineSpan> spans = parseInline(trimmed);
            if (!spans.isEmpty()) {
                out.add(new ParagraphBlock(spans, isBullet));
            }
        }
    }

    private static List<InlineSpan> parseInline(String line) {
        List<InlineSpan> spans = new ArrayList<>();
        Matcher m = INLINE_PATTERN.matcher(line);
        int lastEnd = 0;

        while (m.find()) {
            if (m.start() > lastEnd) {
                String plain = unescapeHtml(stripTags(line.substring(lastEnd, m.start())));
                if (!plain.isEmpty()) {
                    spans.add(new InlineSpan(SpanType.TEXT, plain));
                }
            }
            if (m.group(1) != null) {
                spans.add(new InlineSpan(SpanType.INLINE_MATH, unescapeHtml(m.group(1).trim())));
            } else if (m.group(2) != null) {
                spans.add(new InlineSpan(SpanType.INLINE_MATH, unescapeHtml(m.group(2).trim())));
            } else if (m.group(3) != null) {
                spans.add(new InlineSpan(SpanType.INLINE_MATH, unescapeHtml(m.group(3).trim())));
            } else if (m.group(4) != null) {
                spans.add(new InlineSpan(SpanType.INLINE_MATH, unescapeHtml(m.group(4).trim())));
            } else if (m.group(5) != null) {
                parseRubyTag(m.group(5), spans);
            } else if (m.group(6) != null && m.group(7) != null) {
                spans.add(new InlineSpan(SpanType.FURIGANA, unescapeHtml(m.group(6)), unescapeHtml(m.group(7))));
            } else if (m.group(8) != null) {
                spans.add(new InlineSpan(SpanType.CODE, unescapeHtml(stripTags(m.group(8)))));
            } else if (m.group(9) != null) {
                spans.add(new InlineSpan(SpanType.CLOZE, unescapeHtml(stripTags(m.group(9)))));
            } else if (m.group(11) != null) {
                spans.add(new InlineSpan(SpanType.BOLD, unescapeHtml(stripTags(m.group(11)))));
            } else if (m.group(13) != null) {
                spans.add(new InlineSpan(SpanType.ITALIC, unescapeHtml(stripTags(m.group(13)))));
            }
            lastEnd = m.end();
        }
        if (lastEnd < line.length()) {
            String plain = unescapeHtml(stripTags(line.substring(lastEnd)));
            if (!plain.isEmpty()) {
                spans.add(new InlineSpan(SpanType.TEXT, plain));
            }
        }
        return spans;
    }

    private static void parseRubyTag(String rubyInner, List<InlineSpan> spans) {
        String noRp = rubyInner.replaceAll("(?is)<rp[^>]*>.*?</rp>", "");
        Matcher rm = RUBY_INNER_PAIR.matcher(noRp);
        int lastEnd = 0;
        boolean foundPair = false;
        while (rm.find()) {
            foundPair = true;
            String base = unescapeHtml(stripTags(rm.group(1))).trim();
            String rt = unescapeHtml(stripTags(rm.group(2))).trim();
            if (!base.isEmpty() && !rt.isEmpty()) {
                spans.add(new InlineSpan(SpanType.FURIGANA, base, rt));
            } else if (!base.isEmpty()) {
                spans.add(new InlineSpan(SpanType.TEXT, base));
            }
            lastEnd = rm.end();
        }
        if (!foundPair) {
            String plain = unescapeHtml(stripTags(noRp)).trim();
            if (!plain.isEmpty()) {
                spans.add(new InlineSpan(SpanType.TEXT, plain));
            }
        } else if (lastEnd < noRp.length()) {
            String tail = unescapeHtml(stripTags(noRp.substring(lastEnd))).trim();
            if (!tail.isEmpty()) {
                spans.add(new InlineSpan(SpanType.TEXT, tail));
            }
        }
    }

    private static String normalizeImageSrc(String raw) {
        String s = unescapeHtml(raw.trim());
        if (s.startsWith("data:")) {
            return s;
        }
        int qIdx = s.indexOf('?');
        if (qIdx >= 0) {
            s = s.substring(0, qIdx);
        }
        try {
            s = URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {}
        return s;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "");
    }

    private static String unescapeHtml(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
