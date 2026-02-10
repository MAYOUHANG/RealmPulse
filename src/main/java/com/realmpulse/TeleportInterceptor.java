package com.realmpulse;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class TeleportInterceptor implements Listener {

    private final RealmPulse plugin;

    public TeleportInterceptor(RealmPulse plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.length() <= 1 || raw.charAt(0) != '/') {
            return;
        }
        String[] args = raw.substring(1).split("\\s+");
        if (args.length < 2) {
            return;
        }

        String cmd = args[0].toLowerCase();
        String targetName = "";

        if (cmd.equals("tpa") || cmd.equals("tpahere") || cmd.equals("etpa") || cmd.equals("etpahere")) {
            targetName = args[1];
        } else if (cmd.equals("cmi") && args.length >= 3) {
            String sub = args[1].toLowerCase();
            if (sub.equals("tpa") || sub.equals("tpahere")) {
                targetName = args[2];
            }
        }

        if (targetName.isEmpty()) {
            return;
        }

        for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
            if (ghost.getName().equalsIgnoreCase(targetName)) {
                event.setCancelled(true);
                String denyMessage = plugin.getConfig().getString("messages.prevent-tpa", "&cThat player is refusing teleport requests.");
                event.getPlayer().sendMessage(ColorUtils.translate(denyMessage));
                return;
            }
        }
    }
}
