package org.example.service;

import org.example.service.MatchDataExtractor.FullContext;
import org.example.service.MatchDataExtractor.PlayerContext;
import org.example.service.MatchDataExtractor.PlayerTrajectory;
import org.example.service.MatchDataExtractor.RichKillEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Dérive le profil de jeu observé d'un joueur depuis ses courbes (gold, dégâts) et sa
 * trajectoire spatiale. Aucun prior champion : on observe ce qui s'est passé dans CETTE partie.
 *
 * Le profil sert à valider la cohérence narrative de la performance, pas à pré-classer.
 */
public class BehaviorProfiler {

    public enum PlayerProfile {
        SCALER,           // courbe d'or/dmg explose en mid/late
        EARLY_AGGRESSOR,  // activité massive avant 15 min
        SPLITPUSHER,      // loin du centre d'équipe + dégâts aux bâtiments
        ROAMER,           // mobilité élevée + takedowns hors lane
        TEAMFIGHTER       // défaut : présent en groupe, scaling régulier
    }

    public static class ObservedBehavior {
        public double goldSlopeEarly;
        public double goldSlopeMid;
        public double goldSlopeLate;
        public double dpmEarly;
        public double dpmMid;
        public double dpmLate;
        public double avgDistanceFromTeam;
        public double laneFidelity;     // [0,1] ratio de frames en lane phase passées dans son corridor
        public double mapMobility;      // unités parcourues par minute
        public int    killsBefore15min;
        public PlayerProfile profile;
    }

    // Seuils heuristiques (calibrables)
    private static final double SCALER_GOLD_RATIO = 1.5;
    private static final double SCALER_DPM_RATIO  = 1.4;
    private static final int    EARLY_KILL_THRESHOLD = 3;
    private static final double EARLY_DPM_DOMINANCE  = 0.8;  // dpmEarly > dpmLate × 0.8
    private static final double SPLITPUSH_DISTANCE   = 6000.0;
    private static final double SPLITPUSH_OBJ_RATIO  = 0.20;  // ≥ 20% des dégâts en dommages aux objectifs
    private static final double ROAMER_MOBILITY      = 1800.0; // unités/min
    private static final int    ROAMER_TAKEDOWN_MIN  = 2;

    public static ObservedBehavior profile(PlayerContext player, FullContext ctx) {
        ObservedBehavior obs = new ObservedBehavior();

        if (player == null || player.trajectory == null || player.trajectory.frameCount == 0) {
            obs.profile = PlayerProfile.TEAMFIGHTER;
            return obs;
        }

        PlayerTrajectory t = player.trajectory;
        int n = t.frameCount;

        // -- Pentes d'or par phase --
        int g0  = valueAt(t.goldCumulative, n, 0);
        int g10 = valueAt(t.goldCumulative, n, 10);
        int g20 = valueAt(t.goldCumulative, n, 20);
        int g30 = valueAt(t.goldCumulative, n, 30);

        obs.goldSlopeEarly = (g10 - g0)  / 10.0;
        obs.goldSlopeMid   = (g20 - g10) / 10.0;
        obs.goldSlopeLate  = (g30 - g20) / 10.0;

        // -- DPM par phase (dégâts aux champions) --
        int d0  = valueAt(t.dmgChampsCumul, n, 0);
        int d10 = valueAt(t.dmgChampsCumul, n, 10);
        int d20 = valueAt(t.dmgChampsCumul, n, 20);
        int d30 = valueAt(t.dmgChampsCumul, n, 30);

        obs.dpmEarly = (d10 - d0)  / 10.0;
        obs.dpmMid   = (d20 - d10) / 10.0;
        obs.dpmLate  = (d30 - d20) / 10.0;

        // -- Spatial --
        obs.avgDistanceFromTeam = computeAvgDistanceFromTeam(player, ctx);
        obs.laneFidelity        = computeLaneFidelity(player);
        obs.mapMobility         = computeMapMobility(player);
        obs.killsBefore15min    = countKillsBefore(player.participantId, ctx, 15 * 60_000L);

        obs.profile = deriveProfile(obs, player);
        return obs;
    }

    // -----------------------------------------------------------------
    // Dérivation du profil
    // -----------------------------------------------------------------

