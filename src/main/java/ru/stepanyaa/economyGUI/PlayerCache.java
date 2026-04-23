/**
 * MIT License
 * EconomyGui — Copyright (c) 2026 Stepanyaa
 */
package ru.stepanyaa.economyGUI;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PlayerCache implements Listener {


    static class CachedPlayer {
        final UUID uuid;
        final String name;
        volatile boolean online;

        CachedPlayer(UUID uuid, String name, boolean online) {
            this.uuid = uuid;
            this.name = name;
            this.online = online;
        }
    }

    private final Map<UUID, CachedPlayer> cache = new ConcurrentHashMap<>();
    private final EconomyGUI plugin;

    public PlayerCache(EconomyGUI plugin) {
        this.plugin = plugin;
        rebuild();
    }

    public List<EconomySearchGUI.PlayerResult> getFiltered(String search,
                                                           EconomySearchGUI.Filter filter,
                                                           Set<UUID> selected) {
        String q = search.toLowerCase(Locale.ROOT);
        return cache.values().stream()
                .filter(cp -> {
                    if (cp.name == null) return false;
                    if (!q.isEmpty() && !cp.name.toLowerCase(Locale.ROOT).contains(q)
                            && !cp.uuid.toString().contains(q)) return false;
                    return matchesFilter(cp, filter, selected);
                })
                .map(cp -> {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(cp.uuid);
                    double balance = plugin.getEconomy().getBalance(op);
                    return new EconomySearchGUI.PlayerResult(cp.uuid.toString(), cp.name, cp.online, balance);
                })
                .sorted(Comparator.comparingDouble((EconomySearchGUI.PlayerResult r) -> r.balance).reversed())
                .collect(Collectors.toList());
    }

    public List<EconomySearchGUI.PlayerResult> getAll() {
        return cache.values().stream()
                .filter(cp -> cp.name != null)
                .map(cp -> {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(cp.uuid);
                    double balance = plugin.getEconomy().getBalance(op);
                    return new EconomySearchGUI.PlayerResult(cp.uuid.toString(), cp.name, cp.online, balance);
                })
                .collect(Collectors.toList());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        CachedPlayer cp = cache.get(p.getUniqueId());
        if (cp != null) {
            cp.online = true;
        } else {
            cache.put(p.getUniqueId(), new CachedPlayer(p.getUniqueId(), p.getName(), true));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CachedPlayer cp = cache.get(event.getPlayer().getUniqueId());
        if (cp != null) cp.online = false;
    }

    public void rebuild() {
        cache.clear();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null) continue;
            cache.put(op.getUniqueId(), new CachedPlayer(op.getUniqueId(), op.getName(), op.isOnline()));
        }
    }

    private boolean matchesFilter(CachedPlayer cp, EconomySearchGUI.Filter filter, Set<UUID> selected) {
        switch (filter) {
            case ONLINE:   return cp.online;
            case OFFLINE:  return !cp.online;
            case SELECTED: return selected.contains(cp.uuid);
            default:       return true;
        }
    }
}