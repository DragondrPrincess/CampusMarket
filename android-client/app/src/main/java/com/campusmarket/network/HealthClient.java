package com.campusmarket.network;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HealthClient {
    public HealthResponse fetch(String baseUrl) throws IOException {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IOException("Server URL is not configured");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/health/db").openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "CampusMarket-Android/0.2");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new HttpStatusException(status);
            }
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                HealthResponse response = new Gson().fromJson(reader, HealthResponse.class);
                if (response == null || response.code != 0 || response.data == null
                        || response.data.projectName == null || response.data.databaseName == null
                        || response.data.serverVersion == null || response.data.databaseTime == null
                        || response.data.schemaVersion < 1) {
                    throw new JsonParseException("Invalid health response");
                }
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }

    public static final class HttpStatusException extends IOException {
        public final int status;
        public HttpStatusException(int status) { super("HTTP " + status); this.status = status; }
    }
}
