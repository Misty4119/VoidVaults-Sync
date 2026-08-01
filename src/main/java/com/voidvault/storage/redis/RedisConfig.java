package com.voidvault.storage.redis;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Logger;

/**
 * Strongly-typed view over the {@code storage.redis.*} keys of {@code config.yml}.
 * The defaults are tuned for a single-node Redis 8.0 instance on the same host as
 * the Minecraft server. Every field can be overridden in the config file.
 */
public class RedisConfig {

    private final String host;
    private final int port;
    private final String password;
    private final String username;
    private final boolean useSsl;
    private final int database;
    private final int timeoutMs;
    private final int poolMaxTotal;
    private final int poolMaxIdle;
    private final int poolMinIdle;

    private final String serverId;
    private final String invalidationChannel;
    private final String vaultKeyPrefix;
    private final long keyTtlSeconds;
    private final boolean publishOnSave;
    private final boolean invalidateOnReceive;

    // Persistent MySQL backing. When persistence.enabled is true the
    // RedisBackedMySqlStorage wrapper is selected and every Redis write also
    // (asynchronously) mirrors the data into MySQL for crash-survival.
    private final boolean persistenceEnabled;
    private final boolean persistOnShutdown;
    private final int maxRetryAttempts;
    private final long retryIntervalSeconds;
    private final boolean warmCacheOnStartup;

    // ----- Compression & wire format -------------------------------------------
    private final boolean compressionEnabled;
    private final int compressionLevel;
    private final int compressionMinSize;

    // ----- Diff sync & locking -------------------------------------------------
    private final boolean diffSyncEnabled;
    private final long lockTtlMs;
    private final long reconnectCompensationThresholdSeconds;
    private final long reconnectMaxBackoffSeconds;

    // ----- Migration -----------------------------------------------------------
    private final boolean keepLegacyV1;

