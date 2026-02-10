package com.realmpulse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

public class DeathManager {

    private static final long DEFAULT_DEAD_DURATION_MS = 30_000L;

    private final RealmPulse plugin;
    private final Map<UUID, Long> deadUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDeathAt = new ConcurrentHashMap<>();
    private volatile long lastGlobalDeathAt = 0L;

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
        pruneGhostStateCache();
        if (shouldSkipByServerState()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (shouldSkipByDeathCooldown(now)) {
            return;
        }
        double chance = plugin.getConfig().getDouble("death-settings.chance", 0.05);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        long perGhostMinMs = Math.max(10L, plugin.getConfig().getLong("death-settings.per-ghost-min-interval-seconds", 900L)) * 1000L;
        List<GhostPlayer> aliveGhosts = new ArrayList<>();
        for (GhostPlayer ghost : getAliveGhosts(GhostManager.getOnlineGhosts())) {
            if (!isGhostInDeathCooldown(ghost, now, perGhostMinMs)) {
                aliveGhosts.add(ghost);
            }
        }
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
        long deadDurationMs = resolveDeadDurationMs();
        deadUntil.put(ghost.getUuid(), now + deadDurationMs);
        lastDeathAt.put(ghost.getUuid(), now);
        lastGlobalDeathAt = now;
    }

    private long resolveDeadDurationMs() {
        long minSeconds = Math.max(5L, plugin.getConfig().getLong("death-settings.dead-duration-seconds-min", 25L));
        long maxSeconds = Math.max(minSeconds, plugin.getConfig().getLong("death-settings.dead-duration-seconds-max", 90L));
        if (maxSeconds <= minSeconds) {
            return minSeconds * 1000L;
        }
        long randomSeconds = minSeconds + ThreadLocalRandom.current().nextLong(maxSeconds - minSeconds + 1L);
        return randomSeconds * 1000L;
    }

    private boolean shouldSkipByServerState() {
        if (!plugin.getConfig().getBoolean("death-settings.require-real-player-online", true)) {
            return false;
        }
        int minPlayers = Math.max(0, plugin.getConfig().getInt("death-settings.min-online-real-players", 1));
        return Bukkit.getOnlinePlayers().size() < minPlayers;
    }

    private boolean isGhostInDeathCooldown(GhostPlayer ghost, long now, long perGhostMinMs) {
        long last = lastDeathAt.getOrDefault(ghost.getUuid(), 0L);
        return now - last < perGhostMinMs;
    }

    private boolean shouldSkipByDeathCooldown(long now) {
        long globalMinMs = Math.max(10L, plugin.getConfig().getLong("death-settings.global-min-interval-seconds", 180L)) * 1000L;
        return now - lastGlobalDeathAt < globalMinMs;
    }

    private void pruneGhostStateCache() {
        Set<UUID> active = new HashSet<>();
        for (GhostPlayer ghost : GhostManager.getGhosts()) {
            if (ghost == null || ghost.getUuid() == null) {
                continue;
            }
            active.add(ghost.getUuid());
        }
        deadUntil.keySet().removeIf(uuid -> !active.contains(uuid));
        lastDeathAt.keySet().removeIf(uuid -> !active.contains(uuid));
    }
}
