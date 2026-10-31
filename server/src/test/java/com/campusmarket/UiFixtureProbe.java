package com.campusmarket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Read-only profile proof, or expire only the UI test's own active session. */
public final class UiFixtureProbe {
    public static void main(String[] args) {
        try { run(args); }
        catch (java.sql.SQLException exception) { System.err.println("UI JDBC FAIL: state=" + exception.getSQLState() + "; code=" + exception.getErrorCode()); System.exit(1); }
        catch (AssertionError exception) { System.err.println("UI JDBC FAIL: " + exception.getMessage()); System.exit(1); }
        catch (Exception exception) { System.err.println("UI JDBC FAIL: type=" + exception.getClass().getSimpleName()); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        String username = fixture.get("username").getAsString(); long id = fixture.get("userId").getAsLong();
        if (!username.matches("p3_ui_[a-f0-9]{12}")) throw new IllegalArgumentException("Not a UI fixture account");
        try (Connection connection = Database.open()) {
            try (PreparedStatement query = connection.prepareStatement("SELECT nickname,school,campus,bio,password_hash FROM users WHERE id=? AND username=?")) {
                query.setLong(1, id); query.setString(2, username);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next() || !"界面验收同学".equals(rows.getString(1)) || !"校园易物测试大学".equals(rows.getString(2))
                            || !"南校区".equals(rows.getString(3)) || !"由Android界面真实提交".equals(rows.getString(4))
                            || !rows.getString(5).startsWith("pbkdf2_sha256$600000$")) {
                        throw new AssertionError("UI-submitted profile not persisted");
                    }
                }
            }
            System.out.println("UI JDBC PASS: actual Android profile persisted; password hash format verified.");
            if (args[0].equals("expire")) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE auth_sessions s JOIN users u ON u.id=s.user_id SET s.expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) "
                                + "WHERE u.id=? AND u.username=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP(3)")) {
                    update.setLong(1, id); update.setString(2, username);
                    if (update.executeUpdate() != 1) throw new AssertionError("Expected one active fixture session");
                    System.out.println("UI FIXTURE EXPIRY PASS: only this run's own UI fixture session expired.");
                }
            }
        }
    }
}