    public RedisConfig(String host,
                       int port,
                       String password,
                       String username,
                       boolean useSsl,
                       int database,
                       int timeoutMs,
                       int poolMaxTotal,
                       int poolMaxIdle,
                       int poolMinIdle,
                       String serverId,
                       String invalidationChannel,
                       String vaultKeyPrefix,
                       long keyTtlSeconds,
                       boolean publishOnSave,
                       boolean invalidateOnReceive,
                       boolean persistenceEnabled,
                       boolean persistOnShutdown,
                       int maxRetryAttempts,
                       long retryIntervalSeconds,
                       boolean warmCacheOnStartup,
                       boolean compressionEnabled,
                       int compressionLevel,
                       int compressionMinSize,
                       boolean diffSyncEnabled,
                       long lockTtlMs,
                       long reconnectCompensationThresholdSeconds,
                       long reconnectMaxBackoffSeconds,
                       boolean keepLegacyV1) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.username = username;
        this.useSsl = useSsl;
        this.database = database;
        this.timeoutMs = timeoutMs;
        this.poolMaxTotal = poolMaxTotal;
        this.poolMaxIdle = poolMaxIdle;
        this.poolMinIdle = poolMinIdle;
        this.serverId = serverId;
        this.invalidationChannel = invalidationChannel;
        this.vaultKeyPrefix = vaultKeyPrefix;
        this.keyTtlSeconds = keyTtlSeconds;
        this.publishOnSave = publishOnSave;
        this.invalidateOnReceive = invalidateOnReceive;
        this.persistenceEnabled = persistenceEnabled;
        this.persistOnShutdown = persistOnShutdown;
        this.maxRetryAttempts = maxRetryAttempts;
        this.retryIntervalSeconds = retryIntervalSeconds;
        this.warmCacheOnStartup = warmCacheOnStartup;
        this.compressionEnabled = compressionEnabled;
        this.compressionLevel = compressionLevel;
        this.compressionMinSize = compressionMinSize;
        this.diffSyncEnabled = diffSyncEnabled;
        this.lockTtlMs = lockTtlMs;
        this.reconnectCompensationThresholdSeconds = reconnectCompensationThresholdSeconds;
        this.reconnectMaxBackoffSeconds = reconnectMaxBackoffSeconds;
        this.keepLegacyV1 = keepLegacyV1;
    }

    /**
     * Build a {@link RedisConfig} from the plugin's config.yml, applying sensible
     * defaults for any missing keys. The {@code server-id} defaults to the local
     * hostname, which makes multi-server deployments trivial: each node simply
     * reports its own hostname.
     */
    public static RedisConfig fromConfig(Plugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        Logger log = plugin.getLogger();

        String host = cfg.getString("storage.redis.host", "127.0.0.1");
        int port = cfg.getInt("storage.redis.port", 6379);
        String password = cfg.getString("storage.redis.password", "");
        String username = cfg.getString("storage.redis.username", "");
        boolean useSsl = cfg.getBoolean("storage.redis.use-ssl", false);
        int database = cfg.getInt("storage.redis.database", 0);
        int timeoutMs = cfg.getInt("storage.redis.timeout-ms", 5000);

        int poolMaxTotal = cfg.getInt("storage.redis.pool.max-total", 48);
        int poolMaxIdle = cfg.getInt("storage.redis.pool.max-idle", 16);
        int poolMinIdle = cfg.getInt("storage.redis.pool.min-idle", 8);

        String configuredServerId = cfg.getString("storage.redis.server-id", null);
        String serverId = (configuredServerId != null && !configuredServerId.isBlank())
                ? configuredServerId
                : resolveHostname(log);

        String invalidationChannel = cfg.getString("storage.redis.invalidation-channel", "voidvault:invalidate");
        String vaultKeyPrefix = cfg.getString("storage.redis.key-prefix", "vv:p:");
        long keyTtl = cfg.getLong("storage.redis.key-ttl-seconds", 30L * 24L * 60L * 60L); // 30 days
        boolean publishOnSave = cfg.getBoolean("storage.redis.publish-on-save", true);
        boolean invalidateOnReceive = cfg.getBoolean("storage.redis.invalidate-on-receive", true);

        // Persistence (MySQL backup) sub-block. Defaults keep the behavior
        // identical to the previous "pure Redis" mode.
        boolean persistenceEnabled = cfg.getBoolean("storage.redis.persistence.enabled", false);
        boolean persistOnShutdown = cfg.getBoolean("storage.redis.persistence.persist-on-shutdown", true);
        int maxRetryAttempts = cfg.getInt("storage.redis.persistence.max-retry-attempts", 5);
        long retryIntervalSeconds = cfg.getLong("storage.redis.persistence.retry-interval-seconds", 30);
        boolean warmCacheOnStartup = cfg.getBoolean("storage.redis.persistence.warm-cache-on-startup", false);

        // Compression. Default: enabled, level 6 (zlib default), 256 byte floor.
        boolean compressionEnabled = cfg.getBoolean("storage.redis.compression.enabled", true);
        int compressionLevel = cfg.getInt("storage.redis.compression.level", 6);
        int compressionMinSize = cfg.getInt("storage.redis.compression.min-size", 256);

        // Diff sync: when true the publisher emits INVALIDATE_PAGE with the
        // touched page numbers instead of INVALIDATE_FULL on every save.
        boolean diffSyncEnabled = cfg.getBoolean("storage.redis.diff-sync.enabled", true);
        long lockTtlMs = cfg.getLong("storage.redis.diff-sync.lock-ttl-ms", 5_000L);
        // Subscriber reconnect tuning (P1-2). Compensation threshold is the
        // minimum gap (seconds) before reconnect triggers a wholesale local
        // cache eviction; max backoff caps the exponential reconnect delay.
        long reconnectCompensationThresholdSeconds =
                cfg.getLong("storage.redis.diff-sync.reconnect-compensation-threshold-seconds", 30L);
        long reconnectMaxBackoffSeconds =
                cfg.getLong("storage.redis.diff-sync.reconnect-max-backoff-seconds", 30L);

        // Migration helper: keep the legacy v1 blob in Redis alongside the new
        // per-page layout. Disable once every node in the network has been
        // upgraded and you've verified nothing still reads the legacy blob.
        boolean keepLegacyV1 = cfg.getBoolean("storage.redis.migration.keep-legacy-v1", false);

        return new RedisConfig(
                host, port, password, username, useSsl, database, timeoutMs,
                poolMaxTotal, poolMaxIdle, poolMinIdle,
                serverId, invalidationChannel, vaultKeyPrefix, keyTtl,
                publishOnSave, invalidateOnReceive,
                persistenceEnabled, persistOnShutdown, maxRetryAttempts,
                retryIntervalSeconds, warmCacheOnStartup,
                compressionEnabled, compressionLevel, compressionMinSize,
                diffSyncEnabled, lockTtlMs,
                reconnectCompensationThresholdSeconds, reconnectMaxBackoffSeconds,
                keepLegacyV1);
    }

    private static String resolveHostname(Logger log) {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            log.warning("Could not resolve local hostname, falling back to 'unknown-server': " + ex.getMessage());
            return "unknown-server";
        }
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getPassword() { return password; }
    public String getUsername() { return username; }
    public boolean isUseSsl() { return useSsl; }
    public int getDatabase() { return database; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getPoolMaxTotal() { return poolMaxTotal; }
    public int getPoolMaxIdle() { return poolMaxIdle; }
    public int getPoolMinIdle() { return poolMinIdle; }
    public String getServerId() { return serverId; }
    public String getInvalidationChannel() { return invalidationChannel; }
    public String getVaultKeyPrefix() { return vaultKeyPrefix; }
    public long getKeyTtlSeconds() { return keyTtlSeconds; }
    public boolean isPublishOnSave() { return publishOnSave; }
    public boolean isInvalidateOnReceive() { return invalidateOnReceive; }
    public boolean isPersistenceEnabled() { return persistenceEnabled; }
    public boolean isPersistOnShutdown() { return persistOnShutdown; }
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public long getRetryIntervalSeconds() { return retryIntervalSeconds; }
    public boolean isWarmCacheOnStartup() { return warmCacheOnStartup; }
    public boolean isCompressionEnabled() { return returnTrue(compressionEnabled); }
    public int getCompressionLevel() { return compressionLevel; }
    public int getCompressionMinSize() { return compressionMinSize; }
    public boolean isDiffSyncEnabled() { return diffSyncEnabled; }
    public long getLockTtlMs() { return lockTtlMs; }
    public long getReconnectCompensationThresholdSeconds() {
        return reconnectCompensationThresholdSeconds;
    }
    public long getReconnectMaxBackoffSeconds() {
        return reconnectMaxBackoffSeconds;
    }
    public boolean isKeepLegacyV1() { return keepLegacyV1; }

    private static boolean returnTrue(boolean v) { return v; }
}
