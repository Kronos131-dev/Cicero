package org.example.service;

/**
 * Classe de configuration centralisée pour tous les "chiffres magiques" de l'algorithme ScoreCalculator.
 * Modifier ces valeurs permet de calibrer finement la notation sans toucher à la logique principale.
 */
public final class ScoringConstants {

    // Empêche l'instanciation de cette classe utilitaire
    private ScoringConstants() {}

    // --- CONSTANTES GLOBALES ---
    public static final class Global {
        public static final double BASE_SCORE = 50.0;
        public static final double BOUNTY_GOLD_BONUS_CAP = 15.0;
        public static final double BOUNTY_GOLD_DIVISOR = 60.0;
        public static final double SOFT_CAP_THRESHOLD = 95.0;
        public static final double SOFT_CAP_LOG_FACTOR = 5.0;
        public static final double MIN_SCORE = 0.0;
        public static final double MAX_SCORE = 150.0;

        public static final class Floor {
            public static final double EXCEPTIONAL_KDA = 4.0;
            public static final double EXCEPTIONAL_KP = 0.50;
            public static final double EXCEPTIONAL_FLOOR = 55.0;

            public static final double SOLID_KDA = 3.0;
            public static final double SOLID_KP = 0.40;
            public static final double SOLID_FLOOR = 40.0;
        }
        
        // --- NOUVEAU : SYNERGIES D'IMPACT TEMPOREL & DOMINATION ---
        public static final class Synergies {
            public static final double CLUTCH_KILL_BONUS = 1.5;       // Kill -> Objectif
            public static final double PICK_OFF_BONUS = 2.0;          // Kill isolé
            public static final double UNFORCED_ERROR_MALUS = -4.0;  // Mort -> Perte d'objectif
            
            // Le Bonus "Terminator" (Net Kills = Kills - Morts)
            // Il faut avoir au moins 7 kills de plus que de morts pour activer ce bonus
            public static final int TERMINATOR_NET_KILL_THRESHOLD = 7;   
            public static final double TERMINATOR_BONUS_PER_NET_KILL = 0.5;
        }
    }

    // --- CONSTANTES PAR RÔLE ---

    public static final class Top {
        // Piliers
        public static final double LANE_ECO_WEIGHT = 0.35;
        public static final double COMBAT_WEIGHT = 0.30;
        public static final double MACRO_CLASS_WEIGHT = 0.35;

        // Pilier LANE_ECO
        public static final double CS_SCORE_WEIGHT = 0.50;
        public static final double GOLD_SCORE_WEIGHT = 0.50;
        public static final double GOLD_SENSITIVITY = 2500.0;
        public static final double CS_SENSITIVITY = 20.0;

        // Pilier COMBAT
        public static final double KP_SCORE_WEIGHT = 0.50;
        public static final double KDA_SCORE_WEIGHT = 0.50;
        public static final double KP_EXPECTED = 0.40;
        public static final double KP_SENSITIVITY = 0.15;
        public static final double KDA_SENSITIVITY = 3.5;

        // Pilier MACRO_CLASS
        public static final class Tank {
            public static final double TANKING_WEIGHT = 0.60;
            public static final double CC_WEIGHT = 0.40;
            public static final double TANKING_EXPECTED = 0.30;
            public static final double TANKING_SENSITIVITY = 0.15;
            public static final double CC_EXPECTED = 15.0;
            public static final double CC_SENSITIVITY = 8.0;
        }
        public static final class Carry { // Combattant Eclair, Assassin, ADC
            public static final double SOLO_KILL_WEIGHT = 0.35;
            public static final double OBJECTIVE_DAMAGE_WEIGHT = 0.40;
            public static final double DPM_WEIGHT = 0.25;
            public static final double OBJECTIVE_DAMAGE_EXPECTED = 8000.0;
            public static final double OBJECTIVE_DAMAGE_SENSITIVITY = 3000.0;
            public static final double SOLO_KILL_SENSITIVITY = 1.5;
            public static final double DPM_SENSITIVITY = 150.0;
        }
        public static final class Bruiser {
            public static final double SOLO_KILL_WEIGHT = 0.35;
            public static final double DPM_WEIGHT = 0.35;
            public static final double TANKING_WEIGHT = 0.30;
            public static final double TANKING_EXPECTED = 0.25;
            public static final double TANKING_SENSITIVITY = 0.08;
            public static final double SOLO_KILL_SENSITIVITY = 1.5;
            public static final double DPM_SENSITIVITY = 150.0;
        }

