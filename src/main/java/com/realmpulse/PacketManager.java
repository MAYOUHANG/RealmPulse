package com.realmpulse;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import java.util.Collections;
import java.util.EnumSet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class PacketManager {
    private final EnumSet<PlayerInfoAction> tabListActions;
    private final JavaPlugin plugin;
    private final ProtocolManager protocolManager;

    public PacketManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        
        // Use a more conservative approach for 1.21.4+
        if (MinecraftVersion.get().isAtLeast(MinecraftVersion.V1_19_3)) {
            this.tabListActions = EnumSet.of(
                PlayerInfoAction.ADD_PLAYER,
                PlayerInfoAction.UPDATE_LATENCY,
                PlayerInfoAction.UPDATE_LISTED,
                PlayerInfoAction.UPDATE_DISPLAY_NAME
            );
        } else {
            this.tabListActions = EnumSet.of(
                PlayerInfoAction.ADD_PLAYER,
                PlayerInfoAction.UPDATE_LATENCY,
                PlayerInfoAction.UPDATE_DISPLAY_NAME
            );
        }
    }

    public void sendTabListAdd(Player target, GhostPlayer ghost) {
        // Send Level via Scoreboard
        sendScoreboardLevel(target, ghost);

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, tabListActions);

        WrappedGameProfile profile = ghost.getProfile();
        ghost.setRandomPing();

        PlayerInfoData infoData = new PlayerInfoData(
            ghost.getUuid(),
            ghost.getPing(),
            true, // listed
            NativeGameMode.SURVIVAL,
            profile,
            WrappedChatComponent.fromLegacyText(
                ColorUtils.translate(ghost.getDisplayName())
            )
        );

        packet.getPlayerInfoDataLists().write(1, Collections.singletonList(infoData));

        try {
            protocolManager.sendServerPacket(target, packet);
        } catch (Exception e) {
            // Only log if it's a real issue, common errors on reload can be ignored
            if (plugin.isEnabled()) {
                plugin.getLogger().warning("Could not send tab list add packet to " + target.getName() + ": " + e.getMessage());
            }
        }
    }

    public void sendTabListRemove(Player target, GhostPlayer ghost) {
        PacketType type = MinecraftVersion.get().isAtLeast(MinecraftVersion.V1_19_3) 
            ? PacketType.Play.Server.PLAYER_INFO_REMOVE 
            : PacketType.Play.Server.PLAYER_INFO;

        PacketContainer packet = protocolManager.createPacket(type);
        
        if (type == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            packet.getUUIDLists().write(0, Collections.singletonList(ghost.getUuid()));
        } else {
            packet.getPlayerInfoActions().write(0, EnumSet.of(PlayerInfoAction.REMOVE_PLAYER));
            PlayerInfoData infoData = new PlayerInfoData(ghost.getUuid(), 0, false, NativeGameMode.SURVIVAL, ghost.getProfile(), null);
            packet.getPlayerInfoDataLists().write(1, Collections.singletonList(infoData));
        }

        try {
            protocolManager.sendServerPacket(target, packet);
        } catch (Exception e) {
            if (plugin.isEnabled()) {
                plugin.getLogger().warning("Could not send tab list remove packet to " + target.getName() + ": " + e.getMessage());
            }
        }
    }

    public void updateTabForAll(GhostPlayer ghost) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendTabListAdd(player, ghost);
        }
    }

    public void sendScoreboardLevel(Player target, GhostPlayer ghost) {
        try {
            PacketContainer scorePacket = protocolManager.createPacket(PacketType.Play.Server.SCOREBOARD_SCORE);
            scorePacket.getStrings().write(0, ghost.getName()); // Score owner
            scorePacket.getScoreboardActions().write(0, com.comphenix.protocol.wrappers.EnumWrappers.ScoreboardAction.CHANGE);
            scorePacket.getStrings().write(1, "Level"); // Objective name
            scorePacket.getIntegers().write(0, ghost.getLevel()); // Value

            protocolManager.sendServerPacket(target, scorePacket);
        } catch (Exception e) {
            // Ignore
        }
    }
}
