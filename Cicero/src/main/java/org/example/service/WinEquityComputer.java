package org.example.service;

import org.example.service.MatchDataExtractor.FullContext;
import org.example.service.MatchDataExtractor.ObjectiveEvent;
import org.example.service.MatchDataExtractor.PlayerContext;
import org.example.service.MatchDataExtractor.RichKillEvent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Calcule la timeline de Win Probability (WP) à partir des frames + events.
 *
 * WP_blue(t) = sigmoid( S_blue(t) ),
 * S_blue(t) = α(t)·goldDiff/1000 + β(t)·killDiff + γ(t)·turretDiff
 *           + δ(t)·dragonDiff + ε(t)·baronBuff + ζ(t)·inhibDiff
 *
 * Les coefficients dépendent de la phase de jeu (cf. wp_coefficients.json).
 *
 * Utilisation typique :
 *   WinEquityTimeline tl = WinEquityComputer.compute(rawTimeline, fullContext);
 *   double wpAt = tl.wpAt(timestampMs);
 *   double delta = tl.wpDelta(beforeMs, afterMs);
 */
public class WinEquityComputer {

    /** Durée de l'effet du buff baron Nashor (3 minutes). */
    public static final long BARON_BUFF_DURATION_MS = 180_000L;

    public static class Coefficients {
        public double gold;
        public double kills;
        public double turrets;
        public double dragons;
        public double baron;
        public double inhibs;
    }

    public static class PhaseConfig {
        public double endMinute;
        public Coefficients coeffs;
    }

    public static class GameState {
        public long timestamp;
        public int goldDiff;     // blue - red (cumulé)
        public int killDiff;
        public int turretDiff;
        public int dragonDiff;
        public int baronBuff;    // +1 blue actif, -1 red actif, 0 sinon
        public int inhibDiff;
        public double wpBlue;    // [0,1]
    }

    public static class WinEquityTimeline {
        public final List<GameState> states = new ArrayList<>();

        /** WP à un timestamp donné, via la frame la plus proche. Renvoie 0.5 si vide. */
        public double wpAt(long timestampMs) {
            if (states.isEmpty()) return 0.5;
            int idx = nearestIndex(timestampMs);
            return states.get(idx).wpBlue;
        }

        /** ΔWP entre deux instants (positif = bleu gagne en équité). */
        public double wpDelta(long beforeMs, long afterMs) {
            return wpAt(afterMs) - wpAt(beforeMs);
        }

        public GameState stateAt(long timestampMs) {
            if (states.isEmpty()) return null;
            return states.get(nearestIndex(timestampMs));
        }

        private int nearestIndex(long timestampMs) {
            int best = 0;
            long bestDiff = Math.abs(states.get(0).timestamp - timestampMs);
            for (int i = 1; i < states.size(); i++) {
                long d = Math.abs(states.get(i).timestamp - timestampMs);
                if (d < bestDiff) { bestDiff = d; best = i; }
            }
            return best;
        }
    }

    // -----------------------------------------------------------------
    // Chargement des coefficients depuis wp_coefficients.json (cache)
    // -----------------------------------------------------------------

    private static volatile List<PhaseConfig> phasesCache = null;
    private static volatile double goldUnit = 1000.0;

    private static List<PhaseConfig> loadCoefficients() {
        List<PhaseConfig> cached = phasesCache;
        if (cached != null) return cached;
        synchronized (WinEquityComputer.class) {
            if (phasesCache != null) return phasesCache;
            List<PhaseConfig> list = new ArrayList<>();
            try (InputStream is = WinEquityComputer.class.getClassLoader()
                    .getResourceAsStream("wp_coefficients.json")) {
                if (is == null) {
                    System.err.println("❌ wp_coefficients.json introuvable, fallback hardcodé.");
                    phasesCache = defaultPhases();
                    return phasesCache;
                }
                try (Scanner s = new Scanner(is, StandardCharsets.UTF_8.name())) {
                    String txt = s.useDelimiter("\\A").hasNext() ? s.next() : "{}";
                    JSONObject root = new JSONObject(txt);
                    JSONArray phases = root.getJSONArray("phases");
                    for (int i = 0; i < phases.length(); i++) {
                        JSONObject p = phases.getJSONObject(i);
                        PhaseConfig phase = new PhaseConfig();
                        phase.endMinute = p.getDouble("end_minute");
                        Coefficients c = new Coefficients();
                        c.gold    = p.getDouble("gold");
                        c.kills   = p.getDouble("kills");
                        c.turrets = p.getDouble("turrets");
                        c.dragons = p.getDouble("dragons");
                        c.baron   = p.getDouble("baron");
                        c.inhibs  = p.getDouble("inhibs");
                        phase.coeffs = c;
                        list.add(phase);
                    }
                    JSONObject norm = root.optJSONObject("normalization");
                    if (norm != null) goldUnit = norm.optDouble("gold_unit", 1000.0);
                }
            } catch (Exception e) {
                System.err.println("❌ Erreur lecture wp_coefficients.json : " + e.getMessage());
                phasesCache = defaultPhases();
                return phasesCache;
            }
            phasesCache = list;
            return list;
        }
    }

    private static List<PhaseConfig> defaultPhases() {
        List<PhaseConfig> list = new ArrayList<>();
        list.add(phase(10,  0.60, 0.30, 0.05, 0.05, 0.00, 0.00));
        list.add(phase(20,  0.40, 0.25, 0.20, 0.15, 0.00, 0.00));
        list.add(phase(30,  0.25, 0.15, 0.20, 0.20, 0.20, 0.05));
        list.add(phase(999, 0.15, 0.10, 0.25, 0.20, 0.30, 0.10));
        return list;
    }

