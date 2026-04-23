/**
 * MIT License
 * EconomyGui — Copyright (c) 2026 Stepanyaa
 */
package ru.stepanyaa.economyGUI;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


public class TransactionHandler {

    static class Transaction {
        final long timestamp;
        final String description;
        final double amount;
        final String executor;

        Transaction(long timestamp, String description, double amount, String executor) {
            this.timestamp = timestamp;
            this.description = description;
            this.amount = amount;
            this.executor = executor;
        }
    }

    private final Map<String, List<Transaction>> history = new ConcurrentHashMap<>();
    private final EconomyGUI plugin;

    public TransactionHandler(EconomyGUI plugin) {
        this.plugin = plugin;
    }

    public void log(String uuid, String action, double amount, Player executor) {
        String executorName = executor != null ? executor.getName() : "External";
        String description = buildDescription(action, executorName);
        history.computeIfAbsent(uuid, k -> new ArrayList<>())
                .add(new Transaction(System.currentTimeMillis(), description, amount, executorName));
    }

    public List<Transaction> getHistory(String uuid) {
        List<Transaction> list = history.getOrDefault(uuid, Collections.emptyList());
        list.sort(Comparator.comparingLong(t -> -t.timestamp));
        return Collections.unmodifiableList(list);
    }

    public void cleanOld() {
        int days = plugin.transactionRetentionDays;
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - (days * 86_400_000L);
        for (List<Transaction> list : history.values()) {
            list.removeIf(t -> t.timestamp < cutoff);
        }
        history.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    public void load(FileConfiguration config) {
        history.clear();
        for (String uuid : config.getKeys(false)) {
            List<Transaction> list = new ArrayList<>();
            for (String raw : config.getStringList(uuid)) {
                String[] parts = raw.split(";", 4);
                if (parts.length == 4) {
                    try {
                        long ts   = Long.parseLong(parts[0]);
                        String desc = parts[1];
                        double amt  = Double.parseDouble(parts[2]);
                        String exec = parts[3];
                        list.add(new Transaction(ts, desc, amt, exec));
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Bad transaction for UUID " + uuid + ": " + raw);
                    }
                }
            }
            if (!list.isEmpty()) history.put(uuid, list);
        }
        cleanOld();
    }

    public void save(FileConfiguration config) {
        cleanOld();
        for (String key : new HashSet<>(config.getKeys(false))) {
            if (!history.containsKey(key)) config.set(key, null);
        }
        for (Map.Entry<String, List<Transaction>> entry : history.entrySet()) {
            List<String> raw = entry.getValue().stream()
                    .map(t -> t.timestamp + ";" + t.description + ";" + t.amount + ";" + t.executor)
                    .collect(Collectors.toList());
            config.set(entry.getKey(), raw);
        }
    }

    private String buildDescription(String action, String executorName) {
        switch (action.toLowerCase()) {
            case "pay":     return "paid to " + executorName;
            case "receive": return "received from " + executorName;
            case "give":    return "given by " + executorName;
            case "take":    return "taken by " + executorName;
            case "set":     return "set by " + executorName;
            default:        return action + " (" + executorName + ")";
        }
    }
}