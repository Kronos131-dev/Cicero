package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
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
        String sqlUsers = "CREATE TABLE IF NOT EXISTS users (" +
                "discord_id TEXT PRIMARY KEY, " +
                "riot_puuid TEXT NOT NULL, " +
                "summoner_name TEXT NOT NULL, " +
                "region TEXT DEFAULT 'euw1', " +
                "last_audit TEXT" +
                ");";

        String sqlSessions = "CREATE TABLE IF NOT EXISTS chat_sessions (" +
                "discord_id TEXT PRIMARY KEY, " +
                "history TEXT NOT NULL, " +
                "last_updated INTEGER NOT NULL" +
                ");";

        String sqlSnapshots = "CREATE TABLE IF NOT EXISTS user_snapshots (" +
                "discord_id TEXT PRIMARY KEY, " +
                "tier TEXT, " +
                "rank TEXT, " +
                "lp INTEGER, " +
                "timestamp INTEGER" +
                ");";

        String sqlConfig = "CREATE TABLE IF NOT EXISTS config (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT" +
                ");";

        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sqlUsers);
            stmt.execute(sqlSessions);
            stmt.execute(sqlSnapshots);
            stmt.execute(sqlConfig);

            // Migrations pour les anciennes bases de données
            try {
                stmt.execute("ALTER TABLE users ADD COLUMN region TEXT DEFAULT 'euw1'");
            } catch (SQLException ignored) {
                // La colonne existe déjà
            }

            try {
                stmt.execute("ALTER TABLE users ADD COLUMN last_audit TEXT");
            } catch (SQLException ignored) {
                // La colonne existe déjà
            }
        } catch (SQLException e) {
            System.out.println("Erreur init BDD: " + e.getMessage());
        }
    }

    // --- GESTION AUDITS PERFORMANCE (Utilisé par PerformanceCommand) ---
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
        String sql = "INSERT OR REPLACE INTO users(discord_id, riot_puuid, summoner_name, region) VALUES(?, ?, ?, ?)";
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
    public synchronized void saveSnapshot(String discordId, String tier, String rank, int lp) {
        String sql = "INSERT OR REPLACE INTO user_snapshots(discord_id, tier, rank, lp, timestamp) VALUES(?, ?, ?, ?, ?)";
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
                return new SnapshotRecord(
                        discordId,
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
}