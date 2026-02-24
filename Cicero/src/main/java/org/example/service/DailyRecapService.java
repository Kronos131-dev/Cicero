package org.example.service;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.example.DatabaseManager;
import org.example.service.RiotService.RankInfo;
import org.example.util.RankUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static final String RECAP_BANNER = "https://images.contentstack.io/v3/assets/blt731acb42bb3d1659/bltacc406a1643cf5cd/5e98753f18a3221d65d69303/2020_Worlds_Trophy_Header.jpg";

    public DailyRecapService(DatabaseManager db, RiotService riotService, JDA jda) {
        this.db = db;
        this.riotService = riotService;
        this.jda = jda;
        
        scheduleDailyRecap();
    }

    private void scheduleDailyRecap() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Paris"));
        ZonedDateTime nextRun = now.withHour(0).withMinute(59).withSecond(0);
        if (now.compareTo(nextRun) > 0) {
            nextRun = nextRun.plusDays(1);
        }

        long initialDelay = Duration.between(now, nextRun).getSeconds();
        // Au récap programmé (00:59), on met à jour le snapshot pour le lendemain
        scheduler.scheduleAtFixedRate(() -> sendRecap(true), initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    private void sendRecap(boolean updateSnapshot) {
        // On attend que le JDA soit prêt si ce n'est pas le cas
        try {
            jda.awaitReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        String salonId = db.getConfig("RECAP_CHANNEL_ID");
        if (salonId == null) {
            System.out.println("Aucun salon configuré pour le récap quotidien (RECAP_CHANNEL_ID manquant en DB).");
            return;
        }

        TextChannel channel = jda.getTextChannelById(salonId);
        if (channel == null) {
            System.out.println("Salon introuvable pour le récap quotidien: " + salonId + ". Vérifiez que le bot a accès à ce salon.");
            return;
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

                // 3. Récupérer les matchs des dernières 24h
                String historyJson = riotService.getMatchHistorySummary(user.puuid, user.region, RiotService.QUEUE_SOLOQ, 20);
                int wins = 0;
                int losses = 0;

                if (historyJson != null && historyJson.startsWith("[")) {
                    JSONArray matches = new JSONArray(historyJson);
                    long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

                    for (int i = 0; i < matches.length(); i++) {
                        JSONObject match = matches.getJSONObject(i);
                        if (match.getLong("date") > oneDayAgo) {
                            if (match.getBoolean("win")) wins++;
                            else losses++;
                        }
                    }
                }
                
                recapList.add(new UserRecapData(user, currentRank, wins, losses, lpDiff, sameTierRank, hasSnapshot));

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

        // Tri par nombre de games décroissant
        recapList.sort(Comparator.comparingInt(UserRecapData::getTotalGames).reversed());

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
            } else {
                sb.append("> 💤 *Pas de game aujourd'hui*\n");
            }
            sb.append("\n");
        }

        if (sb.length() > 0) {
            embed.setDescription(sb.toString());
            channel.sendMessageEmbeds(embed.build()).queue();
        }
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

        public UserRecapData(DatabaseManager.UserRecord user, RankInfo currentRank, int wins, int losses, int lpDiff, boolean sameTierRank, boolean hasSnapshot) {
            this.user = user;
            this.currentRank = currentRank;
            this.wins = wins;
            this.losses = losses;
            this.lpDiff = lpDiff;
            this.sameTierRank = sameTierRank;
            this.hasSnapshot = hasSnapshot;
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