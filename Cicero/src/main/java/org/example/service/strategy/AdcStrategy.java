package org.example.service.strategy;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;

public class AdcStrategy extends AbstractScoringStrategy {
    private static final double GOLD_SCALE_FACTOR = 1000.0; // 1000g diff ~ 10 points
    private static final double CS_SCALE_FACTOR = 15.0;   // 15 cs diff ~ 10 points

    @Override
    public double calculateEarlyScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double goldScore = calculateRelativeScore(ctx.goldAt14, oppCtx.goldAt14, GOLD_SCALE_FACTOR);
        double csScore = calculateRelativeScore(ctx.csAt14, oppCtx.csAt14, CS_SCALE_FACTOR);

        double pEarly = (goldScore * ScoringConstants.Adc.GOLD_SCORE_WEIGHT) + (csScore * ScoringConstants.Adc.CS_SCORE_WEIGHT);

        // Validation de Matchup
        if (oppCtx.kills > ctx.kills + 2 && ctx.goldAt14 < oppCtx.goldAt14) {
            double malusReduction = (oppCtx.kills - ctx.kills) * 5; // Reduce malus by 5 points per extra kill of opponent
            pEarly = Math.min(pEarly + malusReduction, 50); // Cap at 50 to avoid rewarding losing lane
            res.addMacroInfo("Lane difficile, malus réduit.");
        }

        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Adc.EARLY_WEIGHT, String.format("CS@14: %d vs %d, Gold@14: %d vs %d", ctx.csAt14, oppCtx.csAt14, ctx.goldAt14, oppCtx.goldAt14));
        return pEarly;
    }

    @Override
    public double calculateCombatScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, ScoringConstants.Adc.DPM_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Adc.KDA_SENSITIVITY);
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Adc.KP_EXPECTED, ScoringConstants.Adc.KP_SENSITIVITY);

        double pCombat = (dpmScore * ScoringConstants.Adc.DPM_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Adc.KDA_SCORE_WEIGHT) + (kpScore * ScoringConstants.Adc.KP_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / ScoringConstants.Global.BOUNTY_GOLD_DIVISOR);

        // Apply the new death penalty logic
        double deathPenalty = calculateDeathPenalty(ctx, durationMin, res);
        pCombat = Math.max(0, pCombat - deathPenalty);

        res.setPillar("COMBAT", pCombat, ScoringConstants.Adc.COMBAT_WEIGHT, String.format("DPM: %.0f, KDA: %.2f, Malus Morts: %.1f", ctx.damagePerMinute, ctx.kda, deathPenalty));
        return pCombat;
    }

    @Override
    public double calculateMacroScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Adc.CS_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Adc.VISION_SENSITIVITY);

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Adc.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));
        return pMacro;
    }

    @Override
    public double calculateClassIdentity(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double objScore = calculateNormalizedScore(ctx.damageDealtToObjectives, ScoringConstants.Adc.OBJECTIVE_DAMAGE_EXPECTED, ScoringConstants.Adc.OBJECTIVE_DAMAGE_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Adc.VISION_SENSITIVITY);
        
        double pClass = ScoringConstants.Global.BASE_SCORE;
        if (champClass.equals(MAGE)) {
            pClass = (objScore * ScoringConstants.Adc.Mage.OBJECTIVE_DAMAGE_WEIGHT) + (visionScoreNorm * ScoringConstants.Adc.Mage.VISION_SCORE_WEIGHT);
        } else {
            pClass = (objScore * ScoringConstants.Adc.Default.OBJECTIVE_DAMAGE_WEIGHT) + (visionScoreNorm * ScoringConstants.Adc.Default.VISION_SCORE_WEIGHT);
        }
        res.setPillar("CLASS_IDENTITY", pClass, ScoringConstants.Adc.CLASS_WEIGHT, String.format("Dégâts Tours: %d, Vis/m: %.1f", ctx.damageDealtToObjectives, actualVisionPerMin));
        return pClass;
    }

    @Override
    protected void applySpecificSynergies(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp, double pEarly, double pCombat, double pMacro, double pClass) {
        if (pCombat >= ScoringConstants.Adc.GLASS_CANNON_COMBAT_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Adc.GLASS_CANNON_BONUS, "Glass Cannon Parfait (Dégâts massifs)");
        }

        if (pEarly >= ScoringConstants.Adc.FARM_SIMULATOR_LANE_SCORE_THRESHOLD && pCombat <= ScoringConstants.Adc.FARM_SIMULATOR_COMBAT_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Adc.FARM_SIMULATOR_MALUS, "KDA Player / Farm Simulator (Beaucoup de ressources pour un impact nul)");
        }
    }
}