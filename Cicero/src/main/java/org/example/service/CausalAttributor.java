package org.example.service;

import org.example.service.MatchDataExtractor.FullContext;
import org.example.service.MatchDataExtractor.ObjectiveEvent;
import org.example.service.MatchDataExtractor.PlayerContext;
import org.example.service.MatchDataExtractor.RichKillEvent;
import org.example.service.WinEquityComputer.GameState;
import org.example.service.WinEquityComputer.WinEquityTimeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attribution causale du ΔWP par event aux joueurs impliqués.
 *
 * - CHAMPION_KILL : killer 60%, chaque assist 30%/n, allié présent dans rayon 2500 10%/n.
 *   Victime : pénalité -base × targetThreat (×1.5 si throw).
 * - ELITE_MONSTER_KILL : smiter 40%, alliés dans rayon 3000 partagent 60%.
 * - BUILDING_KILL : killer 40%, alliés dans rayon 3500 partagent 60%.
 *
 * Le ΔWP d'un kill est modulé par la formule kill value (design 5.2) :
 *   killerΔWP = baseΔWP × targetThreat × snowball × resourceEfficiency + opportunityCost
 */
public class CausalAttributor {

    // Rayons d'attribution (cf. design section 5.1)
    public static final int PRESENCE_RADIUS_KILL  = 2500;
    public static final int PRESENCE_RADIUS_OBJ   = 3000;
    public static final int PRESENCE_RADIUS_BUILD = 3500;

    // Fenêtre pour snowball / opportunity cost
    public static final long DOWNSTREAM_WINDOW_MS = 60_000L;

    // Seuils kill value
    public static final double TARGET_THREAT_MIN = 0.3;
    public static final double TARGET_THREAT_MAX = 5.0;
    public static final double TARGET_WEAK_THRESHOLD = 0.8;
    public static final double THROW_WP_THRESHOLD = 0.55;

    public static class EventBreakdown {
        public final long timestamp;
        public final String eventType;     // CHAMPION_KILL, ELITE_MONSTER_KILL, BUILDING_KILL
        public final String eventSubType;  // DRAGON, BARON_NASHOR, TOWER_BUILDING, etc. ou null
        public final int    primaryActorId;
        public final double rawDeltaWP;
        public final double adjustedDeltaWP;
        public final Map<Integer, Double> attribution; // participantId → ΔWP attribué
        public final String reason;

        EventBreakdown(long ts, String type, String subType, int actorId,
                       double raw, double adjusted, Map<Integer, Double> attr, String reason) {
            this.timestamp = ts;
            this.eventType = type;
            this.eventSubType = subType;
            this.primaryActorId = actorId;
            this.rawDeltaWP = raw;
            this.adjustedDeltaWP = adjusted;
            this.attribution = attr;
            this.reason = reason;
        }
    }

    public static class Attribution {
        /** Somme des ΔWP attribués à chaque participant. */
        public final Map<Integer, Double> playerDelta = new HashMap<>();
        /** Détail event par event (pour debug & commentator brief). */
        public final List<EventBreakdown> breakdown = new ArrayList<>();

        void add(int participantId, double delta) {
            playerDelta.merge(participantId, delta, Double::sum);
        }
    }

    public static Attribution attribute(FullContext ctx, WinEquityTimeline tl) {
        Attribution out = new Attribution();
        if (ctx == null || tl == null) return out;

        Map<Integer, PlayerContext> playersById = buildPlayerIdMap(ctx);

        if (ctx.killEvents != null) {
            for (RichKillEvent k : ctx.killEvents) {
                attributeKill(k, ctx, tl, playersById, out);
            }
        }
        if (ctx.objectiveEvents != null) {
            for (ObjectiveEvent o : ctx.objectiveEvents) {
                attributeObjective(o, ctx, tl, playersById, out);
            }
        }
        return out;
    }

    // -----------------------------------------------------------------
    // CHAMPION_KILL
    // -----------------------------------------------------------------

