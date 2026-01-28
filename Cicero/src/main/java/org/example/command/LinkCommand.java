package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class LinkCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("link", "Lier ton compte Riot")
                .addOption(OptionType.STRING, "riot_id", "Ton Riot ID (ex: Yvain#FDC)", true)
                .addOption(OptionType.STRING, "region", "Ta région (ex: EUW1, NA1, KR)", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply(true).queue();
        String riotIdInput = event.getOption("riot_id").getAsString();
        String regionInput = event.getOption("region") != null ? event.getOption("region").getAsString().toLowerCase() : "euw1";

        String[] parts = riotIdInput.split("#");
        if (parts.length != 2) {
            event.getHook().sendMessage("Format invalide. Utilise : GameName#TagLine").queue();
            return;
        }

        ctx.executor().submit(() -> {
            try {
                String puuid = ctx.riotService().getPuuid(parts[0], parts[1]);
                ctx.db().saveUser(event.getUser().getId(), puuid, riotIdInput, regionInput);
                ctx.db().clearChatHistory(event.getUser().getId());
                event.getHook().sendMessage("Compte lié avec succès : " + riotIdInput + " (" + regionInput + ")").queue();
            } catch (Exception e) {
                event.getHook().sendMessage("Erreur lors de la liaison : " + e.getMessage()).queue();
            }
        });
    }
}