package lbvn.eto2112.playerpointlog;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final PlayerPointLog plugin;

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

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(sender);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "help":
                sendHelpMessage(sender);
                break;
            case "lookup":
                handleLookup(sender, args);
                break;
            default:
                sender.sendMessage(plugin.getLanguageManager().getMessage("unknown-command"));
                break;
        }

        return true;
    }

    private void handleLookup(CommandSender sender, String[] args) {
        // Check permissions for lookup command
        if (!sender.hasPermission("playerpointlog.admin") && !sender.hasPermission("playerpointlog.use")) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-usage"));
            return;
        }

        final String finalPlayerName = args[1];
        int tempPage = 1;

        if (args.length >= 3) {
            try {
                tempPage = Integer.parseInt(args[2]);
                if (tempPage < 1) {
                    sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-page"));
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-number"));
                return;
            }
        }

        final int finalPage = tempPage;

        // Run database query asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            java.util.List<DatabaseManager.TransactionRecord> transactions = plugin.getDatabaseManager()
                    .getPlayerTransactions(finalPlayerName, finalPage, 5);
            int totalTransactions = plugin.getDatabaseManager().getTotalTransactionCount(finalPlayerName);

            // Send results back on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                displayTransactions(sender, finalPlayerName, transactions, finalPage, totalTransactions);
            });
        });
    }

    private void displayTransactions(CommandSender sender, String playerName,
                                     java.util.List<DatabaseManager.TransactionRecord> transactions,
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

        // Transaction list
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
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        } catch (java.time.format.DateTimeParseException e) {
            return timestamp;
        }
    }

    private void handleReload(CommandSender sender) {
        sender.sendMessage(plugin.getLanguageManager().getMessage("reloading"));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.reload();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(plugin.getLanguageManager().getMessage("plugin-reloaded"));
            });
        });
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(plugin.getLanguageManager().getMessage("status-header"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("status-version", "version", plugin.getDescription().getVersion()));

        boolean dbConnected = plugin.getDatabaseManager().isConnected();
        String dbStatus = dbConnected ?
                plugin.getLanguageManager().getMessage("status-connected") :
                plugin.getLanguageManager().getMessage("status-disconnected");
        sender.sendMessage(plugin.getLanguageManager().getMessage("status-database", "status", dbStatus));
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
            List<String> subcommands = Arrays.asList("reload", "status", "help", "lookup");

            for (String subcommand : subcommands) {
                if (subcommand.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(subcommand);
                }
            }

            return completions;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lookup")) {
            // Only show player suggestions if user has permission
            if (sender.hasPermission("playerpointlog.admin") || sender.hasPermission("playerpointlog.use")) {
                plugin.getServer().getOnlinePlayers().forEach(player -> {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(player.getName());
                    }
                });
            }
            return completions;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("lookup")) {
            // Only show page numbers if user has permission
            if (sender.hasPermission("playerpointlog.admin") || sender.hasPermission("playerpointlog.use")) {
                for (int i = 1; i <= 5; i++) {
                    completions.add(String.valueOf(i));
                }
            }
            return completions;
        }

        return new ArrayList<>();
    }
}