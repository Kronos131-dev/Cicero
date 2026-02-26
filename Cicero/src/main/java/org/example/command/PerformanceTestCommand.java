package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.FileUpload;
import org.example.DatabaseManager;
import org.example.service.MatchDataExtractor;
import org.example.service.RiotService;
import org.example.service.ScoreCalculator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PerformanceTestCommand implements SlashCommand {

    private static final Map<String, Integer> ROLE_ORDER = Map.of(
            "TOP", 1, "JUNGLE", 2, "MIDDLE", 3, "BOTTOM", 4, "UTILITY", 5
    );

    @Override
    public CommandData getCommandData() {
        return Commands.slash("performance-test", "Calibrage : Analyse X games (Embed Mathématicien uniquement).")
                .addOption(OptionType.USER, "joueur", "Le joueur à tester", true)
                .addOption(OptionType.INTEGER, "nombre", "Nombre de games à analyser", true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();

        User targetUser = event.getOption("joueur").getAsUser();
        int matchCount = event.getOption("nombre").getAsInt();

        DatabaseManager.UserRecord user = ctx.db().getUser(targetUser.getId());
        if (user == null) {
            event.getHook().sendMessage("❌ Joueur non lié.").queue();
            return;
        }

        ctx.executor().submit(() -> {
            try {
                // On récupère la liste des matchs
                List<String> matchIds = ctx.riotService().getMatchIds(user.puuid, user.region, matchCount);
                StringBuilder globalAudit = new StringBuilder();
                globalAudit.append("=== AUDIT CALIBRAGE MATHÉMATICIEN ===\n\n");

                for (String matchId : matchIds) {
                    // 1. Données brutes et contexte causal (exactement comme PerformanceCommand)
                    String matchJsonStr = ctx.riotService().getMatchAnalysis(matchId, user.puuid, user.region);
                    JSONObject fullMatchData = new JSONObject(matchJsonStr);
                    Map<String, MatchDataExtractor.PlayerContext> globalContext = ctx.riotService().getMatchContext(matchId, user.region);

                    RiotService.RankInfo rankInfo = ctx.riotService().getRank(user.puuid, user.region);
                    String gameTier = (rankInfo != null && rankInfo.tier != null) ? rankInfo.tier : "GOLD";
                    JSONObject benchmarks = ctx.benchmarkService().getBenchmarks();
                    double durationMin = fullMatchData.getJSONObject("metadata").optLong("duration_sec", 1800) / 60.0;

                    JSONArray playersToAnalyze = new JSONArray();
                    playersToAnalyze.put(fullMatchData.getJSONObject("target_player"));
                    fullMatchData.getJSONArray("allies").forEach(p -> playersToAnalyze.put(p));
                    fullMatchData.getJSONArray("enemies").forEach(p -> playersToAnalyze.put(p));

                    Map<String, JSONObject> javaPlayerMap = new HashMap<>();
                    globalAudit.append("MATCH ID: ").append(matchId).append("\n");

                    // 2. Boucle du Mathématicien (Copie conforme de PerformanceCommand)
                    for (int i = 0; i < playersToAnalyze.length(); i++) {
                        JSONObject p = playersToAnalyze.getJSONObject(i);
                        String champName = p.getString("champion").toUpperCase();
                        MatchDataExtractor.PlayerContext pCtx = globalContext.get(champName);

                        MatchDataExtractor.PlayerContext oppCtx = null;
                        if (pCtx != null) {
                            for (MatchDataExtractor.PlayerContext other : globalContext.values()) {
                                if (other.teamId != pCtx.teamId && other.role.equals(pCtx.role)) {
                                    oppCtx = other; break;
                                }
                            }
                        }

                        JSONObject mathResult = ScoreCalculator.analyzePlayer(p, benchmarks, gameTier, durationMin, pCtx, oppCtx);
                        p.put("ai_context", mathResult);
                        p.put("score", mathResult.getInt("math_score"));
                        p.put("comment", "Note Mathématique Pure");
                        javaPlayerMap.put(champName, p);

                        // On log le détail dans le fichier TXT
                        globalAudit.append("  [").append(champName).append("] Score: ").append(p.getInt("score")).append("\n");
                        JSONArray breakdown = mathResult.getJSONArray("score_breakdown");
                        for(int j=0; j<breakdown.length(); j++) globalAudit.append("    > ").append(breakdown.getString(j)).append("\n");
                    }
                    globalAudit.append("\n");

                    // 3. Envoi de l'Embed (Version Transition de PerformanceCommand)
                    EmbedBuilder embed = buildTransitionEmbed(javaPlayerMap, targetUser, matchId, gameTier);
                    event.getHook().sendMessageEmbeds(embed.build()).queue();
                }

                // 4. Envoi du fichier de synthèse final
                byte[] fileData = globalAudit.toString().getBytes(StandardCharsets.UTF_8);
                event.getHook().sendMessage("✅ Test terminé. Voici l'audit détaillé des calculs :")
                        .addFiles(FileUpload.fromData(fileData, "audit_calibrage.txt"))
                        .queue();

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Erreur : " + e.getMessage()).queue();
            }
        });
    }

    // Le même constructeur d'Embed que dans PerformanceCommand (avant passage de l'IA)
    private EmbedBuilder buildTransitionEmbed(Map<String, JSONObject> javaPlayerMap, User targetUser, String matchId, String gameTier) {
        List<JSONObject> blueTeam = new ArrayList<>();
        List<JSONObject> redTeam = new ArrayList<>();

        for (JSONObject p : javaPlayerMap.values()) {
            String role = p.optString("role", "TOP").toUpperCase();
            if (role.equals("MID")) role = "MIDDLE";
            if (role.equals("BOT") || role.equals("ADC")) role = "BOTTOM";
            if (role.equals("SUP") || role.equals("SUPPORT")) role = "UTILITY";
            p.put("role_normalized", role);

            if (p.optBoolean("win", false)) blueTeam.add(p);
            else redTeam.add(p);
        }

        Comparator<JSONObject> roleSorter = Comparator.comparingInt(o -> ROLE_ORDER.getOrDefault(o.getString("role_normalized"), 6));
        blueTeam.sort(roleSorter);
        redTeam.sort(roleSorter);

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("⏱️ Calibrage Mathématique - " + targetUser.getName());
        embed.setDescription("Match: " + matchId + " • Élo: " + gameTier + "\n*Affichage des notes brutes du ScoreCalculator...*");
        embed.setColor(Color.GRAY);

        StringBuilder sbBlue = new StringBuilder();
        for (JSONObject p : blueTeam) sbBlue.append(formatLine(p));
        StringBuilder sbRed = new StringBuilder();
        for (JSONObject p : redTeam) sbRed.append(formatLine(p));

        embed.addField("🔵 Équipe Gagnante", sbBlue.toString(), true);
        embed.addField("🔴 Équipe Perdante", sbRed.toString(), true);
        return embed;
    }

    private String formatLine(JSONObject p) {
        String name = p.optString("riotIdGameName", p.optString("summonerName", "Inconnu"));
        if (name.length() > 14) name = name.substring(0, 12) + "..";
        return String.format("**%s** (%d/100)\n", name, p.optInt("score", 0));
    }
}