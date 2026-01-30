package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.example.DatabaseManager;
import org.example.service.ai.PromptRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PerformanceCommand implements SlashCommand {

    private static final Map<String, Integer> ROLE_ORDER = Map.of(
            "TOP", 1,
            "JUNGLE", 2,
            "MIDDLE", 3,
            "BOTTOM", 4,
            "UTILITY", 5
    );

    @Override
    public CommandData getCommandData() {
        return Commands.slash("performance", "Affiche les notes et performances des 10 joueurs de ta dernière game.");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        event.deferReply().queue();
        String discordId = event.getUser().getId();
        DatabaseManager.UserRecord user = ctx.db().getUser(discordId);

        if (user == null) {
            event.getHook().sendMessage("Tu n'as pas lié ton compte Riot ! Utilise la commande `/link` d'abord.").queue();
            return;
        }

        ctx.executor().submit(() -> {
            try {
                // 1. Récupérer le dernier match
                String lastMatchId = ctx.riotService().getLastMatchId(user.puuid, user.region);
                if (lastMatchId.startsWith("Error") || lastMatchId.equals("None")) {
                    event.getHook().sendMessage("Impossible de récupérer ton dernier match.").queue();
                    return;
                }

                // 2. Récupérer l'analyse complète du match
                String matchJson = ctx.riotService().getMatchAnalysis(lastMatchId, user.puuid, user.region);
                if (matchJson.startsWith("Error")) {
                    event.getHook().sendMessage("Erreur lors de l'analyse du match : " + matchJson).queue();
                    return;
                }

                // 3. Construire le prompt pour l'IA
                String systemPrompt = PromptRegistry.PERFORMANCE_ANALYSIS_SYSTEM;
                String userPrompt = "Voici les données du match :\n" + matchJson;

                // 4. Appeler l'IA via performTask pour un contrôle total du prompt
                String aiResponse = ctx.mistralService().performTask(userPrompt, systemPrompt);

                // Nettoyage de la réponse (au cas où l'IA ajoute des ```json ... ```)
                String cleanJson = aiResponse.replace("```json", "").replace("```", "").trim();
                
                JSONArray performances;
                try {
                    performances = new JSONArray(cleanJson);
                } catch (Exception e) {
                    event.getHook().sendMessage("L'IA a eu du mal à formater les résultats. Voici son analyse brute :\n" + aiResponse).queue();
                    return;
                }

                // 5. Traitement et Tri des données
                List<JSONObject> blueTeam = new ArrayList<>();
                List<JSONObject> redTeam = new ArrayList<>();

                for (int i = 0; i < performances.length(); i++) {
                    JSONObject p = performances.getJSONObject(i);
                    // Normalisation du rôle pour le tri (parfois l'IA renvoie "MID" au lieu de "MIDDLE")
                    String role = p.optString("role", "TOP").toUpperCase();
                    if (role.equals("MID")) role = "MIDDLE";
                    if (role.equals("BOT") || role.equals("ADC")) role = "BOTTOM";
                    if (role.equals("SUP") || role.equals("SUPPORT")) role = "UTILITY";
                    p.put("role_normalized", role);

                    int teamId = p.optInt("team", 100);
                    if (teamId == 100) blueTeam.add(p);
                    else redTeam.add(p);
                }

                // Tri par rôle (Top -> Jungle -> Mid -> Bot -> Support)
                Comparator<JSONObject> roleComparator = Comparator.comparingInt(o -> ROLE_ORDER.getOrDefault(o.getString("role_normalized"), 6));
                blueTeam.sort(roleComparator);
                redTeam.sort(roleComparator);

                // 6. Construction de l'Embed
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("📊 Rapport de Performance - " + lastMatchId);
                embed.setColor(Color.decode("#C8AA6E")); // Or Hextech
                embed.setFooter("Analyse générée par Cicero AI • " + user.region.toUpperCase());
                embed.setTimestamp(java.time.Instant.now());

                StringBuilder sbBlue = new StringBuilder();
                for (JSONObject p : blueTeam) {
                    sbBlue.append(formatPlayerLine(p));
                }

                StringBuilder sbRed = new StringBuilder();
                for (JSONObject p : redTeam) {
                    sbRed.append(formatPlayerLine(p));
                }

                embed.addField("🔵 Équipe Bleue", sbBlue.toString(), true);
                embed.addField("🔴 Équipe Rouge", sbRed.toString(), true);

                event.getHook().sendMessageEmbeds(embed.build()).queue();

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Une erreur technique est survenue : " + e.getMessage()).queue();
            } finally {
                // 7. Écriture des traces (Agent + Tavily) après l'envoi de la réponse
                ctx.mistralService().flushTraces();
            }
        });
    }

    private String formatPlayerLine(JSONObject p) {
        String name = p.optString("name", "Inconnu");
        String champion = p.optString("champion", "Champion");
        int score = p.optInt("score", 50);
        
        // Correction : l'IA hallucine parfois des clés comme "dégâts" au lieu de "comment"
        // On cherche "comment", sinon on prend une valeur par défaut
        String comment = p.optString("comment", "Pas de commentaire.");
        
        // Si le commentaire est vide ou générique, on regarde si l'IA n'a pas mis l'info ailleurs
        if (comment.equals("Pas de commentaire.") || comment.isEmpty()) {
             if (p.has("dégâts")) comment = p.getString("dégâts");
             else if (p.has("analyse")) comment = p.getString("analyse");
             else if (p.has("summary")) comment = p.getString("summary");
        }

        String role = p.getString("role_normalized");

        String medal = "";
        if (score >= 90) medal = "👑";
        else if (score >= 80) medal = "🔥";
        else if (score >= 50) medal = "😐";
        else if (score >= 20) medal = "💩";
        else medal = "💀";

        String roleEmoji = getRoleEmoji(role);

        return String.format("%s **%s** (%s)\n**%d/100** %s\n*%s*\n\n", 
                roleEmoji, name, champion, score, medal, comment);
    }

    private String getRoleEmoji(String role) {
        return switch (role) {
            case "TOP" -> "<:top:1340021376840765522>"; // Remplacez par vos IDs d'emojis custom si vous en avez
            case "JUNGLE" -> "<:jungle:1340021418544730224>";
            case "MIDDLE" -> "<:mid:1340021462085799978>";
            case "BOTTOM" -> "<:bot:1340021505765412968>";
            case "UTILITY" -> "<:support:1340021553039413340>";
            default -> "❓";
        };
    }
}