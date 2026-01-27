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

public class RiotService {
    private final OkHttpClient client;

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
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .header("X-Riot-Token", apiKey.trim())
                            .build();
                    return chain.proceed(request);
                })
                .build();
    }

    // --- 1. LINK ---
    public String getPuuid(String gameName, String tagLine) throws IOException {
        String safeName = URLEncoder.encode(gameName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/" + safeName + "/" + tagLine;

        JSONObject json = executeRequest(url);
        if (!json.has("puuid")) throw new IOException("Compte introuvable.");

        return json.getString("puuid");
    }

    // --- 2. RANK (Méthode Directe via PUUID) ---
    // Retourne une Map avec "SOLO" et "FLEX"
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
            System.err.println("⚠️ Erreur lors de la récupération du rang : " + e.getMessage());
        }
        return ranks;
    }

    // Garde l'ancienne méthode pour compatibilité, mais utilise la nouvelle logique
    public RankInfo getRank(String puuid) throws IOException {
        return getAllRanks(puuid).get("SOLO");
    }

    // --- 3. CHAMPION MASTERY (Top 3 Champions) ---
    public String getTopChampions(String puuid) {
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
            return "Inconnu";
        }
    }

    // --- 4. MATCH HISTORY (Dernières 5 games) ---
    public List<String> getLastMatchesIds(String puuid, int count) throws IOException {
        String url = "https://europe.api.riotgames.com/lol/match/v5/matches/by-puuid/" + puuid.trim() + "/ids?start=0&count=" + count;
        JSONArray matches = executeRequestArray(url);
        List<String> ids = new ArrayList<>();
        for(int i=0; i<matches.length(); i++) ids.add(matches.getString(i));
        return ids;
    }

    public String getLastMatchId(String puuid) throws IOException {
        List<String> ids = getLastMatchesIds(puuid, 1);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public JSONObject getMatchDetails(String matchId) throws IOException {
        return executeRequest("https://europe.api.riotgames.com/lol/match/v5/matches/" + matchId);
    }

    // --- HTTP ---
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