        // Synergies & Malus
        public static final double TYRAN_LANE_SCORE_THRESHOLD = 90.0;
        public static final double TYRAN_SOLO_KILL_SCORE_THRESHOLD = 80.0;
        public static final double TYRAN_CLASS_SCORE_THRESHOLD = 80.0;
        public static final double TYRAN_BONUS = 10.0;
        public static final int SACRIFICIAL_DEATHS_THRESHOLD = 2;
        public static final int PRESSURE_OBJECTIVE_DAMAGE_THRESHOLD = 10000;
        public static final double PRESSURE_BONUS = 12.0;
        public static final int ABYSS_EARLY_DEATHS_THRESHOLD = 3;
        public static final double ABYSS_LANE_SCORE_THRESHOLD = 40.0;
        public static final double ABYSS_MALUS = -15.0;
    }

    public static final class Jungle {
        // Piliers
        public static final double PATHING_ECO_WEIGHT = 0.35;
        public static final double IMPACT_WEIGHT = 0.35;
        public static final double MACRO_CLASS_WEIGHT = 0.30;

        // Pilier PATHING_ECO
        public static final double CS_SCORE_WEIGHT = 0.40;
        public static final double GOLD_SCORE_WEIGHT = 0.40;
        public static final double INVADE_WEIGHT = 0.20; // Added weight for invade
        public static final double GOLD_SENSITIVITY = 600.0;
        public static final double CS_SENSITIVITY = 3.5;
        public static final double INVADE_EXPECTED = 5.0;
        public static final double INVADE_SENSITIVITY = 5.0;

        // Pilier IMPACT
        public static final double KP_SCORE_WEIGHT = 0.60;
        public static final double KDA_SCORE_WEIGHT = 0.40;
        public static final double KP_EXPECTED = 0.50;
        public static final double KP_SENSITIVITY = 0.25;
        public static final double KDA_SENSITIVITY = 0.30;

        // Pilier MACRO_CLASS (Vision)
        public static final double VISION_SCORE_WEIGHT = 0.6;
        public static final double CONTROL_WARDS_WEIGHT = 0.4;
        public static final double VISION_SENSITIVITY = 0.6;
        public static final double CONTROL_WARDS_SENSITIVITY = 1.0;

        public static final class Tank {
            public static final double VISION_WEIGHT = 0.40;
            public static final double TANKING_WEIGHT = 0.30;
            public static final double CC_WEIGHT = 0.30;
            public static final double TANKING_EXPECTED = 0.30;
            public static final double TANKING_SENSITIVITY = 0.08;
            public static final double CC_EXPECTED = 15.0;
            public static final double CC_SENSITIVITY = 8.0;
        }
        public static final class Assassin {
            public static final double SOLO_KILL_WEIGHT = 0.45;
            public static final double DPM_WEIGHT = 0.35;
            public static final double VISION_WEIGHT = 0.20;
            public static final double SOLO_KILL_SENSITIVITY = 1.5;
            public static final double DPM_SENSITIVITY = 300.0;
        }
        public static final class Enchanter {
            public static final double VISION_WEIGHT = 0.40;
            public static final double HEAL_WEIGHT = 0.40;
            public static final double SAVE_ALLY_WEIGHT = 0.20; // Added weight
            public static final double HEAL_EXPECTED = 3000.0;
            public static final double HEAL_SENSITIVITY = 1500.0;
            public static final double SAVE_ALLY_EXPECTED = 0.5;
            public static final double SAVE_ALLY_SENSITIVITY = 1.0;
        }
        public static final class Fighter {
            public static final double OBJECTIVE_DAMAGE_WEIGHT = 0.40;
            public static final double DPM_WEIGHT = 0.40;
            public static final double VISION_WEIGHT = 0.20;
            public static final double OBJECTIVE_DAMAGE_EXPECTED = 15000.0;
            public static final double OBJECTIVE_DAMAGE_SENSITIVITY = 6000.0;
            public static final double DPM_SENSITIVITY = 150.0;
        }

