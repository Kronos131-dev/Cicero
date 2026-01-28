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
import net.dv8tion.jda.api.utils.FileUpload;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONObject;
import org.json.JSONArray;

import java.awt.Color;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main class for the Cicero Discord Bot.
 * Handles command dispatching and interaction logic.
 */
public class LolBot extends ListenerAdapter {

    private final DatabaseManager db = new DatabaseManager();
    private static RiotService riotService;
    private static GeminiService geminiService;
    private static LeaguepediaService leaguepediaService;
    
    private static final ExecutorService apiExecutor = Executors.newFixedThreadPool(10);
    private static final List<String> ESPORT_LEAGUES = Arrays.asList("lec", "lfl", "lck", "lpl", "lcs", "worlds", "msi");
    private static final List<String> ESPORT_KEYWORDS = Arrays.asList("esport", "compétitif", "pro", "match", "résultat", "score", "méta", "build", "stats");
    private static final String LEADERBOARD_BANNER = "https://images.contentstack.io/v3/assets/blt731acb42bb3d1659/bltacc406a1643cf5cd/5e98753f18a3221d65d69303/2020_Worlds_Trophy_Header.jpg";

    public static void main(String[] args) throws Exception {
        Dotenv dotenv = Dotenv.load();

        riotService = new RiotService(dotenv.get("RIOT_API_KEY"));
        geminiService = new GeminiService();
        leaguepediaService = new LeaguepediaService();

        JDA jda = JDABuilder.createDefault(dotenv.get("DISCORD_TOKEN"))
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new LolBot())
                .build();

        jda.updateCommands().addCommands(
                Commands.slash("link", "Lier ton compte Riot (GameName#TagLine)")
                        .addOption(OptionType.STRING, "riot_id", "Ton Riot ID (ex: Yvain#FDC)", true),
                Commands.slash("analyze", "L'IA analyse ta dernière game en détail")
                        .addOption(OptionType.STRING, "question", "Ta question (ex: pourquoi j'ai perdu ?)", true),
                Commands.slash("leaderboard", "Affiche le classement des membres du serveur"),
                Commands.slash("rank", "Affiche le rang d'un membre")
                        .addOption(OptionType.USER, "membre", "Le membre dont tu veux voir le rang", true),
                Commands.slash("ask", "Pose une question à l'IA sur LoL, l'esport ou un joueur")
                        .addOption(OptionType.STRING, "question", "Ta question (ex: Que penses-tu de @Yvain ?)", true),
                Commands.slash("new-ask", "Démarre une nouvelle conversation avec l'IA (efface la mémoire)"),
                Commands.slash("help", "Affiche la liste des commandes disponibles")
        ).queue();

