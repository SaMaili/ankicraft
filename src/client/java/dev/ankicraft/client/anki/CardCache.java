package dev.ankicraft.client.anki;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.anki.AnkiModels.AnkiCard;
import dev.ankicraft.client.anki.AnkiModels.AnkiDeck;
import dev.ankicraft.client.anki.AnkiModels.PendingReview;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CardCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir().resolve("ankicraft");
    private static final Path CACHE_PATH = BASE_DIR.resolve("cache.json");
    private static final Path MEDIA_DIR = BASE_DIR.resolve("media");

    public List<AnkiDeck> decks = new ArrayList<>();
    public List<AnkiCard> cards = new ArrayList<>();
    public List<PendingReview> pendingReviews = new ArrayList<>();
    public long lastSyncMs = 0L;

    public static CardCache load() {
        if (Files.exists(CACHE_PATH)) {
            try {
                CardCache loaded = GSON.fromJson(Files.readString(CACHE_PATH), CardCache.class);
                if (loaded != null) {
                    if (loaded.decks == null) loaded.decks = new ArrayList<>();
                    if (loaded.cards == null) loaded.cards = new ArrayList<>();
                    if (loaded.pendingReviews == null) loaded.pendingReviews = new ArrayList<>();
                    return loaded;
                }
            } catch (Exception e) {
                AnkiCraftMod.LOGGER.warn("Failed to load AnkiCraft cache, starting fresh", e);
            }
        }
        return new CardCache();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(BASE_DIR);
            Files.writeString(CACHE_PATH, GSON.toJson(this));
        } catch (IOException e) {
            AnkiCraftMod.LOGGER.error("Failed to save AnkiCraft cache", e);
        }
    }

    public static Path getMediaPath(String filename) {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return MEDIA_DIR.resolve(safe);
    }

    public static void saveMedia(String filename, byte[] bytes) {
        if (bytes == null) return;
        try {
            Files.createDirectories(MEDIA_DIR);
            Files.write(getMediaPath(filename), bytes);
        } catch (IOException e) {
            AnkiCraftMod.LOGGER.warn("Failed to cache media file {}", filename, e);
        }
    }

    public static byte[] loadMedia(String filename) {
        Path p = getMediaPath(filename);
        if (Files.exists(p)) {
            try {
                return Files.readAllBytes(p);
            } catch (IOException ignored) {}
        }
        return null;
    }
}
