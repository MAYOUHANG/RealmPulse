package com.realmpulse;

import net.md_5.bungee.api.ChatColor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtils {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /**
     * Translates legacy color codes (&) and hex color codes (&#rrggbb)
     * @param message The message to translate
     * @return The translated message
     */
    public static String translate(String message) {
        if (message == null) return null;

        // Handle hex colors
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of("#" + matcher.group(1)).toString());
        }
        message = matcher.appendTail(buffer).toString();

        // Handle legacy colors
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
