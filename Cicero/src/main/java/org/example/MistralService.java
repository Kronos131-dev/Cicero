package org.example;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

public class MistralService {
    private final String apiKey;
    private final OkHttpClient client;
    private static final String MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions";

    public MistralService(String apiKey) {
        this.apiKey = apiKey;
        this.client = new OkHttpClient();
    }

    // Ancienne méthode (pour compatibilité)
    public String analyzeGame(String userQuestion, String gameStatsContext) throws IOException {
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", "Tu es un coach expert de League of Legends. " +
                "Sois concis, direct et un peu taquin. " +
                "Utilise les stats suivantes pour répondre au joueur :\n" + gameStatsContext);
        messages.put(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userQuestion);
        messages.put(userMsg);

        return sendRequest(messages);
    }

    // Nouvelle méthode avec historique complet
    public String chatWithHistory(JSONArray history, String systemContext) throws IOException {
        // On construit le payload final
        JSONArray finalMessages = new JSONArray();

        // 1. System Prompt (Toujours en premier)
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContext);
        finalMessages.put(systemMsg);

        // 2. Historique de la conversation
        for (int i = 0; i < history.length(); i++) {
            finalMessages.put(history.getJSONObject(i));
        }

        return sendRequest(finalMessages);
    }

    private String sendRequest(JSONArray messages) throws IOException {
        JSONObject payload = new JSONObject();
        payload.put("model", "mistral-small-latest");
        payload.put("messages", messages);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(MISTRAL_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Erreur Mistral: " + response.body().string());
            JSONObject jsonResponse = new JSONObject(response.body().string());
            return jsonResponse.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content");
        }
    }
}