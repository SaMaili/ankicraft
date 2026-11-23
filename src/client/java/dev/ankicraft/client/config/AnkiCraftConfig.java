package dev.ankicraft.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ankicraft.AnkiCraftMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AnkiCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("ankicraft")
            .resolve("config.json");

    public String ankiConnectUrl = "http://127.0.0.1:8765";
    public String ankiConnectApiKey = "";
    public List<String> selectedDecks = new ArrayList<>();

    public boolean timerEnabled = true;
    public int quizIntervalSeconds = 300;
    public boolean manualQuizDoesNotResetTimer = true;
    public boolean blockBreakTriggerEnabled = false;
    public int blocksPerQuiz = 64;
    public boolean sleepTriggerEnabled = true;
    public boolean pauseInSingleplayer = true;

    public boolean rewardsEnabled = true;
    public boolean penaltiesEnabled = false;
    public boolean gatekeepingEnabled = false;

    public boolean showHudOverlay = true;
    public float latexScale = 1.0f;
    public int autoSyncMinutes = 5;

    public static AnkiCraftConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                AnkiCraftConfig loaded = GSON.fromJson(json, AnkiCraftConfig.class);
                if (loaded != null) {
                    if (loaded.selectedDecks == null) loaded.selectedDecks = new ArrayList<>();
                    if (loaded.ankiConnectUrl == null || loaded.ankiConnectUrl.isBlank()) {
                        loaded.ankiConnectUrl = "http://127.0.0.1:8765";
                    }
                    loaded.quizIntervalSeconds = Math.max(30, loaded.quizIntervalSeconds);
                    loaded.blocksPerQuiz = Math.max(8, loaded.blocksPerQuiz);
                    loaded.latexScale = Math.max(0.6f, Math.min(2.0f, loaded.latexScale));
                    return loaded;
                }
            } catch (Exception e) {
                AnkiCraftMod.LOGGER.warn("Failed to load AnkiCraft config, using defaults", e);
            }
        }
        AnkiCraftConfig cfg = new AnkiCraftConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            AnkiCraftMod.LOGGER.error("Failed to save AnkiCraft config", e);
        }
    }
}
