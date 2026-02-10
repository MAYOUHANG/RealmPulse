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
    private final Object saveLock = new Object();
    private boolean saveQueued = false;
    private boolean dirty = false;

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
            if (trimSetToMax(completed, resolveMaxCompletionsPerGhost())) {
                changed = true;
            }
        }
        if (changed) {
            trimTrackedGhosts();
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
        int maxCompletionsPerGhost = resolveMaxCompletionsPerGhost();
        synchronized (lock) {
            for (String ghostId : root.getKeys(false)) {
                List<String> keys = root.getStringList(ghostId);
                Set<String> cleaned = new LinkedHashSet<>();
                for (String key : keys) {
                    if (key != null && !key.isBlank()) {
                        cleaned.add(key.trim());
                    }
                }
                trimSetToMax(cleaned, maxCompletionsPerGhost);
                if (!cleaned.isEmpty()) {
                    progressByGhost.put(ghostId, cleaned);
                }
            }
        }
        trimTrackedGhosts();
    }

    private void ensureFileExists() {
        if (!file.exists()) {
            saveNow();
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

    private void trimTrackedGhosts() {
        int maxTracked = Math.max(100, plugin.getConfig().getInt("advancement-events.progress-max-tracked-ghosts", 3000));
        boolean removed = false;
        synchronized (lock) {
            while (progressByGhost.size() > maxTracked) {
                String firstKey = progressByGhost.keySet().iterator().next();
                progressByGhost.remove(firstKey);
                removed = true;
            }
        }
        if (removed) {
            saveAsync();
        }
    }

    private int resolveMaxCompletionsPerGhost() {
        return Math.max(1, plugin.getConfig().getInt("advancement-events.max-completions-per-ghost", 300));
    }

    private boolean trimSetToMax(Set<String> values, int maxSize) {
        boolean removed = false;
        while (values.size() > maxSize) {
            String firstKey = values.iterator().next();
            values.remove(firstKey);
            removed = true;
        }
        return removed;
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
