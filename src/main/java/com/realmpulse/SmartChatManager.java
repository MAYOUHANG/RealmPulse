package com.realmpulse;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartChatManager {
    private enum LearningBucket {
        QA,
        GENERAL
    }

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern PLAYER_MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{3,16})");
    private static final Pattern NON_CONTENT_PATTERN = Pattern.compile("[^\\p{L}\\p{Nd}\\u4e00-\\u9fff]+");
    private static final Set<String> WEAK_ENGLISH_TOKENS = Set.of(
        "the", "and", "for", "with", "this", "that", "from", "into", "onto", "about",
        "you", "your", "our", "ours", "they", "them", "their", "his", "her",
        "have", "has", "had", "will", "shall", "would", "could", "should",
        "lets", "let", "then", "than", "just", "more", "need", "focus", "same",
        "true", "yeah", "okay", "good", "best", "first"
    );

    private final RealmPulse plugin;
    private final PluginConfigService configService;
    private final DeepSeekService deepSeekService;
    private final DeathManager deathManager;
    private final PhraseLearningStore phraseLearningStore;
    private final PhraseLearningStore qaPhraseLearningStore;
    private final PhraseLearningStore legacyPhraseLearningStore;
    private final RawLearningStore rawLearningStore;
    private final LinkedList<String> recentMessages = new LinkedList<>();
    private final LinkedList<String> recentPlayerNames = new LinkedList<>();
    private final LinkedList<String> pendingLearningRaw = new LinkedList<>();
    private final LinkedList<String> pendingLearningQaRaw = new LinkedList<>();
    private final LinkedList<String> recentGhostDialogue = new LinkedList<>();
    private final Object recentLock = new Object();
    private final Object learningLock = new Object();
    private volatile boolean learningSummaryInFlight = false;
    private volatile boolean qaLearningSummaryInFlight = false;
    private volatile long lastLearningSummaryAt = 0L;
    private volatile long lastQaLearningSummaryAt = 0L;

    public static final class LearningStatus {
        public final int rawCount;
        public final int pendingCount;
        public final int refinedCount;

        public LearningStatus(int rawCount, int pendingCount, int refinedCount) {
            this.rawCount = rawCount;
            this.pendingCount = pendingCount;
            this.refinedCount = refinedCount;
        }
    }

    public SmartChatManager(
        RealmPulse plugin,
        PluginConfigService configService,
        DeepSeekService deepSeekService,
        DeathManager deathManager
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.deepSeekService = deepSeekService;
        this.deathManager = deathManager;
        this.phraseLearningStore = new PhraseLearningStore(plugin, "learned-phrases-chat.yml");
        this.qaPhraseLearningStore = new PhraseLearningStore(plugin, "learned-phrases-qa.yml");
        this.legacyPhraseLearningStore = new PhraseLearningStore(plugin);
        this.rawLearningStore = new RawLearningStore(plugin);
    }

    public void startIdleChat() {
        int intervalSeconds = configService.getInt("core.chat-interval", 15);
        if (intervalSeconds <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::runIdleTick,
            20L,
            intervalSeconds * 20L
        );
    }

    public void startEnglishDialogue() {
        if (!plugin.getConfig().getBoolean("events.english-dialogue-enabled", true)) {
            return;
        }
        long intervalSeconds = Math.max(30L, plugin.getConfig().getLong("events.english-dialogue-interval-seconds", 90L));
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::runEnglishDialogueTick,
            intervalSeconds * 20L,
            intervalSeconds * 20L
        );
    }

    private void runIdleTick() {
        List<GhostPlayer> ghosts = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        if (ghosts.isEmpty()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > 0.4) {
            return;
        }
        GhostPlayer ghost = ghosts.get(ThreadLocalRandom.current().nextInt(ghosts.size()));
        String phrase = maybeAttachPlayerId(ghost, pickIdlePhraseForGhost(ghost), null, false);
        speakWithTyping(ghost, phrase, 0L);
    }

    private void runEnglishDialogueTick() {
        List<GhostPlayer> onlineAlive = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        onlineAlive.removeIf(ghost -> !ghost.isEnglishSpeaker());
        if (onlineAlive.size() < 2) {
            return;
        }

        double chance = plugin.getConfig().getDouble("events.english-dialogue-chance", 0.65);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        Collections.shuffle(onlineAlive);
        double ratio = plugin.getConfig().getDouble("events.english-dialogue-participation-ratio", 0.5);
        int participants = Math.max(2, (int) Math.round(onlineAlive.size() * ratio));
        participants = Math.min(participants, onlineAlive.size());
        List<GhostPlayer> speakers = onlineAlive.subList(0, participants);

        String previous = pickEnglishStarter();
        boolean useAi = plugin.getConfig().getBoolean("events.english-dialogue-use-ai", true)
            && configService.getBoolean("ai.qa.enabled", true);
        double aiChance = plugin.getConfig().getDouble("events.english-dialogue-ai-chance", 0.75);
        if (useAi && ThreadLocalRandom.current().nextDouble() < aiChance) {
            String prompt = buildEnglishDialoguePrompt(previous, speakers.size());
            deepSeekService.askQA(prompt, response -> {
                List<String> aiLines = parseAiEnglishLines(response, speakers.size());
                if (aiLines.isEmpty()) {
                    runEnglishDialogueFallback(speakers, previous);
                    return;
                }
                long gapTicks = 20L + ThreadLocalRandom.current().nextInt(30);
                for (int i = 0; i < speakers.size() && i < aiLines.size(); i++) {
                    speakWithTyping(speakers.get(i), aiLines.get(i), gapTicks);
                    gapTicks += 28L + ThreadLocalRandom.current().nextInt(35);
                }
            });
            return;
        }

        runEnglishDialogueFallback(speakers, previous);
    }

    private void runEnglishDialogueFallback(List<GhostPlayer> speakers, String previous) {
        long gapTicks = 20L + ThreadLocalRandom.current().nextInt(30);
        for (int i = 0; i < speakers.size(); i++) {
            GhostPlayer ghost = speakers.get(i);
            String line = composeEnglishLine(previous, i == 0);
            if (line.isBlank()) {
                continue;
            }
            speakWithTyping(ghost, line, gapTicks);
            previous = line;
            gapTicks += 28L + ThreadLocalRandom.current().nextInt(35);
        }
    }

    private String buildEnglishDialoguePrompt(String previous, int lineCount) {
        int maxLines = Math.max(2, Math.min(4, lineCount));
        String recent = getRecentEnglishDialogue(6);
        return "Generate " + maxLines + " lines of natural Minecraft server English dialogue.\n"
            + "Rules:\n"
            + "- One short line per row\n"
            + "- No bullets, no numbering\n"
            + "- Avoid repeating the same sentence pattern\n"
            + "- Keep it practical (gear, farm, dungeon, route, boss)\n"
            + "- No role labels\n"
            + "Previous topic: " + previous + "\n"
            + "Recent lines:\n" + recent;
    }

    private List<String> parseAiEnglishLines(String response, int maxCount) {
        if (response == null || response.isBlank()) {
            return Collections.emptyList();
        }
        String[] rawLines = response.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String raw : rawLines) {
            String line = raw.replaceFirst("^[-*\\d.\\s]+", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!isEnglishLike(line)) {
                continue;
            }
            lines.add(line);
            if (lines.size() >= maxCount) {
                break;
            }
        }
        return lines;
    }

    private String getRecentEnglishDialogue(int limit) {
        synchronized (recentLock) {
            return recentGhostDialogue.stream()
                .filter(this::isEnglishLike)
                .skip(Math.max(0, recentGhostDialogue.size() - limit))
                .collect(Collectors.joining("\n"));
        }
    }

    public void triggerAI(String question, String playerName, boolean ghostMentioned) {
        deepSeekService.askQA(question, answer -> {
            String finalAnswer = answer == null ? "" : answer.trim();
            if (finalAnswer.isBlank()) {
                return;
            }
            scheduleConversationReply(finalAnswer, playerName, ghostMentioned);
        });
    }

    public void learnFromRealPlayer(String playerName, String message, boolean priorityQuestion) {
        rememberPlayer(playerName);
        if (!plugin.getConfig().getBoolean("learning.enabled", true)) {
            return;
        }
        String raw = sanitizeRawLearningMessage(message);
        if (raw.isEmpty()) {
            return;
        }
        int rawMaxSize = plugin.getConfig().getInt("learning.raw-max-size", 2000);
        rawLearningStore.addRaw(raw, rawMaxSize);
        LearningBucket bucket = priorityQuestion ? LearningBucket.QA : LearningBucket.GENERAL;
        enqueueLearningPhrase(raw, priorityQuestion, bucket);
        maybeSummarizeAndImportLearningBatch(false);
    }

    public void maybeSimulateReplyToRealPlayer(String playerName, boolean ghostMentioned) {
        if (!plugin.getConfig().getBoolean("events.fake-mention-dialogue-enabled", true)) {
            return;
        }
        double chance = plugin.getConfig().getDouble("events.player-mention-reply-chance", 0.02);
        if (ghostMentioned) {
            chance = plugin.getConfig().getDouble("events.player-mention-reply-boost-chance", 0.06);
        }
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        List<GhostPlayer> ghosts = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        if (ghosts.size() < 2) {
            return;
        }
        Collections.shuffle(ghosts);
        GhostPlayer speaker = ghosts.get(0);
        GhostPlayer target = pickAnotherGhost(ghosts, speaker.getName());
        if (target == null) {
            return;
        }

        String content = buildGhostMentionLine(speaker, target);
        if (content.isBlank()) {
            return;
        }
        long leadGapTicks = 30L + ThreadLocalRandom.current().nextInt(60);
        speakWithTyping(speaker, content, leadGapTicks);

        String reply = buildGhostMentionReply(target, speaker, content, ghostMentioned);
        if (reply.isBlank()) {
            return;
        }
        long replyGapTicks = leadGapTicks + 35L + ThreadLocalRandom.current().nextInt(50);
        speakWithTyping(target, reply, replyGapTicks);
    }

    private GhostPlayer pickAnotherGhost(List<GhostPlayer> ghosts, String excludedName) {
        if (ghosts == null || ghosts.isEmpty()) {
            return null;
        }
        List<GhostPlayer> candidates = new ArrayList<>();
        for (GhostPlayer ghost : ghosts) {
            if (excludedName != null && !excludedName.isBlank() && excludedName.equalsIgnoreCase(ghost.getName())) {
                continue;
            }
            candidates.add(ghost);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private String buildGhostMentionLine(GhostPlayer speaker, GhostPlayer target) {
        if (speaker == null || target == null) {
            return "";
        }
        List<String> templates = speaker.isEnglishSpeaker()
            ? plugin.getConfig().getStringList("messages.ghost-mention-templates-en")
            : plugin.getConfig().getStringList("messages.ghost-mention-templates-zh");
        if (templates.isEmpty()) {
            templates = plugin.getConfig().getStringList("messages.ghost-mention-templates");
        }
        if (templates.isEmpty()) {
            templates = speaker.isEnglishSpeaker()
                ? plugin.getConfig().getStringList("messages.player-mention-templates-en")
                : plugin.getConfig().getStringList("messages.player-mention-templates-zh");
        }
        if (templates.isEmpty()) {
            templates = plugin.getConfig().getStringList("messages.player-mention-templates");
        }

        String template = templates.isEmpty()
            ? "@{target} we can run a quick dungeon"
            : templates.get(ThreadLocalRandom.current().nextInt(templates.size()));
        String line = template
            .replace("{speaker}", speaker.getName())
            .replace("{player}", target.getName())
            .replace("{target}", target.getName());
        if (!line.contains("@")) {
            line = "@" + target.getName() + " " + line;
        }
        return line.trim();
    }

    private String buildGhostMentionReply(GhostPlayer responder, GhostPlayer speaker, String seedLine, boolean boosted) {
        if (responder == null) {
            return "";
        }
        boolean english = responder.isEnglishSpeaker();
        String learnedQa = english ? pickEnglishQaLearningPhrase() : pickChineseQaLearningPhrase();
        String topic = extractTopicWord(seedLine);
        if (topic.isBlank()) {
            topic = english ? "route" : "farm";
        }

        List<String> templates = english
            ? plugin.getConfig().getStringList("messages.ghost-mention-reply-templates-en")
            : plugin.getConfig().getStringList("messages.ghost-mention-reply-templates-zh");
        if (templates.isEmpty()) {
            templates = plugin.getConfig().getStringList("messages.ghost-mention-reply-templates");
        }
        if (templates.isEmpty()) {
            templates = plugin.getConfig().getStringList("messages.follow-up-templates");
        }

        String reply = "";
        if (!learnedQa.isBlank() && ThreadLocalRandom.current().nextDouble() < 0.70) {
            reply = learnedQa;
        } else if (!templates.isEmpty()) {
            String template = templates.get(ThreadLocalRandom.current().nextInt(templates.size()));
            reply = template
                .replace("{speaker}", speaker == null ? "" : speaker.getName())
                .replace("{topic}", topic)
                .replace("{target}", responder.getName())
                .replace("{player}", speaker == null ? "" : speaker.getName());
        }

        if (reply.isBlank()) {
            reply = english ? "i can take " + topic : "我来弄" + topic;
        }

        if (speaker != null) {
            double chance = plugin.getConfig().getDouble("events.ghost-mention-back-chance", 0.45);
            if (boosted) {
                chance = Math.min(1.0, chance + 0.10);
            }
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                reply = "@" + speaker.getName() + " " + reply;
            }
        }
        return reply;
    }

    private void enqueueLearningPhrase(String phrase, boolean priority, LearningBucket bucket) {
        LinkedList<String> queue = queueForBucket(bucket);
        synchronized (learningLock) {
            if (queue.contains(phrase)) {
                return;
            }
            if (priority) {
                queue.addFirst(phrase);
            } else {
                queue.addLast(phrase);
            }
            int maxPending = Math.max(50, plugin.getConfig().getInt("learning.pending-max-size", 300));
            while (queue.size() > maxPending) {
                queue.removeFirst();
            }
        }
    }

    public boolean flushLearningNow() {
        return maybeSummarizeAndImportLearningBatch(true);
    }

    private boolean maybeSummarizeAndImportLearningBatch(boolean force) {
        if (force) {
            boolean qaStarted = maybeSummarizeAndImportLearningBatch(true, LearningBucket.QA);
            boolean generalStarted = maybeSummarizeAndImportLearningBatch(true, LearningBucket.GENERAL);
            return qaStarted || generalStarted;
        }
        boolean qaStarted = maybeSummarizeAndImportLearningBatch(false, LearningBucket.QA);
        boolean generalStarted = maybeSummarizeAndImportLearningBatch(false, LearningBucket.GENERAL);
        return qaStarted || generalStarted;
    }

    private boolean maybeSummarizeAndImportLearningBatch(boolean force, LearningBucket bucket) {
        int batchSize = Math.max(5, plugin.getConfig().getInt("learning.summary-batch-size", 20));
        int minFiltered = Math.max(3, plugin.getConfig().getInt("learning.summary-min-filtered-size", 8));
        long cooldownMs = Math.max(5L, plugin.getConfig().getLong("learning.summary-cooldown-seconds", 30L)) * 1000L;
        long now = System.currentTimeMillis();
        if ((!force && now - getLastSummaryAt(bucket) < cooldownMs) || isSummaryInFlight(bucket)) {
            return false;
        }

        List<String> batch = new ArrayList<>();
        LinkedList<String> queue = queueForBucket(bucket);
        synchronized (learningLock) {
            int required = force ? Math.max(1, Math.min(batchSize, queue.size())) : batchSize;
            if (queue.size() < required) {
                return false;
            }
            for (int i = 0; i < required; i++) {
                batch.add(queue.removeFirst());
            }
        }

        List<String> filtered = filterLearningBatch(batch);
        if (filtered.isEmpty()) {
            return false;
        }
        if (!force && filtered.size() < minFiltered) {
            requeueFilteredBatch(filtered, bucket);
            return false;
        }

        setLastSummaryAt(bucket, now);
        boolean useApi = plugin.getConfig().getBoolean("learning.api-summary-enabled", true)
            && configService.getBoolean("ai.summary.enabled", true);

        if (!useApi) {
            importFilteredFallback(filtered, bucket);
            return true;
        }

        setSummaryInFlight(bucket, true);
        String prompt = buildSummaryPrompt(filtered, bucket);
        deepSeekService.askSummary(prompt, summarized -> {
            try {
                List<String> refined = parseSummarizedPhrases(summarized, filtered);
                if (refined.isEmpty()) {
                    importFilteredFallback(filtered, bucket);
                    return;
                }
                for (String phrase : refined) {
                    targetStore(bucket).addPhrase(phrase);
                }
            } finally {
                setSummaryInFlight(bucket, false);
            }
        });
        return true;
    }

    private void importFilteredFallback(List<String> filtered, LearningBucket bucket) {
        for (String phrase : filtered) {
            targetStore(bucket).addPhrase(phrase);
        }
    }

    private String buildSummaryPrompt(List<String> filteredBatch, LearningBucket bucket) {
        StringBuilder sb = new StringBuilder();
        if (bucket == LearningBucket.QA) {
            sb.append("You are refining Minecraft server Q&A dialogue style.\n");
            sb.append("Task: rewrite player lines into concise reply-like phrases used in question threads.\n");
        } else {
            sb.append("You are refining Minecraft server chat style.\n");
            sb.append("Task: rewrite player lines into reusable short in-game phrases.\n");
        }
        sb.append("Output 8-15 lines.\n");
        sb.append("Hard rules:\n");
        sb.append("- One phrase per line, no bullets, no numbering, no explanation.\n");
        sb.append("- Do NOT copy any input line verbatim.\n");
        sb.append("- Rewrite with varied wording and sentence pattern.\n");
        sb.append("- Remove all @player mentions and IDs.\n");
        sb.append("- Keep concise (prefer <=16 chars for zh or <=10 words for en).\n");
        if (bucket == LearningBucket.QA) {
            sb.append("- Prefer answer-like tone (brief advice, route, steps, fix).\n");
        }
        sb.append("Input:\n");
        for (String line : filteredBatch) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    private List<String> filterLearningBatch(List<String> rawBatch) {
        Set<String> dedup = new LinkedHashSet<>();
        for (String raw : rawBatch) {
            String cleaned = sanitizeSummaryInput(raw);
            if (!cleaned.isEmpty()) {
                dedup.add(cleaned);
            }
        }
        return new ArrayList<>(dedup);
    }

    private LinkedList<String> queueForBucket(LearningBucket bucket) {
        return bucket == LearningBucket.QA ? pendingLearningQaRaw : pendingLearningRaw;
    }

    private PhraseLearningStore targetStore(LearningBucket bucket) {
        return bucket == LearningBucket.QA ? qaPhraseLearningStore : phraseLearningStore;
    }

    private boolean isSummaryInFlight(LearningBucket bucket) {
        return bucket == LearningBucket.QA ? qaLearningSummaryInFlight : learningSummaryInFlight;
    }

    private void setSummaryInFlight(LearningBucket bucket, boolean inFlight) {
        if (bucket == LearningBucket.QA) {
            qaLearningSummaryInFlight = inFlight;
        } else {
            learningSummaryInFlight = inFlight;
        }
    }

    private long getLastSummaryAt(LearningBucket bucket) {
        return bucket == LearningBucket.QA ? lastQaLearningSummaryAt : lastLearningSummaryAt;
    }

    private void setLastSummaryAt(LearningBucket bucket, long timestamp) {
        if (bucket == LearningBucket.QA) {
            lastQaLearningSummaryAt = timestamp;
        } else {
            lastLearningSummaryAt = timestamp;
        }
    }

    private void requeueFilteredBatch(List<String> filtered, LearningBucket bucket) {
        LinkedList<String> queue = queueForBucket(bucket);
        synchronized (learningLock) {
            for (String phrase : filtered) {
                if (!queue.contains(phrase)) {
                    queue.addLast(phrase);
                }
            }
            int maxPending = Math.max(50, plugin.getConfig().getInt("learning.pending-max-size", 300));
            while (queue.size() > maxPending) {
                queue.removeFirst();
            }
        }
    }

    private List<String> parseSummarizedPhrases(String summarized, List<String> sourceBatch) {
        if (summarized == null || summarized.isBlank()) {
            return Collections.emptyList();
        }
        double outputDedupThreshold = clamp(
            plugin.getConfig().getDouble("learning.summary-dedup-similarity-threshold", 0.86),
            0.55,
            0.99
        );
        double copyThreshold = clamp(
            plugin.getConfig().getDouble("learning.summary-copy-similarity-threshold", 0.93),
            0.60,
            0.99
        );

        List<String> sourceKeys = new ArrayList<>();
        for (String source : sourceBatch) {
            String sourceKey = normalizeForSimilarity(source);
            if (!sourceKey.isEmpty()) {
                sourceKeys.add(sourceKey);
            }
        }

        String[] lines = summarized.split("\\r?\\n");
        Set<String> exactDedup = new LinkedHashSet<>();
        List<String> similarityDedup = new ArrayList<>();
        for (String raw : lines) {
            String stripped = raw.replaceFirst("^[-*\\d.\\s]+", "");
            stripped = stripAllPlayerMentions(stripped);
            String normalized = sanitizeLearnedPhrase(stripped);
            if (!normalized.isEmpty()) {
                String candidateKey = normalizeForSimilarity(normalized);
                if (candidateKey.isEmpty()) {
                    continue;
                }
                if (isTooSimilarToAny(candidateKey, sourceKeys, copyThreshold)) {
                    continue;
                }
                if (isTooSimilarToAny(candidateKey, similarityDedup, outputDedupThreshold)) {
                    continue;
                }
                if (exactDedup.add(normalized)) {
                    similarityDedup.add(candidateKey);
                }
            }
        }
        return new ArrayList<>(exactDedup);
    }

    private String pickIdlePhraseForGhost(GhostPlayer ghost) {
        boolean english = ghost != null && ghost.isEnglishSpeaker();
        String phrase = pickIdlePhraseByLanguage(english);
        if (!phrase.isBlank()) {
            return phrase;
        }
        return english ? "anyone online" : "anyone here";
    }

    private String pickIdlePhraseByLanguage(boolean english) {
        List<String> pool = new ArrayList<>(plugin.getConfig().getStringList("messages.idle-phrases"));
        pool.addAll(phraseLearningStore.getPhrases());
        pool.addAll(legacyPhraseLearningStore.getPhrases());
        double qaBlendChance = clamp(plugin.getConfig().getDouble("learning.qa-idle-blend-chance", 0.2), 0.0, 1.0);
        if (ThreadLocalRandom.current().nextDouble() < qaBlendChance) {
            pool.addAll(qaPhraseLearningStore.getPhrases());
        }
        if (pool.isEmpty()) {
            return "";
        }
        Collections.shuffle(pool);
        for (String phrase : pool) {
            if (english != isEnglishLike(phrase)) {
                continue;
            }
            if (!isRecentlyUsed(phrase)) {
                return phrase;
            }
        }
        return "";
    }

    private String pickEnglishStarter() {
        String learned = pickEnglishLearningPhrase();
        if (!learned.isBlank()) {
            return learned;
        }
        List<String> seeds = plugin.getConfig().getStringList("messages.english-seed-phrases");
        if (seeds.isEmpty()) {
            return "anyone farming tonight";
        }
        return seeds.get(ThreadLocalRandom.current().nextInt(seeds.size()));
    }

    private String composeEnglishLine(String previous, boolean first) {
        if (first) {
            String starter = pickEnglishStarter();
            return starter.isBlank() ? "we should gear up" : starter;
        }
        String learned = pickEnglishLearningPhrase();
        if (!learned.isBlank() && ThreadLocalRandom.current().nextDouble() < 0.6) {
            return learned;
        }
        String topic = extractTopicWord(previous);
        List<String> templates = plugin.getConfig().getStringList("messages.english-content-templates");
        if (!templates.isEmpty()) {
            String t = templates.get(ThreadLocalRandom.current().nextInt(templates.size()));
            if (topic.isBlank()) topic = pickFallbackEnglishTopic();
            return t.replace("{topic}", topic);
        }
        return topic.isBlank() ? "lets farm before boss" : "we should optimize " + topic;
    }

    private String pickEnglishLearningPhrase() {
        return pickLearningPhraseByLanguage(true, false);
    }

    private String pickChineseLearningPhrase() {
        return pickLearningPhraseByLanguage(false, false);
    }

    private String pickEnglishQaLearningPhrase() {
        return pickLearningPhraseByLanguage(true, true);
    }

    private String pickChineseQaLearningPhrase() {
        return pickLearningPhraseByLanguage(false, true);
    }

    private String pickLearningPhraseByLanguage(boolean english, boolean qaPreferred) {
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        if (qaPreferred) {
            for (String phrase : qaPhraseLearningStore.getPhrases()) {
                if (english == isEnglishLike(phrase)) {
                    pool.add(phrase);
                }
            }
        }
        for (String phrase : phraseLearningStore.getPhrases()) {
            if (english == isEnglishLike(phrase)) {
                pool.add(phrase);
            }
        }
        for (String phrase : legacyPhraseLearningStore.getPhrases()) {
            if (english == isEnglishLike(phrase)) {
                pool.add(phrase);
            }
        }
        if (!qaPreferred) {
            double qaBlendChance = clamp(plugin.getConfig().getDouble("learning.qa-idle-blend-chance", 0.2), 0.0, 1.0);
            if (ThreadLocalRandom.current().nextDouble() < qaBlendChance) {
                for (String phrase : qaPhraseLearningStore.getPhrases()) {
                    if (english == isEnglishLike(phrase)) {
                        pool.add(phrase);
                    }
                }
            }
        }
        synchronized (recentLock) {
            for (String line : recentGhostDialogue) {
                if (english == isEnglishLike(line)) {
                    pool.add(line);
                }
            }
        }
        if (pool.isEmpty()) return "";
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        for (String candidate : shuffled) {
            if (!isRecentlyUsed(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String extractTopicWord(String text) {
        if (text == null || text.isBlank()) return "";
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fff]+");
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            if (isWeakEnglishToken(token)) {
                continue;
            }
            return token;
        }
        return "";
    }

    private boolean isWeakEnglishToken(String token) {
        return WEAK_ENGLISH_TOKENS.contains(token);
    }

    private String pickFallbackEnglishTopic() {
        List<String> topics = plugin.getConfig().getStringList("messages.english-topics");
        if (topics.isEmpty()) {
            topics = List.of("gear", "iron", "farm", "dungeon", "boss", "route");
        }
        return topics.get(ThreadLocalRandom.current().nextInt(topics.size()));
    }

    private boolean isEnglishLike(String text) {
        if (text == null || text.isBlank()) return false;
        int letters = 0;
        int asciiLetters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    asciiLetters++;
                }
            }
        }
        if (letters < 3) return false;
        return ((double) asciiLetters / (double) letters) >= 0.85;
    }

    private boolean isChineseLike(String text) {
        if (text == null || text.isBlank()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    private void scheduleConversationReply(String answer, String playerName, boolean ghostMentioned) {
        List<GhostPlayer> ghosts = new ArrayList<>(deathManager.getAliveGhosts(GhostManager.getOnlineGhosts()));
        if (ghosts.isEmpty()) return;

        boolean expectEnglish = isEnglishLike(answer) && !isChineseLike(answer);
        List<GhostPlayer> sameLanguage = new ArrayList<>();
        for (GhostPlayer ghost : ghosts) {
            if (ghost.isEnglishSpeaker() == expectEnglish) sameLanguage.add(ghost);
        }
        if (!sameLanguage.isEmpty()) ghosts = sameLanguage;

        Collections.shuffle(ghosts);
        double replyChance = plugin.getConfig().getDouble("events.reply-chance", 0.2);
        int responders = (int) Math.max(1, Math.floor(ghosts.size() * replyChance));
        responders = Math.min(responders, ghosts.size());

        GhostPlayer lead = ghosts.get(0);
        String leadMessage = maybeAttachPlayerId(lead, fitMessageToGhostLanguage(lead, answer), playerName, ghostMentioned);
        long leadGap = 20L + ThreadLocalRandom.current().nextInt(40);
        speakWithTyping(lead, leadMessage, leadGap);

        double followupChance = plugin.getConfig().getDouble("events.ghost-followup-chance", 0.2);
        if (ThreadLocalRandom.current().nextDouble() > followupChance || responders <= 1) return;

        responders = Math.min(responders, 2);
        long gap = leadGap + 40L;
        for (int i = 1; i < responders; i++) {
            GhostPlayer follower = ghosts.get(i);
            gap += 30L + ThreadLocalRandom.current().nextInt(40);
            String phrase = maybeAttachPlayerId(follower, buildFollowupLine(answer, follower.isEnglishSpeaker()), playerName, ghostMentioned);
            speakWithTyping(follower, phrase, gap);
        }
    }

    private String buildFollowupLine(String leadAnswer, boolean englishSpeaker) {
        List<String> templates = plugin.getConfig().getStringList("messages.follow-up-templates");
        String topic = extractTopicWord(leadAnswer);
        if (topic.isBlank()) topic = englishSpeaker ? "route" : "farm";
        String qaLearned = englishSpeaker ? pickEnglishQaLearningPhrase() : pickChineseQaLearningPhrase();
        if (!qaLearned.isBlank() && ThreadLocalRandom.current().nextDouble() < 0.65) {
            return qaLearned;
        }
        if (!templates.isEmpty()) {
            String t = templates.get(ThreadLocalRandom.current().nextInt(templates.size()));
            String line = t.replace("{topic}", topic);
            if (englishSpeaker == isEnglishLike(line)) return line;
        }
        String learned = pickIdlePhraseByLanguage(englishSpeaker);
        if (!learned.isBlank()) return learned;
        return englishSpeaker ? "we should prepare " + topic : "先准备" + topic;
    }

    private String fitMessageToGhostLanguage(GhostPlayer ghost, String message) {
        if (ghost == null) return message == null ? "" : message;
        if (ghost.isEnglishSpeaker()) {
            if (isEnglishLike(message) && !isChineseLike(message)) return message;
            String fallback = pickEnglishLearningPhrase();
            return fallback.isBlank() ? "lets do dungeon prep" : fallback;
        }
        if (isChineseLike(message) && !isEnglishLike(message)) return message;
        String fallback = pickChineseLearningPhrase();
        return fallback.isBlank() ? "先发育再打本" : fallback;
    }

    private String maybeAttachPlayerId(GhostPlayer speaker, String phrase, String preferredPlayer, boolean ghostMentioned) {
        if (phrase == null || phrase.isBlank()) return "";
        String player = resolveGhostMentionTarget(preferredPlayer, speaker == null ? "" : speaker.getName());
        if (player == null || player.isBlank()) return phrase;

        double chance = plugin.getConfig().getDouble("messages.append-player-id-chance", 0.01);
        if (ghostMentioned) chance = plugin.getConfig().getDouble("messages.append-player-id-boost-chance", 0.03);
        if (ThreadLocalRandom.current().nextDouble() > chance) return phrase;

        String format = plugin.getConfig().getString("messages.player-id-format", "{message} @{player}");
        return format.replace("{message}", phrase).replace("{player}", player);
    }

    private String resolveGhostMentionTarget(String preferredTarget, String speakerName) {
        if (preferredTarget != null && !preferredTarget.isBlank()) {
            if (isGhostOnline(preferredTarget) && !preferredTarget.equalsIgnoreCase(speakerName)) {
                return preferredTarget;
            }
        }

        List<GhostPlayer> ghosts = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        if (ghosts.isEmpty()) {
            return "";
        }
        List<String> candidates = new ArrayList<>();
        for (GhostPlayer ghost : ghosts) {
            String name = ghost.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (speakerName != null && !speakerName.isBlank() && name.equalsIgnoreCase(speakerName)) {
                continue;
            }
            candidates.add(name);
        }
        if (candidates.isEmpty()) {
            return "";
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private String sanitizeLearnedPhrase(String message) {
        if (message == null) return "";
        String value = WHITESPACE_PATTERN.matcher(message.trim()).replaceAll(" ");
        int minLength = plugin.getConfig().getInt("learning.min-length", 2);
        int maxLength = plugin.getConfig().getInt("learning.max-length", 36);
        if (value.length() < minLength || value.length() > maxLength) return "";
        if (value.startsWith("/")) return "";
        if (!containsMeaningfulChars(value)) return "";

        String lower = value.toLowerCase(Locale.ROOT);
        for (String bad : plugin.getConfig().getStringList("learning.block-contains")) {
            if (!bad.isBlank() && lower.contains(bad.toLowerCase(Locale.ROOT))) return "";
        }
        return value;
    }

    private String sanitizeRawLearningMessage(String message) {
        if (message == null) return "";
        String value = WHITESPACE_PATTERN.matcher(message.trim()).replaceAll(" ");
        if (value.length() > 120) value = value.substring(0, 120).trim();
        return value;
    }

    private String sanitizeSummaryInput(String message) {
        if (message == null) return "";
        String value = WHITESPACE_PATTERN.matcher(message.trim()).replaceAll(" ");
        int minLength = plugin.getConfig().getInt("learning.min-length", 2);
        int maxLength = Math.max(40, plugin.getConfig().getInt("learning.summary-input-max-length", 90));
        if (value.length() < minLength || value.length() > maxLength) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        for (String bad : plugin.getConfig().getStringList("learning.block-contains")) {
            if (!bad.isBlank() && lower.contains(bad.toLowerCase(Locale.ROOT))) return "";
        }
        return value;
    }

    private String sanitizeOutgoingMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String value = WHITESPACE_PATTERN.matcher(message.trim()).replaceAll(" ");
        value = filterOfflineMentions(value);
        value = WHITESPACE_PATTERN.matcher(value.trim()).replaceAll(" ");
        value = value.replaceAll("\\s+([,.!?;:])", "$1");
        if (!containsMeaningfulChars(value)) {
            return "";
        }
        return value;
    }

    private String filterOfflineMentions(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        if (!plugin.getConfig().getBoolean("messages.filter-offline-player-mentions", true)) {
            return message;
        }
        Matcher matcher = PLAYER_MENTION_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String mentioned = matcher.group(1);
            if (isMentionTargetOnline(mentioned)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement("@" + mentioned));
            } else {
                matcher.appendReplacement(buffer, "");
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String stripAllPlayerMentions(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String stripped = PLAYER_MENTION_PATTERN.matcher(message).replaceAll("");
        stripped = WHITESPACE_PATTERN.matcher(stripped.trim()).replaceAll(" ");
        return stripped.replaceAll("\\s+([,.!?;:])", "$1");
    }

    private boolean isRealPlayerOnline(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        Player player = Bukkit.getPlayerExact(playerName);
        return player != null && player.isOnline();
    }

    private boolean isMentionTargetOnline(String name) {
        return isRealPlayerOnline(name) || isGhostOnline(name);
    }

    private boolean isGhostOnline(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (GhostPlayer ghost : GhostManager.getOnlineGhosts()) {
            if (name.equalsIgnoreCase(ghost.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsMeaningfulChars(String text) {
        int lettersOrDigits = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) lettersOrDigits++;
        }
        return lettersOrDigits >= 2;
    }

    private boolean isRecentlyUsed(String message) {
        String key = normalizeMessage(message);
        if (key.isEmpty()) {
            return true;
        }
        String similarityKey = normalizeForSimilarity(key);
        double threshold = clamp(
            plugin.getConfig().getDouble("messages.no-repeat-similarity-threshold", 0.84),
            0.55,
            0.99
        );
        synchronized (recentLock) {
            if (recentMessages.contains(key)) {
                return true;
            }
            if (similarityKey.isEmpty()) {
                return false;
            }
            for (String recent : recentMessages) {
                String recentKey = normalizeForSimilarity(recent);
                if (isSimilar(similarityKey, recentKey, threshold)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void recordUsedMessage(String message) {
        String key = normalizeMessage(message);
        synchronized (recentLock) {
            recentMessages.remove(key);
            recentMessages.addLast(key);
            int maxRecent = Math.max(10, plugin.getConfig().getInt("messages.no-repeat-window", 40));
            while (recentMessages.size() > maxRecent) recentMessages.removeFirst();
        }
    }

    private String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        String normalized = WHITESPACE_PATTERN.matcher(message.trim()).replaceAll(" ");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeForSimilarity(String message) {
        String normalized = normalizeMessage(message);
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = PLAYER_MENTION_PATTERN.matcher(normalized).replaceAll("");
        normalized = NON_CONTENT_PATTERN.matcher(normalized).replaceAll("");
        return normalized;
    }

    private boolean isTooSimilarToAny(String key, List<String> candidates, double threshold) {
        for (String candidate : candidates) {
            if (isSimilar(key, candidate, threshold)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSimilar(String left, String right, double threshold) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        int shorter = Math.min(left.length(), right.length());
        int longer = Math.max(left.length(), right.length());
        if (longer > 0 && ((double) shorter / (double) longer) < 0.45) {
            return false;
        }
        return diceSimilarity(left, right) >= threshold;
    }

    private double diceSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        if (left.length() < 2 || right.length() < 2) {
            return 0.0;
        }
        Map<String, Integer> leftBigrams = new HashMap<>();
        for (int i = 0; i < left.length() - 1; i++) {
            String bigram = left.substring(i, i + 2);
            leftBigrams.merge(bigram, 1, Integer::sum);
        }
        int intersection = 0;
        for (int i = 0; i < right.length() - 1; i++) {
            String bigram = right.substring(i, i + 2);
            Integer count = leftBigrams.get(bigram);
            if (count != null && count > 0) {
                leftBigrams.put(bigram, count - 1);
                intersection++;
            }
        }
        int total = (left.length() - 1) + (right.length() - 1);
        if (total <= 0) {
            return 0.0;
        }
        return (2.0 * intersection) / (double) total;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String dedupeWithVariation(String message) {
        String candidate = sanitizeOutgoingMessage(message);
        if (candidate.isBlank()) {
            return "";
        }
        if (!isRecentlyUsed(candidate)) {
            return candidate;
        }

        boolean english = isEnglishLike(candidate) && !isChineseLike(candidate);
        String fallback = sanitizeOutgoingMessage(pickIdlePhraseByLanguage(english));
        if (!fallback.isBlank() && !isRecentlyUsed(fallback)) {
            return fallback;
        }

        int attempts = Math.max(0, plugin.getConfig().getInt("messages.repeat-rewrite-attempts", 2));
        for (int i = 0; i < attempts; i++) {
            String rewritten = sanitizeOutgoingMessage(rewriteCandidate(candidate, i));
            if (rewritten.isBlank()) {
                continue;
            }
            if (!isRecentlyUsed(rewritten)) {
                return rewritten;
            }
        }
        return "";
    }

    private String rewriteCandidate(String message, int attempt) {
        if (message == null || message.isBlank()) {
            return "";
        }
        int mode = Math.floorMod(attempt, 3);
        if (mode == 0) {
            return rewriteByPunctuation(message, attempt);
        }
        if (mode == 1) {
            return rewriteByTailWord(message, attempt);
        }
        return rewriteByClause(message, attempt);
    }

    private String rewriteByPunctuation(String message, int attempt) {
        String stem = message.replaceAll("[.!?~]+$", "").trim();
        if (stem.isEmpty()) {
            stem = message.trim();
        }
        String[] suffixes = {"!", "...", "~"};
        return stem + suffixes[Math.floorMod(attempt, suffixes.length)];
    }

    private String rewriteByTailWord(String message, int attempt) {
        if (!(isEnglishLike(message) && !isChineseLike(message))) {
            return rewriteByPunctuation(message, attempt + 1);
        }
        String[] tails = {" tbh", " imo", " rn", " fr"};
        String tail = tails[Math.floorMod(attempt, tails.length)];
        if (message.toLowerCase(Locale.ROOT).endsWith(tail.trim())) {
            return rewriteByPunctuation(message, attempt + 1);
        }
        return message + tail;
    }

    private String rewriteByClause(String message, int attempt) {
        String[] parts = message.split("[,.!?;]+");
        if (parts.length < 2) {
            return rewriteByPunctuation(message, attempt + 1);
        }
        String picked = (Math.floorMod(attempt, 2) == 0) ? parts[0].trim() : parts[parts.length - 1].trim();
        if (picked.isEmpty() || picked.equalsIgnoreCase(message.trim())) {
            return rewriteByPunctuation(message, attempt + 1);
        }
        return picked;
    }

    private void rememberPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        synchronized (recentLock) {
            recentPlayerNames.removeIf(name -> name.equalsIgnoreCase(playerName));
            recentPlayerNames.addLast(playerName);
            while (recentPlayerNames.size() > 50) recentPlayerNames.removeFirst();
        }
    }

    private String pickRecentPlayer() {
        synchronized (recentLock) {
            if (recentPlayerNames.isEmpty()) return null;
            return recentPlayerNames.get(ThreadLocalRandom.current().nextInt(recentPlayerNames.size()));
        }
    }

    private void rememberGhostLine(String messageText) {
        synchronized (recentLock) {
            recentGhostDialogue.removeIf(line -> line.equalsIgnoreCase(messageText));
            recentGhostDialogue.addLast(messageText);
            int maxMemory = Math.max(20, plugin.getConfig().getInt("messages.ghost-dialogue-memory-size", 80));
            while (recentGhostDialogue.size() > maxMemory) recentGhostDialogue.removeFirst();
        }
    }

    public LearningStatus getLearningStatus() {
        int pending;
        synchronized (learningLock) {
            pending = pendingLearningRaw.size() + pendingLearningQaRaw.size();
        }
        return new LearningStatus(
            rawLearningStore.size(),
            pending,
            phraseLearningStore.size() + qaPhraseLearningStore.size()
        );
    }

    private void speakWithTyping(GhostPlayer ghost, String messageText, long extraGapTicks) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> speakWithTyping(ghost, messageText, extraGapTicks));
            return;
        }

        String candidate = fitMessageToGhostLanguage(ghost, messageText);
        candidate = sanitizeOutgoingMessage(candidate);
        if (candidate == null || candidate.isBlank()) return;

        String finalMessage = dedupeWithVariation(candidate);
        if (finalMessage.isBlank()) return;

        recordUsedMessage(finalMessage);
        rememberGhostLine(finalMessage);

        long baseMin = plugin.getConfig().getLong("messages.typing-base-min-ticks", 15L);
        long baseMax = Math.max(baseMin, plugin.getConfig().getLong("messages.typing-base-max-ticks", 35L));
        long perChar = Math.max(1L, plugin.getConfig().getLong("messages.typing-per-char-ticks", 2L));
        long typingTicks = baseMin + ThreadLocalRandom.current().nextLong(baseMax - baseMin + 1L) + finalMessage.length() * perChar;
        long totalDelay = Math.max(0L, extraGapTicks) + typingTicks;

        String format = configService.getString("chat.format", "{prefix}{name}: {message}");
        String output = finalMessage;
        Bukkit.getScheduler().runTaskLater(plugin, () -> MessageUtils.broadcast(ghost, format, output), totalDelay);
    }
}


