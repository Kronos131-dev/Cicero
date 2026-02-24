package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandManager extends ListenerAdapter {

    private final Map<String, SlashCommand> commands = new HashMap<>();
    private final BotContext context;

    public CommandManager(BotContext context) {
        this.context = context;
    }

    public void addCommand(SlashCommand command) {
        commands.put(command.getCommandData().getName(), command);
    }

    public List<CommandData> getCommandDataList() {
        List<CommandData> dataList = new ArrayList<>();
        for (SlashCommand cmd : commands.values()) {
            dataList.add(cmd.getCommandData());
        }
        return dataList;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String commandName = event.getName();
        SlashCommand command = commands.get(commandName);

        if (command != null) {
            try {
                command.execute(event, context);
            } catch (Exception e) {
                e.printStackTrace();
                // Si l'interaction n'a pas encore été répondue (ACK), on le fait ici pour éviter le "Délai dépassé"
                if (!event.isAcknowledged()) {
                    event.reply("❌ Une erreur critique est survenue avant le traitement de la commande : " + e.getMessage())
                         .setEphemeral(true)
                         .queue();
                }
            }
        }
    }
}