package com.realmpulse;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TabInterceptor implements Listener {

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getBuffer();
        if (buffer.startsWith("/")) {
            buffer = buffer.substring(1);
        }
        
        String[] args = buffer.split("\s+", -1);
        if (args.length < 2) return;

        String cmd = args[0].toLowerCase();
        boolean shouldAddGhosts = false;

        // Check if the command is one where we want to suggest ghost players
        if (cmd.equals("tpa") || cmd.equals("tpahere") || cmd.equals("etpa") || cmd.equals("etpahere") || cmd.equals("msg") || cmd.equals("w") || cmd.equals("tell")) {
            shouldAddGhosts = args.length == 2;
        } else if (cmd.equals("cmi") && args.length >= 2) {
            String sub = args[1].toLowerCase();
            if (sub.equals("tpa") || sub.equals("tpahere") || sub.equals("msg")) {
                shouldAddGhosts = args.length == 3;
            }
        }

        if (shouldAddGhosts) {
            String lastToken = args[args.length - 1].toLowerCase();
            List<String> ghostNames = GhostManager.getOnlineGhosts().stream()
                .map(GhostPlayer::getName)
                .filter(name -> name.toLowerCase().startsWith(lastToken))
                .collect(Collectors.toList());
            
            if (!ghostNames.isEmpty()) {
                List<String> completions = new ArrayList<>(event.getCompletions());
                completions.addAll(ghostNames);
                event.setCompletions(completions);
            }
        }
    }
}
