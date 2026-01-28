package org.example.service;

import com.google.genai.Client;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

public class GeminiService {
    private final Client client1;
    private final Client client2;

    // Restauration des modèles standards (Flash est le plus rapide/efficace actuellement)
    private static final String MODEL_ELITE = "gemini-3-flash-preview";
    private static final String MODEL_TANK = "gemma-3-27b-it";

    private static long key1BlockedUntil = 0;
    private static long key2BlockedUntil = 0;
    private static final long BLOCK_DURATION = 24 * 60 * 60 * 1000; 

    public GeminiService() {
        Dotenv dotenv = Dotenv.load();
        String key1 = dotenv.get("GEMINI_API_KEY_1");
        String key2 = dotenv.get("GEMINI_API_KEY_2");

        if (key2 == null || key2.isEmpty()) key2 = key1;

        this.client1 = Client.builder().apiKey(key1).build();
        this.client2 = Client.builder().apiKey(key2).build();
    }

    public String analyzeGame(String userQuestion, String gameStatsContext) {
        String fullPrompt = "Rôle : Coach Expert de League of Legends.\n" +
                "Instruction : Réponds TOUJOURS en français.\n" +
                "Contexte du match (Tous les joueurs) :\n" + gameStatsContext + "\n\n" +
                "Question du joueur : " + userQuestion;
        return smartGenerate(fullPrompt);
    }

    public String chatWithHistory(JSONArray previousHistory, String systemContext) {
        StringBuilder conversation = new StringBuilder();
        conversation.append("Système : ").append(systemContext)
                    .append(" (Réponds impérativement en français.)\n");

        for (int i = 0; i < previousHistory.length(); i++) {
            JSONObject msg = previousHistory.getJSONObject(i);
            String role = msg.getString("role").equals("user") ? "Utilisateur" : "Assistant";
            conversation.append(role).append(": ")
                    .append(msg.getString("content")).append("\n");
        }
        return smartGenerate(conversation.toString());
    }

    private String smartGenerate(String prompt) {
        long now = System.currentTimeMillis();

        if (now > key1BlockedUntil) {
            try {
                return generateWithRetry(client1, MODEL_ELITE, prompt);
            } catch (Exception e1) {
                if (isQuotaError(e1)) {
                    System.out.println("⚠️ Quota Key 1 reached.");
                    key1BlockedUntil = now + BLOCK_DURATION;
                } else {
                    return "❌ Erreur IA (Clé 1) : " + e1.getMessage();
                }
            }
        }

        if (now > key2BlockedUntil) {
            try {
                return generateWithRetry(client2, MODEL_ELITE, prompt);
            } catch (Exception e2) {
                if (isQuotaError(e2)) {
                    System.out.println("⚠️ Quota Key 2 reached.");
                    key2BlockedUntil = now + BLOCK_DURATION;
                } else {
                    return "❌ Erreur IA (Clé 2) : " + e2.getMessage();
                }
            }
        }

        try {
            System.out.println("⚠️ Switching to Tank model...");
            return generateWithRetry(client1, MODEL_TANK, prompt);
        } catch (Exception e3) {
            return "❌ Panne critique IA : " + e3.getMessage();
        }
    }

    private String generateWithRetry(Client client, String model, String prompt) throws Exception {
        int maxRetries = 2;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return client.models.generateContent(model, prompt, null).text();
            } catch (Exception e) {
                if (e.getMessage().contains("503") && i < maxRetries) {
                    Thread.sleep(2000);
                    continue;
                }
                throw e;
            }
        }
        throw new Exception("Échec après plusieurs tentatives");
    }

    private boolean isQuotaError(Exception e) {
        String msg = e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted");
    }
}