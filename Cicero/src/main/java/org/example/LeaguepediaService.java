package org.example;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class LeaguepediaService {
    private final OkHttpClient client = new OkHttpClient();
    private static final String BASE_URL = "https://lol.fandom.com/api.php";

    // --- 1. RÉSULTATS RÉCENTS ---
    public String getRecentResults(String leagueName) {
        try {
            String tournamentPattern = resolveLeaguePattern(leagueName);
            String whereClause = "SG.Tournament LIKE '%" + tournamentPattern + "%'";
            
            System.out.println("DEBUG: Searching results for " + tournamentPattern);
            
            JSONArray data = executeCargoQuery(
                "ScoreboardGames=SG",
                "SG.Tournament, SG.Team1, SG.Team2, SG.Winner, SG.DateTime_UTC, SG.Score1, SG.Score2, SG.GameId",
                whereClause,
                "SG.DateTime_UTC DESC",
                "10"
            );

            if (data.isEmpty()) return "Aucun résultat récent trouvé pour " + leagueName + ".";

            StringBuilder sb = new StringBuilder("📅 **Derniers résultats " + leagueName + " :**\n");
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.getJSONObject(i).getJSONObject("title");
                String t1 = row.getString("Team1");
                String t2 = row.getString("Team2");
                String winner = row.getString("Winner");
                String date = row.getString("DateTime_UTC").split(" ")[0];

                if ("1".equals(winner)) t1 = "**" + t1 + "**";
                else if ("2".equals(winner)) t2 = "**" + t2 + "**";

                sb.append("• [").append(date).append("] ").append(t1).append(" vs ").append(t2).append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Erreur Leaguepedia (Résultats) : " + e.getMessage();
        }
    }

    // --- 2. STATS CHAMPIONS (Méta) ---
    public String getChampionStats(String leagueName) {
        try {
            String tournamentPattern = resolveLeaguePattern(leagueName);
            
            System.out.println("DEBUG: Searching champion stats for " + tournamentPattern);

            JSONArray stats = executeCargoQuery(
                "ScoreboardPlayers=SP",
                "SP.Champion, COUNT(*)=Picks, SUM(SP.Win)=Wins", 
                "SP.Tournament LIKE '%" + tournamentPattern + "%'",
                "Picks DESC",
                "10",
                "SP.Champion"
            );

            if (stats.isEmpty()) return "Pas de données champions pour " + leagueName;

            StringBuilder sb = new StringBuilder("📊 **Méta " + leagueName + " (Top 10 Picks) :**\n");
            for (int i = 0; i < stats.length(); i++) {
                JSONObject row = stats.getJSONObject(i).getJSONObject("title");
                String champ = row.getString("Champion");
                int picks = row.getInt("Picks");
                int wins = row.optInt("Wins", 0);
                double winrate = (picks > 0) ? ((double) wins / picks * 100) : 0;
                
                sb.append(String.format("• **%s** : %d games | %.1f%% WR\n", champ, picks, winrate));
            }
            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Erreur Leaguepedia (Champions) : " + e.getMessage();
        }
    }

    // --- 3. STATS JOUEUR ---
    public String getPlayerStats(String leagueName, String playerName) {
        try {
            String tournamentPattern = resolveLeaguePattern(leagueName);

            // OPTIMISATION 1 : Formatter le nom (ex: "caliste" -> "Caliste") pour correspondre à la DB
            if (playerName != null && !playerName.isEmpty()) {
                playerName = playerName.substring(0, 1).toUpperCase() + playerName.substring(1);
            }

            System.out.println("DEBUG: Searching EXACT player '" + playerName + "' in " + tournamentPattern);

            // OPTIMISATION 2 : Utiliser "=" au lieu de "LIKE" pour le joueur (SP.Link)
            // Cela utilise l'index de la base de données -> Réponse instantanée
            String whereClause = "SP.Tournament LIKE '%" + tournamentPattern + "%' AND SP.Link = '" + playerName + "'";

            JSONArray data = executeCargoQuery(
                    "ScoreboardPlayers=SP",
                    "SP.Link, SP.Team, COUNT(*)=Games, SUM(SP.Kills)=Kills, SUM(SP.Deaths)=Deaths, SUM(SP.Assists)=Assists",
                    whereClause,
                    null, "1", "SP.Link, SP.Team"
            );

            if (data.isEmpty()) {
                return "❌ Introuvable : Le joueur **" + playerName + "** n'a pas joué en " + tournamentPattern + " (Vérifie l'orthographe !)";
            }

            JSONObject stats = data.getJSONObject(0).getJSONObject("title");
            int games = stats.getInt("Games");
            int k = stats.getInt("Kills");
            int d = stats.getInt("Deaths");
            int a = stats.getInt("Assists");
            double kda = (d > 0) ? (double)(k + a) / d : (k + a);

            // Requête secondaire pour les champions (aussi optimisée avec "=")
            JSONArray champs = executeCargoQuery(
                    "ScoreboardPlayers=SP", "SP.Champion, COUNT(*)=Games",
                    whereClause,
                    "Games DESC", "3", "SP.Champion"
            );

            StringBuilder sb = new StringBuilder();
            sb.append("👤 **Stats de ").append(stats.getString("Link")).append(" en ").append(leagueName).append("**\n");
            sb.append("🏆 Team : **").append(stats.getString("Team")).append("**\n");
            sb.append("🕹️ Games : ").append(games).append(" | KDA : **").append(String.format("%.2f", kda)).append("** (").append(k).append("/").append(d).append("/").append(a).append(")\n");

            sb.append("👑 Top Champs : ");
            for(int i=0; i<champs.length(); i++) {
                JSONObject c = champs.getJSONObject(i).getJSONObject("title");
                sb.append(c.getString("Champion")).append(" (").append(c.getString("Games")).append("), ");
            }
            // Enlever la dernière virgule
            if (sb.toString().endsWith(", ")) sb.setLength(sb.length() - 2);

            sb.append("\n\n");

            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Erreur Leaguepedia (Joueur) : " + e.getMessage();
        }
    }

    // --- 4. DÉTAILS D'UN MATCH PRÉCIS ---
    public String getMatchDetails(String gameId) {
        try {
            JSONArray players = executeCargoQuery(
                "ScoreboardPlayers=SP",
                "SP.Team, SP.Link, SP.Champion, SP.Kills, SP.Deaths, SP.Assists, SP.Gold, SP.CS",
                "SP.GameId = '" + gameId + "'",
                "SP.Team, SP.Role_Number", 
                "10"
            );

            if (players.isEmpty()) return "Détails introuvables pour le match " + gameId;

            StringBuilder sb = new StringBuilder("📄 **Détails du Match (" + gameId + ")**\n");
            String currentTeam = "";

            for (int i = 0; i < players.length(); i++) {
                JSONObject p = players.getJSONObject(i).getJSONObject("title");
                String team = p.getString("Team");

                if (!team.equals(currentTeam)) {
                    sb.append("\n**Équipe : ").append(team).append("**\n");
                    currentTeam = team;
                }

                sb.append(String.format("• %-15s (%-10s) | %s/%s/%s | %s CS | %s Gold\n",
                    p.getString("Link"), p.getString("Champion"),
                    p.getString("Kills"), p.getString("Deaths"), p.getString("Assists"),
                    p.getString("CS"), p.getString("Gold")
                ));
            }
            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "❌ Erreur Leaguepedia (Détails Match) : " + e.getMessage();
        }
    }

    // --- UTILITAIRES ---

    private String resolveLeaguePattern(String input) {
        input = input.toUpperCase();
        int year = LocalDate.now().getYear();
        
        // On utilise l'année réelle du système
        if (input.contains("LEC")) return "LEC " + year;
        if (input.contains("LFL")) return "LFL " + year;
        if (input.contains("LCK")) return "LCK " + year;
        if (input.contains("LPL")) return "LPL " + year;
        if (input.contains("LCS")) return "LCS " + year;
        if (input.contains("WORLDS")) return "World Championship " + year;
        if (input.contains("MSI")) return "Mid-Season Invitational " + year;
        
        return input; 
    }

    private JSONArray executeCargoQuery(String tables, String fields, String where, String orderBy, String limit) throws IOException {
        return executeCargoQuery(tables, fields, where, orderBy, limit, null);
    }

    private JSONArray executeCargoQuery(String tables, String fields, String where, String orderBy, String limit, String groupBy) throws IOException {
        // Pause anti-spam (Rate Limit)
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL).newBuilder();
        urlBuilder.addQueryParameter("action", "cargoquery");
        urlBuilder.addQueryParameter("format", "json");
        urlBuilder.addQueryParameter("tables", tables);
        urlBuilder.addQueryParameter("fields", fields);
        if (where != null) urlBuilder.addQueryParameter("where", where);
        if (orderBy != null) urlBuilder.addQueryParameter("order_by", orderBy);
        if (limit != null) urlBuilder.addQueryParameter("limit", limit);
        if (groupBy != null) urlBuilder.addQueryParameter("group_by", groupBy);

        // DEBUG: Afficher l'URL générée
        System.out.println("DEBUG API CALL: " + urlBuilder.build().toString());

        Request request = new Request.Builder().url(urlBuilder.build()).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP Error " + response.code());
            
            String body = response.body().string();
            JSONObject json = new JSONObject(body);
            
            if (!json.has("cargoquery")) {
                System.err.println("Leaguepedia API Error: " + body);
                return new JSONArray();
            }
            
            return json.getJSONArray("cargoquery");
        }
    }
}