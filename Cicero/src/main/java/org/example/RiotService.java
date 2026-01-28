package org.example;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for interacting with the Riot Games API.
 * Includes a thread-safe Circuit Breaker to handle Rate Limits and Quotas.
 */
public class RiotService {
    private final OkHttpClient client;
    
    // Thread-safe Circuit Breaker
    private static final AtomicLong quotaResetTime = new AtomicLong(0);
    private static final AtomicBoolean isQuotaExceeded = new AtomicBoolean(false);

    // Queue IDs
    public static final int QUEUE_SOLOQ = 420;
    public static final int QUEUE_FLEX = 440;
    public static final int QUEUE_ARAM = 450;
    public static final int QUEUE_CLASH = 700;
    public static final int QUEUE_ARAM_CLASH = 720;
    public static final int QUEUE_ARENA = 1700;

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
                    // Circuit Breaker Check
                    if (isQuotaExceeded.get()) {
                        if (System.currentTimeMillis() > quotaResetTime.get()) {
                            isQuotaExceeded.set(false); // Reset
                        } else {
                            throw new IOException("QUOTA_EXCEEDED");
                        }
                    }

                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("X-Riot-Token", apiKey.trim())
                            .build();
                    
                    Response response = chain.proceed(request);
                    
                    // Handle 429 (Rate Limit)
                    if (response.code() == 429) {
                        isQuotaExceeded.set(true);
                        long retryAfter = 120; // Default 2 min
                        String retryHeader = response.header("Retry-After");
                        if (retryHeader != null) {
                            try {
                                retryAfter = Long.parseLong(retryHeader);
                            } catch (NumberFormatException ignored) {}
                        }
                        quotaResetTime.set(System.currentTimeMillis() + (retryAfter * 1000));
                        throw new IOException("QUOTA_EXCEEDED");
                    }
                    
                    // Handle 403 (Forbidden / Key Expired)
                    if (response.code() == 403) {
                        isQuotaExceeded.set(true);
                        quotaResetTime.set(System.currentTimeMillis() + (24 * 60 * 60 * 1000)); // Block for 24h
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
    public Map<String, RankInfo> getAllRanks(String puuid) throws IOException {
        Map<String, RankInfo> ranks = new HashMap<>();
        ranks.put("SOLO", new RankInfo("UNRANKED", "", 0, 0, 0));
        ranks.put("FLEX", new RankInfo("UNRANKED", "", 0, 0, 0));

        if (puuid == null) return ranks;

        String region = "euw1"; 
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

                if ("RANKED_SOLO_5x5".equals(queueType)) {
                    ranks.put("SOLO", info);
                } else if ("RANKED_FLEX_SR".equals(queueType)) {
                    ranks.put("FLEX", info);
                }
            }
        } catch (IOException e) {
            if (e.getMessage().equals("QUOTA_EXCEEDED")) throw e;
            System.err.println("[Riot] Rank Error: " + e.getMessage());
        }
        return ranks;
    }

    public RankInfo getRank(String puuid) throws IOException {
        return getAllRanks(puuid).get("SOLO");
    }

    // --- MASTERY ---
    public String getTopChampions(String puuid) throws IOException {
        String region = "euw1";
        String url = "https://" + region + ".api.riotgames.com/lol/champion-mastery/v4/champion-masteries/by-puuid/" + puuid + "/top?count=3";
        
        try {
            JSONArray masteries = executeRequestArray(url);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < masteries.length(); i++) {
                JSONObject m = masteries.getJSONObject(i);
                sb.append("ChampID:").append(m.getLong("championId"))
                  .append("(Lvl").append(m.getInt("championLevel")).append("), ");
            }
            return sb.toString();
        } catch (IOException e) {
            if (e.getMessage().equals("QUOTA_EXCEEDED")) throw e;
            return "Inconnu";
        }
    }

    // --- MATCH HISTORY ---
    public List<String> getMatchHistoryIds(String puuid, Integer queueId, int count) throws IOException {
        String url = "https://europe.api.riotgames.com/lol/match/v5/matches/by-puuid/" + puuid.trim() + "/ids?start=0&count=" + count;
        if (queueId != null) {
            url += "&queue=" + queueId;
        }
        
        JSONArray matches = executeRequestArray(url);
        List<String> ids = new ArrayList<>();
        for(int i=0; i<matches.length(); i++) ids.add(matches.getString(i));
        return ids;
    }

    public List<String> getLastMatchesIds(String puuid, int count) throws IOException {
        return getMatchHistoryIds(puuid, null, count);
    }

    public String getLastMatchId(String puuid) throws IOException {
        List<String> ids = getLastMatchesIds(puuid, 1);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public JSONObject getMatchDetails(String matchId) throws IOException {
        return executeRequest("https://europe.api.riotgames.com/lol/match/v5/matches/" + matchId);
    }

    // --- DETAILED ANALYSIS ---
    public String getFullMatchDetails(String matchId, String myPuuid) throws IOException {
        JSONObject json = getMatchDetails(matchId);
        JSONObject info = json.getJSONObject("info");
        JSONArray participants = info.getJSONArray("participants");
        
        StringBuilder sb = new StringBuilder();
        sb.append("MATCH ID: ").append(matchId).append("\n");
        sb.append("Mode: ").append(info.getString("gameMode")).append(" | Durée: ").append(info.getLong("gameDuration")).append("s\n");
        sb.append("--- JOUEURS ---\n");

        for (int i = 0; i < participants.length(); i++) {
            JSONObject p = participants.getJSONObject(i);
            boolean isMe = p.getString("puuid").equals(myPuuid);
            
            String team = (p.getInt("teamId") == 100) ? "BLEU" : "ROUGE";
            String champ = p.getString("championName");
            String name = p.getString("summonerName");
            
            int k = p.getInt("kills");
            int d = p.getInt("deaths");
            int a = p.getInt("assists");
            int dmgDealt = p.getInt("totalDamageDealtToChampions");
            int dmgTaken = p.getInt("totalDamageTaken");
            int heal = p.getInt("totalHeal");
            int gold = p.getInt("goldEarned");
            int cs = p.getInt("totalMinionsKilled") + p.getInt("neutralMinionsKilled");
            int vision = p.getInt("visionScore");
            
            List<Integer> items = new ArrayList<>();
            for(int j=0; j<=6; j++) items.add(p.getInt("item" + j));

            sb.append(isMe ? "👉 MOI (" : "   (").append(team).append(") ").append(name).append(" - ").append(champ).append("\n");
            sb.append("   KDA: ").append(k).append("/").append(d).append("/").append(a);
            sb.append(" | Dmg: ").append(dmgDealt).append(" | Tank: ").append(dmgTaken).append(" | Heal: ").append(heal).append("\n");
            sb.append("   Gold: ").append(gold).append(" | CS: ").append(cs).append(" | Vision: ").append(vision).append("\n");
            sb.append("   Items IDs: ").append(items).append("\n");
            sb.append("   Win: ").append(p.getBoolean("win")).append("\n\n");
        }
        return sb.toString();
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