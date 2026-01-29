package org.example.service;

import dev.langchain4j.agent.tool.Tool;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TavilyService {
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private final String apiKey;
    private final OkHttpClient client;

    public TavilyService() {
        Dotenv dotenv = Dotenv.load();
        this.apiKey = dotenv.get("TAVILY_API_KEY");
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        if (this.apiKey == null || this.apiKey.isEmpty()) {
            System.err.println("⚠️ TAVILY_API_KEY is missing in .env!");
        }
    }

    /**
     * Performs a search query using Tavily API.
     * @param query The search query.
     * @return A formatted string containing the answer and search results.
     */
    @Tool("Effectue une recherche sur le web pour obtenir des informations récentes (Méta, Patchs, Builds, Esport).")
    public String search(String query) {
        return search(query, null);
    }

    /**
     * Performs a search query using Tavily API with specific domains.
     * @param query The search query.
     * @param domains List of domains to include in the search (e.g., "lolalytics.com").
     * @return A formatted string containing the answer and search results.
     */
    public String search(String query, List<String> domains) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: TAVILY_API_KEY is not configured.";
        }

        JSONObject jsonBody = new JSONObject();
        jsonBody.put("api_key", apiKey);
        jsonBody.put("query", query);
        jsonBody.put("search_depth", "basic"); // or "advanced"
        jsonBody.put("include_answer", true);
        jsonBody.put("max_results", 5);
        
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
                return "Error: Tavily API request failed with code " + response.code() + " - " + response.message();
            }
            if (response.body() == null) {
                return "Error: Empty response from Tavily API.";
            }

            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);

            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("--- Search Results for: ").append(query).append(" ---\n\n");

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

            return resultBuilder.toString();

        } catch (IOException e) {
            e.printStackTrace();
            return "Error: Exception during Tavily API call - " + e.getMessage();
        }
    }
}