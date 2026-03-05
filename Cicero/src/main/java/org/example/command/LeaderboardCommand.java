package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.FileUpload;
import org.example.DatabaseManager;
import org.example.service.RiotService;
import org.example.util.RankUtils;

import java.awt.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class LeaderboardCommand implements SlashCommand {

    private static final String LOL_LOGO_URL = "https://static.wikia.nocookie.net/leagueoflegends/images/1/12/League_of_Legends_Icon.png";
    private static final String LEADERBOARD_BANNER = "https://images.contentstack.io/v3/assets/blt731acb42bb3d1659/bltacc406a1643cf5cd/5e98753f18a3221d65d69303/2020_Worlds_Trophy_Header.jpg";

    @Override
    public CommandData getCommandData() {
        return Commands.slash("leaderboard", "Affiche le classement des membres du serveur");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();
        
        Map<String, List<DatabaseManager.UserRecord>> usersMap = ctx.db().getAllUsersGrouped();
        if (usersMap.isEmpty()) {
            event.getHook().sendMessage("Aucun utilisateur enregistré.").queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 CLASSEMENT DU SERVEUR");
        embed.setColor(Color.ORANGE);
        embed.setDescription("Qui sera le roi de la faille ?");
        embed.setThumbnail(LOL_LOGO_URL);

        // Chargement de la bannière depuis les ressources
        InputStream bannerStream = getClass().getClassLoader().getResourceAsStream("banniere.jpg");
        if (bannerStream != null) {
            embed.setImage("attachment://banniere.jpg");
        } else {
            embed.setImage(LEADERBOARD_BANNER);
        }

        ctx.executor().submit(() -> {
            List<PlayerRank> rankedPlayers = new ArrayList<>();
            
            // On parcourt chaque utilisateur Discord
            for (Map.Entry<String, List<DatabaseManager.UserRecord>> entry : usersMap.entrySet()) {
                PlayerRank bestAccountForUser = null;
                int highestElo = -1;
                
                // On cherche son compte avec le plus haut élo
                for (DatabaseManager.UserRecord userAcc : entry.getValue()) {
                    try {
                        RiotService.RankInfo rank = ctx.riotService().getRank(userAcc.puuid, userAcc.region);
                        if (rank == null) continue;
                        
                        int score = RankUtils.calculateEloScore(rank.tier, rank.rank, rank.lp);
                        if (score > highestElo) {
                            highestElo = score;
                            bestAccountForUser = new PlayerRank(userAcc.summonerName, rank, score);
                        }
                    } catch (Exception ignored) {}
                }
                if (bestAccountForUser != null) {
                    rankedPlayers.add(bestAccountForUser);
                }
            }
            rankedPlayers.sort(Comparator.comparingInt(PlayerRank::getScore).reversed());

            StringBuilder sb = new StringBuilder();
            int rankPos = 1;
            for (PlayerRank p : rankedPlayers) {
                if (rankPos > 10) break;
                String medal = (rankPos == 1) ? "🥇" : (rankPos == 2) ? "🥈" : (rankPos == 3) ? "🥉" : "**" + rankPos + ".**";
                String emoji = RankUtils.getRankEmoji(p.rankInfo.tier);
                int totalGames = p.rankInfo.wins + p.rankInfo.losses;
                int winrate = (totalGames > 0) ? (p.rankInfo.wins * 100 / totalGames) : 0;

                sb.append(medal).append(" ").append(emoji).append(" **").append(p.summonerName).append("**\n");
                if ("UNRANKED".equals(p.rankInfo.tier)) {
                    sb.append("   *Unranked*\n");
                } else {
                    sb.append("   ").append(p.rankInfo.tier).append(" ").append(p.rankInfo.rank)
                            .append(" • **").append(p.rankInfo.lp).append(" LP**\n");
                    sb.append("   ").append(getWinrateProgressBar(winrate)).append(" ").append(winrate).append("% WR\n");
                }
                sb.append("\n");
                rankPos++;
            }
            embed.setDescription(sb.toString());
            embed.setFooter("Mis à jour à l'instant • Top 10");

            if (bannerStream != null) {
                event.getHook().sendMessageEmbeds(embed.build())
                        .addFiles(FileUpload.fromData(bannerStream, "banniere.jpg")).queue();
            } else {
                event.getHook().sendMessageEmbeds(embed.build()).queue();
            }
        });
    }

    private static class PlayerRank {
        String summonerName;
        RiotService.RankInfo rankInfo;
        int score;
        public PlayerRank(String name, RiotService.RankInfo info, int score) {
            this.summonerName = name;
            this.rankInfo = info;
            this.score = score;
        }
        public int getScore() { return score; }
    }

    private String getWinrateProgressBar(int winrate) {
        int bars = 10;
        int filled = Math.round(winrate / 10.0f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "▰" : "▱");
        }
        return sb.toString();
    }
}