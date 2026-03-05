package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.example.service.ScoringConstants.Global.*;
import static org.example.service.ScoringConstants.Global.Floor.*;

public class ScoreCalculator {

    public static final String COMBATTANT_ECLAIR = "COMBATTANT_ECLAIR";
    public static final String COMBATTANT = "COMBATTANT";
    public static final String TANK = "TANK";
    public static final String ENCHANTER = "ENCHANTER";
    public static final String ASSASSIN = "ASSASSIN";
    public static final String MAGE = "MAGE";
    public static final String ADC = "ADC";

    // Multiplicateurs pour le calcul du score d'invade, maintenant locaux.
    private static final double INVADE_KILL_MULTIPLIER = 1.5;
    private static final double INVADE_SCUTTLE_MULTIPLIER = 2.0;

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

    // --- LE DICTIONNAIRE DES CHAMPIONS ---
    private static final Map<String, String> CHAMPION_CLASSES = new HashMap<>();

    static {
        try (InputStream is = ScoreCalculator.class.getResourceAsStream("/champion_classes.json")) {
            if (is == null) {
                throw new RuntimeException("Cannot find champion_classes.json");
            }
            String jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonText);
            
            Iterator<String> keys = json.keys();
            while(keys.hasNext()) {
                String className = keys.next();
                JSONArray champions = json.getJSONArray(className);
                for (int i = 0; i < champions.length(); i++) {
                    String championName = champions.getString(i);
                    CHAMPION_CLASSES.put(championName.toLowerCase(), className);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load champion classes", e);
        }
    }

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

    /**
     * Calcule un score normalisé basé sur une fonction sigmoïde.
     *
     * @param actualValue   La valeur réelle obtenue par le joueur.
     * @param expectedValue La valeur attendue (benchmark).
     * @param sensitivity   La sensibilité de la courbe (écart-type approximatif).
     *                      Plus la sensibilité est faible, plus la courbe est raide autour de l'attente.
     * @return Un score entre 0 et 100 (asymptotique).
     *         - Si actual == expected -> 50
     *         - Si actual > expected -> tend vers 100
     *         - Si actual < expected -> tend vers 0
     */
    private static double calculateNormalizedScore(double actualValue, double expectedValue, double sensitivity) {
        if (sensitivity <= 0) return 50.0; // Évite la division par zéro
        
        // On utilise une sigmoïde logistique centrée sur l'attente
        // Z = (actual - expected) / sensitivity
        // Score = 100 / (1 + e^(-Z))
        
        double z = (actualValue - expectedValue) / sensitivity;
        return 100.0 / (1.0 + Math.exp(-z));
    }

    public static JSONObject analyzePlayer(JSONObject player, JSONObject benchmarks, String gameTier, double gameDurationMin, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, MatchDataExtractor.TeamCompositionProfile enemyComp) {
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
        String champClass = getChampionClass(champName, internalRole);

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
        // Si l'ennemi a beaucoup de tanks, on s'attend à plus de DPM et de tanking.
        double dpmMultiplier = 1.0 + (enemyComp.tankiness * 0.25); // Jusqu'à +25% DPM attendu vs full tank
        // Si l'ennemi a beaucoup de burst, on est plus indulgent sur le KDA (on s'attend à mourir plus).
        double kdaMultiplier = 1.0 + (enemyComp.burstThreat * 0.15); // Jusqu'à +15% KDA attendu vs full assassin
        bench.expectedKda *= kdaMultiplier;
        bench.expectedDpm *= dpmMultiplier;


        switch (internalRole) {
            case "TOP" -> calculateTopScore(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);
            case "JUNGLE" -> calculateJungleScore(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);
            case "MIDDLE" -> calculateMidScore(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);
            case "BOTTOM" -> calculateAdcScore(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);
            case "SUPPORT" -> calculateSupportScore(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);
            default -> calculateTopScore(ctx, oppCtx, champClass, bench, gameDurationMin, res, enemyComp);
        }

        applyGlobalRules(res, player);

        // NOUVEAU : Création du payload hyper-structuré pour l'Analyste IA
        JSONObject output = new JSONObject();
        output.put("math_score", (int) res.totalScore);
        output.put("champion_class", champClass);
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
            // SUPPRIMER LA LIGNE QUI AJOUTE LE MALUS DANS synergiesJson
        }

        if (res.totalScore < MIN_SCORE) res.totalScore = MIN_SCORE;
        if (res.totalScore > MAX_SCORE) res.totalScore = MAX_SCORE;
    }

