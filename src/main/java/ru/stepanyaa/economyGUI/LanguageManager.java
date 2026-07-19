package ru.stepanyaa.economyGUI;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final EconomyGUI plugin;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    private String currentLanguage;

    public LanguageManager(EconomyGUI plugin) {
        this.plugin = plugin;
        this.currentLanguage = plugin.getConfig().getString("language", "en");
    }

    public void loadLanguages() {
        languages.clear();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
        String[] supportedLanguages = {"en", "ru", "de", "fr", "pl", "pt", "tr"};

        for (String lang : supportedLanguages) {
            String filename = "messages_" + lang + ".yml";
            File langFile = new File(langDir, filename);
            if (!langFile.exists()) {
                plugin.saveResource("lang/" + filename, false);
            } else {
                updateLanguageFile(langFile, filename);
            }
            FileConfiguration config = YamlConfiguration.loadConfiguration(langFile);
            languages.put(lang, config);
        }
        plugin.getLogger().info("Languages loaded: " + languages.keySet());
    }

    private void updateLanguageFile(File langFile, String filename) {
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(langFile);
        String fileVersion = existing.getString("config-version", "0.0.0");
        String pluginVersion = plugin.getDescription().getVersion();

        if (fileVersion.equals(pluginVersion)) {
            return;
        }

        if (plugin.getResource("lang/" + filename) == null) {
            plugin.getLogger().warning("Resource lang/" + filename + " not found in plugin!");
            return;
        }

        try {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(plugin.getResource("lang/" + filename), StandardCharsets.UTF_8));
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

    public String getMessage(String key, String defaultValue, String... replacements) {
        FileConfiguration config = languages.getOrDefault(currentLanguage, languages.get("en"));
        if (config == null) {
            return defaultValue;
        }
        String message = config.getString(key, defaultValue);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                String param = replacements[i];
                String value = replacements[i + 1];
                message = message.replace("%" + param + "%", value);
            }
        }
        return message;
    }

    public void setLanguage(String language) {
        if (languages.containsKey(language)) {
            this.currentLanguage = language;
            plugin.getLogger().info("Language changed to: " + language);
        } else {
            plugin.getLogger().warning("The language " + language + " is not supported. Using: " + currentLanguage);
        }
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    public void reload() {
        loadLanguages();
        String newLanguage = plugin.getConfig().getString("language", "en");
        setLanguage(newLanguage);
    }
}