    private static void attributeKill(RichKillEvent kill, FullContext ctx, WinEquityTimeline tl,
                                       Map<Integer, PlayerContext> playersById, Attribution out) {
        PlayerContext killer = playersById.get(kill.killerId);
        PlayerContext victim = playersById.get(kill.victimId);
        if (killer == null || victim == null) return;

        double baseDeltaWP = signedKillDeltaWP(kill, killer.teamId, tl);

        // --- Modificateurs (design 5.2) ---
        double targetThreat = clamp(kill.bountyCollected / 300.0, TARGET_THREAT_MIN, TARGET_THREAT_MAX);
        double snowball     = snowballFactor(kill, ctx, playersById);
        double efficiency   = resourceEfficiency(kill, ctx, playersById, targetThreat);
        double oppCost      = opportunityCost(kill, ctx, tl, playersById);

        double adjustedDelta = baseDeltaWP * targetThreat * snowball * efficiency + oppCost;

        // --- Distribution ---
        Map<Integer, Double> attr = new HashMap<>();

        // Killer : 60%
        double killerShare = adjustedDelta * 0.60;
        attr.merge(killer.participantId, killerShare, Double::sum);
        out.add(killer.participantId, killerShare);

        // Assistants : 30% partagé
        int nbAssists = kill.assistIds.size();
        if (nbAssists > 0) {
            double assistShare = adjustedDelta * 0.30 / nbAssists;
            for (Integer aid : kill.assistIds) {
                attr.merge(aid, assistShare, Double::sum);
                out.add(aid, assistShare);
            }
        }

        // Présence : alliés du killer dans rayon (sauf killer & assistants)
        List<PlayerContext> presence = SpatialAnalyzer.playersInRadius(
                kill.posX, kill.posY, PRESENCE_RADIUS_KILL, kill.timestamp, playersById, killer.teamId);
        presence.removeIf(p -> p.participantId == killer.participantId || kill.assistIds.contains(p.participantId));
        if (!presence.isEmpty()) {
            double presenceShare = adjustedDelta * 0.10 / presence.size();
            for (PlayerContext p : presence) {
                attr.merge(p.participantId, presenceShare, Double::sum);
                out.add(p.participantId, presenceShare);
            }
        }

        // Victime : pénalité -base × targetThreat, ×1.5 si throw
        double victimPenalty = -Math.abs(baseDeltaWP) * targetThreat;
        boolean pickoff = SpatialAnalyzer.isPickoff(kill, playersById);
        double victimSideWpBefore = wpForTeam(tl, kill.timestamp - 1000L, victim.teamId);
        if (pickoff && victimSideWpBefore > THROW_WP_THRESHOLD) {
            victimPenalty *= 1.5;
        }
        attr.merge(victim.participantId, victimPenalty, Double::sum);
        out.add(victim.participantId, victimPenalty);

        String reason = String.format(
                "kill(bounty=%d, threat=%.2f, snowball=%.2f, eff=%.2f, opp=%+.3f) base=%+.3f → adj=%+.3f",
                kill.bountyCollected, targetThreat, snowball, efficiency, oppCost, baseDeltaWP, adjustedDelta);

        out.breakdown.add(new EventBreakdown(
                kill.timestamp, "CHAMPION_KILL", null, killer.participantId,
                baseDeltaWP, adjustedDelta, attr, reason));
    }

    /**
     * ΔWP "local" du kill : on simule l'état post-kill (gold + 1 kill pour le killer) et on
     * compare au state courant. Cela isole l'impact du kill seul, indépendamment des autres
     * events de la même frame.
     */
    private static double signedKillDeltaWP(RichKillEvent kill, int killerTeamId, WinEquityTimeline tl) {
        GameState before = tl.stateAt(kill.timestamp);
        if (before == null) return 0.0;

        GameState after = new GameState();
        after.timestamp  = kill.timestamp;
        int goldSign = (killerTeamId == 100) ? 1 : -1;
        after.goldDiff   = before.goldDiff + goldSign * kill.bountyCollected;
        after.killDiff   = before.killDiff + goldSign;
        after.turretDiff = before.turretDiff;
        after.dragonDiff = before.dragonDiff;
        after.baronBuff  = before.baronBuff;
        after.inhibDiff  = before.inhibDiff;
        after.wpBlue     = WinEquityComputer.computeWP(after);

        double dBlue = after.wpBlue - before.wpBlue;
        return (killerTeamId == 100) ? dBlue : -dBlue;
    }

    private static double snowballFactor(RichKillEvent kill, FullContext ctx,
                                          Map<Integer, PlayerContext> playersById) {
        double minutes = kill.timestamp / 60_000.0;
        boolean isSolo = kill.assistIds.isEmpty();
        boolean isPickoff = SpatialAnalyzer.isPickoff(kill, playersById);

        // Solo pickoff en lane phase = lane terminée pour la victime
        if (isSolo && isPickoff && minutes < 10.0) {
            return Math.min(2.0, 1.5 + minutes / 20.0);
        }

        // Objectif allié dans la fenêtre suivante = kill ayant débloqué un play
        PlayerContext killer = playersById.get(kill.killerId);
        if (killer != null && ctx.objectiveEvents != null) {
            for (ObjectiveEvent o : ctx.objectiveEvents) {
                long dt = o.timestamp - kill.timestamp;
                if (dt > 0 && dt < DOWNSTREAM_WINDOW_MS && o.killerTeamId == killer.teamId) {
                    return 1.3;
                }
            }
        }
        return 1.0;
    }

    private static double resourceEfficiency(RichKillEvent kill, FullContext ctx,
                                              Map<Integer, PlayerContext> playersById, double targetThreat) {
        boolean targetWeak = targetThreat < TARGET_WEAK_THRESHOLD;
        boolean enemyTookObjective = enemyTookObjectiveAfter(kill, ctx, playersById);

        if (targetWeak && enemyTookObjective) return -0.5;
        if (targetWeak) return 0.6;
        return 1.0;
    }

