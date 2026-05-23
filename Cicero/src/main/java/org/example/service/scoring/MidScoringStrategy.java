package org.example.service.scoring;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;
import static org.example.service.ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP;
import static org.example.service.ScoringConstants.Global.BOUNTY_GOLD_DIVISOR;

public class MidScoringStrategy extends BaseScoringStrategy {

    @Override
    public void calculateScore(ScoreCalculator.ScoreResult res, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, ScoreCalculator.RoleBenchmarks bench, double durationMin, String champClass, MatchDataExtractor.TeamCompositionProfile enemyComp) {
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
        double pClass = ScoringConstants.Global.BASE_SCORE;
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
    }
}
