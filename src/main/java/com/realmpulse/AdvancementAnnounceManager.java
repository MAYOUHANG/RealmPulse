package com.realmpulse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.scheduler.BukkitTask;

public class AdvancementAnnounceManager {

    private static final long ONE_HOUR_MS = 60L * 60L * 1000L;

    private final RealmPulse plugin;
    private final PluginConfigService configService;
    private final DeathManager deathManager;
    private final AdvancementProgressStore progressStore;
    private final Deque<Long> globalTriggerTimes = new ArrayDeque<>();
    private final Map<UUID, Deque<Long>> ghostTriggerTimes = new HashMap<>();
    private final Map<UUID, Long> ghostLastTriggerAt = new HashMap<>();

    private BukkitTask task;
    private long globalLastTriggerAt = 0L;

    public static final class AdvancementStatus {
        public final boolean enabled;
        public final int availableAdvancements;
        public final int onlineAliveGhosts;
        public final int trackedGhosts;
        public final int totalCompletions;
        public final int globalTriggersLastHour;

        public AdvancementStatus(
            boolean enabled,
            int availableAdvancements,
            int onlineAliveGhosts,
            int trackedGhosts,
            int totalCompletions,
            int globalTriggersLastHour
        ) {
            this.enabled = enabled;
            this.availableAdvancements = availableAdvancements;
            this.onlineAliveGhosts = onlineAliveGhosts;
            this.trackedGhosts = trackedGhosts;
            this.totalCompletions = totalCompletions;
            this.globalTriggersLastHour = globalTriggersLastHour;
        }
    }

    public static final class TriggerResult {
        public final boolean success;
        public final String reason;
        public final String ghostName;
        public final String advancementName;
        public final String advancementKey;

        public TriggerResult(boolean success, String reason, String ghostName, String advancementName, String advancementKey) {
            this.success = success;
            this.reason = reason;
            this.ghostName = ghostName;
            this.advancementName = advancementName;
            this.advancementKey = advancementKey;
        }
    }

    public AdvancementAnnounceManager(
        RealmPulse plugin,
        PluginConfigService configService,
        DeathManager deathManager
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.deathManager = deathManager;
        this.progressStore = new AdvancementProgressStore(plugin);
    }