    private static double opportunityCost(RichKillEvent kill, FullContext ctx, WinEquityTimeline tl,
                                           Map<Integer, PlayerContext> playersById) {
        PlayerContext killer = playersById.get(kill.killerId);
        if (killer == null || ctx.objectiveEvents == null) return 0.0;
        int enemyTeam = (killer.teamId == 100) ? 200 : 100;
        double cost = 0.0;
        for (ObjectiveEvent o : ctx.objectiveEvents) {
            long dt = o.timestamp - kill.timestamp;
            if (dt <= 0 || dt > DOWNSTREAM_WINDOW_MS) continue;
            if (o.killerTeamId != enemyTeam) continue;
            // ΔWP de cet objectif, signé pour l'équipe du killer
            double objBlue = tl.wpDelta(o.timestamp - 30_000L, o.timestamp + 30_000L);
            double signed  = (killer.teamId == 100) ? objBlue : -objBlue;
            // On accumule 80% du coût (l'objectif perdu pendant qu'on était occupé à kill)
            cost += signed * 0.8;
        }
        return cost; // signé négatif si on a perdu des objectifs
    }

    private static boolean enemyTookObjectiveAfter(RichKillEvent kill, FullContext ctx,
                                                    Map<Integer, PlayerContext> playersById) {
        PlayerContext killer = playersById.get(kill.killerId);
        if (killer == null || ctx.objectiveEvents == null) return false;
        int enemyTeam = (killer.teamId == 100) ? 200 : 100;
        for (ObjectiveEvent o : ctx.objectiveEvents) {
            long dt = o.timestamp - kill.timestamp;
            if (dt > 0 && dt < DOWNSTREAM_WINDOW_MS && o.killerTeamId == enemyTeam) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // ELITE_MONSTER_KILL & BUILDING_KILL
    // -----------------------------------------------------------------

    private static void attributeObjective(ObjectiveEvent obj, FullContext ctx, WinEquityTimeline tl,
                                            Map<Integer, PlayerContext> playersById, Attribution out) {
        if (obj.killerTeamId != 100 && obj.killerTeamId != 200) return;

        double baseDeltaWP = signedObjectiveDeltaWP(obj, tl);
        if (Math.abs(baseDeltaWP) < 1e-6) return;

        boolean isElite = "ELITE_MONSTER_KILL".equals(obj.type);
        int presenceRadius = isElite ? PRESENCE_RADIUS_OBJ : PRESENCE_RADIUS_BUILD;
        double primaryShare = isElite ? 0.40 : 0.40;
        double allyShare    = isElite ? 0.60 : 0.60;

        Map<Integer, Double> attr = new HashMap<>();

        PlayerContext primary = playersById.get(obj.killerId);
        if (primary != null && primary.teamId == obj.killerTeamId) {
            double share = baseDeltaWP * primaryShare;
            attr.merge(primary.participantId, share, Double::sum);
            out.add(primary.participantId, share);
        }

        List<PlayerContext> allies = SpatialAnalyzer.playersInRadius(
                obj.posX, obj.posY, presenceRadius, obj.timestamp, playersById, obj.killerTeamId);
        if (primary != null) allies.removeIf(p -> p.participantId == primary.participantId);

        if (!allies.isEmpty()) {
            double share = baseDeltaWP * allyShare / allies.size();
            for (PlayerContext p : allies) {
                attr.merge(p.participantId, share, Double::sum);
                out.add(p.participantId, share);
            }
        } else if (primary != null) {
            // Pas d'alliés présents : le killer reçoit la totalité
            double fullShare = baseDeltaWP * allyShare;
            attr.merge(primary.participantId, fullShare, Double::sum);
            out.add(primary.participantId, fullShare);
        }

        String reason = String.format("%s/%s base=%+.3f", obj.type, obj.subType, baseDeltaWP);
        out.breakdown.add(new EventBreakdown(
                obj.timestamp, obj.type, obj.subType,
                primary != null ? primary.participantId : 0,
                baseDeltaWP, baseDeltaWP, attr, reason));
    }

    private static double signedObjectiveDeltaWP(ObjectiveEvent obj, WinEquityTimeline tl) {
        // Pour les objectifs, on prend le delta réel observé dans la timeline (±30s)
        double dBlue = tl.wpDelta(obj.timestamp - 5_000L, obj.timestamp + 30_000L);
        return (obj.killerTeamId == 100) ? dBlue : -dBlue;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static double wpForTeam(WinEquityTimeline tl, long timestamp, int teamId) {
        double wpBlue = tl.wpAt(timestamp);
        return (teamId == 100) ? wpBlue : 1.0 - wpBlue;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Map<Integer, PlayerContext> buildPlayerIdMap(FullContext ctx) {
        Map<Integer, PlayerContext> m = new HashMap<>();
        if (ctx.players == null) return m;
        for (PlayerContext p : ctx.players.values()) m.put(p.participantId, p);
        return m;
    }
}
