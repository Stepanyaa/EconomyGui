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
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EconomySearchGUI implements Listener, InventoryHolder {
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
    private final Map<String, List<String>> transactionHistory = new ConcurrentHashMap<>();
    private String moneyFormat;
    private boolean usePlaceholderAPI;
    private final String[] skinNames = {"Notch", "jeb_", "Dinnerbone", "Herobrine"};
    private final Map<UUID, Long> lastPageSwitch = new ConcurrentHashMap<>();

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

        inventory.setItem(49, createFilterItem(Filter.ALL));
        inventory.setItem(50, createFilterItem(Filter.ONLINE));
        inventory.setItem(51, createFilterItem(Filter.OFFLINE));

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

    private ItemStack createFilterItem(Filter filter) {
        Material mat;
        switch (filter) {
            case ALL:
                mat = Material.COMPASS;
                break;
            case ONLINE:
                mat = Material.LIME_DYE;
                break;
            case OFFLINE:
                mat = Material.GRAY_DYE;
                break;
            default:
                mat = Material.PAPER;
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        String displayName;
        switch (filter) {
            case ALL:
                displayName = ChatColor.WHITE + plugin.getMessage("filter.all", "All Players");
                break;
            case ONLINE:
                displayName = ChatColor.GREEN + plugin.getMessage("filter.online", "Online Players");
                break;
            case OFFLINE:
                displayName = ChatColor.GRAY + plugin.getMessage("filter.offline", "Offline Players");
                break;
            default:
                displayName = ChatColor.GRAY + "Unknown";
                break;
        }
        meta.setDisplayName(displayName);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage("filter.filter-hint", "Click to apply filter"));
        if (currentFilter == filter) {
            lore.add(ChatColor.GREEN + plugin.getMessage("filter.filter-active", "✓ Active filter"));
            meta.addEnchant(getGlowEnchantment(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
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
        player.sendMessage(ChatColor.GREEN + "Применён фильтр: " + newFilter.name());
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
        if (result == null) {
            player.sendMessage(ChatColor.RED + "Игрок не найден.");
            return;
        }
        Inventory contextMenu = Bukkit.createInventory(this, 9, ChatColor.DARK_PURPLE + plugin.getMessage("context-menu.title", "Quick Actions for " + targetName));
        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        contextMenu.setItem(4, createPlayerHead(target, 0));

        ItemStack give = new ItemStack(Material.EMERALD);
        ItemMeta giveMeta = give.getItemMeta();
        giveMeta.setDisplayName(ChatColor.GREEN + plugin.getMessage("context-menu.give-money", "Give Money"));
        give.setItemMeta(giveMeta);
        contextMenu.setItem(3, give);

        ItemStack take = new ItemStack(Material.REDSTONE);
        ItemMeta takeMeta = take.getItemMeta();
        takeMeta.setDisplayName(ChatColor.RED + plugin.getMessage("context-menu.take-money", "Take Money"));
        take.setItemMeta(takeMeta);
        contextMenu.setItem(5, take);

        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        List<String> backLore = new ArrayList<>();
        backLore.add(ChatColor.GRAY + plugin.getMessage("context-menu.back-lore", "Return to main menu"));
        backMeta.setLore(backLore);
        backBtn.setItemMeta(backMeta);
        contextMenu.setItem(8, backBtn);

        player.openInventory(contextMenu);
        lastOpenedMenu.put(player.getUniqueId(), "context");
        lastTarget.put(player.getUniqueId(), targetName);
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
        Inventory historyInv = Bukkit.createInventory(this, 54, ChatColor.DARK_PURPLE + plugin.getMessage("history.title", "Operations History: %player%", "%player%", result.name));
        List<String> history = getPlayerHistory(result.uuid);
        int slot = 0;
        for (String entry : history) {
            ItemStack historyItem = new ItemStack(Material.PAPER);
            ItemMeta meta = historyItem.getItemMeta();
            meta.setDisplayName(ChatColor.WHITE + plugin.getMessage("history.operation", "Operation #%number%", "%number%", String.valueOf(slot + 1)));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + entry);
            meta.setLore(lore);
            historyItem.setItemMeta(meta);
            historyInv.setItem(slot, historyItem);
            slot++;
            if (slot >= 45) break;
        }
        if (history.isEmpty()) {
            ItemStack noHistory = new ItemStack(Material.BARRIER);
            ItemMeta meta = noHistory.getItemMeta();
            meta.setDisplayName(ChatColor.RED + plugin.getMessage("history.empty", "History Empty"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + plugin.getMessage("history.empty-hint", "No recorded operations for %player%", "%player%", result.name));
            meta.setLore(lore);
            noHistory.setItemMeta(meta);
            historyInv.setItem(22, noHistory);
        }

        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.setDisplayName(ChatColor.RED + plugin.getMessage("gui.back", "Back"));
        List<String> backLore = new ArrayList<>();
        backLore.add(ChatColor.GRAY + plugin.getMessage("gui.back-hint-finance", "Return to finance menu"));
        backMeta.setLore(backLore);
        backBtn.setItemMeta(backMeta);
        historyInv.setItem(49, backBtn);

        player.openInventory(historyInv);
        lastOpenedMenu.put(player.getUniqueId(), "history");
        lastTarget.put(player.getUniqueId(), result.name);
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

    private List<String> getPlayerHistory(String uuid) {
        List<String> history = transactionHistory.getOrDefault(uuid, new ArrayList<>());
        if (history.isEmpty() && plugin.getServer().getPluginManager().getPlugin("Essentials") != null) {
            history.add("Placeholder: " + plugin.getMessage("action.give-transaction", "Gave $%amount% by %player% %date%", "amount", "100", "player", "System", "date", "2025-09-07"));
            history.add("Placeholder: " + plugin.getMessage("action.take-transaction", "Took $%amount% by %player% %date%", "amount", "50", "player", "System", "date", "2025-09-06"));
        }
        return history;
    }

    private void logTransaction(String uuid, String action, double amount, Player player) {
        List<String> history = transactionHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
        String actionKey;
        switch (action) {
            case "set":
                actionKey = "action.set-transaction";
                break;
            case "give":
                actionKey = "action.give-transaction";
                break;
            case "take":
                actionKey = "action.take-transaction";
                break;
            default:
                actionKey = "action.unknown-transaction";
                break;
        }
        String entry = plugin.getMessage(actionKey, "%action%: $%amount% by %player% %date%",
                "action", action, "amount", String.valueOf(amount), "player", player.getName(), "date", new Date().toString());
        history.add(0, entry);
        if (history.size() > 50) history.remove(history.size() - 1);
    }

    private List<PlayerResult> getFilteredPlayers() {
        List<PlayerResult> results = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() == null) continue;
            boolean matchesSearch = currentSearch.isEmpty() || player.getName().toLowerCase().contains(currentSearch.toLowerCase());
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
        return cachedResults.stream()
                .filter(r -> r.name.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
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
        if (!(event.getInventory().getHolder() instanceof EconomySearchGUI)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        UUID playerUUID = player.getUniqueId();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String menu = lastOpenedMenu.getOrDefault(playerUUID, "main");
        String displayName = clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "";
        long now = System.currentTimeMillis();
        int slot = event.getSlot();

        if (menu.equals("main")) {
            if (slot == 45 || slot == 53) {
                // Page navigation
                if (now - lastPageSwitch.getOrDefault(playerUUID, 0L) < PAGE_SWITCH_COOLDOWN) {
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
                    int totalPages = (cachedResults.size() / 36) + (cachedResults.size() % 36 > 0 ? 1 : 0);
                    if (event.getClick() == ClickType.SHIFT_RIGHT) {
                        currentPage = Math.min(totalPages - 1, currentPage + 5);
                    } else {
                        currentPage++;
                    }
                    lastPage.put(playerUUID, currentPage);
                    refreshGUI();
                }
            } else if (slot == 46) {
                // Select All button
                if (!plugin.isPlayerSelectionEnabled()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-selection-disabled", "Player selection is disabled in config!"));
                    return;
                }
                for (PlayerResult result : cachedResults.subList(currentPage * 36, Math.min((currentPage + 1) * 36, cachedResults.size()))) {
                    UUID uuid = UUID.fromString(result.uuid);
                    selectedPlayers.add(uuid);
                    playerSelections.computeIfAbsent(playerUUID, k -> new HashSet<>()).add(uuid);
                }
                refreshGUI();
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.selection-updated", "Selected all players on page."));
            } else if (slot == 47) {
                // Cancel Selection button
                if (!plugin.isPlayerSelectionEnabled()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-selection-disabled", "Player selection is disabled in config!"));
                    return;
                }
                Set<UUID> selections = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
                selectedPlayers.removeAll(selections);
                playerSelections.remove(playerUUID);
                refreshGUI();
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.selection-cleared", "Selection cleared."));
            } else if (slot == 48) {
                // Mass Operations menu
                if (!plugin.isMassOperationsEnabled()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.mass-operations-disabled", "Mass operations are disabled in config!"));
                    return;
                }
                Set<UUID> selected = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
                if (selected.isEmpty()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players-selected", "No players selected."));
                    return;
                }
                openMassActionsMenu(player);
                lastOpenedMenu.put(playerUUID, "mass");
            } else if (slot == 4) {
                // Search bar
                if (event.getClick() == ClickType.RIGHT) {
                    resetSearch(player);
                } else if (event.getClick() == ClickType.LEFT) {
                    player.closeInventory();
                    TextComponent message = new TextComponent(ChatColor.YELLOW + plugin.getMessage("gui.enter-search", "Enter name to search: "));
                    TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
                    cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
                    message.addExtra(cancel);
                    player.spigot().sendMessage(message);
                    pendingActions.put(playerUUID, (msg, p) -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            String input = ChatColor.stripColor(msg.trim());
                            if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) {
                                resetSearch(p);
                                pendingActions.remove(p.getUniqueId());
                                return;
                            }
                            currentSearch = input;
                            currentPage = 0;
                            lastPage.put(p.getUniqueId(), 0);
                            cachedResults.clear();
                            cachedResults = getFilteredPlayers();
                            setupMainMenuLayout();
                            p.openInventory(inventory);
                            playersInGUI.add(p.getUniqueId());
                            lastOpenedMenu.put(p.getUniqueId(), "main");
                            p.updateInventory();
                            pendingActions.remove(p.getUniqueId());
                        });
                    });
                }
            } else if (slot == 49) {
                applyFilter(Filter.ALL, player);
            } else if (slot == 50) {
                applyFilter(Filter.ONLINE, player);
            } else if (slot == 51) {
                applyFilter(Filter.OFFLINE, player);
            } else if (slot >= 9 && slot <= 44) {
                // Player head click
                String name = ChatColor.stripColor(displayName);
                PlayerResult result = cachedResults.stream()
                        .filter(r -> r.name.equalsIgnoreCase(name))
                        .findFirst()
                        .orElse(null);
                if (result == null) return;
                if (event.getClick() == ClickType.LEFT) {
                    if (!plugin.isFullManagementEnabled()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled", "Full management is disabled in config!"));
                        return;
                    }
                    openPlayerFinanceManagement(player, result);
                    lastOpenedMenu.put(playerUUID, "finance");
                    lastTarget.put(playerUUID, result.name);
                } else if (event.getClick() == ClickType.RIGHT) {
                    if (!plugin.isQuickActionsEnabled()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.quick-actions-disabled", "Quick actions are disabled in config!"));
                        return;
                    }
                    openContextMenu(player, name);
                    lastOpenedMenu.put(playerUUID, "context");
                    lastTarget.put(playerUUID, result.name);
                } else if (event.getClick() == ClickType.SHIFT_LEFT && now - lastPageSwitch.getOrDefault(playerUUID, 0L) >= PAGE_SWITCH_COOLDOWN) {
                    if (!plugin.isPlayerSelectionEnabled()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.player-selection-disabled", "Player selection is disabled in config!"));
                        return;
                    }
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
                    player.sendMessage(ChatColor.YELLOW + plugin.getMessage("action.selection-updated", "Selection updated."));
                }
            }
        } else if (menu.equals("finance")) {
            if (!plugin.isFullManagementEnabled()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled", "Full management is disabled in config!"));
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            String targetName = lastTarget.get(playerUUID);
            PlayerResult result = getPlayerResultByName(targetName);
            if (result == null) {
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            if (slot == 13) {
                // Give (chat input)
                if (!player.hasPermission("economygui.give")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission to give."));
                    openPlayerFinanceManagement(player, result);
                    return;
                }
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
                            if (amount <= 0) {
                                p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                                openPlayerFinanceManagement(p, result);
                                return;
                            }
                            OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                            if (plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                                p.sendMessage(ChatColor.GREEN + plugin.getMessage("action.executed",
                                        "Executed %action% of $%amount% for %player%", "action", "give", "amount", String.format(moneyFormat, amount), "player", result.name));
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
            } else if (slot == 20) {
                // Give (digital menu)
                openDigitalMenu(player, plugin.getMessage("action.give", "Give"), result);
                lastOpenedMenu.put(playerUUID, "digital");
                pendingActionType.put(playerUUID, plugin.getMessage("action.give", "Give"));
                pendingActionTarget.put(playerUUID, result);
            } else if (slot == 22) {
                // Take (digital menu)
                openDigitalMenu(player, plugin.getMessage("action.take", "Take"), result);
                lastOpenedMenu.put(playerUUID, "digital");
                pendingActionType.put(playerUUID, plugin.getMessage("action.take", "Take"));
                pendingActionTarget.put(playerUUID, result);
            } else if (slot == 24) {
                // Set (chat input)
                if (!player.hasPermission("economygui.set")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission to set."));
                    openPlayerFinanceManagement(player, result);
                    return;
                }
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
                                p.sendMessage(ChatColor.GREEN + plugin.getMessage("action.executed",
                                        "Executed %action% of $%amount% for %player%", "action", "set", "amount", String.format(moneyFormat, amount), "player", result.name));
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
                // History menu
                openHistoryMenu(player, result);
                lastOpenedMenu.put(playerUUID, "history");
                lastTarget.put(playerUUID, result.name);
            } else if (slot == 49) {
                // Back to main menu
                openMainGUI(player);
                lastOpenedMenu.put(playerUUID, "main");
            }
        } else if (menu.equals("context")) {
            if (!plugin.isQuickActionsEnabled()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.quick-actions-disabled", "Quick actions are disabled in config!"));
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            String targetName = lastTarget.get(playerUUID);
            PlayerResult result = getPlayerResultByName(targetName);
            if (result == null) {
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            if (slot == 3) {
                // Quick give
                requestAmount(player, "give", targetName);
            } else if (slot == 5) {
                // Quick take
                requestAmount(player, "take", targetName);
            } else if (slot == 8) {
                // Back to main menu
                openMainGUI(player);
                lastOpenedMenu.put(playerUUID, "main");
            }
        } else if (menu.equals("digital")) {
            if (!plugin.isFullManagementEnabled()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled", "Full management is disabled in config!"));
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            PlayerResult result = pendingActionTarget.get(playerUUID);
            if (result == null) {
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            if (slot == 16) {
                // Confirm action
                double amount = pendingActionAmount.getOrDefault(playerUUID, 0.0);
                String action = pendingActionType.getOrDefault(playerUUID, plugin.getMessage("action.give", "Give"));
                if (amount <= 0) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                boolean success = false;
                if (action.equals(plugin.getMessage("action.give", "Give"))) {
                    if (!player.hasPermission("economygui.give")) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission to give."));
                        return;
                    }
                    success = plugin.getEconomy().depositPlayer(target, amount).transactionSuccess();
                    if (success) logTransaction(result.uuid, "give", amount, player);
                } else if (action.equals(plugin.getMessage("action.take", "Take"))) {
                    if (!player.hasPermission("economygui.take")) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission to take."));
                        return;
                    }
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
                openPlayerFinanceManagement(player, result);
                lastOpenedMenu.put(playerUUID, "finance");
            } else if (slot == 18) {
                // Reset amount
                pendingActionAmount.put(playerUUID, 0.0);
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage("gui.amount-selected",
                        "Selected amount: %amount%", "amount", "0"));
                openDigitalMenu(player, pendingActionType.get(playerUUID), result);
            } else if (slot == 26) {
                // Back to finance menu
                openPlayerFinanceManagement(player, result);
                lastOpenedMenu.put(playerUUID, "finance");
            } else if (slot >= 9 && slot <= 14) {
                // Predefined amounts
                int[] amounts = {1, 5, 10, 100, 1000, 5000};
                int index = slot - 9;
                if (index >= 0 && index < amounts.length) {
                    double currentAmount = pendingActionAmount.getOrDefault(playerUUID, 0.0);
                    pendingActionAmount.put(playerUUID, currentAmount + amounts[index]);
                    player.sendMessage(ChatColor.GREEN + plugin.getMessage("gui.amount-selected",
                            "Selected amount: %amount%", "amount", String.valueOf(currentAmount + amounts[index])));
                    openDigitalMenu(player, pendingActionType.get(playerUUID), result);
                }
            } else if (slot == 22) {
                // Custom amount
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
                                    "Selected amount: %amount%", "amount", String.valueOf(currentAmount + amount)));
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
            if (slot == 49) {
                // Back to finance menu
                PlayerResult result = getPlayerResultByName(lastTarget.get(playerUUID));
                if (result != null) {
                    if (!plugin.isFullManagementEnabled()) {
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled", "Full management is disabled in config!"));
                        openMainGUI(player);
                        lastOpenedMenu.put(playerUUID, "main");
                        return;
                    }
                    openPlayerFinanceManagement(player, result);
                    lastOpenedMenu.put(playerUUID, "finance");
                }
            }
        } else if (menu.equals("mass")) {
            if (!plugin.isMassOperationsEnabled()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.mass-operations-disabled", "Mass operations are disabled in config!"));
                player.closeInventory();
                openMainGUI(player);
                return;
            }
            if (slot == 11) {
                // Mass give
                requestMassAmount(player, "mass-give");
            } else if (slot == 13) {
                // Mass take
                requestMassAmount(player, "mass-take");
            } else if (slot == 15) {
                // Mass set
                requestMassAmount(player, "mass-set");
            } else if (slot == 22) {
                // Back to main menu
                openMainGUI(player);
                lastOpenedMenu.put(playerUUID, "main");
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

    public void openLastGUIMenu(Player player) {
        String lastMenu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "main");
        currentPage = lastPage.getOrDefault(player.getUniqueId(), 0);
        currentSearch = lastTarget.getOrDefault(player.getUniqueId(), "");
        playersInGUI.add(player.getUniqueId());
        if (lastMenu.equals("main")) {
            openMainGUI(player);
        } else if (lastMenu.equals("finance")) {
            String targetName = lastTarget.get(player.getUniqueId());
            PlayerResult result = getPlayerResultByName(targetName);
            if (result != null) openPlayerFinanceManagement(player, result);
        } else if (lastMenu.equals("context")) {
            openContextMenu(player, lastTarget.get(player.getUniqueId()));
        } else if (lastMenu.equals("digital")) {
            PlayerResult result = pendingActionTarget.get(player.getUniqueId());
            String action = pendingActionType.get(player.getUniqueId());
            if (result != null && action != null) openDigitalMenu(player, action, result);
        } else if (lastMenu.equals("history")) {
            String targetName = lastTarget.get(player.getUniqueId());
            PlayerResult result = getPlayerResultByName(targetName);
            if (result != null) openHistoryMenu(player, result);
        } else if (lastMenu.equals("mass")) {
            openMassActionsMenu(player);
        }
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