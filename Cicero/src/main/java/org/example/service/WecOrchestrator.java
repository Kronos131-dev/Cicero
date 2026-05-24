package org.example.service;

import org.example.service.BehaviorProfiler.ObservedBehavior;
import org.example.service.BehaviorProfiler.PlayerProfile;
import org.example.service.CausalAttributor.Attribution;
import org.example.service.CausalAttributor.EventBreakdown;
import org.example.service.MatchDataExtractor.FullContext;
import org.example.service.MatchDataExtractor.PlayerContext;
import org.example.service.SpatialAnalyzer.Teamfight;
import org.example.service.SupportImpactAnalyzer.SupportImpact;
import org.example.service.WinEquityComputer.WinEquityTimeline;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrateur du pipeline V3-WEC. Calcule tout une fois pour le match, puis fournit
 * une vue par joueur (commentator_brief inclus).
 *
 * Usage :
 *   WecAnalysis analysis = WecOrchestrator.analyze(ctx, rawTimeline);
 *   JSONObject out = WecOrchestrator.buildPlayerOutput(player, analysis, ctx);
 *
 * Le mapping WEC → score [0, 100+] :
 *   score = 50 + wecRaw × 100, clampé à [0, 130]
 *   - wecRaw = -0.50  → score = 0   (troll)
 *   - wecRaw =  0.00  → score = 50  (neutre)
 *   - wecRaw = +0.50  → score = 100 (1v9)
 *   - wecRaw > +0.50  → bonus jusqu'à 130 (exceptionnel)
 */
public class WecOrchestrator {

    public static final double SCORE_NEUTRAL = 50.0;
    public static final double WEC_TO_SCORE  = 100.0;
    public static final double SCORE_MIN     = 0.0;
    public static final double SCORE_MAX     = 130.0;

    /** Tout ce qui est calculable une fois pour le match. */
    public static class WecAnalysis {
        public WinEquityTimeline timeline;
        public Attribution       attribution;
        public List<Teamfight>   teamfights;
        public final Map<Integer, ObservedBehavior> behaviors = new HashMap<>();
        public final Map<Integer, SupportImpact>    supports  = new HashMap<>();
    }

    /**
     * Lance le pipeline complet sur un match.
     */
    public static WecAnalysis analyze(FullContext ctx, JSONObject rawTimeline) {
        WecAnalysis a = new WecAnalysis();
        if (ctx == null) return a;

        Map<Integer, PlayerContext> byId = buildIdMap(ctx);

        // Phases 3-4 : Win Equity Timeline + attribution causale
        a.timeline    = WinEquityComputer.compute(rawTimeline, ctx);
        a.attribution = CausalAttributor.attribute(ctx, a.timeline);

        // Phase 2 : clustering des teamfights (utile pour debug/narrative)
        a.teamfights = SpatialAnalyzer.clusterTeamfights(ctx.killEvents, byId);

        // Phase 5 : profil comportemental par joueur
        for (PlayerContext p : byId.values()) {
            a.behaviors.put(p.participantId, BehaviorProfiler.profile(p, ctx));
        }

        // Phase 6 : impact support (renvoie un objet vide pour les non-supports)
        for (PlayerContext p : byId.values()) {
            a.supports.put(p.participantId, SupportImpactAnalyzer.analyze(p, ctx));
        }

        return a;
    }

