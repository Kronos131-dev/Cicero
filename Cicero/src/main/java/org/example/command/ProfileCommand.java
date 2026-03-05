package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;
import org.example.service.RiotService;
import org.example.util.RankUtils;

import java.awt.Color;
import java.util.List;

public class ProfileCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("profile", "Affiche les rangs de tous les comptes d'un membre")
                .addOption(OptionType.USER, "membre", "Le membre ciblé (toi par défaut)", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();

        // 1. Détermination de la cible (Auteur par défaut si pas de paramètre)
        OptionMapping option = event.getOption("membre");
        User targetUser = (option != null) ? option.getAsUser() : event.getUser();

        // 2. Récupération de tous les comptes (Système V2)
        List<DatabaseManager.UserRecord> accounts = ctx.db().getUsers(targetUser.getId());
        if (accounts == null || accounts.isEmpty()) {
            event.getHook().sendMessage("❌ " + targetUser.getName() + " n'a lié aucun compte Riot.").queue();
            return;
        }

        ctx.executor().submit(() -> {
            try {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("Profil de " + targetUser.getName());
                embed.setThumbnail(targetUser.getAvatarUrl());

                StringBuilder sb = new StringBuilder();
                Color highestColor = Color.GRAY;
                int highestElo = -1;

                // 3. Boucle sur chaque compte
                for (int i = 0; i < accounts.size(); i++) {
                    DatabaseManager.UserRecord acc = accounts.get(i);
                    RiotService.RankInfo rank = ctx.riotService().getRank(acc.puuid, acc.region);

                    sb.append("**Compte ").append(i + 1).append("** : `").append(acc.summonerName).append("`\n");

                    if (rank == null || "UNRANKED".equals(rank.tier)) {
                        sb.append("> ❓ **Non classé** en Solo/Duo\n\n");
                    } else {
                        String emoji = RankUtils.getRankEmoji(rank.tier);
                        int total = rank.wins + rank.losses;
                        int winrate = (total > 0) ? (rank.wins * 100 / total) : 0;

                        // Affichage formaté
                        sb.append("> ").append(emoji).append(" **").append(rank.tier).append(" ").append(rank.rank);
                        sb.append(" • **").append(rank.lp).append(" LP**");
                        sb.append(" • ").append(rank.wins).append("W / ").append(rank.losses).append("L (").append(winrate).append("%)\n\n");

                        // Calcul de la couleur de l'Embed (On prend celle du compte le plus haut)
                        int currentElo = RankUtils.calculateEloScore(rank.tier, rank.rank, rank.lp);
                        if (currentElo > highestElo) {
                            highestElo = currentElo;
                            highestColor = RankUtils.getRankColor(rank.tier);
                        }
                    }
                }

                embed.setDescription(sb.toString().trim());
                embed.setColor(highestColor);

                event.getHook().sendMessageEmbeds(embed.build()).queue();
            } catch (Exception e) {
                event.getHook().sendMessage("❌ Erreur lors de la récupération des données : " + e.getMessage()).queue();
            }
        });
    }
}