package dev.ankicraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.anki.AnkiConnectClient;
import dev.ankicraft.client.anki.CardCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LatexRenderer {
    static {
        ImageIO.scanForPlugins();
    }

    private static final HttpClient IMAGE_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    public record RenderedTexture(Identifier textureId, int width, int height, boolean failed) {}

    private static final float SUPERSAMPLE = 2.0f;
    private final Map<String, RenderedTexture> latexCache = new ConcurrentHashMap<>();
    private final Map<String, RenderedTexture> imageCache = new ConcurrentHashMap<>();
    private final Set<String> pendingImages = ConcurrentHashMap.newKeySet();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public RenderedTexture getOrRenderLatex(String rawLatex, float fontSize, int argbColor) {
        String key = rawLatex + "|" + fontSize + "|" + argbColor;
        RenderedTexture cached = latexCache.get(key);
        if (cached != null) return cached;

        RenderedTexture rendered = renderLatexInternal(rawLatex, fontSize, argbColor);
        latexCache.put(key, rendered);
        return rendered;
    }

    public RenderedTexture getOrLoadImage(String filename, AnkiConnectClient ankiClient) {
        RenderedTexture cached = imageCache.get(filename);
        if (cached != null) return cached;

        if (filename.startsWith("data:image/")) {
            int comma = filename.indexOf(',');
            if (comma > 0) {
                try {
                    byte[] raw = Base64.getDecoder().decode(filename.substring(comma + 1));
                    RenderedTexture tex = decodeImageToTexture("data_" + idCounter.incrementAndGet(), raw);
                    imageCache.put(filename, tex);
                    return tex;
                } catch (Exception e) {
                    RenderedTexture fail = new RenderedTexture(null, 0, 0, true);
                    imageCache.put(filename, fail);
                    return fail;
                }
            }
        }

        byte[] diskBytes = CardCache.loadMedia(filename);
        if (diskBytes != null) {
            RenderedTexture tex = decodeImageToTexture("media_" + idCounter.incrementAndGet(), diskBytes);
            imageCache.put(filename, tex);
            return tex;
        }

        if ((filename.startsWith("http://") || filename.startsWith("https://")) && pendingImages.add(filename)) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(filename))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            IMAGE_HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenAccept(resp -> {
                pendingImages.remove(filename);
                if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null && resp.body().length > 0) {
                    byte[] bytes = resp.body();
                    CardCache.saveMedia(filename, bytes);
                    Minecraft.getInstance().execute(() -> {
                        RenderedTexture tex = decodeImageToTexture("url_" + idCounter.incrementAndGet(), bytes);
                        imageCache.put(filename, tex);
                    });
                } else {
                    imageCache.put(filename, new RenderedTexture(null, 0, 0, true));
                }
            }).exceptionally(ex -> {
                pendingImages.remove(filename);
                imageCache.put(filename, new RenderedTexture(null, 0, 0, true));
                return null;
            });
            return null;
        }

        if (ankiClient != null && pendingImages.add(filename)) {
            ankiClient.retrieveMediaFile(filename).thenAccept(bytes -> {
                pendingImages.remove(filename);
                if (bytes != null && bytes.length > 0) {
                    CardCache.saveMedia(filename, bytes);
                    Minecraft.getInstance().execute(() -> {
                        RenderedTexture tex = decodeImageToTexture("media_" + idCounter.incrementAndGet(), bytes);
                        imageCache.put(filename, tex);
                    });
                } else {
                    imageCache.put(filename, new RenderedTexture(null, 0, 0, true));
                }
            });
        }
        return null;
    }

    private RenderedTexture renderLatexInternal(String rawLatex, float fontSize, int argbColor) {
        try {
            String latex = preprocessLatex(rawLatex);
            TeXFormula formula = new TeXFormula(latex);
            TeXIcon icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, fontSize * SUPERSAMPLE);
            icon.setInsets(new java.awt.Insets(3, 4, 3, 4));
            icon.setForeground(new Color(argbColor, true));

            int texW = Math.max(1, icon.getIconWidth());
            int texH = Math.max(1, icon.getIconHeight());

            BufferedImage img = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = img.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            icon.paintIcon(null, g2d, 0, 0);
            g2d.dispose();

            Identifier id = registerBufferedImage("latex_" + idCounter.incrementAndGet(), img);
            int logicalW = Math.max(1, Math.round(texW / SUPERSAMPLE));
            int logicalH = Math.max(1, Math.round(texH / SUPERSAMPLE));
            return new RenderedTexture(id, logicalW, logicalH, false);
        } catch (Throwable t) {
            AnkiCraftMod.LOGGER.debug("JLaTeXMath failed for '{}': {}", rawLatex, t.getMessage());
            return new RenderedTexture(null, 0, 0, true);
        }
    }

    private RenderedTexture decodeImageToTexture(String name, byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                byte[] converted = transcodeToPngWithFfmpeg(bytes);
                if (converted != null) {
                    img = ImageIO.read(new ByteArrayInputStream(converted));
                }
            }
            if (img == null) {
                AnkiCraftMod.LOGGER.warn("Could not decode image {} ({} bytes)", name, bytes.length);
                return new RenderedTexture(null, 0, 0, true);
            }
            Identifier id = registerBufferedImage(name, img);
            return new RenderedTexture(id, img.getWidth(), img.getHeight(), false);
        } catch (Exception e) {
            AnkiCraftMod.LOGGER.warn("Failed to decode media image {}", name, e);
            return new RenderedTexture(null, 0, 0, true);
        }
    }

    private static byte[] transcodeToPngWithFfmpeg(byte[] input) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ankicraft_img_", ".bin");
            Files.write(tmp, input);
            Process p = new ProcessBuilder(
                    "ffmpeg", "-y", "-loglevel", "error",
                    "-i", tmp.toAbsolutePath().toString(),
                    "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"
            ).start();
            byte[] out = p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            return (exit == 0 && out.length > 0) ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Identifier registerBufferedImage(String path, BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage nativeImg = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                nativeImg.setPixel(x, y, img.getRGB(x, y));
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath(AnkiCraftMod.MOD_ID, "dynamic/" + path);
        DynamicTexture dyn = new DynamicTexture(() -> "ankicraft_" + path, nativeImg);
        Minecraft.getInstance().getTextureManager().register(id, dyn);
        return id;
    }

    private static String preprocessLatex(String input) {
        String s = input.trim();
        // Common CS & Math macros
        s = s.replaceAll("(?<![a-zA-Z])\\\\R(?![a-zA-Z])", "\\\\mathbb{R}");
        s = s.replaceAll("(?<![a-zA-Z])\\\\N(?![a-zA-Z])", "\\\\mathbb{N}");
        s = s.replaceAll("(?<![a-zA-Z])\\\\Z(?![a-zA-Z])", "\\\\mathbb{Z}");
        s = s.replaceAll("(?<![a-zA-Z])\\\\Q(?![a-zA-Z])", "\\\\mathbb{Q}");
        s = s.replaceAll("(?<![a-zA-Z])\\\\C(?![a-zA-Z])", "\\\\mathbb{C}");
        s = s.replace("\\implies", "\\Rightarrow");
        s = s.replace("\\impliedby", "\\Leftarrow");
        s = s.replace("\\iff", "\\Leftrightarrow");
        s = s.replace("\\begin{align*}", "\\begin{aligned}");
        s = s.replace("\\end{align*}", "\\end{aligned}");
        s = s.replace("\\begin{align}", "\\begin{aligned}");
        s = s.replace("\\end{align}", "\\end{aligned}");
        return s;
    }
}
