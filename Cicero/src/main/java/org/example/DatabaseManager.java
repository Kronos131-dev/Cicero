package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
<<<<<<< Updated upstream
=======
import org.example.service.SimpleCache;
import org.sqlite.SQLiteConfig;
>>>>>>> Stashed changes

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
            System.out.println("Erreur init BDD: " + e.getMessage());
        }
    }

    // --- GESTION UTILISATEURS ---
<<<<<<< Updated upstream
    public void saveUser(String discordId, String puuid, String summonerName) {
        String sql = "INSERT OR REPLACE INTO users(discord_id, riot_puuid, summoner_name) VALUES(?, ?, ?)";
=======
    public synchronized void saveUser(String discordId, String puuid, String summonerName, String region) {
        String sql = "INSERT OR REPLACE INTO users(discord_id, riot_puuid, summoner_name, region) VALUES(?, ?, ?, ?)";
>>>>>>> Stashed changes
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, puuid);
            pstmt.setString(3, summonerName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde user: " + e.getMessage());
        }
    }

    public String getPuuid(String discordId) {
        String sql = "SELECT riot_puuid FROM users WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("riot_puuid");
        } catch (SQLException e) {
            System.out.println("Erreur lecture user: " + e.getMessage());
        }
        return null;
    }

    public List<UserRecord> getAllUsers() {
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
            System.out.println("Erreur lecture liste users: " + e.getMessage());
        }
        return users;
    }

    // --- GESTION SESSION CHAT ---
<<<<<<< Updated upstream
    
    // Récupère l'historique. Si > 20min, retourne un tableau vide et nettoie.
    public JSONArray getChatHistory(String discordId) {
=======
    public synchronized JSONArray getChatHistory(String discordId) {
>>>>>>> Stashed changes
        String sql = "SELECT history, last_updated FROM chat_sessions WHERE discord_id = ?";
        
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                long lastUpdated = rs.getLong("last_updated");
                if (System.currentTimeMillis() - lastUpdated > SESSION_TIMEOUT_MS) {
<<<<<<< Updated upstream
                    // Session expirée
                    clearChatHistory(discordId);
=======
                    // Attention: appel récursif à une méthode synchronized, mais sur le même thread donc OK (reentrant)
                    // Cependant, pour éviter tout risque, on peut extraire la logique de suppression
                    deleteSessionInternal(conn, discordId);
>>>>>>> Stashed changes
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