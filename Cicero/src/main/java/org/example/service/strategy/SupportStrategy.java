package org.example.service.strategy;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;

public class SupportStrategy extends AbstractScoringStrategy {
    private static final double GOLD_SCALE_FACTOR = 800.0; // 800g diff ~ 10 points

    @Override
    public double calculateEarlyScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double pEarly = calculateRelativeScore(ctx.goldAt14, oppCtx.goldAt14, GOLD_SCALE_FACTOR);

        // Validation de Matchup
        if (oppCtx.kills > ctx.kills + 2 && ctx.goldAt14 < oppCtx.goldAt14) {
            double malusReduction = (oppCtx.kills - ctx.kills) * 5; // Reduce malus by 5 points per extra kill of opponent
            pEarly = Math.min(pEarly + malusReduction, 50); // Cap at 50 to avoid rewarding losing lane
            res.addMacroInfo("Lane difficile (opp a " + (oppCtx.kills - ctx.kills) + " kills de plus), malus réduit.");
        }

        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Support.EARLY_WEIGHT, String.format("Gold@14: %d vs %d", ctx.goldAt14, oppCtx.goldAt14));
        return pEarly;
    }

    @Override
    public double calculateCombatScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Support.KP_EXPECTED, ScoringConstants.Support.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Support.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Support.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Support.KDA_SCORE_WEIGHT);
        
        // Apply the new death penalty logic
        double deathPenalty = calculateDeathPenalty(ctx, durationMin, res);
        pCombat = Math.max(0, pCombat - deathPenalty);

        res.setPillar("COMBAT", pCombat, ScoringConstants.Support.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Malus Morts: %.1f", ctx.killParticipation * 100, ctx.kda, deathPenalty));
        return pCombat;
    }

    @Override
    public double calculateMacroScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Support.VISION_SENSITIVITY);
        double controlWardsNorm = calculateNormalizedScore(ctx.controlWardsPlaced, bench.expectedControlWards, ScoringConstants.Support.CONTROL_WARDS_SENSITIVITY);

        double pMacro = (visionScoreNorm * ScoringConstants.Support.VISION_SCORE_WEIGHT) + (controlWardsNorm * ScoringConstants.Support.CONTROL_WARDS_WEIGHT);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Support.MACRO_WEIGHT, String.format("Vis/m: %.1f, Pinks: %d", actualVisionPerMin, ctx.controlWardsPlaced));
        return pMacro;
    }

    @Override
    public double calculateClassIdentity(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Support.VISION_SENSITIVITY);

        double pClass = ScoringConstants.Global.BASE_SCORE;
        String classReason = "";

        String cleanName = ctx.championName.toLowerCase();

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
        return pClass;
    }

    @Override
    protected void applySpecificSynergies(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp, double pEarly, double pCombat, double pMacro, double pClass) {
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Support.VISION_SENSITIVITY);
        
        if (ctx.sacrificialDeaths >= ScoringConstants.Support.BODYGUARD_SACRIFICIAL_DEATHS_THRESHOLD && pClass >= ScoringConstants.Support.BODYGUARD_CLASS_SCORE_THRESHOLD) {
            res.addSynergy(ScoringConstants.Support.BODYGUARD_BONUS, "Garde du Corps Martyr");
        }

        if (visionScoreNorm < ScoringConstants.Support.BLIND_VISION_SCORE_THRESHOLD && durationMin > ScoringConstants.Support.BLIND_GAME_DURATION_THRESHOLD) {
            res.addSynergy(ScoringConstants.Support.BLIND_MALUS, "Lacune de Vision Critique (Moins de la moitié du score attendu)");
        }
    }
}