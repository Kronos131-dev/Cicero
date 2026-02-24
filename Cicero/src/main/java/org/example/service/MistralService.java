package org.example.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.data.message.SystemMessage;
import io.github.cdimascio.dotenv.Dotenv;
import org.example.service.ai.PromptRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class MistralService {

    interface LolAgent {
        String chat(String userMessage);
    }

    public static class HistoryTool {
        private final JSONArray history;

        public HistoryTool(JSONArray history) {
            this.history = history;
        }

        @Tool("Récupère l'historique récent de la conversation. Utile pour comprendre le contexte des échanges passés.")
        public String getConversationHistory() {
            if (history == null || history.isEmpty()) return "Aucun historique disponible.";

            StringBuilder sb = new StringBuilder("[HISTORIQUE RÉCENT]\n");
            for (int i = 0; i < history.length(); i++) {
                JSONObject msg = history.getJSONObject(i);
                String role = msg.getString("role");
                String content = msg.optString("content", "");

                // On filtre les messages vides ou les blocs JSON techniques pour ne garder que la discussion
                if (!content.isBlank() && !content.trim().startsWith("{")) {
                    sb.append(role.equalsIgnoreCase("user") ? "Utilisateur: " : "IA: ")
                            .append(content).append("\n");
                }
            }
            return sb.toString();
        }
    }

    private final ChatModel model;
    private final List<Object> defaultTools;
    private final TavilyService tavilyService;

    // ThreadLocal pour stocker les traces par thread (requête)
    private static final ThreadLocal<StringBuilder> agentTraceLog = ThreadLocal.withInitial(StringBuilder::new);

    public MistralService(RiotService riotService, TavilyService tavilyService) {
        Dotenv dotenv = Dotenv.load();
        String apiKey = dotenv.get("MISTRAL_API_KEY");

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("MISTRAL_API_KEY is missing!");
        }

        // Configuration du modèle avec les nouveaux standards 1.10.0
        this.model = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("mistral-large-latest")
                .temperature(0.7)
                .timeout(Duration.ofMinutes(5)) // On garde le timeout long pour les analyses Riot + Tavily
                .logRequests(true)
                .logResponses(true)
                .build();

        this.tavilyService = tavilyService;
        this.defaultTools = List.of(riotService, tavilyService);
    }

    private LolAgent createAgent(ChatMemory chatMemory, List<Object> tools) {
        return AiServices.builder(LolAgent.class)
                .chatModel(model) // Version 1.10.0 : .chatModel au lieu de .chatLanguageModel
                .chatMemory(chatMemory)
                .tools(tools)
                .build();
    }

    public String analyzeGame(String userQuestion, String gameStatsContext) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

        String systemPrompt = PromptRegistry.GAME_ANALYSIS_SYSTEM + gameStatsContext;

        memory.add(new SystemMessage(systemPrompt));

        // Log de la trace pour debug
        logAgentTrace(systemPrompt, userQuestion);

        LolAgent agent = createAgent(memory, this.defaultTools);
        try {
            String response = agent.chat(userQuestion);
            logChatMemory(memory);
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            logChatMemory(memory);
            return "Erreur lors de l'analyse du match : " + e.getMessage();
        }
    }

    /**
     * Exécute une tâche spécifique avec un contexte système entièrement contrôlé.
     * Utile pour les commandes comme /performance qui nécessitent un format de sortie strict (JSON).
     */
    public String performTask(String userMessage, String fullSystemContext) {
        // Augmentation de la mémoire pour éviter l'éviction des messages lors des appels d'outils multiples
        // 30 messages devraient suffire pour : System + User + (Assistant(Tool) + Tool(Result)) * 14
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(30);
        memory.add(new SystemMessage(fullSystemContext));

        logAgentTrace(fullSystemContext, userMessage);

        LolAgent agent = createAgent(memory, this.defaultTools);
        try {
            String response = agent.chat(userMessage);
            logChatMemory(memory);
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            logChatMemory(memory);
            return "Erreur technique : " + e.getMessage();
        }
    }

    public String chatWithHistory(JSONArray previousHistory, String systemContext, String originalUserQuestion) {
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(20);
        chatMemory.add(new SystemMessage(systemContext));

        List<Object> toolsWithHistory = new ArrayList<>(this.defaultTools);
        toolsWithHistory.add(new HistoryTool(previousHistory));

        // Log de la trace pour debug (incluant l'historique)
        StringBuilder fullTraceContext = new StringBuilder(systemContext);
        if (previousHistory != null && !previousHistory.isEmpty()) {
            try {
                fullTraceContext.append("\n\n[HISTORY (Available via Tool)]\n").append(previousHistory.toString(2));
            } catch (Exception e) {
                fullTraceContext.append("\n\n[HISTORY (Error formatting JSON)]\n").append(previousHistory.toString());
            }
        }
        logAgentTrace(fullTraceContext.toString(), originalUserQuestion);

        LolAgent agent = createAgent(chatMemory, toolsWithHistory);

        try {
            String response = agent.chat(originalUserQuestion);
            logChatMemory(chatMemory);
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            logChatMemory(chatMemory);
            return "Une erreur technique est survenue. Veuillez réessayer.";
        }
    }

    private void logAgentTrace(String systemContext, String userQuestion) {
        StringBuilder sb = agentTraceLog.get();
        sb.append("=== AGENT TRACE (INITIAL CONTEXT) ===\n");
        sb.append("SYSTEM CONTEXT:\n").append(systemContext).append("\n");
        sb.append("-----------------------------------------\n");
        sb.append("USER QUESTION: ").append(userQuestion).append("\n");
        sb.append("=====================================================\n\n");
    }

    private void logChatMemory(ChatMemory memory) {
        StringBuilder sb = agentTraceLog.get();
        sb.append("=== FINAL CHAT MEMORY (FULL INTERACTION) ===\n");
        try {
            for (ChatMessage msg : memory.messages()) {
                sb.append("[").append(msg.type()).append("]: ").append(msg.toString()).append("\n");
            }
        } catch (Exception e) {
            sb.append("Error reading chat memory: ").append(e.getMessage()).append("\n");
        }
        sb.append("=====================================================\n\n");
    }

    /**
     * Écrit les traces accumulées (Agent + Tavily) dans les fichiers respectifs.
     * Cette méthode doit être appelée après la génération de la réponse.
     * Synchronisée pour éviter les conflits d'écriture si plusieurs commandes finissent en même temps.
     */
    public void flushTraces() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        // Récupération et nettoyage des ThreadLocals
        String agentTrace = agentTraceLog.get().toString();
        agentTraceLog.remove();
        
        String tavilyTrace = tavilyService.getAndResetTrace();

        // Synchronisation de l'écriture fichier
        synchronized (MistralService.class) {
            // Gestion de trace.txt
            if (!agentTrace.isEmpty()) {
                try (FileWriter fw = new FileWriter("trace.txt", false); // Overwrite pour ne garder que la dernière commande
                     PrintWriter pw = new PrintWriter(fw)) {
                    pw.println("TIMESTAMP: " + timestamp);
                    pw.println(agentTrace);
                } catch (IOException e) {
                    System.err.println("Failed to write to trace.txt: " + e.getMessage());
                }
            } else {
                // Si pas de trace Agent, on vide le fichier
                try (FileWriter fw = new FileWriter("trace.txt", false)) {
                } catch (IOException e) {
                    System.err.println("Failed to clear trace.txt: " + e.getMessage());
                }
            }

            // Gestion de trace_tavily.txt
            if (!tavilyTrace.isEmpty()) {
                try (FileWriter fw = new FileWriter("trace_tavily.txt", false); // Overwrite
                     PrintWriter pw = new PrintWriter(fw)) {
                    pw.println("TIMESTAMP: " + timestamp);
                    pw.println(tavilyTrace);
                } catch (IOException e) {
                    System.err.println("Failed to write to trace_tavily.txt: " + e.getMessage());
                }
            } else {
                 // Si pas de trace Tavily, on vide le fichier
                 try (FileWriter fw = new FileWriter("trace_tavily.txt", false)) {
                 } catch (IOException e) {
                     System.err.println("Failed to clear trace_tavily.txt: " + e.getMessage());
                 }
            }
        }
    }
}