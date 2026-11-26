package ru.stepanyaa.economyGUI;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.stepanyaa.economyGUI.database.DatabaseManager;
import ru.stepanyaa.economyGUI.compat.PluginMetrics;
import ru.stepanyaa.economyGUI.economy.CustomDatabaseEconomyBridge;
import ru.stepanyaa.economyGUI.economy.EconomyBridge;
import ru.stepanyaa.economyGUI.economy.PlayerPointsEconomyBridge;
import ru.stepanyaa.economyGUI.economy.VaultEconomyBridge;
import ru.stepanyaa.economyGUI.util.ServerPlatform;
import ru.stepanyaa.economyGUI.util.ServerScheduler;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EconomyGUI extends JavaPlugin implements Listener {

    private final Map<UUID, PendingObservation> pendingExternalChanges = new ConcurrentHashMap<>();

    private final class PendingObservation {
        private final UUID[] players;
        private final Runnable check;
        private boolean settled;
        PendingObservation(Runnable check, UUID... players) { this.check = check; this.players = players; }
        synchronized void settle() {
            if (settled) return;
            settled = true;
            for (UUID player : players) pendingExternalChanges.remove(player, this);
            check.run();
        }
    }

    private void settleExternalChanges(UUID... players) {
        for (UUID player : players) {
            PendingObservation pending = pendingExternalChanges.get(player);
            if (pending != null) pending.settle();
        }
    }

    private void observeNextTick(Runnable check, UUID... players) {
        PendingObservation pending = new PendingObservation(check, players);
        for (UUID player : players) pendingExternalChanges.put(player, pending);
        ServerScheduler.runTaskLater(this, pending::settle, 1L);
    }

    private Runnable metricsShutdown = () -> {};

    private EconomyBridge economyBridge;
    private String language;
    private static final String CURRENT_VERSION = "2.2.0";
    private EconomySearchGUI economySearchGUI;
    private LanguageManager languageManager;
    private final Set<String> adminUUIDs = ConcurrentHashMap.newKeySet();
    private String latestVersion = null;
    private boolean playerSelectionEnabled;
    private boolean massOperationsEnabled;
    private boolean quickActionsEnabled;
    private boolean fullManagementEnabled;
    private boolean checkForUpdatesEnabled;
    public int transactionRetentionDays;
    public double maxAmount;
    private DatabaseManager databaseManager;
    private TransactionHandler transactionHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        languageManager = new LanguageManager(this);
        languageManager.loadLanguages();
        applyConfig();
        languageManager.setLanguage(getConfig().getString("language", "en"));

        databaseManager = new DatabaseManager(getLogger(), getDataFolder());
        File sqliteFile = new File(getDataFolder(), "database.db");

        if (getConfig().getBoolean("mysql.enabled")) {
            databaseManager.connectMySQL(
                    getConfig().getString("mysql.host"),
                    getConfig().getInt("mysql.port"),
                    getConfig().getString("mysql.database"),
                    getConfig().getString("mysql.username"),
                    getConfig().getString("mysql.password")
            );
            if (sqliteFile.exists()) {
                databaseManager.migrateLocalToRemote(sqliteFile);
            }
        } else {
            databaseManager.connectSQLite(sqliteFile);
        }

        File oldTransactions = new File(getDataFolder(), "transactions.yml");
        if (oldTransactions.exists()) {
            YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(oldTransactions);
            Map<String, List<String>> records = new HashMap<>();
            for (String key : oldConfig.getKeys(false)) records.put(key, oldConfig.getStringList(key));
            databaseManager.migrateFromYaml(records);
            oldTransactions.renameTo(new File(getDataFolder(), "transactions_old_backup.yml"));
        }

        transactionHandler = new TransactionHandler(this, databaseManager);

        setupEconomy();

        economySearchGUI = new EconomySearchGUI(this);
        getServer().getPluginManager().registerEvents(economySearchGUI, this);
        getServer().getPluginManager().registerEvents(economySearchGUI.getPlayerCache(), this);
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("economygui");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        if (transactionRetentionDays > 0) {
            transactionHandler.cleanOldAsync(transactionRetentionDays);
            schedulePeriodicCleanup();
        }

        try { metricsShutdown = PluginMetrics.start(this); }
        catch (RuntimeException | LinkageError unavailable) { getLogger().fine("Telemetry unavailable: " + unavailable); }

        getLogger().info("EconomyGUI v" + CURRENT_VERSION + " successfully loaded on " +
                ServerPlatform.getPlatformName() + " using economy provider: " + economyBridge.getName());

        ServerScheduler.runTaskLater(this, this::refreshEconomyProvider, 40L);
        ServerScheduler.runTaskLater(this, this::refreshEconomyProvider, 100L);
    }

    private void refreshEconomyProvider() {
        try {
            if (setupVault()) {
                getLogger().info("Economy provider refreshed: " + economyBridge.getName());
            }
        } catch (Throwable t) {
            getLogger().fine("Economy provider refresh skipped: " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (transactionHandler != null) transactionHandler.close();
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (metricsShutdown != null) {
            try { metricsShutdown.run(); }
            catch (RuntimeException | LinkageError ignored) {}
        }
    }

    private void schedulePeriodicCleanup() {
        ServerScheduler.runAsyncLater(this, () -> {
            if (transactionRetentionDays > 0) {
                transactionHandler.cleanOldAsync(transactionRetentionDays);
                schedulePeriodicCleanup();
            }
        }, 20L * 60L * 60L * 24L);
    }

    private boolean setupEconomy() {
        if (ServerPlatform.isFolia()) {
            if (setupPlayerPoints()) {
                getLogger().info("Folia detected: enabled PlayerPoints economy provider.");
                return true;
            }
            if (setupVault()) {
                getLogger().info("Folia detected: PlayerPoints not found, using Vault economy.");
                return true;
            }
            setupCustomEconomy();
            getLogger().info("Folia detected: Vault/PlayerPoints not found, using built-in Custom Database Economy.");
            return true;
        }

        if (setupVault()) {
            return true;
        }
        if (setupPlayerPoints()) {
            getLogger().info("Vault not found. Enabled PlayerPoints economy provider.");
            return true;
        }
        setupCustomEconomy();
        getLogger().info("Vault/PlayerPoints not found. Enabled built-in Custom Database Economy.");
        return true;
    }

    private boolean setupVault() {
        Plugin vaultPlugin = getServer().getPluginManager().getPlugin("Vault");
        if (vaultPlugin == null || !vaultPlugin.isEnabled()) return false;
        try {
            EconomyBridge bridge = VaultEconomyBridge.create(this);
            if (bridge != null) { economyBridge = bridge; return true; }
        } catch (RuntimeException | LinkageError unavailable) {
            getLogger().warning("Vault API unavailable/incompatible, trying another economy: " + unavailable);
        }
        return false;
    }

    private boolean setupPlayerPoints() {
        Plugin ppPlugin = getServer().getPluginManager().getPlugin("PlayerPoints");
        if (ppPlugin == null || !ppPlugin.isEnabled()) return false;
        try {
            EconomyBridge bridge = PlayerPointsEconomyBridge.create();
            if (bridge != null) { economyBridge = bridge; return true; }
        } catch (RuntimeException | LinkageError unavailable) {
            getLogger().warning("PlayerPoints API unavailable/incompatible, trying another economy: " + unavailable);
        }
        return false;
    }

    private void setupCustomEconomy() {
        economyBridge = new CustomDatabaseEconomyBridge(databaseManager);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();
        if ("pay".equals(cmdName) || "epay".equals(cmdName)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + getMessage(sender, "error.player-only", "This command is for players only!"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /pay <player> <amount>");
                return true;
            }
            Player pSender = (Player) sender;
            Player pTarget = Bukkit.getPlayer(args[0]);
            if (pTarget == null) {
                sender.sendMessage(ChatColor.RED + "Player not found!");
                return true;
            }
            if (pTarget.getUniqueId().equals(pSender.getUniqueId())) {
                sender.sendMessage(ChatColor.RED + "You cannot pay yourself!");
                return true;
            }
            try {
                double amt = Double.parseDouble(args[1]);
                if (!Double.isFinite(amt) || amt <= 0 || (maxAmount > 0 && amt > maxAmount)) {
                    sender.sendMessage(ChatColor.RED + "Amount must be positive!");
                    return true;
                }
                settleExternalChanges(pSender.getUniqueId(), pTarget.getUniqueId());
                double bal = economyBridge.getBalance(pSender.getUniqueId(), pSender.getName());
                if (bal < amt) {
                    sender.sendMessage(ChatColor.RED + "Insufficient funds!");
                    return true;
                }
                if (!economyBridge.withdraw(pSender.getUniqueId(), pSender.getName(), amt)) {
                    sender.sendMessage(ChatColor.RED + "Payment failed!");
                    return true;
                }
                if (economyBridge.deposit(pTarget.getUniqueId(), pTarget.getName(), amt)) {
                    pSender.sendMessage(ChatColor.GREEN + "You paid $" + String.format("%,.2f", amt) + " to " + pTarget.getName());
                    pTarget.sendMessage(ChatColor.GREEN + "You received $" + String.format("%,.2f", amt) + " from " + pSender.getName());
                    transactionHandler.logPayment(pSender, pTarget, amt);
                } else {
                    if (!economyBridge.deposit(pSender.getUniqueId(), pSender.getName(), amt)) {
                        getLogger().severe("Payment compensation failed for " + pSender.getUniqueId() + ": " + amt);
                    }
                    sender.sendMessage(ChatColor.RED + "Payment failed!");
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid number format!");
            }
            return true;
        }

        if ("balance".equals(cmdName) || "money".equals(cmdName) || "bal".equals(cmdName) || "ebal".equals(cmdName)) {
            OfflinePlayer target = null;
            if (args.length > 0) {
                target = Bukkit.getOfflinePlayer(args[0]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Usage: /balance [player]");
                return true;
            }
            double bal = economyBridge.getBalance(target.getUniqueId(), target.getName());
            sender.sendMessage(ChatColor.GOLD + (target.getName() != null ? target.getName() : "Player") + "'s Balance: " + ChatColor.GREEN + "$" + String.format("%,.2f", bal));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "gui";

        if ("give".equals(sub) || "take".equals(sub) || "set".equals(sub)) {
            return handleEconomyChange(sender, sub, args);
        }

        if ("refund".equals(sub)) {
            if (!sender.hasPermission("economygui.admin") && !sender.hasPermission("economygui.refund")) {
                sender.sendMessage(ChatColor.RED + getMessage(sender, "error.no-permission", "You don't have permission!"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + getMessage(sender, "command.usage-reverse", "Usage: /economygui refund <id> [reverse]"));
                return true;
            }
            handleCommandRefund(sender, args[1], args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "reverse");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "error.player-only", "This command is for players only!"));
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("economygui.admin") && !player.hasPermission("economygui.gui")) {
            player.sendMessage(ChatColor.RED + getMessage(player, "error.no-permission", "You don't have permission!"));
            return true;
        }

        switch (sub) {
            case "gui":
                if (args.length > 1) {
                    player.sendMessage(ChatColor.RED + getMessage(player, "command.usage-gui", "Usage: /economygui gui"));
                    return true;
                }
                economySearchGUI.openLastGUIMenu(player);
                return true;
            case "reload":
                if (!player.hasPermission("economygui.reload")) {
                    player.sendMessage(ChatColor.RED + getMessage(player, "error.no-permission", "You don't have permission!"));
                    return true;
                }
                if (args.length != 1) {
                    player.sendMessage(ChatColor.RED + getMessage(player, "command.usage-reload", "Usage: /economygui reload"));
                    return true;
                }
                reloadPlugin(player);
                return true;
            case "reset":
                if (!player.hasPermission("economygui.reset")) {
                    player.sendMessage(ChatColor.RED + getMessage(player, "error.no-permission", "You don't have permission!"));
                    return true;
                }
                if (args.length != 1) {
                    player.sendMessage(ChatColor.RED + getMessage(player, "command.usage", "Usage: /economygui reset"));
                    return true;
                }
                economySearchGUI.resetSearch(player);
                return true;
            default:
                player.sendMessage(ChatColor.RED + getMessage(player, "command.usage", "Usage: /economygui <gui | reload | reset | refund>"));
                return true;
        }
    }

    private boolean handleEconomyChange(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission("economygui.admin") && !sender.hasPermission("economygui.give")) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "error.no-permission", "You don't have permission!"));
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /economygui " + action + " <player> <amount>");
            return true;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[2]);
            if (!Double.isFinite(amount) || amount < 0 || (!"set".equals(action) && amount == 0)
                    || (maxAmount > 0 && amount > maxAmount)) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.invalid-amount", "Invalid amount or configured limit exceeded."));
            return true;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.unknown-player", "Player not found."));
            return true;
        }
        UUID uuid = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : args[1];
        settleExternalChanges(uuid);
        double before = economyBridge.getBalance(uuid, name);
        boolean ok;
        if ("give".equals(action)) ok = economyBridge.deposit(uuid, name, amount);
        else if ("take".equals(action)) ok = economyBridge.withdraw(uuid, name, amount);
        else ok = economyBridge.setBalance(uuid, name, amount);
        if (!ok) {
            sender.sendMessage(ChatColor.RED + getMessage(sender, "command.operation-failed", "Operation failed (check balance and economy provider)."));
            return true;
        }
        double after = economyBridge.getBalance(uuid, name);
        double recorded = "set".equals(action) ? after : Math.abs(after - before);
        if ("set".equals(action) || recorded > 0) transactionHandler.log(uuid.toString(), name, action, recorded, sender);
        sender.sendMessage(ChatColor.GREEN + getMessage(sender, "command.operation-success",
                "%action%: %player%, amount %amount%, balance %balance%", "action", action,
                "player", name, "amount", economyBridge.format(recorded), "balance", economyBridge.format(after)));
        return true;
    }

    private void handleCommandRefund(CommandSender sender, String idStr, String targetStr) {
        final long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid transaction ID: " + idStr);
            return;
        }
        transactionHandler.refundTransaction(id, targetStr, sender.getName()).whenComplete((ok, error) ->
                ServerScheduler.runTask(this, () -> sender.sendMessage(
                        error == null && Boolean.TRUE.equals(ok)
                                ? ChatColor.GREEN + getMessage(sender, "refund.reverse-success", "Transaction #%id% reversed.", "id", String.valueOf(id))
                                : ChatColor.RED + getMessage(sender, "refund.reverse-failed",
                                "Cannot reverse #%id%: insufficient funds, unsupported operation, already processed or manual review required.", "id", String.valueOf(id)))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("gui", "reload", "reset", "refund", "give", "take", "set").stream()
                    .filter(cmd -> sender.hasPermission("economygui." + cmd) || (("take".equals(cmd) || "set".equals(cmd)) && sender.hasPermission("economygui.give")) || sender.hasPermission("economygui.admin"))
                    .filter(cmd -> cmd.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && "refund".equalsIgnoreCase(args[0])) {
            return Collections.singletonList("reverse").stream()
                    .filter(sub -> sub.startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExternalEconomyCommand(PlayerCommandPreprocessEvent event) {
        observeExternalEconomyCommand(event.getPlayer(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExternalServerEconomyCommand(ServerCommandEvent event) {
        observeExternalEconomyCommand(event.getSender(), event.getCommand());
    }

    private void observeExternalEconomyCommand(CommandSender executor, String raw) {
        String[] args = raw.replaceFirst("^/", "").trim().split("\\s+");
        if (args.length != 4) return;
        String label = args[0].toLowerCase(Locale.ROOT);
        String base = label.substring(label.lastIndexOf(':') + 1);
        if (!("economy".equals(base) || "eco".equals(base) || "economygui".equals(base)
                || "egui".equals(base) || "ecogui".equals(base))) return;
        PluginCommand command = Bukkit.getPluginCommand(label);
        if (command == null || command.getPlugin() == this) return; 
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!("give".equals(action) || "take".equals(action) || "set".equals(action))) return;
        try {
            double requested = Double.parseDouble(args[3]);
            if (!Double.isFinite(requested) || requested < 0) return;
        } catch (NumberFormatException ignored) { return; }
        OfflinePlayer found = Bukkit.getPlayerExact(args[2]);
        if (found == null) found = Bukkit.getOfflinePlayer(args[2]);
        if (!found.isOnline() && !found.hasPlayedBefore()) return;
        final OfflinePlayer target = found;
        UUID uuid = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : args[2];
        settleExternalChanges(uuid);
        double before = safeGetBalance(target);
        observeNextTick(() -> {
            double after = safeGetBalance(target);
            double delta = after - before;
            if (("give".equals(action) && delta > 0) || ("take".equals(action) && delta < 0)
                    || ("set".equals(action) && delta != 0)) {
                transactionHandler.log(uuid.toString(), name, action,
                        "set".equals(action) ? after : Math.abs(delta), executor);
            }
        }, uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPayCommandAfter(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (!isPayCommand(raw)) return;
        String[] args = raw.split("\\s+");
        String label = args[0].substring(1).toLowerCase(Locale.ROOT);
        PluginCommand command = Bukkit.getPluginCommand(label);
        if (command == null || command.getPlugin() == this) return; 
        String base = label.substring(label.lastIndexOf(':') + 1);
        int playerArgIndex = "money".equals(base) ? 2 : 1;
        Player sender = event.getPlayer();
        OfflinePlayer target = Bukkit.getPlayerExact(args[playerArgIndex]);
        if (target == null) target = Bukkit.getOfflinePlayer(args[playerArgIndex]);
        if (target.getUniqueId().equals(sender.getUniqueId())) return;
        final OfflinePlayer finalTarget = target;
        settleExternalChanges(sender.getUniqueId(), target.getUniqueId());
        double before = safeGetBalance(sender);
        double receiverBefore = safeGetBalance(target);
        observeNextTick(() -> {
            double debit = before - safeGetBalance(sender);
            double credit = safeGetBalance(finalTarget) - receiverBefore;
            if (debit > 0.001 && Math.abs(debit - credit) < 0.001) {
                transactionHandler.logPayment(sender, finalTarget, debit);
            }
        }, sender.getUniqueId(), target.getUniqueId());
    }

    private boolean isPayCommand(String message) {
        String[] args = message.split("\\s+");
        if (args.length < 3) return false;
        String label = args[0].replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        String cmd = label.substring(label.lastIndexOf(':') + 1);
        return cmd.equals("pay") || cmd.equals("epay")
                || (cmd.equals("money") && args.length >= 4 && args[1].equalsIgnoreCase("pay"));
    }

    
    @Deprecated
    public Object getEconomy() {
        return economyBridge == null ? null : economyBridge.getNativeProvider();
    }

    public EconomyBridge getEconomyBridge() {
        return economyBridge;
    }

    public double safeGetBalance(OfflinePlayer player) {
        if (economyBridge == null || player == null) {
            return 0.0;
        }
        try {
            return economyBridge.getBalance(player.getUniqueId(), player.getName());
        } catch (Throwable t) {
            return 0.0;
        }
    }

    public boolean isPlayerSelectionEnabled() { return playerSelectionEnabled; }
    public boolean isMassOperationsEnabled()  { return massOperationsEnabled; }
    public boolean isQuickActionsEnabled()    { return quickActionsEnabled; }
    public boolean isFullManagementEnabled()  { return fullManagementEnabled; }
    public Set<String> getAdminUUIDs()        { return adminUUIDs; }

    public boolean isWithinMaxAmount(double amount) {
        return maxAmount <= 0 || amount <= maxAmount;
    }

    public String getMessage(String key, String def) {
        if (languageManager == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        return ChatColor.translateAlternateColorCodes('&', languageManager.getMessage(key, def));
    }

    public String getMessage(String key, String def, Object... placeholders) {
        if (languageManager == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        String[] replacements = toReplacements(placeholders);
        return ChatColor.translateAlternateColorCodes('&', languageManager.getMessage(key, def, replacements));
    }

    public String getMessage(org.bukkit.command.CommandSender sender, String key, String def) {
        if (languageManager == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        return ChatColor.translateAlternateColorCodes('&', languageManager.getMessage(sender, key, def));
    }

    public String getMessage(org.bukkit.command.CommandSender sender, String key, String def, Object... placeholders) {
        if (languageManager == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        String[] replacements = toReplacements(placeholders);
        return ChatColor.translateAlternateColorCodes('&', languageManager.getMessage(sender, key, def, replacements));
    }

    public String getMessage(Player player, String key, String def) {
        return getMessage((org.bukkit.command.CommandSender) player, key, def);
    }

    public String getMessage(Player player, String key, String def, Object... placeholders) {
        return getMessage((org.bukkit.command.CommandSender) player, key, def, placeholders);
    }

    private static String[] toReplacements(Object... placeholders) {
        String[] replacements = new String[placeholders.length];
        for (int i = 0; i < placeholders.length; i++) {
            replacements[i] = placeholders[i] == null ? "null" : placeholders[i].toString();
        }
        return replacements;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public void reloadPlugin(Player player) {
        reloadConfig();
        applyConfig();
        languageManager.reload();
        updateConfigFile();
        economySearchGUI.recreateInventory();
        economySearchGUI.getPlayerCache().rebuild();
        economySearchGUI.refreshOpenGUIs();
        player.sendMessage(ChatColor.GREEN + getMessage(player, "action.config-reloaded", "Configuration reloaded."));
    }

    private void applyConfig() {
        language = getConfig().getString("language", "en");
        playerSelectionEnabled = getConfig().getBoolean("features.player-selection", true);
        massOperationsEnabled  = getConfig().getBoolean("features.mass-operations", true);
        quickActionsEnabled    = getConfig().getBoolean("features.quick-actions", true);
        fullManagementEnabled  = getConfig().getBoolean("features.full-management", true);
        transactionRetentionDays = getConfig().getInt("features.transaction-retention-days", 30);
        maxAmount = getConfig().getDouble("features.max-amount", 0);
        checkForUpdatesEnabled = getConfig().getBoolean("check-for-updates", true);
    }

    private void updateConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            return;
        }
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
        String fileVersion = existing.getString("config-version", "0.0.0");
        if (fileVersion.equals(CURRENT_VERSION)) return;

        if (getResource("config.yml") == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8));
        for (String key : defaults.getKeys(true)) {
            if (!existing.contains(key)) {
                existing.set(key, defaults.get(key));
            }
        }
        existing.set("config-version", CURRENT_VERSION);
        try {
            existing.save(configFile);
        } catch (IOException ignored) {}
    }

    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
