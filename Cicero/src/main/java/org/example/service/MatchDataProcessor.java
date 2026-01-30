package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class responsible for processing raw Riot API match data
 * and converting it into a structured JSON format optimized for AI analysis.
 */
public class MatchDataProcessor {

    public JSONObject buildDeepAnalysisJson(JSONObject match, JSONObject timeline, String targetPuuid) {
        JSONObject root = new JSONObject();
        JSONObject info = match.getJSONObject("info");
        
        // 1. Metadata
        JSONObject meta = new JSONObject();
        meta.put("mode", info.getString("gameMode"));
        meta.put("type", info.getString("gameType"));
        meta.put("duration_sec", info.getLong("gameDuration"));
        meta.put("version", info.getString("gameVersion"));
        meta.put("matchId", match.getJSONObject("metadata").getString("matchId"));
        root.put("metadata", meta);

        // 2. Teams Macro (Bans & Objectives)
        root.put("teams", extractTeamInfo(info));

        // 3. Participants
        JSONArray participants = info.getJSONArray("participants");
        JSONArray allies = new JSONArray();
        JSONArray enemies = new JSONArray();
        JSONObject targetPlayer = null;
        int targetTeamId = 0;
        int targetParticipantId = 0;

        // Map participantId to Champion Name for Timeline
        Map<Integer, String> idToChamp = new HashMap<>();

        for (int i = 0; i < participants.length(); i++) {
            JSONObject p = participants.getJSONObject(i);
            int pid = p.getInt("participantId");
            idToChamp.put(pid, p.getString("championName"));
            
            boolean isTarget = p.getString("puuid").equals(targetPuuid);
            if (isTarget) {
                targetTeamId = p.getInt("teamId");
                targetParticipantId = pid;
            }

            JSONObject playerObj = extractPlayerStats(p);
            
            if (isTarget) {
                targetPlayer = playerObj;
            } else {
                playerObj.put("teamId", p.getInt("teamId"));
            }
        }

        // Split Allies/Enemies
        for (int i = 0; i < participants.length(); i++) {
            JSONObject p = participants.getJSONObject(i);
            if (p.getString("puuid").equals(targetPuuid)) continue;
            
            JSONObject playerObj = extractPlayerStats(p);
            if (p.getInt("teamId") == targetTeamId) allies.put(playerObj);
            else enemies.put(playerObj);
        }

        // 4. Dynamic Data from Timeline (Skill Order & Build Path)
        if (targetPlayer != null) {
            targetPlayer.put("skill_order", extractSkillOrder(timeline, targetParticipantId));
            targetPlayer.put("build_path", extractBuildPath(timeline, targetParticipantId));
        }

        root.put("target_player", targetPlayer);
        root.put("allies", allies);
        root.put("enemies", enemies);

        // 5. Timeline Events (Story of the game)
        root.put("timeline_events", extractTimelineEvents(timeline, idToChamp, targetTeamId));

        return root;
    }

    private JSONObject extractTeamInfo(JSONObject info) {
        JSONObject teamsObj = new JSONObject();
        JSONArray teams = info.getJSONArray("teams");
        
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i);
            JSONObject teamData = new JSONObject();
            int teamId = t.getInt("teamId");
            
            teamData.put("win", t.getBoolean("win"));
            
            // Bans
            JSONArray bans = new JSONArray();
            JSONArray banArray = t.getJSONArray("bans");
            for(int j=0; j<banArray.length(); j++) {
                int champId = banArray.getJSONObject(j).getInt("championId");
                if (champId != -1) bans.put(champId);
            }
            teamData.put("bans", bans);

            // Objectives
            JSONObject objs = t.getJSONObject("objectives");
            JSONObject objData = new JSONObject();
            objData.put("baron", objs.getJSONObject("baron").getInt("kills"));
            objData.put("dragon", objs.getJSONObject("dragon").getInt("kills"));
            objData.put("horde", objs.getJSONObject("horde").getInt("kills")); // Voidgrubs
            objData.put("riftHerald", objs.getJSONObject("riftHerald").getInt("kills"));
            objData.put("tower", objs.getJSONObject("tower").getInt("kills"));
            teamData.put("objectives", objData);

