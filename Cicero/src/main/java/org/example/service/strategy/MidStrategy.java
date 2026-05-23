package org.example.service.strategy;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;

public class MidStrategy extends AbstractScoringStrategy {
    private static final double GOLD_SCALE_FACTOR = 1000.0; // 1000g diff ~ 10 points
    private static final double CS_SCALE_FACTOR = 15.0;   // 15 cs diff ~ 10 points

    @Override
    public double calculateEarlyScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double goldScore = calculateRelativeScore(ctx.goldAt14, oppCtx.goldAt14, GOLD_SCALE_FACTOR);
        double csScore = calculateRelativeScore(ctx.csAt14, oppCtx.csAt14, CS_SCALE_FACTOR);

        double pEarly;
        if (champClass.equals(ASSASSIN)) {
            pEarly = (csScore * ScoringConstants.Mid.Assassin.CS_SCORE_WEIGHT) + (goldScore * ScoringConstants.Mid.Assassin.GOLD_SCORE_WEIGHT);
        } else {
            pEarly = (csScore * ScoringConstants.Mid.Default.CS_SCORE_WEIGHT) + (goldScore * ScoringConstants.Mid.Default.GOLD_SCORE_WEIGHT);
        }

        // Validation de Matchup
        if (oppCtx.kills > ctx.kills + 2 && ctx.goldAt14 < oppCtx.goldAt14) {
            double malusReduction = (oppCtx.kills - ctx.kills) * 5; // Reduce malus by 5 points per extra kill of opponent
            pEarly = Math.min(pEarly + malusReduction, 50); // Cap at 50 to avoid rewarding losing lane
            res.addMacroInfo("Lane difficile (opp a " + (oppCtx.kills - ctx.kills) + " kills de plus), malus réduit.");
        }

        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Mid.EARLY_WEIGHT, String.format("CS@14: %d vs %d, Gold@14: %d vs %d", ctx.csAt14, oppCtx.csAt14, ctx.goldAt14, oppCtx.goldAt14));
        return pEarly;
    }

    @Override
    public double calculateCombatScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Mid.KDA_SENSITIVITY);
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Mid.KP_EXPECTED, ScoringConstants.Mid.KP_SENSITIVITY);

        double pCombat = (kdaScore * ScoringConstants.Mid.KDA_SCORE_WEIGHT) + (kpScore * ScoringConstants.Mid.KP_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / ScoringConstants.Global.BOUNTY_GOLD_DIVISOR);
        
        // Apply the new death penalty logic
        double deathPenalty = calculateDeathPenalty(ctx, durationMin, res);
        pCombat = Math.max(0, pCombat - deathPenalty);

        res.setPillar("COMBAT", pCombat, ScoringConstants.Mid.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Malus Morts: %.1f", ctx.killParticipation * 100, ctx.kda, deathPenalty));
        return pCombat;
    }

    @Override
    public double calculateMacroScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Mid.CS_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, 1.0);

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Mid.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));
        return pMacro;
    }

    @Override
    public double calculateClassIdentity(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double pClass = ScoringConstants.Global.BASE_SCORE;
        String classReason = "";

        double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Mid.Mage.DPM_SENSITIVITY);
        double soloKillScore = calculateNormalizedScore(ctx.soloKills, bench.expectedSoloKills, ScoringConstants.Mid.Assassin.SOLO_KILL_SENSITIVITY);

        if (ctx.championName.equalsIgnoreCase("GALIO")) {
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
        return pClass;
    }

    @Override
    protected void applySpecificSynergies(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp, double pEarly, double pCombat, double pMacro, double pClass) {
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Mid.CS_SENSITIVITY);
        double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Mid.Mage.DPM_SENSITIVITY);

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