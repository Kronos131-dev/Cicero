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
    private final MatchDataProcessor matchProcessor = new MatchDataProcessor();
    
    private static final AtomicLong quotaResetTime = new AtomicLong(0);
    private static final AtomicBoolean isQuotaExceeded = new AtomicBoolean(false);

    public static final int QUEUE_SOLOQ = 420;
    public static final int QUEUE_FLEX = 440;
    public static final int QUEUE_CLASH = 700;
    public static final int QUEUE_ARAM = 450;

    // --- CACHES ---
    private final SimpleCache<String, String> puuidCache = new SimpleCache<>(24 * 60 * 60 * 1000); // 24h
    private final SimpleCache<String, Map<String, RankInfo>> rankCache = new SimpleCache<>(10 * 60 * 1000); // 10 min
    // Réduction du cache d'historique à 1 minute pour éviter de rater une game qui vient de finir
    private final SimpleCache<String, List<String>> matchHistoryCache = new SimpleCache<>(60 * 1000); // 1 min
    private final SimpleCache<String, String> matchAnalysisCache = new SimpleCache<>(30 * 60 * 1000); // 30 min
    private final SimpleCache<String, String> matchSummaryCache = new SimpleCache<>(30 * 60 * 1000); // 30 min
    private final SimpleCache<String, String> versionCache = new SimpleCache<>(6 * 60 * 60 * 1000); // 6h
    private final SimpleCache<String, JSONObject> itemsCache = new SimpleCache<>(24 * 60 * 60 * 1000); // 24h
    private final SimpleCache<String, JSONObject> runesCache = new SimpleCache<>(24 * 60 * 60 * 1000); // 24h

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
    public String getPuuid(String gameName, String tagLine) {
        try {
            String cacheKey = gameName.toLowerCase() + "#" + tagLine.toLowerCase();
            String cachedPuuid = puuidCache.get(cacheKey);
            if (cachedPuuid != null) return cachedPuuid;

            String safeName = URLEncoder.encode(gameName, StandardCharsets.UTF_8).replace("+", "%20");
            String url = "https://europe.api.riotgames.com/riot/account/v1/accounts/by-riot-id/" + safeName + "/" + tagLine;
            JSONObject json = executeRequest(url);
            if (!json.has("puuid")) return "Error: Compte introuvable.";
            
            String puuid = json.getString("puuid");
            puuidCache.put(cacheKey, puuid);
            return puuid;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool("Récupère le PUUID d'un joueur à partir de son nom d'invocateur (Summoner Name) et de sa région.")
    public String getPuuidBySummonerName(String summonerName, String region) {
        try {
            // Note: L'API Summoner V4 est toujours active mais Riot pousse vers Riot ID.
            // Cependant, pour les vieux comptes ou les recherches par nom simple, c'est utile.
            String safeName = URLEncoder.encode(summonerName, StandardCharsets.UTF_8).replace("+", "%20");
            String url = "https://" + region + ".api.riotgames.com/lol/summoner/v4/summoners/by-name/" + safeName;
            JSONObject json = executeRequest(url);
            if (!json.has("puuid")) return "Error: Invocateur introuvable.";
            
            return json.getString("puuid");
        } catch (Exception e) {
            // Si l'API Summoner échoue (souvent 404 si le nom a changé vers Riot ID), on tente une recherche Riot ID par défaut
            // On suppose que le summonerName est le GameName et on tente le tag par défaut de la région (ex: EUW)
            // C'est une heuristique risquée mais utile en fallback.
            return "Error: " + e.getMessage();
        }
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
    public String getRankInfoString(String puuid, String region) {
        try {
            RankInfo info = getAllRanks(puuid, region).get("SOLO");
            return "Rang SoloQ: " + info.tier + " " + info.rank + " (" + info.lp + " LP) - Winrate: " + info.wins + "W/" + info.losses + "L";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public RankInfo getRank(String puuid, String region) throws IOException {
        return getAllRanks(puuid, region).get("SOLO");
    }

    // --- MATCH HISTORY UTILS ---
    private String getMatchRegion(String region) {
        String r = region.toLowerCase();
        if (r.startsWith("eu") || r.equals("tr1") || r.equals("ru")) return "europe";
        if (r.startsWith("na") || r.startsWith("br") || r.startsWith("la")) return "americas";
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
    public String getLastMatchId(String puuid, String region) {
        try {
            List<String> ids = getMatchHistoryIds(puuid, region, null, 1);
            return ids.isEmpty() ? "None" : ids.get(0);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // --- SMART MATCH FINDER ---
    @Tool("Trouve un match spécifique selon des critères (Champion, KDA, ou index). Index 0 = dernier match.")
    public String findSpecificMatch(String puuid, String region, Integer queueId, String championName, String kdaScore, Integer indexOffset) {
        int safeIndex = (indexOffset != null) ? indexOffset : 0;
        try {
            List<String> matchIds = getMatchHistoryIds(puuid, region, queueId, 20);
            if (matchIds.isEmpty()) return "None";

            if (championName == null && kdaScore == null) {
                if (safeIndex < matchIds.size()) return matchIds.get(safeIndex);
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
            return "Error: " + e.getMessage();
        }
        return "None";
    }

    // --- METHODE 1: ANALYSE LOURDE (JSON STRUCTURÉ POUR L'IA) ---
    @Tool("Récupère une analyse APPROFONDIE (JSON complet: Timeline, Events, Items, Runes) d'un match. À utiliser pour analyser une partie précise.")
    public String getMatchAnalysis(String matchId, String targetPuuid, String region) {
        try {
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
                catch (IOException e) { 
                    // Si la timeline échoue (404 souvent), on renvoie un JSON vide pour ne pas bloquer l'analyse principale
                    return new JSONObject(); 
                }
            }, batchExecutor);

            JSONObject matchInfo = infoFuture.join();
            JSONObject timeline = timelineFuture.join();
            
            // Si la timeline est vide, on fait une analyse partielle
            if (timeline.isEmpty()) {
                // Fallback vers une analyse plus légère si la timeline manque
                return extractLightStats(matchInfo, targetPuuid).toString();
            }
            
            JSONObject analysisJson = matchProcessor.buildDeepAnalysisJson(matchInfo, timeline, targetPuuid);
            
            // --- ENRICHISSEMENT DES DONNÉES (Items & Runes) ---
            // On remplace les IDs par les noms directement ici pour éviter que l'IA ne doive appeler des outils
            enrichAnalysisWithNames(analysisJson);
            
            String result = analysisJson.toString();
            
            matchAnalysisCache.put(cacheKey, result);
            return result;
        } catch (Exception e) {
            // En cas d'erreur fatale, on essaie de renvoyer au moins le résumé léger
            try {
                return getMatchHistorySummary(targetPuuid, region, null, 1);
            } catch (Exception ex) {
                return "Error: " + e.getMessage();
            }
        }
    }

    // --- METHODE 2: ANALYSE LÉGÈRE (JSON ARRAY POUR L'IA) ---
    @Tool("Récupère un historique LÉGER (KDA, Win/Loss, Champion) des N derniers matchs. À utiliser pour voir les tendances ou le niveau général.")
    public String getMatchHistorySummary(String puuid, String region, Integer queueId, Integer count) {
        String cacheKey = puuid + "#" + region + "#" + queueId + "#" + count + "#JSON";
        String cachedSummary = matchSummaryCache.get(cacheKey);
        if (cachedSummary != null) return cachedSummary;

        int safeCount = (count != null) ? count : 5;

        try {
            List<String> matchIds = getMatchHistoryIds(puuid, region, queueId, Math.min(safeCount, 10));
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
            return "Error: " + e.getMessage();
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

    // --- DATA DRAGON (ITEMS & VERSIONS) ---
    public String getLatestVersion() {
        String cached = versionCache.get("latest");
        if (cached != null) return cached;
        try {
            JSONArray versions = executeRequestArray("https://ddragon.leagueoflegends.com/api/versions.json");
            String latest = versions.getString(0);
            versionCache.put("latest", latest);
            return latest;
        } catch (Exception e) {
            System.err.println("[Riot] Version Error: " + e.getMessage());
            return "16.2.1"; // Fallback
        }
    }

    public JSONObject getItemsData(String version) {
        JSONObject cached = itemsCache.get(version);
        if (cached != null) return cached;
        try {
            // On utilise fr_FR pour avoir les noms et descriptions en français
            JSONObject json = executeRequest("https://ddragon.leagueoflegends.com/cdn/" + version + "/data/fr_FR/item.json");
            JSONObject data = json.getJSONObject("data");
            itemsCache.put(version, data);
            return data;
        } catch (Exception e) {
            System.err.println("[Riot] Items Error: " + e.getMessage());
            return new JSONObject();
        }
    }

    public JSONObject getRunesData(String version) {
        JSONObject cached = runesCache.get(version);
        if (cached != null) return cached;
        try {
            // Le format des runes est un tableau, on le convertit en Map pour un accès rapide par ID
            JSONArray jsonArray = executeRequestArray("https://ddragon.leagueoflegends.com/cdn/" + version + "/data/fr_FR/runesReforged.json");
            JSONObject runesMap = new JSONObject();
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject tree = jsonArray.getJSONObject(i);
                int treeId = tree.getInt("id");
                runesMap.put(String.valueOf(treeId), tree.getString("name")); // Arbre principal (ex: Domination)
                
                JSONArray slots = tree.getJSONArray("slots");
                for (int j = 0; j < slots.length(); j++) {
                    JSONArray runes = slots.getJSONObject(j).getJSONArray("runes");
                    for (int k = 0; k < runes.length(); k++) {
                        JSONObject rune = runes.getJSONObject(k);
                        runesMap.put(String.valueOf(rune.getInt("id")), rune.getString("name"));
                    }
                }
            }
            runesCache.put(version, runesMap);
            return runesMap;
        } catch (Exception e) {
            System.err.println("[Riot] Runes Error: " + e.getMessage());
            return new JSONObject();
        }
    }

    private void enrichAnalysisWithNames(JSONObject analysis) {
        try {
            String version = getLatestVersion();
            JSONObject itemsData = getItemsData(version);
            JSONObject runesData = getRunesData(version);

            // 1. Enrichir le joueur cible
            if (analysis.has("target_player")) {
                enrichPlayerStats(analysis.getJSONObject("target_player"), itemsData, runesData);
                
                // Enrichir le build path (Timeline)
                if (analysis.getJSONObject("target_player").has("build_path")) {
                    JSONArray buildPath = analysis.getJSONObject("target_player").getJSONArray("build_path");
                    for (int i = 0; i < buildPath.length(); i++) {
                        JSONObject event = buildPath.getJSONObject(i);
                        String itemId = String.valueOf(event.getInt("itemId"));
                        if (itemsData.has(itemId)) {
                            event.put("itemName", itemsData.getJSONObject(itemId).getString("name"));
                        }
                    }
                }
            }

            // 2. Enrichir les alliés
            if (analysis.has("allies")) {
                JSONArray allies = analysis.getJSONArray("allies");
                for (int i = 0; i < allies.length(); i++) {
                    enrichPlayerStats(allies.getJSONObject(i), itemsData, runesData);
                }
            }

            // 3. Enrichir les ennemis
            if (analysis.has("enemies")) {
                JSONArray enemies = analysis.getJSONArray("enemies");
                for (int i = 0; i < enemies.length(); i++) {
                    enrichPlayerStats(enemies.getJSONObject(i), itemsData, runesData);
                }
            }

        } catch (Exception e) {
            System.err.println("[Riot] Enrichment Error: " + e.getMessage());
        }
    }

    private void enrichPlayerStats(JSONObject player, JSONObject itemsData, JSONObject runesData) {
        // Items
        if (player.has("final_items")) {
            JSONArray itemIds = player.getJSONArray("final_items");
            JSONArray itemNames = new JSONArray();
            for (int i = 0; i < itemIds.length(); i++) {
                String id = String.valueOf(itemIds.getInt(i));
                if (itemsData.has(id)) {
                    itemNames.put(itemsData.getJSONObject(id).getString("name"));
                } else {
                    itemNames.put("Item " + id);
                }
            }
            player.put("final_items_names", itemNames);
        }

        // Runes
        if (player.has("rune_keystone")) {
            String id = String.valueOf(player.getInt("rune_keystone"));
            player.put("rune_keystone_name", runesData.optString(id, "Unknown Rune"));
        }
        if (player.has("rune_primary_tree")) {
            String id = String.valueOf(player.getInt("rune_primary_tree"));
            player.put("rune_primary_tree_name", runesData.optString(id, "Unknown Tree"));
        }
        if (player.has("rune_secondary_tree")) {
            String id = String.valueOf(player.getInt("rune_secondary_tree"));
            player.put("rune_secondary_tree_name", runesData.optString(id, "Unknown Tree"));
        }
    }

    @Tool("Récupère les détails (Nom, Description, Coût) d'une liste d'items via leurs IDs (séparés par des virgules). Utile pour comprendre le build d'un joueur.")
    public String getItemDetails(String itemIdsCommaSeparated) {
        try {
            String version = getLatestVersion();
            JSONObject itemsData = getItemsData(version);
            
            StringBuilder sb = new StringBuilder();
            // Nettoyage des IDs (suppression des crochets éventuels si l'IA envoie "[1001, 3070]")
            String cleanIds = itemIdsCommaSeparated.replace("[", "").replace("]", "").replace("\"", "");
            String[] ids = cleanIds.split(",");
            
            for (String idStr : ids) {
                String id = idStr.trim();
                if (id.isEmpty() || id.equals("0")) continue;

                if (itemsData.has(id)) {
                    JSONObject item = itemsData.getJSONObject(id);
                    String name = item.getString("name");
                    String plaintext = item.optString("plaintext", "Pas de description courte");
                    int totalGold = item.getJSONObject("gold").getInt("total");
                    
                    sb.append("- ID ").append(id).append(" : ").append(name)
                      .append(" (").append(totalGold).append(" PO) -> ")
                      .append(plaintext).append("\n");
                }
            }
            return sb.length() > 0 ? sb.toString() : "Aucun item trouvé pour ces IDs.";
        } catch (Exception e) {
            return "Erreur lors de la récupération des items : " + e.getMessage();
        }
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