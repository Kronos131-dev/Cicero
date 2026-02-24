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

public class AnalyzeCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("analyze", "L'IA analyse ta dernière game en détail")
                .addOption(OptionType.STRING, "question", "Ta question (ex: pourquoi j'ai perdu ?)", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();
        String discordId = event.getUser().getId();
        DatabaseManager.UserRecord user = ctx.db().getUser(discordId);

        if (user == null) {
            event.getHook().sendMessage("Tu n'as pas lié ton compte Riot ! Utilise la commande `/link` d'abord.").queue();
            return;
        }

        String userQuestion = event.getOption("question").getAsString();

        ctx.executor().submit(() -> {
            try {
                // 1. Récupération proactive des données du match (comme dans PerformanceCommand)
                // Cela garantit que l'IA a les données sans avoir à deviner qu'elle doit appeler l'outil
                String lastMatchId = ctx.riotService().getLastMatchId(user.puuid, user.region);
                String matchJson = "Données indisponibles";
                
                if (!lastMatchId.startsWith("Error") && !lastMatchId.equals("None")) {
                    matchJson = ctx.riotService().getMatchAnalysis(lastMatchId, user.puuid, user.region);
                }

                // 2. Construction du contexte via AiContextService
                AiContextService.ContextPayload payload = ctx.aiContextService().buildContext(event, userQuestion, true);
                StringBuilder fullContext = new StringBuilder(payload.context());
                
                // Injection explicite des données du match dans le contexte système
                fullContext.append("\n[DONNÉES DU MATCH (JSON)]\n");
                fullContext.append(matchJson).append("\n");
                
                // On ajoute le contexte spécifique à l'analyse SI il n'est pas déjà présent
                // (Normalement AiContextService ne l'ajoute plus, donc on l'ajoute ici)
                fullContext.append(PromptRegistry.ANALYZE_COMMAND_CONTEXT);

                // 3. Récupération de l'historique
                JSONArray history = ctx.db().getChatHistory(discordId);

                // 4. Appel au service Mistral
                String aiResponse = ctx.mistralService().chatWithHistory(
                        history,
                        fullContext.toString(),
                        userQuestion
                );

                // 5. Sauvegarde de l'échange
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", userQuestion);
                JSONObject aiMsg = new JSONObject().put("role", "assistant").put("content", aiResponse);
                history.put(userMsg).put(aiMsg);
                ctx.db().updateChatHistory(discordId, history);

                sendLongMessage(event, aiResponse);

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Erreur technique lors de l'analyse : " + e.getMessage()).queue();
            } finally {
                // 6. Écriture des traces (Agent + Tavily) après l'envoi de la réponse
                ctx.mistralService().flushTraces();
            }
        });
    }

    private void sendLongMessage(SlashCommandInteractionEvent event, String content) {
        if (content == null || content.isEmpty()) return;

        if (content.length() <= 1950) {
            event.getHook().sendMessage(content).queue();
            return;
        }

        int index = 0;
        while (index < content.length()) {
            int end = Math.min(index + 1950, content.length());
            
            if (end < content.length()) {
                int lastNewline = content.lastIndexOf('\n', end);
                if (lastNewline > index) {
                    end = lastNewline;
                } else {
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