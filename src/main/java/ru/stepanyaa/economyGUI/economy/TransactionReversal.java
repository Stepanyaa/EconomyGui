package ru.stepanyaa.economyGUI.economy;

import java.util.Locale;
import java.util.UUID;

public final class TransactionReversal {
    public enum Result { SUCCESS, FAILED, REVIEW_REQUIRED }
    private TransactionReversal() {}

    public static boolean isSupported(String action, String sender, String receiver, double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || action == null) return false;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "PAY":
                return valid(sender) && valid(receiver) && !sender.equalsIgnoreCase(receiver);
            case "GIVE": return valid(receiver);
            case "TAKE": return valid(sender);
            default: return false;
        }
    }

    private static boolean valid(String id) {
        try { return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id); }
        catch (IllegalArgumentException e) { return false; }
    }

    public static Result reverse(EconomyBridge economy, String action, String sender, String senderName,
                                 String receiver, String receiverName, double amount) {
        if (!isSupported(action, sender, receiver, amount)) return Result.FAILED;
        try {
            switch (action.toUpperCase(Locale.ROOT)) {
                case "GIVE":
                    return economy.withdraw(UUID.fromString(receiver), receiverName, amount)
                            ? Result.SUCCESS : Result.FAILED;
                case "TAKE":
                    return economy.deposit(UUID.fromString(sender), senderName, amount)
                            ? Result.SUCCESS : Result.FAILED;
                case "PAY":
                    UUID from = UUID.fromString(receiver);
                    UUID to = UUID.fromString(sender);
                    if (!economy.withdraw(from, receiverName, amount)) return Result.FAILED;
                    try {
                        if (economy.deposit(to, senderName, amount)) return Result.SUCCESS;
                    } catch (RuntimeException uncertainCredit) {
                        return Result.REVIEW_REQUIRED;
                    }
                    return economy.deposit(from, receiverName, amount)
                            ? Result.FAILED : Result.REVIEW_REQUIRED;
                default: return Result.FAILED;
            }
        } catch (RuntimeException uncertainResult) {
            return Result.REVIEW_REQUIRED;
        }
    }
}
