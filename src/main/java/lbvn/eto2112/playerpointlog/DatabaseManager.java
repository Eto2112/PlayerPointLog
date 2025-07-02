package lbvn.eto2112.playerpointlog;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {

    private final PlayerPointLog plugin;
    private Connection connection;
    private final String databasePath;

    // Prepared statement cache for better performance
    private PreparedStatement insertStatement;
    private PreparedStatement selectStatement;
    private PreparedStatement countStatement;

    public DatabaseManager(PlayerPointLog plugin) {
        this.plugin = plugin;
        this.databasePath = plugin.getDataFolder().getAbsolutePath() + File.separator + "playerpoints.db";
    }

    public boolean initialize() {
        try {
            // Create plugin data folder if it doesn't exist
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            // Simple SQLite connection without shading - same as other secure plugins
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

            // Advanced SQLite optimizations for maximum performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");           // Write-Ahead Logging for better concurrency
                stmt.execute("PRAGMA synchronous=NORMAL");         // Faster than FULL, still safe
                stmt.execute("PRAGMA cache_size=20000");           // Increased cache (20MB)
                stmt.execute("PRAGMA temp_store=memory");          // Store temp data in RAM
                stmt.execute("PRAGMA mmap_size=268435456");        // 256MB memory mapping
                stmt.execute("PRAGMA wal_autocheckpoint=1000");    // Auto-checkpoint every 1000 pages
                stmt.execute("PRAGMA optimize");                   // Auto-optimize database
            }

            // Create table if not exists
            createTable();

            plugin.getLogger().info("Database initialized successfully!");
            return true;

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            return false;
        }
    }

    private void createTable() throws SQLException {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS point_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_received TEXT NOT NULL,
                player_send TEXT NOT NULL,
                points_amount INTEGER NOT NULL,
                timestamp TEXT NOT NULL
            )
        """;

        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSQL);

            // Create indexes for faster queries
            statement.execute("CREATE INDEX IF NOT EXISTS idx_player_received ON point_transactions(player_received)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_player_send ON point_transactions(player_send)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON point_transactions(timestamp)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_player_composite ON point_transactions(player_received, player_send)");
        }
    }

    public void logTransaction(String playerReceived, String playerSend, int pointsAmount) {
        String insertSQL = "INSERT INTO point_transactions (player_received, player_send, points_amount, timestamp) VALUES (?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(insertSQL)) {
            ps.setString(1, playerReceived);
            ps.setString(2, playerSend);
            ps.setInt(3, pointsAmount);
            ps.setString(4, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to log transaction: " + e.getMessage());
        }
    }

    public void close() {
        try {
            // Close prepared statements
            if (insertStatement != null) insertStatement.close();
            if (selectStatement != null) selectStatement.close();
            if (countStatement != null) countStatement.close();

            if (connection != null) {
                // Final optimization before closing
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA optimize");
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                connection.close();
                plugin.getLogger().info("Database connection closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error closing database connection: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public List<TransactionRecord> getPlayerTransactions(String playerName, int page, int itemsPerPage) {
        List<TransactionRecord> transactions = new ArrayList<>();
        int offset = (page - 1) * itemsPerPage;

        String selectSQL = "SELECT player_received, player_send, points_amount, timestamp FROM point_transactions WHERE LOWER(player_received) = LOWER(?) OR LOWER(player_send) = LOWER(?) ORDER BY id DESC LIMIT ? OFFSET ?";

        try (PreparedStatement ps = connection.prepareStatement(selectSQL)) {
            ps.setString(1, playerName);
            ps.setString(2, playerName);
            ps.setInt(3, itemsPerPage);
            ps.setInt(4, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    transactions.add(new TransactionRecord(
                            rs.getString("player_received"),
                            rs.getString("player_send"),
                            rs.getInt("points_amount"),
                            rs.getString("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to retrieve transactions: " + e.getMessage());
        }

        return transactions;
    }

    public int getTotalTransactionCount(String playerName) {
        String countSQL = "SELECT COUNT(*) as total FROM point_transactions WHERE LOWER(player_received) = LOWER(?) OR LOWER(player_send) = LOWER(?)";

        try (PreparedStatement ps = connection.prepareStatement(countSQL)) {
            ps.setString(1, playerName);
            ps.setString(2, playerName);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to count transactions: " + e.getMessage());
        }

        return 0;
    }

    public static class TransactionRecord {
        private final String playerReceived;
        private final String playerSend;
        private final int pointsAmount;
        private final String timestamp;

        public TransactionRecord(String playerReceived, String playerSend, int pointsAmount, String timestamp) {
            this.playerReceived = playerReceived;
            this.playerSend = playerSend;
            this.pointsAmount = pointsAmount;
            this.timestamp = timestamp;
        }

        public String getPlayerReceived() { return playerReceived; }
        public String getPlayerSend() { return playerSend; }
        public int getPointsAmount() { return pointsAmount; }
        public String getTimestamp() { return timestamp; }
    }
}