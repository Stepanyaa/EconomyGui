/**
 * MIT License
 * EconomyGui — Copyright (c) 2026 Stepanyaa
 */
package ru.stepanyaa.economyGUI;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;


public class ItemFactory {

    private static final Random RANDOM = new Random();

    private static final String[] DEFAULT_SKIN_VALUES = {
            // Steve (Classic)
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzEzMTVjYzVjNDQ1NDNlZjZmNTI5MjQzNzUwODM5NjU2ODI4Yjg4ZjMzNTQzYjU5M2VmOTA4ODY2NTc5IgogICAgfQogIH0KfQ==",
            // Alex (Slim)
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjMzMTAwNWI4Mjg3ODE0OGU2Yzc1N2M2Njc0ODY4ZjZkNjM5Yjk3MmUyNzYwYTAxNDkwOThkNjY2ZDczNjM0IgogICAgfQogIH0KfQ==",
            // Noor
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzg5MjU5NzdhNDU2M2E2MmQzZTExMmQ3Yzg2NTE4N2UxZjQ0MGQ5OWVkNTBhMDQ5ZDVjODI5Nzc1MDgyNCIKICAgIH0KICB9Cn0=",
            // Sunny
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjc4ODViNzM2YjZmMDAxNDc5OGU0ZDM1ZTE2M2I4NjhlNjQ3NTY1OGU2NTMwNmQ3MWIzMDY0NDkxNzY0IgogICAgfQogIH0KfQ==",
            // Ari
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZlNWYxZjk5YzE1ODVmNTQ4ZDhjOTFhMGY4NjU4OGE5MTM0NTljMzYzOTk0MzViNTQ2MzZkMTIwZjI2MiIKICAgIH0KICB9Cn0=",
            // Zuri
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGEzMDU5NDM3OTc2ZTMzODFlNGI5ODY4YzY3YzVlNTMzYzY5MGYzMzk0NjI1ZjZlODY0ODE3YTEyODhjIgogICAgfQogIH0KfQ==",
            // Kai
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmE5YTNmYjY4MDdmN2M0NjI3MTk2MDI1MDZlN2EyYjkwNGQ5ZTAzNWU0ZDJhMTM4Mzc5MjYxNzY1YzkyIgogICAgfQogIH0KfQ=="
    };

    private final EconomyGUI plugin;

    public ItemFactory(EconomyGUI plugin) {
        this.plugin = plugin;
    }

    public ItemStack playerHead(OfflinePlayer player, double balance, boolean selected, String moneyFormat) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (player.isOnline()) {
            meta.setOwningPlayer(player);
        } else {
            applyDefaultSkin(meta, player);
        }

        meta.setDisplayName(ChatColor.YELLOW + player.getName());

        List<String> lore = new ArrayList<>();
        String formattedBalance = String.format(moneyFormat, balance);
        lore.add(ChatColor.GOLD + plugin.getMessage("gui.balance", "Balance: $%balance%",
                "balance", formattedBalance));
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.actions",
                "LMB: Manage | RMB: Quick Actions | Shift+Left: Select"));
        if (selected) {
            lore.add(ChatColor.GREEN + plugin.getMessage("gui.selected", "Selected"));
            meta.addEnchant(getGlowEnchantment(), 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }
    public ItemStack backButton(String hintKey, String hintDef) {
        return simple(Material.ARROW,
                ChatColor.RED + plugin.getMessage("gui.back", "Back"),
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(hintKey, hintDef)));
    }
    public ItemStack pageButton(boolean hasPage, boolean isNext, int currentPage, int totalPages) {
        Material mat = hasPage ? Material.ARROW : Material.RED_STAINED_GLASS_PANE;
        String name;
        if (hasPage) {
            name = ChatColor.YELLOW + plugin.getMessage(isNext ? "gui.next-page" : "gui.previous-page",
                    isNext ? "Next Page" : "Previous Page");
        } else {
            name = ChatColor.RED + plugin.getMessage("gui.no-page", "No Page");
        }
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage("gui.page-info", "Page %current_page% of %total_pages%",
                "current_page", String.valueOf(currentPage + 1),
                "total_pages", String.valueOf(totalPages)));
        if (hasPage) {
            lore.add(ChatColor.GRAY + plugin.getMessage("gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
        }
        return simple(mat, name, lore);
    }

    public ItemStack button(Material mat, ChatColor nameColor, String nameKey, String nameDef,
                            String hintKey, String hintDef) {
        return simple(mat,
                nameColor + plugin.getMessage(nameKey, nameDef),
                Collections.singletonList(ChatColor.GRAY + plugin.getMessage(hintKey, hintDef)));
    }

    public ItemStack simple(Material mat, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        if (!lore.isEmpty()) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void applyDefaultSkin(SkullMeta meta, OfflinePlayer player) {
        String skinValue = DEFAULT_SKIN_VALUES[RANDOM.nextInt(DEFAULT_SKIN_VALUES.length)];
        GameProfile profile = new GameProfile(player.getUniqueId(), player.getName());
        try {
            Method getProperties = GameProfile.class.getDeclaredMethod("getProperties");
            getProperties.setAccessible(true);
            Object properties = getProperties.invoke(profile);
            Method put = properties.getClass().getMethod("put", Object.class, Object.class);
            put.invoke(properties, "textures", new Property("textures", skinValue));
            Field profileField = meta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(meta, profile);
        } catch (Exception ignored) {
        }
    }

    private Enchantment getGlowEnchantment() {
        String version = Bukkit.getBukkitVersion();
        return version.contains("1.16") ? Enchantment.DURABILITY : Enchantment.VANISHING_CURSE;
    }
}