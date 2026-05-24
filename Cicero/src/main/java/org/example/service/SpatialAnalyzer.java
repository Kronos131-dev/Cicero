package org.example.service;

import org.example.service.MatchDataExtractor.PlayerContext;
import org.example.service.MatchDataExtractor.PlayerTrajectory;
import org.example.service.MatchDataExtractor.RichKillEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Analyse spatiale du match : clustering de teamfights, détection de pickoffs,
 * détection de roams, requêtes "qui est dans le rayon X à l'instant T".
 *
 * Toutes les positions sont exprimées dans le système de coordonnées Riot (Summoner's Rift,
 * ~0..14820 sur chaque axe). Le Nexus bleu est en bas-gauche (~1500,1500), le rouge en
 * haut-droit (~13500,13500). TOP = corridor haut-gauche, BOT = corridor bas-droit.
 */
public class SpatialAnalyzer {

    // --- Constantes spatiales / temporelles ---
    public static final int  MAP_SIZE                   = 14820;
    public static final int  TEAMFIGHT_CLUSTER_RADIUS   = 2500;
    public static final long TEAMFIGHT_CLUSTER_WINDOW_MS = 15_000L;
    public static final int  PICKOFF_ISOLATION_RADIUS   = 2000;
    public static final int  PRESENCE_RADIUS            = 2500;
    public static final int  LANE_DEVIATION_THRESHOLD   = 4000;
    public static final long LANE_PHASE_END_MS          = 14L * 60_000L;

    // Centres approximatifs des corridors de lane (lane phase)
    private static final int[] TOP_LANE_MID = { 3500, 10500 };
    private static final int[] MID_LANE_MID = { 7500,  7500 };
    private static final int[] BOT_LANE_MID = {10500,  3500 };

    public static class Position {
        public final int x;
        public final int y;
        public Position(int x, int y) { this.x = x; this.y = y; }
    }

    public static class Teamfight {
        public long startTime;
        public long endTime;
        public int  centerX;
        public int  centerY;
        public final List<RichKillEvent> kills = new ArrayList<>();
        public int killsBlue;
        public int killsRed;

        void addKill(RichKillEvent k, int killerTeamId) {
            kills.add(k);
            if (killerTeamId == 100) killsBlue++;
            else if (killerTeamId == 200) killsRed++;
        }

        public double durationSec() { return (endTime - startTime) / 1000.0; }
        public int    totalKills()  { return killsBlue + killsRed; }
        public int    winningTeam() {
            if (killsBlue > killsRed) return 100;
            if (killsRed > killsBlue) return 200;
            return 0;
        }
    }

    // =====================================================================
    // 1. Clustering spatio-temporel des kills en teamfights
    // =====================================================================

