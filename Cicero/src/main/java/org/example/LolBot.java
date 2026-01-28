package org.example;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.User;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONObject;
import org.json.JSONArray;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LolBot extends ListenerAdapter {

    private final DatabaseManager db = new DatabaseManager();
    private static RiotService riotService;
    private static GeminiService geminiService;

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.load();

        // Initialisation des services
        riotService = new RiotService(dotenv.get("RIOT_API_KEY"));
        geminiService = new GeminiService(dotenv.get("GEMINI_API_KEY"));

        // Démarrage du Bot
        JDA jda = JDABuilder.createDefault(dotenv.get("DISCORD_TOKEN"))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new LolBot())
                .build();

        // Enregistrement des commandes Slash
        jda.updateCommands().addCommands(
                Commands.slash("link", "Lier ton compte Riot (GameName#TagLine)")
                        .addOption(OptionType.STRING, "riot_id", "Ton Riot ID (ex: Yvain#FDC)", true),
                Commands.slash("analyze", "L'IA analyse ta dernière game")
                        .addOption(OptionType.STRING, "question", "Ta question (ex: pourquoi j'ai perdu ?)", true),
                Commands.slash("leaderboard", "Affiche le classement des membres du serveur"),
                Commands.slash("rank", "Affiche le rang d'un membre")
                        .addOption(OptionType.USER, "membre", "Le membre dont tu veux voir le rang", true),
                Commands.slash("ask", "Pose une question à l'IA sur LoL ou un joueur")
                        .addOption(OptionType.STRING, "question", "Ta question (ex: Que penses-tu de @Yvain ?)", true),
                Commands.slash("new-ask", "Démarre une nouvelle conversation avec l'IA (efface la mémoire)"),
                Commands.slash("help", "Affiche la liste des commandes disponibles")
        ).queue();

        System.out.println("Bot démarré !");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String command = event.getName();

        if (command.equals("link")) {
            handleLinkCommand(event);
        } else if (command.equals("analyze")) {
            handleAnalyzeCommand(event);
        } else if (command.equals("leaderboard")) {
            handleLeaderboardCommand(event);
        } else if (command.equals("rank")) {
            handleRankCommand(event);
        } else if (command.equals("ask")) {
            handleAskCommand(event);
        } else if (command.equals("new-ask")) {
            handleNewAskCommand(event);
        } else if (command.equals("help")) {
            handleHelpCommand(event);
        }
    }

    private void handleHelpCommand(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🤖 Aide - Commandes du Bot");
        embed.setColor(Color.CYAN);
        embed.setDescription("Voici la liste des commandes disponibles pour interagir avec le bot :");

        embed.addField("🔗 `/link [riot_id]`", 
                "Lie ton compte Riot à ton compte Discord.\n*Exemple : /link Yvain#FDC*", false);

        embed.addField("📊 `/rank [membre]`", 
                "Affiche le rang, les LP et le winrate d'un membre.\n*Exemple : /rank @Yvain*", false);

        embed.addField("🏆 `/leaderboard`", 
                "Affiche le classement de tous les membres enregistrés sur le serveur.", false);

        embed.addField("🧠 `/ask [question]`", 
                "Pose une question à l'IA. Tu peux mentionner un joueur pour que l'IA analyse son profil.\n*Exemple : /ask Comment @Yvain peut améliorer son farm ?*", false);

        embed.addField("🔄 `/new-ask`", 
                "Efface la mémoire de ta conversation avec l'IA pour repartir à zéro.", false);

        embed.addField("🔎 `/analyze [question]`", 
                "L'IA analyse ta toute dernière partie jouée et répond à ta question spécifique.\n*Exemple : /analyze Pourquoi j'ai fait si peu de dégâts ?*", false);

        embed.setFooter("Bot développé avec ❤️ pour les invocateurs.");
        
        event.replyEmbeds(embed.build()).queue();
    }

    private void handleNewAskCommand(SlashCommandInteractionEvent event) {
        String discordId = event.getUser().getId();
        db.clearChatHistory(discordId);
        event.reply("🧠 Mémoire effacée ! On repart sur de nouvelles bases.").setEphemeral(true).queue();
    }

    // --- NOUVELLE COMMANDE ASK ---
    private void handleAskCommand(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        String question = event.getOption("question").getAsString();
        String discordId = event.getUser().getId();

        new Thread(() -> {
            try {
                // 1. Récupération de l'historique
                JSONArray history = db.getChatHistory(discordId);

                // 2. Construction du contexte système (VERROUILLAGE THÉMATIQUE)
                StringBuilder context = new StringBuilder();
                context.append("Tu es un expert absolu de League of Legends et de l'Esport.\n");
                context.append("⚠️ RÈGLE D'OR : Tu refuses catégoriquement de parler de politique, religion, économie ou actualités hors jeu vidéo. Si l'utilisateur aborde ces sujets, réponds poliment que tu n'es là que pour parler de LoL.\n");
                context.append("Tes rôles : Coach technique, Analyste Esport, Encyclopédie du Lore et Ami taquin.\n");
                context.append("Hiérarchie Rangs : IRON > BRONZE > SILVER > GOLD > PLATINUM > EMERALD > DIAMOND > MASTER > GRANDMASTER > CHALLENGER.\n");
                
                // Détection du mode Flex
                boolean isFlexRequest = question.toLowerCase().contains("flex");

                // A. Infos sur l'auteur du message ("Moi")
                String myPuuid = db.getPuuid(discordId);
                if (myPuuid != null) {
                    context.append("\n--- Infos sur l'utilisateur qui te parle (Moi) ---\n");
                    appendPlayerInfo(context, myPuuid, isFlexRequest);
                } else {
                    context.append("\nL'utilisateur qui te parle n'a pas lié son compte Riot.\n");
                }

                // B. Infos sur les joueurs mentionnés (@User)
                Pattern pattern = Pattern.compile("<@(\\d+)>");
                Matcher matcher = pattern.matcher(question);

                while (matcher.find()) {
                    String mentionedId = matcher.group(1);
                    // On évite de remettre les infos si l'utilisateur se mentionne lui-même
                    if (mentionedId.equals(discordId)) continue;

                    String puuid = db.getPuuid(mentionedId);
                    User mentionedUser = event.getJDA().retrieveUserById(mentionedId).complete();
                    String userName = (mentionedUser != null) ? mentionedUser.getName() : "Joueur";

                    if (puuid != null) {
                        context.append("\n--- Infos sur ").append(userName).append(" ---\n");
                        appendPlayerInfo(context, puuid, isFlexRequest);
                    } else {
                        context.append("\nL'utilisateur ").append(userName).append(" n'a pas lié son compte Riot.\n");
                    }
                }

                // 3. Ajout du message utilisateur à l'historique
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", question);
                history.put(userMsg);

                // 4. Appel à gemini
                String aiResponse = geminiService.chatWithHistory(history, context.toString());

                // 5. Sauvegarde
                JSONObject aiMsg = new JSONObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiResponse);
                history.put(aiMsg);
                
                db.updateChatHistory(discordId, history);

                // 6. Réponse Discord
                if (aiResponse.length() > 1900) aiResponse = aiResponse.substring(0, 1900) + "...";
                event.getHook().sendMessage(aiResponse).queue();

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Oups, j'ai eu un problème pour réfléchir : " + e.getMessage()).queue();
            }
        }).start();
    }

    // Helper pour ajouter les infos d'un joueur au contexte
    private void appendPlayerInfo(StringBuilder context, String puuid, boolean showFlex) throws Exception {
        // Rangs
        Map<String, RiotService.RankInfo> ranks = riotService.getAllRanks(puuid);
        RiotService.RankInfo solo = ranks.get("SOLO");
        RiotService.RankInfo flex = ranks.get("FLEX");

        if (showFlex) {
            // Si l'utilisateur demande explicitement le Flex, on met le Flex en avant
            context.append("MODE FLEX DEMANDÉ.\n");
            context.append("Rang FLEX: ").append(flex.tier).append(" ").append(flex.rank)
                   .append(" (").append(flex.lp).append(" LP), Winrate: ")
                   .append(flex.wins).append("W/").append(flex.losses).append("L.\n");
            
            // On mentionne le Solo juste pour info, mais en second plan
            context.append("(Info secondaire - Rang Solo: ").append(solo.tier).append(" ").append(solo.rank).append(")\n");
        } else {
            // Par défaut, on montre le Solo
            context.append("Rang SOLO/DUO: ").append(solo.tier).append(" ").append(solo.rank)
                   .append(" (").append(solo.lp).append(" LP), Winrate: ")
                   .append(solo.wins).append("W/").append(solo.losses).append("L.\n");
        }

        // Top Champions
        String mastery = riotService.getTopChampions(puuid);
        context.append("Top Champions (Mastery): ").append(mastery).append("\n");

        // Historique récent
        List<String> lastMatches = riotService.getLastMatchesIds(puuid, 3);
        context.append("Dernières games :\n");
        for (String matchId : lastMatches) {
            JSONObject matchData = riotService.getMatchDetails(matchId);
            context.append("- ").append(summarizeMatch(matchData, puuid)).append("\n");
        }
    }

    // --- AUTRES COMMANDES (Inchangées) ---

    private void handleLinkCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        String riotIdInput = event.getOption("riot_id").getAsString();
        String[] parts = riotIdInput.split("#");

        if (parts.length != 2) {
            event.getHook().sendMessage("Format invalide. Utilise le format : GameName#TagLine").queue();
            return;
        }

        try {
            String puuid = riotService.getPuuid(parts[0], parts[1]);
            
            // Sauvegarde du nouveau compte
            db.saveUser(event.getUser().getId(), puuid, riotIdInput);
            
            // IMPORTANT : On efface l'historique de chat pour éviter les confusions avec l'ancien compte
            db.clearChatHistory(event.getUser().getId());
            
            event.getHook().sendMessage("Compte lié avec succès : " + riotIdInput + " (Mémoire IA réinitialisée)").queue();
        } catch (Exception e) {
            e.printStackTrace();
            event.getHook().sendMessage("Erreur lors de la liaison : " + e.getMessage()).queue();
        }
    }

    private void handleAnalyzeCommand(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        String discordId = event.getUser().getId();
        String puuid = db.getPuuid(discordId);

        if (puuid == null) {
            event.getHook().sendMessage("Tu n'as pas lié ton compte Riot ! Utilise la commande `/link` d'abord.").queue();
            return;
        }

        String userQuestion = event.getOption("question").getAsString();

        try {
            String matchId = riotService.getLastMatchId(puuid);

            if(matchId == null) {
                event.getHook().sendMessage("Aucune game récente trouvée.").queue();
                return;
            }

            JSONObject matchData = riotService.getMatchDetails(matchId);
            String gameSummary = summarizeMatch(matchData, puuid);
            String aiResponse = geminiService.analyzeGame(userQuestion, gameSummary);

            if(aiResponse.length() > 1900) aiResponse = aiResponse.substring(0, 1900) + "...";

            event.getHook().sendMessage("**Analyse de la game :**\n" + aiResponse).queue();

        } catch (Exception e) {
            e.printStackTrace();
            event.getHook().sendMessage("Erreur technique : " + e.getMessage()).queue();
        }
    }

    private void handleRankCommand(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        User targetUser = event.getOption("membre").getAsUser();
        String discordId = targetUser.getId();
        String puuid = db.getPuuid(discordId);

        if (puuid == null) {
            event.getHook().sendMessage("❌ Ce membre n'a pas lié son compte Riot.").queue();
            return;
        }

        new Thread(() -> {
            try {
                RiotService.RankInfo rank = riotService.getRank(puuid);

                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("Rang de " + targetUser.getName());
                embed.setThumbnail(targetUser.getAvatarUrl());

                embed.setColor(getColorForTier(rank.tier));
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
                e.printStackTrace();
                event.getHook().sendMessage("❌ Erreur : " + e.getMessage()).queue();
            }
        }).start();
    }

    private void handleLeaderboardCommand(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        List<DatabaseManager.UserRecord> users = db.getAllUsers();
        if (users.isEmpty()) {
            event.getHook().sendMessage("Aucun utilisateur enregistré.").queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 Classement du Serveur");
        embed.setColor(Color.ORANGE);

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();

            for (DatabaseManager.UserRecord user : users) {
                try {
                    Thread.sleep(100); 

                    RiotService.RankInfo rank = riotService.getRank(user.puuid);
                    String emoji = getRankEmoji(rank.tier);

                    sb.append(emoji).append(" **").append(user.summonerName).append("** : ");
                    if ("UNRANKED".equals(rank.tier)) {
                        sb.append("Unranked");
                    } else {
                        sb.append(rank.tier).append(" ").append(rank.rank).append(" (").append(rank.lp).append(" LP)");
                    }
                    sb.append("\n");

                } catch (Exception e) {
                    sb.append("❌ **").append(user.summonerName).append("** : Erreur\n");
                }
            }
            embed.setDescription(sb.toString());
            event.getHook().sendMessageEmbeds(embed.build()).queue();
        }).start();
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

    private String summarizeMatch(JSONObject matchData, String myPuuid) {
        JSONArray participants = matchData.getJSONObject("info").getJSONArray("participants");
        JSONObject me = null;

        for (int i = 0; i < participants.length(); i++) {
            JSONObject p = participants.getJSONObject(i);
            if (p.getString("puuid").equals(myPuuid)) {
                me = p;
                break;
            }
        }

        if (me == null) return "Données joueur introuvables.";

        boolean win = me.getBoolean("win");
        int kills = me.getInt("kills");
        int deaths = me.getInt("deaths");
        int assists = me.getInt("assists");
        String champion = me.getString("championName");
        int totalDamage = me.getInt("totalDamageDealtToChampions");
        int gold = me.getInt("goldEarned");
        
        // Nouvelles stats pour l'IA Coach
        int cs = me.getInt("totalMinionsKilled") + me.getInt("neutralMinionsKilled");
        int visionScore = me.getInt("visionScore");
        long gameDuration = matchData.getJSONObject("info").getLong("gameDuration");
        double csPerMin = (gameDuration > 0) ? (cs / (gameDuration / 60.0)) : 0;

        return String.format(
                "Résultat: %s | Champion: %s | KDA: %d/%d/%d | Dégâts: %d | Gold: %d | CS/min: %.1f | Vision: %d",
                (win ? "VICTOIRE" : "DÉFAITE"), champion, kills, deaths, assists, totalDamage, gold, csPerMin, visionScore
        );
    }
}