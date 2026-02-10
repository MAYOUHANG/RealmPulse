package com.realmpulse;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

public class ConnectionSimulator {

    private final RealmPulse plugin;
    private final PacketManager packetManager;

    public ConnectionSimulator(RealmPulse plugin, PacketManager packetManager) {
        this.plugin = plugin;
        this.packetManager = packetManager;
    }

    public void startSimulation() {
        if (!plugin.getConfig().getBoolean("connection-settings.enabled", true)) {
            return;
        }
        long intervalSeconds = plugin.getConfig().getLong("connection-settings.interval-seconds", 30L);
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::runConnectionTick,
            intervalSeconds * 20L,
            intervalSeconds * 20L
        );
    }

    private void runConnectionTick() {
        if (!plugin.getConfig().getBoolean("connection-settings.enabled", true)) {
            return;
        }
        double quitChance = plugin.getConfig().getDouble("connection-settings.quit-chance", 0.02);
        double rejoinChance = plugin.getConfig().getDouble("connection-settings.rejoin-chance", 0.1);
        String joinFormat = plugin.getConfig().getString("connection-settings.cmi-join-format", "&e{name} joined the game");
        String quitFormat = plugin.getConfig().getString("connection-settings.cmi-quit-format", "&e{name} left the game");

        for (GhostPlayer ghost : GhostManager.getGhosts()) {
            if (ghost.isOnline()) {
                if (ThreadLocalRandom.current().nextDouble() < quitChance) {
                    ghost.setOnline(false);
                    sendTabListRemoveAll(ghost);
                    broadcastFormatted(quitFormat, ghost.getName());
                }
            } else {
                if (ThreadLocalRandom.current().nextDouble() < rejoinChance) {
                    ghost.setOnline(true);
                    sendTabListAddAll(ghost);
                    broadcastFormatted(joinFormat, ghost.getName());
                }
            }
        }
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
