package org.example.service;

/**
 * Central configuration class for all scoring-related constants and weights.
 * This allows for easy tuning of the scoring algorithm without touching the strategy logic.
 */
public class ScoringConfig {

    // --- Pillar Weights (must sum to 1.0 for each role) ---
    public static class PillarWeights {
        public static final double EARLY_WEIGHT = 0.25;
        public static final double COMBAT_WEIGHT = 0.35;
        public static final double MACRO_WEIGHT = 0.20;
        public static final double CLASS_WEIGHT = 0.20;
    }

    // --- Death Penalties ---
    public static class DeathPenalty {
        public static final double BASE_PENALTY_PER_DEATH = 8.0;
        public static final double EARLY_MID_GAME_COEFFICIENT = 1.5;
        public static final double LATE_GAME_COEFFICIENT = 2.0;
        public static final double SACRIFICIAL_DEATH_REDUCTION = 0.8; // 80% reduction
        public static final double GAME_ENDING_THROW_MALUS = 20.0;
    }

    // --- General Scaling Factors & Benchmarks ---
    public static class Scaling {
        public static final double GOLD_SCALE_FACTOR = 1000.0;
        public static final double CS_SCALE_FACTOR = 15.0;
        public static final double DPM_SENSITIVITY = 150.0;
        public static final double KDA_SENSITIVITY = 1.5;
        public static final double KP_SENSITIVITY = 0.2;
        public static final double VISION_SENSITIVITY = 0.5;
    }

    // --- Role-Specific Benchmarks & Weights ---
    // These can be further broken down if needed, but for now, this centralizes common values.
    public static class Adc {
        public static final double GOLD_SCORE_WEIGHT = 0.6;
        public static final double CS_SCORE_WEIGHT = 0.4;
        // ... other ADC specific values can be moved here
    }

    public static class Mid {
        public static class Default {
            public static final double CS_SCORE_WEIGHT = 0.5;
            public static final double GOLD_SCORE_WEIGHT = 0.5;
        }
        public static class Assassin {
            public static final double CS_SCORE_WEIGHT = 0.3;
            public static final double GOLD_SCORE_WEIGHT = 0.7;
        }
    }

    public static class Top {
         public static final double GOLD_SCORE_WEIGHT = 0.5;
         public static final double CS_SCORE_WEIGHT = 0.5;
    }

    public static class Jungle {
        public static final double GOLD_SCORE_WEIGHT = 0.6;
        public static final double INVADE_SCORE_WEIGHT = 0.4;
    }

    public static class Support {
        public static final double GOLD_SCALE_FACTOR = 800.0;
    }

    // --- Global Rules & Synergies ---
    public static class Global {
        public static final double BOUNTY_GOLD_BONUS_CAP = 15.0;
        public static final double BOUNTY_GOLD_DIVISOR = 100.0;

        public static final double SOFT_CAP_THRESHOLD = 110.0;
        public static final double SOFT_CAP_LOG_FACTOR = 15.0;
        public static final double MIN_SCORE = 10.0;
        public static final double MAX_SCORE = 130.0;

        public static final class Floor {
            public static final double EXCEPTIONAL_KDA = 4.0;
            public static final double EXCEPTIONAL_KP = 0.50;
            public static final double EXCEPTIONAL_FLOOR = 55.0;

            public static final double SOLID_KDA = 3.0;
            public static final double SOLID_KP = 0.40;
            public static final double SOLID_FLOOR = 40.0;
        }
    }
}
