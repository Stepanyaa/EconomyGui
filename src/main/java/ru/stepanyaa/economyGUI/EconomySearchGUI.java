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

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EconomySearchGUI implements Listener, InventoryHolder {

    static class PlayerResult {
        final String uuid;
        final String name;
        final boolean online;
        final double balance;

        PlayerResult(String uuid, String name, boolean online, double balance) {
            this.uuid    = uuid;
            this.name    = name;
            this.online  = online;
            this.balance = balance;
        }
    }

    public enum Filter { ALL, ONLINE, OFFLINE, SELECTED }

    @FunctionalInterface
    interface ChatAction {
        void execute(String message, Player player);
    }

    private final EconomyGUI plugin;
    private final TransactionHandler transactionHandler;
    private final PlayerCache playerCache;
    private final ItemFactory itemFactory;

    private Inventory inventory;
    private int currentPage = 0;
    private String currentSearch = "";
    private Filter currentFilter = Filter.ALL;

    private final Set<UUID> selectedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> playerSelections = new ConcurrentHashMap<>();

    private final Map<UUID, String> lastOpenedMenu = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastPage       = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastTarget      = new ConcurrentHashMap<>();
    private final Set<UUID> playersInGUI            = ConcurrentHashMap.newKeySet();

    private final Map<UUID, ChatAction> pendingActions     = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingActionType      = new ConcurrentHashMap<>();
    private final Map<UUID, Double> pendingActionAmount    = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerResult> pendingActionTarget = new ConcurrentHashMap<>();

    private List<PlayerResult> cachedResults = new ArrayList<>();

    private static final long PAGE_SWITCH_COOLDOWN = 250L;
    private final Map<UUID, Long> lastPageSwitch  = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFilterSwitch = new ConcurrentHashMap<>();

    final Map<UUID, Double> lastKnownBalances = new ConcurrentHashMap<>();

    private final String moneyFormat = "%,.2f";

    public EconomySearchGUI(EconomyGUI plugin) {
        this.plugin             = plugin;
        this.transactionHandler = new TransactionHandler(plugin);
        this.playerCache        = new PlayerCache(plugin);
        this.itemFactory        = new ItemFactory(plugin);
        this.inventory = Bukkit.createInventory(this, 54,
                ChatColor.DARK_PURPLE + plugin.getMessage("gui.title", "Economy Management"));
        refreshGUI();
    }

    public TransactionHandler getTransactionHandler() { return transactionHandler; }
    public PlayerCache getPlayerCache()               { return playerCache; }
    public Map<UUID, Double> getLastKnownBalances()   { return lastKnownBalances; }

    @Override
    public Inventory getInventory() { return inventory; }

    public void openMainGUI(Player player) {
        currentPage = lastPage.getOrDefault(player.getUniqueId(), 0);
        refreshGUI();
        player.openInventory(inventory);
        lastOpenedMenu.put(player.getUniqueId(), "main");
        playersInGUI.add(player.getUniqueId());
    }

    public void openLastGUIMenu(Player player) {
        String menu = lastOpenedMenu.getOrDefault(player.getUniqueId(), "main");
        currentPage = lastPage.getOrDefault(player.getUniqueId(), 0);
        playersInGUI.add(player.getUniqueId());
        switch (menu) {
            case "finance": {
                PlayerResult r = getResultByName(lastTarget.getOrDefault(player.getUniqueId(), ""));
                if (r != null) openFinanceMenu(player, r); else openMainGUI(player);
                break;
            }
            case "context": {
                String t = lastTarget.getOrDefault(player.getUniqueId(), "");
                if (!t.isEmpty()) openContextMenu(player, t); else openMainGUI(player);
                break;
            }
            case "digital": {
                PlayerResult r = pendingActionTarget.get(player.getUniqueId());
                String action  = pendingActionType.get(player.getUniqueId());
                if (r != null && action != null) openDigitalMenu(player, action, r);
                else openMainGUI(player);
                break;
            }
            case "history": {
                PlayerResult r = getResultByName(lastTarget.getOrDefault(player.getUniqueId(), ""));
                if (r != null) openHistoryMenu(player, r); else openMainGUI(player);
                break;
            }
            case "mass":
                openMassActionsMenu(player);
                break;
            default:
                openMainGUI(player);
        }
    }

    public void resetSearch(Player player) {
        UUID id = player.getUniqueId();
        currentSearch = "";
        currentFilter = Filter.ALL;
        currentPage   = 0;
        selectedPlayers.removeAll(playerSelections.getOrDefault(id, Collections.emptySet()));
        playerSelections.remove(id);
        lastPage.put(id, 0);
        lastTarget.remove(id);
        lastOpenedMenu.put(id, "main");
        player.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.search-reset", "Search and filters reset."));
        openMainGUI(player);
    }

    public void refreshOpenGUIs() {
        for (UUID uuid : playersInGUI) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) openLastGUIMenu(p);
        }
    }

    private void refreshGUI() {
        setupMainMenuLayout();
    }

    private void setupMainMenuLayout() {
        inventory.clear();

        String searchLabel = currentSearch.isEmpty()
                ? plugin.getMessage("gui.search-all", "all")
                : currentSearch;
        List<String> searchLore = Collections.singletonList(
                ChatColor.GRAY + plugin.getMessage("gui.search-hint",
                        "LMB: Enter query | RMB: Reset search | Shift+Left: Select/Deselect"));
        inventory.setItem(4, itemFactory.simple(Material.COMPASS,
                ChatColor.YELLOW + plugin.getMessage("gui.search", "Search: %search_query%",
                        "search_query", searchLabel),
                searchLore));

        inventory.setItem(49, createFilterItem());
        inventory.setItem(50, createGlobalStatsButton());

        if (plugin.isMassOperationsEnabled()) {
            inventory.setItem(48, itemFactory.button(Material.DIAMOND_BLOCK,
                    ChatColor.AQUA, "gui.mass-menu-title", "Mass Operations",
                    "gui.mass-hint", "Click for mass give/take/set (select players first)"));
        }

        cachedResults = playerCache.getFiltered(currentSearch, currentFilter, selectedPlayers);
        int totalPages = totalPages();
        inventory.setItem(45, itemFactory.pageButton(currentPage > 0, false, currentPage, totalPages));
        inventory.setItem(53, itemFactory.pageButton(currentPage < totalPages - 1, true, currentPage, totalPages));

        if (plugin.isPlayerSelectionEnabled()) {
            inventory.setItem(46, itemFactory.button(Material.EMERALD_BLOCK,
                    ChatColor.GREEN, "gui.select-all", "Select All",
                    "gui.click-to-select", "Click to select all on page"));
            inventory.setItem(47, itemFactory.button(Material.REDSTONE_BLOCK,
                    ChatColor.RED, "gui.cancel-selection", "Cancel Selection",
                    "gui.click-to-cancel", "Click to cancel all selections"));
        }

        int start = currentPage * 36;
        int end   = Math.min(start + 36, cachedResults.size());
        for (int i = start; i < end; i++) {
            PlayerResult r    = cachedResults.get(i);
            OfflinePlayer op  = Bukkit.getOfflinePlayer(UUID.fromString(r.uuid));
            boolean selected  = selectedPlayers.contains(UUID.fromString(r.uuid));
            inventory.setItem(9 + (i - start), itemFactory.playerHead(op, r.balance, selected, moneyFormat));
        }

        if (cachedResults.isEmpty()) {
            List<String> noLore = Collections.singletonList(
                    ChatColor.GRAY + plugin.getMessage("gui.no-players-hint", "Try changing the search or filter"));
            inventory.setItem(22, itemFactory.simple(Material.BARRIER,
                    ChatColor.RED + plugin.getMessage("gui.no-players", "No players found"), noLore));
        }
    }

    private void openFinanceMenu(Player player, PlayerResult result) {
        if (!plugin.isFullManagementEnabled()) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled",
                    "Full management is disabled in config!"));
            openMainGUI(player);
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        double freshBalance = plugin.getEconomy().getBalance(target);

        Inventory inv = Bukkit.createInventory(this, 54,
                ChatColor.DARK_PURPLE + plugin.getMessage("gui.title", "Economy Management") + ": " + result.name);

        inv.setItem(4, itemFactory.playerHead(target, freshBalance, false, moneyFormat));

        inv.setItem(13, itemFactory.button(Material.WRITABLE_BOOK,
                ChatColor.YELLOW, "gui.enter-amount", "Enter Amount",
                "gui.enter-amount-hint", "Left click to enter amount in chat"));

        inv.setItem(20, itemFactory.button(Material.EMERALD,
                ChatColor.GREEN, "action.give", "Give Amount",
                "gui.give-hint", "Click to open digital menu"));

        inv.setItem(22, itemFactory.button(Material.REDSTONE,
                ChatColor.RED, "action.take", "Take Amount",
                "gui.take-hint", "Click to open digital menu"));

        inv.setItem(24, itemFactory.button(Material.GOLD_INGOT,
                ChatColor.YELLOW, "action.set", "Set Amount",
                "gui.set-hint", "Click to enter amount in chat"));

        inv.setItem(31, itemFactory.button(Material.BOOK,
                ChatColor.BLUE, "history.operations", "Operations History",
                "history.hint", "Click to view logs"));

        inv.setItem(49, itemFactory.backButton("gui.back-hint", "Return to main menu"));

        player.openInventory(inv);
        lastOpenedMenu.put(player.getUniqueId(), "finance");
        lastTarget.put(player.getUniqueId(), result.name);
    }

    private void openContextMenu(Player player, String targetName) {
        PlayerResult result = getResultByName(targetName);
        if (result == null) { openMainGUI(player); return; }

        Inventory inv = Bukkit.createInventory(this, 27,
                ChatColor.BLUE + plugin.getMessage("context-menu.title",
                        "Quick Actions for %player%", "player", targetName));

        inv.setItem(10, quickActionItem("Give $100",   "give", 100,  result));
        inv.setItem(11, quickActionItem("Give $1000",  "give", 1000, result));
        inv.setItem(12, quickActionItem("Take $100",   "take", 100,  result));
        inv.setItem(13, quickActionItem("Take $1000",  "take", 1000, result));
        inv.setItem(14, quickActionItem("Set $5000",   "set",  5000, result));
        inv.setItem(15, quickActionItem("Reset to $0", "set",  0,    result));
        inv.setItem(16, quickActionItem("Custom Amount", "custom", 0, result));

        if (plugin.isFullManagementEnabled()) {
            inv.setItem(4, itemFactory.button(Material.BOOK,
                    ChatColor.GREEN, "context-menu.full-open", "Open Full Management",
                    "gui.actions-lmb", "Left click: Open Management Menu"));
        }
        inv.setItem(22, itemFactory.simple(Material.BARRIER,
                ChatColor.RED + plugin.getMessage("context-menu.back", "Back to Main Menu"),
                Collections.emptyList()));

        player.openInventory(inv);
        lastOpenedMenu.put(player.getUniqueId(), "context");
        lastTarget.put(player.getUniqueId(), targetName);
    }

    private ItemStack quickActionItem(String label, String action, double amount, PlayerResult result) {
        Material mat = action.equals("give") ? Material.EMERALD
                : action.equals("take") ? Material.REDSTONE : Material.GOLD_INGOT;
        List<String> lore = Collections.singletonList(
                ChatColor.GRAY + "Click to apply to " + result.name);
        return itemFactory.simple(mat, ChatColor.YELLOW + label, lore);
    }

    private void openDigitalMenu(Player player, String action, PlayerResult result) {
        double totalAmount = pendingActionAmount.getOrDefault(player.getUniqueId(), 0.0);

        Inventory inv = Bukkit.createInventory(this, 27,
                ChatColor.DARK_PURPLE + plugin.getMessage("gui.digital-menu", "%action% Menu: %player%",
                        "action", action, "player", result.name));

        inv.setItem(4, itemFactory.playerHead(Bukkit.getOfflinePlayer(UUID.fromString(result.uuid)),
                result.balance, false, moneyFormat));

        int[] amounts = {1, 5, 10, 100, 1000, 5000};
        for (int i = 0; i < amounts.length; i++) {
            List<String> lore = Arrays.asList(
                    ChatColor.GRAY + plugin.getMessage("gui.add-amount-hint",
                            "Click to add %amount% to total", "amount", String.valueOf(amounts[i])),
                    ChatColor.GRAY + plugin.getMessage("gui.current-amount",
                            "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
            inv.setItem(9 + i, itemFactory.simple(Material.PAPER,
                    ChatColor.YELLOW + String.valueOf(amounts[i]), lore));
        }

        List<String> customLore = Arrays.asList(
                ChatColor.GRAY + plugin.getMessage("gui.custom-amount-hint",
                        "Left click to enter custom amount in chat"),
                ChatColor.GRAY + plugin.getMessage("gui.current-amount",
                        "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
        inv.setItem(22, itemFactory.simple(Material.WRITABLE_BOOK,
                ChatColor.BLUE + plugin.getMessage("gui.custom-amount", "Custom Amount"), customLore));

        List<String> resetLore = Arrays.asList(
                ChatColor.GRAY + plugin.getMessage("gui.reset-amount-hint", "Reset the selected amount to 0"),
                ChatColor.GRAY + plugin.getMessage("gui.current-amount",
                        "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
        inv.setItem(18, itemFactory.simple(Material.REDSTONE_BLOCK,
                ChatColor.RED + plugin.getMessage("gui.reset-amount", "Reset Amount"), resetLore));

        List<String> confirmLore = Collections.singletonList(
                ChatColor.GRAY + plugin.getMessage("gui.confirm-hint",
                        "Execute: %action% $%amount%", "action", action,
                        "amount", String.format(moneyFormat, totalAmount)));
        inv.setItem(16, itemFactory.simple(Material.EMERALD_BLOCK,
                ChatColor.GREEN + plugin.getMessage("gui.confirm", "Confirm"), confirmLore));

        inv.setItem(26, itemFactory.backButton("gui.back-hint-finance", "Return to finance menu"));

        player.openInventory(inv);
        pendingActionType.put(player.getUniqueId(), action);
        pendingActionTarget.put(player.getUniqueId(), result);
        lastOpenedMenu.put(player.getUniqueId(), "digital");
    }

    private void openHistoryMenu(Player player, PlayerResult result) {
        List<TransactionHandler.Transaction> history = transactionHandler.getHistory(result.uuid);

        Inventory inv = Bukkit.createInventory(this, 54,
                ChatColor.GOLD + plugin.getMessage("gui.history", "Operations History") + ": " + result.name);

        lastOpenedMenu.put(player.getUniqueId(), "history");
        lastTarget.put(player.getUniqueId(), result.name);

        int page  = lastPage.getOrDefault(player.getUniqueId(), 0);
        int start = page * 45;
        int end   = Math.min(start + 45, history.size());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = start; i < end; i++) {
            TransactionHandler.Transaction t = history.get(i);
            List<String> lore = Arrays.asList(
                    ChatColor.GRAY + "By: " + t.executor,
                    ChatColor.GRAY + "Date: " + sdf.format(new Date(t.timestamp)));
            ItemStack item = itemFactory.simple(Material.PAPER,
                    ChatColor.YELLOW + ChatColor.stripColor(t.description).toUpperCase()
                            + " $" + String.format(moneyFormat, t.amount),
                    lore);
            inv.setItem(i - start, item);
        }

        if (history.isEmpty()) {
            inv.setItem(22, itemFactory.simple(Material.BARRIER,
                    ChatColor.RED + plugin.getMessage("gui.no-players", "No transactions found"),
                    Collections.emptyList()));
        }

        int totalPages = (history.size() / 45) + (history.size() % 45 > 0 ? 1 : 0);
        if (totalPages == 0) totalPages = 1;
        inv.setItem(45, itemFactory.pageButton(page > 0, false, page, totalPages));
        inv.setItem(53, itemFactory.pageButton(page < totalPages - 1, true, page, totalPages));
        inv.setItem(49, itemFactory.backButton("gui.back-hint", "Return to finance menu"));

        player.openInventory(inv);
    }


    private void openMassActionsMenu(Player player) {
        Inventory inv = Bukkit.createInventory(this, 27,
                ChatColor.DARK_PURPLE + plugin.getMessage("gui.mass-menu-title", "Mass Operations"));

        inv.setItem(11, itemFactory.button(Material.EMERALD_BLOCK,
                ChatColor.GREEN, "gui.mass-give", "Give Amount to All Selected",
                "gui.mass-give-hint", "Click to enter amount for all"));
        inv.setItem(13, itemFactory.button(Material.REDSTONE_BLOCK,
                ChatColor.RED, "gui.mass-take", "Take Amount from All Selected",
                "gui.mass-take-hint", "Click to enter amount for all"));
        inv.setItem(15, itemFactory.button(Material.GOLD_BLOCK,
                ChatColor.YELLOW, "gui.mass-set", "Set Balance for All Selected",
                "gui.mass-set-hint", "Click to enter amount for all"));
        inv.setItem(22, itemFactory.backButton("gui.click-to-return", "Return to Main Menu"));

        player.openInventory(inv);
        lastOpenedMenu.put(player.getUniqueId(), "mass");
    }

    private void openGlobalStatsMenu(Player player) {
        player.sendMessage(ChatColor.YELLOW + plugin.getMessage("gui.stats-loading", "Loading economy statistics..."));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PlayerResult> all = playerCache.getAll();
            if (all.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players", "No players found.")));
                return;
            }

            double totalBalance = 0, onlineBalance = 0;
            int onlineCount = 0;
            PlayerResult richest = null, poorest = null;

            for (PlayerResult r : all) {
                totalBalance += r.balance;
                if (r.online) { onlineCount++; onlineBalance += r.balance; }
                if (richest == null || r.balance > richest.balance) richest = r;
                if (poorest == null || r.balance < poorest.balance) poorest = r;
            }
            int playerCount = all.size();
            double avg    = playerCount > 0 ? totalBalance / playerCount : 0;
            double avgOnl = onlineCount  > 0 ? onlineBalance / onlineCount : 0;

            List<PlayerResult> topRich = all.stream()
                    .sorted(Comparator.comparingDouble((PlayerResult r) -> r.balance).reversed())
                    .limit(5).collect(Collectors.toList());
            List<PlayerResult> topPoor = all.stream()
                    .sorted(Comparator.comparingDouble((PlayerResult r) -> r.balance))
                    .limit(5).collect(Collectors.toList());

            final int fCount = playerCount; final int fOnline = onlineCount;
            final double fTotal = totalBalance; final double fOnlineB = onlineBalance;
            final double fAvg = avg; final double fAvgOnl = avgOnl;
            final PlayerResult fRichest = richest; final PlayerResult fPoorest = poorest;

            Bukkit.getScheduler().runTask(plugin, () -> {
                Inventory statsInv = Bukkit.createInventory(new StatsMenuHolder(null), 54,
                        ChatColor.DARK_PURPLE + plugin.getMessage("gui.stats-title", "Economy Statistics"));

                List<String> overviewLore = new ArrayList<>();
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-total-players", "Total players: ")   + ChatColor.WHITE + fCount);
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-online-players", "Online players: ") + ChatColor.GREEN + fOnline);
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-total-balance", "Total balance: ")   + ChatColor.GREEN + String.format(moneyFormat, fTotal));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-online-balance", "Online balance: ") + ChatColor.GREEN + String.format(moneyFormat, fOnlineB));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-average-balance", "Average balance: ") + ChatColor.GREEN + String.format(moneyFormat, fAvg));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-average-online", "Avg online balance: ") + ChatColor.GREEN + String.format(moneyFormat, fAvgOnl));
                overviewLore.add("");
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-richest", "Richest: ")
                        + ChatColor.GOLD + (fRichest != null ? fRichest.name + " (" + String.format(moneyFormat, fRichest.balance) + ")" : "—"));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage("gui.stats-poorest", "Poorest: ")
                        + ChatColor.RED  + (fPoorest != null ? fPoorest.name + " (" + String.format(moneyFormat, fPoorest.balance) + ")" : "—"));
                statsInv.setItem(11, itemFactory.simple(Material.BOOK,
                        ChatColor.YELLOW + plugin.getMessage("gui.stats-overview", "Server Economy Overview"), overviewLore));

                List<String> richLore = new ArrayList<>();
                for (int i = 0; i < topRich.size(); i++) {
                    PlayerResult r = topRich.get(i);
                    richLore.add(ChatColor.GRAY.toString() + (i + 1) + ". " + ChatColor.WHITE + r.name
                            + ChatColor.GRAY + " — " + ChatColor.GREEN + String.format(moneyFormat, r.balance));
                }
                statsInv.setItem(13, itemFactory.simple(Material.DIAMOND,
                        ChatColor.GOLD + plugin.getMessage("gui.stats-top-rich", "Top 5 Richest"), richLore));
                List<String> poorLore = new ArrayList<>();
                for (int i = 0; i < topPoor.size(); i++) {
                    PlayerResult r = topPoor.get(i);
                    poorLore.add(ChatColor.GRAY.toString() + (i + 1) + ". " + ChatColor.WHITE + r.name
                            + ChatColor.GRAY + " — " + ChatColor.RED + String.format(moneyFormat, r.balance));
                }
                statsInv.setItem(15, itemFactory.simple(Material.IRON_INGOT,
                        ChatColor.RED + plugin.getMessage("gui.stats-top-poor", "Top 5 Poorest"), poorLore));

                statsInv.setItem(49, itemFactory.backButton("gui.click-to-return", "Return to main menu"));
                player.openInventory(statsInv);
            });
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                lastKnownBalances.put(p.getUniqueId(), plugin.getEconomy().getBalance(p)), 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastKnownBalances.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EconomySearchGUI
                || event.getInventory().getHolder() instanceof StatsMenuHolder) {
            playersInGUI.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!pendingActions.containsKey(player.getUniqueId())) return;

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

            String actionType = pendingActionType.get(player.getUniqueId());
            if (actionType != null && actionType.startsWith("mass-")) {
                handleMassChatInput(player, actionType, message);
                pendingActions.remove(player.getUniqueId());
                pendingActionType.remove(player.getUniqueId());
                return;
            }

            action.execute(message, player);
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EconomySearchGUI || holder instanceof StatsMenuHolder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player    = (Player) event.getWhoClicked();
        UUID playerUUID  = player.getUniqueId();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        long now = System.currentTimeMillis();

        if (holder instanceof StatsMenuHolder) {
            if (event.getSlot() == 49) {
                playSound(player);
                player.closeInventory();
                openLastGUIMenu(player);
            }
            return;
        }

        String menu = lastOpenedMenu.getOrDefault(playerUUID, "main");
        switch (menu) {
            case "main":    handleMainClick(event, player, playerUUID, now, clicked);    break;
            case "finance": handleFinanceClick(event, player, playerUUID);       break;
            case "context": handleContextClick(event, player, playerUUID);       break;
            case "digital": handleDigitalClick(event, player, playerUUID);       break;
            case "history": handleHistoryClick(event, player, playerUUID);       break;
            case "mass":    handleMassClick(event, player, playerUUID);          break;
        }
    }

    private void handleMainClick(InventoryClickEvent event, Player player, UUID playerUUID, long now, ItemStack clicked) {
        int slot = event.getSlot();

        if (slot == 45 || slot == 53) {
            if (now - lastPageSwitch.getOrDefault(playerUUID, 0L) < PAGE_SWITCH_COOLDOWN) return;
            lastPageSwitch.put(playerUUID, now);
            int totalPages = totalPages();
            if (slot == 45 && currentPage > 0) {
                currentPage = event.getClick() == ClickType.SHIFT_RIGHT
                        ? Math.max(0, currentPage - 5) : currentPage - 1;
            } else if (slot == 53 && currentPage < totalPages - 1) {
                currentPage = event.getClick() == ClickType.SHIFT_RIGHT
                        ? Math.min(totalPages - 1, currentPage + 5) : currentPage + 1;
            }
            lastPage.put(playerUUID, currentPage);
            refreshGUI();
            return;
        }

        if (slot == 46) {
            int start = currentPage * 36;
            int end   = Math.min(start + 36, cachedResults.size());
            for (int i = start; i < end; i++) {
                UUID uuid = UUID.fromString(cachedResults.get(i).uuid);
                selectedPlayers.add(uuid);
                playerSelections.computeIfAbsent(playerUUID, k -> new HashSet<>()).add(uuid);
            }
            refreshGUI(); return;
        }

        if (slot == 47) {
            Set<UUID> sel = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
            selectedPlayers.removeAll(sel);
            playerSelections.remove(playerUUID);
            refreshGUI(); return;
        }

        if (slot == 48) {
            if (!plugin.isMassOperationsEnabled()) return;
            if (playerSelections.getOrDefault(playerUUID, Collections.emptySet()).isEmpty()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players-selected",
                        "No players selected for mass operation!"));
                return;
            }
            openMassActionsMenu(player); return;
        }

        if (slot == 49) {
            if (now - lastFilterSwitch.getOrDefault(playerUUID, 0L) < 500) return;
            lastFilterSwitch.put(playerUUID, now);
            Filter[] cycle = {Filter.ALL, Filter.ONLINE, Filter.OFFLINE};
            boolean reverse = event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT;
            int idx = Arrays.asList(cycle).indexOf(currentFilter);
            currentFilter = cycle[(idx + (reverse ? cycle.length - 1 : 1)) % cycle.length];
            refreshGUI();
            playSound(player); return;
        }

        if (slot == 50) {
            player.closeInventory();
            openGlobalStatsMenu(player);
            playSound(player); return;
        }

        if (slot == 4) {
            if (event.getClick() == ClickType.RIGHT) {
                resetSearch(player);
            } else if (event.getClick() == ClickType.LEFT) {
                player.closeInventory();
                sendCancelablePrompt(player, plugin.getMessage("gui.enter-search", "Enter name to search: "));
                pendingActions.put(playerUUID, (msg, p) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    String input = ChatColor.stripColor(msg.trim());
                    if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) {
                        resetSearch(p); return;
                    }
                    currentSearch = input;
                    currentPage = 0;
                    lastPage.put(p.getUniqueId(), 0);
                    cachedResults = playerCache.getFiltered(currentSearch, currentFilter, selectedPlayers);
                    setupMainMenuLayout();
                    p.openInventory(inventory);
                    playersInGUI.add(p.getUniqueId());
                    lastOpenedMenu.put(p.getUniqueId(), "main");
                    pendingActions.remove(p.getUniqueId());
                }));
            }
            return;
        }

        if (slot >= 9 && slot <= 44) {
            String name = ChatColor.stripColor(clicked.getItemMeta() != null
                    ? clicked.getItemMeta().getDisplayName() : "");
            PlayerResult result = cachedResults.stream()
                    .filter(r -> r.name.equalsIgnoreCase(name)).findFirst().orElse(null);
            if (result == null) return;

            if (event.getClick() == ClickType.LEFT) {
                if (!plugin.isFullManagementEnabled()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.full-management-disabled",
                            "Full management is disabled in config!"));
                    return;
                }
                openFinanceMenu(player, result);
            } else if (event.getClick() == ClickType.RIGHT) {
                if (!plugin.isQuickActionsEnabled()) return;
                openContextMenu(player, name);
            } else if (event.getClick() == ClickType.SHIFT_LEFT
                    && now - lastPageSwitch.getOrDefault(playerUUID, 0L) >= PAGE_SWITCH_COOLDOWN) {
                if (!plugin.isPlayerSelectionEnabled()) return;
                lastPageSwitch.put(playerUUID, now);
                UUID uuid = UUID.fromString(result.uuid);
                Set<UUID> sel = playerSelections.computeIfAbsent(playerUUID, k -> new HashSet<>());
                if (selectedPlayers.contains(uuid)) {
                    selectedPlayers.remove(uuid); sel.remove(uuid);
                    player.sendMessage(ChatColor.GRAY + plugin.getMessage("messages.player-deselected",
                            "Player %player% deselected.", "player", result.name));
                } else {
                    selectedPlayers.add(uuid); sel.add(uuid);
                    player.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.player-selected",
                            "Player %player% selected.", "player", result.name));
                }
                refreshGUI();
            }
        }
    }

    private void handleFinanceClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        int slot = event.getSlot();
        PlayerResult result = getResultByName(lastTarget.get(playerUUID));
        if (result == null) { openMainGUI(player); return; }

        switch (slot) {
            case 13:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("messages.no-permission-give",
                            "You don't have permission to give."));
                    return;
                }
                player.closeInventory();
                sendCancelablePrompt(player, plugin.getMessage("gui.enter-amount", "Enter amount in chat:"));
                pendingActions.put(playerUUID, (msg, p) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCancelWord(msg)) {
                        p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.input-cancelled", "Input cancelled."));
                        openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId()); return;
                    }
                    try {
                        double amount = Double.parseDouble(msg.trim());
                        if (!validateAmount(p, amount, false)) { openFinanceMenu(p, result); return; }
                        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                        if (plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                            p.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.gave-amount",
                                    "Gave $%amount% to %player%",
                                    "amount", String.format(moneyFormat, amount), "player", result.name));
                            transactionHandler.log(result.uuid, "give", amount, p);
                        } else {
                            p.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
                        }
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                    }
                    openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId());
                }));
                break;

            case 20:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("messages.no-permission-give",
                            "You don't have permission to give."));
                    return;
                }
                openDigitalMenu(player, plugin.getMessage("action.give", "Give"), result);
                break;

            case 22:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                    return;
                }
                openDigitalMenu(player, plugin.getMessage("action.take", "Take"), result);
                break;

            case 24:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                    return;
                }
                player.closeInventory();
                sendCancelablePrompt(player, plugin.getMessage("gui.enter-amount", "Enter amount in chat:"));
                pendingActions.put(playerUUID, (msg, p) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCancelWord(msg)) {
                        p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.input-cancelled", "Input cancelled."));
                        openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId()); return;
                    }
                    try {
                        double amount = Double.parseDouble(msg.trim());
                        if (!validateAmount(p, amount, true)) { openFinanceMenu(p, result); return; }
                        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                        double current = plugin.getEconomy().getBalance(target);
                        if (plugin.getEconomy().withdrawPlayer(target, current).transactionSuccess()
                                && plugin.getEconomy().depositPlayer(target, amount).transactionSuccess()) {
                            p.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.set-amount",
                                    "Set balance to $%amount% for %player%",
                                    "amount", String.format(moneyFormat, amount), "player", result.name));
                            transactionHandler.log(result.uuid, "set", amount, p);
                        } else {
                            p.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
                        }
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                    }
                    openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId());
                }));
                break;

            case 31:
                openHistoryMenu(player, result); break;

            case 49:
                openMainGUI(player); break;
        }
    }

    private void handleContextClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        String targetName = lastTarget.getOrDefault(playerUUID, "");
        PlayerResult result = getResultByName(targetName);
        if (result == null) { openMainGUI(player); return; }

        int slot = event.getSlot();
        if (slot == 4 && plugin.isFullManagementEnabled()) {
            openFinanceMenu(player, result);
        } else if (slot >= 10 && slot <= 15 && plugin.isQuickActionsEnabled()) {
            if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                return;
            }
            double[] amounts = {100, 1000, 100, 1000, 5000, 0};
            String[] actions = {"give", "give", "take", "take", "set", "set"};
            executeQuickAction(player, result, actions[slot - 10], amounts[slot - 10]);
        } else if (slot == 16 && plugin.isQuickActionsEnabled()) {
            if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
                return;
            }
            requestAmountForAction(player, "give", result);
        } else if (slot == 22) {
            openMainGUI(player);
        }
    }

    private void handleDigitalClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        int slot = event.getSlot();
        PlayerResult result = pendingActionTarget.get(playerUUID);
        if (result == null) { openMainGUI(player); return; }
        String action = pendingActionType.getOrDefault(playerUUID, plugin.getMessage("action.give", "Give"));

        if (slot >= 9 && slot <= 14) {
            int[] amounts = {1, 5, 10, 100, 1000, 5000};
            double cur = pendingActionAmount.getOrDefault(playerUUID, 0.0);
            pendingActionAmount.put(playerUUID, cur + amounts[slot - 9]);
            openDigitalMenu(player, action, result);

        } else if (slot == 18) {
            pendingActionAmount.put(playerUUID, 0.0);
            openDigitalMenu(player, action, result);

        } else if (slot == 16) {
            double amount = pendingActionAmount.getOrDefault(playerUUID, 0.0);
            if (!validateAmount(player, amount, false)) return;
            executeAction(player, result, action, amount);
            pendingActionAmount.put(playerUUID, 0.0);
            openFinanceMenu(player, result);

        } else if (slot == 22) {
            player.closeInventory();
            sendCancelablePrompt(player, plugin.getMessage("gui.enter-custom", "Enter custom amount:"));
            pendingActions.put(playerUUID, (msg, p) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (isCancelWord(msg)) {
                    openDigitalMenu(p, action, result); pendingActions.remove(p.getUniqueId()); return;
                }
                try {
                    double amt = Double.parseDouble(ChatColor.stripColor(msg.trim()));
                    if (!validateAmount(p, amt, false)) {
                        openDigitalMenu(p, action, result); return;
                    }
                    double cur = pendingActionAmount.getOrDefault(p.getUniqueId(), 0.0);
                    pendingActionAmount.put(p.getUniqueId(), cur + amt);
                    openDigitalMenu(p, action, result);
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
                    openDigitalMenu(p, action, result);
                }
                pendingActions.remove(p.getUniqueId());
            }));

        } else if (slot == 26) {
            openFinanceMenu(player, result);
        }
    }

    private void handleHistoryClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        int slot = event.getSlot();
        PlayerResult result = getResultByName(lastTarget.get(playerUUID));

        if (slot == 49) {
            if (result != null) openFinanceMenu(player, result);
            else openMainGUI(player);
        } else if (slot == 45 && result != null) {
            int page = lastPage.getOrDefault(playerUUID, 0);
            if (page > 0) { lastPage.put(playerUUID, page - 1); openHistoryMenu(player, result); }
        } else if (slot == 53 && result != null) {
            int page = lastPage.getOrDefault(playerUUID, 0);
            List<TransactionHandler.Transaction> history = transactionHandler.getHistory(result.uuid);
            int total = (history.size() / 45) + (history.size() % 45 > 0 ? 1 : 0);
            if (page < total - 1) { lastPage.put(playerUUID, page + 1); openHistoryMenu(player, result); }
        }
    }

    private void handleMassClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        int slot = event.getSlot();
        if (slot == 11) {
            checkMassPermAndRequest(player, playerUUID, "mass-give", "economygui.mass-give");
        } else if (slot == 13) {
            checkMassPermAndRequest(player, playerUUID, "mass-take", "economygui.mass-take");
        } else if (slot == 15) {
            checkMassPermAndRequest(player, playerUUID, "mass-set", "economygui.give");
        } else if (slot == 22) {
            openMainGUI(player);
        }
    }

    private void checkMassPermAndRequest(Player player, UUID playerUUID, String action, String perm) {
        if (!player.hasPermission(perm) && !player.hasPermission("economygui.admin")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-permission", "You don't have permission!"));
            return;
        }
        Set<UUID> selected = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
        if (selected.isEmpty()) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players-selected",
                    "No players selected for mass operation!"));
            return;
        }
        requestMassAmount(player, playerUUID, action);
    }

    private void executeAction(Player admin, PlayerResult result, String action, double amount) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        Economy econ = plugin.getEconomy();
        boolean success = false;

        String msgAction = action.toLowerCase();
        if (msgAction.equals(plugin.getMessage("action.give", "Give").toLowerCase())
                || msgAction.equals("give")) {
            success = econ.depositPlayer(target, amount).transactionSuccess();
            if (success) transactionHandler.log(result.uuid, "give", amount, admin);
        } else if (msgAction.equals(plugin.getMessage("action.take", "Take").toLowerCase())
                || msgAction.equals("take")) {
            if (econ.getBalance(target) >= amount) {
                success = econ.withdrawPlayer(target, amount).transactionSuccess();
                if (success) transactionHandler.log(result.uuid, "take", amount, admin);
            } else {
                admin.sendMessage(ChatColor.RED + plugin.getMessage("error.insufficient-funds",
                        "Insufficient funds for %player%.", "player", result.name));
                return;
            }
        } else if (msgAction.equals(plugin.getMessage("action.set", "Set").toLowerCase())
                || msgAction.equals("set")) {
            double current = econ.getBalance(target);
            success = econ.withdrawPlayer(target, current).transactionSuccess()
                    && econ.depositPlayer(target, amount).transactionSuccess();
            if (success) transactionHandler.log(result.uuid, "set", amount, admin);
        }

        if (success) {
            admin.sendMessage(ChatColor.GREEN + plugin.getMessage("action.executed",
                    "Executed %action% of $%amount% for %player%",
                    "action", action, "amount", String.format(moneyFormat, amount), "player", result.name));
        } else {
            admin.sendMessage(ChatColor.RED + plugin.getMessage("error.action-failed", "Action failed."));
        }
    }

    private void executeQuickAction(Player admin, PlayerResult result, String action, double amount) {
        boolean allowZero = action.equals("set");
        if (!validateAmount(admin, amount, allowZero)) return;
        executeAction(admin, result, action, amount);
        openContextMenu(admin, result.name);
    }

    private void requestAmountForAction(Player player, String action, PlayerResult result) {
        player.closeInventory();
        sendCancelablePrompt(player, plugin.getMessage("messages.enter-amount",
                "Enter amount for %action% (or 'cancel' to abort):", "action", action));
        pendingActions.put(player.getUniqueId(), (msg, p) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (isCancelWord(msg)) {
                p.sendMessage(ChatColor.YELLOW + plugin.getMessage("messages.action-cancelled", "Action cancelled."));
                openMainGUI(p); pendingActions.remove(p.getUniqueId()); return;
            }
            try {
                double amount = Double.parseDouble(msg.trim());
                if (!validateAmount(p, amount, false)) { openMainGUI(p); return; }
                executeAction(p, result, action, amount);
            } catch (NumberFormatException e) {
                p.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
            }
            openMainGUI(p); pendingActions.remove(p.getUniqueId());
        }));
        pendingActionType.put(player.getUniqueId(), action);
        pendingActionTarget.put(player.getUniqueId(), result);
    }

    private void requestMassAmount(Player player, UUID playerUUID, String action) {
        player.closeInventory();
        sendCancelablePrompt(player, plugin.getMessage("gui.enter-amount", "Enter amount:"));
        pendingActions.put(playerUUID, (msg, p) -> {
        });
        pendingActionType.put(playerUUID, action);
    }

    private void handleMassChatInput(Player player, String actionType, String message) {
        try {
            double amount = Double.parseDouble(message.trim());
            boolean allowZero = actionType.equals("mass-set");
            if (!validateAmount(player, amount, allowZero)) {
                openMassActionsMenu(player); return;
            }

            Set<UUID> selected = playerSelections.getOrDefault(player.getUniqueId(), Collections.emptySet());
            if (selected.isEmpty()) {
                player.sendMessage(ChatColor.RED + plugin.getMessage("error.no-players-selected",
                        "No players selected for mass operation!"));
                openMassActionsMenu(player); return;
            }

            Economy econ = plugin.getEconomy();
            int count = 0;
            for (UUID uuid : selected) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
                if (target.getName() == null) continue;
                boolean success = false;
                if (actionType.equals("mass-give")) {
                    success = econ.depositPlayer(target, amount).transactionSuccess();
                    if (success) transactionHandler.log(uuid.toString(), "give", amount, player);
                } else if (actionType.equals("mass-take")) {
                    if (econ.getBalance(target) >= amount) {
                        success = econ.withdrawPlayer(target, amount).transactionSuccess();
                        if (success) transactionHandler.log(uuid.toString(), "take", amount, player);
                    }
                } else if (actionType.equals("mass-set")) {
                    double current = econ.getBalance(target);
                    success = econ.withdrawPlayer(target, current).transactionSuccess()
                            && econ.depositPlayer(target, amount).transactionSuccess();
                    if (success) transactionHandler.log(uuid.toString(), "set", amount, player);
                }
                if (success) count++;
            }
            String actionName = actionType.replace("mass-", "");
            player.sendMessage(ChatColor.GREEN + plugin.getMessage("messages.mass-action-applied",
                    "Applied %action% on $%amount% to %count% players",
                    "action", actionName, "amount", String.format(moneyFormat, amount),
                    "count", String.valueOf(count)));
            openMassActionsMenu(player);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount-format", "Invalid amount format."));
            openMassActionsMenu(player);
        }
    }

    public void startBalancePolling() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                double cur = plugin.getEconomy().getBalance(p);
                lastKnownBalances.put(p.getUniqueId(), cur);
            }
        }, 100L, 100L);
    }

    public PlayerResult getPlayerResultByName(String name) {
        return getResultByName(name);
    }

    private PlayerResult getResultByName(String name) {
        if (name == null || name.isEmpty()) return null;
        PlayerResult r = cachedResults.stream()
                .filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (r != null) return r;
        return playerCache.getFiltered(name, Filter.ALL, Collections.emptySet())
                .stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private boolean validateAmount(Player player, double amount, boolean allowZero) {
        if (amount < 0) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.negative-amount", "Amount cannot be negative."));
            return false;
        }
        if (!allowZero && amount <= 0) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount", "Amount must be positive."));
            return false;
        }
        if (!plugin.isWithinMaxAmount(amount)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage("error.invalid-amount",
                    "Amount exceeds the server limit."));
            return false;
        }
        return true;
    }

    private boolean isCancelWord(String msg) {
        return msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("отмена");
    }

    private void sendCancelablePrompt(Player player, String promptText) {
        TextComponent msg    = new TextComponent(ChatColor.YELLOW + promptText + " ");
        TextComponent cancel = new TextComponent(ChatColor.RED + plugin.getMessage("gui.cancel", "[Cancel]"));
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/economygui reset"));
        msg.addExtra(cancel);
        player.spigot().sendMessage(msg);
    }

    private void playSound(Player player) {
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(cachedResults.size() / 36.0));
    }

    private ItemStack createFilterItem() {
        Material mat;
        String nameKey;
        ChatColor color;
        switch (currentFilter) {
            case ONLINE:  mat = Material.LIME_DYE;  nameKey = "filter.online";  color = ChatColor.GREEN; break;
            case OFFLINE: mat = Material.GRAY_DYE;  nameKey = "filter.offline"; color = ChatColor.GRAY;  break;
            default:      mat = Material.COMPASS;   nameKey = "filter.all";     color = ChatColor.WHITE; break;
        }
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage("filter.filter-current", "Current filter:"));
        lore.add("");
        lore.add(filterLine(Filter.ALL));
        lore.add(filterLine(Filter.ONLINE));
        lore.add(filterLine(Filter.OFFLINE));
        lore.add("");
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.click-cycle", "LMB → Next"));
        lore.add(ChatColor.GRAY + plugin.getMessage("right-click-cycle", "RMB → Previous"));
        return itemFactory.simple(mat, color + plugin.getMessage(nameKey, nameKey), lore);
    }

    private String filterLine(Filter f) {
        String name;
        ChatColor c;
        switch (f) {
            case ONLINE:  name = plugin.getMessage("filter.online", "Online");  c = ChatColor.GREEN; break;
            case OFFLINE: name = plugin.getMessage("filter.offline", "Offline"); c = ChatColor.GRAY; break;
            default:      name = plugin.getMessage("filter.all", "All");         c = ChatColor.WHITE; break;
        }
        return (f == currentFilter)
                ? c + "» " + ChatColor.BOLD + name + " «"
                : "  " + c + name;
    }

    private ItemStack createGlobalStatsButton() {
        List<String> lore = Arrays.asList(
                ChatColor.GRAY + plugin.getMessage("gui.click-to-view", "Click to view history"),
                "",
                ChatColor.GRAY + plugin.getMessage("gui.global-stats-lore", "(Total balance, average, top players...)"));
        return itemFactory.simple(Material.BOOK,
                ChatColor.LIGHT_PURPLE + plugin.getMessage("gui.global-stats", "Global Economy Stats"), lore);
    }

    private class StatsMenuHolder implements InventoryHolder {
        private final Inventory inventory;
        StatsMenuHolder(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }
}