        // Synergies & Malus
        public static final double SMOTHER_GOLD_DIFF_THRESHOLD = 1000;
        public static final double SMOTHER_BONUS = 10.0;
        public static final int NO_SMITE_THROW_DEATHS_THRESHOLD = 2;
        public static final double NO_SMITE_MALUS = -15.0;
    }

    public static final class Mid {
        // Piliers
        public static final double LANE_ECO_WEIGHT = 0.35;
        public static final double COMBAT_WEIGHT = 0.30;
        public static final double MACRO_CLASS_WEIGHT = 0.35;

        // Pilier LANE_ECO
        public static final double GOLD_SENSITIVITY = 600.0;
        public static final double CS_SENSITIVITY = 1.2;
        
        public static final class Default {
            public static final double CS_SCORE_WEIGHT = 0.50;
            public static final double GOLD_SCORE_WEIGHT = 0.50;
        }

        // Pilier COMBAT
        public static final double KDA_SCORE_WEIGHT = 0.5;
        public static final double KP_SCORE_WEIGHT = 0.5;
        public static final double KP_EXPECTED = 0.50;
        public static final double KP_SENSITIVITY = 0.15;
        public static final double KDA_SENSITIVITY = 1.8;

        // Pilier MACRO_CLASS
        public static final class Mage {
            public static final double DPM_WEIGHT = 0.55;
            public static final double TEAM_DAMAGE_WEIGHT = 0.30;
            public static final double CC_WEIGHT = 0.15; // Added weight
            public static final double TEAM_DAMAGE_EXPECTED = 0.25;
            public static final double TEAM_DAMAGE_SENSITIVITY = 0.08;
            public static final double CC_EXPECTED = 10.0;
            public static final double CC_SENSITIVITY = 5.0;
            public static final double DPM_SENSITIVITY = 600.0;
        }
        public static final class Assassin {
            // Lane Eco
            public static final double CS_SCORE_WEIGHT = 0.40;
            public static final double GOLD_SCORE_WEIGHT = 0.60;
            
            // Macro Class
            public static final double SOLO_KILL_WEIGHT = 0.45;
            public static final double TEAM_DAMAGE_WEIGHT = 0.30; // Added weight
            public static final double ROAM_WEIGHT = 0.25; // Added weight
            public static final double ROAM_EXPECTED = 2.0;
            public static final double ROAM_SENSITIVITY = 1.5;
            public static final double TEAM_DAMAGE_EXPECTED = 0.25;
            public static final double TEAM_DAMAGE_SENSITIVITY = 0.08;
            public static final double SOLO_KILL_SENSITIVITY = 1.5;
        }
        public static final class ScalingCarry { // Combattant Eclair
            public static final double DPM_WEIGHT = 0.50;
            public static final double OBJECTIVE_DAMAGE_WEIGHT = 0.40;
            public static final double SOLO_KILL_WEIGHT = 0.10;
            public static final double OBJECTIVE_DAMAGE_EXPECTED = 5000.0;
            public static final double OBJECTIVE_DAMAGE_SENSITIVITY = 10000.0;
            public static final double DPM_SENSITIVITY = 150.0;
            public static final double SOLO_KILL_SENSITIVITY = 1.5;
        }
        public static final class Bruiser {
            public static final double DPM_WEIGHT = 0.40;
            public static final double TANKING_WEIGHT = 0.40;
            public static final double SOLO_KILL_WEIGHT = 0.20;
            public static final double TANKING_EXPECTED = 0.20;
            public static final double TANKING_SENSITIVITY = 0.08;
            public static final double DPM_SENSITIVITY = 150.0;
            public static final double SOLO_KILL_SENSITIVITY = 1.5;
        }
        public static final class Tank {
            public static final double TANKING_EXPECTED = 0.25;
            public static final double TANKING_SENSITIVITY = 0.08;
            public static final double CC_EXPECTED = 15.0;
            public static final double CC_SENSITIVITY = 8.0;
        }
        public static final class Enchanter {
            public static final double DPM_WEIGHT = 0.40;
            public static final double HEAL_WEIGHT = 0.40; // Added weight
            public static final double CC_WEIGHT = 0.20; // Added weight
            public static final double HEAL_EXPECTED = 4000.0;
            public static final double HEAL_SENSITIVITY = 7000.0;
            public static final double CC_EXPECTED = 10.0;
            public static final double CC_SENSITIVITY = 5.0;
            public static final double DPM_SENSITIVITY = 150.0;
        }

