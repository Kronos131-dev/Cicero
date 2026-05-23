package org.example.service;

import org.example.service.strategy.ScoringFactory;
import org.example.service.strategy.ScoringStrategy;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

import static org.example.service.ScoringConfig.Global.*;
import static org.example.service.ScoringConfig.Global.Floor.*;

public class ScoreCalculator {

    public static final String COMBATTANT_ECLAIR = "COMBATTANT_ECLAIR";
    public static final String COMBATTANT = "COMBATTANT";
    public static final String TANK = "TANK";
    public static final String ENCHANTER = "ENCHANTER";
    public static final String ASSASSIN = "ASSASSIN";
    public static final String MAGE = "MAGE";
    public static final String ADC = "ADC";

    public static class RoleBenchmarks {
        public double expectedCsPerMin;
        public double expectedVisionPerMin;
        public double expectedControlWards;
        public double expectedKda;
        public double expectedDodgesPerMin;
        public double expectedSoloKills;
        public double expectedPlates;
        public double expectedDpm;
    }

    // Load champion classes securely via DataLoader
    private static final Map<String, String> CHAMPION_CLASSES = DataLoader.loadChampionClasses();

    public static String getChampionClass(String championName, String role) {
        String cleanName = championName.toLowerCase().trim();
        if (cleanName.contains("nunu")) return TANK;
        if (cleanName.contains("renata")) return ENCHANTER;

        String champClass = CHAMPION_CLASSES.get(cleanName);
        if (champClass != null) return champClass;

        return switch (role.toUpperCase()) {
            case "BOTTOM", "ADC" -> ADC;
            case "UTILITY", "SUPPORT" -> ENCHANTER;
            case "MIDDLE", "MID" -> MAGE;
            case "JUNGLE", "TOP" -> COMBATTANT;
            default -> COMBATTANT;
        };
    }

    /**
     * NOUVELLE STRUCTURE : Optimisée pour fournir un JSON clair à l'Analyste IA
     */
    public static class ScoreResult {
        public double totalScore = 0.0;

        public JSONArray pillarsJson = new JSONArray();
        public JSONArray macroInfoJson = new JSONArray();
        public JSONArray synergiesJson = new JSONArray();

        public ScoreResult() {}

        // Ajoute un pilier et calcule automatiquement la moyenne pondérée
        public void setPillar(String name, double rawScore, double weight, String reason) {
            JSONObject pillar = new JSONObject();
            pillar.put("name", name);
            pillar.put("score", (int) rawScore);
            pillar.put("weight", weight);
            pillar.put("reason", reason);
            pillarsJson.put(pillar);

            this.totalScore += (rawScore * weight);
        }

        public void addSynergy(double points, String reason) {
            if (points == 0) return;
            this.totalScore += points;
            JSONObject syn = new JSONObject();
            syn.put("points", points);
            syn.put("reason", reason);
            synergiesJson.put(syn);
        }

        public void addMacroInfo(String info) {
            macroInfoJson.put(info);
        }

        public void setFloor(double minimumScore, String reason) {
            if (this.totalScore < minimumScore) {
                this.totalScore = minimumScore;
                JSONObject syn = new JSONObject();
                syn.put("points", 0);
                syn.put("reason", "SAUVETAGE : " + reason + " (Note remontée à " + minimumScore + ")");
                synergiesJson.put(syn);
            }
        }
    }

