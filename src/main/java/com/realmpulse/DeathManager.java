package com.realmpulse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public class DeathManager {

    private static final long DEAD_DURATION_MS = 30_000L;

    private final RealmPulse plugin;
    private final Map<UUID, Long> deadUntil = new ConcurrentHashMap<>();

    public DeathManager(RealmPulse plugin) {
        this.plugin = plugin;
    }

    public void startDeathSimulation() {
        if (!plugin.getConfig().getBoolean("death-settings.enabled", true)) {
            return;
        }
        long intervalSeconds = 60L;
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::runDeathTick,
            intervalSeconds * 20L,
            intervalSeconds * 20L
        );
    }

    public boolean isGhostDead(GhostPlayer ghost) {
        Long until = deadUntil.get(ghost.getUuid());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            deadUntil.remove(ghost.getUuid());
            return false;
        }
        return true;
    }

    public List<GhostPlayer> getAliveGhosts(List<GhostPlayer> ghosts) {
        List<GhostPlayer> alive = new ArrayList<>();
        for (GhostPlayer ghost : ghosts) {
            if (!isGhostDead(ghost)) {
                alive.add(ghost);
            }
        }
        return alive;
    }

    private void runDeathTick() {
        double chance = plugin.getConfig().getDouble("death-settings.chance", 0.05);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        List<GhostPlayer> aliveGhosts = getAliveGhosts(GhostManager.getOnlineGhosts());
        if (aliveGhosts.isEmpty()) {
            return;
        }
        GhostPlayer ghost = aliveGhosts.get(ThreadLocalRandom.current().nextInt(aliveGhosts.size()));
        List<String> reasons = plugin.getConfig().getStringList("death-settings.death-reasons");
        if (reasons.isEmpty()) {
            return;
        }
        String reason = reasons.get(ThreadLocalRandom.current().nextInt(reasons.size()));
        String format = plugin.getConfig().getString("death-settings.cmi-format", "&e{name} &f{reason}");
        String message = format
            .replace("{name}", ghost.getName())
            .replace("{reason}", reason);
        MessageUtils.broadcast(message);
        deadUntil.put(ghost.getUuid(), System.currentTimeMillis() + DEAD_DURATION_MS);
    }
}
