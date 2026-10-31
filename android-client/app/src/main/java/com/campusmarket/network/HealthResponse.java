package com.campusmarket.network;

public final class HealthResponse {
    public int code;
    public String message;
    public DatabaseInfo data;

    public static final class DatabaseInfo {
        public String projectName;
        public String databaseName;
        public String serverVersion;
        public String databaseTime;
        public int schemaVersion;
    }
}
