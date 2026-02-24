package org.example.util;

import java.awt.Color;

public class RankUtils {

    public static String getRankEmoji(String tier) {
        if (tier == null) return "❓";
        return switch (tier.toUpperCase()) {
            case "IRON" -> "<:iron:1465729151121096858>";
            case "BRONZE" -> "<:bronze:1465729193626046659>";
            case "SILVER" -> "<:silver:1465729273116627110>";
            case "GOLD" -> "<:gold:1465729325063213161>";
            case "PLATINUM" -> "<:platinum:1465729466230771804>";
            case "EMERALD" -> "<:emerald:1465729555531829443>";
            case "DIAMOND" -> "<:diamond:1465729632706760715>";
            case "MASTER" -> "<:master:1465729682505859133>";
            case "GRANDMASTER" -> "<:grandmaster:1465729725187096717>";
            case "CHALLENGER" -> "<:challenger:1465729776684765385>";
            default -> "❓";
        };
    }

    public static Color getRankColor(String tier) {
        if (tier == null) return Color.GRAY;
        return switch (tier.toUpperCase()) {
            case "IRON" -> new Color(87, 77, 79);
            case "BRONZE" -> new Color(140, 81, 58);
            case "SILVER" -> new Color(128, 152, 157);
            case "GOLD" -> new Color(205, 136, 55);
            case "PLATINUM" -> new Color(78, 161, 177);
            case "EMERALD" -> new Color(42, 168, 115);
            case "DIAMOND" -> new Color(87, 107, 236);
            case "MASTER" -> new Color(157, 72, 224);
            case "GRANDMASTER" -> new Color(239, 79, 79);
            case "CHALLENGER" -> new Color(244, 194, 68);
            default -> Color.GRAY;
        };
    }

    public static int calculateEloScore(String tier, String rank, int lp) {
        int baseScore = 0;
        switch (tier.toUpperCase()) {
            case "IRON": baseScore = 0; break;
            case "BRONZE": baseScore = 400; break;
            case "SILVER": baseScore = 800; break;
            case "GOLD": baseScore = 1200; break;
            case "PLATINUM": baseScore = 1600; break;
            case "EMERALD": baseScore = 2000; break;
            case "DIAMOND": baseScore = 2400; break;
            case "MASTER": baseScore = 2800; break;
            case "GRANDMASTER": baseScore = 3200; break;
            case "CHALLENGER": baseScore = 3600; break;
            default: return -1;
        }
        int divisionScore = 0;
        if (baseScore < 2800) {
            switch (rank) {
                case "I": divisionScore = 300; break;
                case "II": divisionScore = 200; break;
                case "III": divisionScore = 100; break;
                case "IV": divisionScore = 0; break;
            }
        }
        return baseScore + divisionScore + lp;
    }
}