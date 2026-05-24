package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public class DatabaseManager {
    private static final long SESSION_TIMEOUT_MS = 20 * 60 * 1000;

    private final String url;
    private final String user;
    private final String password;

    public DatabaseManager(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        createTables();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private void createTables() {
        String sqlUsers = "CREATE TABLE IF NOT EXISTS users (" +
                "discord_id TEXT PRIMARY KEY, " +
                "riot_puuid TEXT NOT NULL, " +
                "summoner_name TEXT NOT NULL, " +
                "region TEXT DEFAULT 'euw1', " +
                "last_audit TEXT" +
                ")";

        String sqlSessions = "CREATE TABLE IF NOT EXISTS chat_sessions (" +
                "discord_id TEXT PRIMARY KEY, " +
                "history TEXT NOT NULL, " +
                "last_updated BIGINT NOT NULL" +
                ")";

        String sqlSnapshots = "CREATE TABLE IF NOT EXISTS user_snapshots (" +
                "discord_id TEXT PRIMARY KEY, " +
                "tier TEXT, " +
                "rank TEXT, " +
                "lp INTEGER, " +
                "timestamp BIGINT" +
                ")";

        String sqlConfig = "CREATE TABLE IF NOT EXISTS config (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT" +
                ")";

        String sqlDailyPerformances = "CREATE TABLE IF NOT EXISTS daily_performances (" +
                "discord_id TEXT, " +
                "date TEXT, " +
                "games_played INTEGER, " +
                "wins INTEGER, " +
                "average_score REAL, " +
                "lp_diff INTEGER, " +
                "mvp_score REAL, " +
                "ai_summary TEXT, " +
                "PRIMARY KEY(discord_id, date)" +
                ")";

        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sqlUsers);
            stmt.execute(sqlSessions);
            stmt.execute(sqlSnapshots);
            stmt.execute(sqlConfig);
            stmt.execute(sqlDailyPerformances);
        } catch (SQLException e) {
            System.out.println("Erreur init BDD: " + e.getMessage());
        }
    }

    // --- GESTION AUDITS PERFORMANCE ---
    public synchronized void updateLastAudit(String discordId, String audit) {
        String sql = "UPDATE users SET last_audit = ? WHERE discord_id = ?";
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
        String sql = "SELECT last_audit FROM users WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("last_audit");
        } catch (SQLException e) {
            System.out.println("Erreur lecture audit: " + e.getMessage());
        }
        return null;
    }

    // --- GESTION CONFIGURATION ---
    public synchronized void saveConfig(String key, String value) {
        String sql = "INSERT INTO config(key, value) VALUES(?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value";
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
            if (rs.next()) return rs.getString("value");
        } catch (SQLException e) {
            System.out.println("Erreur lecture config: " + e.getMessage());
        }
        return null;
    }

    // --- GESTION UTILISATEURS ---
    public synchronized void saveUser(String discordId, String puuid, String summonerName, String region) {
        String sql = "INSERT INTO users(discord_id, riot_puuid, summoner_name, region) VALUES(?, ?, ?, ?) " +
                     "ON CONFLICT (discord_id) DO UPDATE SET riot_puuid = EXCLUDED.riot_puuid, summoner_name = EXCLUDED.summoner_name, region = EXCLUDED.region";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, puuid);
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
        String sql = "SELECT riot_puuid, summoner_name, region FROM users WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new UserRecord(discordId, rs.getString("riot_puuid"),
                        rs.getString("summoner_name"), rs.getString("region"));
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture user: " + e.getMessage());
        }
        return null;
    }

    public String getPuuid(String discordId) {
        UserRecord user = getUser(discordId);
        return user != null ? user.puuid : null;
    }

    public List<UserRecord> getAllUsers() {
        List<UserRecord> users = new ArrayList<>();
        String sql = "SELECT discord_id, riot_puuid, summoner_name, region FROM users";
        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                users.add(new UserRecord(rs.getString("discord_id"), rs.getString("riot_puuid"),
                        rs.getString("summoner_name"), rs.getString("region")));
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
        String sql = "INSERT INTO chat_sessions(discord_id, history, last_updated) VALUES(?, ?, ?) " +
                     "ON CONFLICT (discord_id) DO UPDATE SET history = EXCLUDED.history, last_updated = EXCLUDED.last_updated";
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
    public synchronized void saveSnapshot(String discordId, String tier, String rank, int lp) {
        String sql = "INSERT INTO user_snapshots(discord_id, tier, rank, lp, timestamp) VALUES(?, ?, ?, ?, ?) " +
                     "ON CONFLICT (discord_id) DO UPDATE SET tier = EXCLUDED.tier, rank = EXCLUDED.rank, lp = EXCLUDED.lp, timestamp = EXCLUDED.timestamp";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, tier);
            pstmt.setString(3, rank);
            pstmt.setInt(4, lp);
            pstmt.setLong(5, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde snapshot: " + e.getMessage());
        }
    }

    public SnapshotRecord getSnapshot(String discordId) {
        String sql = "SELECT tier, rank, lp, timestamp FROM user_snapshots WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return new SnapshotRecord(discordId, rs.getString("tier"), rs.getString("rank"),
                        rs.getInt("lp"), rs.getLong("timestamp"));
            }
        } catch (SQLException e) {
            System.out.println("Erreur lecture snapshot: " + e.getMessage());
        }
        return null;
    }

    // --- GESTION DAILY PERFORMANCES ---
    public synchronized void saveDailyPerformance(String discordId, String date, int gamesPlayed, int wins, double averageScore, int lpDiff, double mvpScore, String aiSummary) {
        String sql = "INSERT INTO daily_performances(discord_id, date, games_played, wins, average_score, lp_diff, mvp_score, ai_summary) VALUES(?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (discord_id, date) DO UPDATE SET games_played = EXCLUDED.games_played, wins = EXCLUDED.wins, average_score = EXCLUDED.average_score, lp_diff = EXCLUDED.lp_diff, mvp_score = EXCLUDED.mvp_score, ai_summary = EXCLUDED.ai_summary";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, date);
            pstmt.setInt(3, gamesPlayed);
            pstmt.setInt(4, wins);
            pstmt.setDouble(5, averageScore);
            pstmt.setInt(6, lpDiff);
            pstmt.setDouble(7, mvpScore);
            pstmt.setString(8, aiSummary);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde daily performance: " + e.getMessage());
        }
    }

    public List<String> getBestPlayersOfPeriod(String fromDateString) {
        List<String> bestDiscordIds = new ArrayList<>();
        String sql = "SELECT discord_id, AVG(mvp_score) as final_score FROM daily_performances WHERE date >= ? AND games_played > 0 GROUP BY discord_id ORDER BY final_score DESC";
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
                } else if (Double.compare(score, bestScore) == 0) {
                    bestDiscordIds.add(discordId);
                } else {
                    break;
                }
            }
        } catch (SQLException e) {
            System.out.println("Erreur calcul meilleurs joueurs: " + e.getMessage());
        }
        return bestDiscordIds;
    }

    public PeriodStats getPlayerPeriodStats(String discordId, String fromDateString) {
        String sql = "SELECT SUM(games_played) as total_games, SUM(wins) as total_wins, AVG(average_score) as avg_score, AVG(mvp_score) as avg_mvp " +
                     "FROM daily_performances WHERE discord_id = ? AND date >= ?";
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
