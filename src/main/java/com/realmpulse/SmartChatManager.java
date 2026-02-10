package com.realmpulse;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartChatManager {
    private enum LearningBucket {
        QA,
        GENERAL
    }

    private enum LanguagePreference {
        EN_ONLY,
        ZH_ONLY,
        MIXED
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
    private final LinkedList<String> recentRealPlayerMessages = new LinkedList<>();
    private final LinkedList<String> pendingLearningRaw = new LinkedList<>();
    private final LinkedList<String> pendingLearningQaRaw = new LinkedList<>();
    private final LinkedList<String> recentGhostDialogue = new LinkedList<>();
    private final Map<String, LinkedList<String>> ghostTopicMemory = new HashMap<>();
    private final Map<String, LinkedList<String>> audienceSeenMessages = new HashMap<>();
    private final Map<String, Long> playerLearningNextAt = new HashMap<>();
    private final Map<String, Long> ghostNextSpeakAt = new HashMap<>();
    private final Map<String, Long> topicNextSpeakAt = new HashMap<>();
    private final LinkedList<Long> recentSendTimeline = new LinkedList<>();
    private final Object recentLock = new Object();
    private final Object learningLock = new Object();
    private final Object learningThrottleLock = new Object();
    private final Object memoryLock = new Object();
    private final Object throttleLock = new Object();
    private volatile long globalNextSpeakAt = 0L;
    private volatile boolean learningSummaryInFlight = false;
    private volatile boolean qaLearningSummaryInFlight = false;
    private volatile long lastLearningSummaryAt = 0L;
    private volatile long lastQaLearningSummaryAt = 0L;
    private volatile int learningSummaryFailureStreak = 0;
    private volatile int qaLearningSummaryFailureStreak = 0;
    private volatile long lastLearningSummaryWarningAt = 0L;
    private volatile long lastQaLearningSummaryWarningAt = 0L;
    private volatile long nextBotMentionDialogueAt = 0L;

    public static final class LearningStatus {
        public final int rawCount;
        public final int pendingCount;
        public final int refinedCount;
        public final int pendingGeneralCount;
        public final int pendingQaCount;
        public final boolean generalSummaryInFlight;
        public final boolean qaSummaryInFlight;
        public final int generalSummaryFailureStreak;
        public final int qaSummaryFailureStreak;

        public LearningStatus(
            int rawCount,
            int pendingCount,
            int refinedCount,
            int pendingGeneralCount,
            int pendingQaCount,
            boolean generalSummaryInFlight,
            boolean qaSummaryInFlight,
            int generalSummaryFailureStreak,
            int qaSummaryFailureStreak
        ) {
            this.rawCount = rawCount;
            this.pendingCount = pendingCount;
            this.refinedCount = refinedCount;
            this.pendingGeneralCount = pendingGeneralCount;
            this.pendingQaCount = pendingQaCount;
            this.generalSummaryInFlight = generalSummaryInFlight;
            this.qaSummaryInFlight = qaSummaryInFlight;
            this.generalSummaryFailureStreak = generalSummaryFailureStreak;
            this.qaSummaryFailureStreak = qaSummaryFailureStreak;
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
        boolean enabled = getCompatBoolean(
            "events.group-dialogue-enabled",
            "events.english-dialogue-enabled",
            true
        );
        if (!enabled) {
            return;
        }
        long intervalSeconds = Math.max(
            30L,
            getCompatLong("events.group-dialogue-interval-seconds", "events.english-dialogue-interval-seconds", 180L)
        );
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::runEnglishDialogueTick,
            intervalSeconds * 20L,
            intervalSeconds * 20L
        );
    }

    public void startLearningSummarizer() {
        long intervalSeconds = Math.max(5L, plugin.getConfig().getLong("learning.auto-summary-interval-seconds", 12L));
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            () -> maybeSummarizeAndImportLearningBatch(false),
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
        String seed = pickIdlePhraseForGhost(ghost);

        boolean strictAiMode = isStrictAiTemplateDisabled();
        boolean useAi = isQaAiAvailable()
            && plugin.getConfig().getBoolean("events.idle-chat-use-ai", true);
        if (strictAiMode) {
            useAi = true;
        }
        double aiChance = clamp(plugin.getConfig().getDouble("events.idle-chat-ai-chance", 0.70), 0.0, 1.0);
        if (strictAiMode) {
            aiChance = 1.0;
        }
        if (useAi && ThreadLocalRandom.current().nextDouble() < aiChance) {
            requestAiLineForGhost(ghost, "idle", seed, aiLine -> {
                String chosen = aiLine == null ? "" : aiLine.trim();
                if (chosen.isBlank()) {
                    if (strictAiMode) {
                        return;
                    }
                    chosen = seed;
                }
                String phrase = maybeAttachPlayerId(ghost, chosen, null, false);
                speakWithTyping(ghost, phrase, 0L);
            });
            return;
        }

        String phrase = maybeAttachPlayerId(ghost, seed, null, false);
        speakWithTyping(ghost, phrase, 0L);
    }

    private void runEnglishDialogueTick() {
        List<GhostPlayer> onlineAlive = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        boolean englishMode = isConfiguredLanguageEnglish();
        onlineAlive.removeIf(ghost -> ghost.isEnglishSpeaker() != englishMode);
        if (onlineAlive.size() < 2) {
            return;
        }

        double chance = clamp(
            getCompatDouble("events.group-dialogue-chance", "events.english-dialogue-chance", englishMode ? 0.16 : 0.24),
            0.0,
            1.0
        );
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        Collections.shuffle(onlineAlive);
        double ratio = clamp(
            getCompatDouble("events.group-dialogue-participation-ratio", "events.english-dialogue-participation-ratio", 0.35),
            0.2,
            1.0
        );
        int participants = Math.max(2, (int) Math.round(onlineAlive.size() * ratio));
        participants = Math.min(participants, onlineAlive.size());
        List<GhostPlayer> speakers = onlineAlive.subList(0, participants);

        String topicSeed = pickDialogueTopicHint(englishMode);
        boolean strictAiMode = isStrictAiTemplateDisabled();
        boolean useAi = isQaAiAvailable()
            && getCompatBoolean("events.group-dialogue-use-ai", "events.english-dialogue-use-ai", true);
        if (strictAiMode) {
            useAi = true;
        }
        double aiChance = clamp(
            getCompatDouble("events.group-dialogue-ai-chance", "events.english-dialogue-ai-chance", 0.85),
            0.0,
            1.0
        );
        if (strictAiMode) {
            aiChance = 1.0;
        }
        if (useAi && ThreadLocalRandom.current().nextDouble() < aiChance) {
            String prompt = buildEnglishDialoguePrompt(topicSeed, speakers, englishMode);
            deepSeekService.askQA(prompt, response -> {
                List<String> aiLines = parseAiEnglishLines(response, speakers.size(), englishMode);
                if (aiLines.size() < 2) {
                    if (strictAiMode) {
                        return;
                    }
                    runEnglishDialogueFallback(speakers, topicSeed, englishMode);
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

        runEnglishDialogueFallback(speakers, topicSeed, englishMode);
    }

    private void runEnglishDialogueFallback(List<GhostPlayer> speakers, String topicSeed, boolean englishMode) {
        long gapTicks = 20L + ThreadLocalRandom.current().nextInt(30);
        String previous = topicSeed == null ? "" : topicSeed;
        Set<String> usedThisDialogue = new LinkedHashSet<>();
        for (int i = 0; i < speakers.size(); i++) {
            String line = composeFallbackDialogueLine(previous, i == 0, englishMode, usedThisDialogue);
            if (line.isBlank()) {
                continue;
            }
            speakWithTyping(speakers.get(i), line, gapTicks);
            previous = line;
            gapTicks += 28L + ThreadLocalRandom.current().nextInt(35);
        }
    }

    private String composeFallbackDialogueLine(String previous, boolean first, boolean englishMode, Set<String> usedThisDialogue) {
        String topic = extractTopicWord(previous);
        if (topic.isBlank()) {
            topic = pickDialogueTopicHint(englishMode);
        }

        List<String> templates = plugin.getConfig().getStringList(
            first
                ? (englishMode ? "messages.non-ai-dialogue.lead-templates-en" : "messages.non-ai-dialogue.lead-templates-zh")
                : (englishMode ? "messages.non-ai-dialogue.reply-templates-en" : "messages.non-ai-dialogue.reply-templates-zh")
        );
        String candidate = pickTemplateLine(templates, englishMode, topic, usedThisDialogue);
        if (!candidate.isBlank()) {
            return candidate;
        }

        if (first) {
            if (englishMode) {
                return composeEnglishLine(previous, true);
            }
            String starter = pickChineseLearningPhrase();
            if (!starter.isBlank()) {
                return starter;
            }
            return "先把" + topic + "补一补";
        }
        if (englishMode) {
            return composeEnglishLine(previous, false);
        }
        String followup = buildFollowupLine(previous, false);
        if (!followup.isBlank()) {
            return followup;
        }
        return "我来补" + topic + "这边";
    }

    private String pickTemplateLine(List<String> templates, boolean englishMode, String topic, Set<String> usedThisDialogue) {
        if (templates == null || templates.isEmpty()) {
            return "";
        }
        List<String> shuffled = new ArrayList<>(templates);
        Collections.shuffle(shuffled);
        for (String template : shuffled) {
            if (template == null || template.isBlank()) {
                continue;
            }
            String line = sanitizeOutgoingMessage(template.replace("{topic}", topic == null ? "" : topic));
            line = trimToAiLength(line, englishMode);
            line = sanitizeOutgoingMessage(line);
            if (line.isBlank()) {
                continue;
            }
            if (!matchesLanguage(line, englishMode)) {
                continue;
            }
            if (containsPlaceholderTopicToken(line)) {
                continue;
            }
            String key = normalizeForSimilarity(line);
            if (key.isEmpty() || usedThisDialogue.contains(key)) {
                continue;
            }
            if (isRecentlyUsed(line) || isSeenByCurrentAudience(line)) {
                continue;
            }
            usedThisDialogue.add(key);
            return line;
        }
        return "";
    }

    private String pickDialogueTopicHint(boolean englishMode) {
        List<String> topics = plugin.getConfig().getStringList(
            englishMode ? "messages.non-ai-dialogue.topics-en" : "messages.non-ai-dialogue.topics-zh"
        );
        if (topics.isEmpty()) {
            if (englishMode) {
                topics = plugin.getConfig().getStringList("messages.english-topics");
                if (topics.isEmpty()) {
                    topics = List.of("gear", "farm", "dungeon", "route", "boss");
                }
            } else {
                topics = plugin.getConfig().getStringList("ai-dialogue.default-topics-zh");
                if (topics.isEmpty()) {
                    topics = List.of("装备", "副本", "路线", "材料", "农场");
                }
            }
        }
        return topics.get(ThreadLocalRandom.current().nextInt(topics.size()));
    }

    private String buildEnglishDialoguePrompt(String topicSeed, List<GhostPlayer> speakers, boolean englishMode) {
        int lineCount = speakers == null ? 2 : speakers.size();
        int maxLines = Math.max(2, Math.min(4, lineCount));
        LanguageClassifier.Result language = englishMode ? LanguageClassifier.Result.EN : LanguageClassifier.Result.ZH;
        String recentGhost = getRecentDialogueByLanguage(language, 8);
        String recentReal = getRecentRealPlayerContext(language, 4);
        String languageRule = englishMode ? "Use English only." : "仅使用简体中文。";
        String lengthRule = englishMode
            ? "- Keep each line <= " + getAiMaxWordsEn() + " words.\n"
            : "- 每行不超过 " + getAiMaxCharsZh() + " 个中文字符。\n";
        StringBuilder style = new StringBuilder();
        for (GhostPlayer speaker : speakers) {
            if (speaker == null || speaker.getName() == null || speaker.getName().isBlank()) {
                continue;
            }
            style.append("- ")
                .append(speaker.getName())
                .append(": ")
                .append(resolveGhostStyleInstruction(speaker, englishMode))
                .append('\n');
        }
        return "Generate exactly " + maxLines + " lines of natural Minecraft server chat dialogue.\n"
            + languageRule + "\n"
            + "Hard rules:\n"
            + "- One line per row, no bullets, no numbering, no role labels.\n"
            + "- Keep conversation ordered: plan -> split work -> confirm.\n"
            + "- Every line must be different in wording and opening phrase.\n"
            + "- Never output placeholders like {topic}.\n"
            + "- Never output the literal word 'topic'.\n"
            + "- No AI/meta wording.\n"
            + lengthRule
            + "Seed subject: " + topicSeed + "\n"
            + "Speaker styles:\n" + style
            + "Recent real-player chat:\n" + recentReal + "\n"
            + "Recent ghost chat to avoid repeating:\n" + recentGhost;
    }

    private List<String> parseAiEnglishLines(String response, int maxCount, boolean englishMode) {
        if (response == null || response.isBlank()) {
            return Collections.emptyList();
        }
        String[] rawLines = response.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        List<String> similarityKeys = new ArrayList<>();
        for (String raw : rawLines) {
            String line = raw.replaceFirst("^[-*\\d.\\s]+", "");
            line = line.replaceFirst("^[A-Za-z][A-Za-z0-9_]{0,15}\\s*[:：-]\\s*", "");
            line = sanitizeOutgoingMessage(line);
            line = trimToAiLength(line, englishMode);
            line = sanitizeOutgoingMessage(line);
            if (line.isEmpty()) {
                continue;
            }
            if (containsPlaceholderTopicToken(line)) {
                continue;
            }
            if (!matchesLanguage(line, englishMode)) {
                continue;
            }
            if (isLowSignalChatLine(line, englishMode)) {
                continue;
            }
            if (isRecentlyUsed(line) || isSeenByCurrentAudience(line)) {
                continue;
            }
            String key = normalizeForSimilarity(line);
            if (!key.isEmpty() && isTooSimilarToAny(key, similarityKeys, 0.82)) {
                continue;
            }
            if (!key.isEmpty()) {
                similarityKeys.add(key);
            }
            lines.add(line);
            if (lines.size() >= maxCount) {
                break;
            }
        }
        return lines;
    }

    private String getRecentDialogueByLanguage(LanguageClassifier.Result language, int limit) {
        synchronized (recentLock) {
            List<String> filtered = recentGhostDialogue.stream()
                .filter(line -> {
                    LanguageClassifier.Result lineLanguage = LanguageClassifier.classify(line);
                    if (language == LanguageClassifier.Result.OTHER) {
                        return lineLanguage != LanguageClassifier.Result.OTHER;
                    }
                    return lineLanguage == language;
                })
                .collect(Collectors.toList());
            int safeLimit = Math.max(1, limit);
            int from = Math.max(0, filtered.size() - safeLimit);
            return filtered.stream().skip(from).collect(Collectors.joining("\n"));
        }
    }

    private String getRecentRealPlayerContext(LanguageClassifier.Result language, int limit) {
        synchronized (recentLock) {
            List<String> filtered = recentRealPlayerMessages.stream()
                .filter(entry -> {
                    String message = extractContextMessage(entry);
                    LanguageClassifier.Result lineLanguage = LanguageClassifier.classify(message);
                    if (language == LanguageClassifier.Result.OTHER) {
                        return lineLanguage != LanguageClassifier.Result.OTHER;
                    }
                    return lineLanguage == language;
                })
                .collect(Collectors.toList());
            int safeLimit = Math.max(1, limit);
            int from = Math.max(0, filtered.size() - safeLimit);
            return filtered.stream().skip(from).collect(Collectors.joining("\n"));
        }
    }

    private String extractContextMessage(String contextLine) {
        if (contextLine == null) {
            return "";
        }
        int split = contextLine.indexOf(": ");
        if (split < 0 || split + 2 >= contextLine.length()) {
            return contextLine;
        }
        return contextLine.substring(split + 2);
    }

    private boolean isAdvancedDialogueMode() {
        String mode = plugin.getConfig().getString("ai-dialogue.mode", "advanced");
        return !"basic".equalsIgnoreCase(mode);
    }

    private int getAiGhostContextLines() {
        return Math.max(2, plugin.getConfig().getInt("ai-dialogue.ghost-context-lines", 8));
    }

    private int getAiRealContextLines() {
        return Math.max(0, plugin.getConfig().getInt("ai-dialogue.real-player-context-lines", 4));
    }

    private int getAiMaxWordsEn() {
        return Math.max(6, plugin.getConfig().getInt("ai-dialogue.max-words-en", 14));
    }

    private int getAiMaxCharsZh() {
        return Math.max(8, plugin.getConfig().getInt("ai-dialogue.max-chars-zh", 22));
    }

    private String resolveGhostStyleInstruction(GhostPlayer ghost, boolean english) {
        List<String> styles = plugin.getConfig().getStringList(english ? "ai-dialogue.styles-en" : "ai-dialogue.styles-zh");
        if (styles.isEmpty()) {
            if (english) {
                styles = List.of(
                    "brief and practical teammate",
                    "calm and route-focused",
                    "natural and collaborative"
                );
            } else {
                styles = List.of(
                    "\u7B80\u77ED\u5B9E\u7528\u961F\u53CB",
                    "\u51B7\u9759\u8DEF\u7EBF\u578B",
                    "\u81EA\u7136\u534F\u4F5C\u578B"
                );
            }
        }
        int seed = ghost == null || ghost.getName() == null ? 0 : ghost.getName().toLowerCase(Locale.ROOT).hashCode();
        int index = Math.floorMod(seed, styles.size());
        return styles.get(index);
    }

    private String pickGhostTopicHint(GhostPlayer ghost, boolean english, String seed) {
        String fromSeed = extractTopicWord(seed);
        if (!fromSeed.isBlank() && matchesLanguage(fromSeed, english)) {
            return fromSeed;
        }

        String ghostKey = ghost == null || ghost.getName() == null
            ? ""
            : ghost.getName().toLowerCase(Locale.ROOT);
        synchronized (memoryLock) {
            LinkedList<String> topics = ghostTopicMemory.get(ghostKey);
            if (topics != null && !topics.isEmpty()) {
                List<String> candidates = new ArrayList<>();
                for (String topic : topics) {
                    if (matchesLanguage(topic, english)) {
                        candidates.add(topic);
                    }
                }
                if (!candidates.isEmpty()) {
                    return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                }
            }
        }

        if (english) {
            return pickFallbackEnglishTopic();
        }
        List<String> zhTopics = plugin.getConfig().getStringList("ai-dialogue.default-topics-zh");
        if (zhTopics.isEmpty()) {
            zhTopics = List.of("\u88c5\u5907", "\u526f\u672c", "\u8def\u7ebf", "\u6750\u6599", "\u519c\u573a");
        }
        return zhTopics.get(ThreadLocalRandom.current().nextInt(zhTopics.size()));
    }

    private void requestAiLineForGhost(GhostPlayer ghost, String scene, String seed, Consumer<String> callback) {
        if (ghost == null) {
            callback.accept("");
            return;
        }
        int maxAttempts = Math.max(1, plugin.getConfig().getInt("ai-dialogue.single-line-max-attempts", 2));
        requestAiLineForGhost(ghost, scene, seed, callback, 1, maxAttempts);
    }

    private void requestAiLineForGhost(
        GhostPlayer ghost,
        String scene,
        String seed,
        Consumer<String> callback,
        int attempt,
        int maxAttempts
    ) {
        String prompt = buildGhostSingleLinePrompt(ghost, scene, seed);
        deepSeekService.askQA(prompt, response -> {
            String parsed = parseAiSingleLine(response, ghost.isEnglishSpeaker());
            if (!parsed.isBlank() || attempt >= maxAttempts) {
                callback.accept(parsed);
                return;
            }
            String retrySeed = (seed == null ? "" : seed) + " #" + (attempt + 1);
            requestAiLineForGhost(ghost, scene, retrySeed, callback, attempt + 1, maxAttempts);
        });
    }

    private String buildGhostSingleLinePrompt(GhostPlayer ghost, String scene, String seed) {
        boolean english = ghost != null && ghost.isEnglishSpeaker();
        LanguageClassifier.Result language = english ? LanguageClassifier.Result.EN : LanguageClassifier.Result.ZH;
        String languageRule = english ? "Reply in English only." : "\u4EC5\u7528\u7B80\u4F53\u4E2D\u6587\u56DE\u590D\u3002";
        String topic = pickGhostTopicHint(ghost, english, seed);
        String recentGhost = getRecentDialogueByLanguage(language, getAiGhostContextLines());
        String recentReal = getRecentRealPlayerContext(language, getAiRealContextLines());
        String sceneTag = (scene == null || scene.isBlank()) ? "chat" : scene;
        if (!isAdvancedDialogueMode()) {
            return "You are an active Minecraft survival player speaking in global chat.\n"
                + languageRule + "\n"
                + "Context scene: " + sceneTag + "\n"
                + "Rules:\n"
                + "- Output exactly one short chat line.\n"
                + "- Do not copy any provided line.\n"
                + "- Keep it natural and practical.\n"
                + "- No bullets, no numbering, no quotes.\n"
                + "Seed topic: " + topic + "\n"
                + "Recent lines:\n" + recentGhost;
        }

        String style = resolveGhostStyleInstruction(ghost, english);
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a human Minecraft survival player in global chat.\n");
        prompt.append(languageRule).append('\n');
        prompt.append("Scene: ").append(sceneTag).append('\n');
        prompt.append("Persona style: ").append(style).append('\n');
        prompt.append("Topic hint: ").append(topic).append('\n');
        prompt.append("Hard rules:\n");
        prompt.append("- Output exactly one line.\n");
        prompt.append("- Keep it natural, short, and human.\n");
        prompt.append("- Never copy any context line directly.\n");
        prompt.append("- No role tags, no bullet list, no quotes, no markdown.\n");
        prompt.append("- Avoid repetitive openings and avoid textbook tone.\n");
        if (english) {
            prompt.append("- Keep it within ").append(getAiMaxWordsEn()).append(" words.\n");
        } else {
            prompt.append("- Keep it within ").append(getAiMaxCharsZh()).append(" Chinese characters.\n");
        }
        if (!recentReal.isBlank()) {
            prompt.append("Recent real-player chat:\n").append(recentReal).append('\n');
        }
        if (!recentGhost.isBlank()) {
            prompt.append("Recent ghost chat:\n").append(recentGhost).append('\n');
        }
        return prompt.toString();
    }

    private String parseAiSingleLine(String response, boolean english) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String[] lines = response.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.replaceFirst("^[-*\\d.\\s]+", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            line = sanitizeOutgoingMessage(line);
            if (line.isEmpty()) {
                continue;
            }
            line = trimToAiLength(line, english);
            line = sanitizeOutgoingMessage(line);
            if (line.isEmpty()) {
                continue;
            }
            if (!matchesLanguage(line, english)) {
                continue;
            }
            if (containsPlaceholderTopicToken(line)) {
                continue;
            }
            if (isLowSignalChatLine(line, english)) {
                continue;
            }
            if (isRecentlyUsed(line) || isSeenByCurrentAudience(line)) {
                continue;
            }
            return line;
        }
        return "";
    }

    public void triggerAI(String question, String playerName, boolean ghostMentioned) {
        boolean strictAiMode = isStrictAiTemplateDisabled();
        String prompt = buildQaReplyPrompt(question, playerName);
        deepSeekService.askQA(prompt, answer -> {
            String finalAnswer = answer == null ? "" : answer.trim();
            if (finalAnswer.isBlank()) {
                if (strictAiMode) {
                    return;
                }
                String fallback = pickQaFallbackForQuestion(question);
                if (!fallback.isBlank()) {
                    scheduleConversationReply(fallback, playerName, ghostMentioned, question);
                }
                return;
            }
            scheduleConversationReply(finalAnswer, playerName, ghostMentioned, question);
        });
    }

    private String pickQaFallbackForQuestion(String question) {
        if (isConfiguredLanguageEnglish()) {
            return pickEnglishQaLearningPhrase();
        }
        return pickChineseQaLearningPhrase();
    }

    private String buildQaReplyPrompt(String question, String playerName) {
        LanguageClassifier.Result language = configuredLanguageResult();
        boolean english = language == LanguageClassifier.Result.EN;
        String languageRule = english ? "Reply in English only." : "\u4EC5\u7528\u7B80\u4F53\u4E2D\u6587\u56DE\u590D\u3002";

        String player = playerName == null ? "" : playerName;
        String recentGhost = getRecentDialogueByLanguage(language, getAiGhostContextLines());
        String recentReal = getRecentRealPlayerContext(language, getAiRealContextLines());

        if (!isAdvancedDialogueMode()) {
            return "You are a normal Minecraft survival player in server chat.\n"
                + languageRule + "\n"
                + "Rules:\n"
                + "- Output exactly one short chat line.\n"
                + "- Do not repeat the player's original sentence.\n"
                + "- Keep it practical and human (route, gear, farm, fix, timing).\n"
                + "- No role tags, no explanation, no markdown.\n"
                + "Player: " + player + "\n"
                + "Question: " + question + "\n"
                + "Recent lines:\n" + recentGhost;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a normal Minecraft survival player replying in global chat.\n");
        prompt.append(languageRule).append('\n');
        prompt.append("Player: ").append(player).append('\n');
        prompt.append("Question: ").append(question).append('\n');
        prompt.append("Hard rules:\n");
        prompt.append("- Output exactly one line.\n");
        prompt.append("- Directly answer or react to the player in a natural tone.\n");
        prompt.append("- Do not copy the question sentence pattern.\n");
        prompt.append("- Avoid AI wording and avoid lecture tone.\n");
        prompt.append("- No role tags, no markdown, no quotes.\n");
        if (english) {
            prompt.append("- Keep it within ").append(getAiMaxWordsEn()).append(" words.\n");
        } else {
            prompt.append("- Keep it within ").append(getAiMaxCharsZh()).append(" Chinese characters.\n");
        }
        if (!recentReal.isBlank()) {
            prompt.append("Recent real-player chat:\n").append(recentReal).append('\n');
        }
        if (!recentGhost.isBlank()) {
            prompt.append("Recent ghost chat:\n").append(recentGhost).append('\n');
        }
        return prompt.toString();
    }

    public void learnFromRealPlayer(String playerName, String message, boolean priorityQuestion) {
        rememberPlayer(playerName);
        rememberRealPlayerMessage(playerName, message);
        if (!plugin.getConfig().getBoolean("learning.enabled", true)) {
            return;
        }
        String raw = sanitizeRawLearningMessage(message);
        if (raw.isEmpty()) {
            return;
        }
        if (!isLearningCandidate(playerName, raw, priorityQuestion)) {
            return;
        }
        int rawMaxSize = plugin.getConfig().getInt("learning.raw-max-size", 2000);
        rawLearningStore.addRaw(raw, rawMaxSize);
        LearningBucket bucket = priorityQuestion ? LearningBucket.QA : LearningBucket.GENERAL;
        enqueueLearningPhrase(raw, priorityQuestion, bucket);
        maybeSummarizeAndImportLearningBatch(false);
    }

    public void maybeSimulateBotMentionDialogue(boolean ghostMentioned) {
        if (!getCompatBoolean("events.bot-mention-dialogue-enabled", "events.fake-mention-dialogue-enabled", true)) {
            return;
        }
        if (ghostMentioned) {
            return;
        }
        long now = System.currentTimeMillis();
        long minIntervalMs = Math.max(
            30L,
            plugin.getConfig().getLong("events.bot-mention-dialogue-min-interval-seconds", 240L)
        ) * 1000L;
        if (now < nextBotMentionDialogueAt) {
            return;
        }

        double chance = getCompatDouble(
            "events.bot-mention-dialogue-chance",
            "events.player-mention-reply-chance",
            0.02
        );
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }

        List<GhostPlayer> ghosts = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        if (ghosts.size() < 2) {
            return;
        }
        Collections.shuffle(ghosts);
        GhostPlayer speaker = ghosts.get(0);
        GhostPlayer target = pickAnotherGhost(ghosts, speaker.getName(), speaker.isEnglishSpeaker());
        if (target == null) {
            return;
        }
        nextBotMentionDialogueAt = now + minIntervalMs;

        String templateLead = buildGhostMentionLine(speaker, target);
        if (templateLead.isBlank()) {
            return;
        }
        boolean strictAiMode = isStrictAiTemplateDisabled();
        boolean useAi = isQaAiAvailable()
            && plugin.getConfig().getBoolean("events.ghost-mention-reply-use-ai", true);
        if (strictAiMode) {
            useAi = true;
        }
        double aiChance = clamp(plugin.getConfig().getDouble("events.ghost-mention-reply-ai-chance", 0.75), 0.0, 1.0);
        if (strictAiMode) {
            aiChance = 1.0;
        }
        final boolean mentionUseAi = useAi;
        final double mentionAiChance = aiChance;

        if (mentionUseAi && ThreadLocalRandom.current().nextDouble() < mentionAiChance) {
            requestAiLineForGhost(speaker, "mention-lead", templateLead, aiLead -> {
                String lead = aiLead == null ? "" : aiLead.trim();
                if (lead.isBlank()) {
                    if (strictAiMode) {
                        return;
                    }
                    lead = templateLead;
                }
                if (!lead.contains("@")) {
                    lead = "@" + target.getName() + " " + lead;
                }
                lead = sanitizeOutgoingMessage(lead);
                if (lead.isBlank()) {
                    if (strictAiMode) {
                        return;
                    }
                    lead = templateLead;
                }
                if (lead.isBlank()) {
                    return;
                }
                long leadGapTicks = 30L + ThreadLocalRandom.current().nextInt(60);
                speakWithTyping(speaker, lead, leadGapTicks, true);
                scheduleGhostMentionReply(target, speaker, lead, ghostMentioned, leadGapTicks, mentionUseAi, mentionAiChance);
            });
            return;
        }

        long leadGapTicks = 30L + ThreadLocalRandom.current().nextInt(60);
        speakWithTyping(speaker, templateLead, leadGapTicks, true);
        scheduleGhostMentionReply(target, speaker, templateLead, ghostMentioned, leadGapTicks, mentionUseAi, mentionAiChance);
    }

    private void scheduleGhostMentionReply(
        GhostPlayer target,
        GhostPlayer speaker,
        String content,
        boolean ghostMentioned,
        long leadGapTicks,
        boolean useAi,
        double aiChance
    ) {
        long replyGapTicks = leadGapTicks + 35L + ThreadLocalRandom.current().nextInt(50);
        if (useAi && ThreadLocalRandom.current().nextDouble() < aiChance) {
            requestAiLineForGhost(target, "mention-reply", content, aiReply -> {
                String reply = aiReply == null ? "" : aiReply.trim();
                if (reply.isBlank()) {
                    if (isStrictAiTemplateDisabled()) {
                        return;
                    }
                    reply = buildGhostMentionReply(target, speaker, content, ghostMentioned);
                } else if (speaker != null && ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("events.ghost-mention-back-chance", 0.45)) {
                    reply = "@" + speaker.getName() + " " + reply;
                }
                if (reply.isBlank()) {
                    return;
                }
                speakWithTyping(target, reply, replyGapTicks, true);
            });
            return;
        }

        String reply = buildGhostMentionReply(target, speaker, content, ghostMentioned);
        if (reply.isBlank()) {
            return;
        }
        speakWithTyping(target, reply, replyGapTicks, true);
    }

    private GhostPlayer pickAnotherGhost(List<GhostPlayer> ghosts, String excludedName, boolean englishSpeaker) {
        if (ghosts == null || ghosts.isEmpty()) {
            return null;
        }
        List<GhostPlayer> candidates = new ArrayList<>();
        for (GhostPlayer ghost : ghosts) {
            if (excludedName != null && !excludedName.isBlank() && excludedName.equalsIgnoreCase(ghost.getName())) {
                continue;
            }
            if (ghost.isEnglishSpeaker() != englishSpeaker) {
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
        boolean english = speaker.isEnglishSpeaker();
        List<String> templates = english
            ? plugin.getConfig().getStringList("messages.ghost-mention-templates-en")
            : plugin.getConfig().getStringList("messages.ghost-mention-templates-zh");
        if (templates.isEmpty()) {
            templates = plugin.getConfig().getStringList("messages.ghost-mention-templates");
        }

        String template = pickLocalizedTemplate(templates, english);
        if (template.isBlank()) {
            template = english
                ? "@{target} we can run a quick dungeon"
                : "@{target} \u6211\u4eec\u53ef\u4ee5\u7a0d\u540e\u4e00\u8d77\u4e0b\u672c";
        }

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
        } else if (!english && !matchesLanguage(topic, false)) {
            topic = "\u8def\u7ebf";
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
            String template = pickLocalizedTemplate(templates, english);
            if (!template.isBlank()) {
                reply = template
                    .replace("{speaker}", speaker == null ? "" : speaker.getName())
                    .replace("{topic}", topic)
                    .replace("{target}", responder.getName())
                    .replace("{player}", speaker == null ? "" : speaker.getName());
            }
        }

        if (reply.isBlank()) {
            reply = english ? "i can take " + topic : "\u6211\u6765\u5904\u7406 " + topic;
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

    private String pickLocalizedTemplate(List<String> templates, boolean english) {
        if (templates == null || templates.isEmpty()) {
            return "";
        }
        List<String> localized = new ArrayList<>();
        for (String template : templates) {
            if (matchesLanguage(template, english)) {
                localized.add(template);
            }
        }
        if (localized.isEmpty()) {
            return "";
        }
        return localized.get(ThreadLocalRandom.current().nextInt(localized.size()));
    }

    private void enqueueLearningPhrase(String phrase, boolean priority, LearningBucket bucket) {
        LinkedList<String> queue = queueForBucket(bucket);
        String candidateKey = normalizeForSimilarity(phrase);
        double dedupThreshold = clamp(
            plugin.getConfig().getDouble("learning.queue-dedup-similarity-threshold", 0.90),
            0.60,
            0.99
        );
        int tailScan = Math.max(20, plugin.getConfig().getInt("learning.queue-dedup-tail-scan", 120));
        synchronized (learningLock) {
            if (queue.contains(phrase)) {
                return;
            }
            if (!candidateKey.isEmpty()) {
                int scanned = 0;
                for (int i = queue.size() - 1; i >= 0 && scanned < tailScan; i--, scanned++) {
                    String existingKey = normalizeForSimilarity(queue.get(i));
                    if (isSimilar(candidateKey, existingKey, dedupThreshold)) {
                        return;
                    }
                }
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
        int backlogBypassThreshold = Math.max(
            batchSize,
            plugin.getConfig().getInt("learning.summary-backlog-bypass-threshold", batchSize * 3)
        );
        LinkedList<String> queue = queueForBucket(bucket);
        int queueSize;
        synchronized (learningLock) {
            queueSize = queue.size();
        }
        boolean bypassCooldown = !force && queueSize >= backlogBypassThreshold;
        if ((!force && !bypassCooldown && now - getLastSummaryAt(bucket) < cooldownMs) || isSummaryInFlight(bucket)) {
            return false;
        }

        List<String> batch = new ArrayList<>();
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
        boolean apiSummaryEnabled = plugin.getConfig().getBoolean("learning.api-summary-enabled", true);
        boolean summaryChannelEnabled = apiSummaryEnabled && configService.getBoolean("ai.summary.enabled", true);
        boolean useApi = summaryChannelEnabled && isSummaryApiConfigured();
        boolean allowRawFallback = plugin.getConfig().getBoolean("learning.allow-raw-fallback-import", false);
        int apiAttempts = Math.max(1, plugin.getConfig().getInt("learning.summary-api-max-attempts", 2));

        if (!useApi) {
            boolean forceFallback = shouldForceFallbackAfterFailure(bucket);
            if (allowRawFallback || forceFallback) {
                importFilteredFallback(filtered, bucket);
                resetSummaryFailureStreak(bucket);
                if (!allowRawFallback) {
                    logSummaryWarning(
                        bucket,
                        "Summary API unavailable, forced fallback import used to avoid queue stall."
                    );
                }
                return true;
            }
            requeueFilteredBatch(filtered, bucket);
            incrementSummaryFailureStreak(bucket);
            if (!summaryChannelEnabled) {
                logSummaryWarning(bucket, "Summary channel is disabled. Pending queue will keep growing.");
            } else {
                logSummaryWarning(
                    bucket,
                    "Summary API not configured or placeholder key detected. Pending queue is requeued."
                );
            }
            return false;
        }

        setSummaryInFlight(bucket, true);
        String prompt = buildSummaryPrompt(filtered, bucket);
        requestSummaryWithRetries(prompt, filtered, bucket, allowRawFallback, 1, apiAttempts);
        return true;
    }

    private void requestSummaryWithRetries(
        String prompt,
        List<String> filtered,
        LearningBucket bucket,
        boolean allowRawFallback,
        int attempt,
        int maxAttempts
    ) {
        deepSeekService.askSummary(prompt, summarized -> {
            List<String> refined = parseSummarizedPhrases(summarized, filtered);
            if (refined.isEmpty() && attempt < maxAttempts) {
                String retryPrompt = buildSummaryRetryPrompt(filtered, bucket, attempt + 1);
                requestSummaryWithRetries(retryPrompt, filtered, bucket, allowRawFallback, attempt + 1, maxAttempts);
                return;
            }
            try {
                if (refined.isEmpty()) {
                    boolean forceFallback = shouldForceFallbackAfterFailure(bucket);
                    if (allowRawFallback || forceFallback) {
                        importFilteredFallback(filtered, bucket);
                        if (!allowRawFallback) {
                            logSummaryWarning(
                                bucket,
                                "Summary API returned empty repeatedly; forced fallback import applied."
                            );
                        }
                    } else {
                        requeueFilteredBatch(filtered, bucket);
                        logSummaryWarning(
                            bucket,
                            "Summary API returned empty output. Batch requeued for later retry."
                        );
                    }
                    if (allowRawFallback || forceFallback) {
                        resetSummaryFailureStreak(bucket);
                    } else {
                        incrementSummaryFailureStreak(bucket);
                    }
                    return;
                }
                for (String phrase : refined) {
                    targetStore(bucket).addPhrase(phrase);
                }
                trimLearningStore(targetStore(bucket));
                resetSummaryFailureStreak(bucket);
            } finally {
                setSummaryInFlight(bucket, false);
            }
        });
    }

    private void importFilteredFallback(List<String> filtered, LearningBucket bucket) {
        for (String phrase : filtered) {
            targetStore(bucket).addPhrase(phrase);
        }
        trimLearningStore(targetStore(bucket));
    }

    private String buildSummaryPrompt(List<String> filteredBatch, LearningBucket bucket) {
        LanguagePreference preference = detectSourceLanguagePreference(filteredBatch);
        int maxLines = Math.max(3, Math.min(12, filteredBatch.size()));
        int minLines = Math.max(3, Math.min(6, maxLines));
        StringBuilder sb = new StringBuilder();
        if (bucket == LearningBucket.QA) {
            sb.append("You are refining Minecraft server Q&A dialogue style.\n");
            sb.append("Task: rewrite player lines into concise reply-like phrases used in question threads.\n");
        } else {
            sb.append("You are refining Minecraft server chat style.\n");
            sb.append("Task: rewrite player lines into reusable short in-game phrases.\n");
        }
        if (preference == LanguagePreference.EN_ONLY) {
            sb.append("Use English only.\n");
        } else if (preference == LanguagePreference.ZH_ONLY) {
            sb.append("Use Simplified Chinese only.\n");
        } else {
            sb.append("Keep each line in the same language style as the input line.\n");
        }
        sb.append("Output ").append(minLines).append("-").append(maxLines).append(" lines.\n");
        sb.append("Hard rules:\n");
        sb.append("- One phrase per line, no bullets, no numbering, no explanation.\n");
        sb.append("- Rewrite ONLY from the provided input lines; do not invent new topics.\n");
        sb.append("- Do NOT copy any input line verbatim.\n");
        sb.append("- Rewrite with clearly different wording and sentence pattern.\n");
        sb.append("- Preserve a natural player-chat tone.\n");
        sb.append("- Keep each line independently useful as a reusable chat phrase.\n");
        sb.append("- Remove all @player mentions and IDs.\n");
        sb.append("- Keep lexical overlap with any input line low (prefer < 50%).\n");
        sb.append("- Do not output meme spam or single-number replies.\n");
        sb.append("- Keep concise (prefer <=16 chars for zh or <=10 words for en).\n");
        if (bucket == LearningBucket.QA) {
            sb.append("- Prefer answer-like tone (brief advice, route, steps, fix).\n");
        }
        if (!isConfiguredLanguageEnglish()) {
            sb.append("- In Chinese mode, avoid pure-English lines.\n");
        }
        sb.append("Input:\n");
        for (String line : filteredBatch) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    private String buildSummaryRetryPrompt(List<String> filteredBatch, LearningBucket bucket, int attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildSummaryPrompt(filteredBatch, bucket));
        sb.append('\n');
        sb.append("Previous attempt #").append(Math.max(1, attempt - 1))
            .append(" was unusable due to copy/mixing/repetition.\n");
        sb.append("Retry instructions:\n");
        sb.append("- Use fresh sentence structure.\n");
        sb.append("- Keep variety in openings.\n");
        sb.append("- Do not output placeholders like {topic}.\n");
        return sb.toString();
    }

    private List<String> filterLearningBatch(List<String> rawBatch) {
        Set<String> dedup = new LinkedHashSet<>();
        List<String> similarityKeys = new ArrayList<>();
        double inputDedupThreshold = clamp(
            plugin.getConfig().getDouble("learning.summary-input-dedup-similarity-threshold", 0.90),
            0.60,
            0.99
        );
        for (String raw : rawBatch) {
            String cleaned = sanitizeSummaryInput(raw);
            if (cleaned.isEmpty()) {
                continue;
            }
            LanguageClassifier.Result language = LanguageClassifier.classify(cleaned);
            boolean english = language == LanguageClassifier.Result.EN;
            if (isLowSignalChatLine(cleaned, english)) {
                continue;
            }
            String key = normalizeForSimilarity(cleaned);
            if (key.isEmpty()) {
                continue;
            }
            if (isTooSimilarToAny(key, similarityKeys, inputDedupThreshold)) {
                continue;
            }
            similarityKeys.add(key);
            dedup.add(cleaned);
        }
        return new ArrayList<>(dedup);
    }

    private LanguagePreference detectSourceLanguagePreference(List<String> lines) {
        return detectSourceLanguagePreference(lines, 0.70);
    }

    private LanguagePreference detectSourceLanguagePreference(List<String> lines, double threshold) {
        if (lines == null || lines.isEmpty()) {
            return LanguagePreference.MIXED;
        }
        int enCount = 0;
        int zhCount = 0;
        for (String line : lines) {
            LanguageClassifier.Result language = LanguageClassifier.classify(line);
            if (language == LanguageClassifier.Result.EN) {
                enCount++;
            } else if (language == LanguageClassifier.Result.ZH) {
                zhCount++;
            }
        }
        int total = enCount + zhCount;
        if (total <= 0) {
            return LanguagePreference.MIXED;
        }
        double enRatio = (double) enCount / (double) total;
        double zhRatio = (double) zhCount / (double) total;
        if (enRatio >= threshold) {
            return LanguagePreference.EN_ONLY;
        }
        if (zhRatio >= threshold) {
            return LanguagePreference.ZH_ONLY;
        }
        return LanguagePreference.MIXED;
    }

    private boolean isLearningCandidate(String playerName, String raw, boolean priorityQuestion) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (raw.startsWith("/")) {
            return false;
        }
        LanguageClassifier.Result language = LanguageClassifier.classify(raw);
        if (language == LanguageClassifier.Result.EN && plugin.getConfig().getBoolean("learning.filter-short-english", true)) {
            int minWords = Math.max(2, plugin.getConfig().getInt("learning.min-english-words", 3));
            int words = WHITESPACE_PATTERN.split(raw.trim()).length;
            if (words < minWords) {
                return false;
            }
        }
        boolean english = language == LanguageClassifier.Result.EN;
        if (isLowSignalChatLine(raw, english)) {
            return false;
        }
        if (looksLikeNoise(raw)) {
            return false;
        }
        if (!passPlayerLearningThrottle(playerName, priorityQuestion)) {
            return false;
        }
        return true;
    }

    private boolean looksLikeNoise(String text) {
        int repeatedLimit = Math.max(4, plugin.getConfig().getInt("learning.max-repeated-char-run", 5));
        if (repeatedLimit <= 1) {
            return false;
        }
        int run = 1;
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == text.charAt(i - 1)) {
                run++;
                if (run >= repeatedLimit) {
                    return true;
                }
            } else {
                run = 1;
            }
        }
        return false;
    }

    private boolean passPlayerLearningThrottle(String playerName, boolean priorityQuestion) {
        String key = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            return true;
        }
        long cooldownSeconds = Math.max(0L, plugin.getConfig().getLong("learning.per-player-cooldown-seconds", 6L));
        if (priorityQuestion) {
            cooldownSeconds = Math.max(0L, Math.round(cooldownSeconds * 0.5));
        }
        if (cooldownSeconds <= 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (learningThrottleLock) {
            long nextAt = playerLearningNextAt.getOrDefault(key, 0L);
            if (now < nextAt) {
                return false;
            }
            playerLearningNextAt.put(key, now + cooldownSeconds * 1000L);
            int maxTrackedPlayers = Math.max(100, plugin.getConfig().getInt("learning.throttle-memory-size", 600));
            if (playerLearningNextAt.size() > maxTrackedPlayers) {
                playerLearningNextAt.entrySet().removeIf(entry -> now > entry.getValue() + 60_000L);
            }
            return true;
        }
    }

    private void trimLearningStore(PhraseLearningStore store) {
        if (store == null) {
            return;
        }
        int maxRefined = Math.max(200, plugin.getConfig().getInt("learning.max-refined-size", 1200));
        store.trimToMaxSize(maxRefined);
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

    private int getSummaryFailureStreak(LearningBucket bucket) {
        return bucket == LearningBucket.QA ? qaLearningSummaryFailureStreak : learningSummaryFailureStreak;
    }

    private void incrementSummaryFailureStreak(LearningBucket bucket) {
        if (bucket == LearningBucket.QA) {
            qaLearningSummaryFailureStreak++;
        } else {
            learningSummaryFailureStreak++;
        }
    }

    private void resetSummaryFailureStreak(LearningBucket bucket) {
        if (bucket == LearningBucket.QA) {
            qaLearningSummaryFailureStreak = 0;
        } else {
            learningSummaryFailureStreak = 0;
        }
    }

    private boolean shouldForceFallbackAfterFailure(LearningBucket bucket) {
        int threshold = Math.max(0, plugin.getConfig().getInt("learning.summary-force-fallback-after-failures", 5));
        if (threshold <= 0) {
            return false;
        }
        int streak = getSummaryFailureStreak(bucket) + 1;
        return streak >= threshold;
    }

    private void logSummaryWarning(LearningBucket bucket, String message) {
        long warnIntervalMs = Math.max(
            30L,
            plugin.getConfig().getLong("learning.summary-warning-interval-seconds", 120L)
        ) * 1000L;
        long now = System.currentTimeMillis();
        long lastWarn = bucket == LearningBucket.QA ? lastQaLearningSummaryWarningAt : lastLearningSummaryWarningAt;
        if (now - lastWarn < warnIntervalMs) {
            return;
        }
        if (bucket == LearningBucket.QA) {
            lastQaLearningSummaryWarningAt = now;
        } else {
            lastLearningSummaryWarningAt = now;
        }
        String bucketName = bucket == LearningBucket.QA ? "QA" : "GENERAL";
        plugin.getLogger().warning("[Learning:" + bucketName + "] " + message);
    }

    private boolean isSummaryApiConfigured() {
        String apiUrl = configService.getString("ai.summary.api-url", "");
        String model = configService.getString("ai.summary.model", "");
        if (apiUrl == null || apiUrl.isBlank() || model == null || model.isBlank()) {
            return false;
        }
        String apiKey = configService.getString("ai.summary.api-key", "");
        return !looksLikePlaceholderApiKey(apiKey);
    }

    private boolean looksLikePlaceholderApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String normalized = apiKey.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("your_api_key_here")
            || normalized.equals("your-api-key-here")
            || normalized.contains("replace_with_your_api_key")
            || normalized.contains("replace-your-api-key")
            || normalized.contains("your api key");
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
        boolean strictNoCopy = plugin.getConfig().getBoolean("learning.strict-no-copy", true);
        boolean enforceConfiguredLanguage = plugin.getConfig().getBoolean("learning.enforce-configured-chat-language", true);
        LanguageClassifier.Result configuredLanguage = configuredLanguageResult();
        boolean keepDominantLanguage = plugin.getConfig().getBoolean("learning.summary-keep-dominant-language", true);
        double dominantThreshold = clamp(
            plugin.getConfig().getDouble("learning.summary-dominant-language-threshold", 0.70),
            0.50,
            0.95
        );
        LanguagePreference sourcePreference = detectSourceLanguagePreference(sourceBatch, dominantThreshold);
        double outputDedupThreshold = clamp(
            plugin.getConfig().getDouble("learning.summary-dedup-similarity-threshold", 0.86),
            0.55,
            0.99
        );
        double copyDefault = strictNoCopy ? 0.88 : 0.93;
        double copyThreshold = clamp(
            plugin.getConfig().getDouble("learning.summary-copy-similarity-threshold", copyDefault),
            0.60,
            0.99
        );
        if (strictNoCopy) {
            copyThreshold = Math.min(copyThreshold, 0.90);
        }

        List<String> sourceKeys = new ArrayList<>();
        List<String> existingKeys = new ArrayList<>();
        for (String source : sourceBatch) {
            String sourceKey = normalizeForSimilarity(source);
            if (!sourceKey.isEmpty()) {
                sourceKeys.add(sourceKey);
            }
        }
        for (String phrase : phraseLearningStore.getPhrases()) {
            String key = normalizeForSimilarity(phrase);
            if (!key.isEmpty()) {
                existingKeys.add(key);
            }
        }
        for (String phrase : qaPhraseLearningStore.getPhrases()) {
            String key = normalizeForSimilarity(phrase);
            if (!key.isEmpty()) {
                existingKeys.add(key);
            }
        }
        for (String phrase : legacyPhraseLearningStore.getPhrases()) {
            String key = normalizeForSimilarity(phrase);
            if (!key.isEmpty()) {
                existingKeys.add(key);
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
                LanguageClassifier.Result lineLanguage = LanguageClassifier.classify(normalized);
                boolean english = lineLanguage == LanguageClassifier.Result.EN;
                if (isLowSignalChatLine(normalized, english)) {
                    continue;
                }
                if (enforceConfiguredLanguage && configuredLanguage != LanguageClassifier.Result.OTHER) {
                    if (lineLanguage != configuredLanguage) {
                        continue;
                    }
                }
                if (keepDominantLanguage && sourcePreference != LanguagePreference.MIXED) {
                    if (sourcePreference == LanguagePreference.EN_ONLY && lineLanguage != LanguageClassifier.Result.EN) {
                        continue;
                    }
                    if (sourcePreference == LanguagePreference.ZH_ONLY && lineLanguage != LanguageClassifier.Result.ZH) {
                        continue;
                    }
                }
                String candidateKey = normalizeForSimilarity(normalized);
                if (candidateKey.isEmpty()) {
                    continue;
                }
                if (isTooSimilarToAny(candidateKey, sourceKeys, copyThreshold)) {
                    continue;
                }
                if (isTooSimilarToAny(candidateKey, existingKeys, outputDedupThreshold)) {
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
        return english ? "anyone online" : "\u6709\u4eba\u5728\u7ebf\u5417";
    }

    private String pickIdlePhraseByLanguage(boolean english) {
        List<String> localized = plugin.getConfig().getStringList(english ? "messages.idle-phrases-en" : "messages.idle-phrases-zh");
        List<String> pool = new ArrayList<>(localized.isEmpty() ? plugin.getConfig().getStringList("messages.idle-phrases") : localized);
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
            if (!matchesLanguage(phrase, english)) {
                continue;
            }
            if (isLowSignalChatLine(phrase, english)) {
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
                if (matchesLanguage(phrase, english)) {
                    pool.add(phrase);
                }
            }
        }
        for (String phrase : phraseLearningStore.getPhrases()) {
            if (matchesLanguage(phrase, english)) {
                pool.add(phrase);
            }
        }
        for (String phrase : legacyPhraseLearningStore.getPhrases()) {
            if (matchesLanguage(phrase, english)) {
                pool.add(phrase);
            }
        }
        if (!qaPreferred) {
            double qaBlendChance = clamp(plugin.getConfig().getDouble("learning.qa-idle-blend-chance", 0.2), 0.0, 1.0);
            if (ThreadLocalRandom.current().nextDouble() < qaBlendChance) {
                for (String phrase : qaPhraseLearningStore.getPhrases()) {
                    if (matchesLanguage(phrase, english)) {
                        pool.add(phrase);
                    }
                }
            }
        }
        synchronized (recentLock) {
            for (String line : recentGhostDialogue) {
                if (matchesLanguage(line, english)) {
                    pool.add(line);
                }
            }
        }
        if (pool.isEmpty()) return "";
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        for (String candidate : shuffled) {
            if (isLowSignalChatLine(candidate, english)) {
                continue;
            }
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

    private boolean containsPlaceholderTopicToken(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("{topic}")) {
            return true;
        }
        return lower.matches(".*\\btopic\\b.*");
    }

    private boolean isEnglishLike(String text) {
        return LanguageClassifier.isEnglish(text);
    }

    private boolean isChineseLike(String text) {
        return LanguageClassifier.isChinese(text);
    }

    private boolean matchesLanguage(String text, boolean english) {
        return LanguageClassifier.matches(text, english);
    }

    private boolean getCompatBoolean(String primaryPath, String legacyPath, boolean def) {
        if (plugin.getConfig().contains(primaryPath, true)) {
            return plugin.getConfig().getBoolean(primaryPath, def);
        }
        return plugin.getConfig().getBoolean(legacyPath, def);
    }

    private long getCompatLong(String primaryPath, String legacyPath, long def) {
        if (plugin.getConfig().contains(primaryPath, true)) {
            return plugin.getConfig().getLong(primaryPath, def);
        }
        return plugin.getConfig().getLong(legacyPath, def);
    }

    private double getCompatDouble(String primaryPath, String legacyPath, double def) {
        if (plugin.getConfig().contains(primaryPath, true)) {
            return plugin.getConfig().getDouble(primaryPath, def);
        }
        return plugin.getConfig().getDouble(legacyPath, def);
    }

    private boolean isConfiguredLanguageEnglish() {
        String configured = plugin.getConfig().getString("chat.language", "zh");
        if (configured == null) {
            return false;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        return "en".equals(normalized) || "english".equals(normalized);
    }

    private LanguageClassifier.Result configuredLanguageResult() {
        return isConfiguredLanguageEnglish() ? LanguageClassifier.Result.EN : LanguageClassifier.Result.ZH;
    }

    private boolean isQaAiAvailable() {
        if (!configService.getBoolean("ai.enabled", true) || !configService.getBoolean("ai.qa.enabled", true)) {
            return false;
        }
        String apiUrl = configService.getString("ai.qa.api-url", "");
        String model = configService.getString("ai.qa.model", "");
        return apiUrl != null && !apiUrl.isBlank() && model != null && !model.isBlank();
    }

    private boolean isStrictAiTemplateDisabled() {
        return plugin.getConfig().getBoolean("events.ai-strict-no-template-fallback", true)
            && isQaAiAvailable();
    }

    private void scheduleConversationReply(String answer, String playerName, boolean ghostMentioned, String sourceQuestion) {
        List<GhostPlayer> ghosts = new ArrayList<>(deathManager.getAliveGhosts(GhostManager.getOnlineGhosts()));
        if (ghosts.isEmpty()) return;
        boolean strictAiMode = isStrictAiTemplateDisabled();

        GhostPlayer forcedResponder = null;
        if (ghostMentioned) {
            forcedResponder = findMentionedGhostTarget(sourceQuestion);
            if (forcedResponder == null) {
                return;
            }
            ghosts = new ArrayList<>(List.of(forcedResponder));
        } else {
            LanguageClassifier.Result answerLanguage = LanguageClassifier.classify(answer);
            List<GhostPlayer> sameLanguage = new ArrayList<>();
            if (answerLanguage != LanguageClassifier.Result.OTHER) {
                boolean expectEnglish = answerLanguage == LanguageClassifier.Result.EN;
                for (GhostPlayer ghost : ghosts) {
                    if (ghost.isEnglishSpeaker() == expectEnglish) sameLanguage.add(ghost);
                }
            }
            if (!sameLanguage.isEmpty()) ghosts = sameLanguage;
        }

        Collections.shuffle(ghosts);
        double replyChance = plugin.getConfig().getDouble("events.reply-chance", 0.2);
        int responders = (int) Math.max(1, Math.floor(ghosts.size() * replyChance));
        responders = Math.min(responders, ghosts.size());

        GhostPlayer lead = ghosts.get(0);
        String leadSeed = strictAiMode ? sanitizeOutgoingMessage(answer) : fitMessageToGhostLanguage(lead, answer);
        String leadMessage = maybeAttachPlayerId(lead, leadSeed, playerName, ghostMentioned);
        if (leadMessage.isBlank()) {
            return;
        }
        long leadMinDelay = Math.max(20L, plugin.getConfig().getLong("events.qa-lead-delay-min-ticks", 120L));
        long leadMaxDelay = Math.max(leadMinDelay, plugin.getConfig().getLong("events.qa-lead-delay-max-ticks", 240L));
        long leadGap = leadMinDelay + ThreadLocalRandom.current().nextLong(leadMaxDelay - leadMinDelay + 1L);
        speakWithTyping(lead, leadMessage, leadGap, true);

        if (forcedResponder != null || strictAiMode) {
            return;
        }
        double followupChance = plugin.getConfig().getDouble("events.ghost-followup-chance", 0.2);
        if (ThreadLocalRandom.current().nextDouble() > followupChance || responders <= 1) return;

        boolean useAiFollowup = plugin.getConfig().getBoolean("events.reply-followup-use-ai", true)
            && configService.getBoolean("ai.qa.enabled", true);
        double followupAiChance = clamp(plugin.getConfig().getDouble("events.reply-followup-ai-chance", 0.65), 0.0, 1.0);

        responders = Math.min(responders, 2);
        long followStepMin = Math.max(30L, plugin.getConfig().getLong("events.qa-follow-delay-step-min-ticks", 90L));
        long followStepMax = Math.max(followStepMin, plugin.getConfig().getLong("events.qa-follow-delay-step-max-ticks", 170L));
        long gap = leadGap + followStepMin;
        for (int i = 1; i < responders; i++) {
            GhostPlayer follower = ghosts.get(i);
            gap += followStepMin + ThreadLocalRandom.current().nextLong(followStepMax - followStepMin + 1L);
            long followGap = gap;
            String fallbackLine = buildFollowupLine(answer, follower.isEnglishSpeaker());
            if (useAiFollowup && ThreadLocalRandom.current().nextDouble() < followupAiChance) {
                String aiSeed = fallbackLine.isBlank() ? answer : fallbackLine;
                requestAiLineForGhost(follower, "followup-reply", aiSeed, aiReply -> {
                    String selected = aiReply == null ? "" : aiReply.trim();
                    if (selected.isBlank()) {
                        return;
                    }
                    String phrase = maybeAttachPlayerId(follower, selected, playerName, ghostMentioned);
                    if (phrase.isBlank()) {
                        return;
                    }
                    if (!phrase.isBlank()) {
                        speakWithTyping(follower, phrase, followGap, false);
                    }
                });
                continue;
            }
            String phrase = maybeAttachPlayerId(follower, fallbackLine, playerName, ghostMentioned);
            speakWithTyping(follower, phrase, gap, false);
        }
    }

    private String buildFollowupLine(String leadAnswer, boolean englishSpeaker) {
        List<String> templates = plugin.getConfig().getStringList("messages.follow-up-templates");
        String topic = extractTopicWord(leadAnswer);
        if (topic.isBlank()) topic = englishSpeaker ? "route" : "farm";
        if (!englishSpeaker && !matchesLanguage(topic, false)) topic = "\u8def\u7ebf";
        String qaLearned = englishSpeaker ? pickEnglishQaLearningPhrase() : pickChineseQaLearningPhrase();
        if (!qaLearned.isBlank() && ThreadLocalRandom.current().nextDouble() < 0.65) {
            return qaLearned;
        }
        if (!templates.isEmpty()) {
            String template = pickLocalizedTemplate(templates, englishSpeaker);
            if (!template.isBlank()) {
                String line = template.replace("{topic}", topic);
                if (matchesLanguage(line, englishSpeaker)) return line;
            }
        }
        String learned = pickIdlePhraseByLanguage(englishSpeaker);
        if (!learned.isBlank()) return learned;
        return englishSpeaker ? "we should prepare " + topic : "\u5148\u51c6\u5907 " + topic;
    }

    private String fitMessageToGhostLanguage(GhostPlayer ghost, String message) {
        if (ghost == null) return message == null ? "" : message;
        if (ghost.isEnglishSpeaker()) {
            if (matchesLanguage(message, true)) return message;
            String fallback = pickEnglishLearningPhrase();
            return fallback.isBlank() ? "lets do dungeon prep" : fallback;
        }
        if (matchesLanguage(message, false)) return message;
        String fallback = pickChineseLearningPhrase();
        return fallback.isBlank() ? "\u5148\u5907\u6218\u518d\u4e0b\u672c" : fallback;
    }

    private String maybeAttachPlayerId(GhostPlayer speaker, String phrase, String preferredPlayer, boolean ghostMentioned) {
        if (phrase == null || phrase.isBlank()) return "";
        String player = resolveGhostMentionTarget(preferredPlayer, speaker == null ? "" : speaker.getName());
        if (player == null || player.isBlank()) return phrase;

        double chance = getCompatDouble(
            "messages.append-ghost-id-chance",
            "messages.append-player-id-chance",
            0.01
        );
        if (ghostMentioned) {
            chance = getCompatDouble(
                "messages.append-ghost-id-boost-chance",
                "messages.append-player-id-boost-chance",
                0.03
            );
        }
        if (ThreadLocalRandom.current().nextDouble() > chance) return phrase;

        String format = plugin.getConfig().contains("messages.ghost-id-format", true)
            ? plugin.getConfig().getString("messages.ghost-id-format", "{message} @{player}")
            : plugin.getConfig().getString("messages.player-id-format", "{message} @{player}");
        return format.replace("{message}", phrase).replace("{player}", player);
    }

    private String resolveGhostMentionTarget(String preferredTarget, String speakerName) {
        if (preferredTarget != null && !preferredTarget.isBlank()) {
            if (isGhostOnline(preferredTarget) && !preferredTarget.equalsIgnoreCase(speakerName)) {
                return preferredTarget;
            }
            return "";
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

    private GhostPlayer findMentionedGhostTarget(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        List<GhostPlayer> ghosts = deathManager.getAliveGhosts(GhostManager.getOnlineGhosts());
        if (ghosts.isEmpty()) {
            return null;
        }

        Matcher matcher = PLAYER_MENTION_PATTERN.matcher(message);
        while (matcher.find()) {
            GhostPlayer byAtMention = findGhostByName(ghosts, matcher.group(1));
            if (byAtMention != null) {
                return byAtMention;
            }
        }

        String lower = message.toLowerCase(Locale.ROOT);
        GhostPlayer matched = null;
        int bestIndex = Integer.MAX_VALUE;
        for (GhostPlayer ghost : ghosts) {
            String name = ghost.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            int index = indexOfWholeGhostName(lower, name.toLowerCase(Locale.ROOT));
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
                matched = ghost;
            }
        }
        return matched;
    }

    private GhostPlayer findGhostByName(List<GhostPlayer> ghosts, String candidateName) {
        if (ghosts == null || ghosts.isEmpty() || candidateName == null || candidateName.isBlank()) {
            return null;
        }
        for (GhostPlayer ghost : ghosts) {
            String name = ghost.getName();
            if (name != null && candidateName.equalsIgnoreCase(name)) {
                return ghost;
            }
        }
        return null;
    }

    private int indexOfWholeGhostName(String textLower, String ghostNameLower) {
        if (textLower == null || textLower.isBlank() || ghostNameLower == null || ghostNameLower.isBlank()) {
            return -1;
        }
        int from = 0;
        while (from <= textLower.length() - ghostNameLower.length()) {
            int index = textLower.indexOf(ghostNameLower, from);
            if (index < 0) {
                return -1;
            }
            int leftIndex = index - 1;
            int rightIndex = index + ghostNameLower.length();
            boolean leftOk = leftIndex < 0 || !isGhostNameChar(textLower.charAt(leftIndex));
            boolean rightOk = rightIndex >= textLower.length() || !isGhostNameChar(textLower.charAt(rightIndex));
            if (leftOk && rightOk) {
                return index;
            }
            from = index + 1;
        }
        return -1;
    }

    private boolean isGhostNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
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

    private String trimToAiLength(String line, boolean english) {
        if (line == null || line.isBlank()) {
            return "";
        }
        if (english) {
            return trimToWordLimit(line, getAiMaxWordsEn());
        }
        int maxChars = getAiMaxCharsZh();
        if (line.length() <= maxChars) {
            return line.trim();
        }
        String trimmed = line.substring(0, maxChars).trim();
        return trimmed.replaceAll("[\\s,，。?!！？；;：:]+$", "");
    }

    private String trimToWordLimit(String text, int maxWords) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] words = WHITESPACE_PATTERN.split(text.trim());
        if (words.length <= maxWords) {
            return text.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
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
        return isGhostOnline(name);
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

    private boolean isSeenByCurrentAudience(String message) {
        String candidateKey = normalizeForSimilarity(message);
        if (candidateKey.isEmpty()) {
            return false;
        }
        double threshold = clamp(
            plugin.getConfig().getDouble("messages.audience-no-repeat-similarity-threshold", 0.82),
            0.55,
            0.99
        );
        int tailScan = Math.max(8, plugin.getConfig().getInt("messages.audience-similarity-tail-scan", 24));
        synchronized (recentLock) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null || !player.isOnline() || player.getName() == null || player.getName().isBlank()) {
                    continue;
                }
                LinkedList<String> seen = audienceSeenMessages.get(player.getName().toLowerCase(Locale.ROOT));
                if (seen == null || seen.isEmpty()) {
                    continue;
                }
                int scanned = 0;
                Iterator<String> descending = seen.descendingIterator();
                while (descending.hasNext() && scanned < tailScan) {
                    String seenKey = descending.next();
                    scanned++;
                    if (candidateKey.equals(seenKey)) {
                        return true;
                    }
                    if (isSimilar(candidateKey, seenKey, threshold)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void rememberAudienceSeenMessage(String message) {
        String key = normalizeForSimilarity(message);
        if (key.isEmpty()) {
            return;
        }
        int perPlayerWindow = Math.max(20, plugin.getConfig().getInt("messages.audience-no-repeat-window", 120));
        int trackedPlayersMax = Math.max(50, plugin.getConfig().getInt("messages.audience-tracked-players", 400));
        synchronized (recentLock) {
            audienceSeenMessages.entrySet().removeIf(entry -> {
                Player player = Bukkit.getPlayerExact(entry.getKey());
                return player == null || !player.isOnline();
            });
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null || !player.isOnline() || player.getName() == null || player.getName().isBlank()) {
                    continue;
                }
                String playerKey = player.getName().toLowerCase(Locale.ROOT);
                LinkedList<String> seen = audienceSeenMessages.computeIfAbsent(playerKey, ignored -> new LinkedList<>());
                seen.removeIf(existing -> existing.equalsIgnoreCase(key));
                seen.addLast(key);
                while (seen.size() > perPlayerWindow) {
                    seen.removeFirst();
                }
            }
            while (audienceSeenMessages.size() > trackedPlayersMax) {
                Iterator<String> iterator = audienceSeenMessages.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
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

    private String dedupeWithVariation(String message, boolean englishSpeaker) {
        String candidate = sanitizeOutgoingMessage(message);
        if (candidate.isBlank()) {
            return "";
        }
        if (!isRecentlyUsed(candidate) && !isSeenByCurrentAudience(candidate)) {
            return candidate;
        }

        String fallback = sanitizeOutgoingMessage(pickIdlePhraseByLanguage(englishSpeaker));
        if (!fallback.isBlank() && !isRecentlyUsed(fallback) && !isSeenByCurrentAudience(fallback)) {
            return fallback;
        }

        int attempts = Math.max(0, plugin.getConfig().getInt("messages.repeat-rewrite-attempts", 2));
        for (int i = 0; i < attempts; i++) {
            String rewritten = sanitizeOutgoingMessage(rewriteCandidate(candidate, i));
            if (rewritten.isBlank()) {
                continue;
            }
            if (!isRecentlyUsed(rewritten) && !isSeenByCurrentAudience(rewritten)) {
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
        if (!matchesLanguage(message, true)) {
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

    private void rememberRealPlayerMessage(String playerName, String message) {
        String raw = sanitizeRawLearningMessage(message);
        if (raw.isBlank()) {
            return;
        }
        String stripped = stripAllPlayerMentions(raw);
        String normalized = WHITESPACE_PATTERN.matcher(stripped.trim()).replaceAll(" ");
        if (!containsMeaningfulChars(normalized)) {
            return;
        }
        String owner = (playerName == null || playerName.isBlank()) ? "player" : playerName;
        String entry = owner + ": " + normalized;
        synchronized (recentLock) {
            recentRealPlayerMessages.addLast(entry);
            int maxMemory = Math.max(20, plugin.getConfig().getInt("ai-dialogue.real-player-memory-size", 120));
            while (recentRealPlayerMessages.size() > maxMemory) {
                recentRealPlayerMessages.removeFirst();
            }
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

    private void rememberGhostTopic(GhostPlayer ghost, String messageText) {
        if (ghost == null || ghost.getName() == null || ghost.getName().isBlank()) {
            return;
        }
        String topic = extractTopicWord(messageText);
        if (topic.isBlank()) {
            return;
        }
        String key = ghost.getName().toLowerCase(Locale.ROOT);
        synchronized (memoryLock) {
            LinkedList<String> topics = ghostTopicMemory.computeIfAbsent(key, ignored -> new LinkedList<>());
            topics.removeIf(existing -> existing.equalsIgnoreCase(topic));
            topics.addLast(topic);
            int maxTopicMemory = Math.max(3, plugin.getConfig().getInt("ai-dialogue.per-ghost-topic-memory", 12));
            while (topics.size() > maxTopicMemory) {
                topics.removeFirst();
            }
            int maxTopicOwners = Math.max(50, plugin.getConfig().getInt("ai-dialogue.topic-owner-max-size", 600));
            if (ghostTopicMemory.size() > maxTopicOwners) {
                Set<String> activeGhostKeys = GhostManager.getGhosts().stream()
                    .map(GhostPlayer::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
                ghostTopicMemory.entrySet().removeIf(entry -> !activeGhostKeys.contains(entry.getKey()));
                while (ghostTopicMemory.size() > maxTopicOwners) {
                    Iterator<String> iterator = ghostTopicMemory.keySet().iterator();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    iterator.next();
                    iterator.remove();
                }
            }
        }
    }

    private boolean isLowSignalChatLine(String message, boolean englishSpeaker) {
        if (message == null || message.isBlank()) {
            return true;
        }
        if (!plugin.getConfig().getBoolean("humanization.filter-low-signal-lines", true)) {
            return false;
        }
        String trimmed = message.trim();
        if (trimmed.matches("^[\\p{Punct}。！？，、…]+$")) {
            return true;
        }
        String compact = NON_CONTENT_PATTERN.matcher(trimmed).replaceAll("");
        if (compact.length() < 2) {
            return true;
        }
        if (trimmed.matches("^\\d{1,4}$")) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String blocked : plugin.getConfig().getStringList("humanization.low-signal-blocklist")) {
            if (!blocked.isBlank() && lower.equals(blocked.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (englishSpeaker) {
            String[] words = WHITESPACE_PATTERN.split(lower);
            if (words.length == 1 && words[0].length() <= 2) {
                return true;
            }
        }
        return false;
    }

    private boolean reserveSpeakSlot(GhostPlayer ghost, String message, boolean highPriority) {
        if (!plugin.getConfig().getBoolean("humanization.enabled", true)) {
            return true;
        }
        if (!highPriority && shouldSuppressForPopulation()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long globalCooldownMs = Math.max(0L, plugin.getConfig().getLong("humanization.global-chat-cooldown-seconds", 4L)) * 1000L;
        long burstWindowMs = Math.max(5L, plugin.getConfig().getLong("humanization.burst-window-seconds", 30L)) * 1000L;
        int burstMax = Math.max(1, plugin.getConfig().getInt("humanization.burst-max-lines", 4));
        long topicCooldownMs = Math.max(0L, plugin.getConfig().getLong("humanization.same-topic-cooldown-seconds", 120L)) * 1000L;

        long ghostMinSeconds = Math.max(2L, plugin.getConfig().getLong("humanization.ghost-chat-cooldown-seconds-min", 20L));
        long ghostMaxSeconds = Math.max(ghostMinSeconds, plugin.getConfig().getLong("humanization.ghost-chat-cooldown-seconds-max", 75L));
        long randomGhostSeconds = ghostMinSeconds + ThreadLocalRandom.current().nextLong(ghostMaxSeconds - ghostMinSeconds + 1L);
        if (highPriority) {
            randomGhostSeconds = Math.max(2L, Math.round(randomGhostSeconds * 0.6));
        }

        String ghostKey = ghost == null || ghost.getName() == null ? "" : ghost.getName().toLowerCase(Locale.ROOT);
        String topicKey = normalizeTopicKey(extractTopicWord(message));

        synchronized (throttleLock) {
            pruneCooldownCaches(now);
            while (!recentSendTimeline.isEmpty() && now - recentSendTimeline.getFirst() > burstWindowMs) {
                recentSendTimeline.removeFirst();
            }
            if (!highPriority && now < globalNextSpeakAt) {
                return false;
            }
            if (!highPriority && recentSendTimeline.size() >= burstMax) {
                return false;
            }
            if (!ghostKey.isBlank()) {
                long ghostNext = ghostNextSpeakAt.getOrDefault(ghostKey, 0L);
                if (!highPriority && now < ghostNext) {
                    return false;
                }
            }
            if (!highPriority && topicCooldownMs > 0L && !topicKey.isBlank()) {
                long topicNext = topicNextSpeakAt.getOrDefault(topicKey, 0L);
                if (now < topicNext) {
                    return false;
                }
            }

            long effectiveGlobal = highPriority ? Math.max(1000L, globalCooldownMs / 2L) : globalCooldownMs;
            if (effectiveGlobal > 0L) {
                globalNextSpeakAt = now + effectiveGlobal;
            }
            recentSendTimeline.addLast(now);
            if (!ghostKey.isBlank()) {
                ghostNextSpeakAt.put(ghostKey, now + randomGhostSeconds * 1000L);
            }
            if (topicCooldownMs > 0L && !topicKey.isBlank()) {
                topicNextSpeakAt.put(topicKey, now + topicCooldownMs);
            }
            return true;
        }
    }

    private void pruneCooldownCaches(long now) {
        long staleAfterMs = Math.max(300_000L, plugin.getConfig().getLong("humanization.cooldown-cache-stale-seconds", 1800L) * 1000L);
        int maxTopicCache = Math.max(200, plugin.getConfig().getInt("humanization.topic-cache-max-size", 4000));
        int maxGhostCache = Math.max(100, plugin.getConfig().getInt("humanization.ghost-cache-max-size", 1000));

        pruneExpiryMap(topicNextSpeakAt, now, staleAfterMs);
        pruneExpiryMap(ghostNextSpeakAt, now, staleAfterMs);
        trimMapByEarliestExpiry(topicNextSpeakAt, maxTopicCache);
        trimMapByEarliestExpiry(ghostNextSpeakAt, maxGhostCache);
    }

    private void pruneExpiryMap(Map<String, Long> map, long now, long staleAfterMs) {
        map.entrySet().removeIf(entry -> now - entry.getValue() > staleAfterMs);
    }

    private void trimMapByEarliestExpiry(Map<String, Long> map, int maxSize) {
        if (map.size() <= maxSize) {
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        int removeCount = map.size() - maxSize;
        for (int i = 0; i < removeCount && i < entries.size(); i++) {
            map.remove(entries.get(i).getKey());
        }
    }

    private String normalizeTopicKey(String topic) {
        if (topic == null || topic.isBlank()) {
            return "";
        }
        String key = NON_CONTENT_PATTERN.matcher(topic.toLowerCase(Locale.ROOT)).replaceAll("");
        return key.length() < 2 ? "" : key;
    }

    private boolean shouldSuppressForPopulation() {
        if (!plugin.getConfig().getBoolean("humanization.require-real-player-online", true)) {
            return false;
        }
        int minPlayers = Math.max(0, plugin.getConfig().getInt("humanization.min-online-real-players", 1));
        return Bukkit.getOnlinePlayers().size() < minPlayers;
    }

    public LearningStatus getLearningStatus() {
        int pendingGeneral;
        int pendingQa;
        int pending;
        synchronized (learningLock) {
            pendingGeneral = pendingLearningRaw.size();
            pendingQa = pendingLearningQaRaw.size();
            pending = pendingGeneral + pendingQa;
        }
        return new LearningStatus(
            rawLearningStore.size(),
            pending,
            phraseLearningStore.size() + qaPhraseLearningStore.size(),
            pendingGeneral,
            pendingQa,
            learningSummaryInFlight,
            qaLearningSummaryInFlight,
            learningSummaryFailureStreak,
            qaLearningSummaryFailureStreak
        );
    }

    private void speakWithTyping(GhostPlayer ghost, String messageText, long extraGapTicks) {
        speakWithTyping(ghost, messageText, extraGapTicks, false);
    }

    private void speakWithTyping(GhostPlayer ghost, String messageText, long extraGapTicks, boolean highPriority) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> speakWithTyping(ghost, messageText, extraGapTicks, highPriority));
            return;
        }

        String candidate = fitMessageToGhostLanguage(ghost, messageText);
        candidate = sanitizeOutgoingMessage(candidate);
        if (candidate == null || candidate.isBlank()) return;

        String finalMessage = dedupeWithVariation(candidate, ghost != null && ghost.isEnglishSpeaker());
        if (finalMessage.isBlank()) return;
        if (isLowSignalChatLine(finalMessage, ghost != null && ghost.isEnglishSpeaker())) {
            return;
        }
        if (!reserveSpeakSlot(ghost, finalMessage, highPriority)) {
            return;
        }

        recordUsedMessage(finalMessage);
        rememberGhostLine(finalMessage);
        rememberGhostTopic(ghost, finalMessage);
        rememberAudienceSeenMessage(finalMessage);

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





