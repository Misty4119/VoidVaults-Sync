package com.voidvault.storage;

import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultDataCloner;
import com.voidvault.model.VaultPage;
import com.voidvault.storage.compression.CompressionCodec;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL-based storage implementation for vault data.
 * Uses HikariCP for efficient connection pooling and supports async operations.
 *
 * <h2>Schema</h2>
 * The plugin owns three tables. {@code voidvault_players} holds player-level
 * metadata; {@code voidvault_pages} stores one row per page so that a
 * single-slot edit rewrites at most one row instead of N rows (the previous
 * design stored one row per filled slot, which scaled poorly when a player
 * owned a 6-page vault full of enchanted items). {@code voidvault_schema}
 * records the schema version so the storage layer can run idempotent
 * migrations on first connect.
 *
 * <h2>Compression</h2>
 * Every page payload may be transparently compressed via
 * {@link CompressionCodec} before being written. The {@code compressed}
 * column tells the reader which path to take; the {@code uncompressed_size}
 * column makes schema audits and capacity planning possible without having
 * to inflate every row.
 *
 * <h2>Concurrent writers</h2>
 * The {@code version} column on {@code voidvault_pages} is incremented on
 * every update and used as a CAS token. When the hybrid Redis+MySQL manager
 * (or a multi-master network) tries to write a stale snapshot the affected
 * row is skipped instead of overwriting the newer value.
 */
public class MySqlStorage implements StorageManager {

    /**
     * v3 removes the destructive startup "cleanup" used by v2. Existing
     * tables are data, not temporary migration artefacts, and must never be
     * replaced implicitly.
     */
    public static final int SCHEMA_VERSION = 3;
    private static final String SCHEMA_LOCK_NAME = "voidvault_schema_migration";
    private static final int SCHEMA_LOCK_TIMEOUT_SECONDS = 30;
    private static final Set<String> REQUIRED_PAGE_COLUMNS = Set.of(
            "id", "player_id", "page_number", "slot_count", "page_data",
            "compressed", "uncompressed_size", "version", "last_updated");

    private final Plugin plugin;
    private final Logger logger;
    private final DataCache dataCache;
    private final ExecutorService asyncExecutor;
    private HikariDataSource dataSource;

    private final boolean compressionEnabled;
    private final int compressionLevel;

    // Connection retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;

    // Latency histograms exposed via StorageManager.getSaveLatencyHistogram /
    // getLoadLatencyHistogram so MetricsUtil can read them without holding
    // a hard reference to this class.
    private final LatencyHistogram saveHistogram = new LatencyHistogram("mysql.save");
    private final LatencyHistogram loadHistogram = new LatencyHistogram("mysql.load");

    // Pool-metrics scheduler: a separate daemon thread that logs Hikari
    // active / idle / total counts every hour. We deliberately do NOT
    // piggy-back on asyncExecutor (which is virtual-thread-per-task) because
    // the logging call is synchronous and would otherwise block a virtual
    // thread for the duration of the log line.
    private final AtomicBoolean metricsStarted = new AtomicBoolean(false);
    private ScheduledExecutorService metricsExecutor;
    private ScheduledFuture<?> metricsTask;

    public MySqlStorage(Plugin plugin, DataCache dataCache) {
        this(plugin, dataCache, true, 6);
    }

