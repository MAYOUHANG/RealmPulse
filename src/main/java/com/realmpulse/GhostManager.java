package com.realmpulse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.plugin.java.JavaPlugin;

public class GhostManager {
    private static final int MAX_NAME_LENGTH = 10;
    private static final int MIN_NAME_LENGTH = 5;

    private static final String[] EN_BASES = {
        "Alex", "Aiden", "Noah", "Ethan", "Mason", "Logan", "Liam", "Ryan",
        "Luna", "Nova", "Aria", "Mia", "Iris", "Nina", "Lily", "Ava",
        "Shadow", "Blaze", "Frost", "Storm", "Flame", "Wolf", "Raven", "Falcon",
        "Pixel", "Craft", "Builder", "Miner", "Hunter", "Ranger", "Knight", "Warden"
    };
    private static final String[] EN_TAGS = {
        "Plays", "MC", "Craft", "PvE", "PvP", "Builds", "Survival", "Sky",
        "Fox", "Wolf", "Bear", "Bird", "Hero", "Nova", "One", "Lite"
    };

    private static final String[] CN_BASES = {
        "XiaoYu", "XiaoChen", "XiaoMing", "XiaoHao", "XiaoKai", "XiaoMo", "XiaoRan",
        "ChenXi", "ZiHan", "MuYu", "YuChen", "LinFeng", "JiaHao", "AnRan", "RuoXi",
        "QingYu", "TianYi", "MingYue", "BaiChen", "AFei", "AQiang", "ALe"
    };
    private static final String[] CN_TAGS = {
        "Ge", "Jie", "Dalao", "MengXin", "WanJia", "LaoLiu", "MC", "Craft",
        "XD", "Lucky", "ZaiXian", "Player"
    };

    private static GhostManager instance;
    private final JavaPlugin plugin;
    private final List<GhostPlayer> ghosts = new CopyOnWriteArrayList<>();

