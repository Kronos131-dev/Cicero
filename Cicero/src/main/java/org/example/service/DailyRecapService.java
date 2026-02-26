package org.example.service;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.example.DatabaseManager;
import org.example.service.RiotService.RankInfo;
import org.example.util.RankUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DailyRecapService {
    private final DatabaseManager db;
    private final RiotService riotService;
    private final JDA jda;
    private final MistralService mistralService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private JSONObject benchmarks;

    private static final String RECAP_BANNER = "https://images.contentstack.io/v3/assets/blt731acb42bb3d1659/bltacc406a1643cf5cd/5e98753f18a3221d65d69303/2020_Worlds_Trophy_Header.jpg";
    private static final String ROLE_WEEKLY_MVP = "1468270322061938769";
    private static final String ROLE_MONTHLY_MVP = "1468270819728691291";

    public DailyRecapService(DatabaseManager db, RiotService riotService, JDA jda, MistralService mistralService) {
        this.db = db;
        this.riotService = riotService;
        this.jda = jda;
        this.mistralService = mistralService;
        loadBenchmarks();
        scheduleDailyRecap();
        
        // --- TEST AU DÉMARRAGE ---
        // Lance les 3 messages de récap/MVP au démarrage pour tester dans le salon spécifié
        //runStartupTests("1465328163210002574");
    }

    private void runStartupTests(String testChannelId) {
        // On utilise un thread séparé pour ne pas bloquer le constructeur
        new Thread(() -> {
            try {
                // Attendre que JDA soit prêt
                jda.awaitReady();
                
                TextChannel channel = jda.getTextChannelById(testChannelId);
                if (channel == null) {
                    System.out.println("Salon de test introuvable : " + testChannelId);
                    return;
                }

                System.out.println("Lancement des tests de récap dans le salon : " + channel.getName());

                // 1. Test Récap Quotidien
                // On force l'envoi dans le salon de test en modifiant temporairement la config ou en surchargeant la méthode
                // Pour faire simple ici, on va appeler sendRecap mais il faut qu'il utilise le bon channel.
                // Comme sendRecap lit la config, on va temporairement sauvegarder l'ID de test si nécessaire, 
                // ou mieux, on crée une version surchargée de sendRecap qui prend un channel en paramètre.
                // Pour l'instant, je vais modifier sendRecap pour qu'il accepte un channel optionnel.
                sendRecap(false, channel); 

                Thread.sleep(5000); // Pause pour la lisibilité

                // 2. Test MVP Semaine
                String lastWeekDate = LocalDate.now().minusDays(7).toString();
                processPeriodMVP(channel, lastWeekDate, ROLE_WEEKLY_MVP, "LA SEMAINE (TEST)");

                Thread.sleep(5000);

                // 3. Test MVP Mois
                String lastMonthDate = LocalDate.now().minusMonths(1).withDayOfMonth(1).toString();
                processPeriodMVP(channel, lastMonthDate, ROLE_MONTHLY_MVP, "CE MOIS-CI (TEST)");

            } catch (Exception e) {
                System.err.println("Erreur lors des tests de démarrage : " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void loadBenchmarks() {
        try (InputStream is = getClass().getResourceAsStream("/benchmarks.json")) {
            if (is == null) {
                System.err.println("Impossible de charger benchmarks.json");
                this.benchmarks = new JSONObject();
                return;
            }
            String jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            this.benchmarks = new JSONObject(jsonText);
        } catch (Exception e) {
            System.err.println("Erreur chargement benchmarks: " + e.getMessage());
            this.benchmarks = new JSONObject();
        }
    }

    private void scheduleDailyRecap() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Paris"));
        
        // --- Scheduler Quotidien (23h45) ---
        ZonedDateTime nextRunDaily = now.withHour(23).withMinute(45).withSecond(0);
        if (now.compareTo(nextRunDaily) > 0) {
            nextRunDaily = nextRunDaily.plusDays(1);
        }
        long initialDelayDaily = Duration.between(now, nextRunDaily).getSeconds();
        scheduler.scheduleAtFixedRate(() -> sendRecap(true, null), initialDelayDaily, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

        // --- Scheduler Hebdomadaire (Lundi 00h01) ---
        ZonedDateTime nextRunWeekly = now.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
                                         .withHour(0).withMinute(1).withSecond(0);
        if (now.compareTo(nextRunWeekly) >= 0) {
             nextRunWeekly = nextRunWeekly.plusWeeks(1);
        }
        long initialDelayWeekly = Duration.between(now, nextRunWeekly).getSeconds();
        scheduler.scheduleAtFixedRate(() -> {
            String salonId = db.getConfig("RECAP_CHANNEL_ID");
            if (salonId != null) {
                TextChannel channel = jda.getTextChannelById(salonId);
                if (channel != null) {
                    String fromDate = LocalDate.now().minusDays(7).toString();
                    processPeriodMVP(channel, fromDate, ROLE_WEEKLY_MVP, "LA SEMAINE");
                }
            }
        }, initialDelayWeekly, TimeUnit.DAYS.toSeconds(7), TimeUnit.SECONDS);

        // --- Scheduler Mensuel (1er du mois 00h02) ---
        scheduleMonthlyTask();
    }

    private void scheduleMonthlyTask() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Paris"));
        ZonedDateTime nextRunMonthly = now.with(TemporalAdjusters.firstDayOfNextMonth())
                                          .withHour(0).withMinute(2).withSecond(0);
        
        long initialDelayMonthly = Duration.between(now, nextRunMonthly).getSeconds();
        
        scheduler.schedule(() -> {
            try {
                String salonId = db.getConfig("RECAP_CHANNEL_ID");
                if (salonId != null) {
                    TextChannel channel = jda.getTextChannelById(salonId);
                    if (channel != null) {
                        // Le 1er du mois, on regarde le mois précédent
                        String fromDate = LocalDate.now().minusMonths(1).withDayOfMonth(1).toString();
                        processPeriodMVP(channel, fromDate, ROLE_MONTHLY_MVP, "CE MOIS-CI");
                    }
                }
            } finally {
                // Reprogrammer pour le mois suivant
                scheduleMonthlyTask();
            }
        }, initialDelayMonthly, TimeUnit.SECONDS);
    }

    private void processPeriodMVP(TextChannel channel, String fromDate, String roleId, String periodName) {
        List<String> winners = db.getBestPlayersOfPeriod(fromDate);
        if (winners.isEmpty()) {
            // Pour le test, on peut vouloir savoir s'il n'y a pas de gagnant
            if (periodName.contains("(TEST)")) {
                channel.sendMessage("⚠️ Pas de données suffisantes pour élire le MVP de " + periodName).queue();
            }
            return;
        }

        Guild guild = channel.getGuild();
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            System.out.println("Rôle introuvable : " + roleId);
            if (periodName.contains("(TEST)")) {
                channel.sendMessage("⚠️ Rôle introuvable pour le test : " + roleId).queue();
            }
            return;
        }

        // Nettoyage des anciens rôles
        guild.findMembersWithRoles(role).onSuccess(members -> {
            for (Member member : members) {
                guild.removeRoleFromMember(member, role).queue();
            }
        });

        // Attribution aux gagnants et préparation de l'annonce
        // On prend le premier gagnant (le meilleur) pour l'annonce principale
        String discordId = winners.get(0);
        
        guild.retrieveMemberById(discordId).queue(
            member -> {
                guild.addRoleToMember(member, role).queue();
                
                // Récupération des stats pour l'IA
                DatabaseManager.PeriodStats stats = db.getPlayerPeriodStats(discordId, fromDate);
                String aiSummary = "Un règne sans partage et une domination absolue !"; // Fallback
                if (stats != null) {
                    int winrate = (int) Math.round((stats.totalWins / (double) stats.totalGames) * 100);
                    String context = "Période: " + periodName + " | Joueur: " + member.getEffectiveName() + 
                                     " | Games: " + stats.totalGames + " (" + winrate + "% WR) | Note Moyenne IA: " + 
                                     String.format("%.1f", stats.avgScore) + "/100 | Score MVP: " + String.format("%.1f", stats.avgMvpScore);
                    
                    // APPEL À L'IA
                    try {
                        aiSummary = mistralService.runPeriodMvpChronicler(context);
                    } catch (Exception e) {
                        System.err.println("Erreur IA MVP: " + e.getMessage());
                    }
                }

                // Construction de l'Embed
                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("🏆 JOUEUR DE " + periodName + " 🏆");
                eb.setColor(new Color(255, 215, 0)); // Doré
                eb.setDescription("Félicitations à <@" + discordId + "> qui est élu meilleur joueur de " + periodName.toLowerCase() + " !\n\n" +
                "📜 Le mot du Chroniqueur :\n*" + aiSummary + "*");

                // Ajout de l'image depuis les ressources (JDA gérera la fermeture du flux)
                InputStream is = getClass().getResourceAsStream("/banniere.jpg");
                if (is != null) {
                    eb.setImage("attachment://banniere.jpg");
                    channel.sendMessageEmbeds(eb.build())
                           .addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(is, "banniere.jpg"))
                           .queue();
                } else {
                    System.err.println("Image introuvable dans le classpath : /banniere.jpg");
                    eb.setImage("https://media.giphy.com/media/l0HlHJGHe3yAMhdQY/giphy.gif"); // Fallback
                    channel.sendMessageEmbeds(eb.build()).queue();
                }
            },
            error -> System.out.println("Membre introuvable pour attribution rôle : " + discordId)
        );
    }

    private void sendRecap(boolean updateSnapshot, TextChannel targetChannel) {
        // On attend que le JDA soit prêt si ce n'est pas le cas
        try {
            jda.awaitReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        TextChannel channel = targetChannel;
        if (channel == null) {
            String salonId = db.getConfig("RECAP_CHANNEL_ID");
            if (salonId == null) {
                System.out.println("Aucun salon configuré pour le récap quotidien (RECAP_CHANNEL_ID manquant en DB).");
                return;
            }

            channel = jda.getTextChannelById(salonId);
            if (channel == null) {
                System.out.println("Salon introuvable pour le récap quotidien: " + salonId + ". Vérifiez que le bot a accès à ce salon.");
                return;
            }
        }

        List<DatabaseManager.UserRecord> users = db.getAllUsers();
        if (users.isEmpty()) return;

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📅 RÉCAPITULATIF QUOTIDIEN");
        embed.setDescription("Voici les performances des invocateurs sur les dernières 24h !");
        embed.setColor(new Color(47, 49, 54)); // Couleur sombre style Discord
        embed.setImage(RECAP_BANNER);
        embed.setFooter("Mis à jour à l'instant • Cicero Bot");
        embed.setTimestamp(java.time.Instant.now());

        List<UserRecapData> recapList = new ArrayList<>();
        String todayDateString = ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_LOCAL_DATE);

        for (DatabaseManager.UserRecord user : users) {
            try {
                // Temporisation pour éviter le Rate Limit (1.5s entre chaque requête utilisateur)
                Thread.sleep(1500);

                // 1. Récupérer le rang actuel
                RankInfo currentRank = riotService.getRank(user.puuid, user.region);
                if (currentRank == null) continue;

                // 2. Récupérer le snapshot précédent
                DatabaseManager.SnapshotRecord snapshot = db.getSnapshot(user.discordId);
                
                // Calcul des LP gagnés/perdus
                int lpDiff = 0;
                boolean sameTierRank = false;
                boolean hasSnapshot = (snapshot != null);
                
                if (hasSnapshot) {
                    if (currentRank.tier.equals(snapshot.tier) && currentRank.rank.equals(snapshot.rank)) {
                        lpDiff = currentRank.lp - snapshot.lp;
                        sameTierRank = true;
                    }
                }

                // 3. Récupérer UNIQUEMENT les matchs des dernières 24h
                List<String> matchIds = riotService.getMatchIdsLast24h(user.puuid, user.region);
                int wins = 0;
                int losses = 0;
                double totalScore = 0.0;
                int gamesPlayed = 0;
                
                // Calcul de la date limite (24h avant maintenant)
                long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

                for (String matchId : matchIds) {
                    try {
                        Thread.sleep(3000); // Anti-Rate Limit
                        
                        // Vérification de la date de la game
                        long gameCreation = riotService.getGameCreationTime(matchId, user.region);
                        if (gameCreation > 0 && gameCreation < oneDayAgo) {
                            System.out.println("Fin des games de 24h pour " + user.summonerName);
                            break; // On stoppe l'analyse de ce joueur, on a fini sa journée !
                        }

                        MatchDataExtractor.FullContext fullContext = riotService.getMatchContext(matchId, user.region);
                        while (fullContext == null) {
                            System.out.println("⚠️ Quota atteint ou erreur sur " + matchId + ". Pause de 2 minutes...");
                            Thread.sleep(125000);
                            fullContext = riotService.getMatchContext(matchId, user.region);
                        }
                        String analysisStr = riotService.getMatchAnalysis(matchId, user.puuid, user.region);
                        if (analysisStr == null || analysisStr.startsWith("[")) continue; 
                        
                        JSONObject analysis = new JSONObject(analysisStr);
                        
                        // CORRECTION DU BUG : On ne cherche plus l'objet "info", il n'existe pas ici !
                        if (!analysis.has("target_player")) continue;
                        
                        JSONObject targetPlayer = analysis.getJSONObject("target_player");
                        gamesPlayed++;
                        boolean win = targetPlayer.optBoolean("win", false);
                        if (win) wins++; else losses++;
                        String champName = targetPlayer.optString("champion", "").toUpperCase();
                        MatchDataExtractor.PlayerContext myPlayerCtx = fullContext.players.get(champName);
                        if (myPlayerCtx == null) {
                            System.err.println("PlayerContext introuvable pour le champion: " + champName);
                            continue;
                        }
                        
                        MatchDataExtractor.PlayerContext oppPlayerCtx = null;
                        for (MatchDataExtractor.PlayerContext p : fullContext.players.values()) {
                            if (p.teamId != myPlayerCtx.teamId && p.role.equals(myPlayerCtx.role)) {
                                oppPlayerCtx = p;
                                break;
                            }
                        }
                        if (oppPlayerCtx == null) oppPlayerCtx = new MatchDataExtractor.PlayerContext();
                        
                        MatchDataExtractor.TeamCompositionProfile enemyComp = (myPlayerCtx.teamId == 100) ? fullContext.redTeamComp : fullContext.blueTeamComp;
                        
                        // CORRECTION DU BUG : La durée est dans "metadata" -> "duration_sec" dans ce JSON spécifique
                        double durationMin = analysis.getJSONObject("metadata").optLong("duration_sec", 1800) / 60.0;
                        
                        JSONObject playerJsonForCalc = analysis.getJSONObject("target_player");
                        playerJsonForCalc.put("k", playerJsonForCalc.optInt("kills"));
                        playerJsonForCalc.put("d", playerJsonForCalc.optInt("deaths"));
                        playerJsonForCalc.put("a", playerJsonForCalc.optInt("assists"));
                        
                        JSONObject scoreResult = ScoreCalculator.analyzePlayer(
                            playerJsonForCalc, 
                            benchmarks, 
                            currentRank.tier, 
                            durationMin, 
                            myPlayerCtx, 
                            oppPlayerCtx, 
                            enemyComp
                        );
                        
                        totalScore += scoreResult.getInt("math_score");

                    } catch (Exception e) {
                        System.err.println("Erreur analyse match " + matchId + ": " + e.getMessage());
                    }
                }
                
                double averageScore = (gamesPlayed > 0) ? (totalScore / gamesPlayed) : 0.0;
                double winrate = (gamesPlayed > 0) ? ((double)wins / gamesPlayed * 100.0) : 0.0;
                
                // Calcul du MVP Score
                double mvpScore = (averageScore * 0.60) + (winrate * 0.30) + (Math.min(gamesPlayed, 5) * 2.0);

                // Appel à l'IA pour le résumé
                String aiSummary = "";
                if (gamesPlayed > 0) {
                    String context = "Joueur: " + user.summonerName + " | Games: " + gamesPlayed + " (" + wins + "W/" + losses + "L) | LP Diff: " + lpDiff + " | Note moyenne IA: " + String.format("%.1f", averageScore) + "/100";
                    try {
                        aiSummary = mistralService.runDailyChronicler(context);
                    } catch (Exception e) {
                        System.err.println("Erreur IA Chronicler pour " + user.summonerName + ": " + e.getMessage());
                        aiSummary = "Pas de commentaire disponible.";
                    }
                }
                
                // Sauvegarde en base
                db.saveDailyPerformance(user.discordId, todayDateString, gamesPlayed, wins, averageScore, lpDiff, mvpScore, aiSummary);
                
                recapList.add(new UserRecapData(user, currentRank, wins, losses, lpDiff, sameTierRank, hasSnapshot, averageScore, mvpScore, aiSummary));

                // 4. Mettre à jour le snapshot si demandé ou si c'est le premier
                if (updateSnapshot || !hasSnapshot) {
                    db.saveSnapshot(user.discordId, currentRank.tier, currentRank.rank, currentRank.lp);
                }

            } catch (Exception e) {
                System.out.println("Erreur récap pour " + user.summonerName + ": " + e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("QUOTA")) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        }

        // Tri par MVP Score décroissant
        recapList.sort((d1, d2) -> Double.compare(d2.mvpScore, d1.mvpScore));

        // Trouver le MVP du jour
        UserRecapData mvpUser = null;
        double bestMvpScore = -1.0;

        for (UserRecapData data : recapList) {
            if (data.getTotalGames() > 0 && data.mvpScore > bestMvpScore) {
                bestMvpScore = data.mvpScore;
                mvpUser = data;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (UserRecapData data : recapList) {
            String rankEmoji = RankUtils.getRankEmoji(data.currentRank.tier);
            
            String lpString = "";
            if (data.hasSnapshot) {
                if (data.sameTierRank) {
                    if (data.lpDiff > 0) lpString = " `+" + data.lpDiff + " LP` 📈";
                    else if (data.lpDiff < 0) lpString = " `" + data.lpDiff + " LP` 📉";
                    else lpString = " `0 LP` ➖";
                } else {
                    lpString = " `Rang modifié` 🔄";
                }
            } else {
                lpString = " `Nouveau suivi` 🆕";
            }

            sb.append(rankEmoji).append(" **").append(data.user.summonerName).append("**\n");
            
            if ("UNRANKED".equals(data.currentRank.tier)) {
                sb.append("> *Unranked*\n");
            } else {
                sb.append("> ").append(data.currentRank.tier).append(" ").append(data.currentRank.rank)
                  .append(" • **").append(data.currentRank.lp).append(" LP**").append(lpString).append("\n");
            }

            int totalGames = data.getTotalGames();
            if (totalGames > 0) {
                String winrateBar = getWinrateProgressBar(data.getWinrate());
                sb.append("> ").append(winrateBar).append(" **").append(data.wins).append("W** / **").append(data.losses).append("L** (").append(data.getWinrate()).append("%)\n");
                
                // Affichage Note IA et Résumé
                sb.append("> 📊 Note IA : **").append(String.format("%.1f", data.averageScore)).append("/100**\n");
                if (data.aiSummary != null && !data.aiSummary.isEmpty()) {
                    sb.append("> 🎙️ *« ").append(data.aiSummary).append(" »*\n");
                }
            } else {
                sb.append("> 💤 *Pas de game aujourd'hui*\n");
            }
            sb.append("\n");
        }

        // Affichage du MVP
        if (mvpUser != null) {
            sb.append("\n\n🏆 **LE MVP DU JOUR** 🏆\n");
            sb.append("Félicitations à **").append(mvpUser.user.summonerName).append("** qui domine le serveur aujourd'hui avec un MVP Score de **").append(String.format("%.1f", bestMvpScore)).append("** !");
        }

        if (sb.length() > 0) {
            embed.setDescription(sb.toString());
            channel.sendMessageEmbeds(embed.build()).queue();
        }
    }

    private void sendRecap(boolean updateSnapshot) {
        sendRecap(updateSnapshot, null);
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

    private static class UserRecapData {
        DatabaseManager.UserRecord user;
        RankInfo currentRank;
        int wins;
        int losses;
        int lpDiff;
        boolean sameTierRank;
        boolean hasSnapshot;
        double averageScore;
        double mvpScore;
        String aiSummary;

        public UserRecapData(DatabaseManager.UserRecord user, RankInfo currentRank, int wins, int losses, int lpDiff, boolean sameTierRank, boolean hasSnapshot, double averageScore, double mvpScore, String aiSummary) {
            this.user = user;
            this.currentRank = currentRank;
            this.wins = wins;
            this.losses = losses;
            this.lpDiff = lpDiff;
            this.sameTierRank = sameTierRank;
            this.hasSnapshot = hasSnapshot;
            this.averageScore = averageScore;
            this.mvpScore = mvpScore;
            this.aiSummary = aiSummary;
        }

        public int getTotalGames() {
            return wins + losses;
        }
        
        public int getWinrate() {
            int total = getTotalGames();
            return total > 0 ? (wins * 100 / total) : 0;
        }
    }
}