    // =========================================================================
    // NOUVEAU : SYNERGIES TEMPORELLES (CLUTCH / THROW) & DOMINATION
    // =========================================================================
    private static void applyTemporalSynergies(MatchDataExtractor.PlayerContext ctx, ScoreResult res) {
        if (ctx.clutchKills > 0) {
            res.addSynergy(ctx.clutchKills * Synergies.CLUTCH_KILL_BONUS,
                    ctx.clutchKills + " kill(s) décisif(s) ayant débloqué un objectif majeur.");
        }

        if (ctx.unforcedErrorDeaths > 0) {
            // Les Throws sont maintenant déduits directement dans le pilier COMBAT, 
            // mais on garde une trace informative ici si besoin, ou on supprime si redondant.
            // Pour l'instant, on laisse l'info mais sans impact score ici car géré dans COMBAT.
            // res.addSynergy(ctx.unforcedErrorDeaths * Synergies.UNFORCED_ERROR_MALUS, ...);
        }

        if (ctx.pickOffs > 0) {
            res.addSynergy(ctx.pickOffs * Synergies.PICK_OFF_BONUS,
                    ctx.pickOffs + " cible(s) isolée(s) éliminée(s), créant une supériorité numérique.");
        }
        
        // --- BONUS TERMINATOR (STOMP) ---
        // Calcul du Net Kills (Kills - Morts)
        int netKills = ctx.kills - ctx.deaths;
        if (netKills > Synergies.TERMINATOR_NET_KILL_THRESHOLD) {
            int extraNetKills = netKills - Synergies.TERMINATOR_NET_KILL_THRESHOLD;
            double bonus = extraNetKills * Synergies.TERMINATOR_BONUS_PER_NET_KILL;
            res.addSynergy(bonus, "Mode Terminator : " + extraNetKills + " kills nets (K-D) au-delà du seuil de domination.");
        }
    }

    // =========================================================================
    // CALCULS PAR RÔLE
    // =========================================================================

    private static void calculateTopScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, RoleBenchmarks bench, double durationMin, ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {

        // --- PILIER 1 : EARLY_GAME (25%) ---
        // Juge ctx.goldDiffAt14 (vs 0) et ctx.csAt14 (vs bench.expectedCsPerMin * 14).
        double expectedCsAt14 = bench.expectedCsPerMin * 14;
        double csAt14Score = calculateNormalizedScore(ctx.csAt14, expectedCsAt14, ScoringConstants.Top.CS_AT_14_SENSITIVITY);
        double goldDiffScore = calculateNormalizedScore(ctx.goldDiffAt14, 0, ScoringConstants.Top.GOLD_SENSITIVITY);

        double pEarly = (csAt14Score * 0.5) + (goldDiffScore * 0.5);
        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Top.EARLY_WEIGHT, String.format("CS@14: %d (Att: %.0f), GoldDiff@14: %d", ctx.csAt14, expectedCsAt14, ctx.goldDiffAt14));

