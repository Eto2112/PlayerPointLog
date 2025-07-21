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
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerPointLog extends JavaPlugin implements Listener {

    private DatabaseManager databaseManager;
    private CommandHandler commandHandler;
    private LookupCommandHandler lookupCommandHandler;
    private LanguageManager languageManager;

    // High-performance concurrent structures
    private final ConcurrentHashMap<String, PendingTransaction> pendingTransactions =
            new ConcurrentHashMap<>(32, 0.75f, 8);

    // Dedicated thread pool for database operations
    private ExecutorService databaseExecutor;

    // Batched transaction queue for better performance
    private final BlockingQueue<DatabaseManager.TransactionData> transactionQueue = new LinkedBlockingQueue<>(1000);
    private BukkitTask batchProcessor;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // Configuration cache to avoid repeated file reads
    private volatile ConfigCache configCache;

    @Override
    public void onEnable() {
        setupPluginFiles();
        saveDefaultConfig();

        // Initialize configuration cache
        updateConfigCache();

        languageManager = new LanguageManager(this);

        if (!Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            getLogger().severe("PlayerPoints plugin not found! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize optimized thread pool for database operations
        databaseExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "PlayerPointLog-DB-Worker");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
        );

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Failed to initialize database! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Start batch processor for improved performance
        startBatchProcessor();

        getServer().getPluginManager().registerEvents(this, this);

        commandHandler = new CommandHandler(this);
        lookupCommandHandler = new LookupCommandHandler(this);

        getCommand("playerpointlog").setExecutor(commandHandler);
        getCommand("plog").setExecutor(lookupCommandHandler);

        getLogger().info("PlayerPointLog enabled with optimized performance!");
    }

    private void updateConfigCache() {
        configCache = new ConfigCache(
                getConfig().getBoolean("log-console", true),
                getConfig().getBoolean("log-take", true),
                getConfig().getBoolean("use-player-name", true)
        );
    }

    private void startBatchProcessor() {
        // Process transactions in batches every 50ms for optimal performance
        batchProcessor = getServer().getScheduler().runTaskTimerAsynchronously(this,
                this::processBatchedTransactions, 1L, 1L);
    }

    private void processBatchedTransactions() {
        if (isShuttingDown.get() || transactionQueue.isEmpty()) return;

        // Process up to 50 transactions per batch to balance performance and responsiveness
        final int maxBatchSize = 50;
        final DatabaseManager.TransactionData[] batch = new DatabaseManager.TransactionData[maxBatchSize];
        int batchSize = 0;

        // Drain available transactions into batch
        while (batchSize < maxBatchSize) {
            DatabaseManager.TransactionData transaction = transactionQueue.poll();
            if (transaction == null) break;
            batch[batchSize++] = transaction;
        }

        if (batchSize == 0) return;

        // Submit batch to database executor
        final int finalBatchSize = batchSize;
        databaseExecutor.submit(() -> {
            try {
                databaseManager.logTransactionsBatch(batch, finalBatchSize);
            } catch (Exception e) {
                getLogger().severe("Error processing transaction batch: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();

        // Ultra-fast pre-filtering
        if (message.length() < 8 || !message.contains(" pay ")) return;

        String command = message.toLowerCase();
        if (!(command.startsWith("/p pay ") || command.startsWith("/points pay ") ||
                command.startsWith("/playerpoints pay "))) return;

        // Efficient argument parsing
        String[] args = message.split(" ", 4);
        if (args.length < 3) return;

        String targetPlayer = args[2].toLowerCase();
        String senderName = configCache.usePlayerName ?
                event.getPlayer().getName() : event.getPlayer().getUniqueId().toString();

        // Store with expiration timestamp for automatic cleanup
        long expirationTime = System.currentTimeMillis() + 3000; // 3 seconds
        pendingTransactions.put(targetPlayer,
                new PendingTransaction(senderName, expirationTime));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPointsChange(PlayerPointsChangeEvent event) {
        if (!configCache.logConsole) return;

        Player player = Bukkit.getPlayer(event.getPlayerId());
        String playerIdentifier = configCache.usePlayerName && player != null ?
                player.getName() : event.getPlayerId().toString();

        int change = event.getChange();
        long currentTime = System.currentTimeMillis();

        if (change > 0) {
            // Player received points - check for pending transaction
            String lowerPlayerName = playerIdentifier.toLowerCase();
            PendingTransaction pending = pendingTransactions.get(lowerPlayerName);

            String sender = "console";
            if (pending != null && pending.expirationTime > currentTime) {
                sender = pending.senderName;
                pendingTransactions.remove(lowerPlayerName);
            }

            // Queue transaction for batched processing
            queueTransaction(new DatabaseManager.TransactionData(playerIdentifier, sender, change));

        } else if (change < 0 && configCache.logTake) {
            // Player lost points
            int amount = -change;
            queueTransaction(new DatabaseManager.TransactionData("console", playerIdentifier, amount));
        }
    }

    private void queueTransaction(DatabaseManager.TransactionData transaction) {
        if (isShuttingDown.get()) return;

        // Non-blocking queue insertion with fallback
        if (!transactionQueue.offer(transaction)) {
            // Queue is full, process immediately to prevent data loss
            databaseExecutor.submit(() -> {
                try {
                    databaseManager.logTransaction(
                            transaction.playerReceived,
                            transaction.playerSend,
                            transaction.pointsAmount
                    );
                } catch (Exception e) {
                    getLogger().severe("Error logging immediate transaction: " + e.getMessage());
                }
            });
        }
    }

    // Clean up expired pending transactions periodically
    public void cleanupExpiredTransactions() {
        if (pendingTransactions.isEmpty()) return;

        long currentTime = System.currentTimeMillis();
        pendingTransactions.entrySet().removeIf(entry ->
                entry.getValue().expirationTime <= currentTime);
    }

    @Override
    public void onDisable() {
        isShuttingDown.set(true);

        // Cancel batch processor
        if (batchProcessor != null) {
            batchProcessor.cancel();
        }

        // Process remaining queued transactions
        processRemainingTransactions();

        // Shutdown executor gracefully
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    databaseExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                databaseExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private void processRemainingTransactions() {
        getLogger().info("Processing remaining " + transactionQueue.size() + " transactions...");

        DatabaseManager.TransactionData transaction;
        while ((transaction = transactionQueue.poll()) != null) {
            try {
                databaseManager.logTransaction(
                        transaction.playerReceived,
                        transaction.playerSend,
                        transaction.pointsAmount
                );
            } catch (Exception e) {
                getLogger().warning("Failed to process final transaction: " + e.getMessage());
            }
        }
    }

    private void setupPluginFiles() {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

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

    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public LanguageManager getLanguageManager() { return languageManager; }
    public ExecutorService getDatabaseExecutor() { return databaseExecutor; }

    public void reload() {
        // Async reload to prevent server lag
        databaseExecutor.submit(() -> {
            try {
                // Update configuration
                reloadConfig();
                updateConfigCache();

                languageManager.reload();
                pendingTransactions.clear();

                if (databaseManager != null) {
                    databaseManager.close();
                }

                databaseManager = new DatabaseManager(this);
                if (!databaseManager.initialize()) {
                    getLogger().severe("Failed to reinitialize database during reload!");
                }
            } catch (Exception e) {
                getLogger().severe("Error during reload: " + e.getMessage());
            }
        });
    }

    // Inner classes for better performance and memory usage
    private static class PendingTransaction {
        final String senderName;
        final long expirationTime;

        PendingTransaction(String senderName, long expirationTime) {
            this.senderName = senderName;
            this.expirationTime = expirationTime;
        }
    }

    private static class ConfigCache {
        final boolean logConsole;
        final boolean logTake;
        final boolean usePlayerName;

        ConfigCache(boolean logConsole, boolean logTake, boolean usePlayerName) {
            this.logConsole = logConsole;
            this.logTake = logTake;
            this.usePlayerName = usePlayerName;
        }
    }
}