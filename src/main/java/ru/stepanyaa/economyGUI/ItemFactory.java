package ru.stepanyaa.economyGUI;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.*;

public class ItemFactory {

    private static final Random RANDOM = new Random();

    private static final String[] DEFAULT_SKIN_VALUES = {
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzEzMTVjYzVjNDQ1NDNlZjZmNTI5MjQzNzUwODM5NjU2ODI4Yjg4ZjMzNTQzYjU5M2VmOTA4ODY2NTc5IgogICAgfQogIH0KfQ==",
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjMzMTAwNWI4Mjg3ODE0OGU2Yzc1N2M2Njc0ODY4ZjZkNjM5Yjk3MmUyNzYwYTAxNDkwOThkNjY2ZDczNjM0IgogICAgfQogIH0KfQ==",
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzg5MjU5NzdhNDU2M2E2MmQzZTExMmQ3Yzg2NTE4N2UxZjQ0MGQ5OWVkNTBhMDQ5ZDVjODI5Nzc1MDgyNCIKICAgIH0KICB9Cn0=",
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjc4ODViNzM2YjZmMDAxNDc5OGU0ZDM1ZTE2M2I4NjhlNjQ3NTY1OGU2NTMwNmQ3MWIzMDY0NDkxNzY0IgogICAgfQogIH0KfQ==",
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZlNWYxZjk5YzE1ODVmNTQ4ZDhjOTFhMGY4NjU4OGE5MTM0NTljMzYzOTk0MzViNTQ2MzZkMTIwZjI2MiIKICAgIH0KICB9Cn0=",
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGEzMDU5NDM3OTc2ZTMzODFlNGI5ODY4YzY3YzVlNTMzYzY5MGYzMzk0NjI1ZjZlODY0ODE3YTEyODhjIgogICAgfQogIH0KfQ==",
            "ewogICJ0ZXh0dXJlcyI6IHsKICAgICJTS0lOIjogewogICAgICAidXJsIjogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmE5YTNmYjY4MDdmN2M0NjI3MTk2MDI1MDZlN2EyYjkwNGQ5ZTAzNWU0ZDJhMTM4Mzc5MjYxNzY1YzkyIgogICAgfQogIH0KfQ=="
    };

    private final EconomyGUI plugin;

    public ItemFactory(EconomyGUI plugin) {
        this.plugin = plugin;
    }

    public ItemStack playerHead(Player viewer, OfflinePlayer player, double balance, boolean selected, String moneyFormat) {
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
        lore.add(ChatColor.GOLD + plugin.getMessage(viewer, "gui.balance", "Balance: $%balance%", "balance", formattedBalance));
        lore.add(ChatColor.GRAY + plugin.getMessage(viewer, "gui.actions", "LMB: Manage | RMB: Quick Actions | Shift+Left: Select"));
        if (selected) {
            lore.add(ChatColor.GREEN + plugin.getMessage(viewer, "gui.selected", "Selected"));
            Enchantment glow = getGlowEnchantment();
            if (glow != null) meta.addEnchant(glow, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    public ItemStack backButton(Player viewer, String hintKey, String hintDef) {
        return simple(Material.ARROW, ChatColor.RED + plugin.getMessage(viewer, "gui.back", "Back"), Collections.singletonList(ChatColor.GRAY + plugin.getMessage(viewer, hintKey, hintDef)));
    }

    public ItemStack pageButton(Player viewer, boolean hasPage, boolean isNext, int currentPage, int totalPages) {
        Material mat = hasPage ? Material.ARROW : Material.RED_STAINED_GLASS_PANE;
        String name;
        if (hasPage) {
            name = ChatColor.YELLOW + plugin.getMessage(viewer, isNext ? "gui.next-page" : "gui.previous-page", isNext ? "Next Page" : "Previous Page");
        } else {
            name = ChatColor.RED + plugin.getMessage(viewer, "gui.no-page", "No Page");
        }
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + plugin.getMessage(viewer, "gui.page-info", "Page %current_page% of %total_pages%", "current_page", String.valueOf(currentPage + 1), "total_pages", String.valueOf(totalPages)));
        if (hasPage) {
            lore.add(ChatColor.GRAY + plugin.getMessage(viewer, "gui.shift-rmb-page", "Shift+RMB: Skip 5 pages"));
        }
        return simple(mat, name, lore);
    }

    public ItemStack button(Player viewer, Material mat, ChatColor nameColor, String nameKey, String nameDef, String hintKey, String hintDef) {
        return simple(mat, nameColor + plugin.getMessage(viewer, nameKey, nameDef), Collections.singletonList(ChatColor.GRAY + plugin.getMessage(viewer, hintKey, hintDef)));
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
        meta.setOwningPlayer(player);
        try {
            String value = DEFAULT_SKIN_VALUES[RANDOM.nextInt(DEFAULT_SKIN_VALUES.length)];
            String json = new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher url = java.util.regex.Pattern.compile("https?://textures\\.minecraft\\.net/texture/[a-zA-Z0-9]+").matcher(json);
            if (!url.find()) return;
            Class<?> profileType = Class.forName("org.bukkit.profile.PlayerProfile");
            Class<?> texturesType = Class.forName("org.bukkit.profile.PlayerTextures");
            Object profile = Bukkit.class.getMethod("createPlayerProfile", UUID.class, String.class)
                    .invoke(null, player.getUniqueId(), player.getName());
            Object textures = profileType.getMethod("getTextures").invoke(profile);
            texturesType.getMethod("setSkin", java.net.URL.class).invoke(textures, new java.net.URL(url.group()));
            profileType.getMethod("setTextures", texturesType).invoke(profile, textures);
            SkullMeta.class.getMethod("setOwnerProfile", profileType).invoke(meta, profile);
        } catch (ReflectiveOperationException | LinkageError | SecurityException | java.net.MalformedURLException unsupported) {
        }
    }

    private Enchantment getGlowEnchantment() {
        return Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("unbreaking"));
    }
}
