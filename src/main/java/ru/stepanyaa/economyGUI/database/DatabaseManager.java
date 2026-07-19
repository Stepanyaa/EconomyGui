package ru.stepanyaa.economyGUI.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.stepanyaa.economyGUI.EconomyGUI;

import java.io.File;
import java.sql.*;
import java.util.List;

public class DatabaseManager {

    private final EconomyGUI plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(EconomyGUI plugin) {
        this.plugin = plugin;
    }

    public void connectMySQL(String host, int port, String database, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&characterEncoding=utf8");
        config.setUsername(user);
        config.setPassword(password);
        setupPool(config);
    }

    public void connectSQLite(File file) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        setupPool(config);
    }

    private void setupPool(HikariConfig config) {
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(5000);
        dataSource = new HikariDataSource(config);
        createTables();
    }

    private void createTables() {
        String sql = "CREATE TABLE IF NOT EXISTS economy_transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "uuid VARCHAR(36) NOT NULL, " +
                "timestamp BIGINT NOT NULL, " +
                "description VARCHAR(255), " +
                "amount DOUBLE NOT NULL, " +
                "executor VARCHAR(36)" +
                ");";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe(e.getMessage());
        }
    }

    public void migrateFromYaml(YamlConfiguration config) {
        String sql = "INSERT INTO economy_transactions (uuid, timestamp, description, amount, executor) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (String uuid : config.getKeys(false)) {
                for (String record : config.getStringList(uuid)) {
                    String[] parts = record.split(";", 4);
                    if (parts.length == 4) {
                        ps.setString(1, uuid);
                        ps.setLong(2, Long.parseLong(parts[0]));
                        ps.setString(3, parts[1]);
                        ps.setDouble(4, Double.parseDouble(parts[2]));
                        ps.setString(5, parts[3]);
                        ps.addBatch();
                    }
                }
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            plugin.getLogger().severe("Migration failed: " + e.getMessage());
        }
    }

    public void migrateLocalToRemote(File sqliteFile) {
        plugin.getLogger().info("Migrating SQLite to MySQL...");
        String selectSql = "SELECT * FROM economy_transactions";
        String insertSql = "INSERT INTO economy_transactions (uuid, timestamp, description, amount, executor) VALUES (?, ?, ?, ?, ?)";

        try (Connection sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
             Connection mysqlConn = dataSource.getConnection();
             Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql);
             PreparedStatement ps = mysqlConn.prepareStatement(insertSql)) {

            mysqlConn.setAutoCommit(false);
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setLong(2, rs.getLong("timestamp"));
                ps.setString(3, rs.getString("description"));
                ps.setDouble(4, rs.getDouble("amount"));
                ps.setString(5, rs.getString("executor"));
                ps.addBatch();
            }
            ps.executeBatch();
            mysqlConn.commit();
            sqliteFile.renameTo(new File(plugin.getDataFolder(), "database_old.db"));
        } catch (Exception e) {
            plugin.getLogger().severe("Remote migration failed: " + e.getMessage());
        }
    }

    public HikariDataSource getDataSource() { return dataSource; }
    public void close() { if (dataSource != null) dataSource.close(); }
}