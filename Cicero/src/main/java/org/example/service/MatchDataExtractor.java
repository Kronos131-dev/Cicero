package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MatchDataExtractor {

    public static class TeamCompositionProfile {
        public int tankCount = 0;
        public int assassinCount = 0;
        public int enchanterCount = 0;
        public int mageCount = 0;
        public int fighterCount = 0;
        public int adcCount = 0;

        // Scores de menace/style de 0.0 à 1.0
        public double tankiness;
        public double burstThreat;
        public double pokeThreat;
        public double engageHardness;
    }

    // Per-frame position + stats trajectory for a single player
    public static class PlayerTrajectory {
        public final int[] positionsX;
        public final int[] positionsY;
        public final int[] goldCumulative;
        public final int[] xpCumulative;
        public final int[] dmgChampsCumul;
        public final int[] dmgTakenCumul;
        public final int[] levels;
        public int frameCount;

        PlayerTrajectory(int capacity) {
            positionsX    = new int[capacity];
            positionsY    = new int[capacity];
            goldCumulative = new int[capacity];
            xpCumulative  = new int[capacity];
            dmgChampsCumul = new int[capacity];
            dmgTakenCumul  = new int[capacity];
            levels         = new int[capacity];
        }

        void record(int x, int y, int gold, int xp, int dmgDone, int dmgTaken, int level) {
            if (frameCount < positionsX.length) {
                positionsX[frameCount]     = x;
                positionsY[frameCount]     = y;
                goldCumulative[frameCount] = gold;
                xpCumulative[frameCount]   = xp;
                dmgChampsCumul[frameCount] = dmgDone;
                dmgTakenCumul[frameCount]  = dmgTaken;
                levels[frameCount]         = level;
                frameCount++;
            }
        }
    }

    // Enriched kill event: position + bounty (encodes victim fed-status) + victim gold at death
    public static class RichKillEvent {
        public final long timestamp;
        public final int posX, posY;
        public final int killerId, victimId;
        public final List<Integer> assistIds;
        /** Gold the killer collected (300 = normal, 600+ = shutdown) — encodes victim's threat level. */
        public final int bountyCollected;
        /** Victim's cumulative gold at the moment of death — cross-reference with avgTeamGold for fed-status. */
        public final int victimTotalGoldAtDeath;

        RichKillEvent(long ts, int x, int y, int killerId, int victimId,
                      List<Integer> assists, int bounty, int victimGold) {
            this.timestamp            = ts;
            this.posX                 = x;
            this.posY                 = y;
            this.killerId             = killerId;
            this.victimId             = victimId;
            this.assistIds            = assists;
            this.bountyCollected      = bounty;
            this.victimTotalGoldAtDeath = victimGold;
        }
    }

    // Ward placement event with position (for SupportImpactAnalyzer)
    public static class WardEvent {
        public final long timestamp;
        public final int placerId, posX, posY;
        public final String wardType;

        WardEvent(long ts, int placerId, int x, int y, String wardType) {
            this.timestamp = ts;
            this.placerId  = placerId;
            this.posX      = x;
            this.posY      = y;
            this.wardType  = wardType;
        }
    }

    // Objective event with position (for WinEquityComputer + CausalAttributor)
    public static class ObjectiveEvent {
        public final long timestamp;
        public final int posX, posY;
        public final int killerId, killerTeamId;
        public final String type;    // ELITE_MONSTER_KILL or BUILDING_KILL
        public final String subType; // DRAGON, BARON_NASHOR, RIFTHERALD, TOWER_BUILDING, INHIBITOR_BUILDING …

        ObjectiveEvent(long ts, int x, int y, int killerId, int killerTeamId,
                       String type, String subType) {
            this.timestamp    = ts;
            this.posX         = x;
            this.posY         = y;
            this.killerId     = killerId;
            this.killerTeamId = killerTeamId;
            this.type         = type;
            this.subType      = subType;
        }
    }

    public static class FullContext {
        public Map<String, PlayerContext> players;
        public TeamCompositionProfile blueTeamComp;
        public TeamCompositionProfile redTeamComp;
        // V3-WEC enriched event streams
        public List<RichKillEvent>    killEvents;
        public List<WardEvent>        wardEvents;
        public List<ObjectiveEvent>   objectiveEvents;

        public FullContext(Map<String, PlayerContext> players,
                           TeamCompositionProfile blueTeamComp,
                           TeamCompositionProfile redTeamComp,
                           List<RichKillEvent>    killEvents,
                           List<WardEvent>        wardEvents,
                           List<ObjectiveEvent>   objectiveEvents) {
            this.players         = players;
            this.blueTeamComp    = blueTeamComp;
            this.redTeamComp     = redTeamComp;
            this.killEvents      = killEvents;
            this.wardEvents      = wardEvents;
            this.objectiveEvents = objectiveEvents;
        }
    }

    public static class PlayerContext {
        public int participantId;
        public String championName;
        public String role;
        public int teamId;
        public boolean win;

        // --- BASIQUES ---
        public int kills, deaths, assists;
        public int visionScore;
        public int soloKills;
        public double killParticipation;

        // --- ⚔️ 1. DUEL & LANE (Le 1v1) ---
        public double laneGoldExpAdvantage;
        public double maxCsAdvantage;
        public int goldDiffAt14 = 0;

        // --- 💰 2. ÉCONOMIE & DÉGÂTS (Carrys) ---
        public double goldPerMinute;
        public double damagePerMinute;
        public double teamDamagePercentage;
        public int damageDealtToObjectives;
        public int bountyGold; // Shutdowns récupérés

        // --- 🛡️ 3. FRONTLINE & UTILITÉ (Tanks / Enchanteurs) ---
        public float damageTakenOnTeamPercentage;
        public int damageSelfMitigated;
        public float effectiveHealAndShielding;
        public int enemyChampionImmobilizations;
        public int saveAllyFromDeath;
        public float visionScoreAdvantage;

        // --- 🗺️ 4. MAP CONTROL & ROAMING ---
        public int earlyRoamTakedowns = 0;
        public float riverControlWardCoverage;
        public int voidGrubsKills;
        public int scuttleCrabs;

        // --- 🌲 5. JUNGLE & OBJECTIFS ---
        public int enemyJungleKills;
        public int epicMonsterSteals;

        // --- ⏳ 6. CHRONOLOGIE DES MORTS (Causalité via Timeline) ---
        public int earlySoloDeaths = 0;
        public int earlyGankDeaths = 0;
        public int lateGameDeaths = 0;

        // --- 🚨 7. L'IMPACT DES MORTS (Le Graal) ---
        public int sacrificialDeaths = 0;
        public int throwDeaths = 0;

        // --- 🧠 8. INTELLIGENCE SÉQUENTIELLE (Nouveau) ---
        public int clutchKills = 0;        // Kill -> Objectif
        public int unforcedErrorDeaths = 0; // Mort -> Perte d'objectif
        public int pickOffs = 0;            // Kill isolé

        // --- 📊 NOUVELLES STATS POUR LE BENCHMARK ---
        public int controlWardsPlaced;
        public int skillshotsDodged;
        public int turretPlatesTaken;
        public double kda;
        public int totalCs;

        public int dragonTakedowns;
        public int baronTakedowns;
        public int heraldTakedowns;

        public boolean isHeavyLosingEarly = false;

        // --- 📍 V3-WEC : Trajectoire positionnelle complète ---
        public PlayerTrajectory trajectory;
    }

    // Classe interne pour représenter un événement de la timeline de manière riche
    private static class TimelineEvent {
        long timestamp;
        String type; // CHAMPION_KILL, ELITE_MONSTER_KILL, BUILDING_KILL
        int killerId;
        int victimId;
        int teamId; // L'équipe qui a réalisé l'action (Killer Team)
        List<Integer> assistingParticipantIds = new ArrayList<>();

        // Pour les kills
        int victimTeamId;

        public TimelineEvent(long timestamp, String type, int killerId, int teamId) {
            this.timestamp = timestamp;
            this.type = type;
            this.killerId = killerId;
            this.teamId = teamId;
        }
    }

    private static TeamCompositionProfile createTeamProfile(List<PlayerContext> teamPlayers) {
        TeamCompositionProfile profile = new TeamCompositionProfile();
        for (PlayerContext p : teamPlayers) {
            String champClass = ScoreCalculator.getChampionClass(p.championName, p.role);
            switch (champClass) {
                case ScoreCalculator.TANK -> profile.tankCount++;
                case ScoreCalculator.ASSASSIN -> profile.assassinCount++;
                case ScoreCalculator.ENCHANTER -> profile.enchanterCount++;
                case ScoreCalculator.MAGE -> profile.mageCount++;
                case ScoreCalculator.COMBATTANT, ScoreCalculator.COMBATTANT_ECLAIR -> profile.fighterCount++;
                case ScoreCalculator.ADC -> profile.adcCount++;
            }
        }

        profile.tankiness = Math.min(1.0, (profile.tankCount * 0.4) + (profile.fighterCount * 0.15));
        profile.burstThreat = Math.min(1.0, (profile.assassinCount * 0.5) + (profile.mageCount * 0.2) + (profile.fighterCount * 0.1));
        profile.pokeThreat = Math.min(1.0, (profile.mageCount * 0.3) + (profile.adcCount * 0.2));
        profile.engageHardness = Math.min(1.0, (profile.tankCount * 0.4) + (profile.assassinCount * 0.2) + (profile.fighterCount * 0.2));

        return profile;
    }

    /**
     * Parcourt le match et la timeline UNE SEULE FOIS pour extraire les données causales.
     */
    public static FullContext extractAll(JSONObject rawMatch, JSONObject rawTimeline) {
        Map<Integer, PlayerContext> byId = new HashMap<>();
        Map<String, PlayerContext> byChamp = new HashMap<>();
        Map<String, PlayerContext> blueTeamRoles = new HashMap<>();
        Map<String, PlayerContext> redTeamRoles = new HashMap<>();
        List<PlayerContext> blueTeamPlayers = new ArrayList<>();
        List<PlayerContext> redTeamPlayers = new ArrayList<>();

        // V3-WEC enriched event lists
        List<RichKillEvent>  killEvents      = new ArrayList<>();
        List<WardEvent>      wardEvents      = new ArrayList<>();
        List<ObjectiveEvent> objectiveEvents = new ArrayList<>();

        try {
            // =================================================================
            // PASSAGE 1 : LECTURE DES PARTICIPANTS ET CHALLENGES
            // =================================================================
            JSONArray participants = rawMatch.getJSONObject("info").getJSONArray("participants");
            for (int i = 0; i < participants.length(); i++) {
                JSONObject p = participants.getJSONObject(i);
                PlayerContext ctx = new PlayerContext();

                ctx.participantId = p.getInt("participantId");
                ctx.championName = p.getString("championName").toUpperCase();
                ctx.role = p.optString("teamPosition", "NONE");
                ctx.teamId = p.getInt("teamId");
                ctx.win = p.getBoolean("win");

                // Basiques
                ctx.kills = p.optInt("kills");
                ctx.deaths = p.optInt("deaths");
                ctx.assists = p.optInt("assists");
                ctx.visionScore = p.optInt("visionScore");
                ctx.damageDealtToObjectives = p.optInt("damageDealtToObjectives");
                ctx.damageSelfMitigated = p.optInt("damageSelfMitigated");
                ctx.kda = ctx.deaths == 0 ? (ctx.kills + ctx.assists) : (double) (ctx.kills + ctx.assists) / ctx.deaths;
                ctx.controlWardsPlaced = p.optInt("visionWardsBoughtInGame", 0);
                ctx.totalCs = p.optInt("totalMinionsKilled", 0) + p.optInt("neutralMinionsKilled", 0);

                // Challenges (La Mine d'Or de Riot)
                JSONObject challenges = p.optJSONObject("challenges");
                if (challenges != null) {
                    ctx.laneGoldExpAdvantage = challenges.optDouble("laningPhaseGoldExpAdvantage", 0);
                    ctx.maxCsAdvantage = challenges.optDouble("maxCsAdvantageOnLaneOpponent", 0);
                    ctx.goldPerMinute = challenges.optDouble("goldPerMinute", 0);
                    ctx.damagePerMinute = challenges.optDouble("damagePerMinute", 0);
                    ctx.teamDamagePercentage = challenges.optDouble("teamDamagePercentage", 0);
                    ctx.damageTakenOnTeamPercentage = (float) challenges.optDouble("damageTakenOnTeamPercentage", 0);
                    ctx.effectiveHealAndShielding = (float) challenges.optDouble("effectiveHealAndShielding", 0);
                    ctx.enemyChampionImmobilizations = challenges.optInt("enemyChampionImmobilizations", 0);
                    ctx.saveAllyFromDeath = challenges.optInt("saveAllyFromDeath", 0);
                    ctx.visionScoreAdvantage = (float) challenges.optDouble("visionScoreAdvantageLaneOpponent", 0);
                    ctx.enemyJungleKills = (int) challenges.optDouble("enemyJungleMonsterKills", 0);
                    ctx.epicMonsterSteals = challenges.optInt("epicMonsterSteals", 0);
                    ctx.bountyGold = challenges.optInt("bountyGold", 0);
                    ctx.killParticipation = challenges.optDouble("killParticipation", 0);
                    ctx.soloKills = challenges.optInt("soloKills", 0);
                    ctx.dragonTakedowns = challenges.optInt("dragonTakedowns", 0);
                    ctx.baronTakedowns = challenges.optInt("baronTakedowns", 0);
                    ctx.heraldTakedowns = challenges.optInt("riftHeraldTakedowns", 0);

                    // Map Control
                    ctx.riverControlWardCoverage = (float) challenges.optDouble("controlWardTimeCoverageInRiverOrEnemyHalf", 0);
                    ctx.voidGrubsKills = challenges.optInt("voidMonsterKill", 0);
                    ctx.scuttleCrabs = challenges.optInt("scuttleCrabKills", 0);
                    ctx.earlyRoamTakedowns = challenges.optInt("killsOnOtherLanesEarlyJungleAsLaner", 0);
                }

                byId.put(ctx.participantId, ctx);
                byChamp.put(ctx.championName, ctx);

                if (ctx.teamId == 100) {
                    blueTeamRoles.put(ctx.role, ctx);
                    blueTeamPlayers.add(ctx);
                } else {
                    redTeamRoles.put(ctx.role, ctx);
                    redTeamPlayers.add(ctx);
                }
            }

            // =================================================================
            // PASSAGE 2 : LECTURE DE LA TIMELINE (Causalité, Positions, Events)
            // =================================================================
            if (rawTimeline != null && rawTimeline.has("info")) {
                JSONArray frames = rawTimeline.getJSONObject("info").optJSONArray("frames");
                if (frames != null) {
                    List<TimelineEvent> allEvents = new ArrayList<>();
                    int blueEarlyKills = 0;
                    int redEarlyKills = 0;

                    // Trajectory builders: one per participant, capacity 65 (enough for any game length)
                    Map<Integer, PlayerTrajectory> trajectoryBuilders = new HashMap<>();
                    for (PlayerContext ctx : byId.values()) {
                        trajectoryBuilders.put(ctx.participantId, new PlayerTrajectory(65));
                    }

                    // Latest gold snapshot per participant (refreshed each frame, used for kill enrichment)
                    Map<Integer, Integer> latestGold = new HashMap<>();

                    // 1. Extraction de tous les événements importants
                    for (int i = 0; i < frames.length(); i++) {
                        JSONObject frame = frames.getJSONObject(i);

                        // ---------------------------------------------------
                        // A. Extract per-frame trajectory data (ALL frames)
                        // ---------------------------------------------------
                        if (frame.has("participantFrames")) {
                            JSONObject pFrames = frame.getJSONObject("participantFrames");

                            for (int pid = 1; pid <= 10; pid++) {
                                String key = String.valueOf(pid);
                                if (!pFrames.has(key)) continue;
                                JSONObject pf = pFrames.getJSONObject(key);

                                int gold = pf.optInt("totalGold", 0);
                                latestGold.put(pid, gold);

                                int x = 0, y = 0;
                                JSONObject pos = pf.optJSONObject("position");
                                if (pos != null) {
                                    x = pos.optInt("x", 0);
                                    y = pos.optInt("y", 0);
                                }

                                int dmgDone = 0, dmgTaken = 0;
                                JSONObject dmgStats = pf.optJSONObject("damageStats");
                                if (dmgStats != null) {
                                    dmgDone  = dmgStats.optInt("totalDamageDoneToChampions", 0);
                                    dmgTaken = dmgStats.optInt("totalDamageTaken", 0);
                                }

                                int xp    = pf.optInt("xp", 0);
                                int level = pf.optInt("level", 0);

                                PlayerTrajectory traj = trajectoryBuilders.get(pid);
                                if (traj != null) traj.record(x, y, gold, xp, dmgDone, dmgTaken, level);
                            }

                            // Gold diff at frame 14 (existing logic, unchanged)
                            if (i == 14) {
                                for (PlayerContext bluePlayer : blueTeamRoles.values()) {
                                    PlayerContext redPlayer = redTeamRoles.get(bluePlayer.role);
                                    if (redPlayer != null && !bluePlayer.role.equals("NONE") &&
                                            pFrames.has(String.valueOf(bluePlayer.participantId)) &&
                                            pFrames.has(String.valueOf(redPlayer.participantId))) {

                                        int blueGold = pFrames.getJSONObject(String.valueOf(bluePlayer.participantId)).optInt("totalGold", 0);
                                        int redGold = pFrames.getJSONObject(String.valueOf(redPlayer.participantId)).optInt("totalGold", 0);

                                        bluePlayer.goldDiffAt14 = blueGold - redGold;
                                        redPlayer.goldDiffAt14 = redGold - blueGold;
                                    }
                                }
                            }
                        }

                        // ---------------------------------------------------
                        // B. Extract events
                        // ---------------------------------------------------
                        JSONArray events = frame.optJSONArray("events");
                        if (events != null) {
                            for (int e = 0; e < events.length(); e++) {
                                JSONObject event = events.getJSONObject(e);
                                String type = event.optString("type");
                                long timestamp = event.optLong("timestamp");

                                if ("CHAMPION_KILL".equals(type)) {
                                    int killerId = event.optInt("killerId");
                                    int victimId = event.optInt("victimId");
                                    PlayerContext killer = byId.get(killerId);
                                    PlayerContext victim = byId.get(victimId);

                                    if (killer != null && victim != null) {
                                        // ---- Existing TimelineEvent for V2 sequential analysis ----
                                        TimelineEvent te = new TimelineEvent(timestamp, type, killerId, killer.teamId);
                                        te.victimId = victimId;
                                        te.victimTeamId = victim.teamId;

                                        JSONArray assists = event.optJSONArray("assistingParticipantIds");
                                        List<Integer> assistList = new ArrayList<>();
                                        if (assists != null) {
                                            for (int a = 0; a < assists.length(); a++) {
                                                assistList.add(assists.getInt(a));
                                                te.assistingParticipantIds.add(assists.getInt(a));
                                            }
                                        }
                                        allEvents.add(te);

                                        // ---- V3-WEC: RichKillEvent with position + bounty ----
                                        int kx = 0, ky = 0;
                                        JSONObject kPos = event.optJSONObject("position");
                                        if (kPos != null) {
                                            kx = kPos.optInt("x", 0);
                                            ky = kPos.optInt("y", 0);
                                        }
                                        int bounty     = event.optInt("bounty", 300);
                                        int victimGold = latestGold.getOrDefault(victimId, 0);
                                        killEvents.add(new RichKillEvent(timestamp, kx, ky, killerId, victimId,
                                                assistList, bounty, victimGold));

                                        // ---- Existing early death / roam logic ----
                                        double minutes = timestamp / 60000.0;

                                        if (minutes <= 15.0) {
                                            if (killer.teamId == 100) blueEarlyKills++;
                                            else redEarlyKills++;
                                        }

                                        if (minutes <= 14.0) {
                                            if (te.assistingParticipantIds.isEmpty()) {
                                                if (killerId != 0) victim.earlySoloDeaths++;
                                            } else {
                                                victim.earlyGankDeaths++;
                                            }
                                            // Roam detection
                                            for (int assistId : te.assistingParticipantIds) {
                                                PlayerContext assistant = byId.get(assistId);
                                                if (assistant != null && !assistant.role.equals(victim.role)) {
                                                    if (assistant.role.equals("UTILITY") || assistant.role.equals("MIDDLE")) {
                                                        assistant.earlyRoamTakedowns++;
                                                    }
                                                }
                                            }
                                        } else if (minutes >= 25.0) {
                                            victim.lateGameDeaths++;
                                        }
                                    }

                                } else if ("WARD_PLACED".equals(type)) {
                                    // V3-WEC: capture ward placements with position
                                    int placerId = event.optInt("creatorId", 0);
                                    if (placerId > 0) {
                                        int wx = 0, wy = 0;
                                        JSONObject wPos = event.optJSONObject("position");
                                        if (wPos != null) {
                                            wx = wPos.optInt("x", 0);
                                            wy = wPos.optInt("y", 0);
                                        }
                                        String wardType = event.optString("wardType", "YELLOW_TRINKET");
                                        wardEvents.add(new WardEvent(timestamp, placerId, wx, wy, wardType));
                                    }

                                } else if ("ELITE_MONSTER_KILL".equals(type) ||
                                        ("BUILDING_KILL".equals(type) && (
                                                "INHIBITOR_BUILDING".equals(event.optString("buildingType")) ||
                                                "TOWER_BUILDING".equals(event.optString("buildingType"))))) {

                                    int killerId = event.optInt("killerId");
                                    int killerTeamId = event.optInt("killerTeamId", 0);
                                    if (killerTeamId == 0 && byId.containsKey(killerId)) {
                                        killerTeamId = byId.get(killerId).teamId;
                                    }

                                    if (killerTeamId == 100 || killerTeamId == 200) {
                                        // Existing TimelineEvent for V2 sequential analysis
                                        TimelineEvent te = new TimelineEvent(timestamp, type, killerId, killerTeamId);
                                        allEvents.add(te);

                                        // V3-WEC: ObjectiveEvent with position + subtype
                                        int ox = 0, oy = 0;
                                        JSONObject oPos = event.optJSONObject("position");
                                        if (oPos != null) {
                                            ox = oPos.optInt("x", 0);
                                            oy = oPos.optInt("y", 0);
                                        }
                                        String subType = "ELITE_MONSTER_KILL".equals(type)
                                                ? event.optString("monsterType", "UNKNOWN")
                                                : event.optString("buildingType", "UNKNOWN");
                                        objectiveEvents.add(new ObjectiveEvent(timestamp, ox, oy,
                                                killerId, killerTeamId, type, subType));
                                    }
                                }
                            }
                        }
                    }

                    // ---------------------------------------------------
                    // Attach completed trajectories to PlayerContext
                    // ---------------------------------------------------
                    for (Map.Entry<Integer, PlayerTrajectory> entry : trajectoryBuilders.entrySet()) {
                        PlayerContext ctx = byId.get(entry.getKey());
                        if (ctx != null) ctx.trajectory = entry.getValue();
                    }

                    // 2. ANALYSE SÉQUENTIELLE (V2 — conservée intact)

                    for (int i = 0; i < allEvents.size(); i++) {
                        TimelineEvent current = allEvents.get(i);

                        if ("CHAMPION_KILL".equals(current.type)) {
                            boolean objectiveTakenAfter = false;
                            boolean objectiveLostAfter = false;

                            double gameMinutes = current.timestamp / 60000.0;
                            long dynamicWindowMs;
                            if (gameMinutes < 15) {
                                dynamicWindowMs = 15000 + (long)(gameMinutes * 1000);
                            } else if (gameMinutes < 30) {
                                dynamicWindowMs = 30000 + (long)((gameMinutes - 15) * 2000);
                            } else {
                                dynamicWindowMs = 60000 + (long)((gameMinutes - 30) * 1000);
                            }

                            int weight = (current.timestamp > 1800000) ? 2 : 1;

                            for (int j = i + 1; j < allEvents.size(); j++) {
                                TimelineEvent future = allEvents.get(j);
                                if (future.timestamp - current.timestamp > dynamicWindowMs) break;

                                if (!"CHAMPION_KILL".equals(future.type)) {
                                    if (future.teamId == current.teamId) {
                                        objectiveTakenAfter = true;
                                    } else if (future.teamId == current.victimTeamId) {
                                        objectiveLostAfter = true;
                                    }
                                }
                            }

                            if (objectiveTakenAfter) {
                                PlayerContext killer = byId.get(current.killerId);
                                if (killer != null) killer.clutchKills += weight;
                            }

                            if (objectiveLostAfter) {
                                PlayerContext victim = byId.get(current.victimId);
                                if (victim != null) {
                                    if (!objectiveTakenAfter) {
                                        victim.unforcedErrorDeaths += weight;
                                    } else {
                                        victim.sacrificialDeaths += weight;
                                    }
                                }
                            }

                            boolean isIsolated = true;
                            for (int j = Math.max(0, i - 5); j < Math.min(allEvents.size(), i + 5); j++) {
                                if (i == j) continue;
                                TimelineEvent other = allEvents.get(j);
                                if ("CHAMPION_KILL".equals(other.type) && Math.abs(other.timestamp - current.timestamp) < 10000) {
                                    isIsolated = false;
                                    break;
                                }
                            }

                            if (isIsolated) {
                                PlayerContext killer = byId.get(current.killerId);
                                if (killer != null) killer.pickOffs += weight;
                            }
                        }
                    }

                    // C. Déduction Finale : Heavy Losing Early
                    boolean blueHeavyLosing = (redEarlyKills - blueEarlyKills) >= 5;
                    boolean redHeavyLosing = (blueEarlyKills - redEarlyKills) >= 5;
                    for (PlayerContext ctx : byId.values()) {
                        ctx.isHeavyLosingEarly = (ctx.teamId == 100) ? blueHeavyLosing : redHeavyLosing;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Erreur dans le Parseur Omniscient : " + e.getMessage());
            e.printStackTrace();
        }

        TeamCompositionProfile blueTeamComp = createTeamProfile(blueTeamPlayers);
        TeamCompositionProfile redTeamComp = createTeamProfile(redTeamPlayers);

        return new FullContext(byChamp, blueTeamComp, redTeamComp, killEvents, wardEvents, objectiveEvents);
    }
}
