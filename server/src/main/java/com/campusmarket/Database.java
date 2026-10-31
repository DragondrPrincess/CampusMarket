package com.campusmarket;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class Database {
    private Database() { }

    public static Connection open() throws SQLException {
        String url = System.getenv("CAMPUS_DB_URL");
        String user = System.getenv("CAMPUS_DB_USER");
        String password = System.getenv("CAMPUS_DB_PASSWORD");
        if (url == null || user == null || password == null) {
            throw new SQLException("Database environment is not configured", "08001");
        }
        // Tomcat initializes DriverManager before loading WAR-local JDBC drivers.
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("MySQL JDBC driver is missing", "08001");
        }
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "5000");
        return DriverManager.getConnection(url, properties);
    }
}
