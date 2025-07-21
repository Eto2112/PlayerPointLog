package lbvn.eto2112.playerpointlog;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DatabaseManager {

    private final PlayerPointLog plugin;
    private Connection connection;
    private final String databasePath;
    private final ReentrantReadWriteLock connectionLock = new ReentrantReadWriteLock();

    // Prepared statement pool for maximum performance
    private PreparedStatement insertStatement;
    private PreparedStatement batchInsertStatement;
    private PreparedStatement selectStatement;
    private PreparedStatement countStatement;

    // Connection pooling for better concurrency
    private static final int MAX_CONNECTIONS = 10;
    private final ConnectionPool connectionPool;

    public DatabaseManager(PlayerPointLog plugin) {
        this.plugin = plugin;
        this.databasePath = plugin.getDataFolder().getAbsolutePath() + File.separator + "playerpoints.db";
        this.connectionPool = new ConnectionPool(databasePath, MAX_CONNECTIONS);
    }

    public boolean initialize() {
        connectionLock.writeLock().lock();
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);

            // Ultra-optimized SQLite settings for maximum performance
            try (Statement stmt = connection.createStatement()) {
                // WAL mode for better concurrency and crash safety
                stmt.execute("PRAGMA journal_mode=WAL");

                // Optimize for speed while maintaining safety
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA cache_size=50000");        // 50MB cache
                stmt.execute("PRAGMA temp_store=MEMORY");
                stmt.execute("PRAGMA mmap_size=536870912");     // 512MB memory mapping
                stmt.execute("PRAGMA wal_autocheckpoint=2000");
                stmt.execute("PRAGMA busy_timeout=30000");      // 30 second timeout

                // Optimize query planner
                stmt.execute("PRAGMA optimize");
            }

            createTable();
            prepareStatements();

            plugin.getLogger().info("Optimized database initialized successfully!");
            return true;

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            return false;
        } finally {
            connectionLock.writeLock().unlock();
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

            // Optimized compound indexes for faster queries
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_player_received_time 
                ON point_transactions(player_received, timestamp DESC)
            """);

            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_player_send_time 
                ON point_transactions(player_send, timestamp DESC)
            """);

            // Covering index for lookup queries (SQLite compatible)
            statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_lookup_covering 
                ON point_transactions(player_received, player_send, id DESC, points_amount, timestamp)
            """);

            // Analyze tables for optimal query planning
            statement.execute("ANALYZE point_transactions");
        }
    }

    private void prepareStatements() throws SQLException {
        insertStatement = connection.prepareStatement(
                "INSERT INTO point_transactions (player_received, player_send, points_amount, timestamp) VALUES (?, ?, ?, ?)"
        );

        batchInsertStatement = connection.prepareStatement(
                "INSERT INTO point_transactions (player_received, player_send, points_amount, timestamp) VALUES (?, ?, ?, ?)"
        );

        selectStatement = connection.prepareStatement(
                """
                SELECT player_received, player_send, points_amount, timestamp 
                FROM point_transactions 
                WHERE LOWER(player_received) = LOWER(?) OR LOWER(player_send) = LOWER(?) 
                ORDER BY id DESC 
                LIMIT ? OFFSET ?
                """
        );

        countStatement = connection.prepareStatement(
                """
                SELECT COUNT(*) as total 
                FROM point_transactions 
                WHERE LOWER(player_received) = LOWER(?) OR LOWER(player_send) = LOWER(?)
                """
        );
    }

    public void logTransaction(String playerReceived, String playerSend, int pointsAmount) {
        connectionLock.readLock().lock();
        try {
            synchronized (insertStatement) {
                insertStatement.setString(1, playerReceived);
                insertStatement.setString(2, playerSend);
                insertStatement.setInt(3, pointsAmount);
                insertStatement.setString(4, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                insertStatement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to log transaction: " + e.getMessage());
            // Attempt to reinitialize connection on failure
            attemptReconnection();
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    // Optimized batch insert for better performance
    public void logTransactionsBatch(TransactionData[] transactions, int batchSize) {
        if (batchSize == 0) return;

        connectionLock.readLock().lock();
        try {
            connection.setAutoCommit(false);

            synchronized (batchInsertStatement) {
                String currentTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                for (int i = 0; i < batchSize; i++) {
                    if (transactions[i] != null) {
                        TransactionData data = transactions[i];

                        batchInsertStatement.setString(1, data.playerReceived);
                        batchInsertStatement.setString(2, data.playerSend);
                        batchInsertStatement.setInt(3, data.pointsAmount);
                        batchInsertStatement.setString(4, currentTime);
                        batchInsertStatement.addBatch();
                    }
                }

                batchInsertStatement.executeBatch();
                connection.commit();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to execute batch insert: " + e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                plugin.getLogger().severe("Failed to rollback batch: " + rollbackEx.getMessage());
            }
            attemptReconnection();
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to reset auto-commit: " + e.getMessage());
            }
            connectionLock.readLock().unlock();
        }
    }

    public List<TransactionRecord> getPlayerTransactions(String playerName, int page, int itemsPerPage) {
        List<TransactionRecord> transactions = new ArrayList<>();
        int offset = (page - 1) * itemsPerPage;

        connectionLock.readLock().lock();
        try {
            synchronized (selectStatement) {
                selectStatement.setString(1, playerName);
                selectStatement.setString(2, playerName);
                selectStatement.setInt(3, itemsPerPage);
                selectStatement.setInt(4, offset);

                try (ResultSet rs = selectStatement.executeQuery()) {
                    while (rs.next()) {
                        transactions.add(new TransactionRecord(
                                rs.getString("player_received"),
                                rs.getString("player_send"),
                                rs.getInt("points_amount"),
                                rs.getString("timestamp")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to retrieve transactions: " + e.getMessage());
            attemptReconnection();
        } finally {
            connectionLock.readLock().unlock();
        }

        return transactions;
    }

    public int getTotalTransactionCount(String playerName) {
        connectionLock.readLock().lock();
        try {
            synchronized (countStatement) {
                countStatement.setString(1, playerName);
                countStatement.setString(2, playerName);

                try (ResultSet rs = countStatement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("total");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to count transactions: " + e.getMessage());
            attemptReconnection();
        } finally {
            connectionLock.readLock().unlock();
        }

        return 0;
    }

    private void attemptReconnection() {
        plugin.getLogger().info("Attempting to reconnect to database...");
        connectionLock.writeLock().lock();
        try {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // Ignore close errors
                }
            }

            // Reinitialize connection
            if (initialize()) {
                plugin.getLogger().info("Database reconnection successful!");
            } else {
                plugin.getLogger().severe("Database reconnection failed!");
            }
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    public void close() {
        connectionLock.writeLock().lock();
        try {
            // Close prepared statements
            closeStatement(insertStatement);
            closeStatement(batchInsertStatement);
            closeStatement(selectStatement);
            closeStatement(countStatement);

            if (connection != null) {
                // Final optimization and cleanup
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA optimize");
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException e) {
                    // Ignore cleanup errors during shutdown
                }

                connection.close();
                plugin.getLogger().info("Database connection closed gracefully.");
            }

            connectionPool.closeAll();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error closing database connection: " + e.getMessage());
        } finally {
            connectionLock.writeLock().unlock();
        }
    }

    private void closeStatement(PreparedStatement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // Ignore close errors during shutdown
            }
        }
    }

    public boolean isConnected() {
        connectionLock.readLock().lock();
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        } finally {
            connectionLock.readLock().unlock();
        }
    }

    // Simple connection pool for better concurrency
    private static class ConnectionPool {
        private final String url;
        private final List<Connection> availableConnections = new ArrayList<>();
        private final List<Connection> usedConnections = new ArrayList<>();
        private final int maxConnections;

        public ConnectionPool(String databasePath, int maxConnections) {
            this.url = "jdbc:sqlite:" + databasePath;
            this.maxConnections = maxConnections;
        }

        public synchronized Connection getConnection() throws SQLException {
            if (availableConnections.isEmpty() && usedConnections.size() < maxConnections) {
                Connection conn = DriverManager.getConnection(url);
                usedConnections.add(conn);
                return conn;
            } else if (!availableConnections.isEmpty()) {
                Connection conn = availableConnections.remove(0);
                usedConnections.add(conn);
                return conn;
            } else {
                throw new SQLException("Maximum connection pool size reached");
            }
        }

        public synchronized void releaseConnection(Connection conn) {
            if (usedConnections.remove(conn)) {
                availableConnections.add(conn);
            }
        }

        public synchronized void closeAll() {
            for (Connection conn : availableConnections) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore
                }
            }
            for (Connection conn : usedConnections) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore
                }
            }
            availableConnections.clear();
            usedConnections.clear();
        }
    }

    public static class TransactionData {
        public final String playerReceived;
        public final String playerSend;
        public final int pointsAmount;

        public TransactionData(String playerReceived, String playerSend, int pointsAmount) {
            this.playerReceived = playerReceived;
            this.playerSend = playerSend;
            this.pointsAmount = pointsAmount;
        }
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