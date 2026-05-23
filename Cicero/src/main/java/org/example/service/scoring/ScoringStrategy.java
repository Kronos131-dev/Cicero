package org.example.service.scoring;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;

/**
 * Interface for the Strategy Pattern, defining a contract for calculating a player's score based on their role.
 */
public interface ScoringStrategy {

    /**
     * Calculates all the score pillars for a player and applies role-specific synergies.
     *
     * @param res         The ScoreResult object to populate.
     * @param ctx         The player's context data.
     * @param oppCtx      The direct opponent's context data.
     * @param bench       The role-specific benchmarks.
     * @param durationMin The duration of the match in minutes.
     * @param champClass  The champion class (e.g., TANK, MAGE).
     * @param enemyComp   The profile of the enemy team composition.
     */
    void calculateScore(ScoreCalculator.ScoreResult res,
                        MatchDataExtractor.PlayerContext ctx,
                        MatchDataExtractor.PlayerContext oppCtx,
                        ScoreCalculator.RoleBenchmarks bench,
                        double durationMin,
                        String champClass,
                        MatchDataExtractor.TeamCompositionProfile enemyComp);
}
