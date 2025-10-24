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
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
public class EconomyGUI extends JavaPlugin implements CommandExecutor, TabCompleter {
    private Economy econ = null;
    private FileConfiguration messagesConfig;
    private FileConfiguration transactionsConfig;
    private File transactionsFile;
    private String language;
    private static final String CURRENT_VERSION = "1.0.1";
    private EconomySearchGUI economySearchGUI;
    private final Set<String> adminUUIDs = ConcurrentHashMap.newKeySet();
    public int transactionRetentionDays;
    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe(getMessage("warning.no-economy", "Economy provider not found! Disabling plugin."));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        reloadConfig();
        language = getConfig().getString("language", "en");
        transactionRetentionDays = getConfig().getInt("features.transaction-retention-days", 30);
        playerSelectionEnabled = getConfig().getBoolean("features.player-selection", true);
        massOperationsEnabled = getConfig().getBoolean("features.mass-operations", true);
        quickActionsEnabled = getConfig().getBoolean("features.quick-actions", true);
        fullManagementEnabled = getConfig().getBoolean("features.full-management", true);
        economySearchGUI = new EconomySearchGUI(this);
        getServer().getPluginManager().registerEvents(economySearchGUI, this);
        loadMessages();
        if (messagesConfig == null) {
            getLogger().severe("Failed to load messages configuration. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadTransactions();
        if (!playerSelectionEnabled && !massOperationsEnabled && !quickActionsEnabled && !fullManagementEnabled) {
            getLogger().warning(getMessage("error.all-features-disabled", "All features are disabled in config! Commands will be limited."));
        }
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
    }
    @Override
    public void onDisable() {
        if (economySearchGUI != null) {
            saveTransactions();
        }
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
        String lang = language != null && (language.equals("en") || language.equals("ru")) ? language : "en";
        File messagesFile = new File(getDataFolder(), "messages_" + lang + ".yml");
        try {
            if (!messagesFile.exists()) {
                if (getResource("messages_" + lang + ".yml") == null) {
                    getLogger().severe("Resource messages_" + lang + ".yml not found in JAR!");
                    messagesConfig = new YamlConfiguration();
                    return;
                }
                saveResource("messages_" + lang + ".yml", false);
                getLogger().info("Created messages file: messages_" + lang + ".yml");
            }
            messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
            if (messagesConfig.getKeys(false).isEmpty()) {
                getLogger().warning("Messages file messages_" + lang + ".yml is empty!");
            } else {
                getLogger().info("Loaded messages file: messages_" + lang + ".yml with " + messagesConfig.getKeys(true).size() + " keys");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load or create messages_" + lang + ".yml: " + e.getMessage());
            messagesConfig = new YamlConfiguration();
        }
    }
    private void loadTransactions() {
        transactionsFile = new File(getDataFolder(), "transactions/transactions.yml");
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
        playerSelectionEnabled = getConfig().getBoolean("features.player-selection", true);
        massOperationsEnabled = getConfig().getBoolean("features.mass-operations", true);
        quickActionsEnabled = getConfig().getBoolean("features.quick-actions", true);
        fullManagementEnabled = getConfig().getBoolean("features.full-management", true);
        economySearchGUI.refreshOpenGUIs();
        player.sendMessage(ChatColor.GREEN + getMessage("action.config-reloaded", "Configuration reloaded."));
    }
    private boolean playerSelectionEnabled;
    private boolean massOperationsEnabled;
    private boolean quickActionsEnabled;
    private boolean fullManagementEnabled;
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