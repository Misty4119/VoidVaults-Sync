package com.voidvault.util;

import com.voidvault.storage.DataCache;
import com.voidvault.storage.StorageManager;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.JedisPool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * bStats integration + periodic local stats logger.
 * <p>
 * Two responsibilities live here so {@link com.voidvault.VoidVaultPlugin}
 * does not have to know about either bStats internals or pool MX beans:
 * <ol>
 *     <li>Push four custom charts to bStats on every collection (cache size,
 *         Hikari active connections, Jedis active connections, backend type).</li>
 *     <li>Log a compact daily stats block — every 24h — so operators have a
 *         plaintext trail to inspect on machines that are not connected to
 *         bStats (or for after-the-fact debugging when bStats itself is the
 *         thing that broke).</li>
 * </ol>
 *
 * <h2>Drilldown pie chart for storage_type</h2>
 * bStats 3.x DrilldownPie expects a single-Key Map (the high-level category)
 * pointing at a Map of {label: count}. Each server reports exactly one
 * backend, so the outer key is hard-coded to {@code "backend"} and the inner
 * map contains a single {@code 1}-count entry for whichever backend this
 * server was started with.
 */
public final class MetricsUtil {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicReference<DailyLogContext> contextRef = new AtomicReference<>();
    private static ScheduledExecutorService dailyExecutor;
    private static ScheduledFuture<?> dailyTask;

    private MetricsUtil() {}

    /**
     * Register the bStats charts and start the daily stats scheduler.
     * Idempotent: subsequent calls are no-ops.
     *
     * @param plugin         The owning plugin
     * @param dataCache      Cache to sample for the size chart
     * @param storageManager Storage backend whose pools feed Hikari/Jedis charts
     */
    public static void register(Plugin plugin, DataCache dataCache, StorageManager storageManager) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Logger logger = plugin.getLogger();
        try {
            Metrics metrics = new Metrics(plugin, 28100);
            metrics.addCustomChart(buildCacheSizeChart(dataCache));
            metrics.addCustomChart(buildHikariChart(storageManager));
            metrics.addCustomChart(buildJedisChart(storageManager));
            metrics.addCustomChart(buildBackendChart(storageManager));
            logger.info("bStats metrics registered (cache-size, mysql-pool, redis-pool, storage-type)");
        } catch (Exception e) {
            logger.warning("Failed to register bStats metrics: " + e.getMessage());
        }

