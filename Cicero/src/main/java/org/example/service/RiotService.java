package org.example.service;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public String getPuuid(String gameName, String tagLine) throws IOException {
        String safeName = URLEncoder.encode(gameName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/" + safeName + "/" + tagLine;
        JSONObject json = executeRequest(url);
        if (!json.has("puuid")) throw new IOException("Compte introuvable.");
        return json.getString("puuid");
    }

    // --- RANK ---
    public Map<String, RankInfo> getAllRanks(String puuid, String region) throws IOException {
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
        } catch (IOException e) {
            if (e.getMessage().equals("QUOTA_EXCEEDED")) throw e;
            System.err.println("[Riot] Rank Error: " + e.getMessage());
        }
        return ranks;
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
        String continent = getMatchRegion(region);
        String url = "https://" + continent + ".api.riotgames.com/lol/match/v5/matches/by-puuid/" + puuid.trim() + "/ids?start=0&count=" + count;
        if (queueId != null) url += "&queue=" + queueId;
        
        JSONArray matches = executeRequestArray(url);
        List<String> ids = new ArrayList<>();
        for(int i=0; i<matches.length(); i++) ids.add(matches.getString(i));
        return ids;
    }

    public String getLastMatchId(String puuid, String region) throws IOException {
        List<String> ids = getMatchHistoryIds(puuid, region, null, 1);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // --- SMART MATCH FINDER ---
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

    // --- METHODE 1: ANALYSE LOURDE (1 Game, Timeline, Full Details) ---
    public String getMatchAnalysis(String matchId, String targetPuuid, String region) throws IOException {
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
            
            return formatHeavyAnalysis(matchInfo, timeline, targetPuuid);
        } catch (Exception e) {
            throw new IOException("Erreur lors de l'analyse détaillée: " + e.getMessage());
        }
    }

    private String formatHeavyAnalysis(JSONObject json, JSONObject timeline, String myPuuid) {
        JSONObject info = json.getJSONObject("info");
        JSONArray participants = info.getJSONArray("participants");
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== ANALYSE DÉTAILLÉE (Match ID: ").append(json.getJSONObject("metadata").getString("matchId")).append(") ===\n");
        sb.append("Mode: ").append(info.getString("gameMode")).append(" | Durée: ").append(info.getLong("gameDuration")).append("s\n");
        
        List<String> blueTeam = new ArrayList<>();
        List<String> redTeam = new ArrayList<>();
        
        for (int i = 0; i < participants.length(); i++) {
            JSONObject p = participants.getJSONObject(i);
            boolean isMe = p.getString("puuid").equals(myPuuid);
            int teamId = p.getInt("teamId");
            
            String stats = String.format(
                "%s%s (%s) - %s | KDA: %d/%d/%d | Dmg: %d | Gold: %d | CS: %d | Vision: %d (Wards: %d, Pinks: %d)",
                isMe ? "👉 CIBLE : " : "", 
                p.getString("summonerName"), 
                p.getString("teamPosition"), 
                p.getString("championName"), 
                p.getInt("kills"), p.getInt("deaths"), p.getInt("assists"), 
                p.getInt("totalDamageDealtToChampions"), 
                p.getInt("goldEarned"), 
                p.getInt("totalMinionsKilled") + p.getInt("neutralMinionsKilled"), 
                p.getInt("visionScore"), 
                p.getInt("wardsPlaced"), 
                p.getInt("visionWardsBoughtInGame")
            );
            
            if (teamId == 100) blueTeam.add(stats + (p.getBoolean("win") ? " (WIN)" : ""));
            else redTeam.add(stats + (p.getBoolean("win") ? " (WIN)" : ""));
        }
        
        sb.append("\n--- ÉQUIPE BLEUE ---\n").append(String.join("\n", blueTeam));
        sb.append("\n\n--- ÉQUIPE ROUGE ---\n").append(String.join("\n", redTeam));
        
        sb.append("\n\n--- MOMENTS CLÉS (Timeline) ---\n");
        sb.append(summarizeTimeline(timeline));
        
        return sb.toString();
    }

    private String summarizeTimeline(JSONObject timeline) {
        StringBuilder sb = new StringBuilder();
        JSONArray frames = timeline.getJSONObject("info").getJSONArray("frames");
        
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.getJSONObject(i);
            JSONArray events = frame.getJSONArray("events");
            long timestamp = frame.getLong("timestamp") / 60000;
            
            for (int j = 0; j < events.length(); j++) {
                JSONObject event = events.getJSONObject(j);
                String type = event.getString("type");
                
                if (type.equals("ELITE_MONSTER_KILL")) {
                    String monster = event.getString("monsterType");
                    if (monster.equals("DRAGON")) monster += " (" + event.optString("monsterSubType", "Unknown") + ")";
                    sb.append("[").append(timestamp).append("min] Objectif : ").append(monster).append("\n");
                } else if (type.equals("BUILDING_KILL")) {
                    String building = event.getString("buildingType");
                    if (building.equals("TOWER_BUILDING")) building = "Tourelle (" + event.getString("laneType") + ")";
                    sb.append("[").append(timestamp).append("min] Structure : ").append(building).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // --- METHODE 2: ANALYSE LÉGÈRE (Multi-Games, Pas de Timeline) ---
    public String getMatchHistorySummary(String puuid, String region, Integer queueId, int count) {
        try {
            System.out.println("DEBUG: Récupération historique pour " + puuid + " (Queue: " + queueId + ")");
            List<String> matchIds = getMatchHistoryIds(puuid, region, queueId, Math.min(count, 10));
            
            if (matchIds.isEmpty()) {
                System.out.println("DEBUG: Aucun match ID trouvé.");
                return "Aucun historique récent.";
            }
            System.out.println("DEBUG: " + matchIds.size() + " matchs trouvés. Lancement des requêtes...");

            List<CompletableFuture<String>> futures = matchIds.stream()
                .map(matchId -> CompletableFuture.supplyAsync(() -> {
                    try {
                        String continent = getMatchRegion(region);
                        JSONObject json = executeRequest("https://" + continent + ".api.riotgames.com/lol/match/v5/matches/" + matchId);
                        return formatLightSummary(json, puuid);
                    } catch (Exception e) {
                        System.err.println("DEBUG: Erreur match " + matchId + " : " + e.getMessage());
                        return "Erreur match " + matchId;
                    }
                }, batchExecutor))
                .collect(Collectors.toList());

            String result = futures.stream().map(CompletableFuture::join).collect(Collectors.joining("\n"));
            System.out.println("DEBUG: Historique construit avec succès.");
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return "Erreur récupération historique: " + e.getMessage();
        }
    }

    private String formatLightSummary(JSONObject json, String myPuuid) {
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

        if (me == null) return "Données joueur introuvables.";

        boolean win = me.getBoolean("win");
        String champion = me.getString("championName");
        String mode = info.getString("gameMode");
        int k = me.getInt("kills");
        int d = me.getInt("deaths");
        int a = me.getInt("assists");
        int dmg = me.getInt("totalDamageDealtToChampions");
        int vision = me.getInt("visionScore");
        int cs = me.getInt("totalMinionsKilled") + me.getInt("neutralMinionsKilled");

        return String.format(
            "[%s] %s - %s | KDA: %d/%d/%d | Dmg: %d | Vision: %d | CS: %d",
            (win ? "VICTOIRE" : "DÉFAITE"), mode, champion, k, d, a, dmg, vision, cs
        );
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