package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;
import org.example.service.MatchDataExtractor;
import org.example.service.MatchNarrator;
import org.example.service.RiotService;
import org.example.service.ScoreCalculator;
import org.example.service.ai.Records.AnalystAdjustment;
import org.example.service.ai.Records.MatchAnalysisResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PerformanceCommand implements SlashCommand {

    private static final Map<String, Integer> ROLE_ORDER = Map.of(
            "TOP", 1, "JUNGLE", 2, "MIDDLE", 3, "BOTTOM", 4, "UTILITY", 5
    );

    @Override
    public CommandData getCommandData() {
        return Commands.slash("performance", "Analyse les performances d'un compte.")
                .addOption(OptionType.USER, "joueur", "Le joueur ciblé (optionnel)", false)
                .addOption(OptionType.INTEGER, "compte", "Numéro du compte (1 pour le main, 2 pour le 1er smurf...)", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();

        User targetUser = event.getUser();
        OptionMapping option = event.getOption("joueur");
        if (option != null) targetUser = option.getAsUser();

        java.util.List<DatabaseManager.UserRecord> accounts = ctx.db().getUsers(targetUser.getId());
        if (accounts.isEmpty()) {
            event.getHook().sendMessage("❌ Aucun compte Riot lié.").queue();
            return;
        }
        
        DatabaseManager.UserRecord dbUser = accounts.get(0); // Par défaut, le 1er compte lié
        OptionMapping compteOpt = event.getOption("compte");
        if (compteOpt != null) {
            int index = compteOpt.getAsInt() - 1;
            if (index >= 0 && index < accounts.size()) dbUser = accounts.get(index);
            else {
                event.getHook().sendMessage("❌ Compte introuvable. Ce joueur n'a que " + accounts.size() + " compte(s).").queue();
                return;
            }
        }

        User finalTargetUser = targetUser;
        DatabaseManager.UserRecord finalDbUser = dbUser;

        ctx.executor().submit(() -> {
            try {
                String lastMatchId = ctx.riotService().getLastMatchId(finalDbUser.puuid, finalDbUser.region);
                String matchJsonStr = ctx.riotService().getMatchAnalysis(lastMatchId, finalDbUser.puuid, finalDbUser.region);
                JSONObject fullMatchData = new JSONObject(matchJsonStr);

                // CORRECTION ICI : Utilisation de FullContext
                MatchDataExtractor.FullContext fullContext = ctx.riotService().getMatchContext(lastMatchId, finalDbUser.region);
                Map<String, MatchDataExtractor.PlayerContext> globalContext = fullContext.players;
                MatchDataExtractor.TeamCompositionProfile enemyComp = fullContext.redTeamComp; // Par défaut

                RiotService.RankInfo rankInfo = ctx.riotService().getRank(finalDbUser.puuid, finalDbUser.region);
                String gameTier = (rankInfo != null && rankInfo.tier != null) ? rankInfo.tier : "GOLD";
                JSONObject benchmarks = ctx.benchmarkService().getBenchmarks();
                double durationMin = fullMatchData.getJSONObject("metadata").optLong("duration_sec", 1800) / 60.0;

                JSONArray allies = fullMatchData.getJSONArray("allies");
                JSONArray enemies = fullMatchData.getJSONArray("enemies");
                JSONObject target = fullMatchData.getJSONObject("target_player");

                JSONArray playersToAnalyze = new JSONArray();
                playersToAnalyze.put(target);
                for (int i = 0; i < allies.length(); i++) playersToAnalyze.put(allies.get(i));
                for (int i = 0; i < enemies.length(); i++) playersToAnalyze.put(enemies.get(i));

                Map<String, JSONObject> javaPlayerMap = new HashMap<>();
                for (int i = 0; i < playersToAnalyze.length(); i++) {
                    JSONObject p = playersToAnalyze.getJSONObject(i);
                    String champName = p.getString("champion").toUpperCase();
                    String role = p.optString("role", "TOP");

                    MatchDataExtractor.PlayerContext pCtx = globalContext.get(champName);
                    MatchDataExtractor.PlayerContext oppCtx = null;
                    if (pCtx != null) {
                        // Détermination de l'équipe ennemie pour l'analyse contextuelle
                        enemyComp = (pCtx.teamId == 100) ? fullContext.redTeamComp : fullContext.blueTeamComp;
                        
                        for (MatchDataExtractor.PlayerContext other : globalContext.values()) {
                            if (other.teamId != pCtx.teamId && other.role.equals(pCtx.role)) {
                                oppCtx = other; break;
                            }
                        }
                    }

                    String champClass = ScoreCalculator.getChampionClass(champName, role);
                    p.put("champion_class", champClass);

                    // Appel avec la nouvelle signature
                    JSONObject mathResult = ScoreCalculator.analyzePlayer(p, benchmarks, gameTier, durationMin, pCtx, oppCtx, enemyComp);
                    p.put("ai_context", mathResult);
                    p.put("score", mathResult.getInt("math_score"));
                    p.put("comment", "⏱️ *Analyse IA en cours...*");

                    // Sauvegarde du KDA formaté pour l'affichage
                    p.put("kda_display", p.optInt("k", 0) + "/" + p.optInt("d", 0) + "/" + p.optInt("a", 0));

                    p.put("factual_digest", MatchNarrator.buildPlayerDigest(p, pCtx, oppCtx));
                    javaPlayerMap.put(champName, p);
                }

                EmbedBuilder fastEmbed = buildDiscordEmbed(javaPlayerMap, finalTargetUser, lastMatchId, gameTier, true);
                event.getHook().sendMessageEmbeds(fastEmbed.build()).queue(message -> {

                    ctx.executor().submit(() -> {
                        try {
                            JSONObject aiPayload = new JSONObject().put("match_duration", durationMin).put("players", playersToAnalyze);
                            // Appel direct au Commentateur (plus d'Analyste)
                            String casterJson = ctx.mistralService().runPerformanceCaster(aiPayload.toString());
                            JSONArray casterComments = new JSONArray(casterJson.replace("```json", "").replace("```", "").trim());

                            for (int i = 0; i < casterComments.length(); i++) {
                                JSONObject c = casterComments.getJSONObject(i);
                                String aiChampName = c.getString("champion").toUpperCase().trim();
                                
                                // Normalisation des noms problématiques
                                if (aiChampName.equals("WUKONG")) aiChampName = "MONKEYKING";
                                if (aiChampName.equals("RENATA GLASC")) aiChampName = "RENATA";
                                if (aiChampName.equals("NUNU & WILLUMP")) aiChampName = "NUNU";
                                
                                JSONObject p = javaPlayerMap.get(aiChampName);
                                if(p != null) {
                                    p.put("comment", c.optString("comment", "Sans commentaire."));
                                } else {
                                    // Fallback de sécurité : on cherche si le nom de l'IA est contenu dans un nom de champion
                                    for (String realName : javaPlayerMap.keySet()) {
                                        if (realName.contains(aiChampName) || aiChampName.contains(realName)) {
                                            javaPlayerMap.get(realName).put("comment", c.optString("comment", "Sans commentaire."));
                                            break;
                                        }
                                    }
                                }
                            }

                            StringBuilder audit = new StringBuilder();
                            audit.append("=== AUDIT DÉTAILLÉ DE LA PARTIE (").append(lastMatchId).append(") ===\n\n");
                            List<JSONObject> sorted = new ArrayList<>(javaPlayerMap.values());
                            sorted.sort(Comparator.comparingInt(o -> ROLE_ORDER.getOrDefault(o.optString("role_normalized"), 6)));

                            for (JSONObject p : sorted) {
                                String champ = p.getString("champion").toUpperCase();
                                audit.append("=========================================\n");
                                audit.append("👑 CHAMPION : ").append(p.getString("champion")).append("\n");
                                audit.append("=========================================\n\n");
                                JSONObject math = p.getJSONObject("ai_context");
                                audit.append("--- 🧮 LE MATHÉMATICIEN ---\nNote brute : ").append(math.getInt("math_score")).append("/100\n");

// 1. Affichage des 4 Piliers
                                if (math.has("pillars")) {
                                    JSONArray pillars = math.getJSONArray("pillars");
                                    for (int j = 0; j < pillars.length(); j++) {
                                        JSONObject pillar = pillars.getJSONObject(j);
                                        audit.append(String.format("  - Pilier %s : %d/100 (Poids: %.0f%%) -> %s\n",
                                                pillar.getString("name"),
                                                pillar.getInt("score"),
                                                pillar.getDouble("weight") * 100,
                                                pillar.getString("reason")));
                                    }
                                }

// 2. Affichage des Synergies
                                if (math.has("synergies")) {
                                    JSONArray synergies = math.getJSONArray("synergies");
                                    for (int j = 0; j < synergies.length(); j++) {
                                        JSONObject syn = synergies.getJSONObject(j);
                                        audit.append(String.format("  - SYNERGIE : %s (%.1f pts)\n",
                                                syn.getString("reason"),
                                                syn.getDouble("points")));
                                    }
                                }
                                // 3. Affichage des Infos Macro
                                if (math.has("macro_info")) {
                                    JSONArray macroInfos = math.getJSONArray("macro_info");
                                    for (int j = 0; j < macroInfos.length(); j++) {
                                        audit.append("  - INFO MACRO : ").append(macroInfos.getString(j)).append("\n");
                                    }
                                }

                                audit.append("\n--- 🎙️ LE COMMENTATEUR ---\n\"").append(p.getString("comment")).append("\"\n\n");
                            }

                            ctx.db().updateLastAudit(finalTargetUser.getId(), audit.toString());

                            EmbedBuilder finalEmbed = buildDiscordEmbed(javaPlayerMap, finalTargetUser, lastMatchId, gameTier, false);
                            message.editMessageEmbeds(finalEmbed.build()).queue();

                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            ctx.mistralService().flushTraces();
                        }
                    });
                });

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Erreur : " + e.getMessage()).queue();
            }
        });
    }

    private EmbedBuilder buildDiscordEmbed(Map<String, JSONObject> javaPlayerMap, User targetUser, String matchId, String gameTier, boolean isTemp) {
        List<JSONObject> blueTeam = new ArrayList<>(), redTeam = new ArrayList<>();
        JSONObject mvp = null, ace = null;
        int maxW = -1, maxL = -1;

        for (JSONObject p : javaPlayerMap.values()) {
            String role = p.optString("role", "TOP").toUpperCase();
            if (role.equals("MID")) role = "MIDDLE";
            if (role.equals("BOT") || role.equals("ADC")) role = "BOTTOM";
            if (role.equals("SUP") || role.equals("SUPPORT")) role = "UTILITY";
            p.put("role_normalized", role);

            int s = p.optInt("score", 50);
            if (p.optBoolean("win")) {
                if (s > maxW) { maxW = s; mvp = p; }
                blueTeam.add(p);
            } else {
                if (s > maxL) { maxL = s; ace = p; }
                redTeam.add(p);
            }
        }

        if (!isTemp) {
            if (mvp != null) mvp.put("is_mvp", true);
            if (ace != null) ace.put("is_ace", true);
        }

        blueTeam.sort(Comparator.comparingInt(o -> ROLE_ORDER.getOrDefault(o.getString("role_normalized"), 6)));
        redTeam.sort(Comparator.comparingInt(o -> ROLE_ORDER.getOrDefault(o.getString("role_normalized"), 6)));

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle((isTemp ? "⏱️ Analyse..." : "📊 Rapport -") + " " + targetUser.getName());
        eb.setDescription("Match: " + matchId + " • Élo: " + gameTier + (isTemp ? "\n*L'IA étudie la macro...*" : ""));
        eb.setColor(isTemp ? Color.GRAY : Color.decode("#C8AA6E"));
        eb.setTimestamp(java.time.Instant.now());

        StringBuilder sbB = new StringBuilder(), sbR = new StringBuilder();
        for (JSONObject p : blueTeam) sbB.append(formatPlayerLine(p));
        for (JSONObject p : redTeam) sbR.append(formatPlayerLine(p));

        eb.addField("🔵 Équipe Gagnante", sbB.toString(), true);
        eb.addField("🔴 Équipe Perdante", sbR.toString(), true);
        return eb;
    }

    private String formatPlayerLine(JSONObject p) {
        // Correction de la récupération du nom : RiotService renvoie souvent 'summoner_name' ou 'name'
        String name = p.optString("riotIdGameName", p.optString("summoner_name", p.optString("name", "Inconnu")));
        if (name.length() > 16) name = name.substring(0, 14) + "..";

        int score = p.optInt("score", 0);
        String kda = p.optString("kda_display", "0/0/0");
        String medal = score >= 95 ? "👑" : score >= 90 ? "👑" : score >= 80 ? "🔥" : score >= 50 ? "😐" : "💩";
        String tag = p.optBoolean("is_mvp") ? " **MVP** 🏆" : (p.optBoolean("is_ace") ? " **ACE** 💎" : "");

        return String.format("%s **%s** (%s)\n**%s** — **%d/100** %s%s\n*%s*\n\n",
                getRoleEmoji(p.getString("role_normalized")), name, p.optString("champion"), kda, score, medal, tag, p.optString("comment"));
    }

    private String getRoleEmoji(String role) {
        return switch (role) {
            case "TOP" -> "<:top:1467225357542363307>";
            case "JUNGLE" -> "<:jungle:1467225403964784640>";
            case "MIDDLE" -> "<:mid:1467225252139634880>";
            case "BOTTOM" -> "<:bot:1467225277431021799>";
            case "UTILITY" -> "<:support:1467225305663013118>";
            default -> "❓";
        };
    }
}