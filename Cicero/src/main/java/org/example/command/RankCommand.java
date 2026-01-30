package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;
import org.example.service.RiotService;
import org.example.util.RankUtils;

public class RankCommand implements SlashCommand {

    @Override
    public CommandData getCommandData() {
        return Commands.slash("rank", "Affiche le rang d'un membre")
                .addOption(OptionType.USER, "membre", "Le membre dont tu veux voir le rang", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();
        User targetUser = event.getOption("membre").getAsUser();
        DatabaseManager.UserRecord user = ctx.db().getUser(targetUser.getId());

        if (user == null) {
            event.getHook().sendMessage("❌ Ce membre n'a pas lié son compte Riot.").queue();
            return;
        }

        ctx.executor().submit(() -> {
            try {
                RiotService.RankInfo rank = ctx.riotService().getRank(user.puuid, user.region);
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("Rang de " + targetUser.getName())
                        .setThumbnail(targetUser.getAvatarUrl())
                        .setColor(RankUtils.getRankColor(rank.tier));
                
                String emoji = RankUtils.getRankEmoji(rank.tier);
                
                if ("UNRANKED".equals(rank.tier)) {
                    embed.setDescription(emoji + " **Non classé** en Solo/Duo");
                } else {
                    embed.setDescription(emoji + " **" + rank.tier + " " + rank.rank + "**");
                    embed.addField("League Points", rank.lp + " LP", true);
                    int total = rank.wins + rank.losses;
                    int winrate = (total > 0) ? (rank.wins * 100 / total) : 0;
                    embed.addField("Winrate", rank.wins + "W / " + rank.losses + "L (" + winrate + "%)", true);
                }
                event.getHook().sendMessageEmbeds(embed.build()).queue();
            } catch (Exception e) {
                event.getHook().sendMessage("❌ Erreur : " + e.getMessage()).queue();
            }
        });
    }
}