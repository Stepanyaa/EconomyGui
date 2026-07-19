package ru.stepanyaa.economyGUI;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.faststats.ErrorTracker;
import dev.faststats.data.Metric;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
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
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ru.stepanyaa.economyGUI.database.DatabaseManager;
import dev.faststats.bukkit.BukkitContext;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class EconomyGUI extends JavaPlugin implements Listener {
    public static final ErrorTracker ERROR_TRACKER = ErrorTracker.contextAware();
    private final AtomicInteger gameCount = new AtomicInteger();

    private final BukkitContext context = new BukkitContext.Factory(this, "8a0c8b55ce2b568bd56821f8b4db9418")
            .errorTrackerService(ERROR_TRACKER)
            .metrics(factory -> factory
                    .addMetric(Metric.number("game_count", gameCount::get))
                    .addMetric(Metric.string("server_version", () -> "1.0.0"))

                    .onFlush(() -> gameCount.set(0))

                    .create())
            .create();

    private Economy econ = null;
    private String language;
    private static final String CURRENT_VERSION = "2.1.0";
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
    private boolean isFirstEnable = true;
    private final Map<UUID, Double> balanceBeforePay = new ConcurrentHashMap<>();


    @Override
    public void onEnable() {
        saveDefaultConfig();
        languageManager = new LanguageManager(this);
        languageManager.loadLanguages();
        applyConfig();
        languageManager.setLanguage(getConfig().getString("language", "en"));

        if (!setupEconomy()) {
            getLogger().severe(getMessage("warning.no-economy", "Economy provider not found! Disabling plugin."));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        databaseManager = new DatabaseManager(this);
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
            databaseManager.migrateFromYaml(oldConfig);
            oldTransactions.renameTo(new File(getDataFolder(), "transactions_old_backup.yml"));
        }

        transactionHandler = new TransactionHandler(this, databaseManager);
        economySearchGUI = new EconomySearchGUI(this);
        getServer().getPluginManager().registerEvents(economySearchGUI, this);
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("economygui");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        context.ready();
        new Metrics(this, 27776);
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        context.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + getMessage("error.player-only", "This command is for players only!"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("economygui.admin") && !player.hasPermission("economygui.gui")) {
            player.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "gui";
        switch (sub) {
            case "gui":
                if (args.length > 1) {
                    player.sendMessage(ChatColor.RED + getMessage("command.usage-gui", "Usage: /economygui gui"));
                    return true;
                }
                economySearchGUI.openLastGUIMenu(player);
                return true;
            case "reload":
                if (!player.hasPermission("economygui.reload")) {
                    player.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
                    return true;
                }
                if (args.length != 1) {
                    player.sendMessage(ChatColor.RED + getMessage("command.usage-reload", "Usage: /economygui reload"));
                    return true;
                }
                reloadPlugin(player);
                return true;
            case "reset":
                if (!player.hasPermission("economygui.reset")) {
                    player.sendMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
                    return true;
                }
                if (args.length != 1) {
                    player.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /economygui reset"));
                    return true;
                }
                economySearchGUI.resetSearch(player);
                return true;
            default:
                player.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /economygui <gui | reload | reset>"));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("gui", "reload", "reset").stream()
                    .filter(cmd -> sender.hasPermission("economygui." + cmd) || sender.hasPermission("economygui.admin"))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPayCommandBefore(PlayerCommandPreprocessEvent event) {
        if (!isPayCommand(event.getMessage())) return;
        Player sender = event.getPlayer();
        double balanceBefore = getEconomy().getBalance(sender);
        balanceBeforePay.put(sender.getUniqueId(), balanceBefore);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPayCommandAfter(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (!isPayCommand(raw)) return;
        String[] args = raw.split("\\s+");
        String cmd = args[0].toLowerCase();
        int playerArgIndex = cmd.equals("/money") ? 2 : 1;
        if (args.length <= playerArgIndex) return;

        Player sender = event.getPlayer();
        Double balanceBefore = balanceBeforePay.remove(sender.getUniqueId());
        if (balanceBefore == null) return;

        String targetName = args[playerArgIndex];
        OfflinePlayer target = Bukkit.getPlayerExact(targetName);
        if (target == null) target = Bukkit.getOfflinePlayer(targetName);

        if (target == null || target.getUniqueId().equals(sender.getUniqueId())) return;

        final OfflinePlayer finalTarget = target;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            double balanceAfter = getEconomy().getBalance(sender);
            double diff = balanceBefore - balanceAfter;
            if (diff > 0.001) {
                transactionHandler.log(sender.getUniqueId().toString(), "pay", diff, sender);
                transactionHandler.log(finalTarget.getUniqueId().toString(), "receive", diff, sender);
            }
        }, 1L);
    }

    private boolean isPayCommand(String message) {
        String[] args = message.split("\\s+");
        if (args.length < 3) return false;
        String cmd = args[0].toLowerCase();
        return cmd.equals("/pay") || cmd.equals("/epay")
                || (cmd.equals("/money") && args.length >= 4 && args[1].equalsIgnoreCase("pay"));
    }

    public Economy getEconomy() {
        return econ;
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
        String[] replacements = new String[placeholders.length];
        for (int i = 0; i < placeholders.length; i++) {
            replacements[i] = placeholders[i].toString();
        }
        return ChatColor.translateAlternateColorCodes('&', languageManager.getMessage(key, def, replacements));
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
        player.sendMessage(ChatColor.GREEN + getMessage("action.config-reloaded", "Configuration reloaded."));
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
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

    private void checkForUpdates() {
        if (!checkForUpdatesEnabled) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL("https://api.modrinth.com/v2/project/economygui/version");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "EconomyGUI/" + CURRENT_VERSION);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    JsonArray versions = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonArray();
                    String highest = null;
                    for (JsonElement el : versions) {
                        String vNum  = el.getAsJsonObject().get("version_number").getAsString();
                        String vType = el.getAsJsonObject().get("version_type").getAsString();
                        if (vNum.contains("-SNAPSHOT") && !vType.equals("release")) continue;
                        if (highest == null || isNewerVersion(vNum, highest)) highest = vNum;
                    }
                    if (highest != null && isNewerVersion(highest, CURRENT_VERSION)) {
                        latestVersion = highest;
                        getLogger().warning("*** UPDATE AVAILABLE *** A new version of EconomyGUI (" + latestVersion + ") is available at:\nhttps://modrinth.com/plugin/economygui/versions");
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        });
    }

    private boolean isNewerVersion(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        for (int i = 0; i < Math.min(p1.length, p2.length); i++) {
            try {
                int n1 = Integer.parseInt(p1[i]);
                int n2 = Integer.parseInt(p2[i]);
                if (n1 > n2) return true;
                if (n1 < n2) return false;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return p1.length > p2.length;
    }

    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
    }
}