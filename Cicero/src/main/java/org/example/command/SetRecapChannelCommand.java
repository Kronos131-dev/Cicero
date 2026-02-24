package org.example.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class SetRecapChannelCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("setrecap", "Définit le salon pour le récapitulatif quotidien")
                .addOption(OptionType.CHANNEL, "salon", "Le salon où envoyer le récap", true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        if (event.getOption("salon") == null) {
            event.reply("Veuillez spécifier un salon.").setEphemeral(true).queue();
            return;
        }

        if (event.getOption("salon").getChannelType() != ChannelType.TEXT) {
            event.reply("Le salon doit être un salon textuel.").setEphemeral(true).queue();
            return;
        }

        TextChannel channel = event.getOption("salon").getAsChannel().asTextChannel();
        String channelId = channel.getId();

        ctx.db().saveConfig("RECAP_CHANNEL_ID", channelId);

        event.reply("✅ Le salon de récapitulatif a été défini sur " + channel.getAsMention()).queue();
    }
}