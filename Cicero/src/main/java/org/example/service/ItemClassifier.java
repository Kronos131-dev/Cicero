package org.example.service;

import java.util.List;
import java.util.Set;

public class ItemClassifier {

    // AP lourd
    private static final Set<Integer> AP_HEAVY_ITEMS = Set.of(
            3089, // Rabadon's Deathcap
            3157, // Zhonya's Hourglass
            3116, // Rylai's Crystal Scepter
            4645, // Shadowflame
            3285, // Luden's Companion
            4647, // Stormsurge
            3135, // Void Staff
            3152, // Hextech Rocketbelt
            6653  // Liandry's Torment
    );

    // Lethality & AD
    private static final Set<Integer> AD_LETHALITY_ITEMS = Set.of(
            3142, // Youmuu's Ghostblade
            6692, // Eclipse
            6693, // Prowler's Claw
            6694, // Serylda's Grudge
            3814, // Edge of Night
            3179, // Umbral Glaive
            3169, // Spear of Shojin
            3138, // Hubris
            6697, // Hubris
            6695, // Serpent's Glaive
            6698, // Profane Hydra
            6699, // Voltaic Cyclosword
            6701, // Opportunity
            3134, // Serrated Dirk
            // AD Items
            3072, // Bloodthirster
            3031, // Infinity Edge
            6673, // The Collector
            3046  // Phantom Dancer
    );

    // Tank
    private static final Set<Integer> TANK_ITEMS = Set.of(
            3068, // Sunfire Aegis
            3110, // Frozen Heart
            3143, // Randuin's Omen
            3075, // Thornmail
            3065, // Spirit Visage
            3193, // Gargoyle Stoneplate
            4401, // Force of Nature
            3119, // Winter's Approach / Fimbulwinter
            6662, // Iceborn Gauntlet
            6665, // Jak'Sho, The Protean
            6667, // Radiant Virtue
            3001, // Evenshroud
            3109, // Knight's Vow
            3190, // Locket of the Iron Solari
            3002, // Trailblazer
            2504, // Kaenic Rookern
            8001 // Heartsteel
    );

    public static String reclassifyChampionByBuild(List<Integer> itemIds, String initialClass) {
        if (itemIds == null || itemIds.isEmpty()) {
            return initialClass;
        }

        int apCount = 0;
        int adLethalityCount = 0;
        int tankCount = 0;

        for (Integer itemId : itemIds) {
            if (AP_HEAVY_ITEMS.contains(itemId)) apCount++;
            if (AD_LETHALITY_ITEMS.contains(itemId)) adLethalityCount++;
            if (TANK_ITEMS.contains(itemId)) tankCount++;
        }

        // Si classe = TANK mais build >= 2 items AP -> Nouvelle Classe = MAGE.
        if (initialClass.equals(ScoreCalculator.TANK) && apCount >= 2) {
            return ScoreCalculator.MAGE;
        }

        // Si classe = COMBATTANT mais build >= 2 items TANK -> Nouvelle Classe = TANK.
        if (initialClass.equals(ScoreCalculator.COMBATTANT) && tankCount >= 2) {
            return ScoreCalculator.TANK;
        }

        // Si classe = MAGE/SUPPORT mais build >= 2 items AD/Lethality -> Nouvelle Classe = ASSASSIN.
        if ((initialClass.equals(ScoreCalculator.MAGE) || initialClass.equals(ScoreCalculator.ENCHANTER)) && adLethalityCount >= 2) {
            return ScoreCalculator.ASSASSIN;
        }

        return initialClass;
    }
}
