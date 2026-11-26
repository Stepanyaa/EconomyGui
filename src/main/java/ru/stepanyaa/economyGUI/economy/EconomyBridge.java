package ru.stepanyaa.economyGUI.economy;

import java.util.UUID;

public interface EconomyBridge {
    String getName();
    default Object getNativeProvider() { return null; }
    double getBalance(UUID uuid, String name);
    boolean deposit(UUID uuid, String name, double amount);
    boolean withdraw(UUID uuid, String name, double amount);
    boolean setBalance(UUID uuid, String name, double amount);
    String format(double amount);
}