    public void start() {
        stop();
        if (!isEnabled()) {
            return;
        }
        long intervalSeconds = Math.max(10L, configService.getLong("advancement-events.interval-seconds", 90L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::runTick, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void runTick() {
        attemptTrigger(false);
    }

    public TriggerResult triggerNow() {
        return attemptTrigger(true);
    }

    public AdvancementStatus getStatus() {
        long now = System.currentTimeMillis();
        pruneOld(globalTriggerTimes, now);
        return new AdvancementStatus(
            isEnabled(),
            collectAdvancements().size(),
            deathManager.getAliveGhosts(new ArrayList<>(GhostManager.getOnlineGhosts())).size(),
            progressStore.totalGhostsTracked(),
            progressStore.totalCompletions(),
            globalTriggerTimes.size()
        );
    }

    private TriggerResult attemptTrigger(boolean manual) {
        if (!isEnabled()) {
            return new TriggerResult(false, "disabled", "", "", "");
        }
        if (Bukkit.getOnlinePlayers().size() < Math.max(0, configService.getInt("advancement-events.min-online-real-players", 0))) {
            return new TriggerResult(false, "not-enough-real-players", "", "", "");
        }
        if (!manual) {
            double chance = clampChance(configService.getDouble("advancement-events.trigger-chance", 0.2));
            if (ThreadLocalRandom.current().nextDouble() > chance) {
                return new TriggerResult(false, "chance-skip", "", "", "");
            }
        }

        long now = System.currentTimeMillis();
        if (!passesGlobalLimits(now)) {
            return new TriggerResult(false, "global-limit", "", "", "");
        }

        List<GhostPlayer> candidates = deathManager.getAliveGhosts(new ArrayList<>(GhostManager.getOnlineGhosts()));
        if (candidates.isEmpty()) {
            return new TriggerResult(false, "no-eligible-ghost", "", "", "");
        }
        Collections.shuffle(candidates);

        List<AdvancementChoice> advancements = collectAdvancements();
        if (advancements.isEmpty()) {
            return new TriggerResult(false, "no-advancement", "", "", "");
        }

        int maxCompletionsPerGhost = Math.max(1, configService.getInt("advancement-events.max-completions-per-ghost", 300));
        for (GhostPlayer ghost : candidates) {
            if (!passesGhostLimits(ghost.getUuid(), now)) {
                continue;
            }
            if (progressStore.completedCount(ghost.getUuid()) >= maxCompletionsPerGhost) {
                continue;
            }

            List<AdvancementChoice> available = advancements.stream()
                .filter(choice -> !progressStore.isCompleted(ghost.getUuid(), choice.key()))
                .collect(Collectors.toList());
            if (available.isEmpty()) {
                continue;
            }

            AdvancementChoice selected = available.get(ThreadLocalRandom.current().nextInt(available.size()));
            if (!progressStore.markCompleted(ghost.getUuid(), selected.key())) {
                continue;
            }

            recordTrigger(ghost.getUuid(), now);
            broadcastAdvancement(ghost, selected);
            return new TriggerResult(true, "ok", ghost.getName(), selected.name(), selected.key());
        }
        return new TriggerResult(false, "all-ghosts-exhausted-or-limited", "", "", "");
    }

    private boolean isEnabled() {
        return configService.getBoolean("advancement-events.enabled", false);
    }

    private boolean passesGlobalLimits(long now) {
        long minInterval = Math.max(0L, configService.getLong("advancement-events.global-min-interval-seconds", 60L)) * 1000L;
        if (now - globalLastTriggerAt < minInterval) {
            return false;
        }
        int hourlyLimit = Math.max(1, configService.getInt("advancement-events.global-max-per-hour", 30));
        pruneOld(globalTriggerTimes, now);
        return globalTriggerTimes.size() < hourlyLimit;
    }

    private boolean passesGhostLimits(UUID ghostId, long now) {
        long minInterval = Math.max(0L, configService.getLong("advancement-events.per-ghost-min-interval-seconds", 300L)) * 1000L;
        Long last = ghostLastTriggerAt.get(ghostId);
        if (last != null && now - last < minInterval) {
            return false;
        }
        int hourlyLimit = Math.max(1, configService.getInt("advancement-events.per-ghost-max-per-hour", 3));
        Deque<Long> times = ghostTriggerTimes.computeIfAbsent(ghostId, unused -> new ArrayDeque<>());
        pruneOld(times, now);
        return times.size() < hourlyLimit;
    }

    private void recordTrigger(UUID ghostId, long now) {
        globalLastTriggerAt = now;
        globalTriggerTimes.addLast(now);
        pruneOld(globalTriggerTimes, now);

        ghostLastTriggerAt.put(ghostId, now);
        Deque<Long> times = ghostTriggerTimes.computeIfAbsent(ghostId, unused -> new ArrayDeque<>());
        times.addLast(now);
        pruneOld(times, now);
    }

    private void pruneOld(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() > ONE_HOUR_MS) {
            times.removeFirst();
        }
    }

    private List<AdvancementChoice> collectAdvancements() {
        Set<String> excludedNamespaces = configService.getStringList("advancement-events.exclude-namespaces")
            .stream()
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .map(s -> s.endsWith(":") ? s.substring(0, s.length() - 1) : s)
            .collect(Collectors.toSet());
        List<String> excludedContains = configService.getStringList("advancement-events.exclude-key-contains")
            .stream()
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toList());

        List<AdvancementChoice> list = new ArrayList<>();
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            NamespacedKey key = advancement.getKey();
            String namespace = key.getNamespace().toLowerCase(Locale.ROOT);
            String path = key.getKey().toLowerCase(Locale.ROOT);
            String fullKey = namespace + ":" + path;

            if (excludedNamespaces.contains(namespace)) {
                continue;
            }
            if (path.startsWith("recipes/") || fullKey.contains("recipes/")) {
                continue;
            }
            boolean excluded = false;
            for (String token : excludedContains) {
                if (fullKey.contains(token)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) {
                continue;
            }
            list.add(new AdvancementChoice(fullKey, prettyNameFromPath(path)));
        }
        return list;
    }

    private String prettyNameFromPath(String path) {
        if (path == null || path.isBlank()) {
            return "Unknown Advancement";
        }
        String[] segments = path.split("/");
        String raw = segments.length == 0 ? path : segments[segments.length - 1];
        String[] words = raw.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1));
            }
        }
        return out.isEmpty() ? raw : out.toString();
    }

    private void broadcastAdvancement(GhostPlayer ghost, AdvancementChoice choice) {
        String template = configService.getString(
            "advancement-events.message-format",
            "&6[Advancement] &e{name} has made the advancement &a[{advancement}]"
        );
        String message = template
            .replace("{name}", ghost.getName())
            .replace("{advancement}", choice.name())
            .replace("{key}", choice.key());
        MessageUtils.broadcast(message);
    }

    private double clampChance(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record AdvancementChoice(String key, String name) {
    }
}
