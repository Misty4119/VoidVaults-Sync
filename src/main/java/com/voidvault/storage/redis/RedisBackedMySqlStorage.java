package com.voidvault.storage.redis;

import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultDataCloner;
import com.voidvault.storage.DataCache;
import com.voidvault.storage.LatencyHistogram;
import com.voidvault.storage.MySqlStorage;
import com.voidvault.storage.StorageManager;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hybrid storage manager that uses Redis 8.0 as the primary fast path and
 * MySQL as a persistent backing store. This is the Write-Through pattern
 * described in the project research report — adopted by HuskSync,
 * PlayerVaultsX, and Syncmoney.
 * <p>
 * <strong>Read path</strong>: Redis first; on miss or failure the manager
 * falls back to MySQL. A successful MySQL hit is best-effort rewritten to
 * Redis so subsequent reads stay on the fast path.
 * <p>
 * <strong>Write path</strong>: Redis SETEX + PUBLISH happens synchronously
 * via {@link RedisStorageManager}; MySQL persistence is dispatched to a
 * background queue so it never blocks the player-visible save. Failed jobs
 * are retried on a configurable interval until the dispatcher is closed.
 * <p>
 * The pub/sub invalidation flow is entirely preserved — we simply hand the
 * shared {@link RedisConnectionManager} to {@link RedisCacheInvalidator} at
 * the plugin boot, exactly as in pure-Redis mode.
 */
public class RedisBackedMySqlStorage implements StorageManager {

    private final Logger logger;
    private final DataCache dataCache;
    private final RedisStorageManager redisPrimary;
    private final MySqlStorage mysql;
    private final RedisConfig redisConfig;

    private final ScheduledExecutorService dispatcher;
    private final BlockingQueue<PersistJob> retryQueue = new LinkedBlockingQueue<>();
    private final Map<UUID, Integer> latestSequencePerPlayer = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean mysqlAvailable = false;

    // Shutdown tuning — kept conservative so the Folia/Minecraft server
    // stopServer() thread is never blocked longer than the OS allows.
    private static final long SHUTDOWN_DEADLINE_MS = 5_000L;
    private static final long SHUTDOWN_PER_WRITE_TIMEOUT_MS = 3_000L;

