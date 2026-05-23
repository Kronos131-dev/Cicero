package org.example.service.scoring;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;

/**
 * Base strategy providing common utility methods to all scoring strategies.
 */
public abstract class BaseScoringStrategy implements ScoringStrategy {

    /**
     * Calculates a normalized score based on a logistic sigmoid function.
     *
     * @param actualValue   The real value obtained by the player.
     * @param expectedValue The expected benchmark value.
     * @param sensitivity   The sensitivity of the curve.
     * @return A score between 0 and 100.
     */
    protected double calculateNormalizedScore(double actualValue, double expectedValue, double sensitivity) {
        if (sensitivity <= 0) return 50.0; // Avoid division by zero
        
        // Z = (actual - expected) / sensitivity
        // Score = 100 / (1 + e^(-Z))
        double z = (actualValue - expectedValue) / sensitivity;
        return 100.0 / (1.0 + Math.exp(-z));
    }
}
