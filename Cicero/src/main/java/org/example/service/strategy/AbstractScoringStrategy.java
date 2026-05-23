package org.example.service.strategy;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import static org.example.service.ScoringConstants.Global.Synergies;

public abstract class AbstractScoringStrategy implements ScoringStrategy {

    protected double calculateNormalizedScore(double actualValue, double expectedValue, double sensitivity) {
        if (sensitivity <= 0) return 50.0;
        double z = (actualValue - expectedValue) / sensitivity;
        return 100.0 / (1.0 + Math.exp(-z));
    }

    protected double calculateRelativeScore(double playerValue, double opponentValue, double scaleFactor) {
        if (scaleFactor <= 0) return 50.0;
        double difference = playerValue - opponentValue;
        double score = 50 + (difference / scaleFactor) * 10;
        return Math.max(0, Math.min(100, score));
    }

    protected double calculateDeathPenalty(MatchDataExtractor.PlayerContext ctx, double durationMin, ScoreCalculator.ScoreResult res) {
        double totalPenalty = 0;
        final double BASE_PENALTY_PER_DEATH = 8.0; // The original penalty was ctx.unforcedErrorDeaths * 8

        // We don't have the exact timestamp for each death in the PlayerContext to apply
        // the exact (1.0 + (minute/gameDuration)) formula per death.
        // However, we do have the breakdown: earlySoloDeaths, earlyGankDeaths, lateGameDeaths
        // We will approximate the formula by giving a base penalty to early/mid deaths,
        // and a much higher penalty to lateGameDeaths.
        
        int earlyMidGameDeaths = ctx.deaths - ctx.lateGameDeaths;
        
        // Let's assume early/mid deaths happened on average at 50% of the game
        // Coeff = 1.0 + (50% / 100%) = 1.5
        double earlyMidCoeff = 1.5; 
        
        // Late game deaths happened at the end
        // Coeff = 1.0 + (100% / 100%) = 2.0
        double lateCoeff = 2.0;

        // Apply time-weighted base penalty
        double timeWeightedPenalty = (earlyMidGameDeaths * BASE_PENALTY_PER_DEATH * earlyMidCoeff) + 
                                     (ctx.lateGameDeaths * BASE_PENALTY_PER_DEATH * lateCoeff);
        
        totalPenalty += timeWeightedPenalty;

        // Modifier: Throw vs Sacrifice
        // The instructions state:
        // - "Double le malus" if unforcedErrorDeaths
        // - "Réduis le malus de 80%" if sacrificialDeaths
        // Since we aggregated the penalty above, we will adjust based on the counts.
        // We assume each throw/sacrifice death carries the average penalty.
        if (ctx.deaths > 0) {
            double avgPenaltyPerDeath = timeWeightedPenalty / ctx.deaths;
            
            // For each unforced error, we add another 1x penalty (effectively doubling it)
            totalPenalty += ctx.unforcedErrorDeaths * avgPenaltyPerDeath;
            
            // For each sacrifice, we subtract 80% of its penalty
            totalPenalty -= ctx.sacrificialDeaths * (avgPenaltyPerDeath * 0.8);
        }

        // Game-Ending Throw
        // "Si le joueur meurt dans les 3 dernières minutes de la partie et que l'équipe perd juste après"
        // We don't have the exact timestamps, but if they lost and have a late game death, it's highly likely.
        // We apply a drastic malus.
        if (!ctx.win && ctx.lateGameDeaths > 0) {
            totalPenalty += 20; // Malus fixe sévère
            res.addSynergy(-20, "Game Ending Throw (Mort décisive en fin de partie ayant coûté la victoire)");
        }

        return Math.max(0, totalPenalty);
    }

    protected void applyTemporalSynergies(MatchDataExtractor.PlayerContext ctx, ScoreCalculator.ScoreResult res) {
        if (ctx.clutchKills > 0) {
            res.addSynergy(ctx.clutchKills * Synergies.CLUTCH_KILL_BONUS,
                    ctx.clutchKills + " kill(s) décisif(s) ayant débloqué un objectif majeur.");
        }

        if (ctx.pickOffs > 0) {
            res.addSynergy(ctx.pickOffs * Synergies.PICK_OFF_BONUS,
                    ctx.pickOffs + " cible(s) isolée(s) éliminée(s), créant une supériorité numérique.");
        }
        
        int netKills = ctx.kills - ctx.deaths;
        if (netKills > Synergies.TERMINATOR_NET_KILL_THRESHOLD) {
            int extraNetKills = netKills - Synergies.TERMINATOR_NET_KILL_THRESHOLD;
            double bonus = extraNetKills * Synergies.TERMINATOR_BONUS_PER_NET_KILL;
            res.addSynergy(bonus, "Mode Terminator : " + extraNetKills + " kills nets (K-D) au-delà du seuil de domination.");
        }
    }

    @Override
    public void calculate(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp) {
        double pEarly = calculateEarlyScore(ctx, oppCtx, champClass, bench, durationMin, res, enemyComp);
        double pCombat = calculateCombatScore(ctx, oppCtx, champClass, bench, durationMin, res, enemyComp);
        double pMacro = calculateMacroScore(ctx, oppCtx, champClass, bench, durationMin, res, enemyComp);
        double pClass = calculateClassIdentity(ctx, oppCtx, champClass, bench, durationMin, res, enemyComp);

        applySpecificSynergies(ctx, oppCtx, champClass, bench, durationMin, res, enemyComp, pEarly, pCombat, pMacro, pClass);
        applyTemporalSynergies(ctx, res);
    }

    protected abstract void applySpecificSynergies(MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx, String champClass, ScoreCalculator.RoleBenchmarks bench, double durationMin, ScoreCalculator.ScoreResult res, MatchDataExtractor.TeamCompositionProfile enemyComp, double pEarly, double pCombat, double pMacro, double pClass);
}