package com.realmpulse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ConnectionListener implements Listener {

    private final PacketManager packetManager;
    private final RealmPulse plugin;
    private final PluginConfigService configService;
    private final DeathManager deathManager;

    public ConnectionListener(
        RealmPulse plugin,
        PluginConfigService configService,
        PacketManager packetManager,
        DeathManager deathManager
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.packetManager = packetManager;
        this.deathManager = deathManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
            packetManager.sendTabListAdd(player, ghost);
        }

        // Delay scoreboard update to ensure MNS/TAB has initialized the client's scoreboard
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
                    packetManager.sendScoreboardLevel(player, ghost);
                }
            }
        }, 80L); // 4 seconds delay

        if (player.hasPlayedBefore()) {
            return;
        }

        double welcomeChance = plugin.getConfig().getDouble("events.welcome-chance", 0.6);
        List<GhostPlayer> shuffled = new ArrayList<>(deathManager.getAliveGhosts(GhostManager.getOnlineGhosts()));
        Collections.shuffle(shuffled);
        int speakers = (int) Math.floor(shuffled.size() * welcomeChance);
        if (speakers <= 0) {
            return;
        }
        List<String> welcomePhrases = plugin.getConfig().getStringList("messages.welcome-phrases");
        String format = configService.getString("chat.format", "{prefix}{name}: {message}");
        long lastTalkTime = 0L;
        long currentDelay = lastTalkTime + 40L;
        for (int i = 0; i < speakers && i < shuffled.size(); i++) {
            GhostPlayer ghost = shuffled.get(i);
            String phrase = pickWelcomePhraseForGhost(welcomePhrases, ghost);
            long typingDelay = calculateTypingDelayTicks(phrase);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                MessageUtils.broadcast(ghost, format, phrase);
            }, currentDelay + typingDelay);
            long interval = 40L + ThreadLocalRandom.current().nextInt(60);
            currentDelay += interval;
        }
    }

    private String pickWelcomePhraseForGhost(List<String> phrases, GhostPlayer ghost) {
        if (phrases.isEmpty()) {
            return ghost.isEnglishSpeaker() ? "Welcome!" : "欢迎";
        }
        List<String> filtered = new ArrayList<>();
        for (String phrase : phrases) {
            if (ghost.isEnglishSpeaker() == isEnglishLike(phrase)) {
                filtered.add(phrase);
            }
        }
        List<String> pool = filtered.isEmpty() ? phrases : filtered;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private boolean isEnglishLike(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int letters = 0;
        int asciiLetters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    asciiLetters++;
                }
            }
        }
        if (letters < 3) {
            return false;
        }
        return ((double) asciiLetters / (double) letters) >= 0.85;
    }

    private long calculateTypingDelayTicks(String text) {
        long baseMin = plugin.getConfig().getLong("messages.typing-base-min-ticks", 15L);
        long baseMax = Math.max(baseMin, plugin.getConfig().getLong("messages.typing-base-max-ticks", 35L));
        long perChar = Math.max(1L, plugin.getConfig().getLong("messages.typing-per-char-ticks", 2L));
        long randomBase = baseMin + ThreadLocalRandom.current().nextLong(baseMax - baseMin + 1L);
        int length = text == null ? 0 : text.length();
        return randomBase + length * perChar;
    }
}
