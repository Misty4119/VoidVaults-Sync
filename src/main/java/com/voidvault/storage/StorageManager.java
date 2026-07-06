package com.voidvault.storage;

import com.voidvault.model.PlayerVaultData;
import com.zaxxer.hikari.HikariPoolMXBean;
import redis.clients.jedis.JedisPool;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for managing vault data persistence.
 * All implementations must support asynchronous operations to prevent main thread blocking.
 * <p>
 * Implementations: YamlStorage, MySqlStorage
 */
public interface StorageManager {

    /**
     * Asynchronously loads a player's vault data from storage.
     *
     * @param playerId The UUID of the player
     * @return A CompletableFuture containing the player's vault data
     */
    CompletableFuture<PlayerVaultData> loadPlayerData(UUID playerId);

    /**
     * Asynchronously saves a player's vault data to storage.
     *
     * @param playerId The UUID of the player
     * @param data     The vault data to save
     * @return A CompletableFuture that completes when the save operation finishes
     */
    CompletableFuture<Void> savePlayerData(UUID playerId, PlayerVaultData data);

    /**
     * Asynchronously saves all cached player data to storage.
     * This is typically called during auto-save or plugin shutdown.
     *
     * @return A CompletableFuture that completes when all save operations finish
     */
    CompletableFuture<Void> saveAll();

    /**
     * Closes the storage manager and releases any resources.
     * This should be called during plugin shutdown.
     */
    void close();

    /**
     * Initializes the storage backend.
     * This is called during plugin startup.
     *
     * @return A CompletableFuture that completes when initialization finishes
     */
    CompletableFuture<Void> initialize();

    // ---------------------------------------------------------------------
    // Metrics hooks — default to "no metrics" so existing implementations
    // (YamlStorage, MySqlStorage, etc.) keep compiling without changes.
    // Only MySqlStorage / RedisStorageManager / RedisBackedMySqlStorage
    // override these to expose pool state to MetricsUtil.
    // ---------------------------------------------------------------------

    /**
     * Underlying HikariCP MX bean, or {@code null} when this storage backend
     * does not use MySQL. Consumed by the bStats / daily-stats metrics
     * pipeline.
     */
    default HikariPoolMXBean getHikariPoolMetrics() {
        return null;
    }

    /**
     * Underlying Jedis pool, or {@code null} when this storage backend does
     * not use Redis.
     */
    default JedisPool getJedisPoolMetrics() {
        return null;
    }

    /**
     * Backend identifier used by the bStats storage_type DrilldownPie chart.
     * Implementations should return one of:
     * {@code YAML}, {@code MYSQL}, {@code REDIS}, {@code REDIS_PERSISTENT}.
     */
    default String getBackendTypeName() {
        return "UNKNOWN";
    }

    /**
     * Latency histogram for save operations. Implementations that do not
     * track latency should still return a histogram instance so the daily
     * log output stays consistent (it will simply report "no samples").
     */
    default LatencyHistogram getSaveLatencyHistogram() {
        return new LatencyHistogram("save");
    }

    /**
     * Latency histogram for load operations. See
     * {@link #getSaveLatencyHistogram()}.
     */
    default LatencyHistogram getLoadLatencyHistogram() {
        return new LatencyHistogram("load");
    }
}
