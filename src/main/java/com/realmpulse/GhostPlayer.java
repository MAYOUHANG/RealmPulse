package com.realmpulse;

import com.comphenix.protocol.wrappers.WrappedGameProfile;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class GhostPlayer {
    public enum Language {
        ZH,
        EN
    }

    private final String name;
    private final UUID uuid;
    private final String prefix;
    private int ping;
    private final WrappedGameProfile profile;
    private final String displayName;
    private final int level;
    private final Language language;
    private boolean isOnline;

    public GhostPlayer(String name, JavaPlugin plugin, Language language) {
        this.name = name;
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        this.level = randomWeightedLevel();
        this.prefix = resolveDefaultPrefix(plugin);
        this.displayName = this.prefix + this.name;
        this.ping = ThreadLocalRandom.current().nextInt(50, 301);
        this.profile = new WrappedGameProfile(this.uuid, this.name);
        this.language = language == null ? Language.ZH : language;
        this.isOnline = true;
    }

    private int randomWeightedLevel() {
        final int min = 1;
        final int max = 100;
        final int range = max - min + 1;
        final int totalWeight = range * (range + 1) / 2;

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (int level = min; level <= max; level++) {
            int weight = max - level + 1;
            if (roll < weight) {
                return level;
            }
            roll -= weight;
        }
        return max;
    }

    private String resolveDefaultPrefix(JavaPlugin plugin) {
        String configuredFallback = plugin.getConfig().getString("tab.default-prefix-fallback", "&7[玩家]&f");

        String lpPrefix = resolveLuckPermsDefaultPrefix(plugin);
        if (lpPrefix != null && !lpPrefix.isBlank()) {
            return lpPrefix;
        }

        String vaultPrefix = resolveVaultDefaultPrefix(plugin);
        if (vaultPrefix != null && !vaultPrefix.isBlank()) {
            return vaultPrefix;
        }

        return configuredFallback;
    }

    private String resolveVaultDefaultPrefix(JavaPlugin plugin) {
        Chat chat = plugin.getServer().getServicesManager().getRegistration(Chat.class) != null
            ? plugin.getServer().getServicesManager().getRegistration(Chat.class).getProvider()
            : null;
        if (chat == null) {
            return "";
        }

        try {
            String byNullWorld = chat.getGroupPrefix((String) null, "default");
            if (byNullWorld != null && !byNullWorld.isBlank()) {
                return byNullWorld;
            }
        } catch (Throwable ignored) {
        }

        try {
            List<World> worlds = Bukkit.getWorlds();
            for (World world : worlds) {
                String byWorld = chat.getGroupPrefix(world.getName(), "default");
                if (byWorld != null && !byWorld.isBlank()) {
                    return byWorld;
                }
            }
        } catch (Throwable ignored) {
        }

        return "";
    }

    // Reflection to avoid hard compile dependency on LuckPerms API.
    private String resolveLuckPermsDefaultPrefix(JavaPlugin plugin) {
        try {
            Plugin lpPlugin = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
            if (lpPlugin == null || !lpPlugin.isEnabled()) {
                return "";
            }

            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);

            Object groupManager = luckPerms.getClass().getMethod("getGroupManager").invoke(luckPerms);
            Object group = groupManager.getClass().getMethod("getGroup", String.class).invoke(groupManager, "default");
            if (group == null) {
                return "";
            }

            Object cachedData = group.getClass().getMethod("getCachedData").invoke(group);
            Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
            Method nonContextual = queryOptionsClass.getMethod("nonContextual");
            Object queryOptions = nonContextual.invoke(null);

            Object metaData = cachedData.getClass()
                .getMethod("getMetaData", queryOptionsClass)
                .invoke(cachedData, queryOptions);
            Object prefixObj = metaData.getClass().getMethod("getPrefix").invoke(metaData);
            return prefixObj == null ? "" : String.valueOf(prefixObj);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getPrefix() {
        return prefix;
    }

    public int getPing() {
        return ping;
    }

    public int getLevel() {
        return level;
    }

    public void setRandomPing() {
        this.ping = ThreadLocalRandom.current().nextInt(50, 301);
    }

    public WrappedGameProfile getProfile() {
        return profile;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Language getLanguage() {
        return language;
    }

    public boolean isEnglishSpeaker() {
        return language == Language.EN;
    }

    public boolean isOnline() {
        return isOnline;
    }

    public void setOnline(boolean online) {
        isOnline = online;
    }
}
