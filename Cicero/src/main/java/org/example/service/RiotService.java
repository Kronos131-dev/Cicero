package org.example.service;

import dev.langchain4j.agent.tool.Tool;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class RiotService {
    private final OkHttpClient client;
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(10);
    
    private static final AtomicLong quotaResetTime = new AtomicLong(0);
    private static final AtomicBoolean isQuotaExceeded = new AtomicBoolean(false);

    public static final int QUEUE_SOLOQ = 420;
    public static final int QUEUE_FLEX = 440;
    public static final int QUEUE_CLASH = 700;
    public static final int QUEUE_ARAM = 450;

    // --- CACHES ---
    private final SimpleCache<String, String> puuidCache = new SimpleCache<>(24 * 60 * 60 * 1000); // 24h
    private final SimpleCache<String, Map<String, RankInfo>> rankCache = new SimpleCache<>(10 * 60 * 1000); // 10 min
    private final SimpleCache<String, List<String>> matchHistoryCache = new SimpleCache<>(5 * 60 * 1000); // 5 min
    private final SimpleCache<String, String> matchAnalysisCache = new SimpleCache<>(30 * 60 * 1000); // 30 min
    private final SimpleCache<String, String> matchSummaryCache = new SimpleCache<>(30 * 60 * 1000); // 30 min

    public static class RankInfo {
        public String tier;
        public String rank;
        public int lp;
        public int wins;
        public int losses;

        public RankInfo(String tier, String rank, int lp, int wins, int losses) {
            this.tier = tier;
            this.rank = rank;
            this.lp = lp;
            this.wins = wins;
            this.losses = losses;
        }
    }

    public RiotService(String apiKey) {
        this.client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (isQuotaExceeded.get()) {
                        if (System.currentTimeMillis() > quotaResetTime.get()) {
                            isQuotaExceeded.set(false);
                        } else {
                            throw new IOException("QUOTA_EXCEEDED");
                        }
                    }

                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("X-Riot-Token", apiKey.trim())
                            .build();
                    
                    Response response = chain.proceed(request);
                    
                    if (response.code() == 429) {
                        isQuotaExceeded.set(true);
                        long retryAfter = 120;
                        String retryHeader = response.header("Retry-After");
                        if (retryHeader != null) {
                            try { retryAfter = Long.parseLong(retryHeader); } catch (NumberFormatException ignored) {}
                        }
                        quotaResetTime.set(System.currentTimeMillis() + (retryAfter * 1000));
                        throw new IOException("QUOTA_EXCEEDED");
                    }
                    
                    if (response.code() == 403) {
                        isQuotaExceeded.set(true);
                        quotaResetTime.set(System.currentTimeMillis() + (24 * 60 * 60 * 1000));
                        throw new IOException("API_KEY_INVALID");
                    }

                    return response;
                })
                .build();
    }

    // --- ACCOUNT ---
    @Tool("Récupère le PUUID d'un joueur à partir de son Riot ID (GameName et TagLine). Ex: 'Faker' et 'KR1'.")
    public String getPuuid(String gameName, String tagLine) throws IOException {
        String cacheKey = gameName.toLowerCase() + "#" + tagLine.toLowerCase();
        String cachedPuuid = puuidCache.get(cacheKey);
        if (cachedPuuid != null) return cachedPuuid;

        String safeName = URLEncoder.encode(gameName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/" + safeName + "/" + tagLine;
        JSONObject json = executeRequest(url);
        if (!json.has("puuid")) throw new IOException("Compte introuvable.");
        
        String puuid = json.getString("puuid");
        puuidCache.put(cacheKey, puuid);
        return puuid;
    }

    // --- RANK ---
    public Map<String, RankInfo> getAllRanks(String puuid, String region) throws IOException {
        String cacheKey = puuid + "#" + region;
        Map<String, RankInfo> cachedRanks = rankCache.get(cacheKey);
        if (cachedRanks != null) return cachedRanks;

        Map<String, RankInfo> ranks = new HashMap<>();
        ranks.put("SOLO", new RankInfo("UNRANKED", "", 0, 0, 0));
        ranks.put("FLEX", new RankInfo("UNRANKED", "", 0, 0, 0));

        if (puuid == null) return ranks;

        String url = "https://" + region + ".api.riotgames.com/lol/league/v4/entries/by-puuid/" + puuid.trim();

        try {
            JSONArray leagues = executeRequestArray(url);
            for (int i = 0; i < leagues.length(); i++) {
                JSONObject league = leagues.getJSONObject(i);
                String queueType = league.getString("queueType");
                
                RankInfo info = new RankInfo(
                        league.getString("tier"),
                        league.getString("rank"),
                        league.getInt("leaguePoints"),
                        league.getInt("wins"),
                        league.getInt("losses")
                );

                if ("RANKED_SOLO_5x5".equals(queueType)) ranks.put("SOLO", info);
                else if ("RANKED_FLEX_SR".equals(queueType)) ranks.put("FLEX", info);
            }
            rankCache.put(cacheKey, ranks);
        } catch (IOException e) {
            if (e.getMessage().equals("QUOTA_EXCEEDED")) throw e;
            System.err.println("[Riot] Rank Error: " + e.getMessage());
        }
        return ranks;
    }

    @Tool("Récupère les informations de rang (SoloQ) pour un PUUID et une région donnés.")
    public String getRankInfoString(String puuid, String region) throws IOException {
        RankInfo info = getAllRanks(puuid, region).get("SOLO");
        return "Rang SoloQ: " + info.tier + " " + info.rank + " (" + info.lp + " LP) - Winrate: " + info.wins + "W/" + info.losses + "L";
    }

    public RankInfo getRank(String puuid, String region) throws IOException {
        return getAllRanks(puuid, region).get("SOLO");
    }

    // --- MATCH HISTORY UTILS ---
    private String getMatchRegion(String region) {
        if (region.startsWith("eu") || region.equals("tr1") || region.equals("ru")) return "europe";
        if (region.startsWith("na") || region.startsWith("br") || region.startsWith("la")) return "americas";
        return "asia";
    }

    public List<String> getMatchHistoryIds(String puuid, String region, Integer queueId, int count) throws IOException {
        String cacheKey = puuid + "#" + region + "#" + queueId + "#" + count;
        List<String> cachedHistory = matchHistoryCache.get(cacheKey);
        if (cachedHistory != null) return cachedHistory;

        String continent = getMatchRegion(region);
        String url = "https://" + continent + ".api.riotgames.com/lol/match/v5/matches/by-puuid/" + puuid.trim() + "/ids?start=0&count=" + count;
        if (queueId != null) url += "&queue=" + queueId;
        
        JSONArray matches = executeRequestArray(url);
        List<String> ids = new ArrayList<>();
        for(int i=0; i<matches.length(); i++) ids.add(matches.getString(i));
        
        matchHistoryCache.put(cacheKey, ids);
        return ids;
    }

    @Tool("Récupère l'ID du dernier match joué par un joueur (PUUID) dans une région.")
    public String getLastMatchId(String puuid, String region) throws IOException {
        List<String> ids = getMatchHistoryIds(puuid, region, null, 1);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // --- SMART MATCH FINDER ---
    @Tool("Trouve un match spécifique selon des critères (Champion, KDA, ou index). Index 0 = dernier match.")
    public String findSpecificMatch(String puuid, String region, Integer queueId, String championName, String kdaScore, int indexOffset) {
        try {
            List<String> matchIds = getMatchHistoryIds(puuid, region, queueId, 20);
            if (matchIds.isEmpty()) return null;

            if (championName == null && kdaScore == null) {
                if (indexOffset < matchIds.size()) return matchIds.get(indexOffset);
                return matchIds.get(0);
            }

            String continent = getMatchRegion(region);
            for (String matchId : matchIds) {
                try {
                    JSONObject json = executeRequest("https://" + continent + ".api.riotgames.com/lol/match/v5/matches/" + matchId);
                    JSONObject info = json.getJSONObject("info");
                    JSONArray participants = info.getJSONArray("participants");

                    for (int i = 0; i < participants.length(); i++) {
                        JSONObject p = participants.getJSONObject(i);
                        if (p.getString("puuid").equals(puuid)) {
                            if (championName != null && !p.getString("championName").equalsIgnoreCase(championName)) {
                                continue;
                            }
                            if (kdaScore != null) {
                                String currentKda = p.getInt("kills") + "/" + p.getInt("deaths") + "/" + p.getInt("assists");
                                if (!currentKda.equals(kdaScore)) continue;
                            }
                            return matchId;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Erreur Smart Finder: " + e.getMessage());
        }
        return null;
    }

    // --- METHODE 1: ANALYSE LOURDE (JSON STRUCTURÉ POUR L'IA) ---
    @Tool("Récupère une analyse détaillée (JSON) d'un match spécifique pour un joueur donné.")
    public String getMatchAnalysis(String matchId, String targetPuuid, String region) throws IOException {
        String cacheKey = matchId + "#" + targetPuuid + "#JSON_V2"; // V2 for optimized structure
        String cachedAnalysis = matchAnalysisCache.get(cacheKey);
        if (cachedAnalysis != null) return cachedAnalysis;

        String continent = getMatchRegion(region);
        
        CompletableFuture<JSONObject> infoFuture = CompletableFuture.supplyAsync(() -> {
            try { return executeRequest("https://" + continent + ".api.riotgames.com/lol/match/v5/matches/" + matchId); } 
            catch (IOException e) { throw new RuntimeException(e); }
        }, batchExecutor);

        CompletableFuture<JSONObject> timelineFuture = CompletableFuture.supplyAsync(() -> {
            try { return executeRequest("https://" + continent + ".api.riotgames.com/lol/match/v5/matches/" + matchId + "/timeline"); } 
            catch (IOException e) { throw new RuntimeException(e); }
        }, batchExecutor);

        try {
            JSONObject matchInfo = infoFuture.join();
            JSONObject timeline = timelineFuture.join();
            
            JSONObject analysisJson = buildDeepAnalysisJson(matchInfo, timeline, targetPuuid);
            String result = analysisJson.toString();
            
            matchAnalysisCache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            throw new IOException("Erreur lors de l'analyse détaillée: " + e.getMessage());
        }
    }

    private JSONObject buildDeepAnalysisJson(JSONObject match, JSONObject timeline, String targetPuuid) {
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

    // --- METHODE 2: ANALYSE LÉGÈRE (JSON ARRAY POUR L'IA) ---
    @Tool("Récupère un résumé (JSON) des N derniers matchs d'un joueur.")
    public String getMatchHistorySummary(String puuid, String region, Integer queueId, int count) {
        String cacheKey = puuid + "#" + region + "#" + queueId + "#" + count + "#JSON";
        String cachedSummary = matchSummaryCache.get(cacheKey);
        if (cachedSummary != null) return cachedSummary;

        try {
            List<String> matchIds = getMatchHistoryIds(puuid, region, queueId, Math.min(count, 10));
            if (matchIds.isEmpty()) return "[]";

            List<CompletableFuture<JSONObject>> futures = matchIds.stream()
                .map(matchId -> CompletableFuture.supplyAsync(() -> {
                    try {
                        String continent = getMatchRegion(region);
                        JSONObject json = executeRequest("https://" + continent + ".api.riotgames.com/lol/match/v5/matches/" + matchId);
                        return extractLightStats(json, puuid);
                    } catch (Exception e) {
                        return new JSONObject().put("error", "Match " + matchId + " failed");
                    }
                }, batchExecutor))
                .collect(Collectors.toList());

            JSONArray history = new JSONArray();
            futures.stream().map(CompletableFuture::join).forEach(history::put);

            String result = history.toString();
            matchSummaryCache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }
    }

    private JSONObject extractLightStats(JSONObject json, String myPuuid) {
        JSONObject info = json.getJSONObject("info");
        JSONArray participants = info.getJSONArray("participants");
        JSONObject me = null;

        for (int i = 0; i < participants.length(); i++) {
            JSONObject p = participants.getJSONObject(i);
            if (p.getString("puuid").equals(myPuuid)) {
                me = p;
                break;
            }
        }

        if (me == null) return new JSONObject();

        JSONObject stats = new JSONObject();
        stats.put("matchId", json.getJSONObject("metadata").getString("matchId"));
        stats.put("mode", info.getString("gameMode"));
        stats.put("date", json.getJSONObject("info").getLong("gameCreation"));
        stats.put("duration", info.getLong("gameDuration"));
        stats.put("win", me.getBoolean("win"));
        stats.put("champion", me.getString("championName"));
        stats.put("role", me.getString("teamPosition"));
        
        stats.put("kda", me.getInt("kills") + "/" + me.getInt("deaths") + "/" + me.getInt("assists"));
        stats.put("cs", me.getInt("totalMinionsKilled") + me.getInt("neutralMinionsKilled"));
        stats.put("gold", me.getInt("goldEarned"));
        stats.put("vision", me.getInt("visionScore"));
        
        // Items (IDs only for compactness)
        JSONArray items = new JSONArray();
        for(int i=0; i<=6; i++) {
            int item = me.getInt("item" + i);
            if(item != 0) items.put(item);
        }
        stats.put("items", items);

        return stats;
    }

    // --- HTTP UTILS ---
    private JSONObject executeRequest(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " : " + body);
            return new JSONObject(body);
        }
    }

    private JSONArray executeRequestArray(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "[]";
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " : " + body);
            return new JSONArray(body);
        }
    }
}