    /**
     * Regroupe les CHAMPION_KILL en teamfights par proximité spatiale ET temporelle.
     * Algorithme glouton : un kill rejoint le cluster en cours si Δt ≤ {@link #TEAMFIGHT_CLUSTER_WINDOW_MS}
     * ET distance(kill, centre_courant) ≤ {@link #TEAMFIGHT_CLUSTER_RADIUS}.
     */
    public static List<Teamfight> clusterTeamfights(List<RichKillEvent> kills,
                                                    Map<Integer, PlayerContext> playersById) {
        List<Teamfight> result = new ArrayList<>();
        if (kills == null || kills.isEmpty()) return result;

        List<RichKillEvent> sorted = new ArrayList<>(kills);
        sorted.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));

        Teamfight current = null;
        for (RichKillEvent k : sorted) {
            int killerTeamId = teamIdOf(k.killerId, playersById);

            if (current != null
                    && k.timestamp - current.endTime <= TEAMFIGHT_CLUSTER_WINDOW_MS
                    && distance(k.posX, k.posY, current.centerX, current.centerY) <= TEAMFIGHT_CLUSTER_RADIUS) {

                int n = current.kills.size();
                current.centerX = (current.centerX * n + k.posX) / (n + 1);
                current.centerY = (current.centerY * n + k.posY) / (n + 1);
                current.endTime = k.timestamp;
                current.addKill(k, killerTeamId);

            } else {
                if (current != null) result.add(current);
                current = new Teamfight();
                current.startTime = k.timestamp;
                current.endTime   = k.timestamp;
                current.centerX   = k.posX;
                current.centerY   = k.posY;
                current.addKill(k, killerTeamId);
            }
        }
        if (current != null) result.add(current);
        return result;
    }

    // =====================================================================
    // 2. Requêtes positionnelles à un instant donné
    // =====================================================================

    /**
     * Renvoie la position du joueur à un timestamp donné, via la frame la plus proche
     * de sa trajectoire. Renvoie null si pas de trajectoire.
     */
    public static Position positionAt(PlayerContext player, long timestampMs) {
        if (player == null || player.trajectory == null || player.trajectory.frameCount == 0) {
            return null;
        }
        PlayerTrajectory t = player.trajectory;
        int idx = (int) Math.round(timestampMs / 60_000.0);
        if (idx < 0) idx = 0;
        if (idx >= t.frameCount) idx = t.frameCount - 1;
        return new Position(t.positionsX[idx], t.positionsY[idx]);
    }

    /**
     * Renvoie les joueurs dans le rayon donné autour d'une position à un instant donné.
     * Si {@code filterTeamId} n'est pas null, ne renvoie que les joueurs de cette équipe.
     */
    public static List<PlayerContext> playersInRadius(int posX, int posY, int radius, long timestampMs,
                                                      Map<Integer, PlayerContext> playersById,
                                                      Integer filterTeamId) {
        List<PlayerContext> hits = new ArrayList<>();
        if (playersById == null) return hits;
        for (PlayerContext p : playersById.values()) {
            if (filterTeamId != null && p.teamId != filterTeamId) continue;
            Position pos = positionAt(p, timestampMs);
            if (pos == null) continue;
            if (distance(pos.x, pos.y, posX, posY) <= radius) hits.add(p);
        }
        return hits;
    }

    // =====================================================================
    // 3. Détection de pickoffs
    // =====================================================================

    /**
     * Un pickoff = kill où la victime était isolée (aucun allié dans le rayon
     * {@link #PICKOFF_ISOLATION_RADIUS} au moment du kill).
     * NB : la victime elle-même est dans son propre rayon → on tolère <= 1 personne.
     */
    public static boolean isPickoff(RichKillEvent kill, Map<Integer, PlayerContext> playersById) {
        PlayerContext victim = playersById.get(kill.victimId);
        if (victim == null) return false;
        List<PlayerContext> allies = playersInRadius(
                kill.posX, kill.posY, PICKOFF_ISOLATION_RADIUS,
                kill.timestamp, playersById, victim.teamId);
        return allies.size() <= 1;
    }

    // =====================================================================
    // 4. Positions de lane attendues & détection de roam
    // =====================================================================

    /**
     * Position attendue du joueur en lane phase. Renvoie null après {@link #LANE_PHASE_END_MS}
     * ou pour les rôles sans position fixe (jungle, none).
     */
    public static Position expectedLanePosition(String role, long timestampMs) {
        if (timestampMs > LANE_PHASE_END_MS) return null;
        if (role == null) return null;
        return switch (role.toUpperCase()) {
            case "TOP"                              -> new Position(TOP_LANE_MID[0], TOP_LANE_MID[1]);
            case "MIDDLE", "MID"                    -> new Position(MID_LANE_MID[0], MID_LANE_MID[1]);
            case "BOTTOM", "BOT", "ADC"             -> new Position(BOT_LANE_MID[0], BOT_LANE_MID[1]);
            case "UTILITY", "SUPPORT", "SUP"        -> new Position(BOT_LANE_MID[0], BOT_LANE_MID[1]);
            default -> null;
        };
    }

    /**
     * Vrai si le joueur est hors de sa lane attendue de plus de
     * {@link #LANE_DEVIATION_THRESHOLD} unités à ce timestamp.
     */
    public static boolean isRoaming(PlayerContext player, long timestampMs) {
        if (player == null) return false;
        Position pos = positionAt(player, timestampMs);
        if (pos == null) return false;
        Position expected = expectedLanePosition(player.role, timestampMs);
        if (expected == null) return false;
        return distance(pos.x, pos.y, expected.x, expected.y) > LANE_DEVIATION_THRESHOLD;
    }

    // =====================================================================
    // 5. Helpers
    // =====================================================================

    public static double distance(int x1, int y1, int x2, int y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static int teamIdOf(int participantId, Map<Integer, PlayerContext> playersById) {
        if (playersById == null) return 0;
        PlayerContext p = playersById.get(participantId);
        return p != null ? p.teamId : 0;
    }
}
