package ru.stepanyaa.economyGUI;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LanguageManager {

    private static final String[] SUPPORTED = {"en", "ru", "de", "fr", "pl", "pt", "tr"};
    private static final Set<String> SUPPORTED_SET = new HashSet<>(Arrays.asList(SUPPORTED));

    private final EconomyGUI plugin;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    private String defaultLanguage;

    public LanguageManager(EconomyGUI plugin) {
        this.plugin = plugin;
        this.defaultLanguage = normalizeLanguage(plugin.getConfig().getString("language", "en"), "en");
    }

    public void loadLanguages() {
        languages.clear();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        for (String lang : SUPPORTED) {
            String filename = "messages_" + lang + ".yml";
            File langFile = new File(langDir, filename);
            if (!langFile.exists()) {
                plugin.saveResource("lang/" + filename, false);
            } else {
                updateLanguageFile(langFile, filename);
            }
            languages.put(lang, YamlConfiguration.loadConfiguration(langFile));
        }
        plugin.getLogger().info("Languages loaded: " + languages.keySet());
    }

    private void updateLanguageFile(File langFile, String filename) {
        if (plugin.getResource("lang/" + filename) == null) {
            plugin.getLogger().warning("Resource lang/" + filename + " not found in plugin!");
            return;
        }

        try {
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(langFile);
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(plugin.getResource("lang/" + filename), StandardCharsets.UTF_8));
            String pluginVersion = plugin.getDescription().getVersion();
            boolean updated = false;

            for (String key : defaults.getKeys(true)) {
                if (!existing.contains(key)) {
                    existing.set(key, defaults.get(key));
                    updated = true;
                }
            }

            if (!existing.contains("config-version")) {
                updated = true;
            }
            existing.set("config-version", pluginVersion);
            existing.save(langFile);

            if (updated) {
                plugin.getLogger().info("Updated language file: " + filename + " to version " + pluginVersion);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to update language file " + filename + ": " + e.getMessage());
        }
    }

    public String resolveLanguage(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return defaultLanguage;
        }
        return resolveLanguage((Player) sender);
    }

    public String resolveLanguage(Player player) {
        if (player == null) {
            return defaultLanguage;
        }
        String language = normalizeLanguage(player.getLocale(), null);
        return language != null && languages.containsKey(language) ? language : defaultLanguage;
    }

    public String getMessage(String key, String defaultValue, String... replacements) {
        return getMessageForLang(defaultLanguage, key, defaultValue, replacements);
    }

    public String getMessage(CommandSender sender, String key, String defaultValue, String... replacements) {
        return getMessageForLang(resolveLanguage(sender), key, defaultValue, replacements);
    }

    public String getMessage(Player player, String key, String defaultValue, String... replacements) {
        return getMessageForLang(resolveLanguage(player), key, defaultValue, replacements);
    }

    public String getMessageForLang(String lang, String key, String defaultValue, String... replacements) {
        String normalized = normalizeLanguage(lang, defaultLanguage);
        FileConfiguration config = languages.getOrDefault(normalized, languages.get("en"));
        if (config == null) {
            return defaultValue;
        }
        String message = config.getString(key, defaultValue);
        if (message == null) {
            message = defaultValue;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace("%" + replacements[i] + "%", replacements[i + 1]);
        }
        return message;
    }

    public void setLanguage(String language) {
        String normalized = normalizeLanguage(language, null);
        if (normalized != null && languages.containsKey(normalized)) {
            this.defaultLanguage = normalized;
            plugin.getLogger().info("Default language set to: " + normalized);
        } else {
            plugin.getLogger().warning("Language " + language + " is not supported. Using: " + defaultLanguage);
        }
    }

    private String normalizeLanguage(String language, String fallback) {
        if (language == null || language.trim().isEmpty()) {
            return fallback;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        int separator = normalized.indexOf('_');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }
        return SUPPORTED_SET.contains(normalized) ? normalized : fallback;
    }

    public String getCurrentLanguage() {
        return defaultLanguage;
    }

    public void reload() {
        loadLanguages();
        setLanguage(plugin.getConfig().getString("language", "en"));
    }
}
