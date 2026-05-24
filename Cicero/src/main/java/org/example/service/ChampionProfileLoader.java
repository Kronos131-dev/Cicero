package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class ChampionProfileLoader {

    public static class ResolvedProfile {
        public final String champClass;
        public final double scalingFactor;
        public ResolvedProfile(String champClass, double scalingFactor) {
            this.champClass = champClass;
            this.scalingFactor = scalingFactor;
        }
    }

    // Scaling factor par défaut selon la classe (0.0 = early, 1.0 = hyper-scaling)
    private static final Map<String, Double> CLASS_SCALING = Map.of(
            ScoreCalculator.COMBATTANT_ECLAIR, 0.80,
            ScoreCalculator.MAGE,              0.65,
            ScoreCalculator.ADC,               0.70,
            ScoreCalculator.ASSASSIN,          0.55,
            ScoreCalculator.COMBATTANT,        0.25,
            ScoreCalculator.TANK,              0.10,
            ScoreCalculator.ENCHANTER,         0.15
    );

    // Overrides individuels pour les outliers de scaling
    private static final Map<String, Double> CHAMPION_SCALING_OVERRIDE = new HashMap<>();
    static {
        CHAMPION_SCALING_OVERRIDE.put("kassadin",    0.95);
        CHAMPION_SCALING_OVERRIDE.put("kayle",       0.95);
        CHAMPION_SCALING_OVERRIDE.put("vladimir",    0.85);
        CHAMPION_SCALING_OVERRIDE.put("aurelion sol",0.90);
        CHAMPION_SCALING_OVERRIDE.put("veigar",      0.90);
        CHAMPION_SCALING_OVERRIDE.put("nasus",       0.85);
        CHAMPION_SCALING_OVERRIDE.put("senna",       0.75);
        CHAMPION_SCALING_OVERRIDE.put("renekton",    0.05);
        CHAMPION_SCALING_OVERRIDE.put("pantheon",    0.10);
        CHAMPION_SCALING_OVERRIDE.put("darius",      0.15);
        CHAMPION_SCALING_OVERRIDE.put("blitzcrank",  0.05);
        CHAMPION_SCALING_OVERRIDE.put("leona",       0.05);
    }

    // Cache du JSON des classes (chargé une seule fois)
    private static JSONObject classesJson = null;

    private static JSONObject loadClassesJson() {
        if (classesJson != null) return classesJson;
        try (InputStream is = ChampionProfileLoader.class.getClassLoader()
                .getResourceAsStream("champion_classes.json")) {
            if (is == null) {
                System.err.println("❌ champion_classes.json introuvable dans resources.");
                return new JSONObject();
            }
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
                String content = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "{}";
                classesJson = new JSONObject(content);
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur lecture champion_classes.json : " + e.getMessage());
            return new JSONObject();
        }
        return classesJson;
    }

    /**
     * Retourne la classe de base du champion. Fallback par rôle si le champion est inconnu.
     */
    public static String getBaseClass(String championName, String role) {
        if (championName == null || championName.isBlank()) return fallbackByRole(role);
        JSONObject json = loadClassesJson();
        String lowerName = championName.toLowerCase().trim();

        for (String cls : new String[]{
                ScoreCalculator.COMBATTANT_ECLAIR, ScoreCalculator.COMBATTANT,
                ScoreCalculator.TANK, ScoreCalculator.ENCHANTER,
                ScoreCalculator.ASSASSIN, ScoreCalculator.MAGE, ScoreCalculator.ADC
        }) {
            JSONArray list = json.optJSONArray(cls);
            if (list == null) continue;
            for (int i = 0; i < list.length(); i++) {
                if (list.getString(i).toLowerCase().equals(lowerName)) return cls;
            }
        }
        return fallbackByRole(role);
    }

    /**
     * Retourne la classe résolue + le scaling_factor pour ce champion dans ce contexte.
     */
    public static ResolvedProfile resolveProfile(String champName, String role,
                                                  MatchDataExtractor.PlayerContext ctx) {
        String champClass = getBaseClass(champName, role);
        double sf = computeScalingFactor(champName, champClass);
        return new ResolvedProfile(champClass, sf);
    }

    private static double computeScalingFactor(String champName, String champClass) {
        if (champName != null) {
            Double override = CHAMPION_SCALING_OVERRIDE.get(champName.toLowerCase().trim());
            if (override != null) return override;
        }
        return CLASS_SCALING.getOrDefault(champClass, 0.30);
    }

    private static String fallbackByRole(String role) {
        if (role == null) return ScoreCalculator.COMBATTANT;
        return switch (role.toUpperCase()) {
            case "SUPPORT", "UTILITY" -> ScoreCalculator.ENCHANTER;
            case "JUNGLE"             -> ScoreCalculator.COMBATTANT;
            case "BOTTOM"             -> ScoreCalculator.ADC;
            case "MIDDLE"             -> ScoreCalculator.MAGE;
            default                   -> ScoreCalculator.COMBATTANT;
        };
    }
}
