package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;
import org.example.service.AiContextService;
import org.example.service.ai.PromptRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class AskCommand implements SlashCommand {

    private static final List<String> DETAILED_KEYWORDS = Arrays.asList(
            "détaillé", "détaillée", "détaillés", "complet", "complète",
            "complets", "profond", "profonde", "profonds", "précisément"
    );

    @Override
    public CommandData getCommandData() {
        return Commands.slash("ask", "Pose une question à l'IA sur LoL, l'esport ou un joueur")
                .addOption(OptionType.STRING, "question", "Ta question (ex: Que penses-tu de @Yvain ?)", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        // On diffère la réponse pour laisser le temps à Mistral (v1.10.0) de réfléchir
        event.deferReply().queue();

        String question = event.getOption("question").getAsString();
        String discordId = event.getUser().getId();

        ctx.executor().submit(() -> {
            try {
                // 1. Construction du contexte via AiContextService
                AiContextService.ContextPayload payload = ctx.aiContextService().buildContext(event, question);
                StringBuilder fullContext = new StringBuilder(payload.context());

                // --- OPTIMISATION : Injection proactive des données si l'utilisateur parle de "ma dernière game" ---
                // Si l'utilisateur est lié et demande une analyse de SA game, on injecte les données directement
                // pour éviter que l'agent n'ait à appeler les outils (ce qui semble échouer parfois).
                if (isAskingAboutLastGame(question)) {
                    // Cas 1 : L'utilisateur parle de SA propre game ("ma game")
                    DatabaseManager.UserRecord user = ctx.db().getUser(discordId);
                    if (user != null) {
                        injectMatchData(ctx, user.puuid, user.region, question, fullContext);
                    }
                } else if (!payload.targets().isEmpty() && isAskingAboutTargetGame(question)) {
                    // Cas 2 : L'utilisateur parle de la game d'un AUTRE joueur mentionné ("la game de @Yvain")
                    // On prend la première cible détectée par AiContextService
                    AiContextService.Target target = payload.targets().get(0);
                    injectMatchData(ctx, target.puuid(), target.region(), question, fullContext);
                }
                // ------------------------------------------------------------------------------------------------

                // 2. Gestion de la concision (Optimisation du prompt)
                if (!isDetailedRequest(question)) {
                    // Instruction stricte pour la concision
                    fullContext.append("\n[CONTRAINTE DE LONGUEUR]\n");
                    fullContext.append("L'utilisateur n'a PAS demandé de détails. Tu DOIS être CONCIS.\n");
                    fullContext.append("Ta réponse doit tenir impérativement dans UN SEUL message Discord (< 1900 caractères).\n");
                    fullContext.append("Va droit au but. Résume l'essentiel. Pas de blabla inutile.\n");
                }

                // 3. Récupération et mise à jour de l'historique local (JSON)
                JSONArray history = ctx.db().getChatHistory(discordId);

                // 4. Appel au service Mistral (Signature v1.10.0 : 3 paramètres)
                String aiResponse = ctx.mistralService().chatWithHistory(
                        history,
                        fullContext.toString(),
                        question
                );

                // 5. Sauvegarde de l'échange dans la DB
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", question);
                JSONObject aiMsg = new JSONObject().put("role", "assistant").put("content", aiResponse);
                history.put(userMsg).put(aiMsg);
                ctx.db().updateChatHistory(discordId, history);

                // 6. Envoi de la réponse (découpage intelligent)
                sendLongMessage(event, aiResponse);

            } catch (Exception e) {
                e.printStackTrace();
                String errorMsg = "❌ Erreur : " + e.getMessage();
                if (e.getCause() instanceof TimeoutException || e.getMessage().contains("timeout")) {
                    errorMsg = "⏳ Le délai de réflexion de l'IA (Mistral 1.10.0) a été dépassé.";
                }
                event.getHook().sendMessage(errorMsg).queue();
            } finally {
                // 7. Écriture des traces (Agent + Tavily) après l'envoi de la réponse (ou en cas d'erreur)
                ctx.mistralService().flushTraces();
            }
        });
    }

    private void injectMatchData(BotContext ctx, String puuid, String region, String question, StringBuilder fullContext) {
        String lastMatchId = ctx.riotService().getLastMatchId(puuid, region);
        if (!lastMatchId.startsWith("Error") && !lastMatchId.equals("None")) {
            String matchJson;
            if (isDetailedRequest(question)) {
                matchJson = ctx.riotService().getMatchAnalysis(lastMatchId, puuid, region);
                if (!fullContext.toString().contains("ANALYSE LOURDE")) {
                    fullContext.append(PromptRegistry.ANALYZE_COMMAND_CONTEXT);
                }
            } else {
                matchJson = ctx.riotService().getMatchHistorySummary(puuid, region, null, 1);
            }
            
            fullContext.append("\n[DONNÉES DU DERNIER MATCH (AUTO-INJECTÉES)]\n");
            fullContext.append(matchJson).append("\n");
            fullContext.append("Note: Ces données ont été récupérées automatiquement pour le joueur cible. Utilise-les pour répondre.\n");
        }
    }

    private boolean isDetailedRequest(String question) {
        String lowerQ = question.toLowerCase();
        return DETAILED_KEYWORDS.stream().anyMatch(lowerQ::contains);
    }

    private boolean isAskingAboutLastGame(String question) {
        String lowerQ = question.toLowerCase();
        return lowerQ.contains("ma dernière game") || 
               lowerQ.contains("mon dernier match") || 
               lowerQ.contains("ma game") ||
               lowerQ.contains("pourquoi j'ai perdu") ||
               lowerQ.contains("pourquoi j'ai gagné") ||
               lowerQ.contains("analyse ma game");
    }

    private boolean isAskingAboutTargetGame(String question) {
        String lowerQ = question.toLowerCase();
        return lowerQ.contains("sa dernière game") || 
               lowerQ.contains("son dernier match") || 
               lowerQ.contains("sa game") ||
               lowerQ.contains("pourquoi il a perdu") ||
               lowerQ.contains("pourquoi il a gagné") ||
               lowerQ.contains("analyse sa game") ||
               lowerQ.contains("la game de");
    }

    /**
     * Gère l'envoi de messages longs pour respecter la limite Discord de 2000 caractères.
     * Coupe intelligemment aux sauts de ligne pour préserver la lisibilité.
     */
    private void sendLongMessage(SlashCommandInteractionEvent event, String content) {
        if (content == null || content.isEmpty()) return;

        if (content.length() <= 1950) {
            event.getHook().sendMessage(content).queue();
            return;
        }

        int index = 0;
        while (index < content.length()) {
            int end = Math.min(index + 1950, content.length());
            
            // Si on n'est pas à la fin, on cherche le dernier saut de ligne avant la limite
            if (end < content.length()) {
                int lastNewline = content.lastIndexOf('\n', end);
                if (lastNewline > index) {
                    end = lastNewline;
                } else {
                    // Pas de saut de ligne trouvé, on cherche un espace
                    int lastSpace = content.lastIndexOf(' ', end);
                    if (lastSpace > index) {
                        end = lastSpace;
                    }
                }
            }

            String chunk = content.substring(index, end).trim();
            if (!chunk.isEmpty()) {
                event.getHook().sendMessage(chunk).queue();
            }
            index = end;
        }
    }
}