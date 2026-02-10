package com.realmpulse;

import com.comphenix.protocol.ProtocolLibrary;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class RealmPulse extends JavaPlugin implements TabExecutor {

    private Chat chat;
    private GhostManager ghostManager;
    private PacketManager packetManager;
    private DeepSeekService deepSeekService;
    private SmartChatManager smartChatManager;
    private DeathManager deathManager;
    private ConnectionSimulator connectionSimulator;
    private AdvancementAnnounceManager advancementAnnounceManager;
    private PluginConfigService configService;
    private AdminMessageService adminMessageService;
    private BukkitTask sceneAutoTask;
    private String lastAutoScene = "";
    private static final Pattern UPDATED_WITH_VALUE_PATTERN = Pattern.compile("^(.+?) updated: (.+)$");
    private static final Pattern SET_TO_PATTERN = Pattern.compile("^(.+?) set to: (.+)$");
    private static final Pattern UPDATED_PATTERN = Pattern.compile("^(.+?) updated\\.$");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int syncedEntries = syncConfigWithDefaults();
        if (syncedEntries > 0) {
            getLogger().info("Config auto-updated: added " + syncedEntries + " missing entries from defaults.");
        }
        sendHeader();

        getLogger().info("Initializing environment...");
        getLogger().info("Detected Minecraft version: " + MinecraftVersion.get());

        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found! This plugin requires ProtocolLib to function.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!setupChat()) {
            return;
        }

        configService = new PluginConfigService(this);
        adminMessageService = new AdminMessageService(this, configService);

        if (getCommand("realmpulse") != null) {
            getCommand("realmpulse").setExecutor(this);
            getCommand("realmpulse").setTabCompleter(this);
        }

        ghostManager = new GhostManager(this);
        int ghostCount = configService.getInt("core.ghost-count", 20);
        ghostManager.initializeGhosts(ghostCount);

        packetManager = new PacketManager(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
                packetManager.sendTabListAdd(player, ghost);
            }
        }

        deathManager = new DeathManager(this);
        connectionSimulator = new ConnectionSimulator(this, packetManager);
        getServer().getPluginManager().registerEvents(new ConnectionListener(this, configService, packetManager, deathManager), this);
        getServer().getPluginManager().registerEvents(new TeleportInterceptor(this), this);
        getServer().getPluginManager().registerEvents(new TabInterceptor(), this);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                ProtocolLibrary.getProtocolManager().addPacketListener(new MotdListener(this));
                getLogger().info("MotdListener registered.");
            } catch (Exception e) {
                getLogger().severe("Failed to register MotdListener: " + e.getMessage());
            }
        }, 100L);

        deepSeekService = new DeepSeekService(this, configService);
        smartChatManager = new SmartChatManager(this, configService, deepSeekService, deathManager);
        getServer().getPluginManager().registerEvents(new RealPlayerChatListener(smartChatManager), this);
        smartChatManager.startIdleChat();
        smartChatManager.startEnglishDialogue();
        deathManager.startDeathSimulation();
        connectionSimulator.startSimulation();
        advancementAnnounceManager = new AdvancementAnnounceManager(this, configService, deathManager);
        advancementAnnounceManager.start();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
                packetManager.updateTabForAll(ghost);
            }
        }, 20L * 30, 20L * 30);

        getLogger().info("System status: [READY]");
        getLogger().info("Running version: " + getDescription().getVersion());
        getLogger().info("Learning system: [ACTIVE]");
        getLogger().info("Signature: MAAAABG");
        refreshSceneAutoTask();
    }

    private void sendHeader() {
        Bukkit.getConsoleSender().sendMessage(" ");
        Bukkit.getConsoleSender().sendMessage("[RealmPulse] Atmosphere Reborn");
        Bukkit.getConsoleSender().sendMessage("Version: " + getDescription().getVersion() + " | Developer: MAAAABG");
        Bukkit.getConsoleSender().sendMessage("-------------------------------------------");
        Bukkit.getConsoleSender().sendMessage(" ");
    }

    @Override
    public void onDisable() {
        if (packetManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
                    packetManager.sendTabListRemove(player, ghost);
                }
            }
            getLogger().info("Tab cleanup: removed ghost entries for online players.");
        }
        if (ghostManager != null) {
            ghostManager.clearGhosts();
        }
        if (sceneAutoTask != null) {
            sceneAutoTask.cancel();
            sceneAutoTask = null;
        }
        if (advancementAnnounceManager != null) {
            advancementAnnounceManager.stop();
        }
        getLogger().info("System status: [STOPPING]");
        getLogger().info("System status: [OFFLINE]");
        getLogger().info("System shutting down... Goodbye!");
    }

    public boolean setupChat() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault not found. Disabling RealmPulse.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        RegisteredServiceProvider<Chat> provider = getServer().getServicesManager().getRegistration(Chat.class);
        if (provider == null) {
            getLogger().severe("Vault Chat provider not found. Disabling RealmPulse.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        chat = provider.getProvider();
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("realmpulse")) {
            return false;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            adminMessageService.sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("reload".equals(sub)) {
            return handleReload(sender);
        }
        if ("learn".equals(sub)) {
            return handleLearn(sender, args);
        }
        if ("bots".equals(sub)) {
            return handleBots(sender);
        }
        if ("addbot".equals(sub)) {
            return handleAddBot(sender, args);
        }
        if ("removebot".equals(sub) || "delbot".equals(sub)) {
            return handleRemoveBot(sender, args);
        }
        if ("setbot".equals(sub)) {
            return handleSetBot(sender, args);
        }
        if ("qamodel".equals(sub)) {
            return handleModelSet(sender, args, "ai.qa.model", "\u95EE\u7B54\u6A21\u578B", "QA model");
        }
        if ("summarymodel".equals(sub)) {
            return handleModelSet(sender, args, "ai.summary.model", "\u603B\u7ED3\u6A21\u578B", "Summary model");
        }
        if ("qaon".equals(sub)) {
            return handleToggleSet(sender, args, "ai.qa.enabled", "\u95EE\u7B54 AI", "QA AI");
        }
        if ("summaryon".equals(sub)) {
            return handleToggleSet(sender, args, "ai.summary.enabled", "\u603B\u7ED3 AI", "Summary AI");
        }
        if ("qaapi".equals(sub)) {
            return handleTextSet(sender, args, "ai.qa.api-url", "\u95EE\u7B54 API \u5730\u5740", "QA API URL");
        }
        if ("summaryapi".equals(sub)) {
            return handleTextSet(sender, args, "ai.summary.api-url", "\u603B\u7ED3 API \u5730\u5740", "Summary API URL");
        }
        if ("qakey".equals(sub)) {
            return handleTextSet(sender, args, "ai.qa.api-key", "\u95EE\u7B54 API \u5BC6\u94A5", "QA API key");
        }
        if ("summarykey".equals(sub)) {
            return handleTextSet(sender, args, "ai.summary.api-key", "\u603B\u7ED3 API \u5BC6\u94A5", "Summary API key");
        }
        if ("profile".equals(sub)) {
            return handleProfile(sender, args);
        }
        if ("scene".equals(sub)) {
            return handleScene(sender, args);
        }
        if ("advancement".equals(sub) || "adv".equals(sub)) {
            return handleAdvancement(sender, args);
        }

        // Advanced mode remains available.
        if ("get".equals(sub) || "set".equals(sub) || "list".equals(sub)) {
            return handleAdvancedConfig(sender, args);
        }
        if ("config".equals(sub) && args.length >= 2) {
            String[] converted = Arrays.copyOfRange(args, 1, args.length);
            return handleAdvancedConfig(sender, converted);
        }

        adminMessageService.sendHelp(sender);
        return true;
    }

    private String zhOf(String en) {
        if (en == null || en.isBlank()) {
            return "";
        }
        String text = en.trim();
        switch (text) {
            case "You do not have permission.":
                return "\u4F60\u6CA1\u6709\u6743\u9650\u3002";
            case "Configuration reloaded.":
                return "\u914D\u7F6E\u5DF2\u91CD\u8F7D\u3002";
            case "Learning system is not initialized.":
                return "\u5B66\u4E60\u7CFB\u7EDF\u672A\u521D\u59CB\u5316\u3002";
            case "Learning status":
                return "\u5B66\u4E60\u72B6\u6001";
            case "Learning flush task started.":
                return "\u5B66\u4E60\u5237\u65B0\u4EFB\u52A1\u5DF2\u542F\u52A8\u3002";
            case "Learning flush skipped (no data or busy).":
                return "\u5B66\u4E60\u5237\u65B0\u5DF2\u8DF3\u8FC7\uFF08\u65E0\u6570\u636E\u6216\u7CFB\u7EDF\u7E41\u5FD9\uFF09\u3002";
            case "Count must be a positive integer.":
                return "\u6570\u91CF\u5FC5\u987B\u662F\u6B63\u6574\u6570\u3002";
            case "Count must be a non-negative integer.":
                return "\u6570\u91CF\u5FC5\u987B\u662F\u975E\u8D1F\u6574\u6570\u3002";
            case "Model cannot be empty.":
                return "\u6A21\u578B\u4E0D\u80FD\u4E3A\u7A7A\u3002";
            case "Only on/off is supported.":
                return "\u4EC5\u652F\u6301 on/off\u3002";
            case "Value cannot be empty.":
                return "\u503C\u4E0D\u80FD\u4E3A\u7A7A\u3002";
            case "Failed to set config value.":
                return "\u8BBE\u7F6E\u914D\u7F6E\u503C\u5931\u8D25\u3002";
            case "Config updated.":
                return "\u914D\u7F6E\u5DF2\u66F4\u65B0\u3002";
            case "Unknown profile. Use: lowcost, balanced, pro":
                return "\u672A\u77E5\u6863\u4F4D\u3002\u8BF7\u4F7F\u7528: lowcost, balanced, pro";
            case "Unknown scene. Use: peak, quiet, promo":
                return "\u672A\u77E5\u573A\u666F\u3002\u8BF7\u4F7F\u7528: peak, quiet, promo";
            case "Auto scene enabled.":
                return "\u81EA\u52A8\u573A\u666F\u5DF2\u542F\u7528\u3002";
            case "Auto scene disabled.":
                return "\u81EA\u52A8\u573A\u666F\u5DF2\u5173\u95ED\u3002";
            case "Advancement simulator is not initialized.":
                return "\u6210\u5C31\u6A21\u62DF\u5668\u672A\u521D\u59CB\u5316\u3002";
            case "Low cost: fewer bots + lower-cost models":
                return "\u4F4E\u6210\u672C\uFF1A\u66F4\u5C11\u673A\u5668\u4EBA + \u66F4\u4F4E\u6210\u672C\u6A21\u578B";
            case "Balanced: efficient QA + quality summary":
                return "\u5747\u8861\uFF1A\u9AD8\u6548\u95EE\u7B54 + \u9AD8\u8D28\u91CF\u603B\u7ED3";
            case "Pro: more bots + stronger summarization":
                return "\u4E13\u4E1A\uFF1A\u66F4\u591A\u673A\u5668\u4EBA + \u66F4\u5F3A\u603B\u7ED3";
        }

        if (text.startsWith("Usage: ")) {
            return "\u7528\u6CD5: " + text.substring("Usage: ".length());
        }
        if (text.startsWith("Raw records: ")) {
            return "\u539F\u59CB\u8BB0\u5F55\u6570: " + text.substring("Raw records: ".length());
        }
        if (text.startsWith("Pending queue: ")) {
            return "\u5F85\u5904\u7406\u961F\u5217: " + text.substring("Pending queue: ".length());
        }
        if (text.startsWith("Refined phrases: ")) {
            return "\u63D0\u70BC\u77ED\u53E5: " + text.substring("Refined phrases: ".length());
        }
        if (text.startsWith("Current bot count: ")) {
            return "\u5F53\u524D\u673A\u5668\u4EBA\u6570\u91CF: " + text.substring("Current bot count: ".length());
        }
        if (text.startsWith("Bots increased. Current count: ")) {
            return "\u673A\u5668\u4EBA\u5DF2\u589E\u52A0\u3002\u5F53\u524D\u6570\u91CF: "
                + text.substring("Bots increased. Current count: ".length());
        }
        if (text.startsWith("Bots reduced. Current count: ")) {
            return "\u673A\u5668\u4EBA\u5DF2\u51CF\u5C11\u3002\u5F53\u524D\u6570\u91CF: "
                + text.substring("Bots reduced. Current count: ".length());
        }
        if (text.startsWith("Bot count set to: ")) {
            return "\u673A\u5668\u4EBA\u6570\u91CF\u5DF2\u8BBE\u7F6E\u4E3A: " + text.substring("Bot count set to: ".length());
        }
        if (text.startsWith("Config value: ")) {
            return "\u914D\u7F6E\u503C: " + text.substring("Config value: ".length());
        }
        if (text.startsWith("Modules: ")) {
            return "\u6A21\u5757: " + text.substring("Modules: ".length());
        }
        if (text.startsWith("Module is empty or not found: ")) {
            return "\u6A21\u5757\u4E3A\u7A7A\u6216\u672A\u627E\u5230: " + text.substring("Module is empty or not found: ".length());
        }
        if (text.startsWith("Keys [")) {
            return "\u952E\u5217\u8868[" + text.substring("Keys [".length());
        }
        if (text.startsWith("Applied profile: ")) {
            return "\u5DF2\u5E94\u7528\u6863\u4F4D: " + text.substring("Applied profile: ".length());
        }
        if (text.startsWith("Scene applied: ")) {
            return "\u573A\u666F\u5DF2\u5E94\u7528: " + text.substring("Scene applied: ".length());
        }
        if (text.startsWith("Auto scene: ")) {
            String raw = text.substring("Auto scene: ".length());
            return "\u81EA\u52A8\u573A\u666F: " + zhOnOff(raw);
        }
        if (text.startsWith("Timezone: ")) {
            return "\u65F6\u533A: " + text.substring("Timezone: ".length());
        }
        if (text.startsWith("Current auto scene: ")) {
            return "\u5F53\u524D\u81EA\u52A8\u573A\u666F: " + text.substring("Current auto scene: ".length());
        }
        if (text.startsWith("Advancement simulator: ")) {
            String raw = text.substring("Advancement simulator: ".length());
            return "\u6210\u5C31\u6A21\u62DF\u5668: " + zhOnOff(raw);
        }
        if (text.startsWith("Available advancements: ")) {
            return "\u53EF\u7528\u6210\u5C31: " + text.substring("Available advancements: ".length());
        }
        if (text.startsWith("Online alive ghosts: ")) {
            return "\u5728\u7EBF\u5B58\u6D3B\u5E7D\u7075: " + text.substring("Online alive ghosts: ".length());
        }
        if (text.startsWith("Tracked ghosts: ")) {
            return "\u5DF2\u8DDF\u8E2A\u5E7D\u7075: " + text.substring("Tracked ghosts: ".length());
        }
        if (text.startsWith("Total completions: ")) {
            return "\u603B\u5B8C\u6210\u6B21\u6570: " + text.substring("Total completions: ".length());
        }
        if (text.startsWith("Triggers in last hour: ")) {
            return "\u6700\u8FD1\u4E00\u5C0F\u65F6\u89E6\u53D1\u6B21\u6570: " + text.substring("Triggers in last hour: ".length());
        }
        if (text.startsWith("No broadcast sent, reason: ")) {
            return "\u672A\u53D1\u9001\u5E7F\u64AD\uFF0C\u539F\u56E0: " + text.substring("No broadcast sent, reason: ".length());
        }
        if (text.startsWith("Triggered: ")) {
            return "\u5DF2\u89E6\u53D1: " + text.substring("Triggered: ".length());
        }

        Matcher updatedWithValue = UPDATED_WITH_VALUE_PATTERN.matcher(text);
        if (updatedWithValue.matches()) {
            String label = zhLabel(updatedWithValue.group(1));
            return label + "\u5DF2\u66F4\u65B0: " + updatedWithValue.group(2);
        }
        Matcher setTo = SET_TO_PATTERN.matcher(text);
        if (setTo.matches()) {
            String label = zhLabel(setTo.group(1));
            return label + "\u5DF2\u8BBE\u7F6E\u4E3A: " + zhOnOff(setTo.group(2));
        }
        Matcher updated = UPDATED_PATTERN.matcher(text);
        if (updated.matches()) {
            String label = zhLabel(updated.group(1));
            return label + "\u5DF2\u66F4\u65B0\u3002";
        }
        return text;
    }

    private String zhLabel(String label) {
        return switch (label) {
            case "QA model" -> "\u95EE\u7B54\u6A21\u578B";
            case "Summary model" -> "\u603B\u7ED3\u6A21\u578B";
            case "QA AI" -> "\u95EE\u7B54 AI";
            case "Summary AI" -> "\u603B\u7ED3 AI";
            case "QA API URL" -> "\u95EE\u7B54 API \u5730\u5740";
            case "Summary API URL" -> "\u603B\u7ED3 API \u5730\u5740";
            case "QA API key" -> "\u95EE\u7B54 API \u5BC6\u94A5";
            case "Summary API key" -> "\u603B\u7ED3 API \u5BC6\u94A5";
            case "Config" -> "\u914D\u7F6E";
            case "Auto scene" -> "\u81EA\u52A8\u573A\u666F";
            case "Advancement simulator" -> "\u6210\u5C31\u6A21\u62DF\u5668";
            default -> label;
        };
    }

    private String zhOnOff(String raw) {
        if ("ON".equalsIgnoreCase(raw)) {
            return "\u5F00\u542F";
        }
        if ("OFF".equalsIgnoreCase(raw)) {
            return "\u5173\u95ED";
        }
        return raw;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("realmpulse.reload")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        getLogger().info("System status: [RELOADING]");
        reloadConfig();
        int syncedEntries = syncConfigWithDefaults();
        if (syncedEntries > 0) {
            String msg = "Config auto-updated: added " + syncedEntries + " missing entries.";
            adminMessageService.info(sender, zhOf(msg), msg);
        }
        if (advancementAnnounceManager != null) {
            advancementAnnounceManager.start();
        }
        getLogger().info("System status: [READY]");
        adminMessageService.success(sender, zhOf("Configuration reloaded."), "Configuration reloaded.");
        return true;
    }

    private int syncConfigWithDefaults() {
        FileConfiguration liveConfig = getConfig();
        YamlConfiguration defaultConfig = loadDefaultConfig();
        if (defaultConfig == null) {
            return 0;
        }

        int added = 0;
        for (String path : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(path)) {
                continue;
            }
            if (!liveConfig.contains(path, true)) {
                liveConfig.set(path, defaultConfig.get(path));
                added++;
            }
        }

        if (added > 0) {
            saveConfig();
        }
        return added;
    }

    private YamlConfiguration loadDefaultConfig() {
        InputStream stream = getResource("config.yml");
        if (stream == null) {
            getLogger().warning("Unable to load embedded config.yml for auto-update.");
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            getLogger().warning("Failed to read embedded config.yml: " + ex.getMessage());
            return null;
        }
    }

    private boolean handleLearn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp learn status|flush"), "Usage: /rp learn status|flush");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if ("status".equals(action)) {
            if (!sender.hasPermission("realmpulse.learn.status")) {
                adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
                return true;
            }
            if (smartChatManager == null) {
                adminMessageService.error(sender, zhOf("Learning system is not initialized."), "Learning system is not initialized.");
                return true;
            }
            SmartChatManager.LearningStatus status = smartChatManager.getLearningStatus();
            adminMessageService.info(sender, zhOf("Learning status"), "Learning status");
            adminMessageService.info(sender, zhOf("Raw records: " + status.rawCount), "Raw records: " + status.rawCount);
            adminMessageService.info(sender, zhOf("Pending queue: " + status.pendingCount), "Pending queue: " + status.pendingCount);
            adminMessageService.info(sender, zhOf("Refined phrases: " + status.refinedCount), "Refined phrases: " + status.refinedCount);
            return true;
        }
        if ("flush".equals(action)) {
            if (!sender.hasPermission("realmpulse.learn.flush")) {
                adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
                return true;
            }
            if (smartChatManager == null) {
                adminMessageService.error(sender, zhOf("Learning system is not initialized."), "Learning system is not initialized.");
                return true;
            }
            boolean started = smartChatManager.flushLearningNow();
            if (started) {
                adminMessageService.success(sender, zhOf("Learning flush task started."), "Learning flush task started.");
            } else {
                adminMessageService.warn(sender, zhOf("Learning flush skipped (no data or busy)."), "Learning flush skipped (no data or busy).");
            }
            return true;
        }
        adminMessageService.warn(sender, zhOf("Usage: /rp learn status|flush"), "Usage: /rp learn status|flush");
        return true;
    }

    private boolean handleBots(CommandSender sender) {
        if (!sender.hasPermission("realmpulse.bot.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        int count = ghostManager == null ? 0 : ghostManager.totalCount();
        adminMessageService.info(sender, zhOf("Current bot count: " + count), "Current bot count: " + count);
        return true;
    }

    private boolean handleAddBot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realmpulse.bot.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp addbot <count>"), "Usage: /rp addbot <count>");
            return true;
        }
        int delta = parsePositiveInt(args[1], -1);
        if (delta <= 0) {
            adminMessageService.warn(sender, zhOf("Count must be a positive integer."), "Count must be a positive integer.");
            return true;
        }
        int target = Math.min(500, Math.max(0, ghostManager.totalCount() + delta));
        applyGhostCount(target);
        adminMessageService.success(sender, zhOf("Bots increased. Current count: " + target), "Bots increased. Current count: " + target);
        return true;
    }

    private boolean handleRemoveBot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realmpulse.bot.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp removebot <count>"), "Usage: /rp removebot <count>");
            return true;
        }
        int delta = parsePositiveInt(args[1], -1);
        if (delta <= 0) {
            adminMessageService.warn(sender, zhOf("Count must be a positive integer."), "Count must be a positive integer.");
            return true;
        }
        int target = Math.max(0, ghostManager.totalCount() - delta);
        applyGhostCount(target);
        adminMessageService.success(sender, zhOf("Bots reduced. Current count: " + target), "Bots reduced. Current count: " + target);
        return true;
    }

    private boolean handleSetBot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realmpulse.bot.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp setbot <count>"), "Usage: /rp setbot <count>");
            return true;
        }
        int target = parsePositiveInt(args[1], -1);
        if (target < 0) {
            adminMessageService.warn(sender, zhOf("Count must be a non-negative integer."), "Count must be a non-negative integer.");
            return true;
        }
        target = Math.min(500, target);
        applyGhostCount(target);
        adminMessageService.success(sender, zhOf("Bot count set to: " + target), "Bot count set to: " + target);
        return true;
    }

    private boolean handleModelSet(CommandSender sender, String[] args, String path, String zhName, String enName) {
        if (!sender.hasPermission("realmpulse.ai.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp " + args[0] + " <model>"), "Usage: /rp " + args[0] + " <model>");
            return true;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (value.isEmpty()) {
            adminMessageService.warn(sender, zhOf("Model cannot be empty."), "Model cannot be empty.");
            return true;
        }
        configService.setByUserPath(path, value);
        saveConfig();
        adminMessageService.success(sender, zhOf(enName + " updated: " + value), enName + " updated: " + value);
        return true;
    }

    private boolean handleToggleSet(CommandSender sender, String[] args, String path, String zhName, String enName) {
        if (!sender.hasPermission("realmpulse.ai.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp " + args[0] + " <on|off>"), "Usage: /rp " + args[0] + " <on|off>");
            return true;
        }
        String flag = args[1].toLowerCase(Locale.ROOT);
        boolean enabled;
        if ("on".equals(flag) || "true".equals(flag)) {
            enabled = true;
        } else if ("off".equals(flag) || "false".equals(flag)) {
            enabled = false;
        } else {
            adminMessageService.warn(sender, zhOf("Only on/off is supported."), "Only on/off is supported.");
            return true;
        }
        configService.setByUserPath(path, String.valueOf(enabled));
        saveConfig();
        adminMessageService.success(sender, zhOf(enName + " set to: " + (enabled ? "ON" : "OFF")), enName + " set to: " + (enabled ? "ON" : "OFF"));
        return true;
    }

    private boolean handleTextSet(CommandSender sender, String[] args, String path, String zhName, String enName) {
        if (!sender.hasPermission("realmpulse.ai.manage")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp " + args[0] + " <value>"), "Usage: /rp " + args[0] + " <value>");
            return true;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (value.isEmpty()) {
            adminMessageService.warn(sender, zhOf("Value cannot be empty."), "Value cannot be empty.");
            return true;
        }
        configService.setByUserPath(path, value);
        saveConfig();
        adminMessageService.success(sender, zhOf(enName + " updated."), enName + " updated.");
        return true;
    }

    private boolean handleAdvancedConfig(CommandSender sender, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        if ("get".equals(action) || "list".equals(action)) {
            if (!sender.hasPermission("realmpulse.config")) {
                adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
                return true;
            }
        } else if ("set".equals(action)) {
            if (!sender.hasPermission("realmpulse.config.set")) {
                adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
                return true;
            }
        }

        if ("get".equals(action)) {
            if (args.length < 2) {
                adminMessageService.warn(sender, zhOf("Usage: /rp get <path>"), "Usage: /rp get <path>");
                return true;
            }
            String path = configService.canonicalPath(args[1]);
            String value = configService.valueAsString(path);
            adminMessageService.info(sender, zhOf("Config value: " + path + " = " + value), "Config value: " + path + " = " + value);
            return true;
        }

        if ("set".equals(action)) {
            if (args.length < 3) {
                adminMessageService.warn(sender, zhOf("Usage: /rp set <path> <value>"), "Usage: /rp set <path> <value>");
                return true;
            }
            String path = args[1];
            String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (!configService.setByUserPath(path, value)) {
                adminMessageService.error(sender, zhOf("Failed to set config value."), "Failed to set config value.");
                return true;
            }
            saveConfig();
            adminMessageService.success(sender, zhOf("Config updated."), "Config updated.");
            return true;
        }

        if ("list".equals(action)) {
            if (args.length == 1) {
                List<String> modules = configService.listTopModules();
                adminMessageService.info(sender, zhOf("Modules: " + String.join(", ", modules)), "Modules: " + String.join(", ", modules));
                return true;
            }
            String module = args[1];
            List<String> keys = configService.listPaths(module);
            if (keys.isEmpty()) {
                adminMessageService.warn(sender, zhOf("Module is empty or not found: " + module), "Module is empty or not found: " + module);
                return true;
            }
            Collections.sort(keys);
            adminMessageService.info(sender, zhOf("Keys [" + module + "]: " + String.join(", ", keys)), "Keys [" + module + "]: " + String.join(", ", keys));
            return true;
        }

        adminMessageService.sendHelp(sender);
        return true;
    }

    private boolean handleProfile(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realmpulse.profile.apply")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp profile <lowcost|balanced|pro>"), "Usage: /rp profile <lowcost|balanced|pro>");
            return true;
        }

        String profile = args[1].toLowerCase(Locale.ROOT);
        switch (profile) {
            case "lowcost" -> {
                applyGhostCount(10);
                configService.setByUserPath("core.chat-interval", "180");
                configService.setByUserPath("ai.qa.enabled", "true");
                configService.setByUserPath("ai.qa.model", "deepseek-ai/DeepSeek-V3");
                configService.setByUserPath("ai.qa.max-tokens", "48");
                configService.setByUserPath("ai.summary.enabled", "true");
                configService.setByUserPath("ai.summary.model", "deepseek-ai/DeepSeek-V3");
                configService.setByUserPath("ai.summary.max-tokens", "120");
                configService.setByUserPath("events.english-dialogue-ai-chance", "0.50");
                saveConfig();
                adminMessageService.success(sender, zhOf("Applied profile: lowcost"), "Applied profile: lowcost");
                adminMessageService.info(sender, zhOf("Low cost: fewer bots + lower-cost models"), "Low cost: fewer bots + lower-cost models");
                return true;
            }
            case "balanced" -> {
                applyGhostCount(20);
                configService.setByUserPath("core.chat-interval", "120");
                configService.setByUserPath("ai.qa.enabled", "true");
                configService.setByUserPath("ai.qa.model", "deepseek-ai/DeepSeek-V3");
                configService.setByUserPath("ai.qa.max-tokens", "64");
                configService.setByUserPath("ai.summary.enabled", "true");
                configService.setByUserPath("ai.summary.model", "deepseek-ai/DeepSeek-R1");
                configService.setByUserPath("ai.summary.max-tokens", "220");
                configService.setByUserPath("events.english-dialogue-ai-chance", "0.75");
                saveConfig();
                adminMessageService.success(sender, zhOf("Applied profile: balanced"), "Applied profile: balanced");
                adminMessageService.info(sender, zhOf("Balanced: efficient QA + quality summary"), "Balanced: efficient QA + quality summary");
                return true;
            }
            case "pro" -> {
                applyGhostCount(35);
                configService.setByUserPath("core.chat-interval", "90");
                configService.setByUserPath("ai.qa.enabled", "true");
                configService.setByUserPath("ai.qa.model", "deepseek-ai/DeepSeek-V3");
                configService.setByUserPath("ai.qa.max-tokens", "80");
                configService.setByUserPath("ai.summary.enabled", "true");
                configService.setByUserPath("ai.summary.model", "deepseek-ai/DeepSeek-R1");
                configService.setByUserPath("ai.summary.max-tokens", "320");
                configService.setByUserPath("events.english-dialogue-ai-chance", "0.90");
                saveConfig();
                adminMessageService.success(sender, zhOf("Applied profile: pro"), "Applied profile: pro");
                adminMessageService.info(sender, zhOf("Pro: more bots + stronger summarization"), "Pro: more bots + stronger summarization");
                return true;
            }
            default -> {
                adminMessageService.warn(sender, zhOf("Unknown profile. Use: lowcost, balanced, pro"), "Unknown profile. Use: lowcost, balanced, pro");
                return true;
            }
        }
    }

    private boolean handleScene(CommandSender sender, String[] args) {
        if (args.length < 2) {
            adminMessageService.warn(sender, zhOf("Usage: /rp scene <peak|quiet|promo|auto>"), "Usage: /rp scene <peak|quiet|promo|auto>");
            return true;
        }

        if ("auto".equalsIgnoreCase(args[1])) {
            return handleSceneAuto(sender, args);
        }

        if (!sender.hasPermission("realmpulse.scene.apply")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }

        String scene = args[1].toLowerCase(Locale.ROOT);
        if (!applyScenePreset(scene)) {
            adminMessageService.warn(sender, zhOf("Unknown scene. Use: peak, quiet, promo"), "Unknown scene. Use: peak, quiet, promo");
            return true;
        }
        adminMessageService.success(sender, zhOf("Scene applied: " + scene), "Scene applied: " + scene);
        return true;
    }

    private boolean handleSceneAuto(CommandSender sender, String[] args) {
        if (!sender.hasPermission("realmpulse.scene.auto")) {
            adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
            return true;
        }
        if (args.length < 3 || "status".equalsIgnoreCase(args[2])) {
            boolean enabled = configService.getBoolean("scene-auto.enabled", false);
            String tz = configService.getString("scene-auto.timezone", ZoneId.systemDefault().getId());
            adminMessageService.info(sender, zhOf("Auto scene: " + (enabled ? "ON" : "OFF")), "Auto scene: " + (enabled ? "ON" : "OFF"));
            adminMessageService.info(sender, zhOf("Timezone: " + tz), "Timezone: " + tz);
            if (enabled) {
                adminMessageService.info(sender, zhOf("Current auto scene: " + (lastAutoScene.isBlank() ? "N/A" : lastAutoScene)), "Current auto scene: " + (lastAutoScene.isBlank() ? "N/A" : lastAutoScene));
            }
            return true;
        }

        String action = args[2].toLowerCase(Locale.ROOT);
        if ("on".equals(action)) {
            configService.setByUserPath("scene-auto.enabled", "true");
            saveConfig();
            refreshSceneAutoTask();
            adminMessageService.success(sender, zhOf("Auto scene enabled."), "Auto scene enabled.");
            return true;
        }
        if ("off".equals(action)) {
            configService.setByUserPath("scene-auto.enabled", "false");
            saveConfig();
            refreshSceneAutoTask();
            adminMessageService.success(sender, zhOf("Auto scene disabled."), "Auto scene disabled.");
            return true;
        }
        adminMessageService.warn(sender, zhOf("Usage: /rp scene auto <on|off|status>"), "Usage: /rp scene auto <on|off|status>");
        return true;
    }

    private boolean handleAdvancement(CommandSender sender, String[] args) {
        if (advancementAnnounceManager == null) {
            adminMessageService.error(sender, zhOf("Advancement simulator is not initialized."), "Advancement simulator is not initialized.");
            return true;
        }
        if (args.length < 2 || "status".equalsIgnoreCase(args[1])) {
            if (!sender.hasPermission("realmpulse.advancement.status")) {
                adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
                return true;
            }
            AdvancementAnnounceManager.AdvancementStatus status = advancementAnnounceManager.getStatus();
            adminMessageService.info(sender, zhOf("Advancement simulator: " + (status.enabled ? "ON" : "OFF")), "Advancement simulator: " + (status.enabled ? "ON" : "OFF"));
            adminMessageService.info(sender, zhOf("Available advancements: " + status.availableAdvancements), "Available advancements: " + status.availableAdvancements);
            adminMessageService.info(sender, zhOf("Online alive ghosts: " + status.onlineAliveGhosts), "Online alive ghosts: " + status.onlineAliveGhosts);
            adminMessageService.info(sender, zhOf("Tracked ghosts: " + status.trackedGhosts), "Tracked ghosts: " + status.trackedGhosts);
            adminMessageService.info(sender, zhOf("Total completions: " + status.totalCompletions), "Total completions: " + status.totalCompletions);
            adminMessageService.info(sender, zhOf("Triggers in last hour: " + status.globalTriggersLastHour), "Triggers in last hour: " + status.globalTriggersLastHour);
            return true;
        }

        if ("trigger".equalsIgnoreCase(args[1])) {
            if (!sender.hasPermission("realmpulse.advancement.trigger")) {
                adminMessageService.error(sender, zhOf("You do not have permission."), "You do not have permission.");
                return true;
            }
            AdvancementAnnounceManager.TriggerResult result = advancementAnnounceManager.triggerNow();
            if (!result.success) {
                adminMessageService.warn(sender, zhOf("No broadcast sent, reason: " + result.reason), "No broadcast sent, reason: " + result.reason);
                return true;
            }
            adminMessageService.success(
                sender, zhOf("Triggered: " + result.ghostName + " -> " + result.advancementName + " (" + result.advancementKey + ")"),
                "Triggered: " + result.ghostName + " -> " + result.advancementName + " (" + result.advancementKey + ")"
            );
            return true;
        }

        adminMessageService.warn(sender, zhOf("Usage: /rp advancement <status|trigger>"), "Usage: /rp advancement <status|trigger>");
        return true;
    }

    private boolean applyScenePreset(String scene) {
        switch (scene.toLowerCase(Locale.ROOT)) {
            case "peak" -> {
                applyGhostCount(40);
                configService.setByUserPath("core.chat-interval", "70");
                configService.setByUserPath("events.reply-chance", "0.25");
                configService.setByUserPath("events.welcome-chance", "0.80");
                configService.setByUserPath("events.player-mention-reply-chance", "0.05");
                configService.setByUserPath("events.player-mention-reply-boost-chance", "0.12");
                configService.setByUserPath("events.english-dialogue-enabled", "true");
                configService.setByUserPath("events.english-dialogue-chance", "0.35");
                configService.setByUserPath("events.english-dialogue-ai-chance", "0.90");
                saveConfig();
                return true;
            }
            case "quiet" -> {
                applyGhostCount(8);
                configService.setByUserPath("core.chat-interval", "220");
                configService.setByUserPath("events.reply-chance", "0.08");
                configService.setByUserPath("events.welcome-chance", "0.35");
                configService.setByUserPath("events.player-mention-reply-chance", "0.01");
                configService.setByUserPath("events.player-mention-reply-boost-chance", "0.03");
                configService.setByUserPath("events.english-dialogue-enabled", "false");
                configService.setByUserPath("events.english-dialogue-chance", "0.08");
                configService.setByUserPath("events.english-dialogue-ai-chance", "0.40");
                saveConfig();
                return true;
            }
            case "promo" -> {
                applyGhostCount(28);
                configService.setByUserPath("core.chat-interval", "95");
                configService.setByUserPath("events.reply-chance", "0.22");
                configService.setByUserPath("events.welcome-chance", "0.95");
                configService.setByUserPath("events.player-mention-reply-chance", "0.08");
                configService.setByUserPath("events.player-mention-reply-boost-chance", "0.16");
                configService.setByUserPath("events.english-dialogue-enabled", "true");
                configService.setByUserPath("events.english-dialogue-chance", "0.30");
                configService.setByUserPath("events.english-dialogue-ai-chance", "0.85");
                saveConfig();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void refreshSceneAutoTask() {
        if (sceneAutoTask != null) {
            sceneAutoTask.cancel();
            sceneAutoTask = null;
        }
        if (!configService.getBoolean("scene-auto.enabled", false)) {
            return;
        }
        long intervalSeconds = Math.max(15L, configService.getLong("scene-auto.check-interval-seconds", 60L));
        sceneAutoTask = Bukkit.getScheduler().runTaskTimer(this, this::runSceneAutoTick, 40L, intervalSeconds * 20L);
    }

    private void runSceneAutoTick() {
        String scene = resolveScheduledScene();
        if (scene.isBlank() || scene.equalsIgnoreCase(lastAutoScene)) {
            return;
        }
        if (applyScenePreset(scene)) {
            lastAutoScene = scene;
            getLogger().info("Scene auto switched to: " + scene);
        }
    }

    private String resolveScheduledScene() {
        ZoneId zoneId;
        String timezone = configService.getString("scene-auto.timezone", ZoneId.systemDefault().getId());
        try {
            zoneId = ZoneId.of(timezone);
        } catch (Exception ignored) {
            zoneId = ZoneId.systemDefault();
        }

        ZonedDateTime nowDateTime = ZonedDateTime.now(zoneId);
        LocalTime now = nowDateTime.toLocalTime();
        List<String> scopes = resolveSceneScopes(nowDateTime.getDayOfWeek());

        for (String scope : scopes) {
            List<String> priority = readPriorityForScope(scope);
            if (priority.isEmpty()) {
                priority = Arrays.asList("promo", "peak", "quiet");
            }
            for (String scene : priority) {
                List<String> ranges = readRangesForScope(scope, scene);
                if (matchesAnyRange(now, ranges)) {
                    return scene.toLowerCase(Locale.ROOT);
                }
            }
        }
        return "";
    }

    private List<String> resolveSceneScopes(DayOfWeek dayOfWeek) {
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return Arrays.asList("weekend", "default");
        }
        return Arrays.asList("weekday", "default");
    }

    private List<String> readPriorityForScope(String scope) {
        if ("default".equals(scope)) {
            return configService.getStringList("scene-auto.priority");
        }
        return configService.getStringList("scene-auto." + scope + ".priority");
    }

    private List<String> readRangesForScope(String scope, String scene) {
        String normalizedScene = scene.toLowerCase(Locale.ROOT);
        if ("default".equals(scope)) {
            return configService.getStringList("scene-auto.slots." + normalizedScene);
        }
        return configService.getStringList("scene-auto." + scope + ".slots." + normalizedScene);
    }

    private boolean matchesAnyRange(LocalTime now, List<String> ranges) {
        for (String range : ranges) {
            if (isInRange(now, range)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInRange(LocalTime now, String range) {
        if (range == null || !range.contains("-")) {
            return false;
        }
        String[] parts = range.split("-", 2);
        LocalTime from = parseLocalTime(parts[0]);
        LocalTime to = parseLocalTime(parts[1]);
        if (from == null || to == null) {
            return false;
        }
        if (!from.isAfter(to)) {
            return !now.isBefore(from) && !now.isAfter(to);
        }
        return !now.isBefore(from) || !now.isAfter(to);
    }

    private LocalTime parseLocalTime(String raw) {
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private int parsePositiveInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void applyGhostCount(int target) {
        List<GhostPlayer> current = new ArrayList<>(GhostManager.getOnlineGhosts());
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (GhostPlayer ghost : current) {
                packetManager.sendTabListRemove(player, ghost);
            }
        }

        ghostManager.initializeGhosts(target);
        configService.setByUserPath("core.ghost-count", String.valueOf(target));
        saveConfig();

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
                packetManager.sendTabListAdd(player, ghost);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList(
                    "help", "reload", "learn", "bots", "addbot", "removebot", "delbot", "setbot",
                    "qamodel", "summarymodel", "qaon", "summaryon", "qaapi", "summaryapi",
                    "qakey", "summarykey", "profile", "scene", "advancement",
                    "get", "set", "list", "config"
                ).stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && "learn".equals(sub)) {
            return Arrays.asList("status", "flush").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("addbot".equals(sub) || "removebot".equals(sub) || "delbot".equals(sub) || "setbot".equals(sub))) {
            return numberSuggestions().stream()
                .filter(s -> s.startsWith(args[1]))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("qaon".equals(sub) || "summaryon".equals(sub))) {
            return Arrays.asList("on", "off").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("qamodel".equals(sub) || "summarymodel".equals(sub))) {
            return Arrays.asList("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-72B-Instruct")
                .stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("qaapi".equals(sub) || "summaryapi".equals(sub))) {
            return Arrays.asList(
                    "https://api.siliconflow.cn/v1/chat/completions",
                    "https://api.openai.com/v1/chat/completions"
                ).stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("qakey".equals(sub) || "summarykey".equals(sub))) {
            return Arrays.asList("sk-").stream()
                .filter(s -> s.startsWith(args[1]))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && "profile".equals(sub)) {
            return Arrays.asList("lowcost", "balanced", "pro").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && "scene".equals(sub)) {
            return Arrays.asList("peak", "quiet", "promo", "auto").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && "scene".equals(sub) && "auto".equalsIgnoreCase(args[1])) {
            return Arrays.asList("on", "off", "status").stream()
                .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("advancement".equals(sub) || "adv".equals(sub))) {
            return Arrays.asList("status", "trigger").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && "list".equals(sub)) {
            return configService.listTopModules().stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && ("get".equals(sub) || "set".equals(sub))) {
            return defaultPathSuggestions().stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .distinct()
                .collect(Collectors.toList());
        }
        if (args.length == 3 && "set".equals(sub)) {
            return setValueSuggestions(args[1], args[2]);
        }

        if (args.length == 2 && "config".equals(sub)) {
            return Arrays.asList("get", "set", "list").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && "config".equals(sub) && "list".equalsIgnoreCase(args[1])) {
            return configService.listTopModules().stream()
                .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && "config".equals(sub) && ("get".equalsIgnoreCase(args[1]) || "set".equalsIgnoreCase(args[1]))) {
            return defaultPathSuggestions().stream()
                .filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT)))
                .distinct()
                .collect(Collectors.toList());
        }
        if (args.length == 4 && "config".equals(sub) && "set".equalsIgnoreCase(args[1])) {
            return setValueSuggestions(args[2], args[3]);
        }

        return new ArrayList<>();
    }

    private List<String> numberSuggestions() {
        return Arrays.asList("1", "2", "5", "10", "20", "30", "50", "100");
    }

    private List<String> defaultPathSuggestions() {
        List<String> suggestions = new ArrayList<>(configService.listTopModules());
        suggestions.add("core.ghost-count");
        suggestions.add("core.chat-interval");
        suggestions.add("chat.format");
        suggestions.add("ai.qa.enabled");
        suggestions.add("ai.qa.model");
        suggestions.add("ai.qa.api-url");
        suggestions.add("ai.summary.enabled");
        suggestions.add("ai.summary.model");
        suggestions.add("ai.summary.api-url");
        return suggestions;
    }

    private List<String> setValueSuggestions(String pathInput, String currentArg) {
        String path = pathInput.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        if (path.endsWith(".enabled")) {
            suggestions.add("true");
            suggestions.add("false");
            suggestions.add("on");
            suggestions.add("off");
        } else if (path.contains("ghost-count") || path.contains("chat-interval") || path.contains("max-tokens")) {
            suggestions.addAll(numberSuggestions());
        } else if (path.contains("model")) {
            suggestions.add("deepseek-ai/DeepSeek-V3");
            suggestions.add("deepseek-ai/DeepSeek-R1");
            suggestions.add("Qwen/Qwen2.5-72B-Instruct");
        } else if (path.contains("api-url")) {
            suggestions.add("https://api.siliconflow.cn/v1/chat/completions");
            suggestions.add("https://api.openai.com/v1/chat/completions");
        } else if (path.contains("api-key")) {
            suggestions.add("sk-");
        }
        return suggestions.stream()
            .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(currentArg.toLowerCase(Locale.ROOT)))
            .collect(Collectors.toList());
    }

    public Chat getChat() {
        return chat;
    }

    public GhostManager getGhostManager() {
        return ghostManager;
    }
}

