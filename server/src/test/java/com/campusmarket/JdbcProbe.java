package com.campusmarket;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Read-only integration probe, launched with the same private environment as Tomcat. */
public final class JdbcProbe {
    public static void main(String[] args) {
        try (Connection connection = Database.open(); Statement statement = connection.createStatement()) {
            if (!connection.isValid(3)) throw new SQLException("Invalid connection", "08001");
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT DATABASE(), VERSION(), project_name, schema_version FROM app_metadata WHERE id=1")) {
                if (!rows.next() || !"campus_market".equals(rows.getString(1))
                        || !"校园易物".equals(rows.getString(3)) || rows.getInt(4) < 1) {
                    throw new SQLException("Unexpected project metadata", "45000");
                }
                System.out.println("JDBC PASS: database=" + rows.getString(1) + "; MySQL=" + rows.getString(2)
                        + "; project=" + rows.getString(3) + "; schema=" + rows.getInt(4));
            }
        } catch (SQLException exception) {
            System.err.println("JDBC FAIL: SQLState=" + exception.getSQLState() + "; vendorCode=" + exception.getErrorCode());
            System.exit(1);
        }
    }
}
