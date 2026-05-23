package org.example.service.scoring;

import org.example.service.MatchDataExtractor;
import org.example.service.ScoreCalculator;
import org.example.service.ScoringConstants;
import static org.example.service.ScoreCalculator.*;

public class SupportScoringStrategy extends BaseScoringStrategy {

    @Override
    public void calculateScore(ScoreCalculator.ScoreResult res,
                               MatchDataExtractor.PlayerContext ctx,
                               MatchDataExtractor.PlayerContext oppCtx,
                               ScoreCalculator.RoleBenchmarks bench,
                               double durationMin,
                               String champClass,
                               MatchDataExtractor.TeamCompositionProfile enemyComp) {

        // ==========================================
        // PILIER 1 : EARLY_GAME (20%) - "Lane & Map Pressure"
        // ==========================================
        // On juge la capacité à gagner la lane (Gold Diff) et à impacter la carte (Roams).
        double lanePressure = calculateNormalizedScore(ctx.goldDiffAt14, 200, 500); // 500g de diff = très bon
        double roamImpact = calculateNormalizedScore(ctx.earlyRoamTakedowns, 0.5, 1.5); // 2 roams = excellent

        double pEarly = (lanePressure * 0.5) + (roamImpact * 0.5);
        res.setPillar("EARLY_GAME", pEarly, 0.20,
                String.format("Pression: %+dg, Roams: %d", ctx.goldDiffAt14, ctx.earlyRoamTakedowns));

        // ==========================================
        // PILIER 2 : COMBAT (30%) - "Efficiency & Clutch"
        // ==========================================
        // La KP est la base, mais elle est pondérée par la survie intelligente.
        double kpScore = calculateNormalizedScore(ctx.killParticipation, ScoringConstants.Support.KP_EXPECTED, 0.15);
        double kdaScore = calculateNormalizedScore(ctx.kda, bench.expectedKda, 2.0);

        // Sanction massive pour les morts qui offrent des objectifs (Audit Causalité)
        // Une mort "Unforced" en late game (via unforcedErrorDeaths) impacte plus qu'une mort classique.
        double utilityImpact = (kpScore * 0.6) + (kdaScore * 0.4);

        // Déduction de points pour erreurs critiques (late game catch-out)
        double unforcedPenalty = ctx.unforcedErrorDeaths * 12.0;
        double pCombat = Math.max(0, utilityImpact - unforcedPenalty);

        res.setPillar("COMBAT", pCombat, 0.30,
                String.format("KP: %.0f%%, KDA: %.1f, Erreurs: %d", ctx.killParticipation * 100, ctx.kda, ctx.unforcedErrorDeaths));

        // ==========================================
        // PILIER 3 : MACRO_MAP (25%) - "Vision Quality"
        // ==========================================
        // ANTI-PADDING : On utilise la couverture de rivière et l'avantage sur l'adversaire.
        double actualVisPerMin = ctx.visionScore / durationMin;

        // La valeur réelle de la vision : Couverture des zones neutres (River/Enemy Half)
        // riverControlWardCoverage est un ratio de 0.0 à 1.0 (ou plus selon Riot)
        double visionQuality = calculateNormalizedScore(ctx.riverControlWardCoverage, 0.25, 0.20);

        // Domination directe sur le support adverse
        double visionAdv = calculateNormalizedScore(ctx.visionScoreAdvantage, 0.2, 0.4);

        double pMacro = (visionQuality * 0.6) + (visionAdv * 0.4);

        // Si le support n'achète aucune Pink (Control Ward), on plafonne la note Macro à 60.
        if (ctx.controlWardsPlaced < 2 && durationMin > 20) pMacro = Math.min(pMacro, 60);

        res.setPillar("MACRO_MAP", pMacro, 0.25,
                String.format("Qualité Vision: %.0f%%, Adv/Opp: %.0f%%", ctx.riverControlWardCoverage * 100, ctx.visionScoreAdvantage * 100));

        // ==========================================
        // PILIER 4 : CLASS_IDENTITY (25%) - "True Utility"
        // ==========================================
        double pClass = 50.0;
        String reason = "";

        switch (champClass) {
            case ENCHANTER -> {
                // ANTI-HEAL PADDING : Le score de soin est indexé sur les sauvetages.
                double rawHealScore = calculateNormalizedScore(ctx.effectiveHealAndShielding, ScoringConstants.Support.Enchanter.HEAL_EXPECTED, 5000);
                double saveBonus = calculateNormalizedScore(ctx.saveAllyFromDeath, 1.0, 2.0);

                // Si 0 saves, le score de soin est bridé à 70 (on ne récompense pas le spam passif)
                pClass = (rawHealScore * 0.4) + (saveBonus * 0.6);
                if (ctx.saveAllyFromDeath == 0) pClass = Math.min(pClass, 70);
                reason = String.format("Utility: %.0f H/S, %d Saves", ctx.effectiveHealAndShielding, ctx.saveAllyFromDeath);
            }
            case TANK -> {
                double ccScore = calculateNormalizedScore(ctx.enemyChampionImmobilizations, ScoringConstants.Support.Tank.CC_EXPECTED, 10);
                double tankScore = calculateNormalizedScore(ctx.damageTakenOnTeamPercentage, 0.25, 0.10);
                pClass = (ccScore * 0.6) + (tankScore * 0.4);
                reason = String.format("Engage: %d CC, %.0f%% Tanking", ctx.enemyChampionImmobilizations, ctx.damageTakenOnTeamPercentage * 100);
            }
            case MAGE, ASSASSIN -> {
                double dpmScore = calculateNormalizedScore(ctx.damagePerMinute, bench.expectedDpm, 150);
                pClass = (dpmScore * 0.7) + (kpScore * 0.3);
                reason = String.format("Carry Supp: %.0f DPM", ctx.damagePerMinute);
            }
            default -> {
                pClass = (kpScore * 0.5) + (visionQuality * 50);
                reason = "Impact Standard";
            }
        }
        res.setPillar("CLASS_IDENTITY", pClass, 0.25, reason);

        // ==========================================
        // SYNERGIES & 1VS9 VALIDATION
        // ==========================================

        // Bonus "1vs9" : Un support qui domine la vision, roame et sauve tout le monde.
        if (ctx.killParticipation > 0.75 && ctx.earlyRoamTakedowns >= 2 && ctx.saveAllyFromDeath >= 3) {
            res.addSynergy(15.0, "SUPPORT CANYON : Omniprésence totale et sauvetages critiques.");
        }

        // Malus "KDA Player" : Score de vision ridicule malgré un KDA correct.
        if (actualVisPerMin < 1.2 && ctx.deaths == 0 && durationMin > 25) {
            res.addSynergy(-20.0, "PASSIVITÉ : KDA préservé mais impact Macro inexistant.");
        }

        // Malus "Thrower" : Si le support a plus d'erreurs non forcées que le reste de son équipe.
        if (ctx.unforcedErrorDeaths >= 3) {
            res.addSynergy(-15.0, "INSTABILITÉ : Erreurs isolées ayant coûté des objectifs majeurs.");
        }

        // Ajustement final pour la rareté du 100+
        // On n'atteint 100+ que si le score total est déjà exceptionnel ET qu'il y a des synergies.
    }
}