            teamsObj.put(teamId == 100 ? "blue" : "red", teamData);
        }
        return teamsObj;
    }

    private JSONObject extractPlayerStats(JSONObject p) {
        JSONObject stats = new JSONObject();
        
        // Correction : Gestion sécurisée du nom du joueur (riotIdTagLine peut être absent)
        String name = p.optString("riotIdGameName", "");
        String tag = p.optString("riotIdTagLine", "");
        
        if (name.isEmpty()) {
            name = p.optString("summonerName", "Unknown");
        }
        
        if (!tag.isEmpty()) {
            name += "#" + tag;
        }
        stats.put("name", name);

        stats.put("champion", p.getString("championName"));
        stats.put("role", p.getString("teamPosition"));
        stats.put("win", p.getBoolean("win"));
        
        // KDA & Combat
        stats.put("kda_str", p.getInt("kills") + "/" + p.getInt("deaths") + "/" + p.getInt("assists"));
        stats.put("k", p.getInt("kills"));
        stats.put("d", p.getInt("deaths"));
        stats.put("a", p.getInt("assists"));
        stats.put("dmg_dealt", p.getInt("totalDamageDealtToChampions"));
        stats.put("dmg_taken", p.getInt("totalDamageTaken"));
        
        // Economy & Farming
        stats.put("gold", p.getInt("goldEarned"));
        stats.put("cs", p.getInt("totalMinionsKilled") + p.getInt("neutralMinionsKilled"));
        stats.put("jungle_ally", p.getInt("totalAllyJungleMinionsKilled"));
        stats.put("jungle_enemy", p.getInt("totalEnemyJungleMinionsKilled")); // Counter jungling
        
        // Vision
        stats.put("vision_score", p.getInt("visionScore"));
        stats.put("wards_placed", p.getInt("wardsPlaced"));
        stats.put("control_wards", p.getInt("visionWardsBoughtInGame"));

        // Final Items
        JSONArray items = new JSONArray();
        for(int i=0; i<=6; i++) {
            int itemId = p.getInt("item" + i);
            if(itemId != 0) items.put(itemId);
        }
        stats.put("final_items", items);
        stats.put("spells", new JSONArray().put(p.getInt("summoner1Id")).put(p.getInt("summoner2Id")));

        // Runes (Detailed)
        JSONObject perks = p.getJSONObject("perks");
        JSONArray styles = perks.getJSONArray("styles");
        
        JSONObject primary = styles.getJSONObject(0);
        JSONObject secondary = styles.getJSONObject(1);
        
        stats.put("rune_keystone", primary.getJSONArray("selections").getJSONObject(0).getInt("perk"));
        stats.put("rune_primary_tree", primary.getInt("style"));
        stats.put("rune_secondary_tree", secondary.getInt("style"));
        
        // Advanced Stats (Challenges)
        if (p.has("challenges")) {
            JSONObject c = p.getJSONObject("challenges");
            JSONObject adv = new JSONObject();
            adv.put("kp_percent", c.optDouble("killParticipation", 0));
            adv.put("dmg_percent", c.optDouble("teamDamagePercentage", 0));
            adv.put("gold_per_min", c.optDouble("goldPerMinute", 0));
            adv.put("solo_kills", c.optInt("soloKills", 0));
            adv.put("skillshots_dodged", c.optInt("skillshotsDodged", 0));
            adv.put("turret_plates", c.optInt("turretPlatesTaken", 0));
            adv.put("lane_minions_first_10_min", c.optInt("laneMinionsFirst10Minutes", 0));
            stats.put("advanced", adv);
        }

        return stats;
    }

    private JSONArray extractSkillOrder(JSONObject timeline, int participantId) {
        JSONArray skillOrder = new JSONArray();
        JSONArray frames = timeline.getJSONObject("info").getJSONArray("frames");
        
        // Map slot to Key
        String[] keys = {"", "Q", "W", "E", "R"};

        for (int i = 0; i < frames.length(); i++) {
            JSONArray events = frames.getJSONObject(i).getJSONArray("events");
            for (int j = 0; j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                if (e.getString("type").equals("SKILL_LEVEL_UP") && e.getInt("participantId") == participantId) {
                    int slot = e.getInt("skillSlot");
                    if (slot >= 1 && slot <= 4) skillOrder.put(keys[slot]);
                }
            }
        }
        return skillOrder;
    }

    private JSONArray extractBuildPath(JSONObject timeline, int participantId) {
        JSONArray buildPath = new JSONArray();
        JSONArray frames = timeline.getJSONObject("info").getJSONArray("frames");

        for (int i = 0; i < frames.length(); i++) {
            long timestamp = frames.getJSONObject(i).getLong("timestamp") / 60000; // Minutes
            JSONArray events = frames.getJSONObject(i).getJSONArray("events");
            
            for (int j = 0; j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                if (e.getString("type").equals("ITEM_PURCHASED") && e.getInt("participantId") == participantId) {
                    JSONObject itemEvent = new JSONObject();
                    itemEvent.put("time", timestamp);
                    itemEvent.put("itemId", e.getInt("itemId"));
                    buildPath.put(itemEvent);
                }
            }
        }
        return buildPath;
    }

    private JSONArray extractTimelineEvents(JSONObject timeline, Map<Integer, String> idToChamp, int targetTeamId) {
        JSONArray events = new JSONArray();
        JSONArray frames = timeline.getJSONObject("info").getJSONArray("frames");

        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.getJSONObject(i);
            JSONArray frameEvents = frame.getJSONArray("events");
            long timestamp = frame.getLong("timestamp") / 60000; // Minutes

            for (int j = 0; j < frameEvents.length(); j++) {
                JSONObject e = frameEvents.getJSONObject(j);
                String type = e.getString("type");
                JSONObject simpleEvent = new JSONObject();

                if (type.equals("CHAMPION_KILL")) {
                    simpleEvent.put("time", timestamp);
                    simpleEvent.put("type", "KILL");
                    simpleEvent.put("killer", idToChamp.getOrDefault(e.optInt("killerId"), "Minion/Tower"));
                    simpleEvent.put("victim", idToChamp.getOrDefault(e.getInt("victimId"), "Unknown"));
                    events.put(simpleEvent);
                } 
                else if (type.equals("ELITE_MONSTER_KILL")) {
                    simpleEvent.put("time", timestamp);
                    simpleEvent.put("type", "OBJECTIVE");
                    simpleEvent.put("monster", e.getString("monsterType"));
                    if (e.has("monsterSubType")) simpleEvent.put("subtype", e.getString("monsterSubType"));
                    simpleEvent.put("killer_team", e.getInt("killerTeamId") == targetTeamId ? "ALLY" : "ENEMY");
                    events.put(simpleEvent);
                }
                else if (type.equals("BUILDING_KILL")) {
                    simpleEvent.put("time", timestamp);
                    simpleEvent.put("type", "STRUCTURE");
                    simpleEvent.put("building", e.getString("buildingType"));
                    simpleEvent.put("lane", e.getString("laneType"));
                    simpleEvent.put("killer_team", e.getInt("teamId") == targetTeamId ? "ENEMY" : "ALLY"); // Building team ID is the victim team
                    events.put(simpleEvent);
                }
            }
        }
        return events;
    }
}