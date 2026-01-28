package org.example;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.json.JSONArray;
import org.json.JSONObject;

public class GeminiService {
    private final Client client;

    // On utilise le modèle que tu as vu dans ta liste (le plus puissant et disponible)
    // Si ça bug, remplace par "gemini-1.5-flash"
    private static final String MODEL_NAME = "gemma-3-27b-it";

    public GeminiService(String apiKey) {
        // Le SDK gère tout : connexion, timeout, etc.
        this.client = Client.builder().apiKey(apiKey).build();
    }

    // --- Méthode Simple ---
    public String analyzeGame(String userQuestion, String gameStatsContext) {
        try {
            // On combine tout dans un seul prompt clair
            String fullPrompt = "Rôle: Coach League of Legends expert et taquin.\n" +
                    "Contexte du match:\n" + gameStatsContext + "\n\n" +
                    "Question du joueur: " + userQuestion;

            GenerateContentResponse response = client.models.generateContent(
                    MODEL_NAME,
                    fullPrompt,
                    null
            );

            return response.text();
        } catch (Exception e) {
            // En cas d'erreur (quota, modèle introuvable...), on renvoie le message proprement
            return "❌ Erreur AI : " + e.getMessage();
        }
    }

    // --- Méthode Chat (Compatibilité) ---
    public String chatWithHistory(JSONArray previousHistory, String systemContext) {
        try {
            // Avec le SDK simple, le plus efficace est de renvoyer l'historique sous forme de texte
            StringBuilder conversation = new StringBuilder();
            conversation.append("Système: ").append(systemContext).append("\n");

            for (int i = 0; i < previousHistory.length(); i++) {
                JSONObject msg = previousHistory.getJSONObject(i);
                conversation.append(msg.getString("role")).append(": ")
                        .append(msg.getString("content")).append("\n");
            }

            GenerateContentResponse response = client.models.generateContent(
                    MODEL_NAME,
                    conversation.toString(),
                    null
            );

            return response.text();
        } catch (Exception e) {
            return "❌ Erreur AI : " + e.getMessage();
        }
    }
}