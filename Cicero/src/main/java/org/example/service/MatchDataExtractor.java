package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MatchDataExtractor {

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
    }

    // Classe utilitaire pour mémoriser les morts récentes (Fenêtre de 60 secondes)
    private static class DeathRecord {
        int victimId;
        int victimTeamId;
        long timestampMs;
        boolean isProcessed; // NOUVEAU : Empêche le double compte de la même mort

        public DeathRecord(int victimId, int victimTeamId, long timestampMs) {
            this.victimId = victimId;
            this.victimTeamId = victimTeamId;
            this.timestampMs = timestampMs;
            this.isProcessed = false; // Initialisé à false par défaut
        }
    }

    /**
     * Parcourt le match et la timeline UNE SEULE FOIS pour extraire les données causales.
     */
    public static Map<String, PlayerContext> extractAll(JSONObject rawMatch, JSONObject rawTimeline) {
        Map<Integer, PlayerContext> byId = new HashMap<>();
        Map<String, PlayerContext> byChamp = new HashMap<>();
        Map<String, PlayerContext> blueTeamRoles = new HashMap<>();
        Map<String, PlayerContext> redTeamRoles = new HashMap<>();

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
                ctx.controlWardsPlaced = p.optInt("visionWardsBoughtInGame", 0); // Les pink wards
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

                if (ctx.teamId == 100) blueTeamRoles.put(ctx.role, ctx);
                else redTeamRoles.put(ctx.role, ctx);
            }

            // =================================================================
            // PASSAGE 2 : LECTURE DE LA TIMELINE (Causalité et Throws)
            // =================================================================
            if (rawTimeline != null && rawTimeline.has("info")) {
                JSONArray frames = rawTimeline.getJSONObject("info").optJSONArray("frames");
                if (frames != null) {
                    int blueEarlyKills = 0;
                    int redEarlyKills = 0;
                    List<DeathRecord> recentDeaths = new ArrayList<>();

                    for (int i = 0; i < frames.length(); i++) {
                        JSONObject frame = frames.getJSONObject(i);

                        // A. Extraction du Duel à la Frame 14 (Golds exacts)
                        if (i == 14 && frame.has("participantFrames")) {
                            JSONObject pFrames = frame.getJSONObject("participantFrames");
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

                        // B. Analyse des Événements (Kills et Objectifs)
                        JSONArray events = frame.optJSONArray("events");
                        if (events != null) {
                            for (int e = 0; e < events.length(); e++) {
                                JSONObject event = events.getJSONObject(e);
                                String type = event.optString("type");
                                long timestamp = event.optLong("timestamp");
                                double minutes = timestamp / 60000.0;

                                // --- 1. ENREGISTREMENT DES MORTS ---
                                if ("CHAMPION_KILL".equals(type)) {
                                    int victimId = event.optInt("victimId");
                                    int killerId = event.optInt("killerId");

                                    PlayerContext victim = byId.get(victimId);
                                    PlayerContext killer = byId.get(killerId);

                                    if (victim != null) {
                                        // On sauvegarde la mort en mémoire (fenêtre de 60s)
                                        recentDeaths.add(new DeathRecord(victimId, victim.teamId, timestamp));

                                        // Kills avant 15 minutes (Dominance d'équipe)
                                        if (minutes <= 15.0 && killer != null) {
                                            if (killer.teamId == 100) blueEarlyKills++;
                                            else redEarlyKills++;
                                        }

                                        // Micro-analyse de la mort
                                        if (minutes <= 14.0) {
                                            JSONArray assists = event.optJSONArray("assistingParticipantIds");

                                            // Traque des Roams du Support/Mid
                                            if (assists != null) {
                                                for (int a = 0; a < assists.length(); a++) {
                                                    PlayerContext assistant = byId.get(assists.getInt(a));
                                                    if (assistant != null && !assistant.role.equals(victim.role)) {
                                                        if (assistant.role.equals("UTILITY") || assistant.role.equals("MIDDLE")) {
                                                            assistant.earlyRoamTakedowns++;
                                                        }
                                                    }
                                                }
                                            }

                                            // Est-ce un gank ou un solo kill ?
                                            if (assists == null || assists.isEmpty()) {
                                                if (killerId != 0) victim.earlySoloDeaths++;
                                            } else {
                                                victim.earlyGankDeaths++;
                                            }
                                        } else if (minutes >= 25.0) {
                                            victim.lateGameDeaths++;
                                        }
                                    }
                                }

                                // --- 2. DÉTECTION DES SACRIFICES ET DES THROWS ---
                                else if ("ELITE_MONSTER_KILL".equals(type) ||
                                        ("BUILDING_KILL".equals(type) && "INHIBITOR_BUILDING".equals(event.optString("buildingType")))) {

                                    int killerTeamId = event.optInt("killerTeamId");
                                    if (killerTeamId == 0 && byId.containsKey(event.optInt("killerId"))) {
                                        killerTeamId = byId.get(event.optInt("killerId")).teamId;
                                    }

                                    if (killerTeamId == 100 || killerTeamId == 200) {
                                        // On regarde les morts des 60 dernières secondes
                                        for (DeathRecord d : recentDeaths) {
                                            // NOUVEAU : On vérifie que la mort n'a pas déjà été comptée (!d.isProcessed)
                                            if (!d.isProcessed && timestamp - d.timestampMs <= 60000 && timestamp >= d.timestampMs) {
                                                PlayerContext deceasedPlayer = byId.get(d.victimId);
                                                if (deceasedPlayer != null) {
                                                    if (d.victimTeamId == killerTeamId) {
                                                        deceasedPlayer.sacrificialDeaths++;
                                                    } else {
                                                        deceasedPlayer.throwDeaths++;
                                                    }
                                                    // On verrouille cette mort pour ne plus la recompter si un autre objectif tombe
                                                    d.isProcessed = true;
                                                }
                                            }
                                        }
                                    }
                                }
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

        return byChamp;
    }
}