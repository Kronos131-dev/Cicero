package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;
import org.example.service.RiotService;

import java.awt.*;

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
                EmbedBuilder embed = new EmbedBuilder().setTitle("Rang de " + targetUser.getName()).setThumbnail(targetUser.getAvatarUrl()).setColor(getColorForTier(rank.tier));
                String emoji = getRankEmoji(rank.tier);
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

    private Color getColorForTier(String tier) {
        switch (tier.toUpperCase()) {
            case "IRON": return new Color(87, 77, 79);
            case "BRONZE": return new Color(140, 81, 58);
            case "SILVER": return new Color(128, 152, 157);
            case "GOLD": return new Color(205, 136, 55);
            case "PLATINUM": return new Color(78, 161, 177);
            case "EMERALD": return new Color(42, 168, 115);
            case "DIAMOND": return new Color(87, 107, 236);
            case "MASTER": return new Color(157, 72, 224);
            case "GRANDMASTER": return new Color(239, 79, 79);
            case "CHALLENGER": return new Color(244, 194, 68);
            default: return Color.GRAY;
        }
    }

    private String getRankEmoji(String tier) {
        switch (tier.toUpperCase()) {
            case "IRON": return "<:iron:1465729151121096858>";
            case "BRONZE": return "<:bronze:1465729193626046659>";
            case "SILVER": return "<:silver:1465729273116627110>";
            case "GOLD": return "<:gold:1465729325063213161>";
            case "PLATINUM": return "<:platinum:1465729466230771804>";
            case "EMERALD": return "<:emerald:1465729555531829443>";
            case "DIAMOND": return "<:diamond:1465729632706760715>";
            case "MASTER": return "<:master:1465729682505859133>";
            case "GRANDMASTER": return "<:grandmaster:1465729725187096717>";
            case "CHALLENGER": return "<:challenger:1465729776684765385>";
            default: return "❓";
        }
    }
}