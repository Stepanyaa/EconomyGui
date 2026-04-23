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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EconomyGUI extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private Economy econ = null;
    private FileConfiguration messagesConfig;
    private FileConfiguration transactionsConfig;
    private File transactionsFile;
    private String language;

    private static final String CURRENT_VERSION = "2.0.0";

    private EconomySearchGUI economySearchGUI;
    private final Set<String> adminUUIDs = ConcurrentHashMap.newKeySet();
    private String latestVersion = null;
    private boolean playerSelectionEnabled;
    private boolean massOperationsEnabled;
    private boolean quickActionsEnabled;
    private boolean fullManagementEnabled;
    public int transactionRetentionDays;
    public double maxAmount;

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
        updateConfigFile();
        reloadConfig();
        applyConfig();
        loadMessages();
        if (messagesConfig == null) {
            getLogger().severe("Failed to load messages configuration. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!playerSelectionEnabled && !massOperationsEnabled && !quickActionsEnabled && !fullManagementEnabled) {
            getLogger().warning(getMessage("error.all-features-disabled",
                    "All features are disabled in config! Commands will be limited."));
        }

        economySearchGUI = new EconomySearchGUI(this);
        getServer().getPluginManager().registerEvents(economySearchGUI, this);
        getServer().getPluginManager().registerEvents(economySearchGUI.getPlayerCache(), this);
        getServer().getPluginManager().registerEvents(this, this);

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
        getLogger().info(getMessage("warning.plugin-enabled",
                "EconomyGUI enabled with language: %lang%", "lang", language));

        checkForUpdates();
        isFirstEnable = false;
        new Metrics(this, 27776);
        economySearchGUI.startBalancePolling();

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveTransactions, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveTransactions();
        getLogger().info("EconomyGUI disabled.");
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

    private final Map<UUID, Double> balanceBeforePay = new ConcurrentHashMap<>();

    /**
     * Фаза 1: снимаем баланс отправителя ДО того, как Essentials выполнит /pay.
     * Запускается на HIGH — раньше, чем Essentials (который обычно на NORMAL/HIGH).
     * Если Essentials стоит на HIGH — используем HIGHEST здесь, важен лишь порядок:
     * этот хэндлер должен быть ДО исполнения команды.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPayCommandBefore(PlayerCommandPreprocessEvent event) {
        if (!isPayCommand(event.getMessage())) return;
        Player sender = event.getPlayer();
        double balanceBefore = getEconomy().getBalance(sender);
        balanceBeforePay.put(sender.getUniqueId(), balanceBefore);
    }

    /**
     * Фаза 2: после выполнения /pay сравниваем балансы.
     * Разница = реально переведённая сумма (учитывает комиссии Essentials и лимиты).
     * Работает для ЛЮБОГО получателя — онлайн или офлайн.
     */
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
        if (target == null) target = Bukkit.getPlayer(targetName);
        if (target == null) {
            @SuppressWarnings("deprecation")
            OfflinePlayer offlineExact = Bukkit.getOfflinePlayer(targetName);

            if (offlineExact.hasPlayedBefore() || offlineExact.isOnline()) {
                target = offlineExact;
            }
        }
        if (target == null || target.getUniqueId().equals(sender.getUniqueId())) {
            return;
        }

        final OfflinePlayer finalTarget = target;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            double balanceAfter = getEconomy().getBalance(sender);
            double diff = balanceBefore - balanceAfter;

            if (diff > 0.001) {
                economySearchGUI.getTransactionHandler()
                        .log(sender.getUniqueId().toString(), "pay", diff, sender);
                economySearchGUI.getTransactionHandler()
                        .log(finalTarget.getUniqueId().toString(), "receive", diff, sender);
            }
        }, 1L);
    }

    /** Проверяет, является ли сообщение командой pay-типа. */
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

    /**
     * Проверяет, не превышает ли сумма лимит из конфига.
     * @return true если сумма в пределах лимита (или лимит не задан)
     */
    public boolean isWithinMaxAmount(double amount) {
        return maxAmount <= 0 || amount <= maxAmount;
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
                msg = msg.replace("%" + placeholders[i] + "%", placeholders[i + 1].toString());
            }
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public void reloadPlugin(Player player) {
        reloadConfig();
        applyConfig();
        loadMessages();
        loadTransactions();
        updateConfigFile();
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
    }

    private void loadMessages() {
        String fileName = "messages_" + language + ".yml";
        messagesFile = new File(getDataFolder(), fileName);
        try {
            if (!messagesFile.exists()) {
                if (getResource(fileName) != null) {
                    saveResource(fileName, false);
                    getLogger().info(getMessage("warning.messages-file-create", "Created messages file: %file%",
                            "file", fileName));
                } else {
                    getLogger().warning(getMessage("warning.messages-file-not-found",
                            "Messages file %file% not found in plugin!", "file", fileName));
                    messagesConfig = new YamlConfiguration();
                    return;
                }
            }
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            String fileVersion = messagesConfig.getString("version", "0.0.0");
            if (!fileVersion.equals(CURRENT_VERSION) && getResource(fileName) != null) {
                saveResource(fileName, true);
                messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                messagesConfig.set("version", CURRENT_VERSION);
                messagesConfig.save(messagesFile);
                getLogger().info(getMessage("warning.messages-file-updated",
                        "Updated messages file %file% to version %version%",
                        "file", fileName, "version", CURRENT_VERSION));
            } else if (isFirstEnable) {
                getLogger().info(getMessage("warning.messages-file-up-to-date",
                        "Messages file %file% is up-to-date (version %version%).",
                        "file", fileName, "version", CURRENT_VERSION));
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
        economySearchGUI.getTransactionHandler().load(transactionsConfig);
    }

    public void saveTransactions() {
        economySearchGUI.getTransactionHandler().save(transactionsConfig);
        try {
            transactionsConfig.save(transactionsFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save transactions.yml: " + e.getMessage());
        }
    }

    private void updateConfigFile() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            getLogger().info(getMessage("warning.config-file-create", "Created config file: config.yml"));
            return;
        }
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
        String fileVersion = existing.getString("config-version", "0.0.0");
        if (fileVersion.equals(CURRENT_VERSION)) {
            if (isFirstEnable) {
                getLogger().info(getMessage("warning.config-file-up-to-date",
                        "Config file config.yml is up-to-date (version %version%).",
                        "version", CURRENT_VERSION));
            }
            return;
        }
        if (getResource("config.yml") == null) {
            getLogger().warning(getMessage("warning.config-file-not-found", "Resource config.yml not found in plugin!"));
            return;
        }
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8));
        boolean updated = false;
        for (String key : defaults.getKeys(true)) {
            if (!existing.contains(key)) {
                existing.set(key, defaults.get(key));
                updated = true;
            }
        }
        existing.set("config-version", CURRENT_VERSION);
        try {
            existing.save(configFile);
            getLogger().info(getMessage(updated ? "warning.config-file-updated" : "warning.config-file-up-to-date",
                    updated ? "Updated config.yml to version %version%." : "Config file config.yml is up-to-date (version %version%).",
                    "version", CURRENT_VERSION));
        } catch (IOException e) {
            getLogger().warning("Failed to save updated config.yml: " + e.getMessage());
        }
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
                    JsonArray versions = JsonParser.parseReader(
                            new InputStreamReader(conn.getInputStream())).getAsJsonArray();
                    String highest = null;
                    for (JsonElement el : versions) {
                        String vNum  = el.getAsJsonObject().get("version_number").getAsString();
                        String vType = el.getAsJsonObject().get("version_type").getAsString();
                        if (vNum.contains("-SNAPSHOT") && !vType.equals("release")) continue;
                        if (highest == null || isNewerVersion(vNum, highest)) highest = vNum;
                    }
                    if (highest != null && isNewerVersion(highest, CURRENT_VERSION)) {
                        latestVersion = highest;
                        getLogger().warning("*** UPDATE AVAILABLE *** A new version of EconomyGUI ("
                                + latestVersion + ") is available at:\nhttps://modrinth.com/plugin/economygui/versions");
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to check for updates: " + e.getMessage());
            }
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
}