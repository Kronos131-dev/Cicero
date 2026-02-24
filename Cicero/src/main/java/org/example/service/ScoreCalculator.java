package org.example.service;

import org.json.JSONObject;

public class ScoreCalculator {

    /**
     * Calcule une note sur 100 pour un joueur donné en fonction de son rôle et de ses stats.
     */
    public static int calculateScore(JSONObject player, long gameDurationMinutes) {
        if (gameDurationMinutes < 1) gameDurationMinutes = 20; // Sécurité

        String role = player.optString("role", "UNKNOWN");
        // Normalisation des rôles
        if (role.equals("UTILITY") || role.equals("SUPPORT")) role = "SUPPORT";
        else if (role.equals("BOTTOM") || role.equals("ADC")) role = "ADC";
        else if (role.equals("JUNGLE")) role = "JUNGLE";
        else if (role.equals("MIDDLE") || role.equals("MID")) role = "MID";
        else role = "TOP";

        // Stats de base
        int k = player.optInt("k", 0);
        int d = player.optInt("d", 0);
        int a = player.optInt("a", 0);
        int cs = player.optInt("cs", 0);
        int vision = player.optInt("vision_score", 0);
        int gold = player.optInt("gold", 0);
        
        // Stats avancées (si disponibles)
        double kp = 0;
        double dmgPercent = 0;
        if (player.has("advanced")) {
            JSONObject adv = player.getJSONObject("advanced");
            kp = adv.optDouble("kp_percent", 0);
            dmgPercent = adv.optDouble("dmg_percent", 0);
        } else {
            // Estimation grossière si pas d'advanced stats
            kp = (k + a) / 20.0; // Arbitraire
        }

        // Métriques dérivées
        double csPerMin = (double) cs / gameDurationMinutes;
        double visionPerMin = (double) vision / gameDurationMinutes;
        double goldPerMin = (double) gold / gameDurationMinutes;

        // Calcul du score de base (50 départ)
        double score = 50.0;

        // 1. Impact KDA (Universel mais pondéré)
        // Un bon KDA monte la note, beaucoup de morts la descendent vite
        score += (k * 2.0) + (a * 1.0) - (d * 2.5);

        // 2. Bonus/Malus par Rôle
        switch (role) {
            case "ADC":
            case "MID":
            case "TOP":
                // Farming (Attendu: ~7-8 CS/min pour une bonne note)
                if (csPerMin >= 9) score += 15;
                else if (csPerMin >= 7) score += 10;
                else if (csPerMin >= 5) score += 0; // Neutre
                else score -= 10; // Mauvais farm

                // Dégâts (Carry)
                if (dmgPercent > 0.30) score += 15; // 30% des dégâts de l'équipe
                else if (dmgPercent > 0.20) score += 5;
                else if (dmgPercent < 0.10) score -= 10;
                break;

            case "JUNGLE":
                // KP est roi
                if (kp > 0.70) score += 20;
                else if (kp > 0.50) score += 10;
                else if (kp < 0.30) score -= 10;

                // Objectifs (Difficile à tracker individuellement sans events précis, on utilise le gold/vision comme proxy d'activité)
                if (goldPerMin > 400) score += 5;
                if (visionPerMin > 1.0) score += 5;
                break;

            case "SUPPORT":
                // Vision est reine
                if (visionPerMin > 2.0) score += 20;
                else if (visionPerMin > 1.5) score += 10;
                else if (visionPerMin < 0.8) score -= 10;

                // KP
                if (kp > 0.60) score += 15;
                
                // Pas de pénalité de farm, au contraire, trop de farm c'est louche
                if (csPerMin > 2) score -= 5; 
                break;
        }

        // 3. Bonus Win/Loss
        boolean win = player.optBoolean("win", false);
        if (win) score += 10;
        else score -= 5; // C'est dur mais c'est la vie

        // 4. Bornage 0-100
        if (score > 100) score = 100;
        if (score < 0) score = 0;

        return (int) score;
    }
}