    private static PlayerProfile deriveProfile(ObservedBehavior obs, PlayerContext player) {
        // SCALER : la pente late dépasse nettement la pente early
        boolean scaler = obs.goldSlopeEarly > 0
                && obs.goldSlopeLate >= obs.goldSlopeEarly * SCALER_GOLD_RATIO
                && obs.dpmEarly > 0
                && obs.dpmLate >= obs.dpmEarly * SCALER_DPM_RATIO;
        if (scaler) return PlayerProfile.SCALER;

        // EARLY_AGGRESSOR : DPM early aussi élevé que late + ≥3 kills avant 15 min
        boolean earlyAggressor = obs.killsBefore15min >= EARLY_KILL_THRESHOLD
                && obs.dpmEarly >= obs.dpmLate * EARLY_DPM_DOMINANCE
                && obs.dpmEarly > 0;
        if (earlyAggressor) return PlayerProfile.EARLY_AGGRESSOR;

        // SPLITPUSHER : loin du centre d'équipe + part importante de dégâts aux structures
        double totalDmg = player.damagePerMinute > 0 && obs.mapMobility >= 0 ? approxTotalDamage(player) : 0;
        double splitRatio = totalDmg > 0 ? (player.damageDealtToObjectives / totalDmg) : 0;
        boolean splitpusher = obs.avgDistanceFromTeam > SPLITPUSH_DISTANCE
                && splitRatio >= SPLITPUSH_OBJ_RATIO;
        if (splitpusher) return PlayerProfile.SPLITPUSHER;

        // ROAMER : mobilité élevée + takedowns hors lane
        boolean roamer = obs.mapMobility >= ROAMER_MOBILITY
                && player.earlyRoamTakedowns >= ROAMER_TAKEDOWN_MIN;
        if (roamer) return PlayerProfile.ROAMER;

        return PlayerProfile.TEAMFIGHTER;
    }

    private static double approxTotalDamage(PlayerContext p) {
        // Approximation : DPM × durée. La trajectory finale donne aussi ça via dmgChampsCumul[last],
        // mais ici on inclut dmg aux objectifs donc on prend une estimation correcte par DPM × durée.
        if (p.trajectory == null || p.trajectory.frameCount == 0) return 0;
        return p.damagePerMinute * p.trajectory.frameCount + p.damageDealtToObjectives;
    }

    // -----------------------------------------------------------------
    // Calculs spatiaux
    // -----------------------------------------------------------------

    private static double computeAvgDistanceFromTeam(PlayerContext player, FullContext ctx) {
        if (player.trajectory == null || player.trajectory.frameCount == 0) return 0;
        if (ctx == null || ctx.players == null) return 0;

        List<PlayerContext> allies = new ArrayList<>();
        for (PlayerContext p : ctx.players.values()) {
            if (p.teamId == player.teamId && p.participantId != player.participantId
                    && p.trajectory != null && p.trajectory.frameCount > 0) {
                allies.add(p);
            }
        }
        if (allies.isEmpty()) return 0;

        int n = player.trajectory.frameCount;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            int cx = 0, cy = 0, c = 0;
            for (PlayerContext a : allies) {
                if (i < a.trajectory.frameCount) {
                    cx += a.trajectory.positionsX[i];
                    cy += a.trajectory.positionsY[i];
                    c++;
                }
            }
            if (c == 0) continue;
            cx /= c;
            cy /= c;
            double dx = player.trajectory.positionsX[i] - cx;
            double dy = player.trajectory.positionsY[i] - cy;
            sum += Math.sqrt(dx * dx + dy * dy);
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private static double computeLaneFidelity(PlayerContext player) {
        if (player.trajectory == null || player.trajectory.frameCount == 0) return 0;
        // Limité à la lane phase (frames 0..14 = minutes 0..14)
        int n = Math.min(player.trajectory.frameCount, 15);
        if (n == 0) return 0;
        SpatialAnalyzer.Position expected = SpatialAnalyzer.expectedLanePosition(player.role, 0);
        if (expected == null) return 1.0; // jungle/none : pas de fidélité de lane
        int inLane = 0;
        for (int i = 0; i < n; i++) {
            double dx = player.trajectory.positionsX[i] - expected.x;
            double dy = player.trajectory.positionsY[i] - expected.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d <= SpatialAnalyzer.LANE_DEVIATION_THRESHOLD) inLane++;
        }
        return (double) inLane / n;
    }

    private static double computeMapMobility(PlayerContext player) {
        if (player.trajectory == null || player.trajectory.frameCount < 2) return 0;
        PlayerTrajectory t = player.trajectory;
        double total = 0;
        for (int i = 1; i < t.frameCount; i++) {
            double dx = t.positionsX[i] - t.positionsX[i - 1];
            double dy = t.positionsY[i] - t.positionsY[i - 1];
            total += Math.sqrt(dx * dx + dy * dy);
        }
        // Mobilité moyenne par minute (entre frames consécutives = ~1 min)
        return total / (t.frameCount - 1);
    }

    private static int countKillsBefore(int participantId, FullContext ctx, long cutoffMs) {
        if (ctx == null || ctx.killEvents == null) return 0;
        int count = 0;
        for (RichKillEvent k : ctx.killEvents) {
            if (k.killerId == participantId && k.timestamp <= cutoffMs) count++;
        }
        return count;
    }

    private static int valueAt(int[] data, int frameCount, int targetMinute) {
        if (data == null || frameCount == 0) return 0;
        int idx = Math.max(0, Math.min(frameCount - 1, targetMinute));
        return data[idx];
    }
}
