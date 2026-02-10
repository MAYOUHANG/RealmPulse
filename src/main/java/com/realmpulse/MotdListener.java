package com.realmpulse;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedServerPing;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class MotdListener extends PacketAdapter {

    public MotdListener(Plugin plugin) {
        super(PacketAdapter.params(plugin, PacketType.Status.Server.SERVER_INFO)
            .listenerPriority(com.comphenix.protocol.events.ListenerPriority.MONITOR));
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        try {
            WrappedServerPing ping = event.getPacket().getServerPings().read(0);
            if (ping == null) return;

            int fakePlayers = GhostManager.getGhosts().size();
            int realPlayers = Bukkit.getOnlinePlayers().size();
            int newCount = realPlayers + fakePlayers;
            
            ping.setPlayersOnline(newCount);
            
            event.getPacket().getServerPings().write(0, ping);
        } catch (Exception e) {
            plugin.getLogger().warning("Error in MotdListener: " + e.getMessage());
        }
    }
}
