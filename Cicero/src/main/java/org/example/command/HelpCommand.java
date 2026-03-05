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
        embed.setDescription("Voici la liste des commandes disponibles pour interagir avec le bot (qui gère désormais vos smurfs !) :");
        embed.addField("🔗 `/link [riot_id]`",
                "Ajoute un compte Riot à ton profil. Tu peux utiliser cette commande plusieurs fois pour lier tous tes smurfs !\n*Exemple : /link Brutus#FDC*", false);
        embed.addField("✂️ `/unlink [riot_id]`",
                "Détache un compte Riot spécifique de ton profil.\n*Exemple : /unlink MonSmurf#EUW*", false);
        embed.addField("📊 `/profile [membre]`",
                "Affiche le rang, les LP et le winrate de **tous les comptes** d'un joueur (ou les tiens par défaut).\n*Exemple : /profile @Brutus*", false);
        embed.addField("🏆 `/leaderboard`",
                "Affiche le classement du serveur (basé uniquement sur le compte au plus haut Élo de chaque joueur).", false);

        embed.addField("📈 `/performance [joueur] [compte]`",
                "Analyse la dernière game. Précise le numéro de `compte` (1, 2, 3...) pour analyser la game de l'un de tes smurfs.\n*Exemple : /performance compte:2*", false);
        embed.addField("🧠 `/ask [question]`",
                "Pose une question à l'IA. Tu peux mentionner un joueur pour que l'IA analyse son profil.\n*Exemple : /ask Comment @Brutus peut améliorer son farm ?*", false);
        embed.addField("🔎 `/analyze [question]`",
                "L'IA analyse ta toute dernière partie jouée et répond à ta question spécifique.\n*Exemple : /analyze Pourquoi j'ai fait si peu de dégâts ?*", false);
        embed.addField("🛠️ `/trace` & `/trace-tavily`",
                "Télécharge les fichiers de logs (debug) de la dernière interaction IA.", false);
        embed.setFooter("Bot développé par Kronos pour les invocateurs.");

        event.replyEmbeds(embed.build()).queue();
    }
}