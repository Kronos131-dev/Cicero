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

    public static class FullContext {
        public Map<String, PlayerContext> players;
        public TeamCompositionProfile blueTeamComp;
        public TeamCompositionProfile redTeamComp;

        public FullContext(Map<String, PlayerContext> players, TeamCompositionProfile blueTeamComp, TeamCompositionProfile redTeamComp) {
            this.players = players;
            this.blueTeamComp = blueTeamComp;
            this.redTeamComp = redTeamComp;
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

        // Logique simple pour calculer les scores de menace. Peut être affinée.
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

                if (ctx.teamId == 100) {
                    blueTeamRoles.put(ctx.role, ctx);
                    blueTeamPlayers.add(ctx);
                } else {
                    redTeamRoles.put(ctx.role, ctx);
                    redTeamPlayers.add(ctx);
                }
            }

            // =================================================================
            // PASSAGE 2 : LECTURE DE LA TIMELINE (Causalité et Throws)
            // =================================================================
            if (rawTimeline != null && rawTimeline.has("info")) {
                JSONArray frames = rawTimeline.getJSONObject("info").optJSONArray("frames");
                if (frames != null) {
                    List<TimelineEvent> allEvents = new ArrayList<>();
                    int blueEarlyKills = 0;
                    int redEarlyKills = 0;

                    // 1. Extraction de tous les événements importants
                    for (int i = 0; i < frames.length(); i++) {
                        JSONObject frame = frames.getJSONObject(i);
                        
                        // A. Extraction du Duel à la Frame 14 (Golds exacts) - inchangé
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
                                        TimelineEvent te = new TimelineEvent(timestamp, type, killerId, killer.teamId);
                                        te.victimId = victimId;
                                        te.victimTeamId = victim.teamId;
                                        
                                        JSONArray assists = event.optJSONArray("assistingParticipantIds");
                                        if (assists != null) {
                                            for(int a=0; a<assists.length(); a++) te.assistingParticipantIds.add(assists.getInt(a));
                                        }
                                        allEvents.add(te);
                                        
                                        // Logique existante (Roam, Early Deaths)
                                        double minutes = timestamp / 60000.0;
                                        
                                        // Comptage des kills early pour HeavyLosingEarly
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
                                } else if ("ELITE_MONSTER_KILL".equals(type) || 
                                          ("BUILDING_KILL".equals(type) && ("INHIBITOR_BUILDING".equals(event.optString("buildingType")) || "TOWER_BUILDING".equals(event.optString("buildingType"))))) {
                                    
                                    int killerId = event.optInt("killerId");
                                    int killerTeamId = event.optInt("killerTeamId"); // Parfois présent
                                    
                                    if (killerTeamId == 0 && byId.containsKey(killerId)) {
                                        killerTeamId = byId.get(killerId).teamId;
                                    }
                                    
                                    if (killerTeamId == 100 || killerTeamId == 200) {
                                        TimelineEvent te = new TimelineEvent(timestamp, type, killerId, killerTeamId);
                                        allEvents.add(te);
                                    }
                                }
                            }
                        }
                    }
                    
                    // 2. ANALYSE SÉQUENTIELLE (Le Cerveau)
                    
                    for (int i = 0; i < allEvents.size(); i++) {
                        TimelineEvent current = allEvents.get(i);
                        
                        if ("CHAMPION_KILL".equals(current.type)) {
                            boolean objectiveTakenAfter = false;
                            boolean objectiveLostAfter = false;
                            
                            // Calcul dynamique de la fenêtre de temps (Death Timer approximatif)
                            // Early game (~15s) -> Late game (~60-70s)
                            double gameMinutes = current.timestamp / 60000.0;
                            long dynamicWindowMs;
                            if (gameMinutes < 15) {
                                dynamicWindowMs = 15000 + (long)(gameMinutes * 1000); // 15s -> 30s
                            } else if (gameMinutes < 30) {
                                dynamicWindowMs = 30000 + (long)((gameMinutes - 15) * 2000); // 30s -> 60s
                            } else {
                                dynamicWindowMs = 60000 + (long)((gameMinutes - 30) * 1000); // 60s -> 70s+
                            }
                            
                            // Modificateur de pression de fin de partie
                            int weight = (current.timestamp > 1800000) ? 2 : 1; // > 30 minutes = double impact

                            // On regarde le futur
                            for (int j = i + 1; j < allEvents.size(); j++) {
                                TimelineEvent future = allEvents.get(j);
                                if (future.timestamp - current.timestamp > dynamicWindowMs) break; // Hors fenêtre dynamique
                                
                                if (!"CHAMPION_KILL".equals(future.type)) {
                                    if (future.teamId == current.teamId) {
                                        objectiveTakenAfter = true;
                                    } else if (future.teamId == current.victimTeamId) {
                                        objectiveLostAfter = true;
                                    }
                                }
                            }
                            
                            // A. CLUTCH KILL (Kill -> Objectif)
                            if (objectiveTakenAfter) {
                                PlayerContext killer = byId.get(current.killerId);
                                if (killer != null) killer.clutchKills += weight;
                                // On pourrait aussi donner des points aux assistants ici
                            }
                            
                            // B. UNFORCED ERROR / THROW (Mort -> Perte d'Objectif)
                            if (objectiveLostAfter) {
                                PlayerContext victim = byId.get(current.victimId);
                                if (victim != null) {
                                    // On vérifie si c'était un sacrifice (déjà géré par l'ancienne logique, mais on affine ici)
                                    // Pour simplifier, toute mort suivie d'une perte d'objectif est un "Throw" potentiel
                                    // Sauf si l'équipe de la victime a AUSSI pris un objectif (Trade)
                                    if (!objectiveTakenAfter) {
                                        victim.unforcedErrorDeaths += weight;
                                    } else {
                                        victim.sacrificialDeaths += weight; // C'était un trade (mort pour objectif)
                                    }
                                }
                            }
                            
                            // C. PICK-OFF (Kill Isolé)
                            // Nécessiterait les positions X,Y. Pour l'instant, on approxime :
                            // Si aucun autre kill n'a eu lieu 10s avant ou après, c'est un pick-off.
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

                    // C. Déduction Finale : Heavy Losing Early - inchangé
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

        return new FullContext(byChamp, blueTeamComp, redTeamComp);
    }
}