    private static PhaseConfig phase(double endMin, double g, double k, double t, double dr, double b, double inh) {
        PhaseConfig p = new PhaseConfig();
        p.endMinute = endMin;
        p.coeffs = new Coefficients();
        p.coeffs.gold = g;
        p.coeffs.kills = k;
        p.coeffs.turrets = t;
        p.coeffs.dragons = dr;
        p.coeffs.baron = b;
        p.coeffs.inhibs = inh;
        return p;
    }

    public static Coefficients coefficientsAt(long timestampMs) {
        double minutes = timestampMs / 60_000.0;
        for (PhaseConfig p : loadCoefficients()) {
            if (minutes <= p.endMinute) return p.coeffs;
        }
        List<PhaseConfig> ps = loadCoefficients();
        return ps.get(ps.size() - 1).coeffs;
    }

    // -----------------------------------------------------------------
    // Calcul WP à partir d'un GameState
    // -----------------------------------------------------------------

    public static double computeWP(GameState state) {
        Coefficients c = coefficientsAt(state.timestamp);
        double s = c.gold    * (state.goldDiff / goldUnit)
                 + c.kills   * state.killDiff
                 + c.turrets * state.turretDiff
                 + c.dragons * state.dragonDiff
                 + c.baron   * state.baronBuff
                 + c.inhibs  * state.inhibDiff;
        return 1.0 / (1.0 + Math.exp(-s));
    }

    // -----------------------------------------------------------------
    // Construction de la timeline WP frame par frame
    // -----------------------------------------------------------------

    public static WinEquityTimeline compute(JSONObject rawTimeline, FullContext context) {
        WinEquityTimeline tl = new WinEquityTimeline();
        if (rawTimeline == null || !rawTimeline.has("info") || context == null) return tl;
        JSONArray frames = rawTimeline.getJSONObject("info").optJSONArray("frames");
        if (frames == null) return tl;

        Map<Integer, Integer> teamById = buildTeamIdMap(context);

        List<RichKillEvent> kills = new ArrayList<>(context.killEvents != null ? context.killEvents : List.of());
        kills.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        List<ObjectiveEvent> objs = new ArrayList<>(context.objectiveEvents != null ? context.objectiveEvents : List.of());
        objs.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));

        int killsBlue = 0, killsRed = 0;
        int turretsBlue = 0, turretsRed = 0;
        int dragonsBlue = 0, dragonsRed = 0;
        int inhibsBlue = 0,  inhibsRed = 0;
        List<long[]> baronTakes = new ArrayList<>();

        int killIdx = 0, objIdx = 0;

        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.getJSONObject(i);
            long ts = frame.optLong("timestamp", i * 60_000L);

            int blueGold = 0, redGold = 0;
            if (frame.has("participantFrames")) {
                JSONObject pFrames = frame.getJSONObject("participantFrames");
                for (int pid = 1; pid <= 10; pid++) {
                    String k = String.valueOf(pid);
                    if (!pFrames.has(k)) continue;
                    int g = pFrames.getJSONObject(k).optInt("totalGold", 0);
                    Integer team = teamById.get(pid);
                    if (team != null && team == 100) blueGold += g;
                    else if (team != null && team == 200) redGold += g;
                }
            }

            while (killIdx < kills.size() && kills.get(killIdx).timestamp <= ts) {
                RichKillEvent k = kills.get(killIdx);
                Integer kt = teamById.get(k.killerId);
                if (kt != null && kt == 100) killsBlue++;
                else if (kt != null && kt == 200) killsRed++;
                killIdx++;
            }

            while (objIdx < objs.size() && objs.get(objIdx).timestamp <= ts) {
                ObjectiveEvent o = objs.get(objIdx);
                if ("ELITE_MONSTER_KILL".equals(o.type)) {
                    if ("BARON_NASHOR".equals(o.subType)) {
                        baronTakes.add(new long[]{ o.timestamp, o.killerTeamId });
                    } else if ("DRAGON".equals(o.subType) || "ELDER_DRAGON".equals(o.subType)) {
                        if (o.killerTeamId == 100) dragonsBlue++;
                        else if (o.killerTeamId == 200) dragonsRed++;
                    }
                } else if ("BUILDING_KILL".equals(o.type)) {
                    if ("TOWER_BUILDING".equals(o.subType)) {
                        if (o.killerTeamId == 100) turretsBlue++;
                        else if (o.killerTeamId == 200) turretsRed++;
                    } else if ("INHIBITOR_BUILDING".equals(o.subType)) {
                        if (o.killerTeamId == 100) inhibsBlue++;
                        else if (o.killerTeamId == 200) inhibsRed++;
                    }
                }
                objIdx++;
            }

            int baronBuff = 0;
            long mostRecent = -1L;
            for (long[] take : baronTakes) {
                if (ts >= take[0] && ts <= take[0] + BARON_BUFF_DURATION_MS && take[0] > mostRecent) {
                    mostRecent = take[0];
                    baronBuff = (take[1] == 100) ? 1 : (take[1] == 200 ? -1 : 0);
                }
            }

            GameState st = new GameState();
            st.timestamp = ts;
            st.goldDiff   = blueGold - redGold;
            st.killDiff   = killsBlue - killsRed;
            st.turretDiff = turretsBlue - turretsRed;
            st.dragonDiff = dragonsBlue - dragonsRed;
            st.baronBuff  = baronBuff;
            st.inhibDiff  = inhibsBlue - inhibsRed;
            st.wpBlue     = computeWP(st);
            tl.states.add(st);
        }

        return tl;
    }

    private static Map<Integer, Integer> buildTeamIdMap(FullContext context) {
        Map<Integer, Integer> map = new HashMap<>();
        if (context.players == null) return map;
        for (PlayerContext p : context.players.values()) {
            map.put(p.participantId, p.teamId);
        }
        return map;
    }
}
