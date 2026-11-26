package ru.stepanyaa.economyGUI;

import ru.stepanyaa.economyGUI.compat.ChatPrompts;

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
import ru.stepanyaa.economyGUI.util.ServerScheduler;
import ru.stepanyaa.economyGUI.economy.EconomyBridge;

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
    private final PlayerCache playerCache;
    private final ItemFactory itemFactory;

    private Inventory inventory;
    private final Map<UUID, Inventory> mainInventories = new ConcurrentHashMap<>();
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
    private final Map<UUID, TransactionHandler.Transaction> pendingRefundTx = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Long>> visibleHistoryIds = new ConcurrentHashMap<>();

    private List<PlayerResult> cachedResults = new ArrayList<>();

    private static final long PAGE_SWITCH_COOLDOWN = 250L;
    private final Map<UUID, Long> lastPageSwitch  = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFilterSwitch = new ConcurrentHashMap<>();

    final Map<UUID, Double> lastKnownBalances = new ConcurrentHashMap<>();

    private final String moneyFormat = "%,.2f";

    public EconomySearchGUI(EconomyGUI plugin) {
        this.plugin             = plugin;
        this.playerCache        = new PlayerCache(plugin);
        this.itemFactory        = new ItemFactory(plugin);
    }

    public void recreateInventory() {
        mainInventories.clear();
        inventory = null;
    }

    public PlayerCache getPlayerCache() { return playerCache; }
    public Map<UUID, Double> getLastKnownBalances() { return lastKnownBalances; }

    @Override
    public Inventory getInventory() { return inventory; }

    public void openMainGUI(Player player) {
        UUID playerId = player.getUniqueId();
        currentPage = lastPage.getOrDefault(playerId, 0);
        refreshGUI(player);
        player.openInventory(mainInventories.get(playerId));
        lastOpenedMenu.put(playerId, "main");
        playersInGUI.add(playerId);
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
            case "refund": {
                TransactionHandler.Transaction t = pendingRefundTx.get(player.getUniqueId());
                PlayerResult r = getResultByName(lastTarget.getOrDefault(player.getUniqueId(), ""));
                if (t != null) openRefundMenu(player, t, r); else openMainGUI(player);
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
        player.sendMessage(ChatColor.GREEN + plugin.getMessage(player, "messages.search-reset", "Search and filters reset."));
        openMainGUI(player);
    }

    public void refreshOpenGUIs() {
        for (UUID uuid : playersInGUI) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) openLastGUIMenu(p);
        }
    }

    private Inventory getMainInventory(Player player) {
        UUID playerId = player.getUniqueId();
        Inventory main = mainInventories.get(playerId);
        if (main == null) {
            MainMenuHolder holder = new MainMenuHolder();
            main = Bukkit.createInventory(holder, 54,
                    ChatColor.DARK_PURPLE + plugin.getMessage(player, "gui.title", "Economy Management"));
            holder.setInventory(main);
            mainInventories.put(playerId, main);
        }
        inventory = main;
        return main;
    }

    private void refreshGUI(Player player) {
        cachedResults = playerCache.getFiltered(currentSearch, currentFilter, selectedPlayers);
        int page = lastPage.getOrDefault(player.getUniqueId(), 0);
        currentPage = page;
        setupMainMenuLayout(player, getMainInventory(player), page);
    }

    private void refreshGUI() {
        cachedResults = playerCache.getFiltered(currentSearch, currentFilter, selectedPlayers);
        for (UUID playerId : new ArrayList<>(playersInGUI)) {
            if (!"main".equals(lastOpenedMenu.get(playerId))) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            int page = lastPage.getOrDefault(playerId, 0);
            currentPage = page;
            setupMainMenuLayout(player, getMainInventory(player), page);
        }
    }

    private void setupMainMenuLayout(Player player, Inventory main, int page) {
        main.clear();
        String searchLabel = currentSearch.isEmpty() ? plugin.getMessage(player, "gui.search-all", "all") : currentSearch;
        List<String> searchLore = Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.search-hint", "LMB: Enter query | RMB: Reset search | Shift+Left: Select/Deselect"));
        main.setItem(4, itemFactory.simple(Material.COMPASS, ChatColor.YELLOW + plugin.getMessage(player, "gui.search", "Search: %search_query%", "search_query", searchLabel), searchLore));
        main.setItem(49, createFilterItem(player));
        main.setItem(50, createGlobalStatsButton(player));

        if (plugin.isMassOperationsEnabled()) {
            main.setItem(48, itemFactory.button(player, Material.DIAMOND_BLOCK, ChatColor.AQUA, "gui.mass-menu-title", "Mass Operations", "gui.mass-hint", "Click for mass give/take/set (select players first)"));
        }

        int totalPages = totalPages();
        main.setItem(45, itemFactory.pageButton(player, page > 0, false, page, totalPages));
        main.setItem(53, itemFactory.pageButton(player, page < totalPages - 1, true, page, totalPages));

        if (plugin.isPlayerSelectionEnabled()) {
            main.setItem(46, itemFactory.button(player, Material.EMERALD_BLOCK, ChatColor.GREEN, "gui.select-all", "Select All", "gui.click-to-select", "Click to select all on page"));
            main.setItem(47, itemFactory.button(player, Material.REDSTONE_BLOCK, ChatColor.RED, "gui.cancel-selection", "Cancel Selection", "gui.click-to-cancel", "Click to cancel all selections"));
        }

        int start = page * 36;
        int end = Math.min(start + 36, cachedResults.size());
        for (int i = start; i < end; i++) {
            PlayerResult result = cachedResults.get(i);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
            boolean selected = selectedPlayers.contains(UUID.fromString(result.uuid));
            main.setItem(9 + (i - start), itemFactory.playerHead(player, offlinePlayer, result.balance, selected, moneyFormat));
        }

        if (cachedResults.isEmpty()) {
            List<String> noLore = Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.no-players-hint", "Try changing the search or filter"));
            main.setItem(22, itemFactory.simple(Material.BARRIER, ChatColor.RED + plugin.getMessage(player, "gui.no-players", "No players found"), noLore));
        }
    }

    private void openFinanceMenu(Player player, PlayerResult result) {
        if (!plugin.isFullManagementEnabled()) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.full-management-disabled", "Full management is disabled in config!"));
            openMainGUI(player);
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        double freshBalance = plugin.safeGetBalance(target);
        Inventory inv = Bukkit.createInventory(this, 54, ChatColor.DARK_PURPLE + plugin.getMessage(player, "gui.title", "Economy Management") + ": " + result.name);

        inv.setItem(4, itemFactory.playerHead(player, target, freshBalance, false, moneyFormat));
        inv.setItem(13, itemFactory.button(player, Material.WRITABLE_BOOK, ChatColor.YELLOW, "gui.enter-amount", "Enter Amount", "gui.enter-amount-hint", "Left click to enter amount in chat"));
        inv.setItem(20, itemFactory.button(player, Material.EMERALD, ChatColor.GREEN, "action.give", "Give Amount", "gui.give-hint", "Click to open digital menu"));
        inv.setItem(22, itemFactory.button(player, Material.REDSTONE, ChatColor.RED, "action.take", "Take Amount", "gui.take-hint", "Click to open digital menu"));
        inv.setItem(24, itemFactory.button(player, Material.GOLD_INGOT, ChatColor.YELLOW, "action.set", "Set Amount", "gui.set-hint", "Click to enter amount in chat"));
        inv.setItem(31, itemFactory.button(player, Material.BOOK, ChatColor.BLUE, "history.operations", "Operations History", "history.hint", "Click to view logs"));
        inv.setItem(49, itemFactory.backButton(player, "gui.back-hint", "Return to main menu"));

        player.openInventory(inv);
        lastOpenedMenu.put(player.getUniqueId(), "finance");
        lastTarget.put(player.getUniqueId(), result.name);
        playersInGUI.add(player.getUniqueId());
    }

    private void openContextMenu(Player player, String targetName) {
        PlayerResult result = getResultByName(targetName);
        if (result == null) { openMainGUI(player); return; }

        Inventory inv = Bukkit.createInventory(this, 27, ChatColor.BLUE + plugin.getMessage(player, "context-menu.title", "Quick Actions for %player%", "player", targetName));
        inv.setItem(10, quickActionItem(player, plugin.getMessage(player, "context-menu.give-100","Give $100"), "give", 100,  result));
        inv.setItem(11, quickActionItem(player, plugin.getMessage(player, "context-menu.give-1000","Give $1000"),  "give", 1000, result));
        inv.setItem(12, quickActionItem(player, plugin.getMessage(player, "context-menu.take-100","Take $100"),   "take", 100,  result));
        inv.setItem(13, quickActionItem(player, plugin.getMessage(player, "context-menu.take-1000","Take $1000"),  "take", 1000, result));
        inv.setItem(14, quickActionItem(player, plugin.getMessage(player, "context-menu.set-5000","Set $5000"),   "set",  5000, result));
        inv.setItem(15, quickActionItem(player, plugin.getMessage(player, "context-menu.reset-0","Reset to $0"), "set",  0,    result));
        inv.setItem(16, quickActionItem(player, plugin.getMessage(player, "gui.custom-amount","Custom Amount"), "custom", 0, result));

        if (plugin.isFullManagementEnabled()) {
            inv.setItem(4, itemFactory.button(player, Material.BOOK, ChatColor.GREEN, "context-menu.full-open", "Open Full Management", "gui.actions-lmb", "Left click: Open Management Menu"));
        }
        inv.setItem(22, itemFactory.simple(Material.BARRIER, ChatColor.RED + plugin.getMessage(player, "context-menu.back", "Back to Main Menu"), Collections.emptyList()));

        player.openInventory(inv);
        lastOpenedMenu.put(player.getUniqueId(), "context");
        lastTarget.put(player.getUniqueId(), targetName);
        playersInGUI.add(player.getUniqueId());
    }

    private ItemStack quickActionItem(Player player, String label, String action, double amount, PlayerResult result) {
        Material mat = action.equals("give") ? Material.EMERALD : action.equals("take") ? Material.REDSTONE : Material.GOLD_INGOT;
        String loreText = plugin.getMessage(player, "context-menu.apply-info", "Click to apply to %player%", "player", result.name);
        List<String> lore = Collections.singletonList(ChatColor.GRAY + loreText);
        return itemFactory.simple(mat, ChatColor.YELLOW + label, lore);
    }

    private void openDigitalMenu(Player player, String action, PlayerResult result) {
        double totalAmount = pendingActionAmount.getOrDefault(player.getUniqueId(), 0.0);
        Inventory inv = Bukkit.createInventory(this, 27, ChatColor.DARK_PURPLE + plugin.getMessage(player, "gui.digital-menu", "%action% Menu: %player%", "action", action, "player", result.name));
        inv.setItem(4, itemFactory.playerHead(player, Bukkit.getOfflinePlayer(UUID.fromString(result.uuid)), result.balance, false, moneyFormat));

        int[] amounts = {1, 5, 10, 100, 1000, 5000};
        for (int i = 0; i < amounts.length; i++) {
            List<String> lore = Arrays.asList(ChatColor.GRAY + plugin.getMessage(player, "gui.add-amount-hint", "Click to add %amount% to total", "amount", String.valueOf(amounts[i])), ChatColor.GRAY + plugin.getMessage(player, "gui.current-amount", "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
            inv.setItem(9 + i, itemFactory.simple(Material.PAPER, ChatColor.YELLOW + String.valueOf(amounts[i]), lore));
        }

        List<String> customLore = Arrays.asList(ChatColor.GRAY + plugin.getMessage(player, "gui.custom-amount-hint", "Left click to enter custom amount in chat"), ChatColor.GRAY + plugin.getMessage(player, "gui.current-amount", "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
        inv.setItem(22, itemFactory.simple(Material.WRITABLE_BOOK, ChatColor.BLUE + plugin.getMessage(player, "gui.custom-amount", "Custom Amount"), customLore));

        List<String> resetLore = Arrays.asList(ChatColor.GRAY + plugin.getMessage(player, "gui.reset-amount-hint", "Reset the selected amount to 0"), ChatColor.GRAY + plugin.getMessage(player, "gui.current-amount", "Current amount: $%amount%", "amount", String.format(moneyFormat, totalAmount)));
        inv.setItem(18, itemFactory.simple(Material.REDSTONE_BLOCK, ChatColor.RED + plugin.getMessage(player, "gui.reset-amount", "Reset Amount"), resetLore));

        List<String> confirmLore = Collections.singletonList(ChatColor.GRAY + plugin.getMessage(player, "gui.confirm-hint", "Execute: %action% $%amount%", "action", action, "amount", String.format(moneyFormat, totalAmount)));
        inv.setItem(16, itemFactory.simple(Material.EMERALD_BLOCK, ChatColor.GREEN + plugin.getMessage(player, "gui.confirm", "Confirm"), confirmLore));

        inv.setItem(26, itemFactory.backButton(player, "gui.back-hint-finance", "Return to finance menu"));
        player.openInventory(inv);
        pendingActionType.put(player.getUniqueId(), action);
        pendingActionTarget.put(player.getUniqueId(), result);
        lastOpenedMenu.put(player.getUniqueId(), "digital");
    }

    private void openHistoryMenu(Player player, PlayerResult result) {
        plugin.getTransactionHandler().getHistoryAsync(result.uuid).thenAccept(history -> {
            ServerScheduler.runTask(plugin, player, () -> {
                Inventory inv = Bukkit.createInventory(this, 54, ChatColor.GOLD + plugin.getMessage(player, "gui.history", "Operations History") + ": " + result.name);
                lastOpenedMenu.put(player.getUniqueId(), "history");
                lastTarget.put(player.getUniqueId(), result.name);

                int page  = lastPage.getOrDefault(player.getUniqueId(), 0);
                int start = page * 45;
                int end   = Math.min(start + 45, history.size());

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                boolean canRefund = player.hasPermission("economygui.refund") || player.hasPermission("economygui.admin");

                Map<Integer, Long> visibleIds = new HashMap<>();
                visibleHistoryIds.put(player.getUniqueId(), visibleIds);
                for (int i = start; i < end; i++) {
                    TransactionHandler.Transaction t = history.get(i);
                    visibleIds.put(i - start, t.id);
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "ID: " + ChatColor.AQUA + "#" + t.id);
                    lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-action", "Action: %action%", "action", t.action != null ? t.action : "TRANSACTION"));
                    lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-amount", "Amount: $%amount%", "amount", String.format(moneyFormat, t.amount)));
                    lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-sender", "Sender: %sender%", "sender", t.senderName != null ? t.senderName : "N/A"));
                    lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-receiver", "Receiver: %receiver%", "receiver", t.receiverName != null ? t.receiverName : "N/A"));
                    lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-by", "By: %executor%", "executor", t.executor));
                    lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-date", "Date: %date%", "date", sdf.format(new Date(t.timestamp))));

                    String refStatus = t.refundStatus != null ? t.refundStatus : "NONE";
                    if (!refStatus.equalsIgnoreCase("NONE")) {
                        lore.add(ChatColor.LIGHT_PURPLE + plugin.getMessage(player, "refund.history-lore-status", "Refund Status: %status%", "status", refStatus));
                    } else {
                        lore.add(ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-status", "Refund Status: %status%", "status", "None"));
                    }

                    if (canRefund) {
                        lore.add("");
                        lore.add(ChatColor.YELLOW + plugin.getMessage(player, "refund.history-lore-click", "▶ Click to open Refund Menu"));
                    }

                    Material iconMat = Material.PAPER;
                    if ("REFUNDED_BOTH".equalsIgnoreCase(refStatus) || "REVERSED".equalsIgnoreCase(refStatus)) {
                        iconMat = Material.BOOK;
                    }

                    ItemStack item = itemFactory.simple(iconMat, ChatColor.GOLD + "#" + t.id + " " + ChatColor.YELLOW + (t.action != null ? t.action.toUpperCase() : "TX") + " $" + String.format(moneyFormat, t.amount), lore);
                    inv.setItem(i - start, item);
                }

                if (history.isEmpty()) {
                    inv.setItem(22, itemFactory.simple(Material.BARRIER, ChatColor.RED + plugin.getMessage(player, "gui.no-players", "No transactions found"), Collections.emptyList()));
                }

                int totalPages = (history.size() / 45) + (history.size() % 45 > 0 ? 1 : 0);
                if (totalPages == 0) totalPages = 1;
                inv.setItem(45, itemFactory.pageButton(player, page > 0, false, page, totalPages));
                inv.setItem(53, itemFactory.pageButton(player, page < totalPages - 1, true, page, totalPages));
                inv.setItem(49, itemFactory.backButton(player, "gui.back-hint", "Return to finance menu"));

                player.openInventory(inv);
            });
        });
    }

    private void openRefundMenu(Player player, TransactionHandler.Transaction t, PlayerResult result) {
        String refundTitle = plugin.getMessage(player, "refund.title", "Refund #%id%", "id", String.valueOf(t.id));
        Inventory inv = Bukkit.createInventory(this, 27, ChatColor.DARK_RED + refundTitle);
        lastOpenedMenu.put(player.getUniqueId(), "refund");
        pendingRefundTx.put(player.getUniqueId(), t);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String ref = t.refundStatus != null ? t.refundStatus.toUpperCase() : "NONE";

        List<String> infoLore = Arrays.asList(
                ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-action", "Action: %action%", "action", t.action != null ? t.action : "TRANSFER"),
                ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-amount", "Amount: $%amount%", "amount", String.format(moneyFormat, t.amount)),
                ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-sender", "Sender: %sender%", "sender", t.senderName != null ? t.senderName : "N/A"),
                ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-receiver", "Receiver: %receiver%", "receiver", t.receiverName != null ? t.receiverName : "N/A"),
                ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-date", "Date: %date%", "date", sdf.format(new Date(t.timestamp))),
                ChatColor.GRAY + plugin.getMessage(player, "refund.history-lore-status", "Status: %status%", "status", ref)
        );
        inv.setItem(4, itemFactory.simple(Material.BOOK, ChatColor.GOLD + "#" + t.id, infoLore));

        if (t.canReverse()) {
            List<String> lore = new ArrayList<>();
            String amount = plugin.getEconomyBridge().format(t.amount);
            if ("PAY".equalsIgnoreCase(t.action)) {
                lore.add(ChatColor.RED + "-" + amount + " : " + t.receiverName);
                lore.add(ChatColor.GREEN + "+" + amount + " : " + t.senderName);
            } else if ("GIVE".equalsIgnoreCase(t.action)) {
                lore.add(ChatColor.RED + "-" + amount + " : " + t.receiverName);
            } else if ("TAKE".equalsIgnoreCase(t.action)) {
                lore.add(ChatColor.GREEN + "+" + amount + " : " + t.senderName);
            }
            lore.add(ChatColor.YELLOW + plugin.getMessage(player, "refund.reverse-hint", "Cancel the original operation once. No money is created."));
            inv.setItem(13, itemFactory.simple(Material.EMERALD_BLOCK,
                    ChatColor.GREEN + plugin.getMessage(player, "refund.reverse-button", "Reverse transaction"), lore));
        } else {
            inv.setItem(13, itemFactory.simple(Material.BARRIER,
                    ChatColor.GRAY + plugin.getMessage(player, "refund.reverse-unavailable", "Reversal unavailable"),
                    Collections.singletonList(ChatColor.RED + plugin.getMessage(player, "refund.reverse-reason",
                            "Already processed, unsupported operation, or manual review required."))));
        }

        inv.setItem(22, itemFactory.backButton(player, "refund.back-to-history", "Return to History"));

        player.openInventory(inv);
    }

    private void handleRefundClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        int slot = event.getSlot();
        TransactionHandler.Transaction t = pendingRefundTx.get(playerUUID);
        PlayerResult result = getResultByName(lastTarget.get(playerUUID));
        if (t == null) {
            openMainGUI(player);
            return;
        }

        if (slot == 22) {
            if (result != null) openHistoryMenu(player, result);
            else openMainGUI(player);
            return;
        }

        if (!player.hasPermission("economygui.refund") && !player.hasPermission("economygui.admin")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-permission", "You don't have permission!"));
            return;
        }
        if (slot == 13 && t.canReverse()) {
            executeRefund(player, t, "reverse", result);
        }
    }

    private void executeRefund(Player admin, TransactionHandler.Transaction t, String type, PlayerResult result) {
        admin.sendMessage(ChatColor.YELLOW + plugin.getMessage(admin, "refund.processing", "Processing refund #%id% (%target%)...", "id", String.valueOf(t.id), "target", type));
        plugin.getTransactionHandler().refundTransaction(t.id, type, admin.getName()).handle((success, error) -> {
            ServerScheduler.runTask(plugin, admin, () -> {
                if (error == null && Boolean.TRUE.equals(success)) {
                    admin.sendMessage(ChatColor.GREEN + plugin.getMessage(admin, "refund.reverse-success", "Transaction #%id% reversed.", "id", String.valueOf(t.id)));
                } else {
                    admin.sendMessage(ChatColor.RED + plugin.getMessage(admin, "refund.reverse-failed", "Cannot reverse #%id%: insufficient funds, unsupported operation, already processed or manual review required.", "id", String.valueOf(t.id)));
                }
                if (result != null) {
                    openHistoryMenu(admin, result);
                } else {
                    openMainGUI(admin);
                }
            });
            return null;
        });
    }

    private void openMassActionsMenu(Player player) {
        Inventory inv = Bukkit.createInventory(this, 27, ChatColor.DARK_PURPLE + plugin.getMessage(player, "gui.mass-menu-title", "Mass Operations"));
        inv.setItem(11, itemFactory.button(player, Material.EMERALD_BLOCK, ChatColor.GREEN, "gui.mass-give", "Give Amount to All Selected", "gui.mass-give-hint", "Click to enter amount for all"));
        inv.setItem(13, itemFactory.button(player, Material.REDSTONE_BLOCK, ChatColor.RED, "gui.mass-take", "Take Amount from All Selected", "gui.mass-take-hint", "Click to enter amount for all"));
        inv.setItem(15, itemFactory.button(player, Material.GOLD_BLOCK, ChatColor.YELLOW, "gui.mass-set", "Set Balance for All Selected", "gui.mass-set-hint", "Click to enter amount for all"));
        inv.setItem(22, itemFactory.backButton(player, "gui.click-to-return", "Return to Main Menu"));
        player.openInventory(inv);
        lastOpenedMenu.put(player.getUniqueId(), "mass");
    }

    private void openGlobalStatsMenu(Player player) {
        player.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "gui.stats-loading", "Loading economy statistics..."));
        ServerScheduler.runTaskAsync(plugin, () -> {
            List<PlayerResult> all = playerCache.getAll();
            if (all.isEmpty()) {
                ServerScheduler.runTask(plugin, player, () -> player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-players", "No players found.")));
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

            List<PlayerResult> topRich = all.stream().sorted(Comparator.comparingDouble((PlayerResult r) -> r.balance).reversed()).limit(5).collect(Collectors.toList());
            List<PlayerResult> topPoor = all.stream().sorted(Comparator.comparingDouble((PlayerResult r) -> r.balance)).limit(5).collect(Collectors.toList());

            final int fCount = playerCount; final int fOnline = onlineCount;
            final double fTotal = totalBalance; final double fOnlineB = onlineBalance;
            final double fAvg = avg; final double fAvgOnl = avgOnl;
            final PlayerResult fRichest = richest; final PlayerResult fPoorest = poorest;

            ServerScheduler.runTask(plugin, player, () -> {
                Inventory statsInv = Bukkit.createInventory(new StatsMenuHolder(null), 54, ChatColor.DARK_PURPLE + plugin.getMessage(player, "gui.stats-title", "Economy Statistics"));

                List<String> overviewLore = new ArrayList<>();
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-total-players", "Total players: ") + ChatColor.WHITE + fCount);
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-online-players", "Online players: ") + ChatColor.GREEN + fOnline);
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-total-balance", "Total balance: ") + ChatColor.GREEN + String.format(moneyFormat, fTotal));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-online-balance", "Online balance: ") + ChatColor.GREEN + String.format(moneyFormat, fOnlineB));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-average-balance", "Average balance: ") + ChatColor.GREEN + String.format(moneyFormat, fAvg));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-average-online", "Avg online balance: ") + ChatColor.GREEN + String.format(moneyFormat, fAvgOnl));
                overviewLore.add("");
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-richest", "Richest: ") + ChatColor.GOLD + (fRichest != null ? fRichest.name + " (" + String.format(moneyFormat, fRichest.balance) + ")" : "—"));
                overviewLore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.stats-poorest", "Poorest: ") + ChatColor.RED  + (fPoorest != null ? fPoorest.name + " (" + String.format(moneyFormat, fPoorest.balance) + ")" : "—"));
                statsInv.setItem(11, itemFactory.simple(Material.BOOK, ChatColor.YELLOW + plugin.getMessage(player, "gui.stats-overview", "Server Economy Overview"), overviewLore));

                List<String> richLore = new ArrayList<>();
                for (int i = 0; i < topRich.size(); i++) {
                    PlayerResult r = topRich.get(i);
                    richLore.add(ChatColor.GRAY.toString() + (i + 1) + ". " + ChatColor.WHITE + r.name + ChatColor.GRAY + " — " + ChatColor.GREEN + String.format(moneyFormat, r.balance));
                }
                statsInv.setItem(13, itemFactory.simple(Material.DIAMOND, ChatColor.GOLD + plugin.getMessage(player, "gui.stats-top-rich", "Top 5 Richest"), richLore));
                List<String> poorLore = new ArrayList<>();
                for (int i = 0; i < topPoor.size(); i++) {
                    PlayerResult r = topPoor.get(i);
                    poorLore.add(ChatColor.GRAY.toString() + (i + 1) + ". " + ChatColor.WHITE + r.name + ChatColor.GRAY + " — " + ChatColor.RED + String.format(moneyFormat, r.balance));
                }
                statsInv.setItem(15, itemFactory.simple(Material.IRON_INGOT, ChatColor.RED + plugin.getMessage(player, "gui.stats-top-poor", "Top 5 Poorest"), poorLore));

                statsInv.setItem(49, itemFactory.backButton(player, "gui.click-to-return", "Return to main menu"));
                player.openInventory(statsInv);
            });
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        ServerScheduler.runTaskLater(plugin, p, () -> {
            double balance = plugin.safeGetBalance(p);
            lastKnownBalances.put(p.getUniqueId(), balance);
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastKnownBalances.remove(id);
        playersInGUI.remove(id);
        pendingActions.remove(id);
        pendingActionType.remove(id);
        pendingActionAmount.remove(id);
        pendingActionTarget.remove(id);
        lastOpenedMenu.remove(id);
        lastPage.remove(id);
        lastTarget.remove(id);
        pendingRefundTx.remove(id);
        playerSelections.remove(id);
        mainInventories.remove(id);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!playersInGUI.contains(id)) {
            return;
        }
        try {
            InventoryHolder holder = event.getView().getTopInventory().getHolder();
            if (holder instanceof EconomySearchGUI || holder instanceof MainMenuHolder || holder instanceof StatsMenuHolder) {
                playersInGUI.remove(id);
            }
        } catch (Throwable ignored) {
            playersInGUI.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!pendingActions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        String message = event.getMessage();

        ServerScheduler.runTask(plugin, player, () -> {
            ChatAction action = pendingActions.get(player.getUniqueId());
            if (action == null) return;
            if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("отмена")) {
                player.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "messages.input-cancelled", "Input cancelled."));
                pendingActions.remove(player.getUniqueId());
                String actionType = pendingActionType.get(player.getUniqueId());
                if (actionType != null && actionType.startsWith("mass-")) {
                    pendingActionType.remove(player.getUniqueId());
                    pendingActionAmount.remove(player.getUniqueId());
                    playerSelections.remove(player.getUniqueId());
                }
                openLastGUIMenu(player);
                return;
            }

            String actionType = pendingActionType.get(player.getUniqueId());
            if (actionType != null && actionType.startsWith("mass-")) {
                handleMassChatInput(player, actionType, message);
                pendingActions.remove(player.getUniqueId());
                return;
            }
            action.execute(message, player);
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof EconomySearchGUI || holder instanceof MainMenuHolder || holder instanceof StatsMenuHolder)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;

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
            case "main":        handleMainClick(event, player, playerUUID, now, clicked);    break;
            case "finance":     handleFinanceClick(event, player, playerUUID);       break;
            case "context":     handleContextClick(event, player, playerUUID);       break;
            case "digital":     handleDigitalClick(event, player, playerUUID);       break;
            case "history":     handleHistoryClick(event, player, playerUUID);       break;
            case "refund":      handleRefundClick(event, player, playerUUID);        break;
            case "mass":        handleMassClick(event, player, playerUUID);          break;
            case "mass-confirm":handleMassConfirmClick(event, player, playerUUID);  break;
        }
    }

    private void handleMainClick(InventoryClickEvent event, Player player, UUID playerUUID, long now, ItemStack clicked) {
        currentPage = lastPage.getOrDefault(playerUUID, 0);
        int slot = event.getSlot();

        if (slot == 45 || slot == 53) {
            if (now - lastPageSwitch.getOrDefault(playerUUID, 0L) < PAGE_SWITCH_COOLDOWN) return;
            lastPageSwitch.put(playerUUID, now);
            int totalPages = totalPages();
            if (slot == 45 && currentPage > 0) {
                currentPage = event.getClick() == ClickType.SHIFT_RIGHT ? Math.max(0, currentPage - 5) : currentPage - 1;
            } else if (slot == 53 && currentPage < totalPages - 1) {
                currentPage = event.getClick() == ClickType.SHIFT_RIGHT ? Math.min(totalPages - 1, currentPage + 5) : currentPage + 1;
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
                player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-players-selected", "No players selected for mass operation!"));
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
                sendCancelablePrompt(player, plugin.getMessage(player, "gui.enter-search", "Enter name to search: "));
                pendingActions.put(playerUUID, (msg, p) -> ServerScheduler.runTask(plugin, p, () -> {
                    String input = ChatColor.stripColor(msg.trim());
                    if (input.equalsIgnoreCase("cancel") || input.equalsIgnoreCase("отмена")) {
                        resetSearch(p); return;
                    }
                    currentSearch = input;
                    currentPage = 0;
                    lastPage.put(p.getUniqueId(), 0);
                    pendingActions.remove(p.getUniqueId());
                    openMainGUI(p);
                }));
            }
            return;
        }

        if (slot >= 9 && slot <= 44) {
            String name = ChatColor.stripColor(clicked.getItemMeta() != null ? clicked.getItemMeta().getDisplayName() : "");
            PlayerResult result = cachedResults.stream().filter(r -> r.name.equalsIgnoreCase(name)).findFirst().orElse(null);
            if (result == null) return;

            if (event.getClick() == ClickType.LEFT) {
                if (!plugin.isFullManagementEnabled()) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.full-management-disabled", "Full management is disabled in config!"));
                    return;
                }
                openFinanceMenu(player, result);
            } else if (event.getClick() == ClickType.RIGHT) {
                if (!plugin.isQuickActionsEnabled()) return;
                openContextMenu(player, name);
            } else if (event.getClick() == ClickType.SHIFT_LEFT && now - lastPageSwitch.getOrDefault(playerUUID, 0L) >= PAGE_SWITCH_COOLDOWN) {
                if (!plugin.isPlayerSelectionEnabled()) return;
                lastPageSwitch.put(playerUUID, now);
                UUID uuid = UUID.fromString(result.uuid);
                Set<UUID> sel = playerSelections.computeIfAbsent(playerUUID, k -> new HashSet<>());
                if (selectedPlayers.contains(uuid)) {
                    selectedPlayers.remove(uuid); sel.remove(uuid);
                    player.sendMessage(ChatColor.GRAY + plugin.getMessage(player, "messages.player-deselected", "Player %player% deselected.", "player", result.name));
                } else {
                    selectedPlayers.add(uuid); sel.add(uuid);
                    player.sendMessage(ChatColor.GREEN + plugin.getMessage(player, "messages.player-selected", "Player %player% selected.", "player", result.name));
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
                    player.sendMessage(ChatColor.RED + plugin.getMessage(player, "messages.no-permission-give", "You don't have permission to give."));
                    return;
                }
                player.closeInventory();
                sendCancelablePrompt(player, plugin.getMessage(player, "gui.enter-amount", "Enter amount in chat:"));
                pendingActions.put(playerUUID, (msg, p) -> ServerScheduler.runTask(plugin, p, () -> {
                    if (isCancelWord(msg)) {
                        p.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "messages.input-cancelled", "Input cancelled."));
                        openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId()); return;
                    }
                    try {
                        double amount = Double.parseDouble(msg.trim());
                        if (!validateAmount(p, amount, false)) { openFinanceMenu(p, result); return; }
                        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                        if (plugin.getEconomyBridge().deposit(target.getUniqueId(), target.getName(), amount)) {
                            p.sendMessage(ChatColor.GREEN + plugin.getMessage(player, "messages.gave-amount", "Gave $%amount% to %player%", "amount", String.format(moneyFormat, amount), "player", result.name));
                            plugin.getTransactionHandler().log(result.uuid, result.name, "give", amount, p);
                        } else {
                            p.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.action-failed", "Action failed."));
                        }
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.invalid-amount-format", "Invalid amount format."));
                    }
                    openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId());
                }));
                break;
            case 20:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage(player, "messages.no-permission-give", "You don't have permission to give."));
                    return;
                }
                openDigitalMenu(player, plugin.getMessage(player, "action.give", "Give"), result);
                break;
            case 22:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-permission", "You don't have permission!"));
                    return;
                }
                openDigitalMenu(player, plugin.getMessage(player, "action.take", "Take"), result);
                break;
            case 24:
                if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                    player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-permission", "You don't have permission!"));
                    return;
                }
                player.closeInventory();
                sendCancelablePrompt(player, plugin.getMessage(player, "gui.enter-amount", "Enter amount in chat:"));
                pendingActions.put(playerUUID, (msg, p) -> ServerScheduler.runTask(plugin, p, () -> {
                    if (isCancelWord(msg)) {
                        p.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "messages.input-cancelled", "Input cancelled."));
                        openFinanceMenu(p, result); pendingActions.remove(p.getUniqueId()); return;
                    }
                    try {
                        double amount = Double.parseDouble(msg.trim());
                        if (!validateAmount(p, amount, true)) { openFinanceMenu(p, result); return; }
                        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
                        if (plugin.getEconomyBridge().setBalance(target.getUniqueId(), target.getName(), amount)) {
                            p.sendMessage(ChatColor.GREEN + plugin.getMessage(player, "messages.set-amount", "Set balance to $%amount% for %player%", "amount", String.format(moneyFormat, amount), "player", result.name));
                            plugin.getTransactionHandler().log(result.uuid, result.name, "set", amount, p);
                        } else {
                            p.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.action-failed", "Action failed."));
                        }
                    } catch (NumberFormatException e) {
                        p.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.invalid-amount-format", "Invalid amount format."));
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
                player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-permission", "You don't have permission!"));
                return;
            }
            double[] amounts = {100, 1000, 100, 1000, 5000, 0};
            String[] actions = {"give", "give", "take", "take", "set", "set"};
            executeQuickAction(player, result, actions[slot - 10], amounts[slot - 10]);
        } else if (slot == 16 && plugin.isQuickActionsEnabled()) {
            if (!player.hasPermission("economygui.give") && !player.hasPermission("economygui.admin")) {
                player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-permission", "You don't have permission!"));
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
        String action = pendingActionType.getOrDefault(playerUUID, plugin.getMessage(player, "action.give", "Give"));

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
            sendCancelablePrompt(player, plugin.getMessage(player, "gui.enter-custom", "Enter custom amount:"));
            pendingActions.put(playerUUID, (msg, p) -> ServerScheduler.runTask(plugin, p, () -> {
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
                    p.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.invalid-amount-format", "Invalid amount format."));
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
            if (page > 0) {
                lastPage.put(playerUUID, page - 1);
                openHistoryMenu(player, result);
            }
        } else if (slot == 53 && result != null) {
            int page = lastPage.getOrDefault(playerUUID, 0);
            plugin.getTransactionHandler().getHistoryAsync(result.uuid).thenAccept(history -> {
                ServerScheduler.runTask(plugin, player, () -> {
                    int total = (history.size() / 45) + (history.size() % 45 > 0 ? 1 : 0);
                    if (page < total - 1) {
                        lastPage.put(playerUUID, page + 1);
                        openHistoryMenu(player, result);
                    }
                });
            });
        } else if (slot >= 0 && slot < 45 && result != null) {
            if (!player.hasPermission("economygui.refund") && !player.hasPermission("economygui.admin")) {
                return;
            }
            Long transactionId = visibleHistoryIds.getOrDefault(playerUUID, Collections.emptyMap()).get(slot);
            if (transactionId == null) return;
            plugin.getTransactionHandler().getTransactionByIdAsync(transactionId).thenAccept(t -> {
                ServerScheduler.runTask(plugin, player, () -> {
                    if (t != null) openRefundMenu(player, t, result);
                });
            });
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

    private void handleMassConfirmClick(InventoryClickEvent event, Player player, UUID playerUUID) {
        int slot = event.getSlot();
        if (slot == 11) {
            playSound(player);
            String actionType = pendingActionType.getOrDefault(playerUUID, "");
            double amount = pendingActionAmount.getOrDefault(playerUUID, 0.0);
            Set<UUID> selected = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
            if (actionType.isEmpty() || selected.isEmpty()) {
                pendingActionType.remove(playerUUID);
                pendingActionAmount.remove(playerUUID);
                playerSelections.remove(playerUUID);
                openMassActionsMenu(player);
                return;
            }
            executeMassOperation(player, actionType, amount, selected);
            pendingActionType.remove(playerUUID);
            pendingActionAmount.remove(playerUUID);
            playerSelections.remove(playerUUID);
            openMassActionsMenu(player);
        } else if (slot == 15) {
            playSound(player);
            pendingActionType.remove(playerUUID);
            pendingActionAmount.remove(playerUUID);
            playerSelections.remove(playerUUID);
            openMassActionsMenu(player);
        }
    }

    private void executeMassOperation(Player player, String actionType, double amount, Set<UUID> selected) {
        EconomyBridge bridge = plugin.getEconomyBridge();
        int count = 0;
        for (UUID uuid : selected) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            if (target.getName() == null) continue;
            boolean success = false;
            if (actionType.equals("mass-give")) {
                success = bridge.deposit(uuid, target.getName(), amount);
                if (success) plugin.getTransactionHandler().log(uuid.toString(), target.getName(), "give", amount, player);
            } else if (actionType.equals("mass-take")) {
                if (bridge.getBalance(uuid, target.getName()) >= amount) {
                    success = bridge.withdraw(uuid, target.getName(), amount);
                    if (success) plugin.getTransactionHandler().log(uuid.toString(), target.getName(), "take", amount, player);
                }
            } else if (actionType.equals("mass-set")) {
                success = bridge.setBalance(uuid, target.getName(), amount);
                if (success) plugin.getTransactionHandler().log(uuid.toString(), target.getName(), "set", amount, player);
            }
            if (success) count++;
        }
        String actionName = actionType.replace("mass-", "");
        player.sendMessage(ChatColor.GREEN + plugin.getMessage(player, "messages.mass-action-applied", "Applied %action% on $%amount% to %count% players", "action", actionName, "amount", String.format(moneyFormat, amount), "count", String.valueOf(count)));
    }

    private void checkMassPermAndRequest(Player player, UUID playerUUID, String action, String perm) {
        if (!player.hasPermission(perm) && !player.hasPermission("economygui.admin")) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-permission", "You don't have permission!"));
            return;
        }
        Set<UUID> selected = playerSelections.getOrDefault(playerUUID, Collections.emptySet());
        if (selected.isEmpty()) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-players-selected", "No players selected for mass operation!"));
            return;
        }
        requestMassAmount(player, playerUUID, action);
    }

    private void executeAction(Player admin, PlayerResult result, String action, double amount) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(UUID.fromString(result.uuid));
        EconomyBridge bridge = plugin.getEconomyBridge();
        boolean success = false;
        String msgAction = action.toLowerCase();
        if (msgAction.equals(plugin.getMessage(admin, "action.give", "Give").toLowerCase()) || msgAction.equals("give")) {
            success = bridge.deposit(target.getUniqueId(), target.getName(), amount);
            if (success) plugin.getTransactionHandler().log(result.uuid, target.getName(), "give", amount, admin);
        } else if (msgAction.equals(plugin.getMessage(admin, "action.take", "Take").toLowerCase()) || msgAction.equals("take")) {
            if (bridge.getBalance(target.getUniqueId(), target.getName()) >= amount) {
                success = bridge.withdraw(target.getUniqueId(), target.getName(), amount);
                if (success) plugin.getTransactionHandler().log(result.uuid, target.getName(), "take", amount, admin);
            } else {
                admin.sendMessage(ChatColor.RED + plugin.getMessage(admin, "error.insufficient-funds", "Insufficient funds for %player%.", "player", result.name));
                return;
            }
        } else if (msgAction.equals(plugin.getMessage(admin, "action.set", "Set").toLowerCase()) || msgAction.equals("set")) {
            success = bridge.setBalance(target.getUniqueId(), target.getName(), amount);
            if (success) plugin.getTransactionHandler().log(result.uuid, target.getName(), "set", amount, admin);
        }

        if (success) {
            admin.sendMessage(ChatColor.GREEN + plugin.getMessage(admin, "action.executed", "Executed %action% of $%amount% for %player%", "action", action, "amount", String.format(moneyFormat, amount), "player", result.name));
        } else {
            admin.sendMessage(ChatColor.RED + plugin.getMessage(admin, "error.action-failed", "Action failed."));
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
        sendCancelablePrompt(player, plugin.getMessage(player, "messages.enter-amount", "Enter amount for %action% (or 'cancel' to abort):", "action", action));
        pendingActions.put(player.getUniqueId(), (msg, p) -> ServerScheduler.runTask(plugin, p, () -> {
            if (isCancelWord(msg)) {
                p.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "messages.action-cancelled", "Action cancelled."));
                openMainGUI(p); pendingActions.remove(p.getUniqueId()); return;
            }
            try {
                double amount = Double.parseDouble(msg.trim());
                if (!validateAmount(p, amount, false)) { openMainGUI(p); return; }
                executeAction(p, result, action, amount);
            } catch (NumberFormatException e) {
                p.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.invalid-amount-format", "Invalid amount format."));
            }
            openMainGUI(p); pendingActions.remove(p.getUniqueId());
        }));
        pendingActionType.put(player.getUniqueId(), action);
        pendingActionTarget.put(player.getUniqueId(), result);
    }

    private void requestMassAmount(Player player, UUID playerUUID, String action) {
        player.closeInventory();
        sendCancelablePrompt(player, plugin.getMessage(player, "gui.enter-amount", "Enter amount:"));
        pendingActions.put(playerUUID, (msg, p) -> ServerScheduler.runTask(plugin, p, () -> {
            if (isCancelWord(msg)) {
                p.sendMessage(ChatColor.YELLOW + plugin.getMessage(player, "messages.action-cancelled", "Action cancelled."));
                openMassActionsMenu(p);
                pendingActions.remove(p.getUniqueId());
                return;
            }
            handleMassChatInput(p, action, msg);
            pendingActions.remove(p.getUniqueId());
        }));
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
                player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.no-players-selected", "No players selected for mass operation!"));
                openMassActionsMenu(player); return;
            }
            showMassConfirmation(player, actionType, amount, selected);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.invalid-amount-format", "Invalid amount format."));
            openMassActionsMenu(player);
        }
    }

    private void showMassConfirmation(Player player, String actionType, double amount, Set<UUID> selected) {
        String actionName = actionType.replace("mass-", "");
        String title = ChatColor.YELLOW + plugin.getMessage(player, "messages.confirm-mass-action", "Confirm %action% of $%amount% on %count% players?", "action", actionName, "amount", String.format(moneyFormat, amount), "count", String.valueOf(selected.size()));
        Inventory inv = Bukkit.createInventory(this, 27, title);
        inv.setItem(11, itemFactory.button(player, Material.LIME_TERRACOTTA, ChatColor.GREEN, "messages.confirm", "Confirm", "gui.click-to-execute", "Click to execute action"));
        inv.setItem(15, itemFactory.button(player, Material.RED_TERRACOTTA, ChatColor.RED, "messages.cancel", "Cancel", "gui.close", "Cancel operation"));
        player.openInventory(inv);
        pendingActionType.put(player.getUniqueId(), actionType);
        pendingActionAmount.put(player.getUniqueId(), amount);
        playerSelections.put(player.getUniqueId(), new HashSet<>(selected));
        lastOpenedMenu.put(player.getUniqueId(), "mass-confirm");
    }

    public void startBalancePolling() {
        ServerScheduler.runTaskTimerAsync(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                double cur = plugin.safeGetBalance(p);
                lastKnownBalances.put(p.getUniqueId(), cur);
            }
        }, 100L, 100L);
    }

    public PlayerResult getPlayerResultByName(String name) {
        return getResultByName(name);
    }

    private PlayerResult getResultByName(String name) {
        if (name == null || name.isEmpty()) return null;
        PlayerResult r = cachedResults.stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (r != null) return r;
        return playerCache.getFiltered(name, Filter.ALL, Collections.emptySet()).stream().filter(p -> p.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private boolean validateAmount(Player player, double amount, boolean allowZero) {
        if (amount < 0) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.negative-amount", "Amount cannot be negative."));
            return false;
        }
        if (!allowZero && amount <= 0) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.invalid-amount", "Amount must be positive."));
            return false;
        }
        if (!plugin.isWithinMaxAmount(amount)) {
            player.sendMessage(ChatColor.RED + plugin.getMessage(player, "error.exceeds-max-amount", "Amount exceeds the server limit of $%max_amount%.", "max_amount", String.format(moneyFormat, plugin.maxAmount)));
            return false;
        }
        return true;
    }

    private boolean isCancelWord(String msg) {
        return msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("отмена");
    }

    private void sendCancelablePrompt(Player player, String promptText) {
        ChatPrompts.send(player, ChatColor.YELLOW + promptText,
                ChatColor.RED + plugin.getMessage(player, "gui.cancel", "[Cancel]"));
    }

    private static Material materialOrFallback(String name, Material fallback) {
        Material material = Material.matchMaterial(name);
        return material != null ? material : fallback;
    }

    private void playSound(Player player) {
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(cachedResults.size() / 36.0));
    }

    private ItemStack createFilterItem(Player player) {
        Material mat;
        String nameKey;
        ChatColor color;
        switch (currentFilter) {
            case ONLINE:  mat = materialOrFallback("LIME_DYE", Material.EMERALD);  nameKey = "filter.online";  color = ChatColor.GREEN; break;
            case OFFLINE: mat = materialOrFallback("GRAY_DYE", Material.COAL);  nameKey = "filter.offline"; color = ChatColor.GRAY;  break;
            default:      mat = Material.COMPASS;   nameKey = "filter.all";     color = ChatColor.WHITE; break;
        }
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "filter.filter-current", "Current filter:"));
        lore.add("");
        lore.add(filterLine(player, Filter.ALL));
        lore.add(filterLine(player, Filter.ONLINE));
        lore.add(filterLine(player, Filter.OFFLINE));
        lore.add("");
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.click-cycle", "LMB → Next"));
        lore.add(ChatColor.GRAY + plugin.getMessage(player, "gui.right-click-cycle", "RMB → Previous"));
        return itemFactory.simple(mat, color + plugin.getMessage(player, nameKey, nameKey), lore);
    }

    private String filterLine(Player player, Filter f) {
        String name;
        ChatColor c;
        switch (f) {
            case ONLINE:  name = plugin.getMessage(player, "filter.online", "Online");  c = ChatColor.GREEN; break;
            case OFFLINE: name = plugin.getMessage(player, "filter.offline", "Offline"); c = ChatColor.GRAY; break;
            default:      name = plugin.getMessage(player, "filter.all", "All");         c = ChatColor.WHITE; break;
        }
        return (f == currentFilter) ? c + "» " + ChatColor.BOLD + name + " «" : "  " + c + name;
    }

    private ItemStack createGlobalStatsButton(Player player) {
        List<String> lore = Arrays.asList(ChatColor.GRAY + plugin.getMessage(player, "gui.click-to-view", "Click to view history"), "", ChatColor.GRAY + plugin.getMessage(player, "gui.global-stats-lore", "(Total balance, average, top players...)"));
        return itemFactory.simple(Material.BOOK, ChatColor.LIGHT_PURPLE + plugin.getMessage(player, "gui.global-stats", "Global Economy Stats"), lore);
    }

    private class MainMenuHolder implements InventoryHolder {
        private Inventory inventory;
        void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private class StatsMenuHolder implements InventoryHolder {
        private final Inventory inventory;
        StatsMenuHolder(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
