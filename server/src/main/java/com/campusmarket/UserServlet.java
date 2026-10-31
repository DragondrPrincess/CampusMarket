package com.campusmarket;

import com.campusmarket.auth.ApiException;
import com.campusmarket.service.AuthService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Reader;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@WebServlet(urlPatterns = {"/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/users/me"})
public final class UserServlet extends HttpServlet {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final AuthService service = new AuthService();

    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            String route = request.getServletPath();
            String method = request.getMethod();
            String token = request.getHeader("Authorization");
            Object data;
            int status = 200;
            if (route.equals("/api/v1/auth/register") && method.equals("POST")) {
                data = service.register(body(request)); status = 201;
            } else if (route.equals("/api/v1/auth/login") && method.equals("POST")) {
                data = service.login(body(request));
            } else if (route.equals("/api/v1/auth/logout") && method.equals("POST")) {
                service.logout(token); data = null;
            } else if (route.equals("/api/v1/users/me") && method.equals("GET")) {
                data = service.requireUser(token).publicProfile();
            } else if (route.equals("/api/v1/users/me") && method.equals("PUT")) {
                data = service.updateProfile(token, body(request));
            } else {
                response.setHeader("Allow", route.equals("/api/v1/users/me") ? "GET, PUT" : "POST");
                throw new ApiException(405, 40501, "不支持的请求方法");
            }
            write(response, status, 0, "ok", data);
        } catch (ApiException exception) {
            write(response, exception.status, exception.code, exception.getMessage(), null);
        } catch (JsonParseException exception) {
            write(response, 400, 40001, "JSON格式不正确", null);
        } catch (SQLException exception) {
            getServletContext().log("User API SQL failure; state=" + exception.getSQLState() + "; code=" + exception.getErrorCode());
            write(response, 503, 50301, "数据库暂不可用，请稍后重试", null);
        } catch (RuntimeException exception) {
            getServletContext().log("User API failure type=" + exception.getClass().getSimpleName());
            write(response, 500, 50001, "服务器暂不可用，请稍后重试", null);
        }
    }

    static JsonObject body(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json")) {
            throw new ApiException(415, 41501, "请使用JSON请求");
        }
        if (request.getContentLengthLong() > 16384) throw new ApiException(413, 41301, "请求过大");
        StringBuilder text = new StringBuilder();
        try (Reader reader = request.getReader()) {
            char[] chars = new char[1024]; int count;
            while ((count = reader.read(chars)) != -1) {
                text.append(chars, 0, count);
                if (text.length() > 8192) throw new ApiException(413, 41301, "请求过大");
            }
        }
        JsonObject value = JSON.fromJson(text.toString(), JsonObject.class);
        if (value == null) throw new ApiException(400, 40001, "请求不能为空");
        return value;
    }
    private static void write(HttpServletResponse response, int status, int code, String message, Object data) throws IOException {
        response.setStatus(status);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code); value.put("message", message); value.put("data", data);
        response.getWriter().write(JSON.toJson(value));
    }
}
