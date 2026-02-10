package com.realmpulse;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class ConnectionSimulator {

    private final RealmPulse plugin;
    private final PacketManager packetManager;
    private final Map<String, Long> lastStateChangeAt = new HashMap<>();
    private final Object stateLock = new Object();
    private BukkitTask connectionTask;

    public ConnectionSimulator(RealmPulse plugin, PacketManager packetManager) {
        this.plugin = plugin;
        this.packetManager = packetManager;
    }

    public void startSimulation() {
        stopSimulation();
        if (!plugin.getConfig().getBoolean("connection-settings.enabled", true)) {
            return;
        }
        rebalanceOnlineRatioSilently();
        long intervalSeconds = Math.max(5L, plugin.getConfig().getLong("connection-settings.interval-seconds", 30L));
        connectionTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::runConnectionTick,
            intervalSeconds * 20L,
            intervalSeconds * 20L
        );
    }

    public void reload() {
        startSimulation();
    }

    public void stopSimulation() {
        if (connectionTask != null) {
            connectionTask.cancel();
            connectionTask = null;
        }
    }

    public void rebalanceOnlineRatioSilently() {
        List<GhostPlayer> allGhosts = GhostManager.getGhosts();
        if (allGhosts.isEmpty()) {
            return;
        }

        double targetOnlineRatio = clamp(plugin.getConfig().getDouble("connection-settings.target-online-ratio", 0.72), 0.10, 0.98);
        int total = allGhosts.size();
        int desiredOnline = (int) Math.round(total * targetOnlineRatio);
        desiredOnline = Math.max(1, Math.min(total - 1, desiredOnline));
        long now = System.currentTimeMillis();

        List<GhostPlayer> online = new ArrayList<>();
        List<GhostPlayer> offline = new ArrayList<>();
        for (GhostPlayer ghost : allGhosts) {
            if (ghost.isOnline()) {
                online.add(ghost);
            } else {
                offline.add(ghost);
            }
        }

        if (online.size() > desiredOnline) {
            Collections.shuffle(online);
            int needOffline = online.size() - desiredOnline;
            for (int i = 0; i < needOffline && i < online.size(); i++) {
                GhostPlayer ghost = online.get(i);
                ghost.setOnline(false);
                markStateChanged(ghost, now);
                sendTabListRemoveAll(ghost);
            }
            return;
        }

        if (online.size() < desiredOnline) {
            Collections.shuffle(offline);
            int needOnline = desiredOnline - online.size();
            for (int i = 0; i < needOnline && i < offline.size(); i++) {
                GhostPlayer ghost = offline.get(i);
                ghost.setOnline(true);
                markStateChanged(ghost, now);
                sendTabListAddAll(ghost);
            }
        }
    }

    private void runConnectionTick() {
        if (!plugin.getConfig().getBoolean("connection-settings.enabled", true)) {
            return;
        }
        if (plugin.getConfig().getBoolean("connection-settings.require-real-player-online", true)
            && Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        List<GhostPlayer> allGhosts = GhostManager.getGhosts();
        if (allGhosts.isEmpty()) {
            return;
        }
        pruneStateCache(allGhosts);

        int maxChanges = Math.max(1, plugin.getConfig().getInt("connection-settings.max-state-changes-per-tick", 2));
        long minStateSeconds = Math.max(10L, plugin.getConfig().getLong("connection-settings.min-seconds-between-state-change", 180L));
        long minStateMs = minStateSeconds * 1000L;

        double quitChance = plugin.getConfig().getDouble("connection-settings.quit-chance", 0.02);
        double rejoinChance = plugin.getConfig().getDouble("connection-settings.rejoin-chance", 0.1);
        double targetOnlineRatio = clamp(plugin.getConfig().getDouble("connection-settings.target-online-ratio", 0.72), 0.10, 0.98);
        double targetTolerance = clamp(plugin.getConfig().getDouble("connection-settings.target-ratio-tolerance", 0.08), 0.01, 0.35);
        String joinFormat = plugin.getConfig().getString("connection-settings.cmi-join-format", "&e{name} joined the game");
        String quitFormat = plugin.getConfig().getString("connection-settings.cmi-quit-format", "&e{name} left the game");
        long now = System.currentTimeMillis();

        for (int i = 0; i < maxChanges; i++) {
            List<GhostPlayer> onlineCandidates = collectCandidates(allGhosts, true, now, minStateMs);
            List<GhostPlayer> offlineCandidates = collectCandidates(allGhosts, false, now, minStateMs);
            if (onlineCandidates.isEmpty() && offlineCandidates.isEmpty()) {
                return;
            }

            int onlineCount = 0;
            for (GhostPlayer ghost : allGhosts) {
                if (ghost.isOnline()) {
                    onlineCount++;
                }
            }
            double currentRatio = (double) onlineCount / (double) allGhosts.size();
            boolean changed = false;

            if (currentRatio > targetOnlineRatio + targetTolerance && !onlineCandidates.isEmpty()) {
                GhostPlayer ghost = onlineCandidates.get(ThreadLocalRandom.current().nextInt(onlineCandidates.size()));
                transitionOffline(ghost, quitFormat, now);
                changed = true;
            } else if (currentRatio < targetOnlineRatio - targetTolerance && !offlineCandidates.isEmpty()) {
                GhostPlayer ghost = offlineCandidates.get(ThreadLocalRandom.current().nextInt(offlineCandidates.size()));
                transitionOnline(ghost, joinFormat, now);
                changed = true;
            } else {
                if (!onlineCandidates.isEmpty() && ThreadLocalRandom.current().nextDouble() < quitChance) {
                    GhostPlayer ghost = onlineCandidates.get(ThreadLocalRandom.current().nextInt(onlineCandidates.size()));
                    transitionOffline(ghost, quitFormat, now);
                    changed = true;
                } else if (!offlineCandidates.isEmpty() && ThreadLocalRandom.current().nextDouble() < rejoinChance) {
                    GhostPlayer ghost = offlineCandidates.get(ThreadLocalRandom.current().nextInt(offlineCandidates.size()));
                    transitionOnline(ghost, joinFormat, now);
                    changed = true;
                }
            }

            if (!changed) {
                return;
            }
        }
    }

    private List<GhostPlayer> collectCandidates(List<GhostPlayer> allGhosts, boolean online, long now, long minStateMs) {
        List<GhostPlayer> candidates = new ArrayList<>();
        for (GhostPlayer ghost : allGhosts) {
            if (ghost.isOnline() != online) {
                continue;
            }
            if (!isEligibleForStateChange(ghost, now, minStateMs)) {
                continue;
            }
            candidates.add(ghost);
        }
        return candidates;
    }

    private boolean isEligibleForStateChange(GhostPlayer ghost, long now, long minStateMs) {
        if (ghost == null || ghost.getName() == null || ghost.getName().isBlank()) {
            return false;
        }
        String key = ghost.getName().toLowerCase(Locale.ROOT);
        synchronized (stateLock) {
            long last = lastStateChangeAt.getOrDefault(key, 0L);
            return now - last >= minStateMs;
        }
    }

    private void markStateChanged(GhostPlayer ghost, long now) {
        if (ghost == null || ghost.getName() == null || ghost.getName().isBlank()) {
            return;
        }
        String key = ghost.getName().toLowerCase(Locale.ROOT);
        synchronized (stateLock) {
            lastStateChangeAt.put(key, now);
        }
    }

    private void pruneStateCache(List<GhostPlayer> ghosts) {
        long now = System.currentTimeMillis();
        long staleMs = Math.max(300_000L, plugin.getConfig().getLong("connection-settings.state-cache-stale-seconds", 3600L) * 1000L);
        Set<String> active = new HashSet<>();
        for (GhostPlayer ghost : ghosts) {
            if (ghost == null || ghost.getName() == null || ghost.getName().isBlank()) {
                continue;
            }
            active.add(ghost.getName().toLowerCase(Locale.ROOT));
        }
        synchronized (stateLock) {
            lastStateChangeAt.entrySet().removeIf(entry ->
                !active.contains(entry.getKey()) || now - entry.getValue() > staleMs
            );
        }
    }

    private void transitionOffline(GhostPlayer ghost, String quitFormat, long now) {
        ghost.setOnline(false);
        markStateChanged(ghost, now);
        sendTabListRemoveAll(ghost);
        broadcastFormatted(quitFormat, ghost.getName());
    }

    private void transitionOnline(GhostPlayer ghost, String joinFormat, long now) {
        ghost.setOnline(true);
        markStateChanged(ghost, now);
        sendTabListAddAll(ghost);
        broadcastFormatted(joinFormat, ghost.getName());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void sendTabListAddAll(GhostPlayer ghost) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            packetManager.sendTabListAdd(player, ghost);
        }
    }

    private void sendTabListRemoveAll(GhostPlayer ghost) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            packetManager.sendTabListRemove(player, ghost);
        }
    }

    private void broadcastFormatted(String format, String name) {
        String message = format.replace("{name}", name);
        MessageUtils.broadcast(message);
    }
}
