package ru.stepanyaa.economyGUI.economy;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;

import java.util.UUID;

public class PlayerPointsEconomyBridge implements EconomyBridge {

    private final PlayerPointsAPI api;

    public PlayerPointsEconomyBridge(PlayerPointsAPI api) {
        this.api = api;
    }

    public static PlayerPointsEconomyBridge create() {
        try {
            PlayerPoints instance = PlayerPoints.getInstance();
            if (instance != null) {
                return new PlayerPointsEconomyBridge(instance.getAPI());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public String getName() {
        return "PlayerPoints";
    }

    @Override
    public double getBalance(UUID uuid, String name) {
        if (api == null || uuid == null) return 0.0;
        try {
            return api.look(uuid);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    @Override
    public boolean deposit(UUID uuid, String name, double amount) {
        if (api == null || uuid == null) return false;
        try {
            int pts = (int) Math.round(amount);
            if (pts <= 0) return true;
            return api.give(uuid, pts);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean withdraw(UUID uuid, String name, double amount) {
        if (api == null || uuid == null) return false;
        try {
            int pts = (int) Math.round(amount);
            if (pts <= 0) return true;
            int current = api.look(uuid);
            if (current < pts) return false;
            return api.take(uuid, pts);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean setBalance(UUID uuid, String name, double amount) {
        if (api == null || uuid == null) return false;
        try {
            int pts = (int) Math.round(amount);
            return api.set(uuid, Math.max(0, pts));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String format(double amount) {
        return String.valueOf((int) Math.round(amount));
    }
}
