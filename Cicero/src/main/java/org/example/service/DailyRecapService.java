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
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DailyRecapService {
    private final DatabaseManager db;
    private final RiotService riotService;
    private final JDA jda;
    private final MistralService mistralService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    // private JSONObject benchmarks; (not needed anymore)

    private static final String RECAP_BANNER = "https://images.contentstack.io/v3/assets/blt731acb42bb3d1659/bltacc406a1643cf5cd/5e98753f18a3221d65d69303/2020_Worlds_Trophy_Header.jpg";
    private static final String ROLE_WEEKLY_MVP = "1468270322061938769";
    private static final String ROLE_MONTHLY_MVP = "1468270819728691291";

    public DailyRecapService(DatabaseManager db, RiotService riotService, JDA jda, MistralService mistralService) {
        this.db = db;
        this.riotService = riotService;
        this.jda = jda;
        this.mistralService = mistralService;
        // loadBenchmarks(); (not needed anymore)
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
        // Nouvelle logique : on récupère tous les utilisateurs, on identifie leur "Main Account" (plus haut élo actuel),
        // et on calcule le score MVP sur ce compte uniquement.
        
        Map<String, List<DatabaseManager.UserRecord>> usersMap = db.getAllUsersGrouped();
        String bestDiscordId = null;
        double bestMvpScore = -1.0;
        
        // Structure pour stocker les stats du gagnant potentiel
        DatabaseManager.PeriodStats bestStats = null;

        for (Map.Entry<String, List<DatabaseManager.UserRecord>> entry : usersMap.entrySet()) {
            String discordId = entry.getKey();
            List<DatabaseManager.UserRecord> accounts = entry.getValue();
            
            // 1. Identifier le Main Account (Plus haut Elo actuel via Snapshot récent ou Rank actuel)
            DatabaseManager.UserRecord mainAccount = null;
            int highestElo = -1;
            
            for (DatabaseManager.UserRecord acc : accounts) {
                // On regarde le snapshot le plus récent pour estimer l'elo
                // Note : Idéalement on ferait un appel Riot, mais pour éviter le rate limit de masse ici, on se base sur le snapshot quotidien sauvegardé en base
                // Si pas de snapshot, on ignore ou on prend par défaut le 1er.
                DatabaseManager.SnapshotRecord snap = db.getSnapshot(acc.puuid);
                int elo = 0;
                if (snap != null) {
                    elo = RankUtils.calculateEloScore(snap.tier, snap.rank, snap.lp);
                }
                
                if (elo > highestElo) {
                    highestElo = elo;
                    mainAccount = acc;
                }
            }
            
            if (mainAccount == null && !accounts.isEmpty()) mainAccount = accounts.get(0); // Fallback
            if (mainAccount == null) continue;

            // 2. Récupérer les stats de ce compte sur la période
            DatabaseManager.PeriodStats stats = db.getAccountPeriodStats(mainAccount.puuid, fromDate);
            if (stats != null && stats.totalGames > 0) {
                // Calcul du score MVP (Formule standard)
                double winrate = (double)stats.totalWins / stats.totalGames * 100.0;
                double mvpScore = (stats.avgScore * 0.60) + (winrate * 0.30) + (Math.min(stats.totalGames, 20) * 2.0); // Cap volume plus haut pour hebdo/mensuel ? Laissons 20 pour l'instant.
                
                if (mvpScore > bestMvpScore) {
                    bestMvpScore = mvpScore;
                    bestDiscordId = discordId;
                    bestStats = stats;
                }
            }
        }

        if (bestDiscordId == null) {
            if (periodName.contains("(TEST)")) {
                channel.sendMessage("⚠️ Pas de données suffisantes pour élire le MVP de " + periodName).queue();
            }
            return;
        }

        // --- Attribution du rôle et Annonce ---
        
        Guild guild = channel.getGuild();
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            System.out.println("Rôle introuvable : " + roleId);
            return;
        }

        // Nettoyage des anciens rôles
        guild.findMembersWithRoles(role).onSuccess(members -> {
            for (Member member : members) {
                guild.removeRoleFromMember(member, role).queue();
            }
        });

        String finalDiscordId = bestDiscordId;
        DatabaseManager.PeriodStats finalStats = bestStats;

        guild.retrieveMemberById(finalDiscordId).queue(
            member -> {
                guild.addRoleToMember(member, role).queue();
                
                int winrate = (int) Math.round((finalStats.totalWins / (double) finalStats.totalGames) * 100);
                String narrativeLog = db.getPeriodNarrativeContext(finalDiscordId, fromDate);
                
                String context = "Période: " + periodName + " | Joueur: " + member.getEffectiveName() + 
                                 " | Games: " + finalStats.totalGames + " (" + winrate + "% WR) | Note Moyenne: " + 
                                 String.format("%.1f", finalStats.avgScore) + "/100\n\n" +
                                 "=== JOURNAL DE BORD DÉTAILLÉ ===\n" + narrativeLog;
                
                String aiSummary = "Un règne sans partage et une domination absolue !";
                try {
                    aiSummary = mistralService.runPeriodMvpChronicler(context);
                } catch (Exception e) {
                    System.err.println("Erreur IA MVP: " + e.getMessage());
                }

                // Construction de l'Embed
                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("🏆 JOUEUR DE " + periodName + " 🏆");
                eb.setColor(new Color(255, 215, 0)); // Doré
                eb.setDescription("Félicitations à <@" + finalDiscordId + "> qui est élu meilleur joueur de " + periodName.toLowerCase() + " !\n\n" +
                "📜 Le mot du Chroniqueur :\n*" + aiSummary + "*");

                InputStream is = getClass().getResourceAsStream("/banniere.jpg");
                if (is != null) {
                    eb.setImage("attachment://banniere.jpg");
                    channel.sendMessageEmbeds(eb.build())
                           .addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(is, "banniere.jpg"))
                           .queue();
                } else {
                    eb.setImage("https://media.giphy.com/media/l0HlHJGHe3yAMhdQY/giphy.gif"); // Fallback
                    channel.sendMessageEmbeds(eb.build()).queue();
                }
            },
            error -> System.out.println("Membre introuvable pour attribution rôle : " + finalDiscordId)
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

        Map<String, List<DatabaseManager.UserRecord>> usersMap = db.getAllUsersGrouped();
        if (usersMap.isEmpty()) return;

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📅 RÉCAPITULATIF QUOTIDIEN");
        embed.setDescription("Voici les performances des invocateurs sur les dernières 24h !");
        embed.setColor(new Color(47, 49, 54)); // Couleur sombre style Discord
        embed.setImage(RECAP_BANNER);
        embed.setFooter("Mis à jour à l'instant • Cicero Bot");
        embed.setTimestamp(java.time.Instant.now());

        List<UserGroupRecap> groups = new ArrayList<>();
        String todayDateString = ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_LOCAL_DATE);

        for (Map.Entry<String, List<DatabaseManager.UserRecord>> entry : usersMap.entrySet()) {
            String discordId = entry.getKey();
            List<DatabaseManager.UserRecord> accounts = entry.getValue();
            
            UserGroupRecap group = new UserGroupRecap();
            group.discordId = discordId;
            
            // --- LOGIQUE MAIN ACCOUNT ---
            // On identifie le compte avec l'élo le plus élevé pour le calcul du MVP
            DatabaseManager.UserRecord mainAccount = null;
            int highestElo = -1;
            
            // Pré-scan pour trouver le main account (via rank actuel ou snapshot)
            // Ici on va utiliser les données 'currentRank' qu'on récupérera dans la boucle ci-dessous
            // Mais pour simplifier, on va stocker les UserRecapData et trier après.
            
            int totalUserGames = 0;
            int totalUserWins = 0;
            int totalUserLosses = 0;
            int totalUserLpDiff = 0;
            double totalUserScoreSum = 0.0;
            Set<String> notableTraits = new HashSet<>();
            Set<String> dailyChampions = new HashSet<>();
            
            List<UserRecapData> userRecaps = new ArrayList<>();

            for (DatabaseManager.UserRecord user : accounts) {
                try {
                    // Temporisation pour éviter le Rate Limit (1.5s entre chaque requête utilisateur)
                    Thread.sleep(1500);

                    // 1. Récupérer le rang actuel
                    RankInfo currentRank = riotService.getRank(user.puuid, user.region);
                    if (currentRank == null) continue;

                    // 2. Récupérer le snapshot précédent
                    DatabaseManager.SnapshotRecord snapshot = db.getSnapshot(user.puuid);
                    
                    // Calcul des LP gagnés/perdus avec l'Elo Absolu
                    int lpDiff = 0;
                    boolean sameTierRank = false;
                    boolean hasSnapshot = (snapshot != null);
                    if (hasSnapshot) {
                        int oldElo = RankUtils.calculateEloScore(snapshot.tier, snapshot.rank, snapshot.lp);
                        int newElo = RankUtils.calculateEloScore(currentRank.tier, currentRank.rank, currentRank.lp);
                        lpDiff = newElo - oldElo;

                        if (currentRank.tier.equals(snapshot.tier) && currentRank.rank.equals(snapshot.rank)) {
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
                            
                            if (!analysis.has("target_player")) continue;
                            
                            JSONObject targetPlayer = analysis.getJSONObject("target_player");
                            gamesPlayed++;
                            boolean win = targetPlayer.optBoolean("win", false);
                            if (win) wins++; else losses++;
                            String champName = targetPlayer.optString("champion", "").toUpperCase();
                            dailyChampions.add(champName);
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
                            
                            double durationMin = analysis.getJSONObject("metadata").optLong("duration_sec", 1800) / 60.0;
                            
                            JSONObject playerJsonForCalc = analysis.getJSONObject("target_player");
                            playerJsonForCalc.put("k", playerJsonForCalc.optInt("kills"));
                            playerJsonForCalc.put("d", playerJsonForCalc.optInt("deaths"));
                            playerJsonForCalc.put("a", playerJsonForCalc.optInt("assists"));
                            
                            JSONObject scoreResult = ScoreCalculator.analyzePlayer(
                                playerJsonForCalc, 
                                currentRank.tier, 
                                durationMin, 
                                myPlayerCtx, 
                                oppPlayerCtx, 
                                enemyComp
                            );
                            
                            totalScore += scoreResult.getInt("math_score");
                            
                            // Capture des synergies
                            JSONArray synergies = scoreResult.optJSONArray("synergies");
                            if (synergies != null) {
                                for (int k = 0; k < synergies.length(); k++) {
                                    String reason = synergies.getJSONObject(k).optString("reason", "");
                                    notableTraits.add(reason.replaceAll("\\(.*?\\)", "").trim()); // On enlève les parenthèses pour faire propre
                                }
                            }

                        } catch (Exception e) {
                            System.err.println("Erreur analyse match " + matchId + ": " + e.getMessage());
                        }
                    }
                    
                    double averageScore = (gamesPlayed > 0) ? (totalScore / gamesPlayed) : 0.0;
                    double winrate = (gamesPlayed > 0) ? ((double)wins / gamesPlayed * 100.0) : 0.0;
                    
                    // Calcul du MVP Score
                    double mvpScore = (averageScore * 0.60) + (winrate * 0.30) + (Math.min(gamesPlayed, 5) * 2.0);
                    
                    // Sauvegarde en base
                    String dailyDetails = "Champions: " + String.join(", ", dailyChampions);
                    if (!notableTraits.isEmpty()) {
                        dailyDetails += " | Traits: " + String.join(", ", notableTraits);
                    }
                    db.saveDailyPerformance(user.puuid, user.discordId, todayDateString, gamesPlayed, wins, averageScore, lpDiff, mvpScore, "", dailyDetails);
                    
                    UserRecapData recapData = new UserRecapData(user, currentRank, wins, losses, lpDiff, sameTierRank, hasSnapshot, averageScore, mvpScore, "");
                    userRecaps.add(recapData);
                    group.accounts.add(recapData);
                    
                    // Agrégation pour le groupe (Toutes les games)
                    totalUserGames += gamesPlayed;
                    totalUserWins += wins;
                    totalUserLosses += losses;
                    totalUserLpDiff += lpDiff;
                    if (gamesPlayed > 0) {
                        totalUserScoreSum += (averageScore * gamesPlayed);
                    }

                    // 4. Mettre à jour le snapshot si demandé ou si c'est le premier
                    if (updateSnapshot || !hasSnapshot) {
                        db.saveSnapshot(user.puuid, user.discordId, currentRank.tier, currentRank.rank, currentRank.lp);
                    }

                } catch (Exception e) {
                    System.out.println("Erreur récap pour " + user.summonerName + ": " + e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("QUOTA")) {
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    }
                }
            }
            
            // --- LOGIQUE MVP (Filtrage Main Account) ---
            // On cherche le compte avec le plus haut Elo qui a joué au moins une game
            UserRecapData mainAccountData = null;
            int highestRecapElo = -1;
            
            for (UserRecapData data : userRecaps) {
                // On peut aussi considérer comme main account celui qui a le plus haut elo TOUT COURT, même s'il n'a pas joué
                // Mais pour le MVP du jour, il faut qu'il ait joué.
                // Si on veut exclure les smurfs du calcul MVP, on prend seulement le compte le plus haut classé.
                // On va dire que le compte "Main" est celui avec l'Elo le plus haut parmis TOUS les comptes du joueur.
                int elo = RankUtils.calculateEloScore(data.currentRank.tier, data.currentRank.rank, data.currentRank.lp);
                if (elo > highestRecapElo) {
                    highestRecapElo = elo;
                    mainAccountData = data;
                }
            }
            
            // Calcul du score MVP UNIQUEMENT sur le Main Account (s'il a joué)
            if (mainAccountData != null && mainAccountData.getTotalGames() > 0) {
                // Le score du groupe pour le classement MVP est celui du Main Account
                group.groupMvpScore = mainAccountData.mvpScore;
            } else {
                group.groupMvpScore = 0.0; // Pas éligible ou n'a joué que sur des comptes inférieurs (ce qui est bizarre si on prend le max elo des recaps, sauf si le 'main' n'a pas joué du tout)
                // Si le Main n'a pas joué mais qu'il a joué sur smurf, on pourrait vouloir l'afficher mais ne pas le compter pour le MVP ?
                // La consigne est : "ne prennent en compte que les comptes les plus hauts elo".
                // Donc si le Main n'a pas joué, le joueur n'est pas éligible au MVP via ses smurfs.
            }

            // Génération du résumé IA (Sur l'ensemble des games par contre, pour raconter la journée)
            if (totalUserGames > 0) {
                double globalAvgScore = totalUserScoreSum / totalUserGames;
                
                StringBuilder aiPrompt = new StringBuilder();
                aiPrompt.append("Joueur: <@").append(discordId).append("> | Total Games: ").append(totalUserGames)
                        .append(" (").append(totalUserWins).append("W/").append(totalUserLosses).append("L) | LP Diff Total: ").append(totalUserLpDiff)
                        .append(" | Note moyenne IA: ").append(String.format("%.1f", globalAvgScore)).append("/100");
                
                if (!notableTraits.isEmpty()) {
                    aiPrompt.append("\nFaits marquants détectés par l'algo : ").append(String.join(", ", notableTraits)).append("\n");
                }
                
                try {
                    group.globalAiSummary = mistralService.runDailyChronicler(aiPrompt.toString());
                } catch (Exception e) {
                    System.err.println("Erreur IA Chronicler pour " + discordId + ": " + e.getMessage());
                    group.globalAiSummary = "Pas de commentaire disponible.";
                }
            }
            
            groups.add(group);
        }

        // Tri par MVP Score de groupe décroissant
        groups.sort((g1, g2) -> Double.compare(g2.groupMvpScore, g1.groupMvpScore));

        // Trouver le MVP du jour (basé sur le score de groupe)
        UserGroupRecap mvpGroup = null;
        if (!groups.isEmpty() && groups.get(0).groupMvpScore > 0) {
            mvpGroup = groups.get(0);
        }

        StringBuilder sb = new StringBuilder();
        for (UserGroupRecap group : groups) {
            if (sb.length() > 3000) {
                embed.setDescription(sb.toString());
                channel.sendMessageEmbeds(embed.build()).queue();
                sb.setLength(0);
                embed = new EmbedBuilder().setColor(new Color(47, 49, 54));
            }
            
            // On liste tous les comptes du joueur
            for (UserRecapData data : group.accounts) {
                String rankEmoji = RankUtils.getRankEmoji(data.currentRank.tier);
                
                String lpString = "";
                if (data.hasSnapshot) {
                    if (data.lpDiff > 0) lpString = " `+" + data.lpDiff + " LP` 📈";
                    else if (data.lpDiff < 0) lpString = " `" + data.lpDiff + " LP` 📉";
                    else lpString = " `0 LP` ➖";
                    
                    if (!data.sameTierRank) {
                         lpString += " *(Rang Modifié)*";
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
                
                if (data.getTotalGames() > 0) {
                    sb.append("> ").append(getWinrateProgressBar(data.getWinrate())).append(" **").append(data.wins).append("W** / **").append(data.losses).append("L** (").append(data.getWinrate()).append("%)\n");
                    sb.append("> 📊 Note IA : **").append(String.format("%.1f", data.averageScore)).append("/100**\n");
                } else {
                    sb.append("> 💤 *Pas de game aujourd'hui*\n");
                }
            }
            
            // Le commentaire IA global s'affiche UNE SEULE FOIS sous le groupe du joueur
            if (group.globalAiSummary != null && !group.globalAiSummary.isEmpty()) {
                sb.append("> 🎙️ *« ").append(group.globalAiSummary).append(" »*\n");
            }
            sb.append("\n");
        }

        // Affichage du MVP
        if (mvpGroup != null) {
            // On récupère le nom d'un des comptes pour l'affichage (le premier par exemple)
            String mvpName = !mvpGroup.accounts.isEmpty() ? mvpGroup.accounts.get(0).user.summonerName : "Inconnu";
            
            sb.append("\n\n🏆 **LE MVP DU JOUR** 🏆\n");
            sb.append("Félicitations à <@").append(mvpGroup.discordId).append("> qui domine le serveur aujourd'hui !\n");
            sb.append("> *Score MVP Global (Main Account) : **").append(String.format("%.1f", mvpGroup.groupMvpScore)).append("** ");
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

    private static class UserGroupRecap {
        String discordId;
        double groupMvpScore;
        List<UserRecapData> accounts = new ArrayList<>();
        String globalAiSummary;
    }
}