package org.example.service.scoring;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;

import static org.example.service.ScoreCalculator.*;
import static org.example.service.ScoringConstants.Global.BOUNTY_GOLD_BONUS_CAP;
import static org.example.service.ScoringConstants.Global.BOUNTY_GOLD_DIVISOR;

public class TopScoringStrategy extends BaseScoringStrategy {

    @Override
    public void calculateScore(ScoreCalculator.ScoreResult res, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, ScoreCalculator.RoleBenchmarks bench, double durationMin, String champClass, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        // --- PILIER 1 : EARLY_GAME (25%) ---
        double expectedCsAt14 = bench.expectedCsPerMin * 14;
        double csAt14Score = calculateNormalizedScore(ctx.csAt14, expectedCsAt14, ScoringConstants.Top.CS_AT_14_SENSITIVITY);
        double goldDiffScore = calculateNormalizedScore(ctx.goldDiffAt14, 0, ScoringConstants.Top.GOLD_SENSITIVITY);

        double pEarly = (csAt14Score * 0.5) + (goldDiffScore * 0.5);
        res.setPillar("EARLY_GAME", pEarly, ScoringConstants.Top.EARLY_WEIGHT, String.format("CS@14: %d (Att: %.0f), GoldDiff@14: %d", ctx.csAt14, expectedCsAt14, ctx.goldDiffAt14));

        // --- PILIER 2 : COMBAT (25%) ---
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Top.KP_EXPECTED, ScoringConstants.Top.KP_SENSITIVITY);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, ScoringConstants.Top.KDA_SENSITIVITY);

        double pCombat = (kpScore * ScoringConstants.Top.KP_SCORE_WEIGHT) + (kdaScore * ScoringConstants.Top.KDA_SCORE_WEIGHT);
        if (ctx.bountyGold > 0) pCombat += Math.min(BOUNTY_GOLD_BONUS_CAP, ctx.bountyGold / BOUNTY_GOLD_DIVISOR);
        
        pCombat = Math.max(0, pCombat - (ctx.unforcedErrorDeaths * 8));

        res.setPillar("COMBAT", pCombat, ScoringConstants.Top.COMBAT_WEIGHT, String.format("KP: %.0f%%, KDA: %.2f, Throws: %d", ctx.killParticipation * 100, ctx.kda, ctx.unforcedErrorDeaths));

        // --- PILIER 3 : MACRO_MAP (25%) ---
        double actualCsPerMin = ctx.totalCs / durationMin;
        double csScore = calculateNormalizedScore(actualCsPerMin, bench.expectedCsPerMin, ScoringConstants.Top.CS_SENSITIVITY);
        
        double actualVisionPerMin = ctx.visionScore / durationMin;
        double visionScoreNorm = calculateNormalizedScore(actualVisionPerMin, bench.expectedVisionPerMin, 1.0);

        double pMacro = (csScore * 0.7) + (visionScoreNorm * 0.3);
        res.setPillar("MACRO_MAP", pMacro, ScoringConstants.Top.MACRO_WEIGHT, String.format("CS/m: %.1f, Vis/m: %.1f", actualCsPerMin, actualVisionPerMin));

        // --- PILIER 4 : CLASS_IDENTITY (25%) ---
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
    }
}
