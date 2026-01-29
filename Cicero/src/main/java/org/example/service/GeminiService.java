package org.example.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Service for interacting with Google's Gemini AI using LangChain4j.
 * Integrates RiotAPI and Tavily as tools.
 */
public class GeminiService {

    interface LolAgent {
        @SystemMessage("Tu es un expert de League of Legends (Coach Challenger). " +
                "Tu as accès à des outils pour chercher des infos sur les joueurs (Riot API) et sur le web (Tavily). " +
                "Utilise ces outils pour répondre précisément aux questions. " +
                "Réponds TOUJOURS en français.")
        String chat(String userMessage);
    }

    private final ChatLanguageModel model;
    private final List<Object> tools;
    
    // Cache simple pour éviter de rappeler l'agent pour la même requête exacte
    private final SimpleCache<String, String> responseCache = new SimpleCache<>(60 * 60 * 1000); // 1h cache

    public GeminiService(RiotService riotService, TavilyService tavilyService) {
        Dotenv dotenv = Dotenv.load();
        String apiKey = dotenv.get("GEMINI_API_KEY_1");

        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("⚠️ GEMINI_API_KEY_1 is missing!");
        }

        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-1.5-pro")
                .temperature(0.7)
                .build();

        this.tools = Arrays.asList(riotService, tavilyService);
    }

    private LolAgent createAgent() {
        return AiServices.builder(LolAgent.class)
                .chatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20)) // Mémoire locale pour la boucle d'exécution des outils
                .tools(tools)
                .build();
    }

    public String analyzeGame(String userQuestion, String gameStatsContext) {
        String fullPrompt = "Contexte du match (JSON) :\n" + gameStatsContext + "\n\n" +
                "Question du joueur : " + userQuestion;
        
        // Check cache
        String cacheKey = Integer.toHexString(fullPrompt.hashCode());
        String cachedResponse = responseCache.get(cacheKey);
        if (cachedResponse != null) return cachedResponse;

        String response = createAgent().chat(fullPrompt);
        
        if (response != null) {
            responseCache.put(cacheKey, response);
        }
        return response;
    }

    public String chatWithHistory(JSONArray previousHistory, String systemContext, AiContextService.Intent intent, String originalUserQuestion) {
        StringBuilder conversation = new StringBuilder();
        conversation.append("Système : ").append(systemContext).append("\n");
        
        for (int i = 0; i < previousHistory.length(); i++) {
            JSONObject msg = previousHistory.getJSONObject(i);
            String role = msg.getString("role").equals("user") ? "Utilisateur" : "Assistant";
            conversation.append(role).append(": ").append(msg.getString("content")).append("\n");
        }
        conversation.append("Utilisateur: ").append(originalUserQuestion);

        String fullPrompt = conversation.toString();
        
        // Check cache (avec suffixe pour différencier les contextes)
        String cacheKey = Integer.toHexString(fullPrompt.hashCode());
        String cachedResponse = responseCache.get(cacheKey);
        if (cachedResponse != null) return cachedResponse;

        String response = createAgent().chat(fullPrompt);

        if (response != null) {
            responseCache.put(cacheKey, response);
        }
        return response;
    }
}