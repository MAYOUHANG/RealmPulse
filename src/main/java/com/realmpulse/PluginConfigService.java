package com.realmpulse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginConfigService {

    private final JavaPlugin plugin;
    private final Map<String, String> aliases;

    public PluginConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.aliases = buildAliases();
    }

    private Map<String, String> buildAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("core.ghost-count", "ghost-count");
        map.put("core.chat-interval", "chat-interval");
        map.put("chat.format", "chat-format");

        map.put("ai.qa.api-url", "ai-settings.api-url");
        map.put("ai.qa.api-key", "ai-settings.api-key");
        map.put("ai.qa.model", "ai-settings.model");
        map.put("ai.qa.system-prompt", "ai-settings.system-prompt");
        map.put("ai.qa.enabled", "ai-settings.enabled");

        map.put("ai.summary.api-url", "ai-settings.api-url");
        map.put("ai.summary.api-key", "ai-settings.api-key");
        map.put("ai.summary.model", "ai-settings.model");
        map.put("ai.summary.system-prompt", "ai-settings.system-prompt");
        map.put("ai.summary.enabled", "ai-settings.enabled");

        return map;
    }

    public Object get(String path) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().get(resolved);
    }

    public String getString(String path, String def) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().getString(resolved, def);
    }

    public boolean getBoolean(String path, boolean def) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().getBoolean(resolved, def);
    }

    public int getInt(String path, int def) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().getInt(resolved, def);
    }

    public long getLong(String path, long def) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().getLong(resolved, def);
    }

    public double getDouble(String path, double def) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().getDouble(resolved, def);
    }

    public List<String> getStringList(String path) {
        String resolved = resolveReadablePath(path);
        return plugin.getConfig().getStringList(resolved);
    }

    public String canonicalPath(String inputPath) {
        if (inputPath == null) {
            return "";
        }
        return normalizePath(inputPath);
    }

    public boolean setByUserPath(String inputPath, String rawValue) {
        if (inputPath == null || inputPath.isBlank()) {
            return false;
        }
        String path = normalizePath(inputPath);
        FileConfiguration config = plugin.getConfig();
        Object current = get(path);
        Object parsed = parseValue(rawValue, current);
        config.set(path, parsed);
        plugin.saveConfig();
        return true;
    }

    public String valueAsString(String inputPath) {
        Object value = get(inputPath);
        if (value == null) {
            return "<null>";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) {
                parts.add(String.valueOf(o));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(value);
    }

    public List<String> listPaths(String module) {
        String m = normalizePath(module);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(m);
        if (section == null) {
            return List.of();
        }
        return new ArrayList<>(section.getKeys(true));
    }

    public List<String> listTopModules() {
        ConfigurationSection root = plugin.getConfig();
        return new ArrayList<>(root.getKeys(false));
    }

    private String resolveReadablePath(String inputPath) {
        String canonical = normalizePath(inputPath);
        FileConfiguration config = plugin.getConfig();
        if (config.contains(canonical)) {
            return canonical;
        }
        String alias = aliases.get(canonical);
        if (alias != null && config.contains(alias)) {
            return alias;
        }
        return canonical;
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
    }

    private Object parseValue(String rawValue, Object currentValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        if (currentValue instanceof Boolean) {
            if ("true".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) {
                return false;
            }
            return currentValue;
        }
        if (currentValue instanceof Integer) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return currentValue;
            }
        }
        if (currentValue instanceof Long) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return currentValue;
            }
        }
        if (currentValue instanceof Double || currentValue instanceof Float) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return currentValue;
            }
        }
        if (currentValue instanceof List<?>) {
            return parseList(raw);
        }

        if (raw.startsWith("[") && raw.endsWith("]")) {
            return parseList(raw.substring(1, raw.length() - 1));
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
        }
        return raw;
    }

    private List<String> parseList(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\s*,\\s*"))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
    }
}
