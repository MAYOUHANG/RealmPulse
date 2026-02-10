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

        double welcomeChance = clamp(plugin.getConfig().getDouble("events.welcome-chance", 0.6), 0.0, 1.0);
        if (ThreadLocalRandom.current().nextDouble() > welcomeChance) {
            return;
        }
        List<GhostPlayer> shuffled = new ArrayList<>(deathManager.getAliveGhosts(GhostManager.getOnlineGhosts()));
        Collections.shuffle(shuffled);
        if (shuffled.isEmpty()) {
            return;
        }
        int minSpeakers = Math.max(1, plugin.getConfig().getInt("events.welcome-min-speakers", 1));
        int maxSpeakers = Math.max(minSpeakers, plugin.getConfig().getInt("events.welcome-max-speakers", 2));
        int suggested = (int) Math.floor(shuffled.size() * welcomeChance);
        if (suggested <= 0) {
            suggested = 1;
        }
        int speakers = Math.max(minSpeakers, suggested);
        speakers = Math.min(maxSpeakers, speakers);
        speakers = Math.min(speakers, shuffled.size());
        if (speakers <= 0) {
            return;
        }
        List<String> welcomePhrases = plugin.getConfig().getStringList("messages.welcome-phrases");
        String format = configService.getString("chat.format", "{prefix}{name}: {message}");
        long currentDelay = 40L;
        for (int i = 0; i < speakers && i < shuffled.size(); i++) {
            GhostPlayer ghost = shuffled.get(i);
            String phrase = pickWelcomePhraseForGhost(welcomePhrases, ghost);
            long typingDelay = calculateTypingDelayTicks(phrase);
            Bukkit.getScheduler().runTaskLater(plugin, () -> MessageUtils.broadcast(ghost, format, phrase), currentDelay + typingDelay);
            long interval = 40L + ThreadLocalRandom.current().nextInt(60);
            currentDelay += interval;
        }
    }

    private String pickWelcomePhraseForGhost(List<String> phrases, GhostPlayer ghost) {
        boolean english = ghost != null && ghost.isEnglishSpeaker();
        List<String> localized = plugin.getConfig().getStringList(english ? "messages.welcome-phrases-en" : "messages.welcome-phrases-zh");
        if (!localized.isEmpty()) {
            return localized.get(ThreadLocalRandom.current().nextInt(localized.size()));
        }
        if (phrases.isEmpty()) {
            return english ? "Welcome!" : "\u6b22\u8fce\u4f60";
        }

        List<String> filtered = new ArrayList<>();
        for (String phrase : phrases) {
            if (LanguageClassifier.matches(phrase, english)) {
                filtered.add(phrase);
            }
        }
        if (filtered.isEmpty()) {
            return english ? "Welcome!" : "\u6b22\u8fce\u4f60";
        }
        return filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
    }

    private long calculateTypingDelayTicks(String text) {
        long baseMin = plugin.getConfig().getLong("messages.typing-base-min-ticks", 15L);
        long baseMax = Math.max(baseMin, plugin.getConfig().getLong("messages.typing-base-max-ticks", 35L));
        long perChar = Math.max(1L, plugin.getConfig().getLong("messages.typing-per-char-ticks", 2L));
        long randomBase = baseMin + ThreadLocalRandom.current().nextLong(baseMax - baseMin + 1L);
        int length = text == null ? 0 : text.length();
        return randomBase + length * perChar;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
