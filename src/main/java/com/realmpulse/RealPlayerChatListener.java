package com.realmpulse;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class RealPlayerChatListener implements Listener {

    private final SmartChatManager smartChatManager;
    private final List<String> keywords = Arrays.asList(
        "?", "？", "how", "what", "where", "why",
        "怎么", "为何", "为什么", "哪里", "多少", "请问", "如何", "能不能", "可以吗"
    );

    public RealPlayerChatListener(SmartChatManager smartChatManager) {
        this.smartChatManager = smartChatManager;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage().trim();
        String playerName = event.getPlayer().getName();
        boolean mentionedGhost = mentionsGhost(message);
        boolean isQaLike = isQuestionLike(message);

        smartChatManager.maybeSimulateReplyToRealPlayer(playerName, mentionedGhost);

        if (isQaLike) {
            // Real-player Q&A is high-priority learning input.
            smartChatManager.learnFromRealPlayer(playerName, message, true);
            smartChatManager.triggerAI(message, playerName, mentionedGhost);
            return;
        }

        if (!mentionedGhost) {
            smartChatManager.learnFromRealPlayer(playerName, message, false);
        }
    }

    private boolean isQuestionLike(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            String k = keyword.toLowerCase(Locale.ROOT);
            if (lower.contains(k) && !lower.equals(k)) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsGhost(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
            if (lower.contains(ghost.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