    /**
     * Construit le JSON de sortie pour un joueur donné.
     */
    public static JSONObject buildPlayerOutput(PlayerContext player, WecAnalysis analysis, FullContext ctx) {
        JSONObject out = new JSONObject();
        if (player == null || analysis == null) return out;

        double rawAttribution = analysis.attribution.playerDelta.getOrDefault(player.participantId, 0.0);
        double supportBonus   = analysis.supports.getOrDefault(player.participantId,
                                    new SupportImpact()).bonusWp;
        double wecRaw         = rawAttribution + supportBonus;

        double mathScore = clamp(SCORE_NEUTRAL + wecRaw * WEC_TO_SCORE, SCORE_MIN, SCORE_MAX);

        ObservedBehavior behavior = analysis.behaviors.getOrDefault(player.participantId, new ObservedBehavior());
        SupportImpact    support  = analysis.supports.getOrDefault(player.participantId, new SupportImpact());

        out.put("math_score", (int) Math.round(mathScore));
        out.put("wec_raw", round3(wecRaw));
        out.put("wec_cumulative_delta_wp", round3(rawAttribution));
        out.put("support_bonus_wp", round3(supportBonus));

        out.put("behavior_profile", buildBehaviorJson(behavior));
        out.put("win_equity_contributions", buildContributionsJson(player, analysis));
        out.put("team_context", buildTeamContextJson(player, analysis, ctx));

        if (player.role != null && isSupport(player.role)) {
            out.put("support_metrics", buildSupportMetricsJson(support));
        }

        out.put("commentator_brief", buildCommentatorBrief(player, analysis, behavior, wecRaw, mathScore));

        return out;
    }

    // -----------------------------------------------------------------
    // Sous-construction des sous-JSON
    // -----------------------------------------------------------------

    private static JSONObject buildBehaviorJson(ObservedBehavior b) {
        JSONObject j = new JSONObject();
        j.put("identity", b.profile != null ? b.profile.name() : PlayerProfile.TEAMFIGHTER.name());
        j.put("gold_slope_early",   round3(b.goldSlopeEarly));
        j.put("gold_slope_mid",     round3(b.goldSlopeMid));
        j.put("gold_slope_late",    round3(b.goldSlopeLate));
        j.put("dpm_early",          round3(b.dpmEarly));
        j.put("dpm_mid",            round3(b.dpmMid));
        j.put("dpm_late",           round3(b.dpmLate));
        j.put("lane_fidelity",      round3(b.laneFidelity));
        j.put("map_mobility",       round3(b.mapMobility));
        j.put("avg_distance_from_team", round3(b.avgDistanceFromTeam));
        j.put("kills_before_15min", b.killsBefore15min);
        return j;
    }

    private static JSONArray buildContributionsJson(PlayerContext player, WecAnalysis analysis) {
        JSONArray arr = new JSONArray();
        for (EventBreakdown ev : analysis.attribution.breakdown) {
            Double attributed = ev.attribution.get(player.participantId);
            if (attributed == null || Math.abs(attributed) < 0.005) continue;
            JSONObject e = new JSONObject();
            e.put("event", ev.eventType);
            if (ev.eventSubType != null) e.put("sub_type", ev.eventSubType);
            e.put("timestamp_min", round1(ev.timestamp / 60_000.0));
            e.put("role_in_event", roleInEvent(player, ev));
            e.put("delta_wp", round3(ev.adjustedDeltaWP));
            e.put("attributed", round3(attributed));
            e.put("context", ev.reason);
            arr.put(e);
        }
        return arr;
    }

    private static String roleInEvent(PlayerContext player, EventBreakdown ev) {
        if (ev.primaryActorId == player.participantId) return "primary";
        if ("CHAMPION_KILL".equals(ev.eventType)) {
            // victime = celui qui a reçu une attribution franchement négative
            Double v = ev.attribution.get(player.participantId);
            if (v != null && v < 0) return "victim";
        }
        return "participant";
    }

    private static JSONObject buildTeamContextJson(PlayerContext player, WecAnalysis analysis, FullContext ctx) {
        JSONObject j = new JSONObject();
        double teamSum = 0;
        int teamCount  = 0;
        int carryId = 0;
        double carryDelta = Double.NEGATIVE_INFINITY;
        int throwId = 0;
        double throwDelta = Double.POSITIVE_INFINITY;

        for (PlayerContext p : ctx.players.values()) {
            if (p.teamId != player.teamId) continue;
            double d = analysis.attribution.playerDelta.getOrDefault(p.participantId, 0.0);
            teamSum += d;
            teamCount++;
            if (d > carryDelta) { carryDelta = d; carryId = p.participantId; }
            if (d < throwDelta) { throwDelta = d; throwId = p.participantId; }
        }
        j.put("team_avg_wec", teamCount > 0 ? round3(teamSum / teamCount) : 0);
        j.put("team_carry_id", carryId);
        if (throwDelta < -0.05) j.put("team_throw_id", throwId);
        return j;
    }

