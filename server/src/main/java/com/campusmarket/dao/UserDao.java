package com.campusmarket.dao;

import com.campusmarket.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UserDao {
    public record User(long id, String username, String passwordHash, String nickname, String school,
                       String campus, String bio, String status, boolean locked) {
        public Map<String, Object> publicProfile() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id); result.put("username", username); result.put("nickname", nickname);
            result.put("school", school); result.put("campus", campus); result.put("bio", bio);
            return result;
        }
    }
    public record Session(User user, boolean expired) { }
    private static final String COLUMNS = "u.id,u.username,u.password_hash,u.nickname,u.school,u.campus,u.bio,u.status,"
            + "(u.locked_until > CURRENT_TIMESTAMP(3)) AS locked";

    public User findByUsername(String username) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = prepare(connection,
                "SELECT " + COLUMNS + " FROM users u WHERE u.username=?")) {
            sql.setString(1, username);
            try (ResultSet rows = sql.executeQuery()) { return rows.next() ? readUser(rows) : null; }
        }
    }

    public long insertUser(String username, String hash, String nickname) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = connection.prepareStatement(
                "INSERT INTO users(username,password_hash,nickname) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setQueryTimeout(5);
            sql.setString(1, username); sql.setString(2, hash); sql.setString(3, nickname);
            sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated user id", "45000");
                return keys.getLong(1);
            }
        }
    }

    public void recordFailure(long id) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = prepare(connection,
                "UPDATE users SET locked_until=IF(failed_attempts+1 >= 5, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 MINUTE), locked_until),"
                        + "failed_attempts=failed_attempts+1 WHERE id=?")) {
            sql.setLong(1, id); sql.executeUpdate();
        }
    }

    public long createSession(long id, String tokenHash) throws SQLException {
        try (Connection connection = Database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement sql = prepare(connection, "UPDATE users SET failed_attempts=0,locked_until=NULL WHERE id=?")) {
                    sql.setLong(1, id); sql.executeUpdate();
                }
                try (PreparedStatement sql = prepare(connection,
                        "INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES(?,?,DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY))")) {
                    sql.setString(1, tokenHash); sql.setLong(2, id); sql.executeUpdate();
                }
                long expiresAt;
                try (PreparedStatement sql = prepare(connection, "SELECT expires_at FROM auth_sessions WHERE token_hash=?")) {
                    sql.setString(1, tokenHash);
                    try (ResultSet rows = sql.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Session missing", "45000");
                        expiresAt = rows.getTimestamp(1).getTime();
                    }
                }
                connection.commit();
                return expiresAt;
            } catch (SQLException exception) { connection.rollback(); throw exception; }
        }
    }

    public Session findSession(String hash) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = prepare(connection,
                "SELECT " + COLUMNS + ", (s.expires_at<=CURRENT_TIMESTAMP(3)) AS expired FROM auth_sessions s "
                        + "JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL")) {
            sql.setString(1, hash);
            try (ResultSet rows = sql.executeQuery()) {
                return rows.next() ? new Session(readUser(rows), rows.getBoolean("expired")) : null;
            }
        }
    }

    public void revoke(String hash) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = prepare(connection,
                "UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP(3) WHERE token_hash=? AND revoked_at IS NULL")) {
            sql.setString(1, hash); sql.executeUpdate();
        }
    }

    public void updateProfile(long id, String nickname, String school, String campus, String bio) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = prepare(connection,
                "UPDATE users SET nickname=?,school=?,campus=?,bio=? WHERE id=? AND status='ACTIVE'")) {
            sql.setString(1, nickname); sql.setString(2, school); sql.setString(3, campus); sql.setString(4, bio);
            sql.setLong(5, id); sql.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection connection, String text) throws SQLException {
        PreparedStatement sql = connection.prepareStatement(text);
        sql.setQueryTimeout(5);
        return sql;
    }
    private static User readUser(ResultSet row) throws SQLException {
        return new User(row.getLong("id"), row.getString("username"), row.getString("password_hash"),
                row.getString("nickname"), row.getString("school"), row.getString("campus"), row.getString("bio"),
                row.getString("status"), row.getBoolean("locked"));
    }
}
