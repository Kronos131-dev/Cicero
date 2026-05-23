package org.example.service.strategy;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;

public class TopStrategy extends AbstractScoringStrategy {
    private static final double GOLD_SCALE_FACTOR = 1000.0; // 1000g diff ~ 10 points
    private static final double CS_SCALE_FACTOR = 15.0;   // 15 cs diff ~ 10 points

    @Override
    public double calculateEarlyScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double goldScore = calculateRelativeScore(ctx.goldAt14, oppCtx.goldAt14, GOLD_SCALE_FACTOR);
        double csScore = calculateRelativeScore(ctx.csAt14, oppCtx.csAt14, CS_SCALE_FACTOR);

        double pEarly = (csScore * 0.5) + (goldScore * 0.5);

        // Validation de Matchup
        if (oppCtx.kills > ctx.kills + 2 && ctx.goldAt14 < oppCtx.goldAt14) {
            double malusReduction = (oppCtx.kills - ctx.kills) * 5; // Reduce malus by 5 points per extra kill of opponent
            pEarly = Math.min(pEarly + malusReduction, 50); // Cap at 50 to avoid rewarding losing lane
            res.addMacroInfo("Lane difficile (opp a " + (oppCtx.kills - ctx.kills) + " kills de plus), malus réduit.");
        }

        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Top.EARLY_WEIGHT, String.format("CS@14: %d vs %d, Gold@14: %d vs %d", ctx.csAt14, oppCtx.csAt14, ctx.goldAt14, oppCtx.goldAt14));
        return pEarly;
    }

    @Override
    public double calculateCombatScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Top.KP_EXPECTED, ScoringConstants.Top.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Top.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Top.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Top.KDA_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / ScoringConstants.Global.BOUNTY_GOLD_DIVISOR);
        
        // Apply the new death penalty logic
        double deathPenalty = calculateDeathPenalty(ctx, durationMin, res);
        pCombat = Math.max(0, pCombat - deathPenalty);

        res.setPillar("COMBAT", pCombat, ScoringConstants.Top.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Malus Morts: %.1f", ctx.killParticipation * 100, ctx.kda, deathPenalty));
        return pCombat;
    }

    @Override
    public double calculateMacroScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Top.CS_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, 1.0);

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Top.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));
        return pMacro;
    }

    @Override
    public double calculateClassIdentity(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double pClass = ScoringConstants.Global.BASE_SCORE;
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
        return pClass;
    }

    @Override
    protected void applySpecificSynergies(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp, double pEarly, double pCombat, double pMacro, double pClass) {
        double soloKillScore = calculateNormalizedScore(ctx.soloKills, bench.expectedSoloKills, ScoringConstants.Top.Carry.SOLO_KILL_SENSITIVITY);
        
        if (pEarly >= ScoringConstants.Top.TYRAN_LANE_SCORE_THRESHOLD && soloKillScore >= ScoringConstants.Top.TYRAN_SOLO_KILL_SCORE_THRESHOLD && pClass >= ScoringConstants.Top.TYRAN_CLASS_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Top.TYRAN_BONUS, "Le Tyran de la Lane (Écrasement total en 1v1 et conversion de l'avantage)");
        }
        if (ctx.sacrificialDeaths >= ScoringConstants.Top.SACRIFICIAL_DEATHS_THRESHOLD && ctx.damageDealtToObjectives >= ScoringConstants.Top.PRESSURE_OBJECTIVE_DAMAGE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Top.PRESSURE_BONUS, "Pression Asphyxiante (A attiré toute l'équipe ennemie pour faire gagner le reste de la carte)");
        }
        if (ctx.earlySoloDeaths >= ScoringConstants.Top.ABYSS_EARLY_DEATHS_THRESHOLD && pEarly <= ScoringConstants.Top.ABYSS_LANE_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Top.ABYSS_MALUS, "Gouffre Absolu (A détruit les chances de victoire de son équipe dès les 10 premières minutes)");
        }
    }
}