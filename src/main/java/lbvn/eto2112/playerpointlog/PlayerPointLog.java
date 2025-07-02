package lbvn.eto2112.playerpointlog;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.event.PlayerPointsChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerPointLog extends JavaPlugin implements Listener {

    private DatabaseManager databaseManager;
    private CommandHandler commandHandler;
    private LookupCommandHandler lookupCommandHandler;
    private LanguageManager languageManager;

    // Optimized concurrent map with initial capacity
    private final ConcurrentHashMap<String, String> pendingTransactions = new ConcurrentHashMap<>(16, 0.75f, 4);

    @Override
    public void onEnable() {
        // First-run setup: extract SQLite JAR and create default files
        setupPluginFiles();

        saveDefaultConfig();
        languageManager = new LanguageManager(this);

        if (!Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            getLogger().severe("PlayerPoints plugin not found! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Failed to initialize database! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        commandHandler = new CommandHandler(this);
        lookupCommandHandler = new LookupCommandHandler(this);

        getCommand("playerpointlog").setExecutor(commandHandler);
        getCommand("plog").setExecutor(lookupCommandHandler);

        getLogger().info("PlayerPointLog enabled!");
    }

    private void setupPluginFiles() {
        try {
            // Create plugin data folder
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

            // Extract SQLite JAR from resources to plugin folder
            File sqliteJar = new File(getDataFolder(), "sqlite-jdbc-3.45.2.0.jar");
            if (!sqliteJar.exists()) {
                extractResource("libs/sqlite-jdbc-3.45.2.0.jar", sqliteJar);
                getLogger().info("Extracted SQLite driver to plugin folder");
            }

        } catch (Exception e) {
            getLogger().severe("Failed to setup plugin files: " + e.getMessage());
        }
    }

    private void extractResource(String resourcePath, File targetFile) throws Exception {
        try (var inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                throw new Exception("Resource not found: " + resourcePath);
            }

            java.nio.file.Files.copy(inputStream, targetFile.toPath());
        }
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();

        // Quick check before expensive operations
        if (!message.contains(" pay ")) return;

        String command = message.toLowerCase();
        if (command.startsWith("/p pay ") || command.startsWith("/points pay ") ||
                command.startsWith("/playerpoints pay ")) {

            String[] args = message.split(" ", 4);
            if (args.length >= 3) {
                String targetPlayer = args[2].toLowerCase();
                String senderName = getConfig().getBoolean("use-player-name", true) ?
                        event.getPlayer().getName() : event.getPlayer().getUniqueId().toString();

                pendingTransactions.put(targetPlayer, senderName);

                // Auto-cleanup after 3 seconds
                getServer().getScheduler().runTaskLater(this, () ->
                        pendingTransactions.remove(targetPlayer), 60L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPointsChange(PlayerPointsChangeEvent event) {
        if (!getConfig().getBoolean("log-console", true)) return;

        boolean usePlayerName = getConfig().getBoolean("use-player-name", true);
        Player player = Bukkit.getPlayer(event.getPlayerId());
        String playerIdentifier = usePlayerName && player != null ?
                player.getName() : event.getPlayerId().toString();

        int change = event.getChange();

        if (change > 0) {
            // Player received points
            String sender = pendingTransactions.remove(playerIdentifier.toLowerCase());
            if (sender == null) sender = "console";

            final String finalSender = sender;
            getServer().getScheduler().runTaskAsynchronously(this, () ->
                    databaseManager.logTransaction(playerIdentifier, finalSender, change));

        } else if (change < 0 && getConfig().getBoolean("log-take", true)) {
            // Player lost points
            final int amount = -change;
            getServer().getScheduler().runTaskAsynchronously(this, () ->
                    databaseManager.logTransaction("console", playerIdentifier, amount));
        }
    }

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public LanguageManager getLanguageManager() { return languageManager; }

    public void reload() {
        reloadConfig();
        languageManager.reload();
        pendingTransactions.clear();

        if (databaseManager != null) {
            databaseManager.close();
        }

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Failed to reinitialize database during reload!");
        }
    }
}