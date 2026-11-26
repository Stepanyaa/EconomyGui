package ru.stepanyaa.economyGUI.economy;

import ru.stepanyaa.economyGUI.database.DatabaseManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CustomDatabaseEconomyBridge implements EconomyBridge {

    private final DatabaseManager db;
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();

    public CustomDatabaseEconomyBridge(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public String getName() {
        return "Custom Database Economy";
    }

    @Override
    public double getBalance(UUID uuid, String name) {
        if (uuid == null) return 0.0;
        Double cached = balanceCache.get(uuid);
        if (cached != null) return cached;
        double bal = db.getBalance(uuid);
        balanceCache.put(uuid, bal);
        return bal;
    }

    @Override
    public boolean deposit(UUID uuid, String name, double amount) {
        if (uuid == null || !Double.isFinite(amount) || amount < 0) return false;
        double current = getBalance(uuid, name);
        double updated = current + amount;
        if (!Double.isFinite(updated) || !db.setBalance(uuid, name, updated)) return false;
        balanceCache.put(uuid, updated);
        return true;
    }

    @Override
    public boolean withdraw(UUID uuid, String name, double amount) {
        if (uuid == null || !Double.isFinite(amount) || amount < 0) return false;
        double current = getBalance(uuid, name);
        if (current < amount) return false;
        double updated = current - amount;
        if (!Double.isFinite(updated) || !db.setBalance(uuid, name, updated)) return false;
        balanceCache.put(uuid, updated);
        return true;
    }

    @Override
    public boolean setBalance(UUID uuid, String name, double amount) {
        if (uuid == null || !Double.isFinite(amount) || amount < 0) return false;
        if (!db.setBalance(uuid, name, amount)) return false;
        balanceCache.put(uuid, amount);
        return true;
    }

    @Override
    public String format(double amount) {
        return String.format("%.2f", amount);
    }
}
