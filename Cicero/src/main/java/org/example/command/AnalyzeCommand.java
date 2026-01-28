package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;

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
                String matchId = ctx.riotService().getLastMatchId(user.puuid, user.region);
                if (matchId == null) {
                    event.getHook().sendMessage("Aucune game récente trouvée.").queue();
                    return;
                }

                // Utilisation de la nouvelle méthode d'analyse lourde
                String gameDetails = ctx.riotService().getMatchAnalysis(matchId, user.puuid, user.region);
                String aiResponse = ctx.geminiService().analyzeGame(userQuestion, gameDetails);

                sendLongMessage(event, "**Analyse détaillée de la dernière game :**\n" + aiResponse);

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Erreur technique lors de l'analyse : " + e.getMessage()).queue();
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