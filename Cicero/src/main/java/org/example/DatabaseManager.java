package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.sqlite.SQLiteConfig;

/**
 * Handles all SQLite database interactions.
 * Manages user links and chat session persistence.
 */
public class DatabaseManager {
    private final String url = "jdbc:sqlite:lolbot.db";
    
    // Configuration constants
    private static final long SESSION_TIMEOUT_MS = 20 * 60 * 1000; // 20 minutes inactivity
    private static final int MAX_HISTORY_LENGTH = 20; // Keep only the last 20 messages

    public DatabaseManager() {
        createTables();
    }

    /**
     * Establishes a connection to the SQLite database with WAL mode enabled for concurrency.
     */
    private Connection connect() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        return DriverManager.getConnection(url, config.toProperties());
    }

    private void createTables() {
        String sqlUsers = "CREATE TABLE IF NOT EXISTS users (" +
                "discord_id TEXT PRIMARY KEY, " +
                "riot_puuid TEXT NOT NULL, " +
                "summoner_name TEXT NOT NULL" +
                ");";

        String sqlSessions = "CREATE TABLE IF NOT EXISTS chat_sessions (" +
                "discord_id TEXT PRIMARY KEY, " +
                "history TEXT NOT NULL, " +
                "last_updated INTEGER NOT NULL" +
                ");";

        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sqlUsers);
            stmt.execute(sqlSessions);
        } catch (SQLException e) {
            System.err.println("[DB] Init Error: " + e.getMessage());
        }
    }

    // --- USER MANAGEMENT ---

    public synchronized void saveUser(String discordId, String puuid, String summonerName) {
        String sql = "INSERT OR REPLACE INTO users(discord_id, riot_puuid, summoner_name) VALUES(?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, puuid);
            pstmt.setString(3, summonerName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Save User Error: " + e.getMessage());
        }
    }

    public synchronized String getPuuid(String discordId) {
        String sql = "SELECT riot_puuid FROM users WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("riot_puuid");
        } catch (SQLException e) {
            System.err.println("[DB] Get PUUID Error: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<UserRecord> getAllUsers() {
        List<UserRecord> users = new ArrayList<>();
        String sql = "SELECT discord_id, riot_puuid, summoner_name FROM users";
        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                users.add(new UserRecord(
                        rs.getString("discord_id"),
                        rs.getString("riot_puuid"),
                        rs.getString("summoner_name")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[DB] List Users Error: " + e.getMessage());
        }
        return users;
    }

    // --- CHAT SESSION MANAGEMENT ---

    public synchronized JSONArray getChatHistory(String discordId) {
        String sql = "SELECT history, last_updated FROM chat_sessions WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                long lastUpdated = rs.getLong("last_updated");
                if (System.currentTimeMillis() - lastUpdated > SESSION_TIMEOUT_MS) {
                    deleteSession(conn, discordId);
                    return new JSONArray();
                }
                return new JSONArray(rs.getString("history"));
            }
        } catch (Exception e) {
            System.err.println("[DB] Get History Error: " + e.getMessage());
        }
        return new JSONArray();
    }

    public synchronized void updateChatHistory(String discordId, JSONArray history) {
        // Truncate history to prevent token overflow
        while (history.length() > MAX_HISTORY_LENGTH) {
            history.remove(0);
        }

        String sql = "INSERT OR REPLACE INTO chat_sessions(discord_id, history, last_updated) VALUES(?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, history.toString());
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Update History Error: " + e.getMessage());
        }
    }

    public synchronized void clearChatHistory(String discordId) {
        try (Connection conn = this.connect()) {
            deleteSession(conn, discordId);
        } catch (SQLException e) {
            System.err.println("[DB] Clear History Error: " + e.getMessage());
        }
    }

    private void deleteSession(Connection conn, String discordId) throws SQLException {
        String sql = "DELETE FROM chat_sessions WHERE discord_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.executeUpdate();
        }
    }

    public static class UserRecord {
        public String discordId;
        public String puuid;
        public String summonerName;

        public UserRecord(String discordId, String puuid, String summonerName) {
            this.discordId = discordId;
            this.puuid = puuid;
            this.summonerName = summonerName;
        }
    }
}