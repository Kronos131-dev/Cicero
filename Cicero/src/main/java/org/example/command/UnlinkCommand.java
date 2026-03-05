package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class UnlinkCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("unlink", "Détacher un de vos comptes Riot (ex: Yvain#FDC)")
                .addOption(OptionType.STRING, "riot_id", "Le Riot ID exact à supprimer", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        String riotId = event.getOption("riot_id").getAsString();
        // Note: deleteUserAccount signature in DatabaseManager was:
        // public synchronized void deleteUserAccount(String discordId, String riotIdInput)
        // It uses LIKE riotIdInput + "%"
        ctx.db().deleteUserAccount(event.getUser().getId(), riotId);
        event.reply("Si le compte " + riotId + " t'appartenait, il a été détaché de ton profil.").setEphemeral(true).queue();
    }
}