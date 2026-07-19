package ru.stepanyaa.economyGUI;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.stepanyaa.economyGUI.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TransactionHandler {

    public static class Transaction {
        public final long timestamp;
        public final String description;
        public final double amount;
        public final String executor;

        public Transaction(long timestamp, String description, double amount, String executor) {
            this.timestamp = timestamp;
            this.description = description;
            this.amount = amount;
            this.executor = executor;
        }
    }

    private final EconomyGUI plugin;
    private final DatabaseManager dbManager;

    public TransactionHandler(EconomyGUI plugin, DatabaseManager dbManager) {
        this.plugin = plugin;
        this.dbManager = dbManager;
    }

    public void log(String uuid, String action, double amount, Player executor) {
        String execName = executor != null ? executor.getName() : "External";
        String desc = buildDesc(action, execName);
        long ts = System.currentTimeMillis();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO economy_transactions (uuid, timestamp, description, amount, executor) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = dbManager.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setLong(2, ts);
                ps.setString(3, desc);
                ps.setDouble(4, amount);
                ps.setString(5, execName);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(e.getMessage());
            }
        });
    }

    public CompletableFuture<List<Transaction>> getHistoryAsync(String uuid) {
        CompletableFuture<List<Transaction>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Transaction> list = new ArrayList<>();
            String sql = "SELECT * FROM economy_transactions WHERE uuid = ? ORDER BY timestamp DESC LIMIT 50";
            try (Connection conn = dbManager.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Transaction(rs.getLong("timestamp"), rs.getString("description"), rs.getDouble("amount"), rs.getString("executor")));
                    }
                }
                future.complete(list);
            } catch (SQLException e) {
                future.complete(list);
            }
        });
        return future;
    }

    private String buildDesc(String action, String execName) {
        return plugin.getMessage("history." + action, action + " by %player%", "player", execName);
    }
}