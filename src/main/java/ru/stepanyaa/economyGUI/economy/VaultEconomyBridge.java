package ru.stepanyaa.economyGUI.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import ru.stepanyaa.economyGUI.EconomyGUI;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class VaultEconomyBridge implements EconomyBridge {

    private final Economy vault;
    private final EconomyGUI plugin;
    private final AtomicBoolean warnedNotReady = new AtomicBoolean(false);

    public VaultEconomyBridge(EconomyGUI plugin, Economy vault) {
        this.plugin = plugin;
        this.vault = vault;
    }

    public static EconomyBridge create(EconomyGUI plugin) {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return null;
        }
        return new VaultEconomyBridge(plugin, registration.getProvider());
    }

    @Override
    public Object getNativeProvider() {
        return vault;
    }

    @Override
    public String getName() {
        return "Vault (" + (vault != null ? vault.getName() : "Unknown") + ")";
    }

    private void warnOnce(Throwable t) {
        if (warnedNotReady.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING,
                    "Economy provider not ready or failed (further errors suppressed until restart): " + t.getMessage());
        }
    }

    @Override
    public double getBalance(UUID uuid, String name) {
        if (vault == null) return 0.0;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            return vault.getBalance(player);
        } catch (Throwable t) {
            warnOnce(t);
            return 0.0;
        }
    }

    @Override
    public boolean deposit(UUID uuid, String name, double amount) {
        if (vault == null) return false;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            return vault.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    @Override
    public boolean withdraw(UUID uuid, String name, double amount) {
        if (vault == null) return false;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            if (vault.getBalance(player) < amount) return false;
            return vault.withdrawPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    @Override
    public boolean setBalance(UUID uuid, String name, double amount) {
        if (vault == null) return false;
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            double current = vault.getBalance(player);
            return vault.withdrawPlayer(player, current).transactionSuccess()
                    && vault.depositPlayer(player, amount).transactionSuccess();
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    @Override
    public String format(double amount) {
        if (vault != null) {
            try {
                return vault.format(amount);
            } catch (Throwable ignored) {
            }
        }
        return String.format("%.2f", amount);
    }

    public Economy getVault() {
        return vault;
    }
}
