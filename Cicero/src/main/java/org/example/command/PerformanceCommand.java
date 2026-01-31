package org.example.command;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
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
        return Commands.slash("performance", "Affiche les notes et performances des 10 joueurs de la dernière game.")
                .addOption(OptionType.USER, "joueur", "Le joueur dont tu veux voir la performance (optionnel)", false);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, BotContext ctx) {
        // On diffère la réponse pour éviter le timeout de 3 secondes de Discord
        event.deferReply().queue();
        
        User targetUser = event.getUser();
        OptionMapping option = event.getOption("joueur");
        if (option != null) {
            targetUser = option.getAsUser();
        }
        
        String discordId = targetUser.getId();
        DatabaseManager.UserRecord user = ctx.db().getUser(discordId);

        if (user == null) {
            String message = (option != null) 
                ? "Ce joueur n'a pas lié son compte Riot !" 
                : "Tu n'as pas lié ton compte Riot ! Utilise la commande `/link` d'abord.";
            event.getHook().sendMessage(message).queue();
            return;
        }

        User finalTargetUser = targetUser;

        ctx.executor().submit(() -> {
            try {
                // 1. Récupérer le dernier match
                String lastMatchId = ctx.riotService().getLastMatchId(user.puuid, user.region);
                if (lastMatchId.startsWith("Error") || lastMatchId.equals("None")) {
                    event.getHook().sendMessage("Impossible de récupérer le dernier match.").queue();
                    return;
                }

                // 2. Récupérer l'analyse complète du match (MÉTHODE LOURDE)
                String matchJson = ctx.riotService().getMatchAnalysis(lastMatchId, user.puuid, user.region);
                if (matchJson.startsWith("Error")) {
                    event.getHook().sendMessage("Erreur lors de l'analyse du match : " + matchJson).queue();
                    return;
                }
                
                // On parse le JSON du match pour savoir qui a gagné
                JSONObject matchData = new JSONObject(matchJson);
                // On cherche l'équipe gagnante. Dans le JSON de Riot, "teams" contient les infos de victoire.
                // Mais ici matchJson est le résultat de getMatchAnalysis qui est déjà processé ou brut ?
                // getMatchAnalysis renvoie le JSON processé par MatchDataProcessor.
                // Regardons la structure. Si c'est le JSON brut de Riot, il y a "info" -> "teams".
                // Si c'est le JSON processé, il faut voir comment récupérer le gagnant.
                // Le JSON processé contient "allies" et "enemies" par rapport au joueur cible, ou une liste de joueurs.
                // Le prompt system demande à l'IA de renvoyer "team": 100 ou 200.
                
                // Pour simplifier, on va laisser l'IA faire son travail et on déterminera le MVP/ACE après avoir reçu les scores.
                // Cependant, il nous faut savoir quelle équipe a gagné pour attribuer MVP (gagnant) ou ACE (perdant).
                // On peut le déduire des données renvoyées par l'IA si on lui demande d'inclure "win": true/false
                // OU on peut le parser depuis matchJson si on a accès à l'info "win".
                
                // On va modifier le prompt pour demander si le joueur a gagné.
                // Mais attendez, le prompt actuel demande juste un tableau de joueurs.
                // On peut déduire l'équipe gagnante en regardant le JSON brut si besoin, ou demander à l'IA.
                // Le plus simple est de demander à l'IA d'ajouter "win": true/false dans sa réponse JSON pour chaque joueur.
                
                // Modifions le prompt système d'abord (dans une autre étape si besoin, mais ici on peut tricher en l'ajoutant au user prompt ou en espérant que l'IA le sache).
                // Mieux : on va parser le matchJson ici pour trouver le gagnant (team 100 ou 200).
                int winningTeamId = -1;
                if (matchData.has("info")) {
                    JSONArray teams = matchData.getJSONObject("info").getJSONArray("teams");
                    for(int i=0; i<teams.length(); i++) {
                        JSONObject t = teams.getJSONObject(i);
                        if(t.getBoolean("win")) {
                            winningTeamId = t.getInt("teamId");
                            break;
                        }
                    }
                } else if (matchData.has("teams")) { // Structure processée potentielle
                     // Si la structure est différente, on essaie de trouver un joueur gagnant
                     // Mais getMatchAnalysis renvoie souvent le JSON enrichi.
                     // Si on ne trouve pas, on fera sans ou on demandera à l'IA.
                }
                
                // Si on ne trouve pas le gagnant facilement, on va demander à l'IA de nous le dire dans le JSON.
                // On va modifier le prompt système via le code (en surchargeant la string) ou en modifiant le fichier PromptRegistry.
                // Pour l'instant, supposons qu'on modifie PromptRegistry pour inclure "win".
                
                // 3. Construire le prompt pour l'IA
                // On injecte une instruction supplémentaire pour être sûr d'avoir l'info de victoire
                String systemPrompt = PromptRegistry.PERFORMANCE_ANALYSIS_SYSTEM + 
                    "\nAJOUT OBLIGATOIRE : Ajoute le champ \"win\": true/false pour chaque joueur.";
                
                String userPrompt = "Voici les données du match :\n" + matchJson;

                // 4. Appeler l'IA
                String aiResponse = ctx.mistralService().performTask(userPrompt, systemPrompt);
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
                
                // Variables pour MVP et ACE
                JSONObject mvpPlayer = null;
                JSONObject acePlayer = null;
                int maxScoreWinner = -1;
                int maxScoreLoser = -1;

                for (int i = 0; i < performances.length(); i++) {
                    JSONObject p = performances.getJSONObject(i);
                    
                    // Normalisation rôle
                    String role = p.optString("role", "TOP").toUpperCase();
                    if (role.equals("MID")) role = "MIDDLE";
                    if (role.equals("BOT") || role.equals("ADC")) role = "BOTTOM";
                    if (role.equals("SUP") || role.equals("SUPPORT")) role = "UTILITY";
                    p.put("role_normalized", role);

                    int teamId = p.optInt("team", 100);
                    boolean win = p.optBoolean("win", false); // On espère que l'IA l'a mis, sinon on devra deviner
                    
                    // Si l'IA n'a pas mis "win", on essaie de le déduire du teamId si on a trouvé winningTeamId plus haut
                    if (!p.has("win") && winningTeamId != -1) {
                        win = (teamId == winningTeamId);
                        p.put("win", win);
                    }

                    int score = p.optInt("score", 0);

                    if (win) {
                        if (score > maxScoreWinner) {
                            maxScoreWinner = score;
                            mvpPlayer = p;
                        }
                    } else {
                        if (score > maxScoreLoser) {
                            maxScoreLoser = score;
                            acePlayer = p;
                        }
                    }

                    if (teamId == 100) blueTeam.add(p);
                    else redTeam.add(p);
                }
                
                // Marquage MVP / ACE dans les objets JSON pour le formatage
                if (mvpPlayer != null) mvpPlayer.put("is_mvp", true);
                if (acePlayer != null) acePlayer.put("is_ace", true);

                // Tri par rôle
                Comparator<JSONObject> roleComparator = Comparator.comparingInt(o -> ROLE_ORDER.getOrDefault(o.getString("role_normalized"), 6));
                blueTeam.sort(roleComparator);
                redTeam.sort(roleComparator);

                // 6. Construction de l'Embed
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("📊 Rapport de Performance - " + finalTargetUser.getName());
                embed.setDescription("Match: " + lastMatchId);
                embed.setColor(Color.decode("#C8AA6E"));
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
                ctx.mistralService().flushTraces();
            }
        });
    }

    private String formatPlayerLine(JSONObject p) {
        String name = p.optString("name", "Inconnu");
        String champion = p.optString("champion", "Champion");
        int score = p.optInt("score", 50);
        String kda = p.optString("kda", "N/A");
        
        String comment = p.optString("comment", "Pas de commentaire.");
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
        
        // Ajout MVP / ACE
        String specialTag = "";
        if (p.optBoolean("is_mvp", false)) {
            specialTag = " **— MVP** 🏆"; 
        } else if (p.optBoolean("is_ace", false)) {
            specialTag = " **— ACE** 💎";
        }

        String roleEmoji = getRoleEmoji(role);

        return String.format("%s **%s** (%s)\n**%s** - **%d/100** %s%s\n*%s*\n\n", 
                roleEmoji, name, champion, kda, score, medal, specialTag, comment);
    }

    private String getRoleEmoji(String role) {
        return switch (role) {
            case "TOP" -> "<:top:1340021376840765522>";
            case "JUNGLE" -> "<:jungle:1340021418544730224>";
            case "MIDDLE" -> "<:mid:1340021462085799978>";
            case "BOTTOM" -> "<:bot:1340021505765412968>";
            case "UTILITY" -> "<:support:1340021553039413340>";
            default -> "❓";
        };
    }
}