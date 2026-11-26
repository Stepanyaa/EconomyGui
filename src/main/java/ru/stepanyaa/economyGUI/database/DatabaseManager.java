package ru.stepanyaa.economyGUI.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import ru.stepanyaa.economyGUI.economy.TransactionReversal;
import java.sql.*;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.util.logging.Logger;

public class DatabaseManager {

    private final Logger logger;
    private final File dataFolder;
    private HikariDataSource dataSource;
    private boolean isMySQL = false;

    public DatabaseManager(Logger logger, File dataFolder) {
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    public void connectMySQL(String host, int port, String database, String user, String password) {
        isMySQL = true;
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&characterEncoding=utf8&allowPublicKeyRetrieval=true");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5000);
        config.setPoolName("EconomyGUI-MySQL-Pool");
        setupPool(config);
    }

    public void connectSQLite(File file) {
        isMySQL = false;
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5000);
        config.setPoolName("EconomyGUI-SQLite-Pool");
        setupPool(config);
    }

    private void setupPool(HikariConfig config) {
        dataSource = new HikariDataSource(config);
        createTables();
    }

    private void createTables() {
        String sqlTransactions;
        if (isMySQL) {
            sqlTransactions = "CREATE TABLE IF NOT EXISTS economy_transactions (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "sender_uuid VARCHAR(36), " +
                    "sender_name VARCHAR(64), " +
                    "receiver_uuid VARCHAR(36), " +
                    "receiver_name VARCHAR(64), " +
                    "timestamp BIGINT NOT NULL, " +
                    "description VARCHAR(255), " +
                    "amount DOUBLE NOT NULL, " +
                    "executor VARCHAR(64), " +
                    "action VARCHAR(32), " +
                    "refund_status VARCHAR(32) DEFAULT 'NONE'" +
                    ");";
        } else {
            sqlTransactions = "CREATE TABLE IF NOT EXISTS economy_transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "sender_uuid VARCHAR(36), " +
                    "sender_name VARCHAR(64), " +
                    "receiver_uuid VARCHAR(36), " +
                    "receiver_name VARCHAR(64), " +
                    "timestamp BIGINT NOT NULL, " +
                    "description VARCHAR(255), " +
                    "amount DOUBLE NOT NULL, " +
                    "executor VARCHAR(64), " +
                    "action VARCHAR(32), " +
                    "refund_status VARCHAR(32) DEFAULT 'NONE'" +
                    ");";
        }

        String sqlBalances = "CREATE TABLE IF NOT EXISTS economy_balances (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "player_name VARCHAR(64), " +
                "balance DOUBLE NOT NULL DEFAULT 0.0" +
                ");";

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(sqlTransactions);
            st.executeUpdate(sqlBalances);
            upgradeSchemaIfNeeded(conn);
        } catch (SQLException e) {
            logger.severe("Database table initialization error: " + e.getMessage());
        }
    }

    private void upgradeSchemaIfNeeded(Connection conn) {
        String[] columnsToAdd = {
                "ALTER TABLE economy_transactions ADD COLUMN sender_uuid VARCHAR(36)",
                "ALTER TABLE economy_transactions ADD COLUMN sender_name VARCHAR(64)",
                "ALTER TABLE economy_transactions ADD COLUMN receiver_uuid VARCHAR(36)",
                "ALTER TABLE economy_transactions ADD COLUMN receiver_name VARCHAR(64)",
                "ALTER TABLE economy_transactions ADD COLUMN action VARCHAR(32)",
                "ALTER TABLE economy_transactions ADD COLUMN refund_status VARCHAR(32) DEFAULT 'NONE'"
        };
        for (String sql : columnsToAdd) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(sql);
            } catch (SQLException ignored) {}
        }
    }

    public void cleanOldTransactions(int retentionDays) {
        if (retentionDays <= 0 || dataSource == null) return;
        long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        String sql = "DELETE FROM economy_transactions WHERE timestamp < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                logger.info("Cleaned up " + rows + " expired transactions older than " + retentionDays + " days.");
            }
        } catch (SQLException e) {
            logger.warning("Failed to clean up old transactions: " + e.getMessage());
        }
    }

    public double getBalance(UUID uuid) {
        if (dataSource == null || uuid == null) return 0.0;
        String sql = "SELECT balance FROM economy_balances WHERE uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to get balance for " + uuid + ": " + e.getMessage());
        }
        return 0.0;
    }

    public boolean setBalance(UUID uuid, String playerName, double balance) {
        if (dataSource == null || uuid == null) return false;
        String sql;
        if (isMySQL) {
            sql = "INSERT INTO economy_balances (uuid, player_name, balance) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE player_name = ?, balance = ?";
        } else {
            sql = "INSERT OR REPLACE INTO economy_balances (uuid, player_name, balance) VALUES (?, ?, ?)";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName != null ? playerName : "Unknown");
            ps.setDouble(3, balance);
            if (isMySQL) {
                ps.setString(4, playerName != null ? playerName : "Unknown");
                ps.setDouble(5, balance);
            }
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warning("Failed to set balance for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    
    public boolean reverseDatabaseTransaction(long id, String admin) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement claim = conn.prepareStatement(
                        "UPDATE economy_transactions SET refund_status = 'REVERSING' WHERE id = ? AND COALESCE(refund_status, 'NONE') = 'NONE'")) {
                    claim.setLong(1, id);
                    if (claim.executeUpdate() != 1) { conn.rollback(); return false; }
                }
                String sender, receiver, senderName, receiverName, action;
                double amount;
                try (PreparedStatement select = conn.prepareStatement("SELECT * FROM economy_transactions WHERE id = ?")) {
                    select.setLong(1, id);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return false; }
                        sender = rs.getString("sender_uuid"); receiver = rs.getString("receiver_uuid");
                        senderName = rs.getString("sender_name"); receiverName = rs.getString("receiver_name");
                        action = rs.getString("action"); amount = rs.getDouble("amount");
                    }
                }
                if (!TransactionReversal.isSupported(action, sender, receiver, amount)) {
                    conn.rollback(); return false;
                }
                if ("PAY".equalsIgnoreCase(action) || "GIVE".equalsIgnoreCase(action)) {
                    try (PreparedStatement debit = conn.prepareStatement(
                            "UPDATE economy_balances SET balance = balance - ? WHERE uuid = ? AND balance >= ?")) {
                        debit.setDouble(1, amount); debit.setString(2, receiver); debit.setDouble(3, amount);
                        if (debit.executeUpdate() != 1) { conn.rollback(); return false; }
                    }
                }
                if ("PAY".equalsIgnoreCase(action) || "TAKE".equalsIgnoreCase(action)) {
                    try (PreparedStatement credit = conn.prepareStatement(
                            "UPDATE economy_balances SET balance = balance + ? WHERE uuid = ?")) {
                        credit.setDouble(1, amount); credit.setString(2, sender);
                        if (credit.executeUpdate() != 1) { conn.rollback(); return false; }
                    }
                }
                try (PreparedStatement finish = conn.prepareStatement("UPDATE economy_transactions SET refund_status = 'REVERSED' WHERE id = ?");
                     PreparedStatement audit = conn.prepareStatement(
                             "INSERT INTO economy_transactions (uuid, sender_uuid, sender_name, receiver_uuid, receiver_name, timestamp, description, amount, executor, action, refund_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REFUND', 'NOT_APPLICABLE')")) {
                    finish.setLong(1, id); finish.executeUpdate();
                    audit.setString(1, "GIVE".equalsIgnoreCase(action) ? receiver : sender);
                    audit.setString(2, "TAKE".equalsIgnoreCase(action) ? null : receiver);
                    audit.setString(3, "TAKE".equalsIgnoreCase(action) ? "System" : receiverName);
                    audit.setString(4, "GIVE".equalsIgnoreCase(action) ? null : sender);
                    audit.setString(5, "GIVE".equalsIgnoreCase(action) ? "System" : senderName);
                    audit.setLong(6, System.currentTimeMillis());
                    audit.setString(7, "Reversed #" + id + " (" + action + ")"); audit.setDouble(8, amount);
                    audit.setString(9, admin); audit.executeUpdate();
                }
                conn.commit(); return true;
            } catch (SQLException | RuntimeException e) {
                conn.rollback(); throw e;
            }
        } catch (SQLException | RuntimeException e) {
            logger.warning("Database reversal #" + id + " failed: " + e.getMessage());
            return false;
        }
    }

    
    public boolean compareAndSetRefundStatus(long id, String expected, String next) {
        if (dataSource == null) return false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE economy_transactions SET refund_status = ? WHERE id = ? AND COALESCE(refund_status, 'NONE') = ?")) {
            ps.setString(1, next);
            ps.setLong(2, id);
            ps.setString(3, expected);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logger.warning("Failed to claim/update reversal #" + id + ": " + e.getMessage());
            return false;
        }
    }

    public boolean updateRefundStatus(long id, String newStatus) {
        if (dataSource == null) return false;
        String sql = "UPDATE economy_transactions SET refund_status = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warning("Failed to update refund status for transaction #" + id + ": " + e.getMessage());
            return false;
        }
    }

    public void migrateFromYaml(Map<String, List<String>> config) {
        if (dataSource == null) return;
        String sql = "INSERT INTO economy_transactions (uuid, timestamp, description, amount, executor, refund_status) VALUES (?, ?, ?, ?, ?, 'NONE')";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (String uuid : config.keySet()) {
                for (String record : config.get(uuid)) {
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
            logger.info("Migrated transactions from YAML successfully.");
        } catch (Exception e) {
            logger.severe("Migration from YAML failed: " + e.getMessage());
        }
    }

    public void migrateLocalToRemote(File sqliteFile) {
        if (dataSource == null) return;
        logger.info("Migrating SQLite to MySQL...");
        String selectSql = "SELECT * FROM economy_transactions";
        String insertSql = "INSERT INTO economy_transactions (uuid, sender_uuid, sender_name, receiver_uuid, receiver_name, timestamp, description, amount, executor, action, refund_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
             Connection mysqlConn = dataSource.getConnection();
             Statement stmt = sqliteConn.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql);
             PreparedStatement ps = mysqlConn.prepareStatement(insertSql)) {

            mysqlConn.setAutoCommit(false);
            while (rs.next()) {
                ps.setString(1, rs.getString("uuid"));
                ps.setString(2, getColSafe(rs, "sender_uuid", rs.getString("uuid")));
                ps.setString(3, getColSafe(rs, "sender_name", "Unknown"));
                ps.setString(4, getColSafe(rs, "receiver_uuid", rs.getString("uuid")));
                ps.setString(5, getColSafe(rs, "receiver_name", "Unknown"));
                ps.setLong(6, rs.getLong("timestamp"));
                ps.setString(7, rs.getString("description"));
                ps.setDouble(8, rs.getDouble("amount"));
                ps.setString(9, rs.getString("executor"));
                ps.setString(10, getColSafe(rs, "action", "TRANSFER"));
                ps.setString(11, getColSafe(rs, "refund_status", "NONE"));
                ps.addBatch();
            }
            ps.executeBatch();
            mysqlConn.commit();
            logger.info("Remote migration to MySQL completed.");
            sqliteFile.renameTo(new File(dataFolder, "database_old.db"));
        } catch (Exception e) {
            logger.severe("Remote migration failed: " + e.getMessage());
        }
    }

    private String getColSafe(ResultSet rs, String col, String def) {
        try {
            String val = rs.getString(col);
            return val != null ? val : def;
        } catch (SQLException ignored) {
            return def;
        }
    }


    public HikariDataSource getDataSource() { return dataSource; }
    public boolean isMySQL() { return isMySQL; }
    public void close() { if (dataSource != null) dataSource.close(); }
}
