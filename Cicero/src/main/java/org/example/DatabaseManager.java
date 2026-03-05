package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.json.JSONArray;
import org.sqlite.SQLiteConfig;

public class DatabaseManager {
    private final String url = "jdbc:sqlite:lolbot.db";
    private static final long SESSION_TIMEOUT_MS = 20 * 60 * 1000; // 20 minutes

    public DatabaseManager() {
        createTables();
    }

    private Connection connect() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000); // Attendre jusqu'à 5000ms si la DB est verrouillée
        config.setJournalMode(SQLiteConfig.JournalMode.WAL); // Write-Ahead Logging pour meilleure concurrence
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        return DriverManager.getConnection(url, config.toProperties());
    }

    private void createTables() {
        String sqlUsersV2 = "CREATE TABLE IF NOT EXISTS users_v2 (riot_puuid TEXT PRIMARY KEY, discord_id TEXT NOT NULL, summoner_name TEXT NOT NULL, region TEXT DEFAULT 'euw1', last_audit TEXT);";
        String sqlSnapshotsV2 = "CREATE TABLE IF NOT EXISTS user_snapshots_v2 (riot_puuid TEXT PRIMARY KEY, discord_id TEXT NOT NULL, tier TEXT, rank TEXT, lp INTEGER, timestamp INTEGER);";
        String sqlDailyV2 = "CREATE TABLE IF NOT EXISTS daily_performances_v2 (riot_puuid TEXT, discord_id TEXT, date TEXT, games_played INTEGER, wins INTEGER, average_score REAL, lp_diff INTEGER, mvp_score REAL, ai_summary TEXT, PRIMARY KEY(riot_puuid, date));";
        
        try (Connection conn = this.connect(); Statement stmt = conn.createStatement()) {
            // Création V2
            stmt.execute(sqlUsersV2); 
            stmt.execute(sqlSnapshotsV2); 
            stmt.execute(sqlDailyV2);
            
            // Migration des anciennes données (Ignore si déjà fait)
            try { stmt.execute("INSERT OR IGNORE INTO users_v2 SELECT riot_puuid, discord_id, summoner_name, region, last_audit FROM users;"); } catch (Exception ignored) {}
            try { stmt.execute("INSERT OR IGNORE INTO user_snapshots_v2 SELECT u.riot_puuid, s.discord_id, s.tier, s.rank, s.lp, s.timestamp FROM user_snapshots s JOIN users u ON s.discord_id = u.discord_id;"); } catch (Exception ignored) {}
            try { stmt.execute("INSERT OR IGNORE INTO daily_performances_v2 SELECT u.riot_puuid, d.discord_id, d.date, d.games_played, d.wins, d.average_score, d.lp_diff, d.mvp_score, d.ai_summary FROM daily_performances d JOIN users u ON d.discord_id = u.discord_id;"); } catch (Exception ignored) {}
            
            // Les autres tables (sessions, config) ne changent pas
            stmt.execute("CREATE TABLE IF NOT EXISTS chat_sessions (discord_id TEXT PRIMARY KEY, history TEXT NOT NULL, last_updated INTEGER NOT NULL);");
            stmt.execute("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT);");
        } catch (SQLException e) { System.out.println("Erreur init BDD: " + e.getMessage()); }
    }

    // --- GESTION AUDITS PERFORMANCE (Utilisé par PerformanceCommand) ---
    public synchronized void updateLastAudit(String discordId, String audit) {
        String sql = "UPDATE users_v2 SET last_audit = ? WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, audit);
            pstmt.setString(2, discordId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde audit: " + e.getMessage());
        }
    }

    public String getLastAudit(String discordId) {
        String sql = "SELECT last_audit FROM users_v2 WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("last_audit");
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture audit: " + e.getMessage());
        }
        return null;
    }

    // --- GESTION CONFIGURATION ---
    public synchronized void saveConfig(String key, String value) {
        String sql = "INSERT OR REPLACE INTO config(key, value) VALUES(?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, value);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde config: " + e.getMessage());
        }
    }

    public String getConfig(String key) {
        String sql = "SELECT value FROM config WHERE key = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture config: " + e.getMessage());
        }
        return null;
    }

    // --- GESTION UTILISATEURS ---
    public synchronized void saveUser(String discordId, String puuid, String summonerName, String region) {
        String sql = "INSERT OR REPLACE INTO users_v2(riot_puuid, discord_id, summoner_name, region) VALUES(?, ?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, puuid);
            pstmt.setString(2, discordId);
            pstmt.setString(3, summonerName);
            pstmt.setString(4, region != null ? region : "euw1");
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde user: " + e.getMessage());
        }
    }

    public void saveUser(String discordId, String puuid, String summonerName) {
        saveUser(discordId, puuid, summonerName, "euw1");
    }

    public UserRecord getUser(String discordId) {
        String sql = "SELECT riot_puuid, summoner_name, region FROM users_v2 WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new UserRecord(
                        discordId,
                        rs.getString("riot_puuid"),
                        rs.getString("summoner_name"),
                        rs.getString("region")
                );
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture user: " + e.getMessage());
        }
        return null;
    }

    // Renvoie TOUS les comptes d'un joueur
    public List<UserRecord> getUsers(String discordId) {
        List<UserRecord> users = new ArrayList<>();
        String sql = "SELECT riot_puuid, summoner_name, region FROM users_v2 WHERE discord_id = ?";
        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) users.add(new UserRecord(discordId, rs.getString("riot_puuid"), rs.getString("summoner_name"), rs.getString("region")));
        } catch (SQLException e) {}
        return users;
    }

    // Renvoie tous les utilisateurs groupés par Discord ID
    public Map<String, List<UserRecord>> getAllUsersGrouped() {
        Map<String, List<UserRecord>> map = new HashMap<>();
        String sql = "SELECT discord_id, riot_puuid, summoner_name, region FROM users_v2";
        try (Connection conn = this.connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String dId = rs.getString("discord_id");
                map.computeIfAbsent(dId, k -> new ArrayList<>()).add(new UserRecord(dId, rs.getString("riot_puuid"), rs.getString("summoner_name"), rs.getString("region")));
            }
        } catch (SQLException e) {}
        return map;
    }

    public synchronized void deleteUserAccount(String discordId, String riotIdInput) {
        String sql = "DELETE FROM users_v2 WHERE discord_id = ? AND summoner_name LIKE ?";
        try (Connection conn = this.connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, riotIdInput + "%"); // Gère les casses ou tags partiels
            pstmt.executeUpdate();
        } catch (SQLException e) {}
    }

    public String getPuuid(String discordId) {
        UserRecord user = getUser(discordId);
        return user != null ? user.puuid : null;
    }

    public List<UserRecord> getAllUsers() {
        List<UserRecord> users = new ArrayList<>();
        String sql = "SELECT discord_id, riot_puuid, summoner_name, region FROM users_v2";
        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                users.add(new UserRecord(
                        rs.getString("discord_id"),
                        rs.getString("riot_puuid"),
                        rs.getString("summoner_name"),
                        rs.getString("region")
                ));
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture liste users: " + e.getMessage());
        }
        return users;
    }

    // --- GESTION SESSION CHAT ---
    public synchronized JSONArray getChatHistory(String discordId) {
        String sql = "SELECT history, last_updated FROM chat_sessions WHERE discord_id = ?";

        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                long lastUpdated = rs.getLong("last_updated");
                if (System.currentTimeMillis() - lastUpdated > SESSION_TIMEOUT_MS) {
                    deleteSessionInternal(conn, discordId);
                    return new JSONArray();
                }
                return new JSONArray(rs.getString("history"));
            }
        } catch (Exception e) {
            System.out.println("Erreur lecture chat: " + e.getMessage());
        }
        return new JSONArray();
    }

    public synchronized void updateChatHistory(String discordId, JSONArray history) {
        String sql = "INSERT OR REPLACE INTO chat_sessions(discord_id, history, last_updated) VALUES(?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, history.toString());
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde chat: " + e.getMessage());
        }
    }

    public synchronized void clearChatHistory(String discordId) {
        try (Connection conn = this.connect()) {
            deleteSessionInternal(conn, discordId);
        } catch (SQLException e) {
            System.out.println("Erreur suppression chat: " + e.getMessage());
        }
    }

    private void deleteSessionInternal(Connection conn, String discordId) throws SQLException {
        String sql = "DELETE FROM chat_sessions WHERE discord_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.executeUpdate();
        }
    }

    // --- GESTION SNAPSHOTS ---
    public synchronized void saveSnapshot(String puuid, String discordId, String tier, String rank, int lp) {
        String sql = "INSERT OR REPLACE INTO user_snapshots_v2(riot_puuid, discord_id, tier, rank, lp, timestamp) VALUES(?, ?, ?, ?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, puuid);
            pstmt.setString(2, discordId);
            pstmt.setString(3, tier);
            pstmt.setString(4, rank);
            pstmt.setInt(5, lp);
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde snapshot: " + e.getMessage());
        }
    }

    public SnapshotRecord getSnapshot(String puuid) {
        String sql = "SELECT discord_id, tier, rank, lp, timestamp FROM user_snapshots_v2 WHERE riot_puuid = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, puuid);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new SnapshotRecord(
                        rs.getString("discord_id"),
                        rs.getString("tier"),
                        rs.getString("rank"),
                        rs.getInt("lp"),
                        rs.getLong("timestamp")
                );
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture snapshot: " + e.getMessage());
        }
        return null;
    }

    // --- GESTION DAILY PERFORMANCES ---
    public synchronized void saveDailyPerformance(String puuid, String discordId, String date, int gamesPlayed, int wins, double averageScore, int lpDiff, double mvpScore, String aiSummary) {
        String sql = "INSERT OR REPLACE INTO daily_performances_v2(riot_puuid, discord_id, date, games_played, wins, average_score, lp_diff, mvp_score, ai_summary) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, puuid);
            pstmt.setString(2, discordId);
            pstmt.setString(3, date);
            pstmt.setInt(4, gamesPlayed);
            pstmt.setInt(5, wins);
            pstmt.setDouble(6, averageScore);
            pstmt.setInt(7, lpDiff);
            pstmt.setDouble(8, mvpScore);
            pstmt.setString(9, aiSummary);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde daily performance: " + e.getMessage());
        }
    }

    public List<String> getBestPlayersOfPeriod(String fromDateString) {
        List<String> bestDiscordIds = new ArrayList<>();
        // On groupe par riot_puuid pour évaluer chaque compte, et on ignore les mvp_score à 0
        String sql = "SELECT discord_id, AVG(mvp_score) as final_score FROM daily_performances_v2 WHERE date >= ? AND games_played > 0 AND mvp_score > 0 GROUP BY riot_puuid ORDER BY final_score DESC";

        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, fromDateString);
            ResultSet rs = pstmt.executeQuery();

            double bestScore = -1.0;
            boolean first = true;

            while (rs.next()) {
                double score = rs.getDouble("final_score");
                String discordId = rs.getString("discord_id");

                if (first) {
                    bestScore = score;
                    bestDiscordIds.add(discordId);
                    first = false;
                } else {
                    if (Double.compare(score, bestScore) == 0) {
                        bestDiscordIds.add(discordId);
                    } else {
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Erreur calcul meilleurs joueurs: " + e.getMessage());
        }
        return bestDiscordIds;
    }

    public PeriodStats getPlayerPeriodStats(String discordId, String fromDateString) {
        String sql = "SELECT SUM(games_played) as total_games, SUM(wins) as total_wins, " +
                     "AVG(CASE WHEN mvp_score > 0 THEN average_score ELSE NULL END) as avg_score, " +
                     "AVG(CASE WHEN mvp_score > 0 THEN mvp_score ELSE NULL END) as avg_mvp " +
                     "FROM daily_performances_v2 WHERE discord_id = ? AND date >= ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, fromDateString);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt("total_games") > 0) {
                PeriodStats stats = new PeriodStats();
                stats.totalGames = rs.getInt("total_games");
                stats.totalWins = rs.getInt("total_wins");
                stats.avgScore = rs.getDouble("avg_score");
                stats.avgMvpScore = rs.getDouble("avg_mvp");
                return stats;
            }
        } catch (SQLException e) {
            System.out.println("Erreur getPlayerPeriodStats: " + e.getMessage());
        }
        return null;
    }

    public static class UserRecord {
        public String discordId;
        public String puuid;
        public String summonerName;
        public String region;

        public UserRecord(String discordId, String puuid, String summonerName, String region) {
            this.discordId = discordId;
            this.puuid = puuid;
            this.summonerName = summonerName;
            this.region = (region == null || region.isEmpty()) ? "euw1" : region;
        }

        // Constructeur de compatibilité
        public UserRecord(String discordId, String puuid, String summonerName) {
            this(discordId, puuid, summonerName, "euw1");
        }
    }

    public static class SnapshotRecord {
        public String discordId;
        public String tier;
        public String rank;
        public int lp;
        public long timestamp;

        public SnapshotRecord(String discordId, String tier, String rank, int lp, long timestamp) {
            this.discordId = discordId;
            this.tier = tier;
            this.rank = rank;
            this.lp = lp;
            this.timestamp = timestamp;
        }
    }

    public static class DailyPerformanceRecord {
        public String discordId;
        public String date;
        public int gamesPlayed;
        public int wins;
        public double averageScore;
        public int lpDiff;
        public double mvpScore;
        public String aiSummary;

        public DailyPerformanceRecord(String discordId, String date, int gamesPlayed, int wins, double averageScore, int lpDiff, double mvpScore, String aiSummary) {
            this.discordId = discordId;
            this.date = date;
            this.gamesPlayed = gamesPlayed;
            this.wins = wins;
            this.averageScore = averageScore;
            this.lpDiff = lpDiff;
            this.mvpScore = mvpScore;
            this.aiSummary = aiSummary;
        }
    }

    public static class PeriodStats {
        public int totalGames;
        public int totalWins;
        public double avgScore;
        public double avgMvpScore;
    }
}
