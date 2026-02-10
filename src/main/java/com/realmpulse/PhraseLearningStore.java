package com.realmpulse;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PhraseLearningStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, String> phrasesByKey = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final Object saveLock = new Object();
    private boolean saveQueued = false;
    private boolean dirty = false;

    public PhraseLearningStore(JavaPlugin plugin) {
        this(plugin, "learned-phrases.yml");
    }

    public PhraseLearningStore(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        String safeFile = (fileName == null || fileName.isBlank()) ? "learned-phrases.yml" : fileName.trim();
        this.file = new File(plugin.getDataFolder(), safeFile);
        load();
        ensureFileExists();
    }

    public boolean addPhrase(String phrase) {
        String cleaned = sanitize(phrase);
        if (cleaned.isEmpty()) {
            return false;
        }

        String key = normalize(cleaned);
        synchronized (lock) {
            if (phrasesByKey.containsKey(key)) {
                return false;
            }
            phrasesByKey.put(key, cleaned);
        }
        saveAsync();
        return true;
    }

    public List<String> getPhrases() {
        synchronized (lock) {
            return new ArrayList<>(phrasesByKey.values());
        }
    }

    public int size() {
        synchronized (lock) {
            return phrasesByKey.size();
        }
    }

    public int trimToMaxSize(int maxSize) {
        int safeMax = Math.max(1, maxSize);
        int removed = 0;
        synchronized (lock) {
            while (phrasesByKey.size() > safeMax) {
                String firstKey = phrasesByKey.keySet().iterator().next();
                phrasesByKey.remove(firstKey);
                removed++;
            }
        }
        if (removed > 0) {
            saveAsync();
        }
        return removed;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> loaded = yaml.getStringList("phrases");
        synchronized (lock) {
            for (String item : loaded) {
                String cleaned = sanitize(item);
                if (!cleaned.isEmpty()) {
                    phrasesByKey.put(normalize(cleaned), cleaned);
                }
            }
        }
    }

    private void saveAsync() {
        synchronized (saveLock) {
            dirty = true;
            if (saveQueued) {
                return;
            }
            saveQueued = true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::drainPendingSaves);
    }

    private void drainPendingSaves() {
        while (true) {
            synchronized (saveLock) {
                if (!dirty) {
                    saveQueued = false;
                    return;
                }
                dirty = false;
            }
            saveNow();
        }
    }

    private void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        synchronized (lock) {
            yaml.set("phrases", new ArrayList<>(phrasesByKey.values()));
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            yaml.save(file);
        } catch (IOException ignored) {
        }
    }

    private void ensureFileExists() {
        if (file.exists()) {
            return;
        }
        saveNow();
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim().replaceAll("\\s+", " ");
        if (value.length() > 60) {
            value = value.substring(0, 60).trim();
        }
        return value;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
