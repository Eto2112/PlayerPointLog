package lbvn.eto2112.playerpointlog;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final PlayerPointLog plugin;
    private FileConfiguration languageConfig;
    private final Map<String, String> messageCache = new HashMap<>();

    public LanguageManager(PlayerPointLog plugin) {
        this.plugin = plugin;
        loadLanguageFile();
    }

    private void loadLanguageFile() {
        File languageFile = new File(plugin.getDataFolder(), "language.yml");

        // Create language.yml if it doesn't exist
        if (!languageFile.exists()) {
            try {
                // Create plugin data folder if it doesn't exist
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }

                // Copy default language.yml from resources
                try (InputStream inputStream = plugin.getResource("language.yml")) {
                    if (inputStream != null) {
                        Files.copy(inputStream, languageFile.toPath());
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create language.yml: " + e.getMessage());
            }
        }

        // Load the configuration
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);

        // Cache all messages for performance
        cacheMessages();

        plugin.getLogger().info("Language file loaded successfully!");
    }

    private void cacheMessages() {
        messageCache.clear();
        for (String key : languageConfig.getKeys(true)) {
            if (languageConfig.isString(key)) {
                String message = languageConfig.getString(key);
                if (message != null) {
                    messageCache.put(key, ChatColor.translateAlternateColorCodes('&', message));
                }
            }
        }
    }

    public String getMessage(String key, Object... placeholders) {
        String message = messageCache.get(key);
        if (message == null) {
            plugin.getLogger().warning("Missing language key: " + key);
            return ChatColor.RED + "Missing message: " + key;
        }

        // Replace placeholders
        if (placeholders.length > 0) {
            for (int i = 0; i < placeholders.length; i += 2) {
                if (i + 1 < placeholders.length) {
                    String placeholder = "{" + placeholders[i] + "}";
                    String replacement = String.valueOf(placeholders[i + 1]);
                    message = message.replace(placeholder, replacement);
                }
            }
        }

        return message;
    }

    public void reload() {
        loadLanguageFile();
    }
}