        contextRef.set(new DailyLogContext(plugin.getLogger(), dataCache, storageManager));
        startDailyLogger(plugin);
    }

    /**
     * Variant that skips the bStats Metrics object creation. Useful when the
     * caller has already created one (e.g. the {@code initializeMetrics()}
     * call in {@code VoidVaultPlugin}) and only wants the custom charts +
     * daily scheduler wired up.
     */
    public static void registerChartsOnly(Plugin plugin, DataCache dataCache, StorageManager storageManager) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Logger logger = plugin.getLogger();
        try {
            // We still register a Metrics instance so bStats can collect
            // the player/server chart. The plugin id is the same as the
            // base Metrics; bStats deduplicates by plugin id internally.
            Metrics metrics = new Metrics(plugin, 28100);
            metrics.addCustomChart(buildCacheSizeChart(dataCache));
            metrics.addCustomChart(buildHikariChart(storageManager));
            metrics.addCustomChart(buildJedisChart(storageManager));
            metrics.addCustomChart(buildBackendChart(storageManager));
            logger.info("bStats custom charts registered (cache-size, mysql-pool, redis-pool, storage-type)");
        } catch (Exception e) {
            logger.warning("Failed to register bStats custom charts: " + e.getMessage());
        }

        contextRef.set(new DailyLogContext(plugin.getLogger(), dataCache, storageManager));
        startDailyLogger(plugin);
    }

    /**
     * Stop the daily scheduler. Safe to call multiple times. Called from
     * {@link com.voidvault.VoidVaultPlugin#onDisable()}.
     */
    public static void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> task = dailyTask;
        ScheduledExecutorService exec = dailyExecutor;
        dailyTask = null;
        dailyExecutor = null;
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
        contextRef.set(null);
    }

    /**
     * Force the daily logger to run right now. Used by onDisable so the
     * final stats block is emitted before the plugin exits.
     */
    public static void runDailyStatsNow() {
        DailyLogContext ctx = contextRef.get();
        if (ctx != null) {
            ctx.emitDailyStats();
        }
    }

    private static CustomChart buildCacheSizeChart(DataCache dataCache) {
        return new SingleLineChart("vault_cache_size", () -> dataCache.size());
    }

    private static CustomChart buildHikariChart(StorageManager storageManager) {
        return new SingleLineChart("mysql_pool_active", () -> {
            HikariPoolMXBean mx = storageManager.getHikariPoolMetrics();
            return mx == null ? 0 : mx.getActiveConnections();
        });
    }

    private static CustomChart buildJedisChart(StorageManager storageManager) {
        return new SingleLineChart("redis_pool_active", () -> {
            JedisPool pool = storageManager.getJedisPoolMetrics();
            return pool == null ? 0 : pool.getNumActive();
        });
    }

    private static CustomChart buildBackendChart(StorageManager storageManager) {
        return new DrilldownPie("storage_type", () -> {
            Map<String, Map<String, Integer>> outer = new HashMap<>();
            Map<String, Integer> inner = new HashMap<>();
            inner.put(storageManager.getBackendTypeName(), 1);
            outer.put("backend", inner);
            return outer;
        });
    }

    private static void startDailyLogger(Plugin plugin) {
        dailyExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-daily-stats");
            t.setDaemon(true);
            return t;
        });
        dailyTask = dailyExecutor.scheduleAtFixedRate(
                () -> {
                    DailyLogContext ctx = contextRef.get();
                    if (ctx != null) {
                        ctx.emitDailyStats();
                    }
                },
                24 * 60 * 60, 24 * 60 * 60, TimeUnit.SECONDS);
    }

    /**
     * Captured snapshot of the dependencies needed to render the daily
     * stats block. Stored in an {@link AtomicReference} so {@link #shutdown()}
     * can null it out cleanly even from a different thread.
     */
    private static final class DailyLogContext {
        private final Logger logger;
        private final DataCache dataCache;
        private final StorageManager storageManager;

        DailyLogContext(Logger logger, DataCache dataCache, StorageManager storageManager) {
            this.logger = logger;
            this.dataCache = dataCache;
            this.storageManager = storageManager;
        }

        void emitDailyStats() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("\n[Daily Stats]");
            sb.append("\n  Cache: size=").append(dataCache.size())
                    .append(", dirty=").append(dataCache.dirtyCount())
                    .append(", max=").append(dataCache.getMaxSize());
            HikariPoolMXBean hikari = storageManager.getHikariPoolMetrics();
            if (hikari != null) {
                sb.append("\n  Hikari: active=").append(hikari.getActiveConnections())
                        .append(", idle=").append(hikari.getIdleConnections())
                        .append(", total=").append(hikari.getTotalConnections())
                        .append(", waiting=").append(hikari.getThreadsAwaitingConnection());
            } else {
                sb.append("\n  Hikari: (not used by this backend)");
            }
            JedisPool jedis = storageManager.getJedisPoolMetrics();
            if (jedis != null) {
                sb.append("\n  Jedis:  active=").append(jedis.getNumActive())
                        .append(", idle=").append(jedis.getNumIdle())
                        .append(", waiting=").append(jedis.getNumWaiters());
            } else {
                sb.append("\n  Jedis:  (not used by this backend)");
            }
            sb.append("\n  Save: ").append(storageManager.getSaveLatencyHistogram().summary());
            sb.append("\n  Load: ").append(storageManager.getLoadLatencyHistogram().summary());
            sb.append("\n  Backend: ").append(storageManager.getBackendTypeName());
            logger.info(sb.toString());
        }
    }
}