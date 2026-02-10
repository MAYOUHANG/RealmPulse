package com.realmpulse;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RawLearningStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Set<String> lines = new LinkedHashSet<>();
    private final Object lock = new Object();

    public RawLearningStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "learned-raw.yml");
        load();
        ensureFileExists();
    }

    public void addRaw(String text, int maxSize) {
        String cleaned = sanitize(text);
        if (cleaned.isEmpty()) {
            return;
        }
        synchronized (lock) {
            lines.remove(cleaned);
            lines.add(cleaned);
            while (lines.size() > Math.max(100, maxSize)) {
                String first = lines.iterator().next();
                lines.remove(first);
            }
        }
        saveAsync();
    }

    public int size() {
        synchronized (lock) {
            return lines.size();
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> loaded = yaml.getStringList("raw");
        synchronized (lock) {
            for (String s : loaded) {
                String cleaned = sanitize(s);
                if (!cleaned.isEmpty()) {
                    lines.add(cleaned);
                }
            }
        }
    }

    private void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    private void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        synchronized (lock) {
            yaml.set("raw", new ArrayList<>(lines));
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
        if (!file.exists()) {
            saveNow();
        }
    }

    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim().replaceAll("\\s+", " ");
        if (value.length() > 160) {
            value = value.substring(0, 160).trim();
        }
        return value;
    }
}