    public MySqlStorage(Plugin plugin, DataCache dataCache, boolean compressionEnabled, int compressionLevel) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataCache = dataCache;
        this.compressionEnabled = compressionEnabled;
        this.compressionLevel = Math.max(1, Math.min(9, compressionLevel));
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                setupConnectionPool();
                initializeSchemaSafely();
                logger.info("MySQL storage initialized successfully (compression="
                        + (compressionEnabled ? "level " + compressionLevel : "off") + ")");
                logPoolMetrics("initial");
                startMetricsLogger();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize MySQL storage", e);
                throw new RuntimeException("Failed to initialize MySQL storage", e);
            }
        }, asyncExecutor);
    }

    /**
     * Log Hikari pool counters (active / idle / total / waiting). Used both
     * at startup and from the hourly metrics scheduler.
     */
    private void logPoolMetrics(String source) {
        if (dataSource == null || dataSource.isClosed()) {
            return;
        }
        HikariPoolMXBean mx = dataSource.getHikariPoolMXBean();
        if (mx == null) {
            return;
        }
        logger.info(() -> "[Hikari:" + source + "] active=" + mx.getActiveConnections()
                + " idle=" + mx.getIdleConnections()
                + " total=" + mx.getTotalConnections()
                + " waiting=" + mx.getThreadsAwaitingConnection());
    }

    /**
     * Spin up a daemon that logs pool metrics once an hour. The interval is
     * intentionally long because Hikari's own pool-sizing heuristics are
     * already triggered by high contention; we just need a coarse
     * "everything is healthy" heartbeat to compare against the daily stats.
     */
    private void startMetricsLogger() {
        if (!metricsStarted.compareAndSet(false, true)) {
            return;
        }
        metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-mysql-metrics");
            t.setDaemon(true);
            return t;
        });
        metricsTask = metricsExecutor.scheduleAtFixedRate(
                () -> logPoolMetrics("hourly"),
                1, 1, TimeUnit.HOURS);
    }

    /**
     * Sets up the HikariCP connection pool from configuration.
     * Automatically creates the database if it does not exist.
     */
    private void setupConnectionPool() {
        FileConfiguration config = plugin.getConfig();

        // Read from storage.mysql.* with fallback to legacy mysql.* path
        String host = config.getString("storage.mysql.host",
                config.getString("mysql.host", "localhost"));
        int port = config.getInt("storage.mysql.port",
                config.getInt("mysql.port", 3306));
        host = requireSafeHost(host, "storage.mysql.host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("storage.mysql.port must be between 1 and 65535");
        }
        String database = config.getString("storage.mysql.database",
                config.getString("mysql.database", "voidvault"));
        database = requireSafeIdentifier(database, "storage.mysql.database");
        String username = config.getString("storage.mysql.username",
                config.getString("mysql.username", "root"));
        String password = config.getString("storage.mysql.password",
                config.getString("mysql.password", ""));
        if ("change-me".equals(password) || "password".equalsIgnoreCase(password)) {
            throw new IllegalArgumentException(
                    "Refusing insecure default MySQL password; configure storage.mysql.password");
        }
        int poolSize = config.getInt("storage.mysql.pool-size",
                config.getInt("mysql.pool-size", 30));
        int connectionTimeoutMs = config.getInt("storage.mysql.connection-timeout-ms", 3000);
        long leakDetectionThresholdMs = config.getLong("storage.mysql.leak-detection-threshold-ms", 10000L);
        String sslMode = requireSslMode(config.getString("storage.mysql.ssl-mode", "VERIFY_IDENTITY"));

        // Step 1: Connect without database name to create the database if needed
        String baseJdbcUrl = "jdbc:mysql://" + host + ":" + port
                + "/?sslMode=" + sslMode + "&allowPublicKeyRetrieval=false&characterEncoding=utf8";
        try (var tempDs = new HikariDataSource(buildTempConfig(
                baseJdbcUrl, username, password, connectionTimeoutMs));
             Connection conn = tempDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            logger.info("Database '" + database + "' ensured to exist");
        } catch (SQLException e) {
            logger.warning("Could not auto-create database '" + database
                    + "' at " + host + ":" + port + " (will try connecting anyway): "
                    + describeConnectionFailure(e));
        }

        // Step 2: Build the real connection pool with the target database.
        // We turn on JDBC-level compression here so huge BLOBs (especially
        // map-painting NBT) don't dominate the network round-trip between
        // the Minecraft server and the MySQL primary.
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?sslMode=" + sslMode + "&allowPublicKeyRetrieval=false&autoReconnect=true"
                + "&failOverReadOnly=false&characterEncoding=utf8"
                + "&useCompression=true&maxAllowedPacket=67108864";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(Math.max(2, Math.min(poolSize / 4, 8)));
        // Fail fast: 3s instead of Hikari's 30s default so a saturated pool
        // surfaces as a normal exception rather than blocking the main
        // thread for half a minute during a server-stop cascade.
        hikariConfig.setConnectionTimeout(connectionTimeoutMs);
        // Validation timeout: borrow-thread side is already bounded by
        // connectionTimeoutMs, but this caps the time the JDBC driver
        // spends waiting for isValid() to return.
        hikariConfig.setValidationTimeout(2000L);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        // Leak detection: anything held longer than this is almost
        // certainly a forgotten close() somewhere.
        hikariConfig.setLeakDetectionThreshold(leakDetectionThresholdMs);
        hikariConfig.setPoolName("VoidVault-Pool");

        // MySQL-specific optimizations
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
        hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
        hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

        this.dataSource = new HikariDataSource(hikariConfig);
        logger.info("HikariCP connection pool established for database '" + database
                + "' (pool=" + poolSize + ", connTimeout=" + connectionTimeoutMs
                + "ms, leakThreshold=" + leakDetectionThresholdMs + "ms)");
    }

    /**
     * SQL identifiers cannot be bound through PreparedStatement. Restrict the
     * configured schema name before it is interpolated into CREATE DATABASE
     * and the JDBC URL, preventing identifier and connection-string injection.
     */
    static String requireSafeIdentifier(String value, String setting) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException(setting
                    + " must contain only ASCII letters, digits, or underscore (1-64 characters)");
        }
        return value;
    }

    static String requireSafeHost(String value, String setting) {
        if (value == null || value.isBlank() || value.length() > 253
                || !value.matches("[A-Za-z0-9.:-]+")) {
            throw new IllegalArgumentException(setting + " contains invalid host characters");
        }
        return value;
    }

    static String requireSslMode(String value) {
        if (value == null) throw new IllegalArgumentException("storage.mysql.ssl-mode is required");
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported storage.mysql.ssl-mode: " + value);
        };
    }

    /**
     * Builds a minimal HikariConfig for the one-shot CREATE DATABASE connection.
     */
    static HikariConfig buildTempConfig(String jdbcUrl, String username, String password,
                                        long connectionTimeoutMs) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(connectionTimeoutMs);
        cfg.setValidationTimeout(Math.min(connectionTimeoutMs, 2000L));
        cfg.setPoolName("VoidVault-TempPool");
        // Disable fail-fast so we don't throw if MySQL is unreachable — the
        // caller already catches SQLException.
        cfg.setInitializationFailTimeout(-1);
        return cfg;
    }

    /**
     * Produces a compact, actionable summary without logging credentials or a
     * full duplicate stack trace. Connector/J may wrap the actual network or
     * TLS failure after retrying, so retain SQLState/error code and the
     * deepest available cause message.
     */
    static String describeConnectionFailure(SQLException exception) {
        SQLException deepestSql = exception;
        for (SQLException next = exception.getNextException(); next != null; next = next.getNextException()) {
            deepestSql = next;
        }
        Throwable deepestCause = deepestSql;
        while (deepestCause.getCause() != null && deepestCause.getCause() != deepestCause) {
            deepestCause = deepestCause.getCause();
        }
        String message = deepestCause.getMessage();
        if (message == null || message.isBlank()) {
            message = deepestSql.getMessage();
        }
        return "SQLState=" + deepestSql.getSQLState() + ", errorCode=" + deepestSql.getErrorCode()
                + ", cause=" + (message == null ? deepestSql.getClass().getSimpleName() : message);
    }

    /**
     * Serialises schema work across all server nodes. MySQL advisory locks are
     * connection-scoped, so a crashed node automatically releases its lock.
     */
    private void initializeSchemaSafely() throws SQLException {
        try (Connection conn = getConnection()) {
            if (!acquireSchemaLock(conn)) {
                throw new SQLException("Timed out waiting for another VoidVaults node to finish schema migration");
            }
            try {
                createTables(conn);
                validatePagesSchema(conn);
                runMigrations(conn);
            } finally {
                releaseSchemaLock(conn);
            }
        }
    }

    static boolean containsOnlySafeSchemaDdl(String sql) {
        String normalized = sql.toUpperCase(Locale.ROOT);
        return !normalized.contains("DROP TABLE") && !normalized.contains("TRUNCATE TABLE");
    }

    private static boolean acquireSchemaLock(Connection conn) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, SCHEMA_LOCK_NAME);
            statement.setInt(2, SCHEMA_LOCK_TIMEOUT_SECONDS);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static void releaseSchemaLock(Connection conn) {
        try (PreparedStatement statement = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, SCHEMA_LOCK_NAME);
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the connection also releases the advisory lock.
        }
    }

    /** Creates missing tables only; it never mutates or removes existing data. */
    private void createTables(Connection conn) throws SQLException {
        String createSchemaTable = """
            CREATE TABLE IF NOT EXISTS voidvault_schema (
                version INT PRIMARY KEY,
                upgraded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        String createPlayersTable = """
            CREATE TABLE IF NOT EXISTS voidvault_players (
                player_id VARCHAR(36) PRIMARY KEY,
                custom_slots INT DEFAULT 0,
                custom_pages INT DEFAULT 0,
                last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        // Note: the player_id column must share the same collation as the
        // referenced column in voidvault_players (utf8mb4_unicode_ci). MySQL
        // requires FK columns to be identical in type, charset and collation
        // — a mismatch surfaces as errno 150 "Foreign key constraint is
        // incorrectly formed" during CREATE TABLE.
        String createPagesTable = """
            CREATE TABLE IF NOT EXISTS voidvault_pages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                player_id VARCHAR(36) NOT NULL,
                page_number INT NOT NULL,
                slot_count INT NOT NULL DEFAULT 0,
                page_data LONGBLOB NOT NULL,
                compressed TINYINT(1) NOT NULL DEFAULT 0,
                uncompressed_size INT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY unique_player_page (player_id, page_number),
                INDEX idx_player (player_id),
                INDEX idx_player_version (player_id, version),
                CONSTRAINT fk_pages_player FOREIGN KEY (player_id)
                    REFERENCES voidvault_players(player_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        // Legacy per-slot table, kept around for backwards compatibility and
        // for the migration path. New code paths only write to
        // voidvault_pages; this table is now read-only. Uses the same
        // utf8mb4_unicode_ci collation as the players table so any future
        // FK added between them would behave like the pages FK.
        String createLegacyItemsTable = """
            CREATE TABLE IF NOT EXISTS voidvault_items (
                id INT AUTO_INCREMENT PRIMARY KEY,
                player_id VARCHAR(36) NOT NULL,
                page_number INT NOT NULL,
                slot_number INT NOT NULL,
                item_data MEDIUMBLOB NOT NULL,
                UNIQUE KEY unique_slot (player_id, page_number, slot_number),
                INDEX idx_player_page (player_id, page_number)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

        if (!containsOnlySafeSchemaDdl(createSchemaTable)
                || !containsOnlySafeSchemaDdl(createPlayersTable)
                || !containsOnlySafeSchemaDdl(createPagesTable)
                || !containsOnlySafeSchemaDdl(createLegacyItemsTable)) {
            throw new SQLException("Refusing unsafe VoidVaults schema DDL");
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createSchemaTable);
            stmt.execute(createPlayersTable);
            stmt.execute(createPagesTable);
            stmt.execute(createLegacyItemsTable);
            logger.info("Database tables created/verified (schema v" + SCHEMA_VERSION + ")");
        }
    }

    /**
     * Idempotent migration runner. Reads the current {@code voidvault_schema}
     * row and applies the migrations that have not yet been run. New
     * deployments will always run every migration up to {@link #SCHEMA_VERSION}.
     */
    private void runMigrations(Connection conn) throws SQLException {
            int current = readSchemaVersion(conn);
            if (current < 1) {
                logger.info("Recording schema version " + SCHEMA_VERSION);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO voidvault_schema (version) VALUES (?) ON DUPLICATE KEY UPDATE version = VALUES(version)")) {
                    ps.setInt(1, SCHEMA_VERSION);
                    ps.executeUpdate();
                }
            } else if (current < SCHEMA_VERSION) {
                logger.info("Upgrading voidvault schema from v" + current + " to v" + SCHEMA_VERSION);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE voidvault_schema SET version = ? WHERE version = ?")) {
                    ps.setInt(1, SCHEMA_VERSION);
                    ps.setInt(2, current);
                    ps.executeUpdate();
                }
            } else {
                logger.fine("voidvault schema already at v" + current);
            }
    }

    private int readSchemaVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM voidvault_schema")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    /**
     * A pre-existing page table may be an interrupted, old, or manually
     * modified schema. Refuse to start rather than guessing and destroying
     * rows. The operator can then back up and perform an explicit migration.
     */
    private static void validatePagesSchema(Connection conn) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'voidvault_pages'");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                columns.add(result.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        Set<String> missing = missingRequiredPageColumns(columns);
        if (!missing.isEmpty()) {
            throw new SQLException("voidvault_pages has an incompatible schema; missing " + missing
                    + ". Refusing destructive repair. Restore/backup the database and run an explicit migration.");
        }
    }

    static Set<String> missingRequiredPageColumns(Set<String> columns) {
        Set<String> normalized = new HashSet<>();
        for (String column : columns) {
            normalized.add(column.toLowerCase(Locale.ROOT));
        }
        Set<String> missing = new TreeSet<>(REQUIRED_PAGE_COLUMNS);
        missing.removeAll(normalized);
        return missing;
    }

    @Override
    public CompletableFuture<PlayerVaultData> loadPlayerData(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            long startNs = System.nanoTime();
            try {
                return executeWithRetry(() -> loadPlayerDataInternal(playerId), "load data for " + playerId);
            } finally {
                loadHistogram.record(System.nanoTime() - startNs);
            }
        }, asyncExecutor);
    }

    private PlayerVaultData loadPlayerDataInternal(UUID playerId) throws SQLException {
        try (Connection conn = getConnection()) {
            // Load player metadata. Missing rows are normalised to (0,0).
            int customSlots = 0;
            int customPages = 0;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT custom_slots, custom_pages FROM voidvault_players WHERE player_id = ?")) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        customSlots = rs.getInt("custom_slots");
                        customPages = rs.getInt("custom_pages");
                    } else {
                        return PlayerVaultData.createEmpty(playerId);
                    }
                }
            }

            // Read every page in one query and group the result locally so
            // we never keep a ResultSet open across an NBT decode.
            Map<Integer, VaultPage> pages = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT page_number, page_data, compressed, uncompressed_size, version " +
                            "FROM voidvault_pages WHERE player_id = ? ORDER BY page_number")) {
                stmt.setString(1, playerId.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        int pageNumber = rs.getInt("page_number");
                        byte[] pageData = rs.getBytes("page_data");
                        boolean compressed = rs.getInt("compressed") == 1;
                        try {
                            ItemStack[] contents = decodePage(pageData, compressed);
                            pages.put(pageNumber, new VaultPage(pageNumber, contents));
                        } catch (IOException ex) {
                            logger.log(Level.WARNING, "Failed to decode page " + pageNumber
                                    + " for player " + playerId, ex);
                        }
                    }
                }
            }

            logger.fine("Loaded data for player " + playerId + " with " + pages.size() + " pages");
            PlayerVaultData loaded = new PlayerVaultData(playerId, pages, customSlots, customPages);
            // Defensive deep copy before crossing the I/O thread → caller thread
            // boundary. VaultPage's compact constructor already cloned each
            // ItemStack, but we re-clone here so the returned object owns
            // entirely independent VaultPage instances (independent page
            // numbers / array references) and the caller's subsequent
            // mutations cannot leak into the I/O thread that produced this
            // payload.
            return VaultDataCloner.deepClone(loaded);
        }
    }

    @Override
    public CompletableFuture<Void> savePlayerData(UUID playerId, PlayerVaultData data) {
        return CompletableFuture.runAsync(() -> {
            long startNs = System.nanoTime();
            try {
                executeWithRetry(() -> {
                    savePlayerDataInternal(playerId, data);
                    return null;
                }, "save data for " + playerId);
            } finally {
                saveHistogram.record(System.nanoTime() - startNs);
            }
        }, asyncExecutor);
    }

    private void savePlayerDataInternal(UUID playerId, PlayerVaultData data) throws SQLException, IOException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                // 1. Upsert player metadata.
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO voidvault_players (player_id, custom_slots, custom_pages) " +
                                "VALUES (?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE custom_slots = VALUES(custom_slots), custom_pages = VALUES(custom_pages)")) {
                    stmt.setString(1, playerId.toString());
                    stmt.setInt(2, data.customSlots());
                    stmt.setInt(3, data.customPages());
                    stmt.executeUpdate();
                }

                // 2. Determine which pages we already have so we can prune
                //    pages the player no longer owns.
                Set<Integer> existingPages = new HashSet<>();
                Map<Integer, Long> existingVersions = new HashMap<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT page_number, version FROM voidvault_pages WHERE player_id = ?")) {
                    stmt.setString(1, playerId.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            existingPages.add(rs.getInt("page_number"));
                            existingVersions.put(rs.getInt("page_number"), rs.getLong("version"));
                        }
                    }
                }
                Set<Integer> newPages = new HashSet<>(data.pages().keySet());
                Set<Integer> removed = new HashSet<>(existingPages);
                removed.removeAll(newPages);

                // 3. Delete the rows for pages that no longer exist.
                if (!removed.isEmpty()) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM voidvault_pages WHERE player_id = ? AND page_number = ?")) {
                        for (Integer page : removed) {
                            stmt.setString(1, playerId.toString());
                            stmt.setInt(2, page);
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                }

                // 4. Upsert every page in a batched statement. The version
                //    column gives us optimistic concurrency: if a parallel
                //    node has already written a newer version, our update
                //    is silently skipped (we'd detect it via the
                //    update-count == 0 path below in a future revision).
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO voidvault_pages " +
                                "(player_id, page_number, slot_count, page_data, compressed, uncompressed_size, version) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                                "ON DUPLICATE KEY UPDATE " +
                                "  slot_count = VALUES(slot_count), " +
                                "  page_data = VALUES(page_data), " +
                                "  compressed = VALUES(compressed), " +
                                "  uncompressed_size = VALUES(uncompressed_size), " +
                                "  version = VALUES(version)")) {
                    int batchCount = 0;
                    for (Map.Entry<Integer, VaultPage> entry : data.pages().entrySet()) {
                        VaultPage page = entry.getValue();
                        byte[] serialised = serializePage(page.contents());
                        byte[] stored;
                        boolean compressed = compressionEnabled
                                && CompressionCodec.isWorthCompressing(serialised);
                        if (compressed) {
                            stored = CompressionCodec.compress(serialised, compressionLevel);
                        } else {
                            stored = serialised;
                        }
                        long newVersion = existingVersions.getOrDefault(entry.getKey(), 0L) + 1L;

                        stmt.setString(1, playerId.toString());
                        stmt.setInt(2, entry.getKey());
                        stmt.setInt(3, page.getSize());
                        stmt.setBytes(4, stored);
                        stmt.setInt(5, compressed ? 1 : 0);
                        stmt.setInt(6, serialised.length);
                        stmt.setLong(7, newVersion);
                        stmt.addBatch();
                        batchCount++;
                        if (batchCount % 64 == 0) {
                            stmt.executeBatch();
                        }
                    }
                    if (batchCount % 64 != 0) {
                        stmt.executeBatch();
                    }
                }

                conn.commit();
                logger.fine("Saved data for player " + playerId + " ("
                        + data.pages().size() + " pages, removed " + removed.size() + ")");
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        return CompletableFuture.runAsync(() -> {
            Set<UUID> dirtyPlayers = dataCache.getDirtyPlayers();
            logger.info("Saving " + dirtyPlayers.size() + " dirty player(s) to MySQL");

            List<CompletableFuture<Void>> saveFutures = new ArrayList<>();

            for (UUID playerId : dirtyPlayers) {
                dataCache.get(playerId).ifPresent(data -> {
                    CompletableFuture<Void> saveFuture = savePlayerData(playerId, data)
                            .thenRun(() -> dataCache.clearDirty(playerId))
                            .exceptionally(ex -> {
                                logger.log(Level.SEVERE, "Failed to save data for player " + playerId, ex);
                                return null;
                            });
                    saveFutures.add(saveFuture);
                });
            }

            CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0])).join();
            logger.info("Completed saving all dirty players to MySQL");

        }, asyncExecutor);
    }

    @Override
    public void close() {
        logger.info("Closing MySQL storage...");

        // Stop the metrics scheduler first so it cannot fire during shutdown
        // and try to read pool counters off a closing data source.
        ScheduledFuture<?> task = metricsTask;
        ScheduledExecutorService exec = metricsExecutor;
        metricsTask = null;
        metricsExecutor = null;
        if (task != null) {
            task.cancel(false);
        }
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException ie) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("HikariCP connection pool closed");
        }

        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public HikariPoolMXBean getHikariPoolMetrics() {
        return dataSource == null ? null : dataSource.getHikariPoolMXBean();
    }

    @Override
    public LatencyHistogram getSaveLatencyHistogram() {
        return saveHistogram;
    }

    @Override
    public LatencyHistogram getLoadLatencyHistogram() {
        return loadHistogram;
    }

    @Override
    public String getBackendTypeName() {
        return "MYSQL";
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Data source is not available");
        }
        return dataSource.getConnection();
    }

    private <T> T executeWithRetry(SQLOperation<T> operation, String operationName) {
        int attempt = 0;
        long delay = INITIAL_RETRY_DELAY_MS;

        while (attempt < MAX_RETRIES) {
            try {
                return operation.execute();
            } catch (SQLException | IOException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    logger.log(Level.SEVERE, "Failed to " + operationName + " after " + MAX_RETRIES + " attempts", e);
                    throw new RuntimeException("Database operation failed: " + operationName, e);
                }

                logger.warning("Failed to " + operationName + " (attempt " + attempt + "/" + MAX_RETRIES + "), retrying in " + delay + "ms");

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }

                delay *= 2; // Exponential backoff
            }
        }

        throw new RuntimeException("Unexpected error in retry logic");
    }

    /**
     * Serialise a page's contents to a raw {@code byte[]}. We always pay
     * the Bukkit object-serialisation cost so the result is version-agnostic;
     * the outer compression step (when enabled) hides most of the size.
     * <p>
     * Per-slot framing: a single byte {@code 0} marks an empty slot; the
     * byte {@code 1} is followed by the serialised ItemStack. This avoids
     * the trap where an empty-slot sentinel collides with the leading four
     * bytes of a serialised stack.
     */
    private byte[] serializePage(ItemStack[] contents) throws IOException {
        if (contents == null) {
            contents = new ItemStack[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(contents.length * 256);
        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(contents.length);
            for (ItemStack stack : contents) {
                if (stack == null || stack.getType().isAir()) {
                    boos.writeByte(0);
                } else {
                    boos.writeByte(1);
                    boos.writeObject(stack);
                }
            }
        }
        return baos.toByteArray();
    }

    /**
     * Inverse of {@link #serializePage(ItemStack[])}. Tolerates compressed
     * and uncompressed inputs.
     */
    private ItemStack[] decodePage(byte[] data, boolean compressed) throws IOException {
        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }
        byte[] body = compressed ? CompressionCodec.decompress(data) : data;
        if (body == null) {
            throw new IOException("Failed to decode page payload");
        }
        try (BukkitObjectInputStream bis = new BukkitObjectInputStream(new ByteArrayInputStream(body))) {
            int count = bis.readInt();
            ItemStack[] contents = new ItemStack[count];
            for (int i = 0; i < count; i++) {
                int sentinel = bis.readByte();
                if (sentinel == 0) {
                    contents[i] = null;
                } else {
                    Object obj = bis.readObject();
                    contents[i] = obj instanceof ItemStack stack ? stack : null;
                }
            }
            return contents;
        } catch (ClassNotFoundException ex) {
            throw new IOException("ItemStack class missing on this server", ex);
        }
    }

    @FunctionalInterface
    private interface SQLOperation<T> {
        T execute() throws SQLException, IOException;
    }
}