    public static JSONObject analyzePlayer(JSONObject player, String gameTier, double gameDurationMin, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        // Load benchmarks securely via DataLoader instead of passing it as an argument
        JSONObject benchmarks = DataLoader.loadBenchmarks();
        
        String rankKey = (gameTier == null || gameTier.isEmpty() || gameTier.equalsIgnoreCase("UNRANKED"))
                ? "GOLD" : gameTier.toUpperCase();

        String rawRole = player.optString("role", "TOP").toUpperCase();

        // Mapping Interne (Pour le Switch Java)
        String internalRole = rawRole;
        if (internalRole.equals("MID")) internalRole = "MIDDLE";
        if (internalRole.equals("BOT") || internalRole.equals("ADC")) internalRole = "BOTTOM";
        if (internalRole.equals("SUP") || internalRole.equals("SUPPORT") || internalRole.equals("UTILITY")) internalRole = "SUPPORT";

        // Mapping JSON (Pour lire benchmarks.json correctement)
        String jsonRoleKey = internalRole;
        if (internalRole.equals("MIDDLE")) jsonRoleKey = "MID";
        if (internalRole.equals("BOTTOM")) jsonRoleKey = "ADC";

        String champName = player.optString("champion", player.optString("championName", ""));
        String initialChampClass = getChampionClass(champName, internalRole);
        
        // Override de la classe en fonction du build
        String champClass = ItemClassifier.reclassifyChampionByBuild(ctx.itemIds, initialChampClass);

        ScoreResult res = new ScoreResult();

        JSONObject roleBenchmarksJson = benchmarks.optJSONObject(jsonRoleKey);
        if (roleBenchmarksJson == null) roleBenchmarksJson = new JSONObject();

        // --- CHARGEMENT DYNAMIQUE DE TOUT LE BENCHMARK ---
        RoleBenchmarks bench = new RoleBenchmarks();
        bench.expectedCsPerMin = roleBenchmarksJson.optJSONObject("cs_per_min") != null ? roleBenchmarksJson.getJSONObject("cs_per_min").optDouble(rankKey, 5.0) : 5.0;
        bench.expectedVisionPerMin = roleBenchmarksJson.optJSONObject("vision_per_min") != null ? roleBenchmarksJson.getJSONObject("vision_per_min").optDouble(rankKey, 1.0) : 1.0;
        bench.expectedControlWards = roleBenchmarksJson.optJSONObject("control_wards") != null ? roleBenchmarksJson.getJSONObject("control_wards").optDouble(rankKey, 1.0) : 1.0;
        bench.expectedKda = roleBenchmarksJson.optJSONObject("kda") != null ? roleBenchmarksJson.getJSONObject("kda").optDouble(rankKey, 2.5) : 2.5;
        bench.expectedDodgesPerMin = roleBenchmarksJson.optJSONObject("skillshots_dodged_per_min") != null ? roleBenchmarksJson.getJSONObject("skillshots_dodged_per_min").optDouble(rankKey, 1.0) : 1.0;
        bench.expectedSoloKills = roleBenchmarksJson.optJSONObject("solo_kills") != null ? roleBenchmarksJson.getJSONObject("solo_kills").optDouble(rankKey, 1.0) : 1.0;
        bench.expectedPlates = roleBenchmarksJson.optJSONObject("turret_plates") != null ? roleBenchmarksJson.getJSONObject("turret_plates").optDouble(rankKey, 2.0) : 2.0;
        bench.expectedDpm = roleBenchmarksJson.optJSONObject("expected_dpm") != null ? roleBenchmarksJson.getJSONObject("expected_dpm").optDouble(rankKey, 400.0) : 400.0;

        // --- AJUSTEMENT DYNAMIQUE DES BENCHMARKS ---
        double dpmMultiplier = 1.0 + (enemyComp.tankiness * 0.25);
        double kdaMultiplier = 1.0 + (enemyComp.burstThreat * 0.15);
        bench.expectedKda *= kdaMultiplier;
        bench.expectedDpm *= dpmMultiplier;

        // Use Strategy Pattern instead of switch
        ScoringStrategy strategy = ScoringFactory.getStrategy(internalRole);
        strategy.calculate(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);

        applyGlobalRules(res, player);

        JSONObject output = new JSONObject();
        output.put("math_score", (int) res.totalScore);
        output.put("champion_class", champClass);
        if (!champClass.equals(initialChampClass)) {
            output.put("original_champion_class", initialChampClass);
        }
        output.put("pillars", res.pillarsJson);
        output.put("macro_info", res.macroInfoJson);
        output.put("synergies", res.synergiesJson);

        return output;
    }

    private static void applyGlobalRules(ScoreResult res, JSONObject player) {
        int kills = player.optInt("k", 0);
        int deaths = player.optInt("d", 0);
        int assists = player.optInt("a", 0);
        double kda = deaths == 0 ? (kills + assists) : (double) (kills + assists) / deaths;

        JSONObject adv = player.optJSONObject("advanced");
        double kp = adv != null ? adv.optDouble("kp_percent", 0) : 0;

        if (kda >= EXCEPTIONAL_KDA && kp >= EXCEPTIONAL_KP) res.setFloor(EXCEPTIONAL_FLOOR, "Performance globale exceptionnelle (KDA & Présence)");
        else if (kda >= SOLID_KDA && kp >= SOLID_KP) res.setFloor(SOLID_FLOOR, "Solide contribution d'équipe");

        if (res.totalScore >= SOFT_CAP_THRESHOLD) {
            res.totalScore = SOFT_CAP_THRESHOLD + (Math.log10(res.totalScore - (SOFT_CAP_THRESHOLD - 1)) * SOFT_CAP_LOG_FACTOR);
        }

        if (res.totalScore < MIN_SCORE) res.totalScore = MIN_SCORE;
        if (res.totalScore > MAX_SCORE) res.totalScore = MAX_SCORE;
    }
}