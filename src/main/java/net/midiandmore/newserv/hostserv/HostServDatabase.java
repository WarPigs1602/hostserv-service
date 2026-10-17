package net.midiandmore.newserv.hostserv;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

final class HostServDatabase implements AutoCloseable {
    private final Connection connection;

    HostServDatabase(JsonConfig config) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.get("dbuser", ""));
        properties.setProperty("password", config.get("dbpassword", ""));
        properties.setProperty("ssl", config.get("dbssl", "false"));
        String url = "jdbc:postgresql://" + config.get("dbhost", "localhost") + "/" + config.get("db", "hostserv");
        connection = DriverManager.getConnection(url, properties);
        initializeHostServSchema();
    }

    private void initializeHostServSchema() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS hostserv");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS hostserv.hosts (
                        uid integer,
                        ident character varying(10),
                        host character varying(63),
                        "timestamp" integer
                    )
                    """);
        }
    }

    int flags(String account) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT flags FROM chanserv.users WHERE username = ?")) {
            statement.setString(1, account);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt("flags") : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to look up account flags", ex);
        }
    }

    boolean isRegistered(String account) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chanserv.users WHERE LOWER(username) = LOWER(?)")) {
            statement.setString(1, account);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to verify account registration", ex);
        }
    }

    String host(String account) {
        String sql = """
                SELECT h.ident, h.host
                FROM hostserv.hosts h
                JOIN chanserv.users u ON u.id = h.uid
                WHERE LOWER(u.username) = LOWER(?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString("ident") + "@" + results.getString("host") : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to look up the account's virtual host", ex);
        }
    }

    long hostTimestamp(String account) {
        String sql = """
                SELECT h.timestamp
                FROM hostserv.hosts h
                JOIN chanserv.users u ON u.id = h.uid
                WHERE LOWER(u.username) = LOWER(?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong("timestamp") : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to look up the virtual-host change time", ex);
        }
    }

    void saveHost(String account, String ident, String host) {
        String update = """
                UPDATE hostserv.hosts h
                SET ident = ?, host = ?, timestamp = ?
                FROM chanserv.users u
                WHERE h.uid = u.id AND LOWER(u.username) = LOWER(?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setString(1, ident);
            statement.setString(2, host);
            statement.setLong(3, System.currentTimeMillis() / 1000);
            statement.setString(4, account);
            if (statement.executeUpdate() == 0) {
                String insert = """
                        INSERT INTO hostserv.hosts (uid, ident, host, timestamp)
                        SELECT id, ?, ?, ? FROM chanserv.users WHERE LOWER(username) = LOWER(?)
                        """;
                try (PreparedStatement insertStatement = connection.prepareStatement(insert)) {
                    insertStatement.setString(1, ident);
                    insertStatement.setString(2, host);
                    insertStatement.setLong(3, System.currentTimeMillis() / 1000);
                    insertStatement.setString(4, account);
                    if (insertStatement.executeUpdate() != 1) {
                        throw new IllegalStateException("Unable to save virtual host: account is not registered");
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to save the account's virtual host", ex);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
