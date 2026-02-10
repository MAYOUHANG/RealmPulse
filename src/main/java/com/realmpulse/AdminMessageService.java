package com.realmpulse;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class AdminMessageService {

    private static final String DEFAULT_PREFIX = "&#79C7F6&lR&#98DEF7&lP &8|&r";

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
        send(sender, "&#8ED9FF&lRealmPulse &7\u7BA1\u7406\u5458\u547D\u4EE4 / Admin Commands");
        send(sender, "&7/rp bots &8- &b\u67E5\u770B\u673A\u5668\u4EBA\u6570\u91CF &7/ Show bot count");
        send(sender, "&7/rp addbot <count> &8- &b\u589E\u52A0\u673A\u5668\u4EBA &7/ Add bots");
        send(sender, "&7/rp removebot <count> &8- &b\u51CF\u5C11\u673A\u5668\u4EBA &7/ Remove bots");
        send(sender, "&7/rp setbot <count> &8- &b\u8BBE\u7F6E\u673A\u5668\u4EBA\u603B\u6570 &7/ Set bot count");
        send(sender, "&7/rp qamodel <model> &8- &b\u8BBE\u7F6E\u95EE\u7B54\u6A21\u578B &7/ Set QA model");
        send(sender, "&7/rp summarymodel <model> &8- &b\u8BBE\u7F6E\u603B\u7ED3\u6A21\u578B &7/ Set summary model");
        send(sender, "&7/rp qaon <on|off> &8- &b\u95EE\u7B54 AI \u5F00\u5173 &7/ QA AI toggle");
        send(sender, "&7/rp summaryon <on|off> &8- &b\u603B\u7ED3 AI \u5F00\u5173 &7/ Summary AI toggle");
        send(sender, "&7/rp qakey <key> &8- &b\u8BBE\u7F6E\u95EE\u7B54\u5BC6\u94A5 &7/ Set QA key");
        send(sender, "&7/rp summarykey <key> &8- &b\u8BBE\u7F6E\u603B\u7ED3\u5BC6\u94A5 &7/ Set summary key");
        send(sender, "&7/rp profile <lowcost|balanced|pro> &8- &b\u4E00\u952E\u5E94\u7528\u6863\u4F4D &7/ One-click profile");
        send(sender, "&7/rp scene <peak|quiet|promo> &8- &b\u5207\u6362\u8FD0\u8425\u573A\u666F &7/ Scene switch");
        send(sender, "&7/rp scene auto <on|off|status> &8- &b\u81EA\u52A8\u573A\u666F\u5F00\u5173 &7/ Auto scene switch");
        send(sender, "&7/rp advancement <status|trigger> &8- &b\u6210\u5C31\u6A21\u62DF\u5668\u7BA1\u7406 &7/ Advancement simulator");
        send(sender, "&7/rp learn status|flush &8- &b\u5B66\u4E60\u961F\u5217\u7BA1\u7406 &7/ Learning controls");
        send(sender, "&7/rp reload &8- &b\u91CD\u8F7D\u914D\u7F6E &7/ Reload config");
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
        if (safeEn.isBlank()) {
            return safeZh;
        }
        if (safeZh.equalsIgnoreCase(safeEn)) {
            return safeEn;
        }
        if (safeZh.isBlank() || looksGarbledChinese(safeZh)) {
            return safeEn;
        }
        return safeZh + " &8| &7" + safeEn;
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
