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

        final String finalPlayerName = args[1];
        int tempPage = 1;

        if (args.length >= 3) {
            try {
                tempPage = Integer.parseInt(args[2]);
                if (tempPage < 1) {
                    sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-page"));
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("lookup-invalid-number"));
                return true;
            }
        }

        final int finalPage = tempPage;

        // Run database query asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<DatabaseManager.TransactionRecord> transactions = plugin.getDatabaseManager()
                    .getPlayerTransactions(finalPlayerName, finalPage, ITEMS_PER_PAGE);
            int totalTransactions = plugin.getDatabaseManager().getTotalTransactionCount(finalPlayerName);

            // Send results back on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                displayTransactions(sender, finalPlayerName, transactions, finalPage, totalTransactions);
            });
        });

        return true;
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

        int totalPages = (int) Math.ceil((double) totalTransactions / ITEMS_PER_PAGE);

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
        if (totalPages > 1) {
            sendPaginationFooter(sender, playerName, page, totalPages);
        } else {
            sender.sendMessage(plugin.getLanguageManager().getMessage("pagination-simple", "page", page, "total", totalPages));
        }
    }

    private void sendPaginationFooter(CommandSender sender, String playerName, int currentPage, int totalPages) {
        if (!(sender instanceof Player)) {
            // For console, send simple text
            sender.sendMessage(plugin.getLanguageManager().getMessage("pagination-simple", "page", currentPage, "total", totalPages));
            if (currentPage > 1) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("console-previous", "player", playerName, "page", (currentPage - 1)));
            }
            if (currentPage < totalPages) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("console-next", "player", playerName, "page", (currentPage + 1)));
            }
            return;
        }

        // For players, send clickable components
        TextComponent footer = new TextComponent("---------------<<<");
        footer.setColor(net.md_5.bungee.api.ChatColor.GRAY);

        // Previous page button
        if (currentPage > 1) {
            TextComponent prevButton = new TextComponent(" << ");
            prevButton.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            prevButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/plog lookup " + playerName + " " + (currentPage - 1)));
            prevButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(plugin.getLanguageManager().getMessage("pagination-previous-hover")).create()));
            footer.addExtra(prevButton);
        } else {
            footer.addExtra(new TextComponent(" << "));
        }

        // Page info
        TextComponent pageInfo = new TextComponent("Page " + currentPage + " of " + totalPages);
        pageInfo.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        footer.addExtra(pageInfo);

        // Next page button
        if (currentPage < totalPages) {
            TextComponent nextButton = new TextComponent(" >> ");
            nextButton.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            nextButton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/plog lookup " + playerName + " " + (currentPage + 1)));
            nextButton.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(plugin.getLanguageManager().getMessage("pagination-next-hover")).create()));
            footer.addExtra(nextButton);
        } else {
            footer.addExtra(new TextComponent(" >> "));
        }

        TextComponent endFooter = new TextComponent(" >>>---------------");
        endFooter.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        footer.addExtra(endFooter);

        ((Player) sender).spigot().sendMessage(footer);
    }

    private String formatTimestamp(String timestamp) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(timestamp, INPUT_FORMATTER);
            return dateTime.format(OUTPUT_FORMATTER);
        } catch (DateTimeParseException e) {
            // If parsing fails, return the original timestamp
            return timestamp;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("playerpointlog.admin") && !sender.hasPermission("playerpointlog.use")) {
            return new ArrayList<>();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if ("lookup".startsWith(args[0].toLowerCase())) {
                completions.add("lookup");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lookup")) {
            // Add online players for tab completion
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(player.getName());
                }
            });
        } else if (args.length == 3 && args[0].equalsIgnoreCase("lookup")) {
            // Add page numbers
            for (int i = 1; i <= 5; i++) {
                completions.add(String.valueOf(i));
            }
        }

        return completions;
    }
}