        // Synergies & Malus
        public static final int TERROR_ROAM_TAKEDOWNS_THRESHOLD = 3;
        public static final double TERROR_LANE_SCORE_THRESHOLD = 60.0;
        public static final double TERROR_BONUS = 10.0;
        public static final double HYPERSCALING_CS_SCORE_THRESHOLD = 80.0;
        public static final double HYPERSCALING_DPM_SCORE_THRESHOLD = 80.0;
        public static final double HYPERSCALING_BONUS = 10.0;
        public static final double STERILE_LANE_SCORE_THRESHOLD = 80.0;
        public static final double STERILE_COMBAT_SCORE_THRESHOLD = 40.0;
        public static final double STERILE_CLASS_SCORE_THRESHOLD = 50.0;
        public static final double STERILE_MALUS = -12.0;
    }

    public static final class Adc {
        // Piliers
        public static final double LANE_ECO_WEIGHT = 0.35;
        public static final double COMBAT_WEIGHT = 0.35;
        public static final double MACRO_SIEGE_WEIGHT = 0.30;

        // Pilier LANE_ECO
        public static final double CS_SCORE_WEIGHT = 0.50;
        public static final double GOLD_SCORE_WEIGHT = 0.50;
        public static final double GOLD_SENSITIVITY = 600.0;
        public static final double CS_SENSITIVITY = 1.2;

        // Pilier COMBAT
        public static final double DPM_SCORE_WEIGHT = 0.50;
        public static final double KDA_SCORE_WEIGHT = 0.40;
        public static final double KP_SCORE_WEIGHT = 0.10;
        public static final double KP_EXPECTED = 0.50;
        public static final double KP_SENSITIVITY = 0.15;
        public static final double DPM_SENSITIVITY = 150.0;
        public static final double KDA_SENSITIVITY = 1.8;

        // Pilier MACRO_SIEGE
        public static final double OBJECTIVE_DAMAGE_EXPECTED = 6000.0;
        public static final double OBJECTIVE_DAMAGE_SENSITIVITY = 2500.0;
        public static final double VISION_SENSITIVITY = 2.0;
        
        public static final class Mage {
            public static final double OBJECTIVE_DAMAGE_WEIGHT = 0.4;
            public static final double VISION_SCORE_WEIGHT = 0.6;
        }
        public static final class Default {
            public static final double OBJECTIVE_DAMAGE_WEIGHT = 0.7;
            public static final double VISION_SCORE_WEIGHT = 0.3;
        }

        // Synergies & Malus
        public static final double GLASS_CANNON_COMBAT_SCORE_THRESHOLD = 90.0;
        public static final double GLASS_CANNON_BONUS = 10.0;
        public static final double FARM_SIMULATOR_LANE_SCORE_THRESHOLD = 85.0;
        public static final double FARM_SIMULATOR_COMBAT_SCORE_THRESHOLD = 45.0;
        public static final double FARM_SIMULATOR_MALUS = -12.0;
    }

    public static final class Support {
        // Piliers
        public static final double LANE_WEIGHT = 0.30;
        public static final double IMPACT_WEIGHT = 0.35;
        public static final double UTILITY_VISION_WEIGHT = 0.35;

        // Pilier LANE
        public static final double GOLD_SENSITIVITY = 600.0;

