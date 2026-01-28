package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class NewAskCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("new-ask", "Démarre une nouvelle conversation avec l'IA (efface la mémoire)");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        ctx.db().clearChatHistory(event.getUser().getId());
        event.reply("🧠 Mémoire effacée ! On repart sur de nouvelles bases.").setEphemeral(true).queue();
    }
}