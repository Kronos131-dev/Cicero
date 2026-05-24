package org.example.service;

import org.example.service.MatchDataExtractor.FullContext;
import org.example.service.MatchDataExtractor.ObjectiveEvent;
import org.example.service.MatchDataExtractor.PlayerContext;
import org.example.service.MatchDataExtractor.RichKillEvent;
import org.example.service.MatchDataExtractor.WardEvent;
import org.example.service.SpatialAnalyzer.Position;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rework complet de l'évaluation support (design section 7).
 *
 * Le score d'un support ne se réduit pas à "vision_score × heal × CC". On contextualise :
 *  - Vision : seules les wards qui ONT révélé un ennemi, sécurisé un objectif ou été suivies
 *             d'un kill allié dans leur zone comptent.
 *  - Heal/Shield : pondéré par les saves effectives (un ally sauvé d'une mort certaine).
 *  - CC : converti par KP — un Blitz qui pull 30 fois mais ne convertit aucun de ses pulls = 0.
 *  - Lane : on regarde le duo ADC+Support vs duo adverse, pas le sup en solo.
 *
 * Le total est exprimé en ΔWP additif à ajouter au score WEC du support.
 */
public class SupportImpactAnalyzer {

    public static final long  WARD_OBSERVATION_WINDOW_MS = 90_000L;
    public static final int   WARD_REVEAL_RADIUS         = 1500;
    public static final int   WARD_ZONE_RADIUS           = 2000;

    public static final double VALUE_PER_ENEMY_REVEALED   = 1.0;
    public static final double VALUE_PER_ALLY_KILL_NEAR   = 5.0;
    public static final double VALUE_PER_OBJECTIVE_NEAR   = 8.0;

    public static final double SAVE_BONUS_PER_OCCURRENCE  = 10.0;
    public static final double HEAL_NORM_DIVISOR          = 1000.0;

    public static final double LANE_DUO_CREDIT_SHARE      = 0.40;
    /** Conversion du score brut en ΔWP additif (calibrable). */
    public static final double SCORE_TO_WP_FACTOR         = 0.0008;

    public static class SupportImpact {
        public double visionValue;
        public double healShieldValue;
        public double ccChainValue;
        public double laneDuoGoldDiff;
        public double rawScore;       // somme avant conversion
        public double bonusWp;        // contribution à ajouter à playerDelta du WEC
        public int    wardsPlaced;
        public int    enemiesRevealedByWards;
        public int    objectivesSecuredByWards;
        public int    alliedKillsInWardedZones;
    }

    public static SupportImpact analyze(PlayerContext player, FullContext ctx) {
        SupportImpact out = new SupportImpact();
        if (player == null || ctx == null) return out;
        if (!isSupport(player.role)) {
            // Pour les non-supports : on ne calcule pas. analyse() retourne 0.
            return out;
        }

        Map<Integer, PlayerContext> byId = buildIdMap(ctx);

        // 1. Vision contextualisée
        WardOutcome wardOutcome = scoreVision(player, ctx, byId);
        out.visionValue              = wardOutcome.score;
        out.wardsPlaced              = wardOutcome.wardsPlaced;
        out.enemiesRevealedByWards   = wardOutcome.enemiesRevealed;
        out.objectivesSecuredByWards = wardOutcome.objectivesSecured;
        out.alliedKillsInWardedZones = wardOutcome.alliedKillsInZone;

        // 2. Heal/Shield contextualisé (saveAllyFromDeath × bonus + heal absolu)
        out.healShieldValue = player.saveAllyFromDeath * SAVE_BONUS_PER_OCCURRENCE
                            + player.effectiveHealAndShielding / HEAL_NORM_DIVISOR;

        // 3. CC contextualisé : CC × KP (un CC sans présence dans le kill = bruit)
        double kpFloor = Math.max(0.3, player.killParticipation);
        out.ccChainValue = player.enemyChampionImmobilizations * kpFloor;

        // 4. Lane duo gold diff (40% du crédit du duo)
        out.laneDuoGoldDiff = laneDuoGoldDiff(player, ctx);

        out.rawScore = out.visionValue + out.healShieldValue + out.ccChainValue + out.laneDuoGoldDiff;
        out.bonusWp  = out.rawScore * SCORE_TO_WP_FACTOR;
        return out;
    }

    // -----------------------------------------------------------------
    // Vision : passe les wards du joueur, compte enemy reveals / objectifs / kills proches
    // -----------------------------------------------------------------

    private static class WardOutcome {
        double score;
        int wardsPlaced;
        int enemiesRevealed;
        int objectivesSecured;
        int alliedKillsInZone;
    }

    private static WardOutcome scoreVision(PlayerContext player, FullContext ctx,
                                            Map<Integer, PlayerContext> byId) {
        WardOutcome out = new WardOutcome();
        if (ctx.wardEvents == null) return out;
        int enemyTeam = (player.teamId == 100) ? 200 : 100;

        for (WardEvent w : ctx.wardEvents) {
            if (w.placerId != player.participantId) continue;
            out.wardsPlaced++;
            long endTs = w.timestamp + WARD_OBSERVATION_WINDOW_MS;

            // Ennemis distincts qui passent dans le rayon pendant la fenêtre d'observation
            Set<Integer> revealed = new HashSet<>();
            long firstMinute = w.timestamp / 60_000L;
            long lastMinute  = endTs / 60_000L;
            for (long m = firstMinute; m <= lastMinute; m++) {
                long ts = m * 60_000L;
                for (PlayerContext e : byId.values()) {
                    if (e.teamId != enemyTeam) continue;
                    if (revealed.contains(e.participantId)) continue;
                    Position pos = SpatialAnalyzer.positionAt(e, ts);
                    if (pos == null) continue;
                    if (SpatialAnalyzer.distance(pos.x, pos.y, w.posX, w.posY) <= WARD_REVEAL_RADIUS) {
                        revealed.add(e.participantId);
                    }
                }
            }
            out.enemiesRevealed += revealed.size();
            out.score += revealed.size() * VALUE_PER_ENEMY_REVEALED;

            // Objectifs sécurisés par l'équipe alliée dans la zone
            if (ctx.objectiveEvents != null) {
                for (ObjectiveEvent o : ctx.objectiveEvents) {
                    if (o.timestamp < w.timestamp || o.timestamp > endTs) continue;
                    if (o.killerTeamId != player.teamId) continue;
                    if (SpatialAnalyzer.distance(o.posX, o.posY, w.posX, w.posY) <= WARD_ZONE_RADIUS) {
                        out.objectivesSecured++;
                        out.score += VALUE_PER_OBJECTIVE_NEAR;
                    }
                }
            }

            // Kills alliés dans la zone (proxy d'un gank évité ou d'un play réussi sur info ward)
            if (ctx.killEvents != null) {
                for (RichKillEvent k : ctx.killEvents) {
                    if (k.timestamp < w.timestamp || k.timestamp > endTs) continue;
                    PlayerContext killer = byId.get(k.killerId);
                    if (killer == null || killer.teamId != player.teamId) continue;
                    if (SpatialAnalyzer.distance(k.posX, k.posY, w.posX, w.posY) <= WARD_ZONE_RADIUS) {
                        out.alliedKillsInZone++;
                        out.score += VALUE_PER_ALLY_KILL_NEAR;
                    }
                }
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Lane duo : diff de gold combinée ADC + SUP vs adverse à frame 14
    // -----------------------------------------------------------------

    private static double laneDuoGoldDiff(PlayerContext sup, FullContext ctx) {
        if (ctx.players == null) return 0;
        PlayerContext adcAlly = null, adcEnemy = null, supEnemy = null;
        for (PlayerContext p : ctx.players.values()) {
            if (p == sup) continue;
            String role = p.role != null ? p.role.toUpperCase() : "";
            boolean isAdc = role.equals("BOTTOM") || role.equals("ADC");
            boolean isSup = role.equals("UTILITY") || role.equals("SUPPORT") || role.equals("SUP");
            if (p.teamId == sup.teamId && isAdc) adcAlly = p;
            else if (p.teamId != sup.teamId && isAdc) adcEnemy = p;
            else if (p.teamId != sup.teamId && isSup) supEnemy = p;
        }
        if (adcAlly == null || adcEnemy == null || supEnemy == null) return 0;

        int allyDuoGold = goldAtFrame(adcAlly, 14) + goldAtFrame(sup, 14);
        int enemyDuoGold = goldAtFrame(adcEnemy, 14) + goldAtFrame(supEnemy, 14);
        // 40% du crédit/blâme de la diff revient au support
        return (allyDuoGold - enemyDuoGold) * LANE_DUO_CREDIT_SHARE / 1000.0;
    }

    private static int goldAtFrame(PlayerContext p, int targetFrame) {
        if (p.trajectory == null || p.trajectory.frameCount == 0) return 0;
        int idx = Math.max(0, Math.min(targetFrame, p.trajectory.frameCount - 1));
        return p.trajectory.goldCumulative[idx];
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static boolean isSupport(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return r.equals("UTILITY") || r.equals("SUPPORT") || r.equals("SUP");
    }

    private static Map<Integer, PlayerContext> buildIdMap(FullContext ctx) {
        Map<Integer, PlayerContext> m = new HashMap<>();
        if (ctx.players == null) return m;
        for (PlayerContext p : ctx.players.values()) m.put(p.participantId, p);
        return m;
    }
}
