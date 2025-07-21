package lbvn.eto2112.playerpointlog;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final PlayerPointLog plugin;
    private static final List<String> SUBCOMMANDS = Arrays.asList("reload", "status", "help", "lookup");

    public CommandHandler(PlayerPointLog plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playerpointlog.admin")) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "reload":
                handleReloadAsync(sender);
                break;
            case "status":
                handleStatusAsync(sender);
                break;
            case "help":
                sendHelpMessage(sender);
                break;
            case "lookup":
                handleLookupAsync(sender, args);
                break;
            default:
                sender.sendMessage(plugin.getLanguageManager().getMessage("unknown-command"));
                break;
        }

        return true;
    }

    private void handleLookupAsync(CommandSender sender, String[] args) {
        // Check permissions for lookup command
        if (!sender.hasPermission("playerpointlog.admin") && !sender.hasPermission("playerpointlog.use")) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-usage"));
            return;
        }

        String playerName = args[1];
        int page = 1;

        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                if (page < 1) {
                    sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-page"));
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-number"));
                return;
            }
        }

        final int finalPage = page;

        // Use CompletableFuture for better async handling
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        List<DatabaseManager.TransactionRecord> transactions =
                                plugin.getDatabaseManager().getPlayerTransactions(playerName, finalPage, 5);
                        int totalTransactions = plugin.getDatabaseManager().getTotalTransactionCount(playerName);
                        return new LookupResult(transactions, totalTransactions, null);
                    } catch (Exception e) {
                        return new LookupResult(new ArrayList<>(), 0, e.getMessage());
                    }
                }, plugin.getDatabaseExecutor())
                .thenAcceptAsync(result -> {
                    if (result.error != null) {
                        sender.sendMessage("§cError retrieving transaction data: " + result.error);
                    } else {
                        displayTransactions(sender, playerName, result.transactions, finalPage, result.totalTransactions);
                    }
                }, runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable));
    }

    private void displayTransactions(CommandSender sender, String playerName,
                                     List<DatabaseManager.TransactionRecord> transactions,
                                     int page, int totalTransactions) {
        if (transactions.isEmpty()) {
            if (page == 1) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-no-history", "player", playerName));
            } else {
                sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-no-page", "page", page, "player", playerName));
            }
            return;
        }

        int totalPages = (int) Math.ceil((double) totalTransactions / 5);

        // Header
        sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-header", "player", playerName));

        // Transaction list with optimized formatting
        for (DatabaseManager.TransactionRecord record : transactions) {
            String formattedTime = formatTimestamp(record.getTimestamp());
            String message;

            if (record.getPlayerReceived().equals("console")) {
                // Console received points from player (player lost points)
                message = plugin.getLanguageManager().getMessage("transaction-lost",
                        "time", formattedTime,
                        "player", record.getPlayerSend(),
                        "amount", record.getPointsAmount(),
                        "receiver", "console"
                );
            } else {
                // Player received points
                message = plugin.getLanguageManager().getMessage("transaction-received",
                        "time", formattedTime,
                        "player", record.getPlayerReceived(),
                        "amount", record.getPointsAmount(),
                        "sender", record.getPlayerSend()
                );
            }
            sender.sendMessage(message);
        }

        // Pagination footer
        sender.sendMessage(plugin.getLanguageManager().getMessage("pagination-simple", "page", page, "total", totalPages));
    }

    private String formatTimestamp(String timestamp) {
        try {
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.parse(timestamp,
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        } catch (java.time.format.DateTimeParseException e) {
            return timestamp;
        }
    }

    private void handleReloadAsync(CommandSender sender) {
        sender.sendMessage(plugin.getLanguageManager().getMessage("reloading"));

        // Use CompletableFuture for better async handling
        CompletableFuture
                .runAsync(() -> {
                    try {
                        plugin.reload();
                        // Clean up expired transactions during reload
                        plugin.cleanupExpiredTransactions();
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error during reload: " + e.getMessage());
                        throw new RuntimeException("Reload failed: " + e.getMessage());
                    }
                }, plugin.getDatabaseExecutor())
                .thenRunAsync(() -> {
                    sender.sendMessage(plugin.getLanguageManager().getMessage("plugin-reloaded"));
                }, runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable))
                .exceptionally(throwable -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§cReload failed: " + throwable.getMessage());
                    });
                    return null;
                });
    }

    private void handleStatusAsync(CommandSender sender) {
        // Quick status check without blocking main thread
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        boolean dbConnected = plugin.getDatabaseManager().isConnected();
                        return new StatusResult(dbConnected, null);
                    } catch (Exception e) {
                        return new StatusResult(false, e.getMessage());
                    }
                }, plugin.getDatabaseExecutor())
                .thenAcceptAsync(result -> {
                    sender.sendMessage(plugin.getLanguageManager().getMessage("status-header"));
                    sender.sendMessage(plugin.getLanguageManager().getMessage("status-version", "version", plugin.getDescription().getVersion()));

                    String dbStatus;
                    if (result.error != null) {
                        dbStatus = plugin.getLanguageManager().getMessage("status-disconnected") + " (" + result.error + ")";
                    } else {
                        dbStatus = result.connected ?
                                plugin.getLanguageManager().getMessage("status-connected") :
                                plugin.getLanguageManager().getMessage("status-disconnected");
                    }
                    sender.sendMessage(plugin.getLanguageManager().getMessage("status-database", "status", dbStatus));
                }, runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable));
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-header"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-reload"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-status"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-lookup"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-help"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("playerpointlog.admin")) {
            return new ArrayList<>();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(input)) {
                    completions.add(subcommand);
                }
            }
            return completions;
        } else if (args.length == 2 && "lookup".equalsIgnoreCase(args[0])) {
            // Only show player suggestions if user has permission
            if (sender.hasPermission("playerpointlog.admin") || sender.hasPermission("playerpointlog.use")) {
                String input = args[1].toLowerCase();
                plugin.getServer().getOnlinePlayers().forEach(player -> {
                    if (player.getName().toLowerCase().startsWith(input)) {
                        completions.add(player.getName());
                    }
                });
            }
        } else if (args.length == 3 && "lookup".equalsIgnoreCase(args[0])) {
            // Only show page numbers if user has permission
            if (sender.hasPermission("playerpointlog.admin") || sender.hasPermission("playerpointlog.use")) {
                for (int i = 1; i <= 5; i++) {
                    completions.add(String.valueOf(i));
                }
            }
        }

        return completions;
    }

    // Helper classes for better type safety and performance
    private static class LookupResult {
        final List<DatabaseManager.TransactionRecord> transactions;
        final int totalTransactions;
        final String error;

        LookupResult(List<DatabaseManager.TransactionRecord> transactions, int totalTransactions, String error) {
            this.transactions = transactions;
            this.totalTransactions = totalTransactions;
            this.error = error;
        }
    }

    private static class StatusResult {
        final boolean connected;
        final String error;

        StatusResult(boolean connected, String error) {
            this.connected = connected;
            this.error = error;
        }
    }
}