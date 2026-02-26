package org.example.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.github.cdimascio.dotenv.Dotenv;
import org.example.service.ai.PromptRegistry;
import org.example.service.ai.Records.MatchAnalysisResult; // <-- Le bon import est ici !
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

    // ========================================================================
    // 1. DÉFINITION DES AGENTS (INTERFACES LANGCHAIN4J)
    // ========================================================================

    /**
     * NŒUD 3 : L'Agent Analyste.
     * Il retourne un objet Java strict (MatchAnalysisResult) généré au format JSON par Mistral.
     */
    interface AnalystAgent {
        @SystemMessage(PromptRegistry.PERFORMANCE_ANALYST_SYSTEM)
        MatchAnalysisResult adjustScores(@UserMessage String matchData);
    }

    /**
     * NŒUD 4 : L'Agent Caster.
     * Il retourne un String texte formaté pour Discord.
     */
    interface CasterAgent {
        @SystemMessage(PromptRegistry.PERFORMANCE_CASTER_SYSTEM)
        String writeDiscordCommentary(@UserMessage String analystReport);
    }

    /**
     * NŒUD 5 : Le Chroniqueur Quotidien.
     * Il retourne une phrase courte pour résumer la journée d'un joueur.
     */
    interface DailyChroniclerAgent {
        @SystemMessage(PromptRegistry.DAILY_CHRONICLER_SYSTEM)
        String summarizeDay(@UserMessage String playerStatsContext);
    }

    /**
     * NŒUD 6 : Le Chroniqueur MVP Périodique.
     * Il rédige un éloge pour le MVP de la semaine ou du mois.
     */
    interface PeriodMvpChroniclerAgent {
        @SystemMessage(PromptRegistry.PERIOD_MVP_CHRONICLER_SYSTEM)
        String writeMvpEulogy(@UserMessage String playerStatsContext);
    }

    /**
     * Agent Générique pour les anciennes commandes.
     */
    interface LolAgent {
        String chat(String userMessage);
    }

    // ========================================================================
    // 2. TOOLS
    // ========================================================================

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

                if (!content.isBlank() && !content.trim().startsWith("{")) {
                    sb.append(role.equalsIgnoreCase("user") ? "Utilisateur: " : "IA: ")
                            .append(content).append("\n");
                }
            }
            return sb.toString();
        }
    }

    // ========================================================================
    // 3. CONFIGURATION DU SERVICE
    // ========================================================================

    private final ChatModel defaultModel;
    private final String apiKey;
    private final List<Object> defaultTools;
    private final TavilyService tavilyService;

    private static final ThreadLocal<StringBuilder> agentTraceLog = ThreadLocal.withInitial(StringBuilder::new);

    public MistralService(RiotService riotService, TavilyService tavilyService) {
        Dotenv dotenv = Dotenv.load();
        this.apiKey = dotenv.get("MISTRAL_API_KEY");

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("MISTRAL_API_KEY is missing!");
        }

        // Modèle par défaut (Température 0.7 pour usage général)
        this.defaultModel = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("mistral-large-latest")
                .temperature(0.7)
                .timeout(Duration.ofMinutes(5))
                .logRequests(true)
                .logResponses(true)
                .build();

        this.tavilyService = tavilyService;
        this.defaultTools = List.of(riotService, tavilyService);
    }

    // ========================================================================
    // 4. PIPELINE MULTI-AGENTS (NOUVELLE ARCHITECTURE)
    // ========================================================================

    /**
     * Étape 1 du Pipeline : Le Cerveau Analytique
     */
    public MatchAnalysisResult runPerformanceAnalyst(String enrichedMatchJson) {
        logAgentTrace(PromptRegistry.PERFORMANCE_ANALYST_SYSTEM, "Input Data:\n" + enrichedMatchJson);

        // Modèle spécifique très froid (0.3) pour la rigueur mathématique et le JSON
        ChatModel analystModel = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("mistral-large-latest")
                .temperature(0.3)
                .timeout(Duration.ofMinutes(5))
                .build();

        AnalystAgent agent = AiServices.builder(AnalystAgent.class)
                .chatModel(analystModel)
                .tools(tavilyService)
                .build();

        return agent.adjustScores(enrichedMatchJson);
    }

    /**
     * Étape 2 du Pipeline : La Plume Discord
     */
    public String runPerformanceCaster(MatchAnalysisResult analystResult) {
        String analystReportStr = analystResult.toString();
        logAgentTrace(PromptRegistry.PERFORMANCE_CASTER_SYSTEM, "Analyst Data:\n" + analystReportStr);

        // Modèle spécifique créatif (0.8) pour l'humour et le style
        ChatModel casterModel = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("mistral-large-latest")
                .temperature(0.8)
                .timeout(Duration.ofMinutes(5))
                .build();

        CasterAgent agent = AiServices.builder(CasterAgent.class)
                .chatModel(casterModel)
                .build();

        String prompt = "Voici les ajustements techniques de l'analyste. Rédige les commentaires pour chaque joueur et renvoie un JSON final formaté comme une liste d'objets contenant les champs 'name', 'champion', 'role', 'team', 'score' (qui est la note ajustée) et 'comment'. Voici les données:\n" + analystReportStr;
        return agent.writeDiscordCommentary(prompt);
    }

    public String runDailyChronicler(String playerStatsContext) {
        // Modèle créatif (0.8) pour le ton sarcastique/hype
        ChatModel chroniclerModel = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("mistral-large-latest")
                .temperature(0.8)
                .timeout(Duration.ofMinutes(2))
                .build();

        DailyChroniclerAgent agent = AiServices.builder(DailyChroniclerAgent.class)
                .chatModel(chroniclerModel)
                .build();

        return agent.summarizeDay(playerStatsContext);
    }

    public String runPeriodMvpChronicler(String playerStatsContext) {
        // Modèle créatif (0.9) pour l'éloge épique
        ChatModel chroniclerModel = MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("mistral-large-latest")
                .temperature(0.9)
                .timeout(Duration.ofMinutes(2))
                .build();

        PeriodMvpChroniclerAgent agent = AiServices.builder(PeriodMvpChroniclerAgent.class)
                .chatModel(chroniclerModel)
                .build();

        return agent.writeMvpEulogy(playerStatsContext);
    }

    // ========================================================================
    // 5. ANCIENNES MÉTHODES (Conservées pour rétrocompatibilité)
    // ========================================================================

    private LolAgent createGenericAgent(ChatMemory chatMemory, List<Object> tools) {
        return AiServices.builder(LolAgent.class)
                .chatModel(defaultModel)
                .chatMemory(chatMemory)
                .tools(tools)
                .build();
    }

    public String analyzeGame(String userQuestion, String gameStatsContext) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);
        String systemPrompt = PromptRegistry.GAME_ANALYSIS_SYSTEM + gameStatsContext;
        memory.add(new dev.langchain4j.data.message.SystemMessage(systemPrompt));

        logAgentTrace(systemPrompt, userQuestion);
        LolAgent agent = createGenericAgent(memory, this.defaultTools);

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

    public String performTask(String userMessage, String fullSystemContext) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(30);
        memory.add(new dev.langchain4j.data.message.SystemMessage(fullSystemContext));

        logAgentTrace(fullSystemContext, userMessage);
        LolAgent agent = createGenericAgent(memory, this.defaultTools);

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
        chatMemory.add(new dev.langchain4j.data.message.SystemMessage(systemContext));

        List<Object> toolsWithHistory = new ArrayList<>(this.defaultTools);
        toolsWithHistory.add(new HistoryTool(previousHistory));

        StringBuilder fullTraceContext = new StringBuilder(systemContext);
        if (previousHistory != null && !previousHistory.isEmpty()) {
            try {
                fullTraceContext.append("\n\n[HISTORY (Available via Tool)]\n").append(previousHistory.toString(2));
            } catch (Exception e) {
                fullTraceContext.append("\n\n[HISTORY (Error formatting JSON)]\n").append(previousHistory.toString());
            }
        }
        logAgentTrace(fullTraceContext.toString(), originalUserQuestion);

        LolAgent agent = createGenericAgent(chatMemory, toolsWithHistory);

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

    // ========================================================================
    // 6. LOGGING & TRACES
    // ========================================================================

    private void logAgentTrace(String systemContext, String userQuestion) {
        StringBuilder sb = agentTraceLog.get();
        sb.append("=== AGENT TRACE (INITIAL CONTEXT) ===\n");
        sb.append("SYSTEM CONTEXT:\n").append(systemContext).append("\n");
        sb.append("-----------------------------------------\n");
        sb.append("USER QUESTION / DATA: ").append(userQuestion).append("\n");
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

    public void flushTraces() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String agentTrace = agentTraceLog.get().toString();
        agentTraceLog.remove();

        String tavilyTrace = tavilyService.getAndResetTrace();

        synchronized (MistralService.class) {
            if (!agentTrace.isEmpty()) {
                try (FileWriter fw = new FileWriter("trace.txt", false);
                     PrintWriter pw = new PrintWriter(fw)) {
                    pw.println("TIMESTAMP: " + timestamp);
                    pw.println(agentTrace);
                } catch (IOException e) {
                    System.err.println("Failed to write to trace.txt: " + e.getMessage());
                }
            } else {
                try (FileWriter fw = new FileWriter("trace.txt", false)) {
                } catch (IOException e) {
                    System.err.println("Failed to clear trace.txt: " + e.getMessage());
                }
            }

            if (!tavilyTrace.isEmpty()) {
                try (FileWriter fw = new FileWriter("trace_tavily.txt", false);
                     PrintWriter pw = new PrintWriter(fw)) {
                    pw.println("TIMESTAMP: " + timestamp);
                    pw.println(tavilyTrace);
                } catch (IOException e) {
                    System.err.println("Failed to write to trace_tavily.txt: " + e.getMessage());
                }
            } else {
                try (FileWriter fw = new FileWriter("trace_tavily.txt", false)) {
                } catch (IOException e) {
                    System.err.println("Failed to clear trace_tavily.txt: " + e.getMessage());
                }
            }
        }
    }
}