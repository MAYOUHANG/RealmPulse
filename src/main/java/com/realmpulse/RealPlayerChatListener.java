package com.realmpulse;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class RealPlayerChatListener implements Listener {

    private final SmartChatManager smartChatManager;
    private static final Pattern EN_QUESTION_WORD_PATTERN = Pattern.compile(
        "\\b(how|what|where|why|when|which|can|could|should|does|do|is|are)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private final List<String> zhQuestionKeywords = Arrays.asList(
        "怎么", "为何", "为什么", "哪里", "多少", "请问", "如何", "能不能", "可以吗", "是不是"
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

        smartChatManager.maybeSimulateBotMentionDialogue(mentionedGhost);

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
        String trimmed = message.trim();
        if (trimmed.length() <= 2) {
            return false;
        }
        if (trimmed.contains("?") || trimmed.contains("？")) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (EN_QUESTION_WORD_PATTERN.matcher(lower).find()) {
            return true;
        }
        for (String keyword : zhQuestionKeywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (lower.endsWith("吗") || lower.endsWith("么") || lower.endsWith("嘛")) {
            return true;
        }
        return false;
    }

    private boolean mentionsGhost(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
            String name = ghost.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String ghostLower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("@" + ghostLower) || containsWholeToken(lower, ghostLower)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsWholeToken(String textLower, String tokenLower) {
        if (textLower == null || tokenLower == null || tokenLower.isBlank()) {
            return false;
        }
        int from = 0;
        while (from <= textLower.length() - tokenLower.length()) {
            int index = textLower.indexOf(tokenLower, from);
            if (index < 0) {
                return false;
            }
            int leftIndex = index - 1;
            int rightIndex = index + tokenLower.length();
            boolean leftOk = leftIndex < 0 || !isNameChar(textLower.charAt(leftIndex));
            boolean rightOk = rightIndex >= textLower.length() || !isNameChar(textLower.charAt(rightIndex));
            if (leftOk && rightOk) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