    private static JSONObject buildSupportMetricsJson(SupportImpact s) {
        JSONObject j = new JSONObject();
        j.put("vision_value",                round3(s.visionValue));
        j.put("heal_shield_value",           round3(s.healShieldValue));
        j.put("cc_chain_value",              round3(s.ccChainValue));
        j.put("lane_duo_gold_diff",          round3(s.laneDuoGoldDiff));
        j.put("wards_placed",                s.wardsPlaced);
        j.put("enemies_revealed_by_wards",   s.enemiesRevealedByWards);
        j.put("objectives_secured_by_wards", s.objectivesSecuredByWards);
        j.put("allied_kills_in_warded_zones", s.alliedKillsInWardedZones);
        return j;
    }

    // -----------------------------------------------------------------
    // Commentator brief
    // -----------------------------------------------------------------

    private static JSONObject buildCommentatorBrief(PlayerContext player, WecAnalysis analysis,
                                                     ObservedBehavior behavior,
                                                     double wecRaw, double mathScore) {
        JSONObject brief = new JSONObject();

        String tone;
        if (mathScore >= 85)      tone = "dominant";
        else if (mathScore >= 65) tone = "impactful";
        else if (mathScore >= 45) tone = "average";
        else if (mathScore >= 25) tone = "disappointing";
        else                      tone = "trolling";
        brief.put("tone", tone);

        // Top 3 moments décisifs (par |attribution|)
        JSONArray decisive = new JSONArray();
        List<EventBreakdown> mine = new ArrayList<>();
        for (EventBreakdown ev : analysis.attribution.breakdown) {
            Double v = ev.attribution.get(player.participantId);
            if (v != null && Math.abs(v) >= 0.01) mine.add(ev);
        }
        mine.sort(Comparator.comparingDouble(
                (EventBreakdown e) -> Math.abs(e.attribution.getOrDefault(player.participantId, 0.0))
        ).reversed());
        for (int i = 0; i < Math.min(3, mine.size()); i++) {
            EventBreakdown ev = mine.get(i);
            double v = ev.attribution.get(player.participantId);
            String sign = v > 0 ? "+" : "";
            decisive.put(String.format("%s%.3f ΔWP %s à %.1fmin",
                    sign, v, ev.eventType, ev.timestamp / 60_000.0));
        }
        brief.put("decisive_moments", decisive);

        // narrative hook
        String hook;
        String profileName = behavior.profile != null ? behavior.profile.name() : "TEAMFIGHTER";
        if (wecRaw >= 0.30) {
            hook = String.format("Performance carry (%s) : +%.0f%% WP cumulée pour son équipe.",
                    profileName, wecRaw * 100);
        } else if (wecRaw <= -0.30) {
            hook = String.format("Sous-performance critique (%s) : %.0f%% WP nette.",
                    profileName, wecRaw * 100);
        } else if (wecRaw >= 0.10) {
            hook = String.format("Contribution positive régulière en mode %s (+%.0f%% WP).",
                    profileName, wecRaw * 100);
        } else if (wecRaw <= -0.10) {
            hook = String.format("Contribution nette négative (%s, %.0f%% WP).",
                    profileName, wecRaw * 100);
        } else {
            hook = String.format("Performance neutre, profil %s.", profileName);
        }
        brief.put("narrative_hook", hook);

        return brief;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static boolean isSupport(String role) {
        String r = role.toUpperCase();
        return r.equals("UTILITY") || r.equals("SUPPORT") || r.equals("SUP");
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static Map<Integer, PlayerContext> buildIdMap(FullContext ctx) {
        Map<Integer, PlayerContext> m = new HashMap<>();
        if (ctx.players == null) return m;
        for (PlayerContext p : ctx.players.values()) m.put(p.participantId, p);
        return m;
    }
}
