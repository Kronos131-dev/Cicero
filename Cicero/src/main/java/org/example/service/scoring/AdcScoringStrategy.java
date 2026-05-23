package org.example.service.scoring;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;
import static org.example.service.ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP;
import static org.example.service.ScoringConstants.Global.BOUNTY_GOLD_DIVISOR;

public class AdcScoringStrategy extends BaseScoringStrategy {

    @Override
    public void calculateScore(ScoreCalculator.ScoreResult res, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, ScoreCalculator.RoleBenchmarks bench, double durationMin, String champClass, MatchDataExtractor.TeamCompositionProfile enemyComp) {
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
        
        double pClass = ScoringConstants.Global.BASE_SCORE;
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
    }
}
