/**
 * MIT License
 *
 * EconomyGui
 * Copyright (c) 2026 Stepanyaa
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
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
public class EconomyGUI extends JavaPlugin implements CommandExecutor, TabCompleter {
    private Economy econ = null;
    private FileConfiguration messagesConfig;
    private FileConfiguration transactionsConfig;
    private File transactionsFile;
    private String language;
    private static final String CURRENT_VERSION = "1.0.6";
    private EconomySearchGUI economySearchGUI;
    private final Set<String> adminUUIDs = ConcurrentHashMap.newKeySet();
    private String latestVersion = null;
    private boolean playerSelectionEnabled;
    private boolean massOperationsEnabled;
    private boolean quickActionsEnabled;
    private boolean fullManagementEnabled;
    public int transactionRetentionDays;
    private boolean isFirstEnable = true;
    private File messagesFile;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe(getMessage("warning.no-economy", "Economy provider not found! Disabling plugin."));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        this.updateConfigFile();
        reloadConfig();
        language = getConfig().getString("language", "en");
        playerSelectionEnabled = getConfig().getBoolean("features.player-selection", true);
        massOperationsEnabled = getConfig().getBoolean("features.mass-operations", true);
        quickActionsEnabled = getConfig().getBoolean("features.quick-actions", true);
        fullManagementEnabled = getConfig().getBoolean("features.full-management", true);
        transactionRetentionDays = getConfig().getInt("features.transaction-retention-days", 30);
        loadMessages();
        if (messagesConfig == null) {
            getLogger().severe("Failed to load messages configuration. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!playerSelectionEnabled && !massOperationsEnabled && !quickActionsEnabled && !fullManagementEnabled) {
            getLogger().warning(getMessage("error.all-features-disabled", "All features are disabled in config! Commands will be limited."));
        }
        economySearchGUI = new EconomySearchGUI(this);
        getServer().getPluginManager().registerEvents(economySearchGUI, this);
        loadTransactions();
        PluginCommand command = getCommand("economygui");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
            command.setPermissionMessage(ChatColor.RED + getMessage("error.no-permission", "You don't have permission!"));
        } else {
            getLogger().warning("Failed to register command 'economygui'!");
        }
        adminUUIDs.addAll(getConfig().getStringList("admin-uuids"));
        getLogger().info(getMessage("warning.plugin-enabled", "EconomyGUI enabled with language: %lang%", "lang", language));
        checkForUpdates();
        this.isFirstEnable = false;
        int pluginId = 27776;
        new Metrics(this, pluginId);
        economySearchGUI.startBalancePolling();
    }
    @Override
    public void onDisable() {
        saveTransactions();
        getLogger().info("EconomyGUI disabled.");
    }
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }
    public Economy getEconomy() {
        return econ;
    }

    private void loadMessages() {
        String messagesFileName = "messages_" + language + ".yml";
        messagesFile = new File(getDataFolder(), messagesFileName);
        try {
            if (!messagesFile.exists()) {
                if (getResource(messagesFileName) != null) {
                    saveResource(messagesFileName, false);
                    getLogger().info("Created messages file: " + messagesFileName);
                } else {
                    getLogger().warning("Messages file " + messagesFileName + " not found in plugin!");
                    messagesConfig = new YamlConfiguration();
                    return;
                }
            }
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            String currentFileVersion = messagesConfig.getString("version", "0.0.0");
            if (!currentFileVersion.equals(CURRENT_VERSION)) {
                if (getResource(messagesFileName) != null) {
                    saveResource(messagesFileName, true);
                    messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                    messagesConfig.set("version", CURRENT_VERSION);
                    messagesConfig.save(messagesFile);
                    getLogger().info("Updated messages file " + messagesFileName + " to version " + CURRENT_VERSION);
                } else {
                    getLogger().warning("Resource " + messagesFileName + " not found in plugin!");
                }
            } else if (isFirstEnable) {
                getLogger().info("Messages file " + messagesFileName + " is up-to-date (version " + CURRENT_VERSION + ").");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load messages file: " + e.getMessage());
            messagesConfig = new YamlConfiguration();
        }
    }
    private void loadTransactions() {
        transactionsFile = new File(getDataFolder(), "transactions.yml");
        if (!transactionsFile.exists()) {
            try {
                transactionsFile.createNewFile();
                getLogger().info("Created transactions file: transactions.yml");
            } catch (IOException e) {
                getLogger().severe("Failed to create transactions.yml: " + e.getMessage());
            }
        }
        transactionsConfig = YamlConfiguration.loadConfiguration(transactionsFile);
        economySearchGUI.loadTransactionHistory(transactionsConfig);
    }
    public void saveTransactions() {
        economySearchGUI.cleanOldTransactions();
        economySearchGUI.saveTransactionHistory(transactionsConfig);
        try {
            transactionsConfig.save(transactionsFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save transactions.yml: " + e.getMessage());
        }
    }
    public String getMessage(String key) {
        if (messagesConfig == null) {
            return ChatColor.translateAlternateColorCodes('&', key);
        }
        String msg = messagesConfig.getString(key, key);
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
    public String getMessage(String key, String def) {
        if (messagesConfig == null) {
            return ChatColor.translateAlternateColorCodes('&', def);
        }
        String msg = messagesConfig.getString(key, def);
        return ChatColor.translateAlternateColorCodes('&', msg);
    }
    public String getMessage(String key, String def, Object... placeholders) {
        String msg = getMessage(key, def);
        if (placeholders != null && placeholders.length >= 2 && placeholders.length % 2 == 0) {
            for (int i = 0; i < placeholders.length; i += 2) {
                String placeholder = placeholders[i].toString();
                String value = placeholders[i + 1].toString();
                msg = msg.replace("%" + placeholder + "%", value);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
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
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (args.length > 1) {
                player.sendMessage(ChatColor.RED + getMessage("command.usage-gui", "Usage: /economygui gui"));
                return true;
            }
            economySearchGUI.openLastGUIMenu(player);
            return true;
        } else if (args[0].equalsIgnoreCase("reload")) {
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
        } else if (args[0].equalsIgnoreCase("reset")) {
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
        }
        player.sendMessage(ChatColor.RED + getMessage("command.usage", "Usage: /economygui <gui | reload | reset>"));
        return true;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPayCommand(PlayerCommandPreprocessEvent event) {
        String[] args = event.getMessage().split(" ");
        if (args.length < 3) return;

        String cmd = args[0].toLowerCase();
        if (cmd.equals("/pay") || cmd.equals("/epay") || cmd.equals("/money") && args[1].equalsIgnoreCase("pay")) {

            Player sender = event.getPlayer();
            Player target = Bukkit.getPlayer(args[1]);

            if (target == null) return;

            try {
                double amount = Double.parseDouble(args[args.length - 1]);
                if (amount <= 0) return;
                String toSender = getMessage("history.sent","&cSent &e$") + amount + (ChatColor.RED + getMessage("history.to-player"," &7to the player &f") + target.getName());
                addTransaction(sender.getUniqueId(), toSender);
                String toReceiver = getMessage("history.received","&aReceived &e$" + amount + getMessage("history.from-payer"," &7from the player &f") + sender.getName());
                addTransaction(target.getUniqueId(), toReceiver);

            } catch (NumberFormatException ignored) {}
        }
    }
    public void addTransaction(UUID uuid, String message) {
        String time = new java.text.SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date());
        String fullEntry = "§8[" + time + "] " + message;

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
    public void reloadPlugin(Player player) {
        reloadConfig();
        language = getConfig().getString("language", "en");
        loadMessages();
        loadTransactions();
        updateConfigFile();
        transactionRetentionDays = getConfig().getInt("features.transaction-retention-days", 30);
        playerSelectionEnabled = getConfig().getBoolean("features.player-selection", true);
        massOperationsEnabled = getConfig().getBoolean("features.mass-operations", true);
        quickActionsEnabled = getConfig().getBoolean("features.quick-actions", true);
        fullManagementEnabled = getConfig().getBoolean("features.full-management", true);
        economySearchGUI.refreshOpenGUIs();
        player.sendMessage(ChatColor.GREEN + getMessage("action.config-reloaded", "Configuration reloaded."));
    }
    private void checkForUpdates() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL("https://api.modrinth.com/v2/project/economygui/version");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "EconomyGUI/" + CURRENT_VERSION);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    JsonArray versions = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonArray();
                    String highestVersion = null;
                    for (JsonElement element : versions) {
                        String versionNumber = element.getAsJsonObject().get("version_number").getAsString();
                        String versionType = element.getAsJsonObject().get("version_type").getAsString();
                        if (versionNumber.contains("-SNAPSHOT") && !versionType.equals("release")) {
                            continue;
                        }
                        if (highestVersion == null || isNewerVersion(versionNumber, highestVersion)) {
                            highestVersion = versionNumber;
                        }
                    }
                    if (highestVersion != null && isNewerVersion(highestVersion, CURRENT_VERSION)) {
                        String[] currentParts = CURRENT_VERSION.split("\\.");
                        String[] highestParts = highestVersion.split("\\.");
                        if (currentParts.length == 3 && highestParts.length == 3) {
                            int currentMajor = Integer.parseInt(currentParts[0]);
                            int currentMinor = Integer.parseInt(currentParts[1]);
                            int currentPatch = Integer.parseInt(currentParts[2]);
                            int highestMajor = Integer.parseInt(highestParts[0]);
                            int highestMinor = Integer.parseInt(highestParts[1]);
                            int highestPatch = Integer.parseInt(highestParts[2]);
                            if (currentMajor == highestMajor && currentMinor == highestMinor && highestPatch == currentPatch + 1) {
                                latestVersion = highestVersion;
                                getLogger().warning("*** UPDATE AVAILABLE *** A new version of EconomyGUI (" + latestVersion + ") is available at:\nhttps://modrinth.com/plugin/economygui/versions");
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to check for updates: " + e.getMessage());
            }
        });
    }
    private boolean isNewerVersion(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
            int num1 = Integer.parseInt(parts1[i]);
            int num2 = Integer.parseInt(parts2[i]);
            if (num1 > num2) return true;
            if (num1 < num2) return false;
        }
        return parts1.length > parts2.length;
    }
    private void updateConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            getLogger().info(getMessage("warning.config-file-create", "Created config file: config.yml"));
            return;
        }
        YamlConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
        String currentFileVersion = existingConfig.getString("config-version", "0.0.0");
        if (currentFileVersion.equals(CURRENT_VERSION)) {
            if (isFirstEnable) {
                getLogger().info(getMessage("warning.config-file-up-to-date", "Config file config.yml is up-to-date (version %version%).")
                        .replace("%version%", CURRENT_VERSION));
            }
            return;
        }
        if (getResource("config.yml") == null) {
            getLogger().warning(getMessage("warning.config-file-not-found", "Resource config.yml not found in plugin!"));
            return;
        }
        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)
        );

        boolean updated = false;
        for (String key : defaultConfig.getKeys(true)) {
            if (!existingConfig.contains(key)) {
                existingConfig.set(key, defaultConfig.get(key));
                updated = true;
            }
        }
        existingConfig.set("config-version", CURRENT_VERSION);
        try {
            existingConfig.save(configFile);
            if (updated) {
                getLogger().info(getMessage("warning.config-file-updated", "Updated config.yml to version %version%. Added missing keys.")
                        .replace("%version%", CURRENT_VERSION));
            } else {
                getLogger().info(getMessage("warning.config-file-version-updated", "Config.yml version updated to %version% (no new keys added).")
                        .replace("%version%", CURRENT_VERSION));
            }
        } catch (IOException e) {
            getLogger().warning("Failed to save updated config.yml: " + e.getMessage());
        }
    }
    public boolean isPlayerSelectionEnabled() {
        return playerSelectionEnabled;
    }
    public boolean isMassOperationsEnabled() {
        return massOperationsEnabled;
    }
    public boolean isQuickActionsEnabled() {
        return quickActionsEnabled;
    }
    public boolean isFullManagementEnabled() {
        return fullManagementEnabled;
    }
    public Set<String> getAdminUUIDs() {
        return adminUUIDs;
    }
}