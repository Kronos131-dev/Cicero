package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

public class MatchNarrator {

    public static String buildPlayerDigest(JSONObject player, MatchDataExtractor.PlayerContext ctx, MatchDataExtractor.PlayerContext oppCtx) {
        StringBuilder digest = new StringBuilder();

        // 1. IDENTITÉ ET RÔLE DYNAMIQUE
        String champion = player.optString("champion", "Inconnu");
        String role = player.optString("role", "Inconnu");
        boolean win = player.optBoolean("win", false);

        digest.append("=== PROFIL MACRO : ").append(champion).append(" (").append(role).append(") ===\n");
        digest.append("- Résultat: ").append(win ? "VICTOIRE" : "DÉFAITE").append("\n");

        if (ctx == null) return digest.append("⚠️ Données indisponibles.\n").toString();

        // 2. LE DUEL (L'HISTOIRE DE LA LANE)
        digest.append("\n--- ANALYSE DU DUEL DIRECT ---\n");
        String laneStatus = (ctx.goldDiffAt14 > 1000) ? "Domination totale" :
                (ctx.goldDiffAt14 < -1000) ? "A sombré" : "Lane équilibrée";
        digest.append("- Statut Lane: ").append(laneStatus).append(" (Diff: ").append(ctx.goldDiffAt14).append("g à 14min).\n");
        digest.append("- Farming: ").append(player.optInt("cs", 0)).append(" CS total (Avantage Max: ").append(ctx.maxCsAdvantage).append(").\n");

        if (ctx.earlySoloDeaths > 0) {
            digest.append("- Alerte: S'est fait dominer en 1v1 pur (").append(ctx.earlySoloDeaths).append(" morts solo).\n");
        }
        if (ctx.earlyGankDeaths > 0) {
            digest.append("- Pression subie: A subi ").append(ctx.earlyGankDeaths).append(" ganks mortels en early (Focus ennemi).\n");
        }

        // 3. IMPACT ET RESPONSABILITÉ (LE "CARRY-CHECK")
        digest.append("\n--- IMPACT ET RÉPARTITION DES RESSOURCES ---\n");
        digest.append("- Poids Offensif: ").append(String.format("%.1f", ctx.teamDamagePercentage * 100)).append("% des dégâts de l'équipe.\n");
        digest.append("- Présence Map: ").append(String.format("%.1f", ctx.killParticipation * 100)).append("% de participation aux kills.\n");

        // Détection de pattern : "Le Riche Inutile" ou "Le Pauvre Utile"
        if (ctx.goldPerMinute > 450 && ctx.teamDamagePercentage < 0.18) {
            digest.append("- Pattern détecté: [VAMPIRISATION] Gros revenus mais impact combat très faible.\n");
        } else if (ctx.goldPerMinute < 350 && ctx.killParticipation > 0.50) {
            digest.append("- Pattern détecté: [WEAKSIDE HERO] Très peu de ressources mais impact map majeur.\n");
        }

        // 4. ACTIONS DÉCISIVES (LES MOMENTS CLÉS)
        digest.append("\n--- ACTIONS DÉCISIVES ---\n");
        ctx.voidGrubsKills = Math.min(3, ctx.voidGrubsKills);
        if (ctx.earlyRoamTakedowns > 0) digest.append("- Roaming: ").append(ctx.earlyRoamTakedowns).append(" décalages gagnants en early.\n");
        if (ctx.voidGrubsKills > 0) digest.append("- Objectifs: A sécurisé ").append(ctx.voidGrubsKills).append(" Larves du Néant.\n");
        if (ctx.dragonTakedowns > 0) digest.append("- Objectifs: A sécurisé ").append(ctx.dragonTakedowns).append(" dragons.\n");
        if (ctx.baronTakedowns > 0) digest.append("- Objectifs: A sécurisé ").append(ctx.voidGrubsKills).append(" Heraut de la Faille.\n");
        if (ctx.heraldTakedowns > 0) digest.append("- Objectifs: A sécurisé ").append(ctx.voidGrubsKills).append(" Baron Nashor.\n");
        if (ctx.epicMonsterSteals > 0) digest.append("- CLUTCH: A volé ").append(ctx.epicMonsterSteals).append(" objectif(s) majeur(s) !\n");
        if (ctx.saveAllyFromDeath > 0) digest.append("- Sauvetages: A sauvé ").append(ctx.saveAllyFromDeath).append(" alliés d'une mort certaine.\n");

        // 5. CAUSALITÉ DES MORTS (POURQUOI LA GAME A TOURNÉ ?)
        digest.append("\n--- AUTOPSIE ET CAUSALITÉ --- \n");
        if (ctx.throwDeaths > 0) {
            digest.append("- ERREUR CRITIQUE: Mort isolé(e) ").append(ctx.throwDeaths).append(" fois, offrant un objectif gratuit en face.\n");
        }
        if (ctx.sacrificialDeaths > 0) {
            digest.append("- SACRIFICE UTILE: Est mort(e) ").append(ctx.sacrificialDeaths).append(" fois pour permettre un Nashor/Drake allié.\n");
        }
        if (ctx.lateGameDeaths > 2) {
            digest.append("- POSITIONNEMENT: ").append(ctx.lateGameDeaths).append(" morts en late game (Souvent fatal pour un carry).\n");
        }

        return digest.toString();
    }
}