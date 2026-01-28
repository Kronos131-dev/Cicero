package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public class DatabaseManager {
    private final String url = "jdbc:sqlite:lolbot.db";
    private static final long SESSION_TIMEOUT_MS = 20 * 60 * 1000; // 20 minutes

    public DatabaseManager() {
        createTables();
        updateSchema(); // Migration pour ajouter la colonne region si elle manque
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void createTables() {
        String sqlUsers = "CREATE TABLE IF NOT EXISTS users (" +
                "discord_id TEXT PRIMARY KEY, " +
                "riot_puuid TEXT NOT NULL, " +
                "summoner_name TEXT NOT NULL, " +
                "region TEXT DEFAULT 'euw1'" + // Nouvelle colonne
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

    // Migration simple pour les bases existantes
    private void updateSchema() {
        try (Connection conn = this.connect();
             Statement stmt = conn.createStatement()) {
            // On essaie d'ajouter la colonne. Si elle existe déjà, SQLite renverra une erreur qu'on ignore.
            stmt.execute("ALTER TABLE users ADD COLUMN region TEXT DEFAULT 'euw1'");
        } catch (SQLException ignored) {
            // La colonne existe probablement déjà
        }
    }

    // --- GESTION UTILISATEURS ---
    public void saveUser(String discordId, String puuid, String summonerName, String region) {
        String sql = "INSERT OR REPLACE INTO users(discord_id, riot_puuid, summoner_name, region) VALUES(?, ?, ?, ?)";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.setString(2, puuid);
            pstmt.setString(3, summonerName);
            pstmt.setString(4, region);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur sauvegarde user: " + e.getMessage());
        }
    }

    // Récupère l'objet complet (PUUID + Region)
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

    // Gardé pour compatibilité, mais il vaut mieux utiliser getUser()
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
    public JSONArray getChatHistory(String discordId) {
        String sql = "SELECT history, last_updated FROM chat_sessions WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                long lastUpdated = rs.getLong("last_updated");
                if (System.currentTimeMillis() - lastUpdated > SESSION_TIMEOUT_MS) {
                    clearChatHistory(discordId);
                    return new JSONArray();
                }
                return new JSONArray(rs.getString("history"));
            }
        } catch (Exception e) {
            System.out.println("Erreur lecture chat: " + e.getMessage());
        }
        return new JSONArray();
    }

    public void updateChatHistory(String discordId, JSONArray history) {
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

    public void clearChatHistory(String discordId) {
        String sql = "DELETE FROM chat_sessions WHERE discord_id = ?";
        try (Connection conn = this.connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, discordId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Erreur suppression chat: " + e.getMessage());
        }
    }

    public static class UserRecord {
        public String discordId;
        public String puuid;
        public String summonerName;
        public String region; // ex: "euw1", "na1"

        public UserRecord(String discordId, String puuid, String summonerName, String region) {
            this.discordId = discordId;
            this.puuid = puuid;
            this.summonerName = summonerName;
            this.region = (region == null || region.isEmpty()) ? "euw1" : region;
        }
    }
}