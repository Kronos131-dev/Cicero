package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

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
    }

    // --- LE DICTIONNAIRE DES CHAMPIONS ---
    private static final Map<String, String> CHAMPION_CLASSES = new HashMap<>();

    static {
        String[] combattantsEclair = {"Vladimir", "Yasuo", "Viego", "Diana", "Yone", "Gangplank", "Gwen", "Tryndamere", "Bel'Veth", "Kassadin", "Jayce"};
        for (String c : combattantsEclair) CHAMPION_CLASSES.put(c.toLowerCase(), COMBATTANT_ECLAIR);

        String[] combattants = {
                "Aatrox", "Senna", "Briar", "Camille", "Darius", "Fiora", "Garen", "Gnar", "Sylas", "Aurora", "Zac",
                "Gragas", "Urgot", "Volibear", "Illaoi", "Riven", "Irelia", "Jarvan IV", "Rumble", "Jax", "Kled", "Swain",
                "Shyvana", "Lee Sin", "Wukong", "Singed", "Mordekaiser", "Olaf", "Pantheon", "Sett", "Trundle", "Rek'Sai", "Vi",
                "Warwick", "Yorick", "Zaahen", "Renekton", "Xin Zhao"
        };
        for (String c : combattants) CHAMPION_CLASSES.put(c.toLowerCase(), COMBATTANT);

        String[] tanks = {
                "Alistar", "Sion", "Skarner", "Blitzcrank", "Amumu", "Malphite", "Leona", "Nasus", "Braum", "Maokai", "K'Sante",
                "Dr. Mundo", "Udyr", "Cho'Gath", "Galio", "Shen", "Nautilus", "Ornn", "Poppy", "Rammus", "Sejuani", "Nunu & Willump",
                "Rell", "Tahm Kench", "Zac", "Thresh", "Taric"
        };
        for (String c : tanks) CHAMPION_CLASSES.put(c.toLowerCase(), TANK);

        String[] enchanters = {
                "Bard", "Soraka", "Sona", "Ivern", "Janna", "Nami", "Renata Glasc", "Seraphine", "Milio", "Lulu", "Karma",
                "Rakan", "Zilean", "Yuumi"
        };
        for (String c : enchanters) CHAMPION_CLASSES.put(c.toLowerCase(), ENCHANTER);

        String[] assassins = {
                "Annie", "Akali", "Ahri", "Shaco", "Kha'Zix", "LeBlanc", "Rengar", "Ekko", "Elise", "Evelynn", "Fizz",
                "Kayn", "Katarina", "Zed", "Talon", "Master Yi", "Qiyana", "Pyke", "Nocturne", "Naafiri", "Vex"
        };
        for (String c : assassins) CHAMPION_CLASSES.put(c.toLowerCase(), ASSASSIN);

        String[] mages = {
                "Aurelion Sol", "Anivia", "Azir", "Lillia", "Lissandra", "Karthus", "Cassiopeia", "Brand", "Hwei", "Veigar", "Fiddlesticks",
                "Teemo", "Heimerdinger", "Xerath", "Syndra", "Zoe", "Orianna", "Zyra", "Taliyah", "Neeko", "Lux", "Ryze",
                "Malzahar", "Nidalee", "Morgana", "Veigar", "Ziggs", "Twisted Fate", "Viktor", "Vel'Koz"
        };
        for (String c : mages) CHAMPION_CLASSES.put(c.toLowerCase(), MAGE);

        String[] adcs = {
                "Akshan", "Aphelios", "Smolder", "Kalista", "Ashe", "Kai'Sa", "Caitlyn", "Jinx", "Corki", "Ezreal", "Graves",
                "Jhin", "Twitch", "Sivir", "Draven", "Miss Fortune", "Senna", "Samira", "Nilah", "Lucian", "Kayle", "Quinn",
                "Kog'Maw", "Bel'Veth", "Zeri", "Vayne", "Xayah", "Varus", "Kindred", "Tristana"
        };
        for (String c : adcs) CHAMPION_CLASSES.put(c.toLowerCase(), ADC);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double getScaledExpectedDpm(String rank, double minDpm, double maxDpm) {
        int rankIndex = switch (rank) {
            case "IRON" -> 0; case "BRONZE" -> 1; case "SILVER" -> 2; case "GOLD" -> 3;
            case "PLATINUM" -> 4; case "EMERALD" -> 5; case "DIAMOND" -> 6; case "MASTER" -> 7;
            case "GRANDMASTER" -> 8; case "CHALLENGER" -> 9; default -> 3;
        };
        return minDpm + ((maxDpm - minDpm) * (rankIndex / 9.0));
    }

    public static JSONObject analyzePlayer(JSONObject player, JSONObject benchmarks, String gameTier, double gameDurationMin, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx) {
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

        ScoreResult res = new ScoreResult(); // Ou `new ScoreResult();` selon ta version actuelle de l'objet

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

        // Calcul du DPM attendu
        double expectedDpm;
        switch (internalRole) {
            case "BOTTOM" -> expectedDpm = getScaledExpectedDpm(rankKey, 450.0, 750.0);
            case "MIDDLE" -> expectedDpm = getScaledExpectedDpm(rankKey, 400.0, 700.0);
            case "TOP" -> expectedDpm = getScaledExpectedDpm(rankKey, 350.0, 600.0);
            case "JUNGLE" -> expectedDpm = getScaledExpectedDpm(rankKey, 300.0, 550.0);
            case "SUPPORT" -> expectedDpm = getScaledExpectedDpm(rankKey, 150.0, 300.0);
            default -> expectedDpm = 400.0;
        }


        switch (internalRole) {
            case "TOP" -> calculateTopScore(ctx, oppCtx, champClass, expectedDpm, gameDurationMin, bench, res);
            case "JUNGLE" -> calculateJungleScore(ctx, oppCtx, champClass, expectedDpm, gameDurationMin, bench, res);
            case "MIDDLE" -> calculateMidScore(ctx, oppCtx, champClass, expectedDpm, gameDurationMin, bench, res);
            case "BOTTOM" -> calculateAdcScore(ctx, oppCtx, champClass, expectedDpm, gameDurationMin, bench, res);
            case "SUPPORT" -> calculateSupportScore(ctx, oppCtx, champClass, expectedDpm, gameDurationMin, bench, res);
            default -> calculateTopScore(ctx, oppCtx, champClass, expectedDpm, gameDurationMin, bench, res);
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

        if (kda >= 4.0 && kp >= 0.50) res.setFloor(70, "Performance globale exceptionnelle (KDA & Présence)");
        else if (kda >= 3.0 && kp >= 0.40) res.setFloor(55, "Solide contribution d'équipe");

        if (res.totalScore >= 95) {
            double original = res.totalScore;
            res.totalScore = 95 + (Math.log10(original - 94) * 5);
            res.addSynergy(res.totalScore - original, "Soft-Cap God Mode activé");
        }

        if (res.totalScore < 0) res.totalScore = 0;
        if (res.totalScore > 150) res.totalScore = 150;
    }

    // =========================================================================
    // CALCULS PAR RÔLE
    // =========================================================================

    private static void calculateTopScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, double expectedDpm, double durationMin, RoleBenchmarks bench, ScoreResult res) {

        // --- PILIER 1 : LANE & ÉCONOMIE (35%) ---
        // En Toplane, l'économie dicte la loi. Farm et Diff 14m.
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = 50.0 + ((actualCsPerMin - bench.expectedCsPerMin) * 20.0);

        // Le Diff Gold est extrêmement valorisé ici (Gagner sa lane = Gagner en Toplane)
        double goldScore = 50.0 + (ctx.goldDiffAt14 / 25.0);

        double pLane = clamp((csScore * 0.50) + (goldScore * 0.50), 0, 130);
        res.setPillar("LANE_ECO", pLane, 0.35, String.format("Diff 14m: %dg, CS/m: %.1f (Att: %.1f)", ctx.goldDiffAt14, actualCsPerMin, bench.expectedCsPerMin));

        // --- PILIER 2 : COMBAT & FLANK (30%) ---
        // Le Toplaner a naturellement moins de KP. 40% est un standard très solide pour eux.
        double kpScore = (ctx.killParticipation / 0.40) * 50.0;
        double kdaScore = bench.expectedKda > 0 ? (ctx.kda / bench.expectedKda) * 50.0 : 50.0;

        double pCombat = clamp((kpScore * 0.50) + (kdaScore * 0.50), 0, 120);
        if (ctx.bountyGold > 0) pCombat = clamp(pCombat + Math.min(15, ctx.bountyGold / 60.0), 0, 120);

        res.setPillar("COMBAT", pCombat, 0.30, String.format("KP: %.0f%%, KDA: %.2f (Att: %.2f)", ctx.killParticipation * 100, ctx.kda, bench.expectedKda));

        // --- PILIER 3 : IDENTITÉ DE CLASSE & PRESSION (35%) ---
        double pClass = 50.0;
        String classReason = "";

        double soloKillScore = bench.expectedSoloKills > 0 ? (ctx.soloKills / bench.expectedSoloKills) * 50.0 : 50.0;
        double dpmScore = expectedDpm > 0 ? (ctx.damagePerMinute / expectedDpm) * 50.0 : 50.0;

        if (champClass.equals(TANK)) {
            // Le Rocher : Encaissement (Tanking) et Engage (CC)
            double tankScore = (ctx.damageTakenOnTeamPercentage / 0.30) * 50.0; // 30% des dégâts subis = 50 pts
            double ccScore = ctx.enemyChampionImmobilizations * 2.0;
            pClass = clamp((tankScore * 0.60) + (ccScore * 0.40), 0, 130);
            classReason = String.format("%.0f%% Tanking, %d CC", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(COMBATTANT_ECLAIR) || champClass.equals(ASSASSIN) || champClass.equals(ADC)) {
            // Le Splitpusher / Carry (Jayce, Fiora, Vayne) : Pression de tour, Solo Kills, DPM
            double objScore = (ctx.damageDealtToObjectives / 8000.0) * 40.0;
            pClass = clamp((soloKillScore * 0.35) + (objScore * 0.40) + (dpmScore * 0.25), 0, 130);
            classReason = String.format("%d SoloKills (Att: %.1f), Dégâts Obj: %d", ctx.soloKills, bench.expectedSoloKills, ctx.damageDealtToObjectives);

        } else {
            // Le Bruiser classique (Darius, Sett) : Un mix de tanking, dégâts et domination en duel
            double tankScore = (ctx.damageTakenOnTeamPercentage / 0.25) * 40.0;
            pClass = clamp((soloKillScore * 0.35) + (dpmScore * 0.35) + (tankScore * 0.30), 0, 130);
            classReason = String.format("%d SoloKills, %.0f DPM, %.0f%% Tanking", ctx.soloKills, ctx.damagePerMinute, ctx.damageTakenOnTeamPercentage * 100);
        }

        res.setPillar("MACRO_CLASS", pClass, 0.35, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (pLane >= 90 && soloKillScore >= 80 && pClass >= 80) {
            res.addSynergy(10.0, "Le Tyran de la Lane (Écrasement total en 1v1 et conversion de l'avantage)");
        }
        if (ctx.sacrificialDeaths >= 2 && ctx.damageDealtToObjectives >= 10000) {
            res.addSynergy(12.0, "Pression Asphyxiante (A attiré toute l'équipe ennemie pour faire gagner le reste de la carte)");
        }
        if (ctx.earlySoloDeaths >= 3 && pLane <= 40) {
            res.addSynergy(-15.0, "Gouffre Absolu (A détruit les chances de victoire de son équipe dès les 10 premières minutes)");
        }
    }

    private static void calculateJungleScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, double expectedDpm, double durationMin, RoleBenchmarks bench, ScoreResult res) {

        // --- PILIER 1 : PATHING, ÉCONOMIE & CONTRÔLE EARLY (35%) ---
        // 1. Le Farming (vs Benchmark)
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = 50.0 + ((actualCsPerMin - bench.expectedCsPerMin) * 20.0);

        // 2. L'Avantage en Or Pur
        double goldScore = 50.0 + (ctx.goldDiffAt14 / 30.0);

        // 3. Le Counter-Jungle (Extrait spécifique de la timeline)
        double invadeScore = Math.min(20.0, (ctx.enemyJungleKills * 1.5) + (ctx.scuttleCrabs * 2.0));

        // On fusionne tout ça : Le CS, l'Or et l'Invade
        double pPathing = clamp((csScore * 0.40) + (goldScore * 0.40) + invadeScore, 0, 120);

        res.setPillar("PATHING_ECO", pPathing, 0.35, String.format("Diff 14m: %dg, CS/m: %.1f (Att: %.1f), Invade: %d camps", ctx.goldDiffAt14, actualCsPerMin, bench.expectedCsPerMin, ctx.enemyJungleKills));

        // --- PILIER 2 : IMPACT & PRÉSENCE (35%) ---
        // Le Jungler dicte l'early/mid game. Le KP est le roi absolu ici.
        double kpScore = (ctx.killParticipation / 0.50) * 50.0; // 50% KP = standard. 75% = excellent.
        double kdaScore = bench.expectedKda > 0 ? (ctx.kda / bench.expectedKda) * 50.0 : 50.0;

        double pCombat = clamp((kpScore * 0.60) + (kdaScore * 0.40), 0, 120);
        if (ctx.bountyGold > 0) pCombat = clamp(pCombat + Math.min(15, ctx.bountyGold / 60.0), 0, 120);

        res.setPillar("IMPACT", pCombat, 0.35, String.format("KP: %.0f%%, KDA: %.2f (Att: %.2f)", ctx.killParticipation * 100, ctx.kda, bench.expectedKda));

        // --- PILIER 3 : MACRO, VISION & IDENTITÉ DE CLASSE (30%) ---
        // Tous les junglers doivent warder. On normalise la vision d'abord.
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = bench.expectedVisionPerMin > 0 ? (actualVisionPerMin / bench.expectedVisionPerMin) * 50.0 : 50.0;
        double controlWardsNorm = bench.expectedControlWards > 0 ? (ctx.controlWardsPlaced / bench.expectedControlWards) * 50.0 : 50.0;
        double baseVisionScore = (visionScoreNorm * 0.6) + (controlWardsNorm * 0.4);

        double pClass = 50.0;
        String classReason = "";

        if (champClass.equals(TANK)) {
            // Tank : CC, Frontline, et gros poids sur la vision
            double tankScore = (ctx.damageTakenOnTeamPercentage / 0.30) * 50.0; // Doit prendre ~30% des dégâts
            double ccScore = ctx.enemyChampionImmobilizations * 2.0;
            pClass = clamp((baseVisionScore * 0.40) + (tankScore * 0.30) + (ccScore * 0.30), 0, 120);
            classReason = String.format("%.0f%% Tanking, %d CC, Vis/m: %.1f", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations, actualVisionPerMin);

        } else if (champClass.equals(ASSASSIN)) {
            // Assassin : Tuer le carry. DPM et Solokills. La vision sert à traquer.
            double soloKillScore = bench.expectedSoloKills > 0 ? (ctx.soloKills / bench.expectedSoloKills) * 50.0 : 50.0;
            double dpmScore = expectedDpm > 0 ? (ctx.damagePerMinute / expectedDpm) * 50.0 : 50.0;
            pClass = clamp((soloKillScore * 0.45) + (dpmScore * 0.35) + (baseVisionScore * 0.20), 0, 120);
            classReason = String.format("%d SoloKills (Att: %.1f), DPM: %.0f", ctx.soloKills, bench.expectedSoloKills, ctx.damagePerMinute);

        } else if (champClass.equals(ENCHANTER)) {
            // Ivern : Soins, Sauvetages, Vision pure
            double healScore = (ctx.effectiveHealAndShielding / 3000.0) * 50.0;
            pClass = clamp((baseVisionScore * 0.40) + (healScore * 0.40) + (ctx.saveAllyFromDeath * 10.0), 0, 120);
            classReason = String.format("%d Sauvetages, Vis/m: %.1f (Att: %.1f)", ctx.saveAllyFromDeath, actualVisionPerMin, bench.expectedVisionPerMin);

        } else {
            // Combattants (Lee Sin, Viego, Xin Zhao) : Escarmouches, Dégâts, Dégâts aux Drakes/Hérauts
            double objScore = (ctx.damageDealtToObjectives / 15000.0) * 50.0;
            double dpmScore = expectedDpm > 0 ? (ctx.damagePerMinute / expectedDpm) * 50.0 : 50.0;
            pClass = clamp((objScore * 0.40) + (dpmScore * 0.40) + (baseVisionScore * 0.20), 0, 120);
            classReason = String.format("Dégâts Obj: %d, DPM: %.0f", ctx.damageDealtToObjectives, ctx.damagePerMinute);
        }

        res.setPillar("MACRO_CLASS", pClass, 0.30, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        // On ne donne plus +15 pts pour un steal aléatoire. On récompense la vraie domination.
        if (ctx.enemyJungleKills >= (bench.expectedCsPerMin * 3) && ctx.goldDiffAt14 >= 1000) {
            res.addSynergy(10.0, "L'Étouffeur (Domination totale de la carte et privation de ressources du jungler adverse)");
        }
        if (ctx.throwDeaths >= 2) {
            res.addSynergy(-15.0, "Absence de Smite (Morts isolées offrant des objectifs gratuits à l'ennemi)");
        }
    }

    private static void calculateMidScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, double expectedDpm, double durationMin, RoleBenchmarks bench, ScoreResult res) {

        // --- PILIER 1 : LANE & ÉCONOMIE (35%) ---
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = 50.0 + ((actualCsPerMin - bench.expectedCsPerMin) * 20.0);
        double goldScore = 50.0 + (ctx.goldDiffAt14 / 20.0);

        double pLane;
        if (champClass.equals(ASSASSIN)) {
            // Un assassin sacrifie souvent un peu de CS pour roam
            pLane = clamp((csScore * 0.40) + (goldScore * 0.60), 0, 120);
        } else {
            // Les Mages et Combattants Scaling dépendent vitalement du farm
            pLane = clamp((csScore * 0.50) + (goldScore * 0.50), 0, 120);
        }
        res.setPillar("LANE_ECO", pLane, 0.35, String.format("Diff 14m: %dg, CS/m: %.1f (Att: %.1f)", ctx.goldDiffAt14, actualCsPerMin, bench.expectedCsPerMin));

        // --- PILIER 2 : IMPACT & COMBAT GLOBAL (30%) ---
        double kdaScore = bench.expectedKda > 0 ? (ctx.kda / bench.expectedKda) * 50.0 : 50.0;
        double kpScore = (ctx.killParticipation / 0.50) * 50.0;

        double pCombat = clamp((kdaScore * 0.5) + (kpScore * 0.5), 0, 120);
        if (ctx.bountyGold > 0) pCombat = clamp(pCombat + Math.min(15, ctx.bountyGold / 60.0), 0, 120); // Bonus pour les shutdowns pris
        res.setPillar("COMBAT", pCombat, 0.30, String.format("KP: %.0f%%, KDA: %.2f (Att: %.2f)", ctx.killParticipation * 100, ctx.kda, bench.expectedKda));

        // --- PILIER 3 : IDENTITÉ DE CLASSE & MACRO (35%) ---
        double pClass = 50.0;
        String classReason = "";

        double dpmScore = expectedDpm > 0 ? (ctx.damagePerMinute / expectedDpm) * 50.0 : 50.0;
        double soloKillScore = bench.expectedSoloKills > 0 ? (ctx.soloKills / bench.expectedSoloKills) * 50.0 : 50.0;

        if (champClass.equals(MAGE)) {
            // Scale, DPM, AoE (Team Damage) et CC
            double teamDmgScore = (ctx.teamDamagePercentage / 0.25) * 50.0; // 25% des dégâts d'équipe = 50/100
            double ccScore = ctx.enemyChampionImmobilizations * 1.5;
            pClass = clamp((dpmScore * 0.55) + (teamDmgScore * 0.30) + ccScore, 0, 130);
            classReason = String.format("DPM: %.0f (Att: %.0f), %d CC", ctx.damagePerMinute, expectedDpm, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(ASSASSIN)) {
            // Assassiner les carrys (SoloKills), Skirmish (Roam), ET Impact en Teamfight Late Game (Dégâts)
            double roamScore = ctx.earlyRoamTakedowns * 8.0;
            // Si une Katarina/Qiyana fait 25% des dégâts de son équipe, c'est énorme et ça prouve son impact en teamfight
            double teamDmgScore = (ctx.teamDamagePercentage / 0.25) * 40.0;

            pClass = clamp((soloKillScore * 0.45) + teamDmgScore + roamScore, 0, 130);
            classReason = String.format("%d SoloK, %d Roams, %.0f%% Dégâts", ctx.soloKills, ctx.earlyRoamTakedowns, ctx.teamDamagePercentage * 100);

        } else if (champClass.equals(COMBATTANT_ECLAIR)) {
            // Scaling : Prendre des ressources pour infliger de lourds dégâts et détruire les tours
            double objScore = (ctx.damageDealtToObjectives / 5000.0) * 40.0;
            pClass = clamp((dpmScore * 0.50) + (objScore * 0.40) + (soloKillScore * 0.10), 0, 130);
            classReason = String.format("DPM: %.0f, Dégâts Obj: %d", ctx.damagePerMinute, ctx.damageDealtToObjectives);

        } else if (champClass.equals(COMBATTANT)) {
            // Bruiser : Dominer le midgame, grosses stats de Tanking et de Dégâts, Duels
            double tankScore = (ctx.damageTakenOnTeamPercentage / 0.20) * 40.0;
            pClass = clamp((dpmScore * 0.40) + (tankScore * 0.40) + (soloKillScore * 0.20), 0, 130);
            classReason = String.format("%.0f DPM, %.0f%% Tanking, %d SoloKills", ctx.damagePerMinute, ctx.damageTakenOnTeamPercentage * 100, ctx.soloKills);

        } else if (champClass.equals(TANK)) {
            // Tank pur : Encaissement et utilité
            double tankScore = (ctx.damageTakenOnTeamPercentage / 0.25) * 50.0;
            double ccScore = ctx.enemyChampionImmobilizations * 2.0;
            pClass = clamp(tankScore + ccScore, 0, 130);
            classReason = String.format("%.0f%% Tanking, %d CC", ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else if (champClass.equals(ENCHANTER)) {
            // Midlaner Utilitaire (Karma, Seraphine) : DPM correct, Soins/Boucliers massifs
            double healScore = (ctx.effectiveHealAndShielding / 4000.0) * 40.0;
            double ccScore = ctx.enemyChampionImmobilizations * 2.0;
            pClass = clamp((dpmScore * 0.40) + healScore + ccScore, 0, 130);
            classReason = String.format("DPM: %.0f, %.0f Heal/Shield", ctx.damagePerMinute, ctx.effectiveHealAndShielding);

        } else {
            pClass = dpmScore;
            classReason = String.format("DPM: %.0f", ctx.damagePerMinute);
        }
        res.setPillar("MACRO_CLASS", pClass, 0.35, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (champClass.equals(ASSASSIN) && ctx.earlyRoamTakedowns >= 3 && pLane >= 60) {
            res.addSynergy(10.0, "Terreur Globale (Roams dévastateurs sans sacrifier sa propre lane)");
        }
        if ((champClass.equals(MAGE) || champClass.equals(COMBATTANT_ECLAIR)) && csScore >= 80 && dpmScore >= 80) {
            res.addSynergy(10.0, "Hyper-Scaling validé (Conversion parfaite de l'or en dégâts)");
        }
        if (pLane >= 80 && pCombat <= 40 && pClass <= 50) {
            res.addSynergy(-12.0, "Avantage Stérile (A gagné sa lane mais n'a eu aucun impact sur la partie)");
        }
    }

    private static void calculateAdcScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, double expectedDpm, double durationMin, RoleBenchmarks bench, ScoreResult res) {

        // --- PILIER 1 : LANE & ÉCONOMIE (35%) ---
        // Un ADC vit et meurt par son farm. On croise le Diff Gold, le CS/min.
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = 50.0 + ((actualCsPerMin - bench.expectedCsPerMin) * 20.0);

        double goldScore = 50.0 + (ctx.goldDiffAt14 / 20.0); // +1000g d'avance = +50 pts (100/100)

        // Le CS et le Gold dictent la lane de l'ADC
        double pLane = clamp((csScore * 0.50) + (goldScore * 0.50), 0, 120);
        res.setPillar("LANE_ECO", pLane, 0.35, String.format("CS/m: %.1f (Att: %.1f), Diff 14m: %dg", actualCsPerMin, bench.expectedCsPerMin, ctx.goldDiffAt14));

        // --- PILIER 2 : COMBAT & DÉGÂTS (35%) ---
        // Le DPM est le juge de paix. Le KDA sanctionne l'efficacité, et le KP jauge la présence.
        double dpmScore = expectedDpm > 0 ? (ctx.damagePerMinute / expectedDpm) * 50.0 : 50.0;
        double kdaScore = bench.expectedKda > 0 ? (ctx.kda / bench.expectedKda) * 50.0 : 50.0;
        double kpScore = (ctx.killParticipation / 0.50) * 50.0; // Un ADC à 50% KP est dans la moyenne

        double pCombat = clamp((dpmScore * 0.50) + (kdaScore * 0.30) + (kpScore * 0.20), 0, 120);
        if (ctx.bountyGold > 0) pCombat = clamp(pCombat + Math.min(15, ctx.bountyGold / 60.0), 0, 120);

        res.setPillar("COMBAT", pCombat, 0.35, String.format("DPM: %.0f (Att: %.0f), KDA: %.2f", ctx.damagePerMinute, expectedDpm, ctx.kda));

        // --- PILIER 3 : MACRO & SIÈGE (30%) ---
        // Un ADC doit faire tomber les tours. S'il joue Mage (Ziggs), on tolère moins de dégâts tourelles au profit du DPM.
        double objScore = (ctx.damageDealtToObjectives / 6000.0) * 50.0; // 6000 dégâts = 50/100

        // Vision de sécurité (très ciblée sur les trinkets bleus/wards posées vs Benchmark)
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = bench.expectedVisionPerMin > 0 ? (actualVisionPerMin / bench.expectedVisionPerMin) * 50.0 : 50.0;

        double pClass = 50.0;
        if (champClass.equals(MAGE)) {
            pClass = clamp((objScore * 0.4) + (visionScoreNorm * 0.6), 0, 120);
        } else {
            pClass = clamp((objScore * 0.7) + (visionScoreNorm * 0.3), 0, 120);
        }
        res.setPillar("MACRO_SIEGE", pClass, 0.30, String.format("Dégâts Tours: %d, Vis/m: %.1f", ctx.damageDealtToObjectives, actualVisionPerMin));

        // --- SYNERGIES ET MALUS CRITIQUES ---
        if (pCombat >= 90) {
            res.addSynergy(10.0, "Glass Cannon Parfait (Dégâts massifs)");
        }

        // Le filtre du "Farm Simulator" : Parfait en lane, inutile en teamfight
        if (pLane >= 85 && pCombat <= 45) {
            res.addSynergy(-12.0, "KDA Player / Farm Simulator (Beaucoup de ressources pour un impact nul)");
        }
    }

    private static void calculateSupportScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, double expectedDpm, double durationMin, RoleBenchmarks bench, ScoreResult res) {

        // --- PILIER 1 : LANE (30%) ---
        // On utilise l'avantage d'or
        double goldScore = 50.0 + (ctx.goldDiffAt14 / 20.0);
        double pLane = clamp(goldScore, 0, 110);

        res.setPillar("LANE", pLane, 0.30, String.format("Diff 14m: %dg", ctx.goldDiffAt14));

        // --- PILIER 2 : IMPACT & KDA (35%) ---
        // Croisement du KP pur et du KDA par rapport à la moyenne de l'élo
        double kpScore = (ctx.killParticipation / 0.50) * 50.0; // 50% KP = 50 pts, 100% KP = 100 pts
        double kdaScore = bench.expectedKda > 0 ? (ctx.kda / bench.expectedKda) * 50.0 : 50.0;

        double pCombat = clamp((kpScore * 0.6) + (kdaScore * 0.4), 0, 120);
        res.setPillar("IMPACT", pCombat, 0.35, String.format("KP: %.0f%%, KDA: %.2f (Attendu: %.2f)", ctx.killParticipation * 100, ctx.kda, bench.expectedKda));

        // --- PILIER 3 : VISION & UTILITÉ (35%) ---
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = bench.expectedVisionPerMin > 0 ? (actualVisionPerMin / bench.expectedVisionPerMin) * 50.0 : 50.0;
        double controlWardsNorm = bench.expectedControlWards > 0 ? (ctx.controlWardsPlaced / bench.expectedControlWards) * 50.0 : 50.0;

        double baseVisionScore = (visionScoreNorm * 0.6) + (controlWardsNorm * 0.4);

        double pClass = 50.0;
        String classReason = "";

        if (champClass.equals(ENCHANTER)) {
            // NERF MASSIF : L'Enchanteur vit par ses soins, mais c'est du "stat padding" facile.
            // 1. On monte l'attente drastiquement (10 000 Heal/Shield = 50 pts)
            double healScore = (ctx.effectiveHealAndShielding / 10000.0) * 50.0;

            // 2. LE PLAFOND DE VERRE : On force le maximum à 80/100. Un enchanteur ne peut plus "casser" l'algorithme.
            pClass = clamp((baseVisionScore * 0.40) + (healScore * 0.40) + (ctx.saveAllyFromDeath * 15.0), 0, 80);

            classReason = String.format("Vis/m: %.1f, %.0f Heal/Shield (Plafond 80), %d Sauvetages", actualVisionPerMin, ctx.effectiveHealAndShielding, ctx.saveAllyFromDeath);
        } else if (champClass.equals(TANK)) {
            // Le Tank Support vit par son encaissement et ses CC !
            double tankScore = (ctx.damageTakenOnTeamPercentage / 0.15) * 50.0; // 15% des dégâts d'équipe encaissés = 50 pts
            double ccScore = ctx.enemyChampionImmobilizations * 2.0;
            pClass = clamp((baseVisionScore * 0.40) + (tankScore * 0.30) + (ccScore * 0.30), 0, 130);
            classReason = String.format("Vis/m: %.1f, %.0f%% Tanking, %d CC", actualVisionPerMin, ctx.damageTakenOnTeamPercentage * 100, ctx.enemyChampionImmobilizations);

        } else {
            // Mages Support (ex: Zyra, Brand, Vel'Koz) -> Jugés sur leur DPM
            double dpmScore = expectedDpm > 0 ? (ctx.damagePerMinute / expectedDpm) * 50.0 : 50.0;
            pClass = clamp((baseVisionScore * 0.50) + (dpmScore * 0.50), 0, 130);
            classReason = String.format("Vis/m: %.1f, DPM: %.0f", actualVisionPerMin, ctx.damagePerMinute);
        }

        res.setPillar("UTILITE_VISION", pClass, 0.35, classReason);

        // --- SYNERGIES ET VÉRIFICATIONS ---
        if (ctx.sacrificialDeaths >= 2 && pClass >= 80) res.addSynergy(10.0, "Garde du Corps Martyr");

        // La punition des supports aveugles
        if (visionScoreNorm < 25.0 && durationMin > 20.0) {
            res.addSynergy(-15.0, "Lacune de Vision Critique (Moins de la moitié du score attendu)");
        }
    }
}