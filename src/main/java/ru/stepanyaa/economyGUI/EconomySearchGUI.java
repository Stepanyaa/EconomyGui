/**
 * MIT License
 *
 * EconomyGui
 * Copyright (c) 2025 Stepanyaa

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ru.stepanyaa.economyGUI;

import me.clip.placeholderapi.PlaceholderAPI;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.enchantments.Enchantment;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Date;

public class EconomySearchGUI implements Listener, InventoryHolder {
    private final Map<String, List<Transaction>> transactionHistory = new ConcurrentHashMap<>();
    private static final long PAGE_SWITCH_COOLDOWN = 1000L;
    private final EconomyGUI plugin;
    private Inventory inventory;
    private int currentPage = 0;
    private String currentSearch = "";
    private Filter currentFilter = Filter.ALL;
    private final Set<UUID> selectedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> playerSelections = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastOpenedMenu = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastPage = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastTarget = new ConcurrentHashMap<>();
    private final Set<UUID> playersInGUI = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ChatAction> pendingActions = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingActionType = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pendingActionAmount = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerResult> pendingActionTarget = new ConcurrentHashMap<>();
    private List<PlayerResult> cachedResults = new ArrayList<>();
    private String moneyFormat;
    private boolean usePlaceholderAPI;

    private final Map<UUID, Long> lastPageSwitch = new ConcurrentHashMap<>();
    private static class Transaction {
        long timestamp;
        String action;
        double amount;
        String executor;

        Transaction(long timestamp, String action, double amount, String executor) {
            this.timestamp = timestamp;
            this.action = action;
            this.amount = amount;
            this.executor = executor;
        }
    }

    @FunctionalInterface
    interface ChatAction {
        void execute(String message, Player player);
    }

    public enum Filter {
        ALL, ONLINE, OFFLINE, SELECTED
    }

    public EconomySearchGUI(EconomyGUI plugin) {
        this.plugin = plugin;
        this.usePlaceholderAPI = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.moneyFormat = "%,.2f";
        this.inventory = Bukkit.createInventory(this, 54,ChatColor.DARK_PURPLE + plugin.getMessage("gui.title", "Economy Management"));
        refreshGUI();
    }

    private void refreshGUI() {
        setupMainMenuLayout();
    }

    private void setupMainMenuLayout() {
        inventory.clear();
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }

        ItemStack searchItem = new ItemStack(Material.COMPASS);
        ItemMeta searchMeta = searchItem.getItemMeta();
        searchMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.search", "Search: %search_query%",
                "search_query", currentSearch.isEmpty() ? plugin.getMessage("gui.search-all", "all") : currentSearch));
        List<String> searchLore = new ArrayList<>();
        searchLore.add(ChatColor.GRAY + plugin.getMessage("gui.search-hint", "Left click: Enter query | Right click: Reset search | Shift+Left: Select/Deselect"));
        searchMeta.setLore(searchLore);
        searchItem.setItemMeta(searchMeta);
        inventory.setItem(4, searchItem);

        inventory.setItem(49, createCurrentFilterItem());
        inventory.setItem(50, createGlobalStatsButton());


        if (plugin.isMassOperationsEnabled()) {
            ItemStack massItem = new ItemStack(Material.DIAMOND_BLOCK);
            ItemMeta massMeta = massItem.getItemMeta();
            massMeta.setDisplayName(ChatColor.AQUA + plugin.getMessage("gui.mass-menu-title", "Mass Operations"));
            List<String> massLore = new ArrayList<>();
            massLore.add(ChatColor.GRAY + plugin.getMessage("gui.mass-hint", "Click for mass give/take/set (select players first)"));
            massMeta.setLore(massLore);
            massItem.setItemMeta(massMeta);
            inventory.setItem(48, massItem);
        }

        int totalPages = (cachedResults.size() / 36) + (cachedResults.size() % 36 > 0 ? 1 : 0);
        int currentPageNum = currentPage + 1;

        ItemStack prev = currentPage > 0 ? new ItemStack(Material.ARROW) : new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta prevMeta = prev.getItemMeta();
        prevMeta.setDisplayName(currentPage > 0 ? ChatColor.YELLOW + plugin.getMessage("gui.previous-page", "Previous Page")
                : ChatColor.RED + plugin.getMessage("gui.no-page", "No Page"));
        List<String> prevLore = new ArrayList<>();
        prevLore.add(ChatColor.GRAY + plugin.getMessage("gui.page-info", "Page %current_page% of %total_pages%",
                "current_page", String.valueOf(currentPageNum), "total_pages", String.valueOf(totalPages)));
        if (currentPage > 0) {
            prevLore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
        }
        prevMeta.setLore(prevLore);
        prev.setItemMeta(prevMeta);
        inventory.setItem(45, prev);

        ItemStack next = currentPage < totalPages - 1 ? new ItemStack(Material.ARROW) : new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta nextMeta = next.getItemMeta();
        nextMeta.setDisplayName(currentPage < totalPages - 1 ? ChatColor.YELLOW + plugin.getMessage("gui.next-page", "Next Page")
                : ChatColor.RED + plugin.getMessage("gui.no-page", "No Page"));
        List<String> nextLore = new ArrayList<>();
        nextLore.add(ChatColor.GRAY + plugin.getMessage("gui.page-info", "Page %current_page% of %total_pages%",
                "current_page", String.valueOf(currentPageNum), "total_pages", String.valueOf(totalPages)));
        if (currentPage < totalPages - 1) {
            nextLore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
        }
        nextMeta.setLore(nextLore);
        next.setItemMeta(nextMeta);
        inventory.setItem(53, next);

        if (plugin.isPlayerSelectionEnabled()) {
            ItemStack selectBtn = new ItemStack(Material.EMERALD_BLOCK);
            ItemMeta selectMeta = selectBtn.getItemMeta();
            selectMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.select-all", "Select All"));
            List<String> selectLore = new ArrayList<>();
            selectLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-select", "Click to select all on page"));
            selectMeta.setLore(selectLore);
            selectBtn.setItemMeta(selectMeta);
            inventory.setItem(46, selectBtn);

            ItemStack cancelBtn = new ItemStack(Material.REDSTONE_BLOCK);
            ItemMeta cancelMeta = cancelBtn.getItemMeta();
            cancelMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.cancel-selection", "Cancel Selection"));
            List<String> cancelLore = new ArrayList<>();
            cancelLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-cancel", "Click to cancel all selections"));
            cancelMeta.setLore(cancelLore);
            cancelBtn.setItemMeta(cancelMeta);
            inventory.setItem(47, cancelBtn);
        }

        List<PlayerResult> results = getFilteredPlayers();
        cachedResults = results;
        int start = currentPage * 36;
        int end = Math.min(start + 36, results.size());
        for (int i = start; i < end; i++) {
            PlayerResult result = results.get(i);
            OfflinePlayer offPlayer = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
            ItemStack head = createPlayerHead(offPlayer, i);
            int slot = 9 + (i - start);
            inventory.setItem(slot, head);
        }
        if (results.isEmpty()) {
            ItemStack noPlayers = new ItemStack(Material.BARRIER);
            ItemMeta meta = noPlayers.getItemMeta();
            meta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-players", "No players found"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.no-players-hint", "Try changing the search or filter"));
            meta.setLore(lore);
            noPlayers.setItemMeta(meta);
            inventory.setItem(22, noPlayers);
        }
    }
    private ItemStack createGlobalStatsButton() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + plugin.getMessage("gui.global-stats", "Global Economy Stats"));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-view", "Click to view server economy overview"));
        lore.add("");
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.global-stats-lore","(Total balance, average, top players...)"));
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }
    private ItemStack createCurrentFilterItem() {
        Material mat;
        String nameKey;
        ChatColor displayColor;

        switch (currentFilter) {
            case ALL:
                mat = Material.COMPASS;
                nameKey = "filter.all";
                displayColor = ChatColor.WHITE;
                break;
            case ONLINE:
                mat = Material.LIME_DYE;
                nameKey = "filter.online";
                displayColor = ChatColor.GREEN;
                break;
            case OFFLINE:
                mat = Material.GRAY_DYE;
                nameKey = "filter.offline";
                displayColor = ChatColor.GRAY;
                break;
            default:
                mat = Material.PAPER;
                nameKey = "filter.all";
                displayColor = ChatColor.GRAY;
        }

        String displayName = displayColor + plugin.getMessage(nameKey, nameKey);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);

        List<String> lore = new ArrayList<>();

        lore.add(ChatColor.GRAY + plugin.getMessage("filter.filter-current","Current filter:"));
        lore.add("");
        lore.add(createFilterLine(Filter.ALL, currentFilter));
        lore.add(createFilterLine(Filter.ONLINE, currentFilter));
        lore.add(createFilterLine(Filter.OFFLINE, currentFilter));

        lore.add("");
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-cycle", "LMB → Next"));
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.right-click-cycle", "Right-click → Previous"));

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    private String createFilterLine(Filter filter, Filter current) {
        String name;
        ChatColor color = ChatColor.GRAY;

        switch (filter) {
            case ALL:
                name = plugin.getMessage("filter.all", "Все игроки");
                color = ChatColor.WHITE;
                break;
            case ONLINE:
                name = plugin.getMessage("filter.online", "Онлайн");
                color = ChatColor.GREEN;
                break;
            case OFFLINE:
                name = plugin.getMessage("filter.offline", "Офлайн");
                color = ChatColor.GRAY;
                break;
            default:
                name = "???";
        }

        if (filter == current) {
            return color + "» " + ChatColor.BOLD + name + " «";
        } else {
            return "  " + color + name;
        }
    }
    private ItemStack createPlayerHead(OfflinePlayer player, int index) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        meta.setDisplayName(ChatColor.YELLOW + player.getName());
        List<String> lore = new ArrayList<>();
        double balance = plugin.getEconomy().getBalance(player);
        String formattedBalance = String.format(moneyFormat, balance);
        lore.add(ChatColor.GOLD + plugin.getMessage("gui.balance", "Balance: $%balance%", "balance", formattedBalance));
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions", "Left click: Manage | Right click: Quick Actions | Shift+Left: Select"));
        if (isSelected(player.getUniqueId().toString())) {
            lore.add(ChatColor.GREEN + plugin.getMessage("gui.selected", "Selected"));
            meta.addEnchant(getGlowEnchantment(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private Enchantment getGlowEnchantment() {
        String version = Bukkit.getBukkitVersion();
        return version.contains("1.16") ? Enchantment.DURABILITY : Enchantment.VANISHING_CURSE;
    }

    private boolean isSelected(String uuid) {
        return selectedPlayers.contains(UUID.fromString(uuid));
    }

    private void applyFilter(Filter newFilter, Player player) {
        currentFilter = newFilter;
        currentPage = 0;
        lastPage.put(player.getUniqueId(), 0);
        refreshGUI();
        player.sendMessage(ChatColor.GREEN + plugin.getMessage("filter.applied","Filter applied: " + newFilter.name()));
    }

    private void openPlayerFinanceManagement(Player player, PlayerResult result) {
        Inventory financeInv = Bukkit.createInventory(this, 54, ChatColor.DARK_PURPLE + plugin.getMessage("gui.title", "Economy Management") + ": " + result.name);
        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        financeInv.setItem(4, createPlayerHead(target, 0));

        ItemStack inputField = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta inputMeta = inputField.getItemMeta();
        inputMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.enter-amount", "Enter Amount"));
        List<String> inputLore = new ArrayList<>();
        inputLore.add(ChatColor.GRAY + plugin.getMessage("gui.enter-amount-hint", "Left click to enter amount in chat"));
        inputMeta.setLore(inputLore);
        inputField.setItemMeta(inputMeta);
        financeInv.setItem(13, inputField);

        ItemStack giveBtn = new ItemStack(Material.EMERALD);
        ItemMeta giveMeta = giveBtn.getItemMeta();
        giveMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("action.give", "Give Amount"));
        List<String> giveLore = new ArrayList<>();
        giveLore.add(ChatColor.GRAY + plugin.getMessage("gui.give-hint", "Click to open digital menu"));
        giveMeta.setLore(giveLore);
        giveBtn.setItemMeta(giveMeta);
        financeInv.setItem(20, giveBtn);

        ItemStack takeBtn = new ItemStack(Material.REDSTONE);
        ItemMeta takeMeta = takeBtn.getItemMeta();
        takeMeta.setDisplayName(ChatColor.RED + plugin.getMessage("action.take", "Take Amount"));
        List<String> takeLore = new ArrayList<>();
        takeLore.add(ChatColor.GRAY + plugin.getMessage("gui.take-hint", "Click to open digital menu"));
        takeMeta.setLore(takeLore);
        takeBtn.setItemMeta(takeMeta);
        financeInv.setItem(22, takeBtn);

        ItemStack setBtn = new ItemStack(Material.GOLD_INGOT);
        ItemMeta setMeta = setBtn.getItemMeta();
        setMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("action.set", "Set Amount"));
        List<String> setLore = new ArrayList<>();
        setLore.add(ChatColor.GRAY + plugin.getMessage("gui.set-hint", "Click to enter amount in chat"));
        setMeta.setLore(setLore);
        setBtn.setItemMeta(setMeta);
        financeInv.setItem(24, setBtn);

        ItemStack historyBtn = new ItemStack(Material.BOOK);
        ItemMeta historyMeta = historyBtn.getItemMeta();
        historyMeta.setDisplayName(ChatColor.BLUE + plugin.getMessage("history.operations", "Operations History"));
        List<String> historyLore = new ArrayList<>();
        historyLore.add(ChatColor.GRAY + plugin.getMessage("history.hint", "Click to view logs"));
        historyMeta.setLore(historyLore);
        historyBtn.setItemMeta(historyMeta);
        financeInv.setItem(31, historyBtn);

        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        List<String> backLore = new ArrayList<>();
        backLore.add(ChatColor.GRAY + plugin.getMessage("gui.back-hint", "Return to main menu"));
        backMeta.setLore(backLore);
        backBtn.setItemMeta(backMeta);
        financeInv.setItem(49, backBtn);

        player.openInventory(financeInv);
        lastOpenedMenu.put(player.getUniqueId(), "finance");
        lastTarget.put(player.getUniqueId(), result.name);
    }

    private void openContextMenu(Player player, String targetName) {
        PlayerResult result = getPlayerResultByName(targetName);
        if (result == null) return;

        Inventory contextInv = Bukkit.createInventory(this, 27, ChatColor.BLUE + plugin.getMessage("context-menu.title", "Quick Actions for %player%", "player", targetName));
        lastOpenedMenu.put(player.getUniqueId(), "context");
        lastTarget.put(player.getUniqueId(), targetName);

        contextInv.setItem(10, createQuickActionItem("Give $100", "give", 100, result));
        contextInv.setItem(11, createQuickActionItem("Give $1000", "give", 1000, result));
        contextInv.setItem(12, createQuickActionItem("Take $100", "take", 100, result));
        contextInv.setItem(13, createQuickActionItem("Take $1000", "take", 1000, result));
        contextInv.setItem(14, createQuickActionItem("Set $5000", "set", 5000, result));
        contextInv.setItem(15, createQuickActionItem("Reset to $0", "set", 0, result));
        contextInv.setItem(16, createQuickActionItem("Custom Amount", "custom", 0, result));

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("context-menu.back", "Back to Main Menu"));
        back.setItemMeta(backMeta);
        contextInv.setItem(22, back);

        if (plugin.isFullManagementEnabled()) {
            ItemStack full = new ItemStack(Material.BOOK);
            ItemMeta fullMeta = full.getItemMeta();
            fullMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("context-menu.full-open", "Open Full Management"));
            full.setItemMeta(fullMeta);
            contextInv.setItem(4, full);
        }

        player.openInventory(contextInv);
    }

    private ItemStack createQuickActionItem(String name, String action, double amount, PlayerResult result) {
        ItemStack item = new ItemStack(action.equals("give") ? Material.EMERALD : action.equals("take") ? Material.REDSTONE : Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + name);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Click to apply to " + result.name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openDigitalMenu(Player player, String action, PlayerResult result) {
        Inventory digitalInv = Bukkit.createInventory(this, 27, ChatColor.DARK_PURPLE + plugin.getMessage("gui.title", "Economy Management") + ": " + result.name);
        double totalAmount = pendingActionAmount.getOrDefault(player.getUniqueId(), 0.0);
        digitalInv.setItem(4, createPlayerHead(Bukkit.getOfflinePlayer(UUID.fromString(result.uuid)), 0));

        int[] amounts = {1, 5, 10, 100, 1000, 5000};
        int slot = 9;
        for (int amount : amounts) {
            ItemStack numBtn = new ItemStack(Material.PAPER);
            ItemMeta numMeta = numBtn.getItemMeta();
            numMeta.setDisplayName(ChatColor.YELLOW + String.valueOf(amount));
            List<String> numLore = new ArrayList<>();
            numLore.add(ChatColor.GRAY + plugin.getMessage("gui.add-amount-hint", "Click to add %amount% to total", "amount", String.valueOf(amount)));
            numLore.add(ChatColor.GRAY + plugin.getMessage("gui.current-amount", "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
            numMeta.setLore(numLore);
            numBtn.setItemMeta(numMeta);
            digitalInv.setItem(slot, numBtn);
            slot++;
        }

        ItemStack customBtn = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta customMeta = customBtn.getItemMeta();
        customMeta.setDisplayName(ChatColor.BLUE + plugin.getMessage("gui.custom-amount", "Custom Amount"));
        List<String> customLore = new ArrayList<>();
        customLore.add(ChatColor.GRAY + plugin.getMessage("gui.custom-amount-hint", "Left click to enter custom amount in chat"));
        customLore.add(ChatColor.GRAY + plugin.getMessage("gui.current-amount", "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
        customMeta.setLore(customLore);
        customBtn.setItemMeta(customMeta);
        digitalInv.setItem(22, customBtn);

        ItemStack resetBtn = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta resetMeta = resetBtn.getItemMeta();
        resetMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.reset-amount", "Reset Amount"));
        List<String> resetLore = new ArrayList<>();
        resetLore.add(ChatColor.GRAY + plugin.getMessage("gui.reset-amount-hint", "Reset the selected amount to 0"));
        resetLore.add(ChatColor.GRAY + plugin.getMessage("gui.current-amount", "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
        resetMeta.setLore(resetLore);
        resetBtn.setItemMeta(resetMeta);
        digitalInv.setItem(18, resetBtn);

        ItemStack confirmBtn = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta confirmMeta = confirmBtn.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.confirm", "Confirm"));
        List<String> confirmLore = new ArrayList<>();
        confirmLore.add(ChatColor.GRAY + plugin.getMessage("gui.confirm-hint", "Execute: %action% $%amount%", "action", action, "amount", String.format(moneyFormat, totalAmount)));
        confirmMeta.setLore(confirmLore);
        confirmBtn.setItemMeta(confirmMeta);
        digitalInv.setItem(16, confirmBtn);

        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        List<String> backLore = new ArrayList<>();
        backLore.add(ChatColor.GRAY + plugin.getMessage("gui.back-hint-finance", "Return to finance menu"));
        backMeta.setLore(backLore);
        backBtn.setItemMeta(backMeta);
        digitalInv.setItem(26, backBtn);

        player.openInventory(digitalInv);
        pendingActionType.put(player.getUniqueId(), action);
        pendingActionTarget.put(player.getUniqueId(), result);
        lastOpenedMenu.put(player.getUniqueId(), "digital");
    }

    private void openHistoryMenu(Player player, PlayerResult result) {
        String uuid = result.uuid;
        List<Transaction> history = transactionHistory.getOrDefault(uuid, new ArrayList<>());
        history.sort(Comparator.comparingLong(t -> -t.timestamp));

        Inventory historyInv = Bukkit.createInventory(this, 54, ChatColor.GOLD + plugin.getMessage("gui.history", "Operations History") + ": " + result.name);
        lastOpenedMenu.put(player.getUniqueId(), "history");
        lastTarget.put(player.getUniqueId(), result.name);

        int page = lastPage.getOrDefault(player.getUniqueId(), 0);
        int start = page * 45;
        int end = Math.min(start + 45, history.size());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (int i = start; i < end; i++) {
            Transaction t = history.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + t.action.toUpperCase() + " $" + String.format(moneyFormat, t.amount));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "By: " + t.executor);
            lore.add(ChatColor.GRAY + "Date: " + sdf.format(new Date(t.timestamp)));
            meta.setLore(lore);
            item.setItemMeta(meta);
            historyInv.setItem(i - start, item);
        }

        if (history.isEmpty()) {
            ItemStack noHistory = new ItemStack(Material.BARRIER);
            ItemMeta noMeta = noHistory.getItemMeta();
            noMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.no-players", "No transactions found"));
            noHistory.setItemMeta(noMeta);
            historyInv.setItem(22, noHistory);
        }

        int totalPages = (history.size() / 45) + (history.size() % 45 > 0 ? 1 : 0);
        ItemStack prev = page > 0 ? new ItemStack(Material.ARROW) : new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta prevMeta = prev.getItemMeta();
        prevMeta.setDisplayName(page > 0 ? ChatColor.YELLOW + plugin.getMessage("gui.previous-page", "Previous Page")
                : ChatColor.RED + plugin.getMessage("gui.no-page", "No Page"));
        List<String> prevLore = new ArrayList<>();
        prevLore.add(ChatColor.GRAY + plugin.getMessage("gui.page-info", "Page %current_page% of %total_pages%",
                "current_page", String.valueOf(page + 1), "total_pages", String.valueOf(totalPages)));
        prevMeta.setLore(prevLore);
        prev.setItemMeta(prevMeta);
        historyInv.setItem(45, prev);

        ItemStack next = page < totalPages - 1 ? new ItemStack(Material.ARROW) : new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta nextMeta = next.getItemMeta();
        nextMeta.setDisplayName(page < totalPages - 1 ? ChatColor.YELLOW + plugin.getMessage("gui.next-page", "Next Page")
                : ChatColor.RED + plugin.getMessage("gui.no-page", "No Page"));
        List<String> nextLore = new ArrayList<>();
        nextLore.add(ChatColor.GRAY + plugin.getMessage("gui.page-info", "Page %current_page% of %total_pages%",
                "current_page", String.valueOf(page + 1), "total_pages", String.valueOf(totalPages)));
        nextMeta.setLore(nextLore);
        next.setItemMeta(nextMeta);
        historyInv.setItem(53, next);

        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        back.setItemMeta(backMeta);
        historyInv.setItem(49, back);

        player.openInventory(historyInv);
    }

    private void openMassActionsMenu(Player player) {
        Inventory massInv = Bukkit.createInventory(this, 27, ChatColor.DARK_PURPLE + plugin.getMessage("gui.mass-menu-title", "Mass Operations"));
        ItemStack giveMass = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta giveMeta = giveMass.getItemMeta();
        giveMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("gui.mass-give", "Give Amount to All Selected"));
        List<String> giveLore = new ArrayList<>();
        giveLore.add(ChatColor.GRAY + plugin.getMessage("gui.mass-give-hint", "Click to enter amount for all"));
        giveMeta.setLore(giveLore);
        giveMass.setItemMeta(giveMeta);
        massInv.setItem(11, giveMass);


        ItemStack takeMass = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta takeMeta = takeMass.getItemMeta();
        takeMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.mass-take", "Take Amount from All Selected"));
        List<String> takeLore = new ArrayList<>();
        takeLore.add(ChatColor.GRAY + plugin.getMessage("gui.mass-take-hint", "Click to enter amount for all"));
        takeMeta.setLore(takeLore);
        takeMass.setItemMeta(takeMeta);
        massInv.setItem(13, takeMass);

        ItemStack setMass = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta setMeta = setMass.getItemMeta();
        setMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.mass-set", "Set Balance for All Selected"));
        List<String> setLore = new ArrayList<>();
        setLore.add(ChatColor.GRAY + plugin.getMessage("gui.mass-set-hint", "Click to enter amount for all"));
        setMeta.setLore(setLore);
        setMass.setItemMeta(setMeta);
        massInv.setItem(15, setMass);

        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        List<String> backLore = new ArrayList<>();
        backLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-return", "Return to Main Menu"));
        backMeta.setLore(backLore);
        backBtn.setItemMeta(backMeta);
        massInv.setItem(22, backBtn);

        player.openInventory(massInv);
        lastOpenedMenu.put(player.getUniqueId(), "mass");
    }

    private void logTransaction(String uuid, String action, double amount, Player executor) {
        transactionHistory.computeIfAbsent(uuid, k -> new ArrayList<>())
                .add(new Transaction(System.currentTimeMillis(), action, amount, executor.getName()));
        cleanOldTransactions();
        plugin.saveTransactions();
    }

    private List<PlayerResult> getFilteredPlayers() {
        List<PlayerResult> results = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() == null) continue;
            boolean matchesSearch = currentSearch.isEmpty() ||
                    player.getName().toLowerCase().contains(currentSearch.toLowerCase()) ||
                    player.getUniqueId().toString().toLowerCase().contains(currentSearch.toLowerCase());
            boolean matchesFilter = currentFilter == Filter.ALL ||
                    (currentFilter == Filter.ONLINE && player.isOnline()) ||
                    (currentFilter == Filter.OFFLINE && !player.isOnline()) ||
                    (currentFilter == Filter.SELECTED && isSelected(player.getUniqueId().toString()));
            if (matchesSearch && matchesFilter) {
                results.add(new PlayerResult(player.getUniqueId().toString(), player.getName(),
                        player.isOnline(), plugin.getEconomy().getBalance(player)));
            }
        }
        results.sort(Comparator.comparingDouble((PlayerResult pr) -> pr.balance).reversed());
        return results;
    }

    public void openMainGUI(Player player) {
        currentPage = lastPage.getOrDefault(player.getUniqueId(), 0);
        refreshGUI();
        player.openInventory(inventory);
        lastOpenedMenu.put(player.getUniqueId(), "main");
        playersInGUI.add(player.getUniqueId());
    }

    public void resetSearch(Player player) {
        UUID playerUUID = player.getUniqueId();
        currentSearch = "";
        currentFilter = Filter.ALL;
        currentPage = 0;
        selectedPlayers.removeAll(playerSelections.getOrDefault(playerUUID, Collections.emptySet()));
        playerSelections.remove(playerUUID);
        lastPage.put(playerUUID, 0);
        lastTarget.remove(playerUUID);
        lastOpenedMenu.put(playerUUID, "main");
        player.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.search-reset", "Search and filters reset."));
        openMainGUI(player);
    }

    public void setLastOpenedMenu(UUID playerId, String menu) {
        lastOpenedMenu.put(playerId, menu);
    }

    public void setLastTarget(UUID playerId, String target) {
        lastTarget.put(playerId, target);
    }

    public PlayerResult getPlayerResultByName(String name) {
        return cachedResults.stream().filter(r -> r.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private void requestAmount(Player player, String action, String targetName) {
        PlayerResult result = getPlayerResultByName(targetName);
        if (result == null) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-not-found", "Player not found."));
            return;
        }
        player.closeInventory();
        TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-amount", "Enter amount (or click to cancel):"));
        TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
        message.addExtra(cancel);
        player.spigot().sendMessage(message);
        pendingActions.put(player.getUniqueId(), (msg, p) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("отмена")) {
                    p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.action-cancelled", "Action cancelled."));
                    openMainGUI(p);
                    pendingActions.remove(p.getUniqueId());
                    pendingActionType.remove(p.getUniqueId());
                    return;
                }
                try {
                    double amount = Double.parseDouble(msg.trim());
                    if (amount <= 0) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                        openMainGUI(p);
                        return;
                    }
                    OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                    boolean success = false;
                    if (action.equals("give")) {
                        success = plugin.getEconomy().depositPlayer(target, amount).transactionSuccess();
                        if (success) logTransaction(result.uuid, "give", amount, p);
                    } else if (action.equals("take")) {
                        if (plugin.getEconomy().getBalance(target) >= amount) {
                            success = plugin.getEconomy().withdrawPlayer(target, amount).transactionSuccess();
                            if (success) logTransaction(result.uuid, "take", amount, p);
                        } else {
                            p.sendMessage(ChatColor.RED + plugin.getMessage("error.insufficient-funds",
                                    "Insufficient funds for %player%", "%player%", target.getName()));
                        }
                    }
                    if (success) {
                        p.sendMessage(ChatColor.GREEN + plugin.getMessage("action.executed",
                                "Executed %action% of $%amount% for %player%",
                                "%action%", action, "%amount%", String.format(moneyFormat, amount), "%player%", result.name));
                    } else {
                        p.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
                    }
                    openMainGUI(p);
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                    openMainGUI(p);
                }
                pendingActions.remove(p.getUniqueId());
                pendingActionType.remove(p.getUniqueId());
            });
        });
        pendingActionType.put(player.getUniqueId(), action);
        pendingActionTarget.put(player.getUniqueId(), result);
    }

    private void requestMassAmount(Player player, String action) {
        player.closeInventory();
        TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-amount", "Enter amount (or click to cancel):"));
        TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
        message.addExtra(cancel);
        player.spigot().sendMessage(message);
        pendingActions.put(player.getUniqueId(), (msg, p) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("отмена")) {
                    p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.action-cancelled", "Action cancelled."));
                    openMainGUI(p);
                    pendingActions.remove(p.getUniqueId());
                    pendingActionType.remove(p.getUniqueId());
                    return;
                }
                try {
                    double amount = Double.parseDouble(msg.trim());
                    if (amount <= 0) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                        openMainGUI(p);
                        return;
                    }
                    Set<UUID> selected = playerSelections.getOrDefault(p.getUniqueId(), Collections.emptySet());
                    if (selected.isEmpty()) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players-selected", "No players selected."));
                        openMainGUI(p);
                        return;
                    }
                    int successCount = 0;
                    for (UUID uuid : selected) {
                        OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
                        if (target.getName() == null) continue;
                        boolean success = false;
                        if (action.equals("mass-give")) {
                            success = plugin.getEconomy().depositPlayer(target, amount).transactionSuccess();
                            if (success) logTransaction(uuid.toString(), "give", amount, p);
                        } else if (action.equals("mass-take")) {
                            if (plugin.getEconomy().getBalance(target) >= amount) {
                                success = plugin.getEconomy().withdrawPlayer(target, amount).transactionSuccess();
                                if (success) logTransaction(uuid.toString(), "take", amount, p);
                            }
                        } else if (action.equals("mass-set")) {
                            double current = plugin.getEconomy().getBalance(target);
                            plugin.getEconomy().withdrawPlayer(target, current);
                            success = plugin.getEconomy().depositPlayer(target, amount).transactionSuccess();
                            if (success) logTransaction(uuid.toString(), "set", amount, p);
                        }
                        if (success) successCount++;
                    }
                    String actionName = action.equals("mass-give") ? "gave" : action.equals("mass-take") ? "took" : "set";
                    p.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.mass-action-applied",
                            "Applied %action% of $%amount% to %count% players",
                            "%action%", actionName, "%amount%", String.format(moneyFormat, amount), "%count%", String.valueOf(successCount)));
                    openMainGUI(p);
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                    openMainGUI(p);
                }
                pendingActions.remove(p.getUniqueId());
                pendingActionType.remove(p.getUniqueId());
            });
        });
        pendingActionType.put(player.getUniqueId(), action);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EconomySearchGUI || holder instanceof StatsMenuHolder)) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        UUID playerUUID = player.getUniqueId();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String menu = lastOpenedMenu.getOrDefault(playerUUID, "main");
        String displayName = clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "";
        long now = System.currentTimeMillis();

        if (event.getView().getTitle().equals(ChatColor.DARK_PURPLE + plugin.getMessage("gui.stats-title", "Economy Statistics"))) {
            if (event.getSlot() == 49) {
                player.closeInventory();
                openLastGUIMenu(player);
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
                return;
            }
            return;
        }

        if (menu.equals("main")) {
            int slot = event.getSlot();
            if ((slot == 45 || slot == 53) && now - lastPageSwitch.getOrDefault(playerUUID, 0L) < PAGE_SWITCH_COOLDOWN) {
                return;
            }
            if (slot == 45 && currentPage > 0) {
                lastPageSwitch.put(playerUUID, now);
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    currentPage = Math.max(0, currentPage - 5);
                } else {
                    currentPage--;
                }
                lastPage.put(playerUUID, currentPage);
                refreshGUI();
            } else if (slot == 53 && (currentPage + 1) * 36 < cachedResults.size()) {
                lastPageSwitch.put(playerUUID, now);
                if (event.getClick() == ClickType.SHIFT_RIGHT) {
                    int totalPages = (cachedResults.size() / 36) + (cachedResults.size() % 36 > 0 ? 1 : 0);
                    currentPage = Math.min(totalPages - 1, currentPage + 5);
                } else {
                    currentPage++;
                }
                lastPage.put(playerUUID, currentPage);
                refreshGUI();
            } else if (slot == 46) {
                for (PlayerResult result : cachedResults.subList(currentPage * 36, Math.min((currentPage + 1) * 36, cachedResults.size()))) {
                    UUID uuid = UUID.fromString(result.uuid);
                    selectedPlayers.add(uuid);
                    playerSelections.computeIfAbsent(playerUUID, k -> new HashSet<>()).add(uuid);
                }
                refreshGUI();
            } else if (slot == 47) {
                Set<UUID> selections = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
                selectedPlayers.removeAll(selections);
                playerSelections.remove(playerUUID);
                refreshGUI();
            } else if (slot == 48) {
                Set<UUID> selected = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
                if (selected.isEmpty()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players-selected", "No players selected."));
                    return;
                }
                openMassActionsMenu(player);
            } else if (slot == 4) {
                if (event.getClick() == ClickType.RIGHT) {
                    resetSearch(player);
                } else if (event.getClick() == ClickType.LEFT) {
                    player.closeInventory();
                    TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-search", "Enter name or UUID to search: "));
                    TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
                    cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
                    message.addExtra(cancel);
                    player.spigot().sendMessage(message);
                    pendingActions.put(playerUUID, (msg, p) -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            String input = ChatColor.stripColor(msg.trim());
                            if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) {
                                resetSearch(p);
                                return;
                            }
                            currentSearch = input;
                            currentPage = 0;
                            lastPage.put(p.getUniqueId(), 0);
                            cachedResults.clear();
                            p.openInventory(inventory);
                            playersInGUI.add(p.getUniqueId());
                            lastOpenedMenu.put(p.getUniqueId(), "main");
                            cachedResults = getFilteredPlayers();
                            setupMainMenuLayout();
                            p.updateInventory();
                            pendingActions.remove(p.getUniqueId());
                        });
                    });
                }
            } else if (event.getSlot() == 49) {
                if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT) {
                    switch (currentFilter) {
                        case ALL:
                            currentFilter = Filter.ONLINE;
                            break;
                        case ONLINE:
                            currentFilter = Filter.OFFLINE;
                            break;
                        case OFFLINE:
                            currentFilter = Filter.ALL;
                            break;
                    }
                    refreshGUI();
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                } else if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                    switch (currentFilter) {
                        case ALL:
                            currentFilter = Filter.OFFLINE;
                            break;
                        case OFFLINE:
                            currentFilter = Filter.ONLINE;
                            break;
                        case ONLINE:
                            currentFilter = Filter.ALL;
                            break;
                    }
                    refreshGUI();
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                }
            } else if (event.getSlot() == 50) {
                player.closeInventory();
                openGlobalStatsMenu(player);
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
                return;
            } else if (slot >= 9 && slot <= 44) {
                String name = ChatColor.stripColor(displayName);
                PlayerResult result = cachedResults.stream()
                        .filter(r -> r.name.equalsIgnoreCase(name))
                        .findFirst()
                        .orElse(null);
                if (result == null) return;
                if (event.getClick() == ClickType.LEFT) {
                    openPlayerFinanceManagement(player, result);
                } else if (event.getClick() == ClickType.RIGHT) {
                    openContextMenu(player, name);
                } else if (event.getClick() == ClickType.SHIFT_LEFT && now - lastPageSwitch.getOrDefault(playerUUID, 0L) >= PAGE_SWITCH_COOLDOWN) {
                    lastPageSwitch.put(playerUUID, now);
                    UUID uuid = UUID.fromString(result.uuid);
                    Set<UUID> selections = playerSelections.computeIfAbsent(playerUUID, k -> new HashSet<>());
                    if (selectedPlayers.contains(uuid)) {
                        selectedPlayers.remove(uuid);
                        selections.remove(uuid);
                    } else {
                        selectedPlayers.add(uuid);
                        selections.add(uuid);
                    }
                    refreshGUI();
                }
            }
        } else if (menu.equals("finance")) {
            int slot = event.getSlot();
            String targetName = lastTarget.get(playerUUID);
            PlayerResult result = getPlayerResultByName(targetName);
            if (result == null) return;
            if (slot == 13) {
                player.closeInventory();
                if (player.hasPermission("economygui.give")) {
                    TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-amount", "Enter amount in chat (or 'cancel' to abort):"));
                    TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
                    cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
                    message.addExtra(cancel);
                    player.spigot().sendMessage(message);
                    pendingActions.put(playerUUID, (msg, p) -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("отмена")) {
                                p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.input-cancelled", "Input cancelled."));
                                openPlayerFinanceManagement(p, result);
                                pendingActions.remove(p.getUniqueId());
                                return;
                            }
                            try {
                                double amount = Double.parseDouble(msg.trim());
                                if (amount <= 0) {
                                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                                    openPlayerFinanceManagement(p, result);
                                    return;
                                }
                                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                                if (plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                                    p.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.gave-amount",
                                            "Gave $%amount% to %player%", "%amount%", String.format(moneyFormat, amount), "%player%", result.name));
                                    logTransaction(result.uuid, "give", amount, p);
                                } else {
                                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
                                }
                            } catch (NumberFormatException e) {
                                p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                            }
                            openPlayerFinanceManagement(p, result);
                            pendingActions.remove(p.getUniqueId());
                        });
                    });
                } else {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("messages.no-permission-give", "You don't have permission to give."));
                    openPlayerFinanceManagement(player, result);
                }
            } else if (slot == 20) {
                openDigitalMenu(player, plugin.getMessage("action.give", "Give"), result);
            } else if (slot == 22) {
                openDigitalMenu(player, plugin.getMessage("action.take", "Take"), result);
            } else if (slot == 24) {
                player.closeInventory();
                TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-amount", "Enter amount in chat (or 'cancel' to abort):"));
                TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
                cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
                message.addExtra(cancel);
                player.spigot().sendMessage(message);
                pendingActions.put(playerUUID, (msg, p) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("отмена")) {
                            p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.input-cancelled", "Input cancelled."));
                            openPlayerFinanceManagement(p, result);
                            pendingActions.remove(p.getUniqueId());
                            return;
                        }
                        try {
                            double amount = Double.parseDouble(msg.trim());
                            if (amount < 0) {
                                p.sendMessage(ChatColor.RED + plugin.getMessage("error.negative-amount", "Amount cannot be negative."));
                                openPlayerFinanceManagement(p, result);
                                return;
                            }
                            OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                            double current = plugin.getEconomy().getBalance(target);
                            if (plugin.getEconomy().withdrawPlayer(target, current).transactionSuccess() &&
                                    plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                                p.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.set-amount",
                                        "Set balance to $%amount% for %player%", "%amount%", String.format(moneyFormat, amount), "%player%", result.name));
                                logTransaction(result.uuid, "set", amount, p);
                            } else {
                                p.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
                            }
                        } catch (NumberFormatException e) {
                            p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                        }
                        openPlayerFinanceManagement(p, result);
                        pendingActions.remove(p.getUniqueId());
                    });
                });
            } else if (slot == 31) {
                openHistoryMenu(player, result);
            } else if (slot == 49) {
                openMainGUI(player);
            }
        } else if (menu.equals("context")) {
            String targetName = lastTarget.getOrDefault(player.getUniqueId(), "");
            PlayerResult result = getPlayerResultByName(targetName);
            if (result == null) {
                openMainGUI(player);
                lastOpenedMenu.put(player.getUniqueId(), "main");
                return;
            }
            if (event.getSlot() == 4 && plugin.isFullManagementEnabled()) {
                openPlayerFinanceManagement(player, result);
                lastOpenedMenu.put(player.getUniqueId(), "finance");
            } else if (event.getSlot() >= 10 && event.getSlot() <= 15 && plugin.isQuickActionsEnabled()) {
                if (event.getSlot() == 10) executeQuickAction(player, "give", 100);
                else if (event.getSlot() == 11) executeQuickAction(player, "give", 1000);
                else if (event.getSlot() == 12) executeQuickAction(player, "take", 100);
                else if (event.getSlot() == 13) executeQuickAction(player, "take", 1000);
                else if (event.getSlot() == 14) executeQuickAction(player, "set", 5000);
                else if (event.getSlot() == 15) executeQuickAction(player, "set", 0);
            } else if (event.getSlot() == 16 && plugin.isQuickActionsEnabled()) {
                requestAmount(player, "custom", result.toString());
            } else if (event.getSlot() == 22) {
                openMainGUI(player);
                lastOpenedMenu.put(player.getUniqueId(), "main");
            }
        } else if (menu.equals("digital")) {
            int slotClicked = event.getSlot();
            PlayerResult result = pendingActionTarget.get(playerUUID);
            if (result == null) return;
            if (slotClicked == 16) {
                double amount = pendingActionAmount.getOrDefault(playerUUID, 0.0);
                String action = pendingActionType.getOrDefault(playerUUID, plugin.getMessage("action.give", "Give"));
                if (amount <= 0) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                boolean success = false;
                if (action.equals(plugin.getMessage("action.give", "Give"))) {
                    success = plugin.getEconomy().depositPlayer(target, amount).transactionSuccess();
                    if (success) logTransaction(result.uuid, "give", amount, player);
                } else if (action.equals(plugin.getMessage("action.take", "Take"))) {
                    if (plugin.getEconomy().getBalance(target) >= amount) {
                        success = plugin.getEconomy().withdrawPlayer(target, amount).transactionSuccess();
                        if (success) logTransaction(result.uuid, "take", amount, player);
                    } else {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.insufficient-funds",
                                "Insufficient funds for %player%", "player", target.getName()));
                    }
                }
                if (success) {
                    player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.executed",
                            "Executed %action% of $%amount% for %player%", "action", action, "amount", String.format(moneyFormat, amount), "player", result.name));
                } else {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
                }
                pendingActionAmount.put(playerUUID, 0.0);
                refreshGUI();
                openPlayerFinanceManagement(player, result);
            } else if (slotClicked == 18) {
                pendingActionAmount.put(playerUUID, 0.0);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("gui.amount-selected",
                        "Selected amount: %amount%", "amount", "0"));
                openDigitalMenu(player, pendingActionType.get(playerUUID), result);
            } else if (slotClicked == 26) {
                openPlayerFinanceManagement(player, result);
            } else if (slotClicked >= 9 && slotClicked <= 14) {
                int[] amounts = {1, 5, 10, 100, 1000, 5000};
                int index = slotClicked - 9;
                if (index >= 0 && index < amounts.length) {
                    double currentAmount = pendingActionAmount.getOrDefault(playerUUID, 0.0);
                    pendingActionAmount.put(playerUUID, currentAmount + amounts[index]);
                    player.sendMessage(ChatColor.GREEN + plugin.getMessage("gui.amount-selected",
                            "Selected amount: %amount%", "amount", String.valueOf(amounts[index])));
                    openDigitalMenu(player, pendingActionType.get(playerUUID), result);
                }
            } else if (slotClicked == 22) {
                player.closeInventory();
                TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-custom", "Enter custom amount: "));
                TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
                cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
                message.addExtra(cancel);
                player.spigot().sendMessage(message);
                pendingActions.put(playerUUID, (msg, p) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        String input = ChatColor.stripColor(msg.trim());
                        if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) {
                            p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.input-cancelled", "Input cancelled."));
                            openDigitalMenu(p, pendingActionType.get(p.getUniqueId()), pendingActionTarget.get(p.getUniqueId()));
                            pendingActions.remove(p.getUniqueId());
                            return;
                        }
                        try {
                            double amount = Double.parseDouble(input);
                            if (amount <= 0) {
                                p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                                openDigitalMenu(p, pendingActionType.get(p.getUniqueId()), pendingActionTarget.get(p.getUniqueId()));
                                return;
                            }
                            double currentAmount = pendingActionAmount.getOrDefault(p.getUniqueId(), 0.0);
                            pendingActionAmount.put(p.getUniqueId(), currentAmount + amount);
                            p.sendMessage(ChatColor.GREEN + plugin.getMessage("gui.amount-selected",
                                    "Selected amount: %amount%", "amount", String.valueOf(amount)));
                            openDigitalMenu(p, pendingActionType.get(p.getUniqueId()), pendingActionTarget.get(p.getUniqueId()));
                        } catch (NumberFormatException e) {
                            p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                            openDigitalMenu(p, pendingActionType.get(p.getUniqueId()), pendingActionTarget.get(p.getUniqueId()));
                        }
                        pendingActions.remove(p.getUniqueId());
                    });
                });
            }
        } else if (menu.equals("history")) {
            if (event.getSlot() == 49) {
                PlayerResult result = getPlayerResultByName(lastTarget.get(player.getUniqueId()));
                if (result != null) {
                    if (!plugin.isFullManagementEnabled()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled", "Full management is disabled in config!"));
                        openMainGUI(player);
                        lastOpenedMenu.put(player.getUniqueId(), "main");
                    } else {
                        openPlayerFinanceManagement(player, result);
                        lastOpenedMenu.put(player.getUniqueId(), "finance");
                    }
                }
            } else if (event.getSlot() == 45) {
                int page = lastPage.getOrDefault(player.getUniqueId(), 0);
                if (page > 0) {
                    lastPage.put(player.getUniqueId(), page - 1);
                    openHistoryMenu(player, getPlayerResultByName(lastTarget.get(player.getUniqueId())));
                }
            } else if (event.getSlot() == 53) {
                int page = lastPage.getOrDefault(player.getUniqueId(), 0);
                PlayerResult result = getPlayerResultByName(lastTarget.get(player.getUniqueId()));
                if (result != null) {
                    List<Transaction> history = transactionHistory.getOrDefault(result.uuid, new ArrayList<>());
                    int totalPages = (history.size() / 45) + (history.size() % 45 > 0 ? 1 : 0);
                    if (page < totalPages - 1) {
                        lastPage.put(player.getUniqueId(), page + 1);
                        openHistoryMenu(player, result);
                    }
                }
            }
        } else if (menu.equals("mass")) {
            int slot = event.getSlot();
            if (slot == 11) {
                requestMassAmount(player, "mass-give");
            } else if (slot == 13) {
                requestMassAmount(player, "mass-take");
            } else if (slot == 15) {
                requestMassAmount(player, "mass-set");
            } else if (slot == 22) {
                openMainGUI(player);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (pendingActions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            String message = event.getMessage();
            Bukkit.getScheduler().runTask(plugin, () -> {
                ChatAction action = pendingActions.get(player.getUniqueId());
                if (action == null) return;

                if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("отмена")) {
                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.input-cancelled", "Input cancelled."));
                    pendingActions.remove(player.getUniqueId());
                    openLastGUIMenu(player);
                    return;
                }
                if (pendingActionType.get(player.getUniqueId()) != null && pendingActionType.get(player.getUniqueId()).startsWith("mass-")) {
                    String actionType = pendingActionType.get(player.getUniqueId());
                    try {
                        double amount = Double.parseDouble(message.trim());
                        if ((actionType.equals("mass-set") && amount < 0) || (!actionType.equals("mass-set") && amount <= 0)) {
                            player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                            openMassActionsMenu(player);
                            return;
                        }
                        int count = 0;
                        for (UUID uuid : selectedPlayers) {
                            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
                            if (actionType.equals("mass-give")) {
                                if (plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                                    count++;
                                    logTransaction(uuid.toString(), "give", amount, player);
                                }
                            } else if (actionType.equals("mass-take")) {
                                if (plugin.getEconomy().getBalance(target) >= amount) {
                                    if (plugin.getEconomy().withdrawPlayer(target, amount).transactionSuccess()) {
                                        count++;
                                        logTransaction(uuid.toString(), "take", amount, player);
                                    }
                                }
                            } else if (actionType.equals("mass-set")) {
                                if (plugin.getEconomy().withdrawPlayer(target, plugin.getEconomy().getBalance(target)).transactionSuccess() &&
                                        plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                                    count++;
                                    logTransaction(uuid.toString(), "set", amount, player);
                                }
                            }
                        }
                        player.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.mass-action-applied",
                                "Applied %action% on $%amount% to %count% players", "action", actionType.replace("mass-", ""),
                                "amount", String.format(moneyFormat, amount), "count", String.valueOf(count)));
                        openMassActionsMenu(player);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                        openMassActionsMenu(player);
                    }
                    pendingActions.remove(player.getUniqueId());
                    pendingActionType.remove(player.getUniqueId());
                    return;
                }
                action.execute(message, player);
            });
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EconomySearchGUI) {
            playersInGUI.remove(event.getPlayer().getUniqueId());
        }
    }

    private void executeQuickAction(Player player, String action, double amount) {
        PlayerResult result = getPlayerResultByName(lastTarget.get(player.getUniqueId()));
        if (result == null) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        Economy econ = plugin.getEconomy();

        boolean success = false;
        if (action.equals("give")) {
            success = econ.depositPlayer(target, amount).transactionSuccess();
        } else if (action.equals("take")) {
            if (econ.getBalance(target) >= amount) {
                success = econ.withdrawPlayer(target, amount).transactionSuccess();
            } else {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.insufficient-funds", "Insufficient funds for %player%", "player", result.name));
                return;
            }
        } else if (action.equals("set")) {
            success = econ.withdrawPlayer(target, econ.getBalance(target)).transactionSuccess() &&
                    econ.depositPlayer(target, amount).transactionSuccess();
        }

        if (success) {
            logTransaction(result.uuid, action, amount, player);
            player.sendMessage(ChatColor.GREEN + plugin.getMessage("action.executed", "Executed %action% of $%amount% for %player%", "action", action, "amount", String.format(moneyFormat, amount), "player", result.name));
        } else {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
        }
        openContextMenu(player, result.name);
    }

    public void loadTransactionHistory(FileConfiguration config) {
        transactionHistory.clear();
        for (String uuid : config.getKeys(false)) {
            List<Transaction> list = new ArrayList<>();
            List<String> rawList = config.getStringList(uuid);
            for (String raw : rawList) {
                String[] parts = raw.split(";");
                if (parts.length == 4) {
                    try {
                        long ts = Long.parseLong(parts[0]);
                        String action = parts[1];
                        double amt = Double.parseDouble(parts[2]);
                        String exec = parts[3];
                        list.add(new Transaction(ts, action, amt, exec));
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid transaction format for UUID " + uuid + ": " + raw);
                    }
                }
            }
            transactionHistory.put(uuid, list);
        }
        cleanOldTransactions();
    }

    public void saveTransactionHistory(FileConfiguration config) {
        cleanOldTransactions();
        Set<String> currentKeys = new HashSet<>(config.getKeys(false));
        for (String key : currentKeys) {
            if (!transactionHistory.containsKey(key)) {
                config.set(key, null);
            }
        }
        for (Map.Entry<String, List<Transaction>> entry : transactionHistory.entrySet()) {
            List<String> rawList = entry.getValue().stream()
                    .map(t -> t.timestamp + ";" + t.action + ";" + t.amount + ";" + t.executor)
                    .collect(Collectors.toList());
            config.set(entry.getKey(), rawList);
        }
    }

    public void cleanOldTransactions() {
        if (plugin.transactionRetentionDays <= 0) return;
        long cutoff = System.currentTimeMillis() - (plugin.transactionRetentionDays * 86400000L);
        for (List<Transaction> list : transactionHistory.values()) {
            list.removeIf(t -> t.timestamp < cutoff);
        }
        transactionHistory.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void openLastGUIMenu(Player player) {
        String lastMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "main");
        currentPage = lastPage.getOrDefault(player.getUniqueId(), 0);
        playersInGUI.add(player.getUniqueId());
        if (lastMenu.equals("main")) {
            openMainGUI(player);
        } else if (lastMenu.equals("finance")) {
            String targetName = lastTarget.getOrDefault(player.getUniqueId(), "");
            PlayerResult result = getPlayerResultByName(targetName);
            if (result != null) openPlayerFinanceManagement(player, result);
            else openMainGUI(player);
        } else if (lastMenu.equals("context")) {
            String targetName = lastTarget.getOrDefault(player.getUniqueId(), "");
            if (!targetName.isEmpty()) openContextMenu(player, targetName);
            else openMainGUI(player);
        } else if (lastMenu.equals("digital")) {
            PlayerResult result = pendingActionTarget.get(player.getUniqueId());
            String action = pendingActionType.get(player.getUniqueId());
            if (result != null && action != null) openDigitalMenu(player, action, result);
            else openMainGUI(player);
        } else if (lastMenu.equals("history")) {
            String targetName = lastTarget.getOrDefault(player.getUniqueId(), "");
            PlayerResult result = getPlayerResultByName(targetName);
            if (result != null) openHistoryMenu(player, result);
            else openMainGUI(player);
        } else if (lastMenu.equals("mass")) {
            openMassActionsMenu(player);
        }
    }
    private List<PlayerResult> getAllPlayersWithBalances() {
        List<PlayerResult> results = new ArrayList<>();
        Economy econ = plugin.getEconomy();

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() == null) continue;

            double balance = econ.getBalance(offline);
            boolean online = offline.isOnline();
            results.add(new PlayerResult(offline.getUniqueId().toString(), offline.getName(), online, balance));
        }

        return results;
    }
    private class StatsMenuHolder implements InventoryHolder {
        private final Inventory inventory;

        public StatsMenuHolder(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
    private void openGlobalStatsMenu(Player player) {
        player.sendMessage(ChatColor.YELLOW + plugin.getMessage("gui.stats-loading", "Loading economy statistics"));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PlayerResult> allPlayers = getAllPlayersWithBalances();

            if (allPlayers.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players", "No players found."));
                });
                return;
            }

            double totalBalance = 0;
            int playerCount = allPlayers.size();
            int onlineCount = 0;
            double onlineBalance = 0;
            double maxBalance = 0;
            double minBalance = Double.MAX_VALUE;

            PlayerResult richest = null;
            PlayerResult poorest = null;

            for (PlayerResult pr : allPlayers) {
                totalBalance += pr.balance;
                if (pr.online) {
                    onlineCount++;
                    onlineBalance += pr.balance;
                }
                if (pr.balance > maxBalance) {
                    maxBalance = pr.balance;
                    richest = pr;
                }
                if (pr.balance < minBalance) {
                    minBalance = pr.balance;
                    poorest = pr;
                }
            }

            double average = playerCount > 0 ? totalBalance / playerCount : 0;
            double averageOnline = onlineCount > 0 ? onlineBalance / onlineCount : 0;

            List<PlayerResult> topRich = allPlayers.stream()
                    .sorted(Comparator.comparingDouble((PlayerResult p) -> p.balance).reversed())
                    .limit(5)
                    .collect(Collectors.toList());

            List<PlayerResult> topPoor = allPlayers.stream()
                    .sorted(Comparator.comparingDouble((PlayerResult p) -> p.balance))
                    .limit(5)
                    .collect(Collectors.toList());

            final int finalPlayerCount = playerCount;
            final int finalOnlineCount = onlineCount;
            final double finalTotalBalance = totalBalance;
            final double finalOnlineBalance = onlineBalance;
            final double finalAverage = average;
            final double finalAverageOnline = averageOnline;
            final PlayerResult finalRichest = richest;
            final PlayerResult finalPoorest = poorest;
            final List<PlayerResult> finalTopRich = topRich;
            final List<PlayerResult> finalTopPoor = topPoor;

            Bukkit.getScheduler().runTask(plugin, () -> {
                Inventory statsInv = Bukkit.createInventory(new StatsMenuHolder(null), 54, ChatColor.DARK_PURPLE + plugin.getMessage("gui.stats-title", "Economy Statistics"));

                ItemStack info = new ItemStack(Material.BOOK);
                ItemMeta meta = info.getItemMeta();
                meta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.stats-overview", "Server Economy Overview"));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-total-players", "Total players: ") + ChatColor.WHITE + finalPlayerCount);
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-online-players", "Online players: ") + ChatColor.GREEN + finalOnlineCount);
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-total-balance", "Total balance: ") + ChatColor.GREEN + String.format(moneyFormat, finalTotalBalance));
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-online-balance", "Online balance: ") + ChatColor.GREEN + String.format(moneyFormat, finalOnlineBalance));
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-average-balance", "Average balance: ") + ChatColor.GREEN + String.format(moneyFormat, finalAverage));
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-average-online", "Avg online balance: ") + ChatColor.GREEN + String.format(moneyFormat, finalAverageOnline));
                lore.add("");
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-richest", "Richest: ") + ChatColor.GOLD + (finalRichest != null ? finalRichest.name : "—") +
                        " (" + String.format(moneyFormat, finalRichest != null ? finalRichest.balance : 0) + ")");
                lore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-poorest", "Poorest: ") + ChatColor.RED + (finalPoorest != null ? finalPoorest.name : "—") +
                        " (" + String.format(moneyFormat, finalPoorest != null ? finalPoorest.balance : 0) + ")");
                meta.setLore(lore);
                info.setItemMeta(meta);
                statsInv.setItem(11, info);

                ItemStack topRichItem = new ItemStack(Material.DIAMOND);
                ItemMeta topRichMeta = topRichItem.getItemMeta();
                topRichMeta.setDisplayName(ChatColor.GOLD + plugin.getMessage("gui.stats-top-rich", "Top 5 Richest"));
                List<String> richLore = new ArrayList<>();
                int pos = 1;
                for (PlayerResult pr : finalTopRich) {
                    richLore.add(ChatColor.GRAY + String.valueOf(pos) + ". " + ChatColor.WHITE + pr.name + ChatColor.GRAY + " — " + ChatColor.GREEN + String.format(moneyFormat, pr.balance));
                    pos++;
                }
                topRichMeta.setLore(richLore);
                topRichItem.setItemMeta(topRichMeta);
                statsInv.setItem(13, topRichItem);

                ItemStack topPoorItem = new ItemStack(Material.IRON_INGOT);
                ItemMeta topPoorMeta = topPoorItem.getItemMeta();
                topPoorMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.stats-top-poor", "Top 5 Poorest"));
                List<String> poorLore = new ArrayList<>();
                pos = 1;
                for (PlayerResult pr : finalTopPoor) {
                    poorLore.add(ChatColor.GRAY + String.valueOf(pos) + ". " + ChatColor.WHITE + pr.name + ChatColor.GRAY + " — " + ChatColor.RED + String.format(moneyFormat, pr.balance));
                    pos++;
                }
                topPoorMeta.setLore(poorLore);
                topPoorItem.setItemMeta(topPoorMeta);
                statsInv.setItem(15, topPoorItem);

                ItemStack back = new ItemStack(Material.ARROW);
                ItemMeta backMeta = back.getItemMeta();
                backMeta.setDisplayName(ChatColor.YELLOW + plugin.getMessage("gui.back", "Back"));
                List<String> backLore = new ArrayList<>();
                backLore.add(ChatColor.GRAY + plugin.getMessage("gui.click-to-return", "Return to main menu"));
                backMeta.setLore(backLore);
                back.setItemMeta(backMeta);
                statsInv.setItem(49, back);

                player.openInventory(statsInv);
            });
        });
    }
    public void refreshOpenGUIs() {
        for (UUID uuid : playersInGUI) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                openLastGUIMenu(player);
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private static class PlayerResult {
        String uuid;
        String name;
        boolean online;
        double balance;

        PlayerResult(String uuid, String name, boolean online, double balance) {
            this.uuid = uuid;
            this.name = name;
            this.online = online;
            this.balance = balance;
        }
    }
}