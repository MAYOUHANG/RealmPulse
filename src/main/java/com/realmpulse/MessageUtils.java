package com.realmpulse;

import org.bukkit.Bukkit;

public class MessageUtils {
    
    public static void broadcast(String message) {
        if (message == null || message.isEmpty()) return;
        Bukkit.broadcastMessage(ColorUtils.translate(message));
    }

    public static void broadcast(GhostPlayer ghost, String format, String text) {
        String message = format
            .replace("{prefix}", ghost.getPrefix())
            .replace("{name}", ghost.getName())
            .replace("{message}", text);
        broadcast(message);
    }
}
