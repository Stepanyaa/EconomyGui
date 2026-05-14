/**
 * MIT License
 *
 * EconomyGui
 * Copyright (c) 2025 Stepanyaa
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
                        plugin.getLogger().warning(plugin.getMessage("warning.bad-transaction",
                                "Bad transaction for UUID %uuid%: %raw%",
                                "uuid", uuid,
                                "raw", raw));
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
        String key;
        String defaultMsg;

        switch (action.toLowerCase()) {
            case "pay":
                key = "history.paid-by";
                defaultMsg = "paid %amount% to %player%";
                break;
            case "receive":
                key = "history.received-from";
                defaultMsg = "received %amount% from %player%";
                break;
            case "give":
                key = "history.given-by";
                defaultMsg = "given %amount% by %player%";
                break;
            case "take":
                key = "history.taken-by";
                defaultMsg = "taken %amount% by %player%";
                break;
            case "set":
                key = "history.set-by";
                defaultMsg = "set balance to %amount% by %player%";
                break;
            default:
                key = "history.unknown";
                defaultMsg = action + " by %player%";
                break;
        }

        return plugin.getMessage(key, defaultMsg, "player", executorName);
    }
}