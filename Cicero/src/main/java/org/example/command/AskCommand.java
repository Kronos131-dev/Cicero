package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.service.AiContextService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class AskCommand implements SlashCommand {

    private static final List<String> DETAILED_KEYWORDS = Arrays.asList("détaillé", "détaillée", "détaillés", "complet", "complète", "complets", "profond", "profonde", "profonds", "précisément");

    @Override
    public CommandData getCommandData() {
        return Commands.slash("ask", "Pose une question à l'IA sur LoL, l'esport ou un joueur")
                .addOption(OptionType.STRING, "question", "Ta question (ex: Que penses-tu de @Yvain ?)", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();
        String question = event.getOption("question").getAsString();
        String discordId = event.getUser().getId();

        ctx.executor().submit(() -> {
            try {
                // 1. Construction du contexte intelligent via le service dédié
                AiContextService.ContextPayload payload = ctx.aiContextService().buildContext(event, question);
                String fullContext = payload.context();

                // 2. Gestion de la concision (Instruction pour l'IA)
                String lowerQ = question.toLowerCase();
                boolean wantsDetailedResponse = DETAILED_KEYWORDS.stream().anyMatch(lowerQ::contains);
                if (!wantsDetailedResponse) {
                    fullContext += "\nIMPORTANT: Ta réponse doit être concise et tenir en moins de 2000 caractères.\n";
                }

                // 3. Interaction avec l'IA
                JSONArray history = ctx.db().getChatHistory(discordId);
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", question);
                history.put(userMsg);

                // On passe l'intent et la question originale pour optimiser la recherche Tavily
                String aiResponse = ctx.geminiService().chatWithHistory(history, fullContext, payload.intent(), question);

                JSONObject aiMsg = new JSONObject().put("role", "assistant").put("content", aiResponse);
                history.put(aiMsg);
                ctx.db().updateChatHistory(discordId, history);

                // 4. Envoi de la réponse
                sendLongMessage(event, aiResponse);

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Oups, une erreur est survenue : " + e.getMessage()).queue();
            }
        });
    }

    private void sendLongMessage(SlashCommandInteractionEvent event, String content) {
        if (content.length() > 1900) {
            for (int i = 0; i < content.length(); i += 1900) {
                event.getHook().sendMessage(content.substring(i, Math.min(i + 1900, content.length()))).queue();
            }
        } else {
            event.getHook().sendMessage(content).queue();
        }
    }
}