        // Pilier IMPACT
        public static final double KP_SCORE_WEIGHT = 0.6;
        public static final double KDA_SCORE_WEIGHT = 0.4;
        public static final double KP_EXPECTED = 0.50;
        public static final double KP_SENSITIVITY = 0.15;
        public static final double KDA_SENSITIVITY = 1.8;

        // Pilier UTILITY_VISION
        public static final double VISION_SCORE_WEIGHT = 0.6;
        public static final double CONTROL_WARDS_WEIGHT = 0.4;
        public static final double VISION_SENSITIVITY = 0.6;
        public static final double CONTROL_WARDS_SENSITIVITY = 1.0;

        public static final class Enchanter {
            public static final double HEAL_EXPECTED = 10000.0;
            public static final double HEAL_SENSITIVITY = 3000.0;
            public static final double VISION_WEIGHT = 0.40;
            public static final double HEAL_WEIGHT = 0.40;
            public static final double SAVE_ALLY_WEIGHT = 0.20; // Added weight
            public static final double SAVE_ALLY_EXPECTED = 1.0;
            public static final double SAVE_ALLY_SENSITIVITY = 1.0;
            public static final double SCORE_CAP = 80.0; // Plafond de verre
        }
        public static final class Tank {
            public static final double VISION_WEIGHT = 0.40;
            public static final double TANKING_WEIGHT = 0.30;
            public static final double CC_WEIGHT = 0.30;
            public static final double TANKING_EXPECTED = 0.15;
            public static final double TANKING_SENSITIVITY = 0.05;
            public static final double CC_EXPECTED = 20.0;
            public static final double CC_SENSITIVITY = 8.0;
        }
        public static final class Mage {
            public static final double VISION_WEIGHT = 0.50;
            public static final double DPM_WEIGHT = 0.50;
            public static final double DPM_SENSITIVITY = 150.0;
        }
        
        // --- NOUVEAUX PROFILS SPÉCIFIQUES ---
        public static final class Assassin { // Pyke, Pantheon
            public static final double VISION_WEIGHT = 0.30;
            public static final double KDA_WEIGHT = 0.40; // Le kill compte !
            public static final double DPM_WEIGHT = 0.30;
            public static final double DPM_SENSITIVITY = 150.0;
        }
        public static final class Rakan { // Hybride Enchanter/Engage
            public static final double VISION_WEIGHT = 0.35;
            public static final double CC_WEIGHT = 0.35; // Grand Entrance
            public static final double HEAL_WEIGHT = 0.30; // Battle Dance
            public static final double CC_EXPECTED = 15.0;
            public static final double CC_SENSITIVITY = 8.0;
            public static final double HEAL_EXPECTED = 5000.0;
            public static final double HEAL_SENSITIVITY = 2000.0;
        }
        public static final class Bard { // Le Roamer
            public static final double VISION_WEIGHT = 0.45; // Roaming = Vision profonde
            public static final double CC_WEIGHT = 0.30;
            public static final double DPM_WEIGHT = 0.25; // Meeps poke
            public static final double CC_EXPECTED = 15.0;
            public static final double CC_SENSITIVITY = 8.0;
            public static final double DPM_SENSITIVITY = 150.0;
        }
        public static final class Senna { // Hybride ADC/Enchanter
            public static final double VISION_WEIGHT = 0.35;
            public static final double DPM_WEIGHT = 0.35;
            public static final double HEAL_WEIGHT = 0.30;
            public static final double DPM_SENSITIVITY = 150.0;
            public static final double HEAL_EXPECTED = 5000.0;
            public static final double HEAL_SENSITIVITY = 2000.0;
        }

        // Synergies & Malus
        public static final int BODYGUARD_SACRIFICIAL_DEATHS_THRESHOLD = 2;
        public static final double BODYGUARD_CLASS_SCORE_THRESHOLD = 80.0;
        public static final double BODYGUARD_BONUS = 10.0;
        public static final double BLIND_VISION_SCORE_THRESHOLD = 25.0;
        public static final double BLIND_GAME_DURATION_THRESHOLD = 20.0;
        public static final double BLIND_MALUS = -15.0;
    }
}