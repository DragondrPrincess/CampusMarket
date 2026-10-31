package com.campusmarket.network;

import com.campusmarket.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;

/** Synchronous transport; activities must call it from their ExecutorService. */
public final class UserApi {
    private final String baseUrl;
    public UserApi(){this(BuildConfig.API_BASE_URL);}
    UserApi(String baseUrl){this.baseUrl=baseUrl;}
    public static final class User {
        public long id;
        public String username, nickname, school, campus, bio;
    }
    public static final class Login {
        public String token;
        public long expiresAt;
        public User user;
    }
    public static final class Failure extends IOException {
        public final int status, code;
        public Failure(int status, int code, String message) { super(message); this.status = status; this.code = code; }
    }
    public User register(String username, String password, String confirm, String nickname) throws IOException {
        return request("POST", "/auth/register", null,
                fields("username", username, "password", password, "confirmPassword", confirm, "nickname", nickname), User.class);
    }
    public Login login(String username, String password) throws IOException {
        return request("POST", "/auth/login", null, fields("username", username, "password", password), Login.class);
    }
    public User me(String token) throws IOException { return request("GET", "/users/me", token, null, User.class); }
    public User update(String token, String nickname, String school, String campus, String bio) throws IOException {
        return request("PUT", "/users/me", token, fields("nickname", nickname, "school", school, "campus", campus, "bio", bio), User.class);
    }
    public void logout(String token) throws IOException { request("POST", "/auth/logout", token, null, Void.class); }
    public CatalogApi.Image upload(String token,byte[] bytes,String mediaType)throws IOException {
        String boundary="CampusMarket"+java.util.UUID.randomUUID().toString().replace("-","");
        HttpURLConnection connection=(HttpURLConnection)new URL(baseUrl+"/images").openConnection();
        try{
            connection.setConnectTimeout(5000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            byte[] head=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\nContent-Type: "+mediaType+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] tail=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII);connection.setFixedLengthStreamingMode(head.length+bytes.length+tail.length);
            try(OutputStream out=connection.getOutputStream()){out.write(head);out.write(bytes);out.write(tail);}
            int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();if(input==null)throw new IOException("Empty response");
            try(InputStreamReader reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
                Gson gson=new Gson();JsonObject response=gson.fromJson(reader,JsonObject.class);if(response==null||!response.has("code"))throw new IOException("Invalid response");
                int code=response.get("code").getAsInt();if(status>=400||code!=0)throw new Failure(status,code,response.get("message").getAsString());
                CatalogApi.Image image=gson.fromJson(response.get("data"),CatalogApi.Image.class);if(image==null||image.id<=0)throw new IOException("Missing image");return image;
            }catch(RuntimeException e){throw new IOException("Invalid upload response");}
        }finally{connection.disconnect();}
    }

    private static Map<String, String> fields(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    <T> T request(String method, String path, String token, Object body, Class<T> type) throws IOException {
        if (baseUrl.isEmpty()) throw new IOException("Server not configured");
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "CampusMarket-Android/" + BuildConfig.VERSION_NAME);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            Gson gson = new Gson();
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IOException("Empty response");
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject response = gson.fromJson(reader, JsonObject.class);
                if (response == null || !response.has("code")) throw new IOException("Invalid response");
                int code = response.get("code").getAsInt();
                if (status >= 400 || code != 0) {
                    String message = response.has("message") ? response.get("message").getAsString() : "服务暂不可用，请重试";
                    throw new Failure(status, code, message);
                }
                if (type == Void.class) return null;
                T data = gson.fromJson(response.get("data"), type);
                if (data == null) throw new IOException("Missing response data");
                return data;
            } catch (RuntimeException exception) { throw new IOException("Invalid JSON response"); }
        } finally { connection.disconnect(); }
    }
}
