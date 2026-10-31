package com.campusmarket;

import com.google.gson.Gson;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet(urlPatterns = {"/api/v1/health", "/api/v1/health/db"})
public final class HealthServlet extends HttpServlet {
    private static final Gson JSON = new Gson();

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (request.getServletPath().equals("/api/v1/health")) {
            write(response, 200, 0, "ok", Map.of("service", "campus-market", "status", "UP"));
            return;
        }
        // Read a persisted row as well as MySQL's live clock/version, never a mock result.
        String sql = "SELECT project_name, schema_version, DATABASE() AS database_name, "
                + "VERSION() AS server_version, CURRENT_TIMESTAMP AS database_time "
                + "FROM app_metadata WHERE id = ?";
        try (Connection connection = Database.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setLong(1, 1);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    write(response, 503, 50302, "Database initialization required", null);
                    return;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("projectName", rows.getString("project_name"));
                data.put("schemaVersion", rows.getInt("schema_version"));
                data.put("databaseName", rows.getString("database_name"));
                data.put("serverVersion", rows.getString("server_version"));
                data.put("databaseTime", rows.getString("database_time"));
                write(response, 200, 0, "ok", data);
            }
        } catch (SQLException exception) {
            // Do not log exception messages: JDBC URLs or credentials may appear in them.
            getServletContext().log("Database health failed; SQLState=" + exception.getSQLState()
                    + "; vendorCode=" + exception.getErrorCode());
            write(response, 503, 50301, "Database unavailable; check local configuration", null);
        }
    }

    private static void write(HttpServletResponse response, int status, int code, String message, Object data) throws IOException {
        response.setStatus(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        response.getWriter().write(JSON.toJson(body));
    }
}
