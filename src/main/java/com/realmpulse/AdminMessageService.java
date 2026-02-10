package com.realmpulse;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminMessageService {

    private static final String DEFAULT_PREFIX = "&#79C7F6&lR&#98DEF7&lP &8|&r";
    private static final String DEFAULT_ADMIN_PRIMARY = "zh";
    private static final String DEFAULT_ADMIN_SECONDARY = "en";

    private final JavaPlugin plugin;
    private final PluginConfigService configService;

    public AdminMessageService(JavaPlugin plugin, PluginConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    public void info(CommandSender sender, String zh, String en) {
        send(sender, styleInfo(zh, en));
    }

    public void success(CommandSender sender, String zh, String en) {
        send(sender, styleSuccess(zh, en));
    }

    public void warn(CommandSender sender, String zh, String en) {
        send(sender, styleWarn(zh, en));
    }

    public void error(CommandSender sender, String zh, String en) {
        send(sender, styleError(zh, en));
    }

    public void sendHelp(CommandSender sender) {
        send(sender, "&#8ED9FF&l" + localize("RealmPulse 管理员命令", "RealmPulse Admin Commands"));
        send(sender, "&7/rp bots &8- &b" + localize("查看机器人数量", "Show bot count"));
        send(sender, "&7/rp addbot <count> &8- &b" + localize("增加机器人", "Add bots"));
        send(sender, "&7/rp removebot <count> &8- &b" + localize("减少机器人", "Remove bots"));
        send(sender, "&7/rp setbot <count> &8- &b" + localize("设置机器人总数", "Set bot count"));
        send(sender, "&7/rp qamodel <model> &8- &b" + localize("设置问答模型", "Set QA model"));
        send(sender, "&7/rp summarymodel <model> &8- &b" + localize("设置总结模型", "Set summary model"));
        send(sender, "&7/rp qaon <on|off> &8- &b" + localize("问答 AI 开关", "QA AI toggle"));
        send(sender, "&7/rp summaryon <on|off> &8- &b" + localize("总结 AI 开关", "Summary AI toggle"));
        send(sender, "&7/rp qakey <key> &8- &b" + localize("设置问答密钥", "Set QA key"));
        send(sender, "&7/rp summarykey <key> &8- &b" + localize("设置总结密钥", "Set summary key"));
        send(sender, "&7/rp profile <lowcost|balanced|pro> &8- &b" + localize("一键应用档位", "One-click profile"));
        send(sender, "&7/rp scene <peak|quiet|promo> &8- &b" + localize("切换运营场景", "Scene switch"));
        send(sender, "&7/rp scene auto <on|off|status> &8- &b" + localize("自动场景开关", "Auto scene switch"));
        send(sender, "&7/rp advancement <status|trigger> &8- &b" + localize("成就模拟器管理", "Advancement simulator"));
        send(sender, "&7/rp learn status|flush &8- &b" + localize("学习队列管理", "Learning controls"));
        send(sender, "&7/rp reload &8- &b" + localize("重载配置", "Reload config"));
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(ColorUtils.translate(prefix() + message));
    }

    private String prefix() {
        return configService.getString("admin-ui.prefix", DEFAULT_PREFIX);
    }

    private String styleInfo(String zh, String en) {
        return "&#9ED7FF" + localize(zh, en);
    }

    private String styleSuccess(String zh, String en) {
        return "&#A6F4C5" + localize(zh, en);
    }

    private String styleWarn(String zh, String en) {
        return "&#FFDCA3" + localize(zh, en);
    }

    private String styleError(String zh, String en) {
        return "&#FFB3B3" + localize(zh, en);
    }

    private String localize(String zh, String en) {
        String safeZh = zh == null ? "" : zh.trim();
        String safeEn = en == null ? "" : en.trim();

        String primary = configService.getString("language.admin-primary", DEFAULT_ADMIN_PRIMARY);
        String secondary = configService.getString("language.admin-secondary", DEFAULT_ADMIN_SECONDARY);
        boolean showSecondary = configService.getBoolean("language.show-secondary", false);
        boolean primaryEnglish = isEnglishCode(primary);
        boolean secondaryEnglish = isEnglishCode(secondary);

        String primaryText = primaryEnglish ? safeEn : safeZh;
        String fallbackText = primaryEnglish ? safeZh : safeEn;
        if (primaryText.isBlank() || (!primaryEnglish && looksGarbledChinese(primaryText))) {
            primaryText = fallbackText;
        }
        if (primaryText.isBlank()) {
            return "";
        }

        if (!showSecondary) {
            return primaryText;
        }

        String secondaryText = secondaryEnglish ? safeEn : safeZh;
        if (secondaryText.isBlank() || secondaryText.equalsIgnoreCase(primaryText)) {
            return primaryText;
        }
        if (!secondaryEnglish && looksGarbledChinese(secondaryText)) {
            return primaryText;
        }
        return primaryText + " &8| &7" + secondaryText;
    }

    private boolean isEnglishCode(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("en");
    }

    private boolean looksGarbledChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isMojibakeMarker(c)) {
                return true;
            }
            if (c >= '\uE000' && c <= '\uF8FF') {
                return true;
            }
        }
        return text.indexOf('\uFFFD') >= 0 || text.contains("\u951F") || text.contains("\u9225");
    }

    private boolean isMojibakeMarker(char c) {
        return c == '\u95C2'
            || c == '\u5A75'
            || c == '\u93CC'
            || c == '\u934F'
            || c == '\u7F01'
            || c == '\u95F8'
            || c == '\u95B9'
            || c == '\u95BB'
            || c == '\u940E';
    }
}

