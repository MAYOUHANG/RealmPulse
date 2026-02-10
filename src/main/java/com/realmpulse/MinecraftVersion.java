package com.realmpulse;

import org.bukkit.Bukkit;

public enum MinecraftVersion {
    UNKNOWN(0),
    V1_19_3(1193),
    V1_19_4(1194),
    V1_20(1200),
    V1_20_1(1201),
    V1_20_2(1202),
    V1_20_4(1204),
    V1_20_6(1206),
    V1_21(1210),
    V1_21_1(1211),
    V1_21_4(1214);

    private final int value;
    private static MinecraftVersion current;

    MinecraftVersion(int value) {
        this.value = value;
    }

    public static MinecraftVersion get() {
        if (current != null) return current;

        String version = Bukkit.getBukkitVersion().split("-")[0];
        if (version.startsWith("1.19.3")) current = V1_19_3;
        else if (version.startsWith("1.19.4")) current = V1_19_4;
        else if (version.startsWith("1.20.1")) current = V1_20_1;
        else if (version.startsWith("1.20.2")) current = V1_20_2;
        else if (version.startsWith("1.20.3") || version.startsWith("1.20.4")) current = V1_20_4;
        else if (version.startsWith("1.20.5") || version.startsWith("1.20.6")) current = V1_20_6;
        else if (version.startsWith("1.20")) current = V1_20;
        else if (version.startsWith("1.21.4")) current = V1_21_4;
        else if (version.startsWith("1.21.1")) current = V1_21_1;
        else if (version.startsWith("1.21")) current = V1_21;
        else current = UNKNOWN;

        return current;
    }

    public boolean isAtLeast(MinecraftVersion other) {
        return this.value >= other.value;
    }
}
