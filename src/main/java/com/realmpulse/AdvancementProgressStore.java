package com.realmpulse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class AdvancementProgressStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Set<String>> progressByGhost = new LinkedHashMap<>();
    private final Object lock = new Object();

    public AdvancementProgressStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "advancement-progress.yml");
        load();
        ensureFileExists();
    }

    public boolean isCompleted(UUID ghostId, String advancementKey) {
        if (ghostId == null || advancementKey == null || advancementKey.isBlank()) {
            return false;
        }
        String id = ghostId.toString();
        synchronized (lock) {
            Set<String> completed = progressByGhost.get(id);
            return completed != null && completed.contains(advancementKey);
        }
    }

    public boolean markCompleted(UUID ghostId, String advancementKey) {
        if (ghostId == null || advancementKey == null || advancementKey.isBlank()) {
            return false;
        }
        String id = ghostId.toString();
        boolean changed;
        synchronized (lock) {
            Set<String> completed = progressByGhost.computeIfAbsent(id, unused -> new LinkedHashSet<>());
            changed = completed.add(advancementKey);
        }
        if (changed) {
            saveAsync();
        }
        return changed;
    }

    public int completedCount(UUID ghostId) {
        if (ghostId == null) {
            return 0;
        }
        synchronized (lock) {
            Set<String> completed = progressByGhost.get(ghostId.toString());
            return completed == null ? 0 : completed.size();
        }
    }

    public int totalGhostsTracked() {
        synchronized (lock) {
            return progressByGhost.size();
        }
    }

    public int totalCompletions() {
        synchronized (lock) {
            int total = 0;
            for (Set<String> values : progressByGhost.values()) {
                total += values.size();
            }
            return total;
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("progress");
        if (root == null) {
            return;
        }
        synchronized (lock) {
            for (String ghostId : root.getKeys(false)) {
                List<String> keys = root.getStringList(ghostId);
                Set<String> cleaned = new LinkedHashSet<>();
                for (String key : keys) {
                    if (key != null && !key.isBlank()) {
                        cleaned.add(key.trim());
                    }
                }
                progressByGhost.put(ghostId, cleaned);
            }
        }
    }

    private void ensureFileExists() {
        if (!file.exists()) {
            saveNow();
        }
    }

    private void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    private void saveNow() {
        YamlConfiguration yaml = new YamlConfiguration();
        synchronized (lock) {
            for (Map.Entry<String, Set<String>> entry : progressByGhost.entrySet()) {
                yaml.set("progress." + entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            yaml.save(file);
        } catch (IOException ignored) {
        }
    }
}
