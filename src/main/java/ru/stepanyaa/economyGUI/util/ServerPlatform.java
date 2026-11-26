package ru.stepanyaa.economyGUI.util;

import org.bukkit.Bukkit;

public class ServerPlatform {

    private static final boolean IS_FOLIA;
    private static final boolean IS_PAPER;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (Throwable ignored) {}
        IS_FOLIA = folia;

        boolean paper = false;
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            paper = true;
        } catch (Throwable ignored) {
            try {
                Class.forName("io.papermc.paper.configuration.Configuration");
                paper = true;
            } catch (Throwable ignored2) {}
        }
        IS_PAPER = paper;
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public static boolean isPaper() {
        return IS_PAPER;
    }

    public static String getPlatformName() {
        if (IS_FOLIA) return "Folia";
        if (IS_PAPER) {
            String name = Bukkit.getName();
            if (name != null && name.toLowerCase().contains("purpur")) return "Purpur";
            return "Paper";
        }
        String name = Bukkit.getName();
        if (name != null && name.toLowerCase().contains("spigot")) return "Spigot";
        return "Bukkit";
    }
}
