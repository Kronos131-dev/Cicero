package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class LinkCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("link", "Lier ton compte Riot (GameName#TagLine)")
                .addOption(OptionType.STRING, "riot_id", "Ton Riot ID (ex: Yvain#FDC)", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply(true).queue();

        String riotIdInput = event.getOption("riot_id").getAsString();
        String[] parts = riotIdInput.split("#");

        if (parts.length != 2) {
            event.getHook().sendMessage("Format invalide. Utilise le format : GameName#TagLine").queue();
            return;
        }

        ctx.executor().submit(() -> {
            try {
                String puuid = ctx.riotService().getPuuid(parts[0], parts[1]);
                
                if (puuid.startsWith("Error")) {
                    event.getHook().sendMessage("Erreur : Compte introuvable ou API Riot indisponible.").queue();
                    return;
                }

                // Sauvegarde du nouveau compte
                ctx.db().saveUser(event.getUser().getId(), puuid, riotIdInput);
                
                // IMPORTANT : On efface l'historique de chat pour éviter les confusions avec l'ancien compte
                ctx.db().clearChatHistory(event.getUser().getId());
                
                event.getHook().sendMessage("Compte lié avec succès : " + riotIdInput + " (Mémoire IA réinitialisée)").queue();
            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("Erreur lors de la liaison : " + e.getMessage()).queue();
            }
        });
    }
}