package ru.stepanyaa.economyGUI;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import ru.stepanyaa.economyGUI.database.DatabaseManager;
import ru.stepanyaa.economyGUI.economy.TransactionReversal;
import ru.stepanyaa.economyGUI.util.ServerScheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class TransactionHandler {

    public static class Transaction {
        public final long id;
        public final String uuid;
        public final String senderUuid;
        public final String senderName;
        public final String receiverUuid;
        public final String receiverName;
        public final long timestamp;
        public final String description;
        public final double amount;
        public final String executor;
        public final String action;
        public volatile String refundStatus;

        public Transaction(long id, String uuid, String senderUuid, String senderName,
                           String receiverUuid, String receiverName, long timestamp,
                           String description, double amount, String executor,
                           String action, String refundStatus) {
            this.id = id;
            this.uuid = uuid;
            this.senderUuid = senderUuid;
            this.senderName = senderName != null ? senderName : "Unknown";
            this.receiverUuid = receiverUuid;
            this.receiverName = receiverName != null ? receiverName : "Unknown";
            this.timestamp = timestamp;
            this.description = description;
            this.amount = amount;
            this.executor = executor != null ? executor : "System";
            this.action = action != null ? action : "TRANSFER";
            this.refundStatus = refundStatus != null ? refundStatus : "NONE";
        }

        public boolean canReverse() {
            return "NONE".equalsIgnoreCase(refundStatus)
                    && TransactionReversal.isSupported(action, senderUuid, receiverUuid, amount);
        }

        public boolean isRefundedToSender() {
            return "REFUNDED_SENDER".equalsIgnoreCase(refundStatus) || "REFUNDED_BOTH".equalsIgnoreCase(refundStatus);
        }

        public boolean isRefundedToReceiver() {
            return "REFUNDED_RECEIVER".equalsIgnoreCase(refundStatus) || "REFUNDED_BOTH".equalsIgnoreCase(refundStatus);
        }

        public boolean isFullyRefunded() {
            return "REVERSED".equalsIgnoreCase(refundStatus) || "REFUNDED_BOTH".equalsIgnoreCase(refundStatus);
        }
    }

    private final EconomyGUI plugin;
    private final DatabaseManager dbManager;
    private final Consumer<Runnable> economyScheduler;
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EconomyGUI-history");
        thread.setDaemon(true);
        return thread;
    });

    public TransactionHandler(EconomyGUI plugin, DatabaseManager dbManager) {
        this(plugin, dbManager, task -> ServerScheduler.runTask(plugin, task));
    }

    TransactionHandler(EconomyGUI plugin, DatabaseManager dbManager, Consumer<Runnable> economyScheduler) {
        this.plugin = plugin;
        this.dbManager = dbManager;
        this.economyScheduler = economyScheduler;
    }

    private CompletableFuture<Void> runDatabase(Runnable task) {
        return CompletableFuture.runAsync(task, databaseExecutor);
    }

    private <T> CompletableFuture<T> supplyDatabase(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, databaseExecutor);
    }

    public void close() {
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("History queue did not finish before shutdown.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void log(String senderUuid, String senderName, String receiverUuid, String receiverName,
                    String action, double amount, String executorName, String desc) {
        long ts = System.currentTimeMillis();
        runDatabase(() -> {
            String sql = "INSERT INTO economy_transactions " +
                    "(uuid, sender_uuid, sender_name, receiver_uuid, receiver_name, timestamp, description, amount, executor, action, refund_status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NONE')";
            try (Connection conn = dbManager.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                String primaryUuid = senderUuid != null ? senderUuid : receiverUuid;
                ps.setString(1, primaryUuid);
                ps.setString(2, senderUuid);
                ps.setString(3, senderName);
                ps.setString(4, receiverUuid);
                ps.setString(5, receiverName);
                ps.setLong(6, ts);
                ps.setString(7, desc);
                ps.setDouble(8, amount);
                ps.setString(9, executorName);
                ps.setString(10, action);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to log transaction: " + e.getMessage());
            }
        });
    }

    public void logPayment(OfflinePlayer sender, OfflinePlayer receiver, double amount) {
        String sName = sender.getName() != null ? sender.getName() : "Unknown";
        String rName = receiver.getName() != null ? receiver.getName() : "Unknown";
        String formattedAmt = plugin.getEconomyBridge().format(amount);
        String desc = plugin.getMessage("history.paid-by", "Paid %amount% to %player%",
                "amount", formattedAmt, "player", rName);
        log(sender.getUniqueId().toString(), sName,
                receiver.getUniqueId().toString(), rName,
                "PAY", amount, sName, desc);
    }

    public void log(String uuid, String action, double amount, CommandSender executor) {
        log(uuid, null, action, amount, executor);
    }

    public void log(String uuid, String targetName, String action, double amount, CommandSender executor) {
        String execName = executor != null ? executor.getName() : "External";
        String desc = buildDesc(action, execName, amount);
        String act = action.toUpperCase();
        if ("give".equalsIgnoreCase(action)) {
            log(null, "System",
                    uuid, targetName, "GIVE", amount, execName, desc);
        } else if ("take".equalsIgnoreCase(action)) {
            log(uuid, targetName,
                    null, "System",
                    "TAKE", amount, execName, desc);
        } else if ("set".equalsIgnoreCase(action)) {
            log(null, "System",
                    uuid, targetName, "SET", amount, execName, desc);
        } else {
            log(uuid, targetName, uuid, targetName, act, amount, execName, desc);
        }
    }

    public CompletableFuture<List<Transaction>> getHistoryAsync(String uuid) {
        CompletableFuture<List<Transaction>> future = new CompletableFuture<>();
        runDatabase(() -> {
            List<Transaction> list = new ArrayList<>();
            String sql = "SELECT * FROM economy_transactions WHERE uuid = ? OR sender_uuid = ? OR receiver_uuid = ? ORDER BY timestamp DESC LIMIT 500";
            try (Connection conn = dbManager.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setString(2, uuid);
                ps.setString(3, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String rowUuid = rs.getString("uuid");
                        String sUuid = getColumnStringSafe(rs, "sender_uuid", null);
                        String sName = getColumnStringSafe(rs, "sender_name", "Unknown");
                        String rUuid = getColumnStringSafe(rs, "receiver_uuid", null);
                        String rName = getColumnStringSafe(rs, "receiver_name", "Unknown");
                        long ts = rs.getLong("timestamp");
                        String desc = rs.getString("description");
                        double amt = rs.getDouble("amount");
                        String exec = rs.getString("executor");
                        String act = getColumnStringSafe(rs, "action", "TRANSFER");
                        String refStatus = getColumnStringSafe(rs, "refund_status", "NONE");
                        list.add(new Transaction(id, rowUuid, sUuid, sName, rUuid, rName, ts, desc, amt, exec, act, refStatus));
                    }
                }
                future.complete(list);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load history for " + uuid + ": " + e.getMessage());
                future.complete(list);
            }
        });
        return future;
    }

    public CompletableFuture<Transaction> getTransactionByIdAsync(long id) {
        CompletableFuture<Transaction> future = new CompletableFuture<>();
        runDatabase(() -> {
            String sql = "SELECT * FROM economy_transactions WHERE id = ?";
            try (Connection conn = dbManager.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String rowUuid = rs.getString("uuid");
                        String sUuid = getColumnStringSafe(rs, "sender_uuid", null);
                        String sName = getColumnStringSafe(rs, "sender_name", "Unknown");
                        String rUuid = getColumnStringSafe(rs, "receiver_uuid", null);
                        String rName = getColumnStringSafe(rs, "receiver_name", "Unknown");
                        long ts = rs.getLong("timestamp");
                        String desc = rs.getString("description");
                        double amt = rs.getDouble("amount");
                        String exec = rs.getString("executor");
                        String act = getColumnStringSafe(rs, "action", "TRANSFER");
                        String refStatus = getColumnStringSafe(rs, "refund_status", "NONE");
                        future.complete(new Transaction(id, rowUuid, sUuid, sName, rUuid, rName, ts, desc, amt, exec, act, refStatus));
                        return;
                    }
                }
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load transaction #" + id + ": " + e.getMessage());
                future.complete(null);
            }
        });
        return future;
    }

    
    public CompletableFuture<Boolean> refundTransaction(long id, String targetType, String adminName) {
        if (!("reverse".equalsIgnoreCase(targetType) || "sender".equalsIgnoreCase(targetType)
                || "receiver".equalsIgnoreCase(targetType) || "both".equalsIgnoreCase(targetType))) {
            return CompletableFuture.completedFuture(false);
        }
        return getTransactionByIdAsync(id).thenCompose(tx -> {
            if (tx == null || !tx.canReverse()) return CompletableFuture.completedFuture(false);
            return supplyDatabase(() -> dbManager.compareAndSetRefundStatus(id, "NONE", "REVERSING"))
                    .thenCompose(claimed -> {
                        if (!claimed) return CompletableFuture.completedFuture(false);
                        CompletableFuture<Boolean> result = new CompletableFuture<>();
                        try {
                            economyScheduler.accept(() -> {
                                TransactionReversal.Result outcome = TransactionReversal.reverse(
                                        plugin.getEconomyBridge(), tx.action, tx.senderUuid, tx.senderName,
                                        tx.receiverUuid, tx.receiverName, tx.amount);
                                supplyDatabase(() -> {
                                    if (outcome == TransactionReversal.Result.SUCCESS) {
                                        return finishReversal(tx, adminName);
                                    }
                                    String state = outcome == TransactionReversal.Result.FAILED ? "NONE" : "REVIEW_REQUIRED";
                                    dbManager.compareAndSetRefundStatus(id, "REVERSING", state);
                                    if (outcome == TransactionReversal.Result.REVIEW_REQUIRED) {
                                        plugin.getLogger().severe("Reversal #" + id + " requires manual balance review. Retry blocked.");
                                    }
                                    return false;
                                }).whenComplete((ok, error) -> {
                                    if (error != null) result.completeExceptionally(error);
                                    else result.complete(ok);
                                });
                            });
                        } catch (RuntimeException schedulingFailure) {
                            dbManager.compareAndSetRefundStatus(id, "REVERSING", "NONE");
                            result.complete(false);
                        }
                        return result;
                    });
        });
    }

    
    private boolean finishReversal(Transaction tx, String adminName) {
        try (Connection conn = dbManager.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE economy_transactions SET refund_status = 'REVERSED' WHERE id = ? AND refund_status = 'REVERSING'");
                 PreparedStatement audit = conn.prepareStatement(
                    "INSERT INTO economy_transactions (uuid, sender_uuid, sender_name, receiver_uuid, receiver_name, timestamp, description, amount, executor, action, refund_status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REFUND', 'NOT_APPLICABLE')")) {
                update.setLong(1, tx.id);
                if (update.executeUpdate() != 1) throw new SQLException("Reversal claim was lost");
                audit.setString(1, "GIVE".equalsIgnoreCase(tx.action) ? tx.receiverUuid : tx.senderUuid);
                audit.setString(2, "TAKE".equalsIgnoreCase(tx.action) ? null : tx.receiverUuid);
                audit.setString(3, "TAKE".equalsIgnoreCase(tx.action) ? "System" : tx.receiverName);
                audit.setString(4, "GIVE".equalsIgnoreCase(tx.action) ? null : tx.senderUuid);
                audit.setString(5, "GIVE".equalsIgnoreCase(tx.action) ? "System" : tx.senderName);
                audit.setLong(6, System.currentTimeMillis());
                audit.setString(7, "Reversed #" + tx.id + " (" + tx.action + ")");
                audit.setDouble(8, tx.amount);
                audit.setString(9, adminName);
                audit.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Reversal #" + tx.id + " applied but audit failed; retry blocked: " + e.getMessage());
            dbManager.compareAndSetRefundStatus(tx.id, "REVERSING", "REVIEW_REQUIRED");
            return false;
        }
    }

    public CompletableFuture<Boolean> updateRefundStatusAsync(long id, String newStatus) {
        return supplyDatabase(() -> dbManager.updateRefundStatus(id, newStatus));
    }

    public void cleanOldAsync(int retentionDays) {
        runDatabase(() -> dbManager.cleanOldTransactions(retentionDays));
    }

    private String buildDesc(String action, String execName, double amount) {
        String formattedAmt = plugin.getEconomyBridge().format(amount);
        String key = "history." + action.toLowerCase();
        if ("give".equalsIgnoreCase(action)) key = "history.given-by";
        else if ("take".equalsIgnoreCase(action)) key = "history.taken-by";
        else if ("set".equalsIgnoreCase(action)) key = "history.set-by";
        else if ("pay".equalsIgnoreCase(action)) key = "history.paid-by";
        else if ("receive".equalsIgnoreCase(action)) key = "history.received-from";

        return plugin.getMessage(key, action + " " + formattedAmt + " by " + execName,
                "player", execName, "amount", formattedAmt);
    }

    private String getColumnStringSafe(ResultSet rs, String col, String def) {
        try {
            String val = rs.getString(col);
            return val != null ? val : def;
        } catch (SQLException ignored) {
            return def;
        }
    }
}
