package org.example.service.strategy;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;

public class JungleStrategy extends AbstractScoringStrategy {
    private static final double INVADE_KILL_MULTIPLIER = 1.5;
    private static final double INVADE_SCUTTLE_MULTIPLIER = 2.0;
    private static final double GOLD_SCALE_FACTOR = 1000.0; // 1000g diff ~ 10 points

    @Override
    public double calculateEarlyScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double goldScore = calculateRelativeScore(ctx.goldAt14, oppCtx.goldAt14, GOLD_SCALE_FACTOR);
        
        double invadeVal = (ctx.enemyJungleKills * INVADE_KILL_MULTIPLIER) + (ctx.scuttleCrabs * INVADE_SCUTTLE_MULTIPLIER);
        double invadeScore = calculateNormalizedScore(invadeVal, ScoringConstants.Jungle.INVADE_EXPECTED, ScoringConstants.Jungle.INVADE_SENSITIVITY);

        double pEarly = (goldScore * 0.6) + (invadeScore * 0.4);

        // Validation de Matchup
        if (oppCtx.kills > ctx.kills + 2 && ctx.goldAt14 < oppCtx.goldAt14) {
            double malusReduction = (oppCtx.kills - ctx.kills) * 5; // Reduce malus by 5 points per extra kill of opponent
            pEarly = Math.min(pEarly + malusReduction, 50); // Cap at 50 to avoid rewarding losing lane
            res.addMacroInfo("Jungle difficile (opp a " + (oppCtx.kills - ctx.kills) + " kills de plus), malus réduit.");
        }

        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Jungle.EARLY_WEIGHT, String.format("Gold@14: %d vs %d, Invade: %d camps", ctx.goldAt14, oppCtx.goldAt14, ctx.enemyJungleKills));
        return pEarly;
    }

    @Override
    public double calculateCombatScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Jungle.KP_EXPECTED, ScoringConstants.Jungle.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Jungle.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Jungle.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Jungle.KDA_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / ScoringConstants.Global.BOUNTY_GOLD_DIVISOR);

        // Apply the new death penalty logic
        double deathPenalty = calculateDeathPenalty(ctx, durationMin, res);
        pCombat = Math.max(0, pCombat - deathPenalty);

        res.setPillar("COMBAT", pCombat, ScoringConstants.Jungle.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Malus Morts: %.1f", ctx.killParticipation * 100, ctx.kda, deathPenalty));
        return pCombat;
    }

    @Override
    public double calculateMacroScore(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Jungle.VISION_SENSITIVITY);
        double controlWardsNorm = calculateNormalizedScore(ctx.controlWardsPlaced, bench.expectedControlWards, ScoringConstants.Jungle.CONTROL_WARDS_SENSITIVITY);
        double baseVisionScore = (visionScoreNorm * ScoringConstants.Jungle.VISION_SCORE_WEIGHT) + (controlWardsNorm * ScoringConstants.Jungle.CONTROL_WARDS_WEIGHT);
        
        double objScore = calculateNormalizedScore(ctx.damageDealtToObjectives, 10000.0, 5000.0);

        double pMacro = (baseVisionScore * 0.7) + (objScore * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Jungle.MACRO_WEIGHT, String.format("Vis/m: %.1f, Dégâts Obj: %d", actualVisionPerMin, ctx.damageDealtToObjectives));
        return pMacro;
    }

    @Override
    public double calculateClassIdentity(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, ScoringConstants.Jungle.VISION_SENSITIVITY);
        double controlWardsNorm = calculateNormalizedScore(ctx.controlWardsPlaced, bench.expectedControlWards, ScoringConstants.Jungle.CONTROL_WARDS_SENSITIVITY);
        double baseVisionScore = (visionScoreNorm * ScoringConstants.Jungle.VISION_SCORE_WEIGHT) + (controlWardsNorm * ScoringConstants.Jungle.CONTROL_WARDS_WEIGHT);

        double pClass = ScoringConstants.Global.BASE_SCORE;
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
        return pClass;
    }

    @Override
    protected void applySpecificSynergies(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp, double pEarly, double pCombat, double pMacro, double pClass) {
        if (ctx.enemyJungleKills >= (bench.expectedCsPerMin * 3) && ctx.goldDiffAt14 >= ScoringConstants.Jungle.SMOTHER_GOLD_DIFF_THRESHOLD) {
            res.addSynergy(ScoringConstants.Jungle.SMOTHER_BONUS, "L'Étouffeur (Domination totale de la carte et privation de ressources du jungler adverse)");
        }
        if (ctx.throwDeaths >= ScoringConstants.Jungle.NO_SMITE_THROW_DEATHS_THRESHOLD) {
            res.addSynergy(ScoringConstants.Jungle.NO_SMITE_MALUS, "Absence de Smite (Morts isolées offrant des objectifs gratuits à l'ennemi)");
        }
    }
}