        // --- PILIER 2 : COMBAT (25%) ---
        // Reste basé sur KDA et KP%. Déduis directement les Throws.
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Top.KP_EXPECTED, ScoringConstants.Top.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Top.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Top.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Top.KDA_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / BOUNTY_GOLD_DIVISOR);
        
        // Déduction des Throws
        pCombat = Math.max(0, pCombat - (ctx.unforcedErrorDeaths * 8));

        res.setPillar("COMBAT", pCombat, ScoringConstants.Top.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Throws: %d", ctx.killParticipation * 100, ctx.kda, ctx.unforcedErrorDeaths));

        // --- PILIER 3 : MACRO_MAP (25%) ---
        // Laners : C'est ICI qu'on juge actualCsPerMin (CS global) et visionScoreNorm.
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Top.CS_SENSITIVITY);
        
        // Vision (Toplane standard)
        // On peut utiliser une sensibilité standard ou celle définie dans Top (si elle existe, sinon on prend une valeur par défaut raisonnable)
        // Ici on va utiliser une valeur par défaut car VISION_SENSITIVITY n'est pas explicitement dans Top
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, 1.0); // 1.0 sensitivity default

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Top.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));

        // --- PILIER 4 : CLASS_IDENTITY (25%) ---
        double pClass = BASE_SCORE;
        String classReason = "";

        double soloKillScore = calculateNormalizedScore(ctx.soloKills, bench.expectedSoloKills, ScoringConstants.Top.Carry.SOLO_KILL_SENSITIVITY);
        double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Top.Carry.DPM_SENSITIVITY);

        if (champClass.equals(TANK)) {
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Top.Tank.TANKING_EXPECTED, ScoringConstants.Top.Tank.TANKING_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Top.Tank.CC_EXPECTED, ScoringConstants.Top.Tank.CC_SENSITIVITY);
            pClass = (tankScore * ScoringConstants.Top.Tank.TANKING_WEIGHT) + (ccScore * ScoringConstants.Top.Tank.CC_WEIGHT);
            classReason = String.format("%.0f%% Tanking, %d CC", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(COMBATTANT_ECLAIR) || champClass.equals(ASSASSIN) || champClass.equals(ADC)) {
            double objScore = calculateNormalizedScore(ctx.damageDealtToObjectives, ScoringConstants.Top.Carry.OBJECTIVE_DAMAGE_EXPECTED, ScoringConstants.Top.Carry.OBJECTIVE_DAMAGE_SENSITIVITY);
            pClass = (soloKillScore * ScoringConstants.Top.Carry.SOLO_KILL_WEIGHT) + (objScore * ScoringConstants.Top.Carry.OBJECTIVE_DAMAGE_WEIGHT) + (dpmScore * ScoringConstants.Top.Carry.DPM_WEIGHT);
            classReason = String.format("%d SoloKills, Dégâts Obj: %d", ctx.soloKills, ctx.damageDealtToObjectives);

        } else {
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Top.Bruiser.TANKING_EXPECTED, ScoringConstants.Top.Bruiser.TANKING_SENSITIVITY);
            pClass = (soloKillScore * ScoringConstants.Top.Bruiser.SOLO_KILL_WEIGHT) + (dpmScore * ScoringConstants.Top.Bruiser.DPM_WEIGHT) + (tankScore * ScoringConstants.Top.Bruiser.TANKING_WEIGHT);
            classReason = String.format("%d SoloKills, %.0f DPM, %.0f%% Tanking", ctx.soloKills, ctx.damagePerMinute, ctx.damageTakenOnTeamPercentage * 100);
        }

        res.setPillar("CLASS_IDENTITY", pClass, ScoringConstants.Top.CLASS_WEIGHT, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (pEarly >= ScoringConstants.Top.TYRAN_LANE_SCORE_THRESHOLD && soloKillScore >= ScoringConstants.Top.TYRAN_SOLO_KILL_SCORE_THRESHOLD && pClass >= ScoringConstants.Top.TYRAN_CLASS_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Top.TYRAN_BONUS, "Le Tyran de la Lane (Écrasement total en 1v1 et conversion de l'avantage)");
        }
        if (ctx.sacrificialDeaths >= ScoringConstants.Top.SACRIFICIAL_DEATHS_THRESHOLD && ctx.damageDealtToObjectives >= ScoringConstants.Top.PRESSURE_OBJECTIVE_DAMAGE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Top.PRESSURE_BONUS, "Pression Asphyxiante (A attiré toute l'équipe ennemie pour faire gagner le reste de la carte)");
        }
        if (ctx.earlySoloDeaths >= ScoringConstants.Top.ABYSS_EARLY_DEATHS_THRESHOLD && pEarly <= ScoringConstants.Top.ABYSS_LANE_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Top.ABYSS_MALUS, "Gouffre Absolu (A détruit les chances de victoire de son équipe dès les 10 premières minutes)");
        }
        
        applyTemporalSynergies(ctx, res);
    }

    private static void calculateJungleScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, RoleBenchmarks bench, double durationMin, ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {

        // --- PILIER 1 : EARLY_GAME (25%) ---
        // Pour le Jungler : Juge le goldDiffAt14 et les Invades (ctx.enemyJungleKills).
        double goldScore = calculateNormalizedScore(ctx.goldDiffAt14, 0, ScoringConstants.Jungle.GOLD_SENSITIVITY);
        
        double invadeVal = (ctx.enemyJungleKills * INVADE_KILL_MULTIPLIER) + (ctx.scuttleCrabs * INVADE_SCUTTLE_MULTIPLIER);
        double invadeScore = calculateNormalizedScore(invadeVal, ScoringConstants.Jungle.INVADE_EXPECTED, ScoringConstants.Jungle.INVADE_SENSITIVITY);

        double pEarly = (goldScore * 0.6) + (invadeScore * 0.4);
        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Jungle.EARLY_WEIGHT, String.format("GoldDiff@14: %d, Invade: %d camps", ctx.goldDiffAt14, ctx.enemyJungleKills));

        // --- PILIER 2 : COMBAT (25%) ---
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Jungle.KP_EXPECTED, ScoringConstants.Jungle.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Jungle.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Jungle.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Jungle.KDA_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / BOUNTY_GOLD_DIVISOR);

        // Déduction des Throws
        pCombat = Math.max(0, pCombat - (ctx.unforcedErrorDeaths * 8));

        res.setPillar("COMBAT", pCombat, ScoringConstants.Jungle.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Throws: %d", ctx.killParticipation * 100, ctx.kda, ctx.unforcedErrorDeaths));

        // --- PILIER 3 : MACRO_MAP (25%) ---
        // Jungler : Vision globale + Dégâts objectifs (mais avec un poids réduit pour les objectifs, on privilégie la Vision).
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Jungle.VISION_SENSITIVITY);
        double controlWardsNorm = calculateNormalizedScore(ctx.controlWardsPlaced, bench.expectedControlWards, ScoringConstants.Jungle.CONTROL_WARDS_SENSITIVITY);
        double baseVisionScore = (visionScoreNorm * ScoringConstants.Jungle.VISION_SCORE_WEIGHT) + (controlWardsNorm * ScoringConstants.Jungle.CONTROL_WARDS_WEIGHT);
        
        // Dégâts objectifs (générique pour tous les junglers ici, affiné dans Class Identity si Fighter)
        // On utilise une attente moyenne générique si non définie spécifiquement, disons 10000
        double objScore = calculateNormalizedScore(ctx.damageDealtToObjectives, 10000.0, 5000.0);

        double pMacro = (baseVisionScore * 0.7) + (objScore * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Jungle.MACRO_WEIGHT, String.format("Vis/m: %.1f, Dégâts Obj: %d", actualVisionPerMin, ctx.damageDealtToObjectives));

        // --- PILIER 4 : CLASS_IDENTITY (25%) ---
        double pClass = BASE_SCORE;
        String classReason = "";

        if (champClass.equals(TANK)) {
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Jungle.Tank.TANKING_EXPECTED, ScoringConstants.Jungle.Tank.TANKING_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Jungle.Tank.CC_EXPECTED, ScoringConstants.Jungle.Tank.CC_SENSITIVITY);
            pClass = (baseVisionScore * ScoringConstants.Jungle.Tank.VISION_WEIGHT) + (tankScore * ScoringConstants.Jungle.Tank.TANKING_WEIGHT) + (ccScore * ScoringConstants.Jungle.Tank.CC_WEIGHT);
            classReason = String.format("%.0f%% Tanking, %d CC, Vis/m: %.1f", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations, actualVisionPerMin);

        } else if (champClass.equals(ASSASSIN)) {
            double soloKillScore = calculateNormalizedScore(ctx.soloKills, bench.expectedSoloKills, ScoringConstants.Jungle.Assassin.SOLO_KILL_SENSITIVITY);
            double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Jungle.Assassin.DPM_SENSITIVITY);
            pClass = (soloKillScore * ScoringConstants.Jungle.Assassin.SOLO_KILL_WEIGHT) + (dpmScore * ScoringConstants.Jungle.Assassin.DPM_WEIGHT) + (baseVisionScore * ScoringConstants.Jungle.Assassin.VISION_WEIGHT);
            classReason = String.format("%d SoloKills, DPM: %.0f", ctx.soloKills, ctx.damagePerMinute);

        } else if (champClass.equals(ENCHANTER)) {
            double healScore = calculateNormalizedScore(ctx.effectiveHealAndShielding, ScoringConstants.Jungle.Enchanter.HEAL_EXPECTED, ScoringConstants.Jungle.Enchanter.HEAL_SENSITIVITY);
            double saveScore = calculateNormalizedScore(ctx.saveAllyFromDeath, ScoringConstants.Jungle.Enchanter.SAVE_ALLY_EXPECTED, ScoringConstants.Jungle.Enchanter.SAVE_ALLY_SENSITIVITY);
            pClass = (baseVisionScore * ScoringConstants.Jungle.Enchanter.VISION_WEIGHT) + (healScore * ScoringConstants.Jungle.Enchanter.HEAL_WEIGHT) + (saveScore * ScoringConstants.Jungle.Enchanter.SAVE_ALLY_WEIGHT);
            classReason = String.format("%d Sauvetages, Vis/m: %.1f", ctx.saveAllyFromDeath, actualVisionPerMin);

        } else {
            double objScoreFighter = calculateNormalizedScore(ctx.damageDealtToObjectives, ScoringConstants.Jungle.Fighter.OBJECTIVE_DAMAGE_EXPECTED, ScoringConstants.Jungle.Fighter.OBJECTIVE_DAMAGE_SENSITIVITY);
            double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Jungle.Fighter.DPM_SENSITIVITY);
            pClass = (objScoreFighter * ScoringConstants.Jungle.Fighter.OBJECTIVE_DAMAGE_WEIGHT) + (dpmScore * ScoringConstants.Jungle.Fighter.DPM_WEIGHT) + (baseVisionScore * ScoringConstants.Jungle.Fighter.VISION_WEIGHT);
            classReason = String.format("Dégâts Obj: %d, DPM: %.0f", ctx.damageDealtToObjectives, ctx.damagePerMinute);
        }

        res.setPillar("CLASS_IDENTITY", pClass, ScoringConstants.Jungle.CLASS_WEIGHT, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (ctx.enemyJungleKills >= (bench.expectedCsPerMin * 3) && ctx.goldDiffAt14 >= ScoringConstants.Jungle.SMOTHER_GOLD_DIFF_THRESHOLD) {
            res.addSynergy(ScoringConstants.Jungle.SMOTHER_BONUS, "L'Étouffeur (Domination totale de la carte et privation de ressources du jungler adverse)");
        }
        if (ctx.throwDeaths >= ScoringConstants.Jungle.NO_SMITE_THROW_DEATHS_THRESHOLD) {
            res.addSynergy(ScoringConstants.Jungle.NO_SMITE_MALUS, "Absence de Smite (Morts isolées offrant des objectifs gratuits à l'ennemi)");
        }
        
        applyTemporalSynergies(ctx, res);
    }

    private static void calculateMidScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, RoleBenchmarks bench, double durationMin, ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {

        // --- PILIER 1 : EARLY_GAME (25%) ---
        double expectedCsAt14 = bench.expectedCsPerMin * 14;
        double csAt14Score = calculateNormalizedScore(ctx.csAt14, expectedCsAt14, ScoringConstants.Mid.CS_AT_14_SENSITIVITY);
        double goldDiffScore = calculateNormalizedScore(ctx.goldDiffAt14, 0, ScoringConstants.Mid.GOLD_SENSITIVITY);

        double pEarly;
        if (champClass.equals(ASSASSIN)) {
            pEarly = (csAt14Score * ScoringConstants.Mid.Assassin.CS_SCORE_WEIGHT) + (goldDiffScore * ScoringConstants.Mid.Assassin.GOLD_SCORE_WEIGHT);
        } else {
            pEarly = (csAt14Score * ScoringConstants.Mid.Default.CS_SCORE_WEIGHT) + (goldDiffScore * ScoringConstants.Mid.Default.GOLD_SCORE_WEIGHT);
        }
        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Mid.EARLY_WEIGHT, String.format("CS@14: %d (Att: %.0f), GoldDiff@14: %d", ctx.csAt14, expectedCsAt14, ctx.goldDiffAt14));

        // --- PILIER 2 : COMBAT (25%) ---
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Mid.KDA_SENSITIVITY);
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Mid.KP_EXPECTED, ScoringConstants.Mid.KP_SENSITIVITY);

        double pCombat = (kdaScore * ScoringConstants.Mid.KDA_SCORE_WEIGHT) + (kpScore * ScoringConstants.Mid.KP_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / BOUNTY_GOLD_DIVISOR);
        
        // Déduction des Throws
        pCombat = Math.max(0, pCombat - (ctx.unforcedErrorDeaths * 8));

        res.setPillar("COMBAT", pCombat, ScoringConstants.Mid.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Throws: %d", ctx.killParticipation * 100, ctx.kda, ctx.unforcedErrorDeaths));

        // --- PILIER 3 : MACRO_MAP (25%) ---
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Mid.CS_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, 1.0);

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Mid.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));

        // --- PILIER 4 : CLASS_IDENTITY (25%) ---
        double pClass = BASE_SCORE;
        String classReason = "";

        double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Mid.Mage.DPM_SENSITIVITY);
        double soloKillScore = calculateNormalizedScore(ctx.soloKills, bench.expectedSoloKills, ScoringConstants.Mid.Assassin.SOLO_KILL_SENSITIVITY);

        if (ctx.championName.equalsIgnoreCase("GALIO")) {
            // Galio Exception: Tanking (50%) et CC (50%)
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Mid.Tank.TANKING_EXPECTED, ScoringConstants.Mid.Tank.TANKING_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Mid.Tank.CC_EXPECTED, ScoringConstants.Mid.Tank.CC_SENSITIVITY);
            pClass = (tankScore * 0.5) + (ccScore * 0.5);
            classReason = String.format("Galio - %.0f%% Tanking, %d CC", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(MAGE)) {
            double teamDmgScore = calculateNormalizedScore(ctx.teamDamagePercentage, ScoringConstants.Mid.Mage.TEAM_DAMAGE_EXPECTED, ScoringConstants.Mid.Mage.TEAM_DAMAGE_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Mid.Mage.CC_EXPECTED, ScoringConstants.Mid.Mage.CC_SENSITIVITY);
            pClass = (dpmScore * ScoringConstants.Mid.Mage.DPM_WEIGHT) + (teamDmgScore * ScoringConstants.Mid.Mage.TEAM_DAMAGE_WEIGHT) + (ccScore * ScoringConstants.Mid.Mage.CC_WEIGHT);
            classReason = String.format("DPM: %.0f, %d CC", ctx.damagePerMinute, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(ASSASSIN)) {
            double roamScore = calculateNormalizedScore(ctx.earlyRoamTakedowns, ScoringConstants.Mid.Assassin.ROAM_EXPECTED, ScoringConstants.Mid.Assassin.ROAM_SENSITIVITY);
            double teamDmgScore = calculateNormalizedScore(ctx.teamDamagePercentage, ScoringConstants.Mid.Assassin.TEAM_DAMAGE_EXPECTED, ScoringConstants.Mid.Assassin.TEAM_DAMAGE_SENSITIVITY);

            pClass = (soloKillScore * ScoringConstants.Mid.Assassin.SOLO_KILL_WEIGHT) + (teamDmgScore * ScoringConstants.Mid.Assassin.TEAM_DAMAGE_WEIGHT) + (roamScore * ScoringConstants.Mid.Assassin.ROAM_WEIGHT);
            classReason = String.format("%d SoloK, %d Roams, %.0f%% Dégâts", ctx.soloKills, ctx.earlyRoamTakedowns, ctx.teamDamagePercentage * 100);

        } else if (champClass.equals(COMBATTANT_ECLAIR)) {
            double objScore = calculateNormalizedScore(ctx.damageDealtToObjectives, ScoringConstants.Mid.ScalingCarry.OBJECTIVE_DAMAGE_EXPECTED, ScoringConstants.Mid.ScalingCarry.OBJECTIVE_DAMAGE_SENSITIVITY);
            pClass = (dpmScore * ScoringConstants.Mid.ScalingCarry.DPM_WEIGHT) + (objScore * ScoringConstants.Mid.ScalingCarry.OBJECTIVE_DAMAGE_WEIGHT) + (soloKillScore * ScoringConstants.Mid.ScalingCarry.SOLO_KILL_WEIGHT);
            classReason = String.format("DPM: %.0f, Dégâts Obj: %d", ctx.damagePerMinute, ctx.damageDealtToObjectives);

        } else if (champClass.equals(COMBATTANT)) {
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Mid.Bruiser.TANKING_EXPECTED, ScoringConstants.Mid.Bruiser.TANKING_SENSITIVITY);
            pClass = (dpmScore * ScoringConstants.Mid.Bruiser.DPM_WEIGHT) + (tankScore * ScoringConstants.Mid.Bruiser.TANKING_WEIGHT) + (soloKillScore * ScoringConstants.Mid.Bruiser.SOLO_KILL_WEIGHT);
            classReason = String.format("%.0f DPM, %.0f%% Tanking, %d SoloKills", ctx.damagePerMinute, ctx.damageTakenOnTeamPercentage * 100, ctx.soloKills);

        } else if (champClass.equals(TANK)) {
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Mid.Tank.TANKING_EXPECTED, ScoringConstants.Mid.Tank.TANKING_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Mid.Tank.CC_EXPECTED, ScoringConstants.Mid.Tank.CC_SENSITIVITY);
            pClass = (tankScore * 0.5) + (ccScore * 0.5);
            classReason = String.format("%.0f%% Tanking, %d CC", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(ENCHANTER)) {
            double healScore = calculateNormalizedScore(ctx.effectiveHealAndShielding, ScoringConstants.Mid.Enchanter.HEAL_EXPECTED, ScoringConstants.Mid.Enchanter.HEAL_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Mid.Enchanter.CC_EXPECTED, ScoringConstants.Mid.Enchanter.CC_SENSITIVITY);
            pClass = (dpmScore * ScoringConstants.Mid.Enchanter.DPM_WEIGHT) + (healScore * ScoringConstants.Mid.Enchanter.HEAL_WEIGHT) + (ccScore * ScoringConstants.Mid.Enchanter.CC_WEIGHT);
            classReason = String.format("DPM: %.0f, %.0f Heal/Shield", ctx.damagePerMinute, ctx.effectiveHealAndShielding);

        } else {
            pClass = dpmScore;
            classReason = String.format("DPM: %.0f", ctx.damagePerMinute);
        }
        res.setPillar("CLASS_IDENTITY", pClass, ScoringConstants.Mid.CLASS_WEIGHT, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (champClass.equals(ASSASSIN) && ctx.earlyRoamTakedowns >= ScoringConstants.Mid.TERROR_ROAM_TAKEDOWNS_THRESHOLD && pEarly >= ScoringConstants.Mid.TERROR_LANE_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Mid.TERROR_BONUS, "Terreur Globale (Roams dévastateurs sans sacrifier sa propre lane)");
        }
        if ((champClass.equals(MAGE) || champClass.equals(COMBATTANT_ECLAIR)) && csScore >= ScoringConstants.Mid.HYPERSCALING_CS_SCORE_THRESHOLD && dpmScore >= ScoringConstants.Mid.HYPERSCALING_DPM_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Mid.HYPERSCALING_BONUS, "Hyper-Scaling validé (Conversion parfaite de l'or en dégâts)");
        }
        if (pEarly >= ScoringConstants.Mid.STERILE_LANE_SCORE_THRESHOLD && pCombat <= ScoringConstants.Mid.STERILE_COMBAT_SCORE_THRESHOLD && pClass <= ScoringConstants.Mid.STERILE_CLASS_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Mid.STERILE_MALUS, "Avantage Stérile (A gagné sa lane mais n'a eu aucun impact sur la partie)");
        }
        
        applyTemporalSynergies(ctx, res);
    }

    private static void calculateAdcScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, RoleBenchmarks bench, double durationMin, ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {

        // --- PILIER 1 : EARLY_GAME (25%) ---
        double expectedCsAt14 = bench.expectedCsPerMin * 14;
        double csAt14Score = calculateNormalizedScore(ctx.csAt14, expectedCsAt14, ScoringConstants.Adc.CS_AT_14_SENSITIVITY);
        double goldDiffScore = calculateNormalizedScore(ctx.goldDiffAt14, 0, ScoringConstants.Adc.GOLD_SENSITIVITY);

        double pEarly = (csAt14Score * ScoringConstants.Adc.CS_SCORE_WEIGHT) + (goldDiffScore * ScoringConstants.Adc.GOLD_SCORE_WEIGHT);
        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Adc.EARLY_WEIGHT, String.format("CS@14: %d (Att: %.0f), GoldDiff@14: %d", ctx.csAt14, expectedCsAt14, ctx.goldDiffAt14));

        // --- PILIER 2 : COMBAT (25%) ---
        double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Adc.DPM_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Adc.KDA_SENSITIVITY);
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Adc.KP_EXPECTED, ScoringConstants.Adc.KP_SENSITIVITY);

        double pCombat = (dpmScore * ScoringConstants.Adc.DPM_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Adc.KDA_SCORE_WEIGHT) + (kpScore * ScoringConstants.Adc.KP_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / BOUNTY_GOLD_DIVISOR);

        // Déduction des Throws
        pCombat = Math.max(0, pCombat - (ctx.unforcedErrorDeaths * 8));

        res.setPillar("COMBAT", pCombat, ScoringConstants.Adc.COMBAT_WEIGHT, String.format("DPM: %.0f, KDA: %.2f, Throws: %d", ctx.damagePerMinute, ctx.kda, ctx.unforcedErrorDeaths));

        // --- PILIER 3 : MACRO_MAP (25%) ---
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Adc.CS_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Adc.VISION_SENSITIVITY);

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Adc.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));

        // --- PILIER 4 : CLASS_IDENTITY (25%) ---
        double objScore = calculateNormalizedScore(ctx.damageDealtToObjectives, ScoringConstants.Adc.OBJECTIVE_DAMAGE_EXPECTED, ScoringConstants.Adc.OBJECTIVE_DAMAGE_SENSITIVITY);
        
        // On réutilise visionScoreNorm ici car c'est dans la logique originale de CLASS_IDENTITY pour ADC
        // Mais pour éviter la redondance avec MACRO_MAP, on pourrait ajuster. 
        // Cependant, la consigne est de conserver EXACTEMENT les blocs if (champClass...)
        
        double pClass = BASE_SCORE;
        if (champClass.equals(MAGE)) {
            pClass = (objScore * ScoringConstants.Adc.Mage.OBJECTIVE_DAMAGE_WEIGHT) + (visionScoreNorm * ScoringConstants.Adc.Mage.VISION_SCORE_WEIGHT);
        } else {
            pClass = (objScore * ScoringConstants.Adc.Default.OBJECTIVE_DAMAGE_WEIGHT) + (visionScoreNorm * ScoringConstants.Adc.Default.VISION_SCORE_WEIGHT);
        }
        res.setPillar("CLASS_IDENTITY", pClass, ScoringConstants.Adc.CLASS_WEIGHT, String.format("Dégâts Tours: %d, Vis/m: %.1f", ctx.damageDealtToObjectives, actualVisionPerMin));

        // --- SYNERGIES ET MALUS CRITIQUES ---
        if (pCombat >= ScoringConstants.Adc.GLASS_CANNON_COMBAT_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Adc.GLASS_CANNON_BONUS, "Glass Cannon Parfait (Dégâts massifs)");
        }

        if (pEarly >= ScoringConstants.Adc.FARM_SIMULATOR_LANE_SCORE_THRESHOLD && pCombat <= ScoringConstants.Adc.FARM_SIMULATOR_COMBAT_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Adc.FARM_SIMULATOR_MALUS, "KDA Player / Farm Simulator (Beaucoup de ressources pour un impact nul)");
        }
        
        applyTemporalSynergies(ctx, res);
    }

    private static void calculateSupportScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, RoleBenchmarks bench, double durationMin, ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {

        // --- PILIER 1 : EARLY_GAME (25%) ---
        // Support : Gold Diff @ 14 (et CS @ 14 qui devrait être proche de 0 ou du support item, mais on se focus sur Gold Diff)
        double goldScore = calculateNormalizedScore(ctx.goldDiffAt14, 0, ScoringConstants.Support.GOLD_SENSITIVITY);
        double pEarly = goldScore;

        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Support.EARLY_WEIGHT, String.format("Diff 14m: %dg", ctx.goldDiffAt14));

        // --- PILIER 2 : COMBAT (25%) ---
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Support.KP_EXPECTED, ScoringConstants.Support.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Support.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Support.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Support.KDA_SCORE_WEIGHT);
        
        // Déduction des Throws
        pCombat = Math.max(0, pCombat - (ctx.unforcedErrorDeaths * 8));

        res.setPillar("COMBAT", pCombat, ScoringConstants.Support.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Throws: %d", ctx.killParticipation * 100, ctx.kda, ctx.unforcedErrorDeaths));

        // --- PILIER 3 : MACRO_MAP (25%) ---
        // Support : Vision & Control Wards
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Support.VISION_SENSITIVITY);
        double controlWardsNorm = calculateNormalizedScore(ctx.controlWardsPlaced, bench.expectedControlWards, ScoringConstants.Support.CONTROL_WARDS_SENSITIVITY);

        double pMacro = (visionScoreNorm * ScoringConstants.Support.VISION_SCORE_WEIGHT) + (controlWardsNorm * ScoringConstants.Support.CONTROL_WARDS_WEIGHT);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Support.MACRO_WEIGHT, String.format("Vis/m: %.1f, Pinks: %d", actualVisionPerMin, ctx.controlWardsPlaced));

        // --- PILIER 4 : CLASS_IDENTITY (25%) ---
        double pClass = BASE_SCORE;
        String classReason = "";

        String cleanName = ctx.championName.toLowerCase();

        // --- CAS SPÉCIAUX (Rakan, Bard, Senna, Pyke/Pantheon) ---
        if (cleanName.contains("rakan")) {
            double healScore = calculateNormalizedScore(ctx.effectiveHealAndShielding, ScoringConstants.Support.Rakan.HEAL_EXPECTED, ScoringConstants.Support.Rakan.HEAL_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Support.Rakan.CC_EXPECTED, ScoringConstants.Support.Rakan.CC_SENSITIVITY);
            pClass = (visionScoreNorm * ScoringConstants.Support.Rakan.VISION_WEIGHT) + (ccScore * ScoringConstants.Support.Rakan.CC_WEIGHT) + (healScore * ScoringConstants.Support.Rakan.HEAL_WEIGHT);
            classReason = String.format("Rakan Hybrid - Vis/m: %.1f, CC: %d, Heal: %.0f", actualVisionPerMin, ctx.enemyChampionImmobilizations, ctx.effectiveHealAndShielding);

        } else if (cleanName.contains("bard")) {
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Support.Bard.CC_EXPECTED, ScoringConstants.Support.Bard.CC_SENSITIVITY);
            double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Support.Bard.DPM_SENSITIVITY);
            pClass = (visionScoreNorm * ScoringConstants.Support.Bard.VISION_WEIGHT) + (ccScore * ScoringConstants.Support.Bard.CC_WEIGHT) + (dpmScore * ScoringConstants.Support.Bard.DPM_WEIGHT);
            classReason = String.format("Bard Roam - Vis/m: %.1f, CC: %d, DPM: %.0f", actualVisionPerMin, ctx.enemyChampionImmobilizations, ctx.damagePerMinute);

        } else if (cleanName.contains("senna")) {
            double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Support.Senna.DPM_SENSITIVITY);
            double healScore = calculateNormalizedScore(ctx.effectiveHealAndShielding, ScoringConstants.Support.Senna.HEAL_EXPECTED, ScoringConstants.Support.Senna.HEAL_SENSITIVITY);
            pClass = (visionScoreNorm * ScoringConstants.Support.Senna.VISION_WEIGHT) + (dpmScore * ScoringConstants.Support.Senna.DPM_WEIGHT) + (healScore * ScoringConstants.Support.Senna.HEAL_WEIGHT);
            classReason = String.format("Senna Hybrid - Vis/m: %.1f, DPM: %.0f, Heal: %.0f", actualVisionPerMin, ctx.damagePerMinute, ctx.effectiveHealAndShielding);

        } else if (cleanName.contains("pyke") || cleanName.contains("pantheon")) {
            double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Support.Assassin.DPM_SENSITIVITY);
            double kdaValScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Support.KDA_SENSITIVITY);
            pClass = (visionScoreNorm * ScoringConstants.Support.Assassin.VISION_WEIGHT) + (kdaValScore * ScoringConstants.Support.Assassin.KDA_WEIGHT) + (dpmScore * ScoringConstants.Support.Assassin.DPM_WEIGHT);
            classReason = String.format("Carry Support - Vis/m: %.1f, KDA: %.2f, DPM: %.0f", actualVisionPerMin, ctx.kda, ctx.damagePerMinute);

        } else if (champClass.equals(ENCHANTER)) {
            double healScore = calculateNormalizedScore(ctx.effectiveHealAndShielding, ScoringConstants.Support.Enchanter.HEAL_EXPECTED, ScoringConstants.Support.Enchanter.HEAL_SENSITIVITY);
            double saveScore = calculateNormalizedScore(ctx.saveAllyFromDeath, ScoringConstants.Support.Enchanter.SAVE_ALLY_EXPECTED, ScoringConstants.Support.Enchanter.SAVE_ALLY_SENSITIVITY);

            pClass = (visionScoreNorm * ScoringConstants.Support.Enchanter.VISION_WEIGHT) + (healScore * ScoringConstants.Support.Enchanter.HEAL_WEIGHT) + (saveScore * ScoringConstants.Support.Enchanter.SAVE_ALLY_WEIGHT);
            classReason = String.format("Vis/m: %.1f, %.0f Heal/Shield, %d Sauvetages", actualVisionPerMin, ctx.effectiveHealAndShielding, ctx.saveAllyFromDeath);
        } else if (champClass.equals(TANK)) {
            double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, ScoringConstants.Support.Tank.TANKING_EXPECTED, ScoringConstants.Support.Tank.TANKING_SENSITIVITY);
            double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Support.Tank.CC_EXPECTED, ScoringConstants.Support.Tank.CC_SENSITIVITY);
            pClass = (visionScoreNorm * ScoringConstants.Support.Tank.VISION_WEIGHT) + (tankScore * ScoringConstants.Support.Tank.TANKING_WEIGHT) + (ccScore * ScoringConstants.Support.Tank.CC_WEIGHT);
            classReason = String.format("Vis/m: %.1f, %.0f%% Tanking, %d CC", actualVisionPerMin, ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else {
            double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Support.Mage.DPM_SENSITIVITY);
            pClass = (visionScoreNorm * ScoringConstants.Support.Mage.VISION_WEIGHT) + (dpmScore * ScoringConstants.Support.Mage.DPM_WEIGHT);
            classReason = String.format("Vis/m: %.1f, DPM: %.0f", actualVisionPerMin, ctx.damagePerMinute);
        }

        res.setPillar("CLASS_IDENTITY", pClass, ScoringConstants.Support.CLASS_WEIGHT, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (ctx.sacrificialDeaths >= ScoringConstants.Support.BODYGUARD_SACRIFICIAL_DEATHS_THRESHOLD && pClass >= ScoringConstants.Support.BODYGUARD_CLASS_SCORE_THRESHOLD) res.addSynergy(ScoringConstants.Support.BODYGUARD_BONUS, "Garde du Corps Martyr");

        if (visionScoreNorm < ScoringConstants.Support.BLIND_VISION_SCORE_THRESHOLD && durationMin > ScoringConstants.Support.BLIND_GAME_DURATION_THRESHOLD) {
            res.addSynergy(ScoringConstants.Support.BLIND_MALUS, "Lacune de Vision Critique (Moins de la moitié du score attendu)");
        }
        
        applyTemporalSynergies(ctx, res);
    }
}