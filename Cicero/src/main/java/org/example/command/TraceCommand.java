package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.File;

public class TraceCommand implements SlashCommand {
    @Override
    public CommandData getCommandData() {
        return Commands.slash("trace", "Télécharge le dernier fichier de trace de l'Agent (Debug)");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        File file = new File("trace.txt");
        if (!file.exists() || file.length() == 0) {
            event.reply("❌ Aucun fichier de trace disponible (ou fichier vide).").setEphemeral(true).queue();
            return;
        }
        event.replyFiles(FileUpload.fromData(file)).setEphemeral(true).queue();
    }
}