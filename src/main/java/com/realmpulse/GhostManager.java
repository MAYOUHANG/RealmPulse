package com.realmpulse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.plugin.java.JavaPlugin;

public class GhostManager {
    private static final int MAX_NAME_LENGTH = 16;
    private static final int MIN_NAME_LENGTH = 3;
    private static final char[] ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

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
        "ChenXi", "ZiHan", "MuYu", "YuChen", "LinFeng", "JiaHao", "AnRan", "RuoXi",
        "QingYu", "TianYi", "MingYue", "BaiChen", "FeiYu", "QiangGe", "LeXin", "HanYu",
        "JingZe", "ShenMu", "YeLan", "MoYan", "SuHe", "YanMo", "QiuYu", "NanFeng",
        "BeiChen", "YunXi", "ZhiYuan", "YueBai", "CangLan", "JunMo", "JinYan", "KeXin",
        "BoWen", "WeiRan", "YuMo", "YiFan", "ChuGe", "QiAn", "ShaoYun", "LanTing",
        "JiuGe", "YuHeng", "ZuoAn", "ShanHe", "SiYuan", "LingYue", "ZhiChen", "RunZe",
        "XingHe", "TingLan", "YiNuo", "QinMu", "RuiZe", "QiaoRan", "MuChen", "YanXi",
        "ZhaoYue", "FanYu", "ZhiMo", "MoXi", "SuiFeng", "RuoChen", "NingYuan"
    };
    private static final String[] CN_TAGS = {
        "Ge", "Jie", "DaShen", "MengXin", "WanJia", "LaoLiu", "ZaiXian", "KaiHei",
        "LianJi", "MoYu", "ZhanShen", "YeXing", "TanSuo", "ShouHu", "KaiTuan",
        "TuiTu", "ShuaBen", "DuiYou", "YongShi", "JueJing", "FanGuan"
    };
    private static final String[] CN_TAGS_EN_HINT = {
        "MC", "Craft", "Player", "Nova", "Lite", "Wolf", "Fox", "XD", "Hero", "Star"
    };

    private static final String[] AUTHENTIC_EN_IDS = {
        "Dream", "Sapnap", "Skeppy", "BadBoyHalo", "Technoblade", "TommyInnit", "Tubbo", "Ranboo",
        "Philza", "Punz", "Antfrost", "Purpled", "HBomb94", "Krinios", "TapL", "Grian",
        "MumboJumbo", "EthosLab", "Xisuma", "DanTDM", "iJevin", "Notch", "jeb_", "Dinnerbone",
        "Grumm", "Vikkstar123", "JeromeASF", "PeteZahHutt", "xNestorio", "IlluminaHD", "Feinberg",
        "Wolfeei", "Fruitberries", "Fundy", "WilburSoot", "Quackity", "Krtzyy", "Seapeekay",
        "CaptainPuffy", "Smajor1995", "InTheLittleWood", "Solidarity", "Shubble", "fWhip", "GeminiTay",
        "impulseSV", "TangoTek", "Skizzleman", "Zedaph", "Docm77", "BdoubleO100", "GoodBird",
        "StoneMole", "MapleMC", "AquaBlade", "RedTulip", "SnowyKid", "NoxPlayer", "AresPvP",
        "CocoBean", "StarDawn", "ZeroCool", "NightWolf", "NeoCraft", "PixelRin", "BlueMoss",
        "TofuMC", "AsunaMC", "KiraPvP", "NaruCraft", "MochiMC", "RavenMC", "HakuMC",
        "YoruMC", "MikaMC", "SoraMC", "KaiMC", "RinMC", "YukiMC", "MintLemon",
        "CloudRider", "IronPick", "GoldApple", "BlockSmith", "FarmBuddy", "DungeonRun",
        "SkyRoute", "TrailFox", "WolfDen", "FoxTail", "CraftPilot", "MineRiver", "OakLeaf",
        "BirchWind", "SpruceWolf", "EnderDust", "NetherDash", "AetherKid", "FrostPine", "EchoRain",
        "NovaByte", "PixelMint", "ArcLight", "MoonDrop", "Starfall", "SunbeamMC", "RiverSong",
        "QuietOwl", "LimeSoda", "BerryPie", "ToastKid", "CyanStone", "MangoFox", "VelvetSky",
        "QuartzKid", "CrimsonOak", "SilverLeaf", "CopperWolf", "SlateBird", "WindArrow", "DawnRider",
        "ThirtyVirus", "Refraction", "SwavyL", "Wallibear", "Bombies", "IntelEdits", "Frosted",
        "Wisp", "Preston", "Unspeakable", "BajanCanadian", "Syndicate", "CaptainSparklez", "PopularMMOs",
        "SethBling", "AntVenom", "SkyDoesMC", "Aphmau", "LDShadowLady", "Smallishbeans", "FalseSymmetry",
        "Keralis", "Rendog", "Welsknight", "Cubfan135", "ZombieCleo", "Stressmonster", "PearlescentMoon",
        "GoodTimesWithS", "Iskall85", "Hypno", "Mefs", "TurtleMC", "RaiderJoe", "SwiftBlade",
        "GrainOfSalt", "NightCraft", "MapRunner", "RouteMaster", "BlockWizard", "OreHunter", "StoneFox",
        "SkyMiner", "DeepCave", "MudWalker", "ForestCat", "DriftWood", "TorchBear", "ShieldMate",
        "QuartzFox", "IronWolf", "CopperBird", "SeaLantern", "GlowBerry", "RedMushroom", "BlueIce",
        "HoneyComb", "NetherBrick", "EndStone", "SoulTorch", "WarpRunner", "EchoCave", "RiftWalker",
        "CoreBreaker", "LaneBuilder", "CropGuard", "LootKeeper", "SpawnRider", "PortalPilot", "RushAnchor",
        "ScoutLeaf", "BrickArrow", "CloudMender", "RiverKeeper", "PathSage", "SkyHarbor", "MossRider",
        "TinFox", "AmberLeaf", "LavaRiver", "SnowWolf", "RainDancer", "FrostArrow", "AshenOak",
        "BasaltFox", "CobaltSky", "GraniteKid", "ObsidianQ", "AquaStorm", "GreenSprout", "HoneyTea",
        "MoonQuartz", "SunVale", "StarMint", "DustyTrail", "CalmHarbor", "WildBirch", "TinyComet"
    };

    private static final String[] AUTHENTIC_CN_STYLE_IDS = {
        "BeiChen", "QingMu", "HanYan", "MingYue", "LinFeng", "YunHai", "TianMo", "QiuShui",
        "YanGe", "MoYu", "YeZhi", "LuChen", "NianNian", "SuSu", "MuLan", "QingHe",
        "RuoNan", "NingXi", "YuXiao", "WanQing", "JinYu", "ShenLan", "YuRan", "BoWen",
        "KeXin", "YiFan", "RinRin", "AkiQ", "MikoNeko", "SoraKaze", "Kitsune7", "NekoMio",
        "KumoCloud", "HaruRain", "YukiTea", "MochiCat", "RikkaStar", "AsaNoa", "Yorumi", "LimeSoda",
        "ColaFish", "MilkTea77", "CocoPuff", "TofuRice", "RiceBunny", "MapleLeaf", "AmberFox", "NightOwl",
        "StoneRiver", "CloudStep", "FrostWing", "WindBlade", "RiverSong", "QuietWolf", "LuckySheep", "BlueTulip",
        "JadeLotus", "PlumRain", "InkBamboo", "TeaHouse", "OldDriver", "LittleBear", "BigOrange", "KoiFish",
        "Lantern", "SparkByte", "PixelMint", "NovaLeaf", "AetherRun", "BlockSmith", "MineRoute", "CraftNest",
        "FarmBuddy", "DungeonPro", "RedstoneQ", "SkyRoute", "TrailMark", "WolfDen", "FoxTail", "IronPick",
        "GoldApple", "EnderDust", "ArcLight", "MoonDrop", "Starfall", "EchoRain", "SnowPine", "SpringTea",
        "SummerWind", "AutumnMaple", "WinterSun", "NorthWind", "SouthBay", "EastLake", "WestHill", "SevenCat",
        "EightBit", "NineCloud", "TenRings", "ZeroDawn", "NeoRiver", "KiraLight", "RinaBlue", "SenaFox",
        "HinaMori", "MinaQ", "NoaSky", "Kaiya", "RuiMoon", "YuriLime", "SakiRain", "MakiTea",
        "NariFox", "LunaPond", "MomoTaro", "KakaDuck", "BoboBear", "DodoBird", "PandaLu", "TigerMu",
        "WhaleQi", "OtterYu", "KoalaAn", "RabbitHe", "SparrowJi", "PeachSoda", "MintCandy", "BerryPie",
        "RikaCat", "NanaFox", "GuguBird", "DidiWolf", "LuoChen", "ZhiYuan", "MuChen", "ShanYu",
        "QingLan", "LanTing", "RuoXi", "YunXi", "JiuGe", "QiaoRan", "TingYu", "ZiHan",
        "Ayanami", "Akiha", "Ritsu", "Yoru", "Mizuki", "RinAsa", "Kaze", "NoirFox",
        "BaiTang", "QingTang", "MuSong", "YunTing", "WeiLan", "ChenMo", "LuYu", "HanShuo",
        "AnNing", "XingYao", "YuZhou", "QianYun", "LinJian", "YeTing", "NuoNuo", "MingHe",
        "LaoChen", "LaoLi", "AFei", "ABin", "AHao", "ALei", "AYan", "AWen",
        "AXing", "AYu", "AMo", "ANing", "MaoMao", "TuTu", "NiuNiu", "HuHu",
        "YuanYuan", "MianMian", "YunGao", "LanMao", "QingYe", "MuYun", "JinBing", "DongLi",
        "BeiYu", "NanHe", "YuShu", "LinHe", "QiaoMu", "YingYue", "YaoLan", "XiMu",
        "AnGe", "HeTang", "HuaiNan", "QinHai", "JiangNan", "HaiTang", "YuBai", "MoQiu",
        "QingDao", "HeYe", "BlueCheese", "KiriTea", "YukiCandy", "KumaKun", "NekoChan", "HinaHana",
        "SoraFeather", "RinBell", "MikaTone", "NoaMoon", "RuiStar", "YuriWind", "NariLamp", "LunaLake",
        "MomoRain", "Heifeng", "Qingcheng", "Anyi", "Muyu", "Luoluo", "Dumpling", "RiceBall",
        "ColdNoodle", "HotPot", "Abo", "Aqian", "Ajia", "Amochi", "BingTang", "Xiaoxi",
        "Ziyou", "Anran", "Haitun", "Bailu", "Qichen", "Nanshan", "Beihai", "Donghu",
        "Xihai", "Yeling", "Mingxuan", "Yueliang", "Shiguang", "Muren", "Qinglin", "Shuimu"
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
        Map<Character, Integer> leadingCharUsage = new HashMap<>();
        int attempts = 0;
        int maxAttempts = Math.max(400, count * 80);
        GhostPlayer.Language configuredLanguage = resolveConfiguredLanguage();
        boolean englishPreferred = configuredLanguage == GhostPlayer.Language.EN;
        double idEnglishFragmentChance = clampChance(plugin.getConfig().getDouble("chat.id-english-fragment-chance", 0.08));

        while (ghosts.size() < count && attempts < maxAttempts) {
            attempts++;
            NameCandidate candidate = generateCandidateName(
                attempts,
                baseUsage,
                tagUsage,
                leadingCharUsage,
                englishPreferred,
                idEnglishFragmentChance
            );
            String name = candidate.name();

            if (usedNames.add(name)) {
                incrementUsage(baseUsage, candidate.base());
                incrementUsage(tagUsage, candidate.tag());
                incrementLeadingUsage(leadingCharUsage, name);
                GhostPlayer ghost = new GhostPlayer(name, plugin, configuredLanguage);
                ghosts.add(ghost);
            }
        }

        if (ghosts.size() < count) {
            plugin.getLogger().warning("Could only generate " + ghosts.size() + " unique ghost names after " + maxAttempts + " attempts.");
        }
    }

    private GhostPlayer.Language resolveConfiguredLanguage() {
        String raw = plugin.getConfig().getString("chat.language", "zh");
        if (raw == null) {
            return GhostPlayer.Language.ZH;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "en", "english" -> GhostPlayer.Language.EN;
            case "zh", "cn", "zh-cn", "zh_cn", "chinese", "simplified-chinese", "simplified_chinese" -> GhostPlayer.Language.ZH;
            default -> GhostPlayer.Language.ZH;
        };
    }

    private NameCandidate generateCandidateName(
        int attempt,
        Map<String, Integer> baseUsage,
        Map<String, Integer> tagUsage,
        Map<Character, Integer> leadingCharUsage,
        boolean englishPreferred,
        double idEnglishFragmentChance
    ) {
        String[] authenticPool = englishPreferred ? AUTHENTIC_EN_IDS : AUTHENTIC_CN_STYLE_IDS;
        double directPoolChance = clampChance(plugin.getConfig().getDouble("chat.authentic-id-pool-chance", 0.90));
        if (ThreadLocalRandom.current().nextDouble() < directPoolChance) {
            String direct = pickLeastUsed(authenticPool, baseUsage);
            String rawDirect = direct;
            double suffixChance = englishPreferred ? 0.14 : 0.10;
            if (ThreadLocalRandom.current().nextDouble() < suffixChance) {
                rawDirect = appendNumericSuffix(rawDirect, ThreadLocalRandom.current().nextInt(2, 100));
            }
            String normalized = normalizeDirectName(rawDirect, attempt);
            return new NameCandidate(normalized, direct, "");
        }

        boolean englishFlavor = !englishPreferred && ThreadLocalRandom.current().nextDouble() < idEnglishFragmentChance;
        String[] basePool;
        String[] tagPool;
        if (englishPreferred) {
            basePool = EN_BASES;
            tagPool = EN_TAGS;
        } else {
            boolean useEnglishBase = englishFlavor && ThreadLocalRandom.current().nextDouble() < 0.20;
            basePool = useEnglishBase ? EN_BASES : CN_BASES;
            tagPool = englishFlavor ? CN_TAGS_EN_HINT : CN_TAGS;
        }

        String base = pickLeastUsed(basePool, baseUsage);
        String tag = pickLeastUsed(tagPool, tagUsage);
        String tail = randomIdTail(englishPreferred || englishFlavor);
        String token = "";
        if (englishPreferred && ThreadLocalRandom.current().nextDouble() < 0.22) {
            token = randomNumeric(1, 2);
        } else if (!englishPreferred && englishFlavor && ThreadLocalRandom.current().nextDouble() < 0.20) {
            token = randomNumeric(1, 2);
        } else if (!englishPreferred && !englishFlavor && ThreadLocalRandom.current().nextDouble() < 0.12) {
            token = String.valueOf(ThreadLocalRandom.current().nextInt(10));
        }

        int type = ThreadLocalRandom.current().nextInt(8);
        String rawName;
        if (englishPreferred) {
            rawName = switch (type) {
                case 0 -> base + tail;
                case 1 -> clipIdPart(base, 4) + clipIdPart(tag, 2) + tail;
                case 2 -> tag + tail;
                case 3 -> base + clipIdPart(tag, 1) + token + tail;
                case 4 -> "Its" + clipIdPart(base, 3) + tail;
                case 5 -> clipIdPart(base, 3) + clipIdPart(tag, 2) + token;
                case 6 -> clipIdPart(tag, 3) + clipIdPart(base, 2) + tail;
                default -> clipIdPart(base, 2) + clipIdPart(tag, 2) + token + tail;
            };
        } else {
            rawName = switch (type) {
                case 0 -> base + tail;
                case 1 -> clipIdPart(base, 4) + clipIdPart(tag, 2) + tail;
                case 2 -> tag + clipIdPart(base, 3) + tail;
                case 3 -> clipIdPart(base, 3) + clipIdPart(tag, 1) + token + tail;
                case 4 -> clipIdPart(base, 5) + tail;
                case 5 -> clipIdPart(tag, 2) + clipIdPart(base, 2) + token + tail;
                case 6 -> clipIdPart(base, 2) + clipIdPart(tag, 3) + tail;
                default -> clipIdPart(base, 4) + clipIdPart(tag, 1) + token;
            };
        }

        String name = enforceNameLength(rawName, base, tag, attempt);
        if (name.isBlank()) {
            name = "Player" + toBase36(attempt % 46656);
            name = enforceNameLength(name, "Player", "", attempt);
        }
        name = rebalanceLeadingCharacter(name, leadingCharUsage, attempt);
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

    private void incrementLeadingUsage(Map<Character, Integer> usage, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        char c = Character.toUpperCase(name.charAt(0));
        usage.put(c, usage.getOrDefault(c, 0) + 1);
    }

    private String clipIdPart(String raw, int maxLength) {
        if (raw == null || raw.isBlank() || maxLength <= 0) {
            return "";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        if (ThreadLocalRandom.current().nextDouble() < 0.97) {
            return cleaned.substring(0, maxLength);
        }
        int start = ThreadLocalRandom.current().nextInt(cleaned.length() - maxLength + 1);
        return cleaned.substring(start, start + maxLength);
    }

    private String normalizeDirectName(String raw, int seed) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isBlank()) {
            cleaned = "Player" + toBase36(seed);
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "P" + cleaned;
            if (cleaned.length() > MAX_NAME_LENGTH) {
                cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
            }
        }
        if (cleaned.length() < MIN_NAME_LENGTH) {
            String filler = toBase36(seed);
            int index = 0;
            while (cleaned.length() < MIN_NAME_LENGTH) {
                cleaned = cleaned + filler.charAt(index % filler.length());
                index++;
            }
        }
        return cleaned;
    }

    private String appendNumericSuffix(String base, int suffix) {
        String safeBase = base == null ? "" : base;
        if (safeBase.isBlank()) {
            safeBase = "Player";
        }
        String suffixText = String.valueOf(Math.max(0, suffix));
        int maxBaseLength = MAX_NAME_LENGTH - suffixText.length();
        if (maxBaseLength <= 0) {
            return suffixText.substring(0, Math.min(MAX_NAME_LENGTH, suffixText.length()));
        }
        int keep = Math.max(1, Math.min(safeBase.length(), maxBaseLength));
        return safeBase.substring(0, keep) + suffixText;
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

    private String rebalanceLeadingCharacter(String name, Map<Character, Integer> leadingUsage, int seed) {
        if (name == null || name.isBlank()) {
            return "";
        }
        char first = Character.toUpperCase(name.charAt(0));
        int current = leadingUsage.getOrDefault(first, 0);
        int min = Integer.MAX_VALUE;
        for (int value : leadingUsage.values()) {
            if (value < min) {
                min = value;
            }
        }
        if (min == Integer.MAX_VALUE) {
            min = 0;
        }
        if (current <= min + 1) {
            return name;
        }

        List<Character> candidates = new ArrayList<>();
        int best = Integer.MAX_VALUE;
        for (char c : ID_CHARS) {
            if (Character.isDigit(c)) {
                continue;
            }
            int count = leadingUsage.getOrDefault(c, 0);
            if (count < best) {
                best = count;
                candidates.clear();
                candidates.add(c);
            } else if (count == best) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return name;
        }
        char replacement = candidates.get(Math.floorMod(seed, candidates.size()));
        if (replacement == first) {
            return name;
        }
        String adjusted = replacement + (name.length() > 1 ? name.substring(1) : "");
        if (adjusted.length() > MAX_NAME_LENGTH) {
            adjusted = adjusted.substring(0, MAX_NAME_LENGTH);
        }
        while (adjusted.length() < MIN_NAME_LENGTH) {
            adjusted = adjusted + "x";
        }
        return adjusted;
    }

    private int pickTargetLength() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 6) {
            return 4;
        }
        if (roll < 16) {
            return 5;
        }
        if (roll < 30) {
            return 6;
        }
        if (roll < 45) {
            return 7;
        }
        if (roll < 60) {
            return 8;
        }
        if (roll < 74) {
            return 9;
        }
        if (roll < 85) {
            return 10;
        }
        if (roll < 93) {
            return 11;
        }
        if (roll < 97) {
            return 12;
        }
        if (roll < 99) {
            return 13;
        }
        return 14;
    }

    private String randomIdTail(boolean allowAlpha) {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.52) {
            return "";
        }
        if (r < 0.82) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(2, 10));
        }
        if (r < 0.97) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(10, 100));
        }
        if (!allowAlpha || r < 0.995) {
            return String.valueOf(ThreadLocalRandom.current().nextInt(100, 1000));
        }
        return randomAlphaNumeric(1, 2);
    }

    private String randomNumeric(int minLength, int maxLength) {
        int min = Math.max(1, minLength);
        int max = Math.max(min, maxLength);
        int len = ThreadLocalRandom.current().nextInt(min, max + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    private String randomAlphaNumeric(int minLength, int maxLength) {
        int min = Math.max(1, minLength);
        int max = Math.max(min, maxLength);
        int len = ThreadLocalRandom.current().nextInt(min, max + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ID_CHARS[ThreadLocalRandom.current().nextInt(ID_CHARS.length)]);
        }
        return sb.toString();
    }

    private String toBase36(int value) {
        return Integer.toString(Math.max(0, value), 36).toUpperCase();
    }

    private double clampChance(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record NameCandidate(String name, String base, String tag) {
    }

    public static List<GhostPlayer> getGhosts() {
        return instance == null ? Collections.emptyList() : instance.ghosts;
    }

    public static List<GhostPlayer> getOnlineGhosts() {
        if (instance == null) {
            return Collections.emptyList();
        }
        List<GhostPlayer> online = new ArrayList<>();
        for (GhostPlayer ghost : instance.ghosts) {
            if (ghost.isOnline()) {
                online.add(ghost);
            }
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