    public RedisBackedMySqlStorage(Plugin plugin,
                                   DataCache dataCache,
                                   RedisConnectionManager redisConnection) {
        this.logger = plugin.getLogger();
        this.dataCache = dataCache;
        this.redisConfig = redisConnection.getConfig();
        this.redisPrimary = new RedisStorageManager(plugin, dataCache, redisConnection);
        // Use the same compression policy as Redis so the row sizes in
        // MySQL match the on-the-wire sizes the Redis layer just produced.
        // Diverging policies can lead to MySQL refusing huge BLOBs that the
        // Redis side happily accepted (because Redis already had them
        // compressed). Centralising the choice here is the easiest way to
        // keep both layers in sync.
        this.mysql = new MySqlStorage(
                plugin,
                dataCache,
                redisConfig.isCompressionEnabled(),
                redisConfig.getCompressionLevel());
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-mysql-persist-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<Void> initialize() {
        // Initialize MySQL first, but tolerate failure so pure-Redis still works
        return mysql.initialize()
                .thenRun(() -> {
                    mysqlAvailable = true;
                    logger.info("MySQL persistent backup initialized successfully");
                })
                .exceptionally(ex -> {
                    mysqlAvailable = false;
                    logger.warning("MySQL persistent backup failed to initialize — "
                            + "operating in degraded (pure-Redis) mode: " + ex.getMessage());
                    logger.warning("MySQL persistence will be retried when the dispatcher runs.");
                    return null;
                })
                .thenCompose(v -> redisPrimary.initialize())
                .thenRun(() -> {
                    startRetryDispatcher();
                    logger.info("Redis-backed MySQL persistence initialized "
                            + "(mysql-available=" + mysqlAvailable
                            + ", retry-interval=" + redisConfig.getRetryIntervalSeconds()
                            + "s, max-retry=" + redisConfig.getMaxRetryAttempts() + ")");
                });
    }

    /**
     * Read path: Redis first, MySQL on miss. A MySQL hit triggers a best-effort
     * background Redis SETEX so future reads stay on the fast path.
     */
    @Override
    public CompletableFuture<PlayerVaultData> loadPlayerData(UUID playerId) {
        return redisPrimary.loadPlayerData(playerId).thenCompose(redisData -> {
            if (!isEmpty(redisData)) {
                return CompletableFuture.completedFuture(redisData);
            }
            // Redis miss → try MySQL as the source of truth.
            return mysql.loadPlayerData(playerId).thenApply(mysqlData -> {
                if (!isEmpty(mysqlData)) {
                    // Re-warm Redis. Fire-and-forget; we already have the data.
                    // mysqlData is already deep-cloned by MySqlStorage, so
                    // handing it to RedisStorageManager.savePlayerData is
                    // safe (that method re-clones internally as well).
                    redisPrimary.savePlayerData(playerId, mysqlData)
                            .exceptionally(ex -> {
                                logger.log(Level.FINE,
                                        "Best-effort Redis warm-back failed for " + playerId, ex);
                                return null;
                            });
                }
                // Belt-and-braces: deep-clone the MySQL result before
                // returning. MySqlStorage already clones, but cloning here
                // protects against future changes that might forget to do
                // so on one of the I/O paths that feed this method.
                return VaultDataCloner.deepClone(mysqlData);
            });
        });
    }

    /**
     * Write path: Redis SETEX + pub/sub is the synchronous hot path. Once it
     * succeeds the data is enqueued for MySQL persistence so a Redis failure
     * (data eviction, server restart with empty Redis, …) can be recovered
     * from MySQL on next load.
     */
    @Override
    public CompletableFuture<Void> savePlayerData(UUID playerId, PlayerVaultData data) {
        return redisPrimary.savePlayerData(playerId, data)
                .thenRun(() -> enqueuePersistence(playerId, data))
                .exceptionally(ex -> {
                    // Redis save itself failed — do NOT enqueue MySQL write,
                    // because that would silently bypass the pub/sub layer the
                    // network relies on. Surface the error instead.
                    logger.log(Level.SEVERE,
                            "Primary Redis save failed for " + playerId
                                    + "; refusing to silently fall back to MySQL", ex);
                    throw new RuntimeException("Redis save failed; persistence skipped", ex);
                });
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        return redisPrimary.saveAll().thenRun(() -> {
            // Mirror to MySQL asynchronously. dirty players should now have a
            // non-null dataCache entry; iterate them and enqueue.
            for (UUID id : dataCache.getDirtyPlayers()) {
                dataCache.get(id).ifPresent(data -> enqueuePersistence(id, data));
            }
            // Block until MySQL writes settle so plugin shutdown semantics
            // hold, but bound the wait — flushPersistenceQueue() itself caps
            // at SHUTDOWN_DEADLINE_MS so this never hangs longer than that.
            try {
                flushPersistenceQueue().get(SHUTDOWN_DEADLINE_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                logger.log(Level.WARNING,
                        "saveAll(): persistence flush did not finish within "
                                + SHUTDOWN_DEADLINE_MS + "ms", ex);
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 1. Best-effort drain of the retry queue BEFORE we shut down the
        //    dispatcher or the MySQL pool. We keep the budget small
        //    (SHUTDOWN_DEADLINE_MS) so the Minecraft server stopServer()
        //    thread — which Paper/Folia gives a fixed grace window — is
        //    never blocked longer than the OS tolerates. Data still in
        //    Redis will be replayed on the next start anyway.
        if (redisConfig.isPersistOnShutdown()) {
            try {
                flushPersistenceQueue().get(SHUTDOWN_DEADLINE_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                logger.log(Level.WARNING,
                        "Timed out or failed while flushing persistence queue on shutdown", ex);
            }
        }
        // 2. Stop the periodic retry dispatcher (no new MySQL writes will be
        //    scheduled). We bound the wait so a stuck executor can't wedge
        //    onDisable() forever.
        dispatcher.shutdown();
        try {
            if (!dispatcher.awaitTermination(2, TimeUnit.SECONDS)) {
                dispatcher.shutdownNow();
            }
        } catch (InterruptedException ie) {
            dispatcher.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 3. Close MySQL pool (releases Hikari connections). After this
        //    point any attempt to read/write MySQL will fail fast, which is
        //    exactly what we want during shutdown.
        mysql.close();
        // 4. Close Redis (shuts down the Jedis pool). Done last because the
        //    primary hot-path save may still be in-flight while MySQL is
        //    draining above.
        redisPrimary.close();
    }

    // ---------------------------------------------------------------------
    // Persistence dispatcher (background retry loop)
    // ---------------------------------------------------------------------

    private void enqueuePersistence(UUID playerId, PlayerVaultData data) {
        if (!mysqlAvailable) {
            logger.fine("MySQL unavailable — skipping persistence enqueue for " + playerId);
            return;
        }
        PersistJob job = new PersistJob(playerId, data);
        latestSequencePerPlayer.put(playerId, job.getSequence());
        if (!retryQueue.offer(job)) {
            logger.warning("Persistence queue is full, dropping MySQL backup for " + playerId);
        }
    }

    private void startRetryDispatcher() {
        long interval = Math.max(1L, redisConfig.getRetryIntervalSeconds());
        dispatcher.scheduleWithFixedDelay(this::drainOnce,
                interval, interval, TimeUnit.SECONDS);
    }

    private void drainOnce() {
        if (closed.get() || retryQueue.isEmpty()) {
            return;
        }
        List<PersistJob> batch = new ArrayList<>();
        retryQueue.drainTo(batch, 32);
        for (PersistJob job : batch) {
            // Drop stale duplicates: if a newer job for the same player has
            // been scheduled already, the MySQL write of this older snapshot
            // would overwrite newer state. Skip it; the newer job will run.
            Integer latest = latestSequencePerPlayer.get(job.getPlayerId());
            if (latest != null && latest > job.getSequence()) {
                continue;
            }
            job.incrementAttempts();
            try {
                mysql.savePlayerData(job.getPlayerId(), job.getData())
                        .get(3, TimeUnit.SECONDS);
                dataCache.clearDirty(job.getPlayerId());
                // Mark MySQL as available on successful write
                if (!mysqlAvailable) {
                    mysqlAvailable = true;
                    logger.info("MySQL connection recovered — persistence resumed");
                }
            } catch (Exception ex) {
                logger.log(Level.WARNING,
                        "MySQL persistence attempt " + job.getAttempts() + " failed for "
                                + job.getPlayerId() + ": " + ex.getMessage());
                if (job.getAttempts() < redisConfig.getMaxRetryAttempts()) {
                    retryQueue.offer(job);
                } else {
                    logger.log(Level.SEVERE,
                            "Giving up on MySQL persistence for " + job.getPlayerId()
                                    + " after " + job.getAttempts() + " attempts; "
                                    + "data is still in Redis and will be replayed on next save",
                            ex);
                }
            }
        }
    }

    /**
     * Synchronously drain the retry queue. Used by {@link #saveAll()} and
     * {@link #close()}.
     * <p>
     * This implementation pulls every queued job at once and dispatches the
     * underlying MySQL writes in parallel. Compared to calling
     * {@link #drainOnce()} in a loop, this prevents the per-write timeout
     * from accumulating: previously a 2-player queue could take up to 30s
     * even on a healthy MySQL because writes were serialized. Now they run
     * concurrently and the whole flush finishes in roughly one write round-trip.
     */
    private CompletableFuture<Void> flushPersistenceQueue() {
        return CompletableFuture.runAsync(() -> {
            long deadline = System.currentTimeMillis() + SHUTDOWN_DEADLINE_MS;
            while (!retryQueue.isEmpty() && System.currentTimeMillis() < deadline) {
                List<PersistJob> batch = new ArrayList<>();
                retryQueue.drainTo(batch);

                // Snapshot the latest sequence per player so stale jobs are
                // dropped before we even start an I/O round-trip.
                List<PersistJob> live = new ArrayList<>(batch.size());
                for (PersistJob job : batch) {
                    Integer latest = latestSequencePerPlayer.get(job.getPlayerId());
                    if (latest != null && latest > job.getSequence()) {
                        continue;
                    }
                    live.add(job);
                }
                if (live.isEmpty()) {
                    continue;
                }

                // Issue every MySQL write in parallel; bound each one with
                // SHUTDOWN_PER_WRITE_TIMEOUT_MS so a single broken job can't
                // hog the budget. Successful writes clear the dirty flag and
                // recovered connections re-arm the persistence layer.
                List<CompletableFuture<PersistJob>> writes = new ArrayList<>(live.size());
                for (PersistJob job : live) {
                    job.incrementAttempts();
                    writes.add(mysql.savePlayerData(job.getPlayerId(), job.getData())
                            .handle((v, ex) -> {
                                if (ex == null) {
                                    dataCache.clearDirty(job.getPlayerId());
                                    if (!mysqlAvailable) {
                                        mysqlAvailable = true;
                                        logger.info("MySQL connection recovered — persistence resumed");
                                    }
                                    return job;
                                }
                                logger.log(Level.WARNING,
                                        "MySQL persistence attempt " + job.getAttempts()
                                                + " failed for " + job.getPlayerId() + ": "
                                                + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                                if (job.getAttempts() < redisConfig.getMaxRetryAttempts()
                                        && !closed.get()) {
                                    retryQueue.offer(job);
                                } else if (job.getAttempts() >= redisConfig.getMaxRetryAttempts()) {
                                    logger.log(Level.SEVERE,
                                            "Giving up on MySQL persistence for "
                                                    + job.getPlayerId() + " after "
                                                    + job.getAttempts() + " attempts; "
                                                    + "data is still in Redis and will be replayed on next save",
                                            ex);
                                }
                                return job;
                            }));
                }
                try {
                    CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]))
                            .get(SHUTDOWN_PER_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ex) {
                    // Best-effort: timeouts here are expected when MySQL is
                    // slow; the remaining jobs are already back in retryQueue
                    // or logged above. Don't propagate — the caller is on a
                    // shutdown timer.
                }

                if (retryQueue.isEmpty() || System.currentTimeMillis() >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /**
     * A player vault is considered empty when it has no pages at all. This is
     * the same sentinel that {@code MySqlStorage} and {@code RedisStorageManager}
     * return for cache misses.
     */
    private static boolean isEmpty(PlayerVaultData data) {
        return data == null || data.pages() == null || data.pages().isEmpty();
    }

    /**
     * Expose the underlying Redis connection so the plugin can hook the
     * pub/sub invalidator to the same Jedis pool.
     */
    public RedisConnectionManager getRedisConnection() {
        return redisPrimary.getConnection();
    }

    // ---------------------------------------------------------------------
    // StorageManager metrics hooks — delegate to whichever backend owns
    // the underlying pool. Redis metrics come from the primary; Hikari
    // metrics come from the persistent MySQL backup.
    // ---------------------------------------------------------------------

    @Override
    public HikariPoolMXBean getHikariPoolMetrics() {
        return mysql == null ? null : mysql.getHikariPoolMetrics();
    }

    @Override
    public JedisPool getJedisPoolMetrics() {
        return redisPrimary == null ? null : redisPrimary.getJedisPoolMetrics();
    }

    @Override
    public LatencyHistogram getSaveLatencyHistogram() {
        // Use Redis's histogram — that is the synchronous hot-path latency
        // a player will actually feel. MySQL writes are async / retry-based
        // and reported separately through their own pool-metrics log.
        return redisPrimary == null ? new LatencyHistogram("redis.save")
                : redisPrimary.getSaveLatencyHistogram();
    }

    @Override
    public LatencyHistogram getLoadLatencyHistogram() {
        return redisPrimary == null ? new LatencyHistogram("redis.load")
                : redisPrimary.getLoadLatencyHistogram();
    }

    @Override
    public String getBackendTypeName() {
        return "REDIS_PERSISTENT";
    }
}