    public GhostManager(JavaPlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public void initializeGhosts(int count) {
        ghosts.clear();
        HashSet<String> usedNames = new HashSet<>();
        Map<String, Integer> baseUsage = new HashMap<>();
        Map<String, Integer> tagUsage = new HashMap<>();
        int attempts = 0;
        int maxAttempts = Math.max(200, count * 30);
        double ratio = plugin.getConfig().getDouble(
            "events.english-ghost-ratio",
            plugin.getConfig().getDouble("events.english-dialogue-participation-ratio", 0.5)
        );
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int englishCount = (int) Math.round(count * ratio);
        
        while (ghosts.size() < count && attempts < maxAttempts) {
            attempts++;
            boolean englishPreferred = ghosts.size() < englishCount;
            NameCandidate candidate = generateCandidateName(attempts, baseUsage, tagUsage, englishPreferred);
            String name = candidate.name();
            
            if (usedNames.add(name)) {
                incrementUsage(baseUsage, candidate.base());
                incrementUsage(tagUsage, candidate.tag());
                GhostPlayer.Language language = englishPreferred ? GhostPlayer.Language.EN : GhostPlayer.Language.ZH;
                ghosts.add(new GhostPlayer(name, plugin, language));
            }
        }
        
        if (ghosts.size() < count) {
            plugin.getLogger().warning("Could only generate " + ghosts.size() + " unique ghost names after " + maxAttempts + " attempts.");
        }
    }

    private NameCandidate generateCandidateName(
        int attempt,
        Map<String, Integer> baseUsage,
        Map<String, Integer> tagUsage,
        boolean englishPreferred
    ) {
        String[] basePool = englishPreferred ? EN_BASES : CN_BASES;
        String[] tagPool = englishPreferred ? EN_TAGS : CN_TAGS;
        String base = pickLeastUsed(basePool, baseUsage);
        String tag = pickLeastUsed(tagPool, tagUsage);
        String num = randomNumericTail();

        int type = ThreadLocalRandom.current().nextInt(6);
        String rawName;
        if (englishPreferred) {
            rawName = switch (type) {
                case 0 -> base + num;
                case 1 -> clipIdPart(base, 4) + clipIdPart(tag, 2) + num;
                case 2 -> tag + num;
                case 3 -> base + clipIdPart(tag, 1) + num;
                case 4 -> "Its" + clipIdPart(base, 3) + num;
                default -> clipIdPart(base, 4) + clipIdPart(tag, 1) + num;
            };
        } else {
            rawName = switch (type) {
                case 0 -> base + num;
                case 1 -> clipIdPart(base, 4) + clipIdPart(tag, 2) + num;
                case 2 -> tag + clipIdPart(base, 3) + num;
                case 3 -> "A" + clipIdPart(base, 4) + num;
                case 4 -> clipIdPart(base, 5) + num;
                default -> clipIdPart(base, 4) + clipIdPart(tag, 1) + num;
            };
        }

        String name = enforceNameLength(rawName, base, tag, attempt);
        if (name.isBlank()) {
            name = "Player" + toBase36(attempt % 46656);
            name = enforceNameLength(name, "Player", "", attempt);
        }
        return new NameCandidate(name, base, tag);
    }

    private String pickLeastUsed(String[] pool, Map<String, Integer> usage) {
        int min = Integer.MAX_VALUE;
        List<String> candidates = new ArrayList<>();
        for (String item : pool) {
            int count = usage.getOrDefault(item, 0);
            if (count < min) {
                min = count;
                candidates.clear();
                candidates.add(item);
            } else if (count == min) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            return pool[ThreadLocalRandom.current().nextInt(pool.length)];
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private void incrementUsage(Map<String, Integer> usage, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        usage.put(key, usage.getOrDefault(key, 0) + 1);
    }

    private String clipIdPart(String raw, int maxLength) {
        if (raw == null || raw.isBlank() || maxLength <= 0) {
            return "";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength);
    }

    private String enforceNameLength(String raw, String base, String tag, int seed) {
        int targetLength = pickTargetLength();
        String cleaned = clipIdPart(raw, targetLength);
        if (cleaned.isBlank()) {
            return "";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "P" + cleaned;
            if (cleaned.length() > targetLength) {
                cleaned = cleaned.substring(0, targetLength);
            }
        }

        String filler = clipIdPart(base + tag + toBase36(seed), MAX_NAME_LENGTH);
        if (filler.isBlank()) {
            filler = "Player" + toBase36(seed);
        }
        int idx = 0;
        while (cleaned.length() < MIN_NAME_LENGTH) {
            cleaned = cleaned + filler.charAt(idx % filler.length());
            idx++;
        }
        if (cleaned.length() > targetLength) {
            cleaned = cleaned.substring(0, targetLength);
        }
        if (cleaned.length() < MIN_NAME_LENGTH) {
            while (cleaned.length() < MIN_NAME_LENGTH) {
                cleaned = cleaned + "x";
            }
        }
        return cleaned;
    }

    private int pickTargetLength() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 30) {
            return 5;
        }
        if (roll < 56) {
            return 6;
        }
        if (roll < 76) {
            return 7;
        }
        if (roll < 90) {
            return 8;
        }
        if (roll < 97) {
            return 9;
        }
        return 10;
    }

    private String randomNumericTail() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.55) {
            return "";
        }
        if (r < 0.75) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(2, 10));
        }
        if (r < 0.95) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(10, 100));
        }
        String[] years = {"98", "99", "00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10"};
        return years[ThreadLocalRandom.current().nextInt(years.length)];
    }

    private String toBase36(int value) {
        return Integer.toString(Math.max(0, value), 36).toUpperCase();
    }

    private record NameCandidate(String name, String base, String tag) {
    }

    public static List<GhostPlayer> getGhosts() {
        return instance == null ? Collections.emptyList() : instance.ghosts;
    }

    public static List<GhostPlayer> getOnlineGhosts() {
        if (instance == null) return Collections.emptyList();
        List<GhostPlayer> online = new ArrayList<>();
        for (GhostPlayer ghost : instance.ghosts) {
            if (ghost.isOnline()) online.add(ghost);
        }
        return online;
    }

    public void clearGhosts() {
        ghosts.clear();
    }

    public int totalCount() {
        return ghosts.size();
    }
}
