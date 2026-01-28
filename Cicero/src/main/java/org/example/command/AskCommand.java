package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.json.JSONArray;
import org.json.JSONObject;

public class AskCommand implements SlashCommand {

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
                JSONArray history = ctx.db().getChatHistory(discordId);
                StringBuilder context = new StringBuilder();
                context.append("Tu es un expert absolu de League of Legends et de l'Esport. Tes rôles : Coach, Analyste, Encyclopédie du jeu.\n");

                String lowerQ = question.toLowerCase();
                
                // 1. Esport Context Injection
                if (ctx.aiContextService().isEsportQuery(lowerQ) || lowerQ.contains("stats")) {
                    ctx.aiContextService().injectEsportContext(context, question, lowerQ);
                }

                // 2. Player Context Injection (Gère maintenant intelligemment les mentions et le mode Analyse/Résumé)
                ctx.aiContextService().injectPlayerContext(event, context, question, discordId, ctx.aiContextService().isEsportQuery(lowerQ));

                // 3. AI Interaction
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", question);
                history.put(userMsg);

                String aiResponse = ctx.geminiService().chatWithHistory(history, context.toString());

                JSONObject aiMsg = new JSONObject().put("role", "assistant").put("content", aiResponse);
                history.put(aiMsg);
                ctx.db().updateChatHistory(discordId, history);

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