package org.example;

import com.google.genai.Client;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Service for interacting with Google's Gemini AI.
 * Implements a multi-key strategy with fallback models and quota management.
 */
public class GeminiService {
    private final Client client1;
    private final Client client2;

    private static final String MODEL_ELITE = "gemini-3-flash-preview";
    private static final String MODEL_TANK = "gemma-3-27b-it";

    // Quota Management (Static to persist across instances)
    private static long key1BlockedUntil = 0;
    private static long key2BlockedUntil = 0;
    private static final long BLOCK_DURATION = 24 * 60 * 60 * 1000; // 24 hours

    public GeminiService() {
        Dotenv dotenv = Dotenv.load();
        String key1 = dotenv.get("GEMINI_API_KEY_1");
        String key2 = dotenv.get("GEMINI_API_KEY_2");

        if (key2 == null || key2.isEmpty()) key2 = key1;

        this.client1 = Client.builder().apiKey(key1).build();
        this.client2 = Client.builder().apiKey(key2).build();
    }

    public String analyzeGame(String userQuestion, String gameStatsContext) {
        String fullPrompt = "Role: Expert League of Legends Coach.\n" +
                "Match Context:\n" + gameStatsContext + "\n\n" +
                "Player Question: " + userQuestion;
        return smartGenerate(fullPrompt);
    }

    public String chatWithHistory(JSONArray previousHistory, String systemContext) {
        StringBuilder conversation = new StringBuilder();
        conversation.append("System: ").append(systemContext).append("\n");

        for (int i = 0; i < previousHistory.length(); i++) {
            JSONObject msg = previousHistory.getJSONObject(i);
            conversation.append(msg.getString("role")).append(": ")
                    .append(msg.getString("content")).append("\n");
        }
        return smartGenerate(conversation.toString());
    }

    private String smartGenerate(String prompt) {
        long now = System.currentTimeMillis();

        // ATTEMPT 1: Key 1 (Elite)
        if (now > key1BlockedUntil) {
            try {
                return generateWithRetry(client1, MODEL_ELITE, prompt);
            } catch (Exception e1) {
                if (isQuotaError(e1)) {
                    System.out.println("⚠️ Quota Key 1 reached. Blocking for 24h.");
                    key1BlockedUntil = now + BLOCK_DURATION;
                } else {
                    return "❌ AI Error (Key 1): " + e1.getMessage();
                }
            }
        }

        // ATTEMPT 2: Key 2 (Elite)
        if (now > key2BlockedUntil) {
            try {
                return generateWithRetry(client2, MODEL_ELITE, prompt);
            } catch (Exception e2) {
                if (isQuotaError(e2)) {
                    System.out.println("⚠️ Quota Key 2 reached. Blocking for 24h.");
                    key2BlockedUntil = now + BLOCK_DURATION;
                } else {
                    return "❌ AI Error (Key 2): " + e2.getMessage();
                }
            }
        }

        // ATTEMPT 3: Tank Model (Fallback with Key 1)
        try {
            System.out.println("⚠️ Elite Quota exhausted. Switching to Tank model...");
            return generateWithRetry(client1, MODEL_TANK, prompt);
        } catch (Exception e3) {
            return "❌ Critical AI Failure: All models unavailable. " + e3.getMessage();
        }
    }

    private String generateWithRetry(Client client, String model, String prompt) throws Exception {
        int maxRetries = 2;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return client.models.generateContent(model, prompt, null).text();
            } catch (Exception e) {
                if (e.getMessage().contains("503") && i < maxRetries) {
                    System.out.println("⚠️ 503 Overload. Retrying in 2s (" + (i + 1) + "/" + maxRetries + ")...");
                    Thread.sleep(2000);
                    continue;
                }
                throw e;
            }
        }
        throw new Exception("Failed after retries");
    }

    private boolean isQuotaError(Exception e) {
        String msg = e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted");
    }
}