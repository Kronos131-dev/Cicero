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

        embed.addField("🔗 `/link [riot_id]`", 
                "Lie ton compte Riot à ton compte Discord.\n*Exemple : /link Brutus#FDC*", false);

        embed.addField("📊 `/rank [membre]`", 
                "Affiche le rang, les LP et le winrate d'un membre.\n*Exemple : /rank @Brutus*", false);

        embed.addField("🏆 `/leaderboard`", 
                "Affiche le classement de tous les membres enregistrés sur le serveur.", false);

        embed.addField("🧠 `/ask [question]`", 
                "Pose une question à l'IA. Tu peux mentionner un joueur pour que l'IA analyse son profil.\n*Exemple : /ask Comment @Brutus peut améliorer son farm ?*", false);

        embed.addField("🔎 `/analyze [question]`", 
                "L'IA analyse ta toute dernière partie jouée et répond à ta question spécifique.\n*Exemple : /analyze Pourquoi j'ai fait si peu de dégâts ?*", false);
        
        embed.addField("📈 `/performance [joueur]`", 
                "Affiche les notes et performances des 10 joueurs de la dernière game.\n*Exemple : /performance @Brutus*", false);

        embed.addField("🛠️ `/trace` & `/trace-tavily`",
                "Télécharge les fichiers de logs (debug) de la dernière interaction IA.", false);

        embed.setFooter("Bot développé par Kronos pour les invocateurs.");
        
        event.replyEmbeds(embed.build()).queue();
    }
}