package com.campusmarket;

import com.campusmarket.service.AuthService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real HTTP + JDBC integration. Only the newly-created fixture session is expired. No data is deleted. */
public final class AuthIntegrationProbe {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String BASE = "http://127.0.0.1:8080/campus-market/api/v1";
    private static final List<String> CHECKS = new ArrayList<>();
    private record Reply(int status, JsonObject body) { JsonObject data() { return body.getAsJsonObject("data"); } }

    public static void main(String[] args) {
        try { run(Path.of(args[0])); }
        catch (SQLException exception) { System.err.println("AUTH TEST FAIL: SQLState=" + exception.getSQLState() + "; code=" + exception.getErrorCode()); System.exit(1); }
        catch (AssertionError exception) { System.err.println("AUTH TEST FAIL: " + exception.getMessage()); System.exit(1); }
        catch (Exception exception) { System.err.println("AUTH TEST FAIL: type=" + exception.getClass().getSimpleName()); System.exit(1); }
    }
    private static void run(Path evidence) throws Exception {
        String username = "p3_api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String other = username + "b";
        byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Map<String, String> registration = Map.of("username", username, "password", password, "confirmPassword", password, "nickname", "接口验收用户");
        check(call("GET", "/users/me", null, null).status == 401, "unauthenticated access rejected");
        check(call("POST", "/auth/register", null, Map.of("username", username, "password", password, "confirmPassword", "mismatch", "nickname", "验收")).status == 400, "confirmation mismatch rejected");
        Reply created = call("POST", "/auth/register", null, registration);
        check(created.status == 201, "registration persisted");
        long userId = created.data().get("id").getAsLong();
        check(!created.data().has("password_hash") && !created.data().has("passwordHash"), "public profile excludes password hash");
        check(call("POST", "/auth/register", null, registration).status == 409, "duplicate username rejected");
        check(call("POST", "/auth/register", null, Map.of("username", username.toUpperCase(java.util.Locale.ROOT), "password", password, "confirmPassword", password, "nickname", "验收")).status == 409, "username normalization enforced");
        check(call("POST", "/auth/login", null, Map.of("username", username, "password", password + "wrong")).status == 401, "wrong password rejected");
        Reply login = call("POST", "/auth/login", null, Map.of("username", username, "password", password));
        check(login.status == 200, "registered account can log in");
        String token = login.data().get("token").getAsString();
        check(token.matches("[A-Za-z0-9_-]{43}") && login.data().get("expiresAt").getAsLong() > System.currentTimeMillis(), "random token and expiry returned");
        check(call("GET", "/users/me", token, null).data().get("username").getAsString().equals(username), "authenticated profile query");
        Reply second = call("POST", "/auth/register", null, Map.of("username", other, "password", password, "confirmPassword", password, "nickname", "隔离验收用户"));
        check(second.status == 201, "second isolated test account created");
        long otherId = second.data().get("id").getAsLong();
        check(call("PUT", "/users/me", token, Map.of("nickname", " ")).status == 400, "empty nickname rejected");
        Reply update = call("PUT", "/users/me", token, Map.of("id", otherId, "nickname", "资料持久化验收", "school", "校园易物测试大学", "campus", "东校区", "bio", "真实HTTP与JDBC验收"));
        check(update.status == 200 && update.data().get("id").getAsLong() == userId, "profile update uses authenticated identity");
        check(call("GET", "/users/me", token, null).data().get("campus").getAsString().equals("东校区"), "profile update survives separate HTTP request");
        String otherToken = call("POST", "/auth/login", null, Map.of("username", other, "password", password)).data().get("token").getAsString();
        check(call("GET", "/users/me", otherToken, null).data().get("nickname").getAsString().equals("隔离验收用户"), "other account profile not modified");
        verifyStorage(username, other, password, token, userId);
        check(call("POST", "/auth/logout", token, null).status == 200, "logout acknowledged");
        check(call("GET", "/users/me", token, null).status == 401, "revoked token cannot read protected data");
        check(call("PUT", "/users/me", token, Map.of("nickname", "不应写入")).status == 401, "revoked token cannot modify profile");
        String expiring = call("POST", "/auth/login", null, Map.of("username", username, "password", password)).data().get("token").getAsString();
        try (Connection connection = Database.open(); PreparedStatement sql = connection.prepareStatement(
                "UPDATE auth_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE token_hash=? AND user_id=?")) {
            sql.setString(1, AuthService.tokenHash(expiring)); sql.setLong(2, userId);
            check(sql.executeUpdate() == 1, "only fixture token expired for test");
        }
        Reply expired = call("GET", "/users/me", expiring, null);
        check(expired.status == 401 && expired.body.get("code").getAsInt() == 40102, "expired token rejected with distinct code");
        check(call("POST", "/auth/logout", otherToken, null).status == 200, "second test session revoked");
        for (int attempt = 0; attempt < 5; attempt++) {
            check(call("POST", "/auth/login", null, Map.of("username", other, "password", password + "bad")).status == 401, "failed login recorded " + (attempt + 1));
        }
        check(call("POST", "/auth/login", null, Map.of("username", other, "password", password)).status == 429, "repeated login failures temporarily locked");
        check(call("POST", "/auth/login", null, Map.of("username", "' OR 1=1 --", "password", password)).status == 400, "invalid username rejected");
        check(raw("POST", "/auth/login", "application/json", "{", null).status == 400, "malformed JSON rejected");
        check(raw("POST", "/auth/login", "text/plain", "{}", null).status == 415, "non JSON body rejected");
        check(raw("POST", "/auth/register", "application/json", "x".repeat(17000), null).status == 413, "oversized body rejected");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, JSON.toJson(Map.of("passed", true, "usernames", List.of(username, other), "checks", CHECKS)));
        System.out.println("AUTH INTEGRATION PASS: " + CHECKS.size() + " checks; two labeled test accounts retained; credentials/tokens not printed.");
    }
    private static void verifyStorage(String username, String other, String password, String token, long userId) throws SQLException {
        try (Connection connection = Database.open(); PreparedStatement sql = connection.prepareStatement(
                "SELECT username,password_hash,nickname,campus FROM users WHERE username IN (?,?) ORDER BY username")) {
            sql.setString(1, username); sql.setString(2, other);
            String previous = null; int count = 0;
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) {
                    String hash = rows.getString("password_hash");
                    check(!hash.equals(password) && hash.startsWith("pbkdf2_sha256$600000$"), "password stored as adaptive salted hash " + count);
                    if (previous != null) check(!previous.equals(hash), "identical passwords have independent salts");
                    previous = hash; count++;
                    if (rows.getString("username").equals(username)) check(rows.getString("nickname").equals("资料持久化验收") && rows.getString("campus").equals("东校区"), "profile verified directly via JDBC");
                }
            }
            check(count == 2, "both fixture users persisted");
            try (PreparedStatement session = connection.prepareStatement("SELECT token_hash FROM auth_sessions WHERE user_id=? AND token_hash=?")) {
                session.setLong(1, userId); session.setString(2, AuthService.tokenHash(token));
                try (ResultSet rows = session.executeQuery()) {
                    check(rows.next() && !rows.getString(1).equals(token), "session stores token digest only");
                }
            }
        }
    }
    private static Reply call(String method, String path, String token, Object body) throws Exception {
        return raw(method, path, "application/json", body == null ? null : JSON.toJson(body), token);
    }
    private static Reply raw(String method, String path, String contentType, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", contentType);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        check(response.headers().firstValue("Cache-Control").orElse("").equals("no-store"), "auth response not cached");
        return new Reply(response.statusCode(), JSON.fromJson(response.body(), JsonObject.class));
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); CHECKS.add(label); }
}
