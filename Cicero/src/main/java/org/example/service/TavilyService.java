package org.example.service;

import dev.langchain4j.agent.tool.Tool;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TavilyService {
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    
    // Domaines restreints pour l'optimisation
    private static final List<String> ESPORT_DOMAINS = Arrays.asList("lol.fandom.com", "dpm.lol");
    private static final List<String> META_DOMAINS = Arrays.asList("lolalytics.com", "dpm.lol");

    private final String apiKey;
    private final OkHttpClient client;

    // ThreadLocal pour stocker les traces par thread (requête)
    private static final ThreadLocal<StringBuilder> traceLog = ThreadLocal.withInitial(StringBuilder::new);

    public TavilyService() {
        Dotenv dotenv = Dotenv.load();
        this.apiKey = dotenv.get("TAVILY_API_KEY");
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // Augmenté pour le mode "advanced"
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        if (this.apiKey == null || this.apiKey.isEmpty()) {
            System.err.println("⚠️ TAVILY_API_KEY is missing in .env!");
        }
    }

    /**
     * Outil spécialisé pour l'Esport.
     * Force l'utilisation de lol.fandom.com et dpm.lol en mode approfondi.
     */
    @Tool("Recherche des informations ESPORT (Résultats de matchs pro, Stats de joueurs pro, Historique des ligues, Rosters).")
    public String searchEsport(String query) {
        // On ajoute "League of Legends Esports Wiki" pour guider la recherche sur le bon sous-domaine/path sémantique
        String optimizedQuery = query + " League of Legends Esports Wiki";
        return executeSearch(optimizedQuery, ESPORT_DOMAINS, "advanced");
    }

    /**
     * Outil spécialisé pour la Méta.
     * Force l'utilisation de lolalytics.com et dpm.lol en mode approfondi.
     */
    @Tool("Recherche des informations sur la MÉTA GÉNÉRALE (Meilleurs Champions, Builds, Items, Tierlists, Winrates par patch).")
    public String searchMeta(String query) {
        return executeSearch(query, META_DOMAINS, "advanced");
    }

    /**
     * Méthode interne générique pour exécuter la requête Tavily.
     */
    private String executeSearch(String query, List<String> domains, String depth) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: TAVILY_API_KEY is not configured.";
        }

        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("api_key", apiKey);
            jsonBody.put("query", query);
            jsonBody.put("search_depth", depth); // "basic" or "advanced"
            jsonBody.put("include_answer", true);
            jsonBody.put("max_results", 7); // Augmenté légèrement pour avoir plus de contexte
            
            if (domains != null && !domains.isEmpty()) {
                JSONArray domainsArray = new JSONArray();
                for (String domain : domains) {
                    domainsArray.put(domain);
                }
                jsonBody.put("include_domains", domainsArray);
            }

            RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(TAVILY_API_URL)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String error = "Error: Tavily API request failed with code " + response.code() + " - " + response.message();
                    logTavilyTrace(query, domains, error);
                    return error;
                }
                if (response.body() == null) {
                    String error = "Error: Empty response from Tavily API.";
                    logTavilyTrace(query, domains, error);
                    return error;
                }

                String responseBody = response.body().string();
                JSONObject jsonResponse = new JSONObject(responseBody);

                StringBuilder resultBuilder = new StringBuilder();
                resultBuilder.append("--- Search Results (" + depth + ") for: ").append(query).append(" ---\n");
                resultBuilder.append("Sources: ").append(domains).append("\n\n");

                if (jsonResponse.has("answer") && !jsonResponse.isNull("answer")) {
                    resultBuilder.append("Short Answer: ").append(jsonResponse.getString("answer")).append("\n\n");
                }

                if (jsonResponse.has("results")) {
                    JSONArray results = jsonResponse.getJSONArray("results");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject result = results.getJSONObject(i);
                        String title = result.optString("title", "No Title");
                        String url = result.optString("url", "No URL");
                        String content = result.optString("content", "No Content");

                        resultBuilder.append("[").append(i + 1).append("] ").append(title).append("\n");
                        resultBuilder.append("URL: ").append(url).append("\n");
                        resultBuilder.append("Content: ").append(content).append("\n\n");
                    }
                }

                String finalResult = resultBuilder.toString();
                logTavilyTrace(query, domains, finalResult);
                return finalResult;
            }
        } catch (Exception e) {
            e.printStackTrace();
            String error = "Error: Exception during Tavily API call - " + e.getMessage();
            logTavilyTrace(query, domains, error);
            return error;
        }
    }

    private void logTavilyTrace(String query, List<String> domains, String result) {
        StringBuilder sb = traceLog.get();
        sb.append("=== TAVILY TRACE ===\n");
        sb.append("QUERY: ").append(query).append("\n");
        sb.append("DOMAINS: ").append(domains).append("\n");
        sb.append("RESULT:\n").append(result).append("\n");
        sb.append("=====================================================\n\n");
    }

    /**
     * Récupère la trace accumulée pour le thread courant et la réinitialise.
     */
    public String getAndResetTrace() {
        String trace = traceLog.get().toString();
        traceLog.remove(); // Nettoie le ThreadLocal
        return trace;
    }
}