package org.example.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.JDA;
import java.util.HashMap;
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

    public void registerCommands(JDA jda) {
        jda.updateCommands()
           .addCommands(commands.values().stream().map(SlashCommand::getCommandData).toList())
           .queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        SlashCommand command = commands.get(event.getName());
        if (command != null) {
            command.execute(event, context);
        }
    }
}