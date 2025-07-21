package lbvn.eto2112.playerpointlog;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

public class LookupCommandHandler implements CommandExecutor, TabCompleter {

    private final PlayerPointLog plugin;
    private static final int ITEMS_PER_PAGE = 5;
    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public LookupCommandHandler(PlayerPointLog plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Check permissions: admin can use everything, regular users need playerpointlog.use
        if (!sender.hasPermission("playerpointlog.admin") && !sender.hasPermission("playerpointlog.use")) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("no-permission"));
            return true;
        }

        if (args.length < 2 || !args[0].equalsIgnoreCase("lookup")) {
            sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-usage-short"));
            return true;
        }

        String playerName = args[1];
        int page = 1;

        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
                if (page < 1) {
                    sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-page"));
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-number"));
                return true;
            }
        }

        // Async lookup with improved error handling
        performLookupAsync(sender, playerName, page);
        return true;
    }

    private void performLookupAsync(CommandSender sender, String playerName, int page) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        List<DatabaseManager.TransactionRecord> transactions =
                                plugin.getDatabaseManager().getPlayerTransactions(playerName, page, ITEMS_PER_PAGE);
                        int totalTransactions = plugin.getDatabaseManager().getTotalTransactionCount(playerName);
                        return new LookupResult(transactions, totalTransactions, true, null);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error during lookup for " + playerName + ": " + e.getMessage());
                        return new LookupResult(new ArrayList<>(), 0, false, e.getMessage());
                    }
                }, plugin.getDatabaseExecutor())
                .thenAcceptAsync(result -> {
                    if (!result.success()) {
                        sender.sendMessage("§cError retrieving transaction data: " + result.error());
                        return;
                    }
                    displayTransactions(sender, playerName, result.transactions(), page, result.totalTransactions());
                }, runnable -> plugin.getServer().getScheduler().runTask(plugin, runnable))
                .exceptionally(throwable -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§cUnexpected error occurred during lookup.");
                        plugin.getLogger().severe("Async lookup error: " + throwable.getMessage());
                    });
                    return null;
                });
    }

    private void displayTransactions(CommandSender sender, String playerName,
                                     List<DatabaseManager.TransactionRecord> transactions,
                                     int page, int totalTransactions) {
        if (transactions.isEmpty()) {
            String message = page == 1 ?
                    plugin.getLanguageManager().getMessage("lookup-no-history", "player", playerName) :
                    plugin.getLanguageManager().getMessage("lookup-no-page", "page", page, "player", playerName);
            sender.sendMessage(message);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) totalTransactions / ITEMS_PER_PAGE));

        // Header
        sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-header", "player", playerName));

        // Transaction list with optimized processing
        transactions.parallelStream()
                .map(this::formatTransaction)
                .forEachOrdered(sender::sendMessage);

        // Pagination footer
        if (totalPages > 1) {
            sendPaginationFooter(sender, playerName, page, totalPages);
        } else {
            sender.sendMessage(plugin.getLanguageManager().getMessage("pagination-simple",
                    "page", page, "total", totalPages));
        }
    }

    private String formatTransaction(DatabaseManager.TransactionRecord record) {
        String formattedTime = formatTimestamp(record.getTimestamp());

        if ("console".equals(record.getPlayerReceived())) {
            // Console received points from player (player lost points)
            return plugin.getLanguageManager().getMessage("transaction-lost",
                    "time", formattedTime,
                    "player", record.getPlayerSend(),
                    "amount", record.getPointsAmount(),
                    "receiver", "console"
            );
        } else {
            // Player received points
            return plugin.getLanguageManager().getMessage("transaction-received",
                    "time", formattedTime,
                    "player", record.getPlayerReceived(),
                    "amount", record.getPointsAmount(),
                    "sender", record.getPlayerSend()
            );
        }
    }

    private void sendPaginationFooter(CommandSender sender, String playerName, int currentPage, int totalPages) {
        if (!(sender instanceof Player)) {
            // For console, send simple text with navigation commands
            sender.sendMessage(plugin.getLanguageManager().getMessage("pagination-simple",
                    "page", currentPage, "total", totalPages));

            if (currentPage > 1) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("console-previous",
                        "player", playerName, "page", (currentPage - 1)));
            }
            if (currentPage < totalPages) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("console-next",
                        "player", playerName, "page", (currentPage + 1)));
            }
            return;
        }

        // For players, send optimized clickable components
        TextComponent footer = createNavigationFooter(playerName, currentPage, totalPages);
        ((Player) sender).spigot().sendMessage(footer);
    }

    private TextComponent createNavigationFooter(String playerName, int currentPage, int totalPages) {
        TextComponent footer = new TextComponent("---------------<<<");
        footer.setColor(net.md_5.bungee.api.ChatColor.GRAY);

        // Previous page button
        TextComponent prevButton = new TextComponent(" << ");
        if (currentPage > 1) {
            prevButton.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            prevButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/plog lookup " + playerName + " " + (currentPage - 1)));
            prevButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(plugin.getLanguageManager().getMessage("pagination-previous-hover")).create()));
        } else {
            prevButton.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
        }
        footer.addExtra(prevButton);

        // Page info
        TextComponent pageInfo = new TextComponent("Page " + currentPage + " of " + totalPages);
        pageInfo.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        footer.addExtra(pageInfo);

        // Next page button
        TextComponent nextButton = new TextComponent(" >> ");
        if (currentPage < totalPages) {
            nextButton.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            nextButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/plog lookup " + playerName + " " + (currentPage + 1)));
            nextButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(plugin.getLanguageManager().getMessage("pagination-next-hover")).create()));
        } else {
            nextButton.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
        }
        footer.addExtra(nextButton);

        TextComponent endFooter = new TextComponent(" >>>---------------");
        endFooter.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        footer.addExtra(endFooter);

        return footer;
    }

    private String formatTimestamp(String timestamp) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(timestamp, INPUT_FORMATTER);
            return dateTime.format(OUTPUT_FORMATTER);
        } catch (DateTimeParseException e) {
            // If parsing fails, return the original timestamp
            plugin.getLogger().fine("Failed to parse timestamp: " + timestamp);
            return timestamp;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("playerpointlog.admin") && !sender.hasPermission("playerpointlog.use")) {
            return new ArrayList<>();
        }

        return switch (args.length) {
            case 1 -> "lookup".startsWith(args[0].toLowerCase()) ? List.of("lookup") : new ArrayList<>();

            case 2 -> args[0].equalsIgnoreCase("lookup") ?
                    getOnlinePlayerCompletions(args[1]) : new ArrayList<>();

            case 3 -> args[0].equalsIgnoreCase("lookup") ?
                    getPageCompletions() : new ArrayList<>();

            default -> new ArrayList<>();
        };
    }

    private List<String> getOnlinePlayerCompletions(String input) {
        String lowerInput = input.toLowerCase();
        return plugin.getServer().getOnlinePlayers().stream()
                .map(player -> player.getName())
                .filter(name -> name.toLowerCase().startsWith(lowerInput))
                .toList();
    }

    private List<String> getPageCompletions() {
        return IntStream.rangeClosed(1, 5)
                .mapToObj(String::valueOf)
                .toList();
    }

    // Record for better performance and immutability
    private record LookupResult(List<DatabaseManager.TransactionRecord> transactions,
                                int totalTransactions,
                                boolean success,
                                String error) {}
}