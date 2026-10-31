package com.campusmarket.service;

import com.campusmarket.auth.ApiException;
import com.campusmarket.auth.PasswordHasher;
import com.campusmarket.dao.UserDao;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = PasswordHasher.hash(newToken());
    private final UserDao dao = new UserDao();

    public Map<String, Object> register(JsonObject body) throws SQLException {
        String username = username(body);
        String password = password(body);
        if (!password.equals(text(body, "confirmPassword", 128, true, false))) {
            throw new ApiException(400, 40001, "两次输入的密码不一致");
        }
        String nickname = text(body, "nickname", 40, true, true);
        try {
            dao.insertUser(username, PasswordHasher.hash(password), nickname);
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1062) throw new ApiException(409, 40901, "用户名已被使用");
            throw exception;
        }
        return dao.findByUsername(username).publicProfile();
    }

    public Map<String, Object> login(JsonObject body) throws SQLException {
        String username = username(body);
        String password = password(body);
        UserDao.User user = dao.findByUsername(username);
        boolean valid = PasswordHasher.verify(password, user == null ? DUMMY_HASH : user.passwordHash());
        if (user != null && user.locked()) throw new ApiException(429, 42901, "尝试次数过多，请在五分钟后重试");
        if (!valid || user == null || !user.status().equals("ACTIVE")) {
            if (user != null) dao.recordFailure(user.id());
            throw new ApiException(401, 40101, "用户名或密码错误");
        }
        String token = newToken();
        long expiresAt = dao.createSession(user.id(), tokenHash(token));
        return Map.of("token", token, "expiresAt", expiresAt, "user", user.publicProfile());
    }

    public UserDao.User requireUser(String authorization) throws SQLException {
        UserDao.Session session = dao.findSession(tokenHash(bearer(authorization)));
        if (session == null) throw new ApiException(401, 40101, "请先登录");
        if (session.expired()) throw new ApiException(401, 40102, "登录已过期，请重新登录");
        if (!session.user().status().equals("ACTIVE")) throw new ApiException(403, 40301, "当前账号不可用");
        return session.user();
    }

    public void logout(String authorization) throws SQLException {
        requireUser(authorization);
        dao.revoke(tokenHash(bearer(authorization)));
    }

    public Map<String, Object> updateProfile(String authorization, JsonObject body) throws SQLException {
        UserDao.User user = requireUser(authorization);
        dao.updateProfile(user.id(), text(body, "nickname", 40, true, true), text(body, "school", 80, false, true),
                text(body, "campus", 80, false, true), text(body, "bio", 200, false, true));
        return dao.findByUsername(user.username()).publicProfile();
    }

    private static String username(JsonObject body) {
        String value = text(body, "username", 32, true, true).toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_]{3,32}")) throw new ApiException(400, 40001, "用户名需为3至32位字母、数字或下划线");
        return value;
    }
    private static String password(JsonObject body) {
        String value = text(body, "password", 128, true, false);
        if (value.length() < 8) throw new ApiException(400, 40001, "密码需为8至128个字符");
        return value;
    }
    private static String text(JsonObject body, String key, int max, boolean required, boolean trim) {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            if (!required) return "";
            throw new ApiException(400, 40001, "请填写完整信息");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new ApiException(400, 40001, "字段类型不正确");
        String value = element.getAsString();
        if (trim) value = value.strip();
        if ((required && value.isBlank()) || value.codePointCount(0, value.length()) > max
                || (trim && value.codePoints().anyMatch(Character::isISOControl))) throw new ApiException(400, 40001, "字段为空、过长或包含不支持的字符");
        return value;
    }
    private static String bearer(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw new ApiException(401, 40101, "请先登录");
        return authorization.substring(7);
    }
    private static String newToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
