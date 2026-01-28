package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.awt.*;

public class HelpCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("help", "Affiche la liste des commandes disponibles");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🤖 Aide - Commandes du Bot");
        embed.setColor(Color.CYAN);
        embed.setDescription("Voici la liste des commandes disponibles pour interagir avec le bot :");
        embed.addField("🔗 `/link [riot_id] [region]`", "Lie ton compte Riot. Région par défaut : EUW1.", false);
        embed.addField("📊 `/rank [membre]`", "Affiche le rang, les LP et le winrate d'un membre.", false);
        embed.addField("🏆 `/leaderboard`", "Affiche le classement de tous les membres enregistrés.", false);
        embed.addField("🧠 `/ask [question]`", "Pose une question à l'IA. Mentionne un joueur pour analyser son profil ou ses games.", false);
        embed.addField("🔄 `/new-ask`", "Efface la mémoire de ta conversation avec l'IA.", false);
        embed.addField("🔎 `/analyze [question]`", "L'IA analyse en détail ta dernière partie jouée.", false);
        embed.setFooter("Bot développé avec ❤️ pour les invocateurs.");
        event.replyEmbeds(embed.build()).queue();
    }
}