        System.out.println("Bot started successfully!");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String command = event.getName();
        switch (command) {
            case "link" -> handleLinkCommand(event);
            case "analyze" -> handleAnalyzeCommand(event);
            case "leaderboard" -> handleLeaderboardCommand(event);
            case "rank" -> handleRankCommand(event);
            case "ask" -> handleAskCommand(event);
            case "new-ask" -> handleNewAskCommand(event);
            case "help" -> handleHelpCommand(event);
        }
    }

    // --- COMMAND HANDLERS ---

    private void handleAskCommand(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        String question = event.getOption("question").getAsString();
        String discordId = event.getUser().getId();

        new Thread(() -> {
            try {
                JSONArray history = db.getChatHistory(discordId);
                StringBuilder context = new StringBuilder();
                context.append("Tu es un expert absolu de League of Legends et de l'Esport. Tes rôles : Coach, Analyste, Encyclopédie du jeu.\n");
                
                String lowerQ = question.toLowerCase();
                boolean isFlexRequest = lowerQ.contains("flex");

                // 1. Esport Context Injection
                if (isEsportQuery(lowerQ)) {
                    injectEsportContext(context, question, lowerQ);
                }

                // 2. Player Context Injection (Targeting Logic)
                injectPlayerContext(event, context, question, discordId, isFlexRequest, isEsportQuery(lowerQ));

                // 3. AI Interaction
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", question);
                history.put(userMsg);

                String aiResponse = geminiService.chatWithHistory(history, context.toString());

                JSONObject aiMsg = new JSONObject().put("role", "assistant").put("content", aiResponse);
                history.put(aiMsg);
                db.updateChatHistory(discordId, history);

                sendLongMessage(event, aiResponse);

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Oups, une erreur est survenue : " + e.getMessage()).queue();
            }
        }).start();
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

        new Thread(() -> {
            try {
                String matchId = riotService.getLastMatchId(puuid);
                if (matchId == null) {
                    event.getHook().sendMessage("Aucune game récente trouvée.").queue();
                    return;
                }

                String gameDetails = riotService.getFullMatchDetails(matchId, puuid);
                String aiResponse = geminiService.analyzeGame(userQuestion, gameDetails);

                sendLongMessage(event, "**Analyse détaillée de la dernière game :**\n" + aiResponse);

            } catch (Exception e) {
                e.printStackTrace();
                event.getHook().sendMessage("❌ Erreur technique lors de l'analyse : " + e.getMessage()).queue();
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
        embed.setTitle("🏆 CLASSEMENT DU SERVEUR");
        embed.setColor(Color.ORANGE);
        embed.setDescription("Qui sera le roi de la faille ?");
        embed.setThumbnail("https://i.imgur.com/o3M5b4X.png");
        
        File bannerFile = new File("data/banniere.jpg");
        if (bannerFile.exists()) {
            embed.setImage("attachment://banniere.jpg");
        } else {
            embed.setImage(LEADERBOARD_BANNER);
        }

        new Thread(() -> {
            List<PlayerRank> rankedPlayers = new ArrayList<>();
            for (DatabaseManager.UserRecord user : users) {
                try {
                    RiotService.RankInfo rank = riotService.getRank(user.puuid);
                    int score = calculateEloScore(rank.tier, rank.rank, rank.lp);
                    rankedPlayers.add(new PlayerRank(user.summonerName, rank, score));
                } catch (Exception ignored) {}
            }

            rankedPlayers.sort(Comparator.comparingInt(PlayerRank::getScore).reversed());

            StringBuilder sb = new StringBuilder();
            int rankPos = 1;
            for (PlayerRank p : rankedPlayers) {
                if (rankPos > 10) break;
                String medal = (rankPos == 1) ? "🥇" : (rankPos == 2) ? "🥈" : (rankPos == 3) ? "🥉" : "**" + rankPos + ".**";
                String emoji = getRankEmoji(p.rankInfo.tier);
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
            
            if (bannerFile.exists()) {
                event.getHook().sendMessageEmbeds(embed.build())
                     .addFiles(FileUpload.fromData(bannerFile, "banniere.jpg")).queue();
            } else {
                event.getHook().sendMessageEmbeds(embed.build()).queue();
            }
        }).start();
    }

    // --- HELPER METHODS ---

    private boolean isEsportQuery(String lowerQ) {
        return ESPORT_LEAGUES.stream().anyMatch(lowerQ::contains) || ESPORT_KEYWORDS.stream().anyMatch(lowerQ::contains);
    }

    private void injectEsportContext(StringBuilder context, String question, String lowerQ) {
        context.append("\n--- CONTEXTE ESPORT ---\n");
        for (String league : ESPORT_LEAGUES) {
            if (lowerQ.contains(league)) {
                if (lowerQ.contains("résultat") || lowerQ.contains("match")) 
                    context.append(leaguepediaService.getRecentResults(league));
                if (lowerQ.contains("méta") || lowerQ.contains("champion") || lowerQ.contains("build")) 
                    context.append(leaguepediaService.getChampionStats(league));
            }
        }
        
        Pattern p = Pattern.compile("stats d[eu] ([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(question);
        if (m.find()) {
            String proName = m.group(1);
            String capName = proName.substring(0, 1).toUpperCase() + proName.substring(1).toLowerCase();
            context.append("\n--- RECHERCHE STATS JOUEUR PRO (" + capName + ") ---\n");
            context.append(leaguepediaService.getPlayerStats("LEC", capName));
            context.append(leaguepediaService.getPlayerStats("LFL", capName));
            context.append(leaguepediaService.getPlayerStats("LCK", capName));
            context.append(leaguepediaService.getPlayerStats("LPL", capName));
        }
    }

    private void injectPlayerContext(SlashCommandInteractionEvent event, StringBuilder context, String question, String discordId, boolean isFlex, boolean isEsport) {
        Pattern pattern = Pattern.compile("<@!?(\\d+)>");
        Matcher matcher = pattern.matcher(question);
        boolean hasMentions = false;

        while (matcher.find()) {
            hasMentions = true;
            String mentionedId = matcher.group(1);
            String puuid = db.getPuuid(mentionedId);
            User mentionedUser = event.getJDA().retrieveUserById(mentionedId).complete();
            String userName = (mentionedUser != null) ? mentionedUser.getName() : "Joueur";

            if (puuid != null) {
                appendFullPlayerInfo(context, puuid, userName, isFlex, question);
            } else {
                context.append("\nL'utilisateur ").append(userName).append(" n'a pas lié son compte Riot.\n");
            }
        }

        if (!hasMentions && !isEsport) {
            String myPuuid = db.getPuuid(discordId);
            if (myPuuid != null) {
                appendFullPlayerInfo(context, myPuuid, "Moi", isFlex, question);
            } else {
                context.append("\nL'utilisateur qui parle n'a pas lié son compte Riot.\n");
            }
        }
    }

    private void appendFullPlayerInfo(StringBuilder context, String puuid, String name, boolean isFlex, String question) {
        try {
            context.append("\n--- Infos sur ").append(name).append(" ---\n");
            
            // Ranks
            Map<String, RiotService.RankInfo> ranks = riotService.getAllRanks(puuid);
            RiotService.RankInfo solo = ranks.get("SOLO");
            RiotService.RankInfo flex = ranks.get("FLEX");
            String rankToShow = isFlex ? "FLEX" : "SOLO/DUO";
            RiotService.RankInfo rankInfo = isFlex ? flex : solo;
            context.append("Rang ").append(rankToShow).append(": ").append(rankInfo.tier).append(" ").append(rankInfo.rank)
                   .append(" (").append(rankInfo.lp).append(" LP), Winrate: ")
                   .append(rankInfo.wins).append("W/").append(rankInfo.losses).append("L.\n");

            // History
            String lowerQ = question.toLowerCase();
            if (lowerQ.contains("soloq") || lowerQ.contains("solo q")) {
                context.append("\n--- HISTORIQUE SOLOQ (5 dernières) ---\n");
                context.append(fetchAndFormatMatchHistory(puuid, RiotService.QUEUE_SOLOQ, 5));
            } else if (lowerQ.contains("flex")) {
                context.append("\n--- HISTORIQUE FLEX (5 dernières) ---\n");
                context.append(fetchAndFormatMatchHistory(puuid, RiotService.QUEUE_FLEX, 5));
            } else if (lowerQ.contains("clash")) {
                context.append("\n--- HISTORIQUE CLASH (5 dernières) ---\n");
                String classic = fetchAndFormatMatchHistory(puuid, RiotService.QUEUE_CLASH, 5);
                String aram = fetchAndFormatMatchHistory(puuid, RiotService.QUEUE_ARAM_CLASH, 5);
                if (classic.contains("Aucune") && aram.contains("Aucune")) context.append("Aucune partie trouvée.");
                else context.append(classic).append("\n").append(aram);
            }
        } catch (Exception e) {
            context.append("Erreur lors de la récupération des infos : ").append(e.getMessage());
        }
    }

    private String fetchAndFormatMatchHistory(String puuid, Integer queueId, int limit) {
        try {
            List<String> matchIds = riotService.getMatchHistoryIds(puuid, queueId, limit);
            if (matchIds.isEmpty()) return "Aucune partie trouvée dans ce mode.\n";

            List<CompletableFuture<String>> futures = matchIds.stream()
                .map(matchId -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return riotService.getFullMatchDetails(matchId, puuid);
                    } catch (Exception e) {
                        return "Erreur match " + matchId;
                    }
                }, apiExecutor))
                .collect(Collectors.toList());

            return futures.stream().map(CompletableFuture::join).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Erreur API Riot: " + e.getMessage() + "\n";
        }
    }

    private void sendLongMessage(SlashCommandInteractionEvent event, String content) {
        if (content.length() > 1900) {
            for (int i = 0; i < content.length(); i += 1900) {
                event.getHook().sendMessage(content.substring(i, Math.min(i + 1900, content.length()))).queue();
            }
        } else {
            event.getHook().sendMessage(content).queue();
        }
    }

    // --- OTHER COMMANDS (Simplified) ---

    private void handleLinkCommand(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        String riotIdInput = event.getOption("riot_id").getAsString();
        String[] parts = riotIdInput.split("#");
        if (parts.length != 2) {
            event.getHook().sendMessage("Format invalide. Utilise : GameName#TagLine").queue();
            return;
        }
        try {
            String puuid = riotService.getPuuid(parts[0], parts[1]);
            db.saveUser(event.getUser().getId(), puuid, riotIdInput);
            db.clearChatHistory(event.getUser().getId());
            event.getHook().sendMessage("Compte lié avec succès : " + riotIdInput).queue();
        } catch (Exception e) {
            event.getHook().sendMessage("Erreur lors de la liaison : " + e.getMessage()).queue();
        }
    }

    private void handleRankCommand(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        User targetUser = event.getOption("membre").getAsUser();
        String puuid = db.getPuuid(targetUser.getId());
        if (puuid == null) {
            event.getHook().sendMessage("❌ Ce membre n'a pas lié son compte Riot.").queue();
            return;
        }
        new Thread(() -> {
            try {
                RiotService.RankInfo rank = riotService.getRank(puuid);
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
        }).start();
    }

    private void handleNewAskCommand(SlashCommandInteractionEvent event) {
        db.clearChatHistory(event.getUser().getId());
        event.reply("🧠 Mémoire effacée ! On repart sur de nouvelles bases.").setEphemeral(true).queue();
    }

    private void handleHelpCommand(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🤖 Aide - Commandes du Bot");
        embed.setColor(Color.CYAN);
        embed.setDescription("Voici la liste des commandes disponibles pour interagir avec le bot :");
        embed.addField("🔗 `/link [riot_id]`", "Lie ton compte Riot à ton compte Discord.", false);
        embed.addField("📊 `/rank [membre]`", "Affiche le rang, les LP et le winrate d'un membre.", false);
        embed.addField("🏆 `/leaderboard`", "Affiche le classement de tous les membres enregistrés.", false);
        embed.addField("🧠 `/ask [question]`", "Pose une question à l'IA. Mentionne un joueur pour analyser son profil ou ses games.", false);
        embed.addField("🔄 `/new-ask`", "Efface la mémoire de ta conversation avec l'IA.", false);
        embed.addField("🔎 `/analyze [question]`", "L'IA analyse en détail ta dernière partie jouée.", false);
        embed.setFooter("Bot développé avec ❤️ pour les invocateurs.");
        event.replyEmbeds(embed.build()).queue();
    }

    // --- UTILS ---

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

    private int calculateEloScore(String tier, String rank, int lp) {
        int baseScore = 0;
        switch (tier.toUpperCase()) {
            case "IRON": baseScore = 0; break;
            case "BRONZE": baseScore = 400; break;
            case "SILVER": baseScore = 800; break;
            case "GOLD": baseScore = 1200; break;
            case "PLATINUM": baseScore = 1600; break;
            case "EMERALD": baseScore = 2000; break;
            case "DIAMOND": baseScore = 2400; break;
            case "MASTER": baseScore = 2800; break;
            case "GRANDMASTER": baseScore = 3200; break;
            case "CHALLENGER": baseScore = 3600; break;
            default: return -1;
        }
        int divisionScore = 0;
        if (baseScore < 2800) {
            switch (rank) {
                case "I": divisionScore = 300; break;
                case "II": divisionScore = 200; break;
                case "III": divisionScore = 100; break;
                case "IV": divisionScore = 0; break;
            }
        }
        return baseScore + divisionScore + lp;
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