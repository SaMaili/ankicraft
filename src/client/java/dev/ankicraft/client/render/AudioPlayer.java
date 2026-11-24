package dev.ankicraft.client.render;

import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.anki.AnkiConnectClient;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plays Anki card audio files ([sound:...] / &lt;audio&gt;) fetched via AnkiConnect
 * and cached under config/ankicraft/media/.
 */
public class AudioPlayer {
    private static final Path MEDIA_DIR = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("ankicraft")
            .resolve("media");
    private static final boolean IN_FLATPAK = Files.exists(Path.of("/.flatpak-info"));

    private final AnkiConnectClient ankiClient;
    private final AtomicLong playSeq = new AtomicLong(0);
    private volatile Process activeProcess;
    private volatile Clip activeClip;
    private volatile String playingFilename;

    public AudioPlayer(AnkiConnectClient ankiClient) {
        this.ankiClient = ankiClient;
    }

    public String getPlayingFilename() {
        return playingFilename;
    }

    public void play(String filename) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        final long seq = playSeq.incrementAndGet();
        stopPlaybackOnly();
        playingFilename = filename;

        loadOrFetchMedia(filename).thenAccept(path -> {
            if (path == null || playSeq.get() != seq) {
                if (playSeq.get() == seq) {
                    playingFilename = null;
                }
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    playFile(path, seq);
                } finally {
                    if (playSeq.get() == seq) {
                        playingFilename = null;
                    }
                }
            }, "AnkiCraftAudio");
            t.setDaemon(true);
            t.start();
        });
    }

    public void stop() {
        playSeq.incrementAndGet();
        playingFilename = null;
        stopPlaybackOnly();
    }

    private void stopPlaybackOnly() {
        Process p = activeProcess;
        activeProcess = null;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        Clip c = activeClip;
        activeClip = null;
        if (c != null) {
            try {
                c.stop();
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private CompletableFuture<Path> loadOrFetchMedia(String filename) {
        String safeName = filename.replace("\\", "/");
        int slash = safeName.lastIndexOf('/');
        if (slash >= 0) {
            safeName = safeName.substring(slash + 1);
        }
        safeName = safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Path cached = MEDIA_DIR.resolve(safeName);
        if (Files.isRegularFile(cached)) {
            return CompletableFuture.completedFuture(cached);
        }

        return ankiClient.retrieveMediaFile(filename).thenApply(bytes -> {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            try {
                Files.createDirectories(MEDIA_DIR);
                Files.write(cached, bytes);
                return cached;
            } catch (Exception e) {
                AnkiCraftMod.LOGGER.warn("Failed to cache Anki audio {}", filename, e);
                return null;
            }
        });
    }

    private void playFile(Path file, long seq) {
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav") && tryPlayWav(file, seq)) {
            return;
        }

        String absPath = file.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        List<List<String>> candidates;
        if (os.contains("win")) {
            candidates = List.of(
                    List.of("powershell", "-NoProfile", "-Command",
                            "Add-Type -AssemblyName presentationCore; " +
                            "$p = New-Object System.Windows.Media.MediaPlayer; " +
                            "$p.Open([Uri]::new('" + absPath.replace("'", "''") + "')); " +
                            "$p.Play(); Start-Sleep -Seconds 1; " +
                            "while($p.Position -lt $p.NaturalDuration.TimeSpan){ Start-Sleep -Milliseconds 150 }")
            );
        } else if (os.contains("mac")) {
            candidates = List.of(List.of("afplay", absPath));
        } else {
            if (IN_FLATPAK) {
                candidates = List.of(
                        List.of("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", absPath),
                        List.of("mpv", "--no-video", "--really-quiet", absPath),
                        List.of("flatpak-spawn", "--host", "ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", absPath),
                        List.of("flatpak-spawn", "--host", "mpv", "--no-video", "--really-quiet", absPath),
                        List.of("flatpak-spawn", "--host", "pw-play", absPath),
                        List.of("flatpak-spawn", "--host", "paplay", absPath)
                );
            } else {
                candidates = List.of(
                        List.of("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", absPath),
                        List.of("mpv", "--no-video", "--really-quiet", absPath),
                        List.of("pw-play", absPath),
                        List.of("paplay", absPath)
                );
            }
        }

        for (List<String> cmd : candidates) {
            if (playSeq.get() != seq) {
                return;
            }
            if (tryRunProcess(cmd, seq)) {
                return;
            }
        }
        AnkiCraftMod.LOGGER.warn("No supported audio player found for {}", absPath);
    }

    private boolean tryPlayWav(Path file, long seq) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
                Clip clip = AudioSystem.getClip();
                activeClip = clip;
                final Object done = new Object();
                clip.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) {
                        synchronized (done) {
                            done.notifyAll();
                        }
                    }
                });
                clip.open(ais);
                if (playSeq.get() != seq) {
                    clip.close();
                    return true;
                }
                clip.start();
                synchronized (done) {
                    while (clip.isRunning() && playSeq.get() == seq) {
                        done.wait(200);
                    }
                }
                clip.close();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryRunProcess(List<String> cmd, long seq) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            activeProcess = p;
            int exit = p.waitFor();
            if (playSeq.get() != seq) {
                return true;
            }
            return exit == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
