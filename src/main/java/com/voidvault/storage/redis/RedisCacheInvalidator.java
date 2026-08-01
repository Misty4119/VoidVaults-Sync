package com.voidvault.storage.redis;

import com.voidvault.storage.DataCache;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens to a Redis pub/sub channel and clears any locally cached vault data
 * when another server broadcasts an invalidation.
 * <p>
 * The subscriber uses Jedis's blocking {@code subscribe} API, which is why it
 * runs on a dedicated single-thread executor rather than the Folia scheduler.
 * The {@link JedisPubSub#onMessage} callback only enqueues the raw payload
 * onto a bounded {@link BlockingQueue}; a separate worker thread drains the
 * queue and performs the actual {@link DataCache#remove} / version tracking
 * on its own thread. Splitting the two responsibilities keeps the Jedis
 * subscriber thread from blocking on cache mutations under a flood of
 * invalidations, which is what used to wedge the pub/sub channel.
 *
 * <h2>Backpressure</h2>
 * The pending queue is bounded to {@link #PENDING_CAPACITY} entries. When
 * it overflows the oldest queued message is dropped (logged as a warning)
 * and the newest message is enqueued. This trades latency under load for
 * forward progress — the rationale is that newer invalidations supersede
 * older ones, so keeping the freshest signal is more valuable than
 * processing every one.
 *
 * <h2>Reconnect 冪等</h2>
 * Every successful (re)connection records the timestamp at which the last
 * {@code onMessage} fired. After re-subscribing we measure the gap; if it
 * exceeds the configured threshold we proactively evict every locally
 * cached player, forcing a fresh read from Redis / MySQL on the next access.
 * Reconnect itself uses exponential backoff (1s → 2s → 4s → ... → cap)
 * so a flapping Redis cannot pin a thread.
 *
 * <h2>Fine-grained invalidation</h2>
 * Newer writers send {@link RedisMessage#TYPE_INVALIDATE_PAGE} with the list
 * of changed page numbers. The subscriber records the writer's version and
 * only drops the local copy if the message is strictly newer than what we
 * already know.
 *
 * <h2>Heartbeat</h2>
 * A periodic heartbeat is published every 60 seconds so that other nodes can
 * detect whether this server's pub/sub subscription is healthy.
 */
public class RedisCacheInvalidator implements AutoCloseable {

    /**
     * Maximum number of pending messages the worker thread will keep in
     * memory. Sized for a worst-case 1000-message flood + ~1ms processing
     * per message; once full we drop the oldest queued payload.
     */
    private static final int PENDING_CAPACITY = 4096;

    private final RedisConnectionManager connection;
    private final DataCache dataCache;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Highest version seen for each player across both full and page-level
     * invalidations. Used to drop replayed or out-of-order messages.
     */
    private final Map<UUID, Long> lastSeenVersion = new ConcurrentHashMap<>();

    /**
     * Timestamp (ms) of the most recent {@code onMessage} delivery. Updated
     * on every successful message parse; consulted after reconnect to
     * detect a gap long enough to warrant a wholesale cache eviction.
     */
    private volatile long lastMessageAt = 0L;

    /**
     * Bounded queue decoupling the Jedis subscriber thread from the worker
     * that performs the actual cache mutations.
     */
    private final BlockingQueue<RedisMessage> pending = new LinkedBlockingQueue<>(PENDING_CAPACITY);

    private ScheduledExecutorService executor;
    private ScheduledExecutorService heartbeatExecutor;
    private Thread workerThread;
    private volatile JedisPubSub pubSub;

    public RedisCacheInvalidator(RedisConnectionManager connection,
                                 DataCache dataCache,
                                 Plugin plugin) {
        this.connection = connection;
        this.dataCache = dataCache;
        this.logger = plugin.getLogger();
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-redis-subscriber");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::runSubscriberLoop);

        // Async worker: drains pending messages and applies the actual
        // DataCache mutations on its own thread so the Jedis subscriber
        // thread is never blocked by cache I/O.
        workerThread = new Thread(this::runWorkerLoop, "voidvault-invalidate-worker");
        workerThread.setDaemon(true);
        workerThread.start();

        // Start heartbeat publisher
        startHeartbeat();
    }

    /**
     * Publishes a HEARTBEAT message every 60 seconds so other nodes can
     * detect whether this server's pub/sub connection is alive.
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-redis-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            RedisConfig cfg = connection.getConfig();
            RedisMessage msg = RedisMessage.heartbeat(cfg.getServerId());
            try (Jedis jedis = connection.getPool().getResource()) {
                jedis.publish(cfg.getInvalidationChannel(), msg.toJson());
                logger.fine("Published heartbeat (server-id=" + cfg.getServerId() + ")");
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed to publish heartbeat", ex);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void runSubscriberLoop() {
        RedisConfig cfg = connection.getConfig();
        if (!cfg.isInvalidateOnReceive()) {
            logger.info("Redis invalidation reception disabled by config.");
            return;
        }

        long maxBackoffMs = Math.max(1_000L, cfg.getReconnectMaxBackoffSeconds() * 1_000L);
        // AtomicLong so the inner JedisPubSub callback can reset it after
        // a successful subscribe without violating the "effectively final"
        // rule for captured locals.
        AtomicLong backoffMs = new AtomicLong(1_000L);

        while (running.get()) {
            final long connectedAt = System.currentTimeMillis();
            try (Jedis jedis = connection.getPool().getResource()) {
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleEnqueue(message);
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        logger.info("Subscribed to Redis channel '" + channel
                                + "' for cross-server invalidation");
                        // Reconnect compensation: if we just reconnected
                        // after a gap long enough to suspect dropped
                        // messages, evict the local cache proactively.
                        long gapSeconds = (connectedAt - lastMessageAt) / 1_000L;
                        if (lastMessageAt > 0L
                                && gapSeconds >= cfg.getReconnectCompensationThresholdSeconds()) {
                            compensateAfterReconnect(gapSeconds);
                        }
                        // Reset backoff after a successful connect.
                        backoffMs.set(1_000L);
                    }
                };
                jedis.subscribe(pubSub, cfg.getInvalidationChannel());
            } catch (Exception ex) {
                if (!running.get()) return;
                long current = backoffMs.get();
                logger.log(Level.WARNING, "Redis subscriber disconnected, "
                        + "reconnecting in " + current + "ms", ex);
                try {
                    Thread.sleep(current);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Exponential backoff with a hard cap.
                backoffMs.set(Math.min(current * 2L, maxBackoffMs));
            } finally {
                pubSub = null;
            }
        }
    }

    /**
     * Called from the Jedis subscriber thread. Decodes the raw payload and
     * enqueues it for the worker thread; this is intentionally cheap so the
     * subscriber thread is never blocked on cache mutations.
     */
    private void handleEnqueue(String message) {
        RedisMessage msg = RedisMessage.fromJson(message);
        if (msg == null) {
            logger.fine(() -> "Discarded malformed Redis message (length="
                    + (message == null ? 0 : message.length()) + ")");
            return;
        }
        if (msg.getOriginServer() != null
                && msg.getOriginServer().equals(connection.getConfig().getServerId())) {
            // Self-published, no need to invalidate local cache.
            return;
        }
        // Heartbeats still update lastMessageAt so reconnect math works
        // even when the channel is otherwise idle.
        lastMessageAt = System.currentTimeMillis();
        offerWithBackpressure(msg);
    }

    /**
     * Push a message onto the pending queue, dropping the oldest entry if
     * the queue is full. Dropped entries are logged as a warning so a
     * persistent overflow is visible at runtime.
     */
    private void offerWithBackpressure(RedisMessage msg) {
        if (!pending.offer(msg)) {
            RedisMessage dropped = pending.poll();
            if (dropped != null) {
                logger.warning("Pending queue full; dropped oldest invalidation ("
                        + dropped + ") to make room for " + msg);
            }
            if (!pending.offer(msg)) {
                // Even after dropping one, the offer can fail if the worker
                // is wedged. Drop the new message instead of blocking.
                logger.warning("Pending queue still full after eviction; "
                        + "dropping incoming invalidation " + msg);
            }
        }
    }

    /**
     * Worker thread: drains pending messages and applies the actual
     * DataCache mutations. The {@code running} flag combined with
     * {@code pending.poll(timeout, MS)} lets {@link #close()} wake us up
     * promptly.
     */
    private void runWorkerLoop() {
        while (running.get()) {
            try {
                RedisMessage msg = pending.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    process(msg);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Invalidate worker crashed processing message", t);
            }
        }
        // Drain remaining entries on the way out so a close() right after
        // a burst of messages still applies them (best-effort).
        RedisMessage leftover;
        while ((leftover = pending.poll()) != null) {
            try {
                process(leftover);
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Invalidate worker failed during drain", t);
            }
        }
    }

    private void process(RedisMessage msg) {
        if (RedisMessage.TYPE_INVALIDATE_FULL.equals(msg.getType())) {
            if (msg.getPlayerId() == null) return;
            if (isStale(msg.getPlayerId(), msg.getVersion())) return;
            lastSeenVersion.put(msg.getPlayerId(), msg.getVersion());
            dataCache.remove(msg.getPlayerId());
            logger.fine("Local cache invalidated (full) for " + msg.getPlayerId()
                    + " (origin=" + msg.getOriginServer() + ", version=" + msg.getVersion() + ")");
        } else if (RedisMessage.TYPE_INVALIDATE_PAGE.equals(msg.getType())) {
            if (msg.getPlayerId() == null) return;
            if (isStale(msg.getPlayerId(), msg.getVersion())) return;
            lastSeenVersion.put(msg.getPlayerId(), msg.getVersion());
            // We currently drop the whole local cache for any page-level
            // change because the in-memory representation is the whole vault
            // (DataCache). A future optimisation can keep per-page slices
            // and evict only the listed page numbers.
            dataCache.remove(msg.getPlayerId());
            logger.fine("Local cache invalidated (pages="
                    + (msg.getPages() == null ? "*" : msg.getPages().length)
                    + ") for " + msg.getPlayerId()
                    + " (origin=" + msg.getOriginServer() + ", version=" + msg.getVersion() + ")");
        } else if (RedisMessage.TYPE_HEARTBEAT.equals(msg.getType())) {
            logger.fine("Received Redis heartbeat from " + msg.getOriginServer());
        }
    }

    /**
     * Wholesale eviction of locally cached entries after a long reconnect
     * gap. Drops every entry in {@link DataCache}; the next read will hit
     * Redis / MySQL and rebuild the cache from the authoritative source.
     * <p>
     * The total count of evicted entries is logged at INFO so operators
     * can correlate spikes in cache misses with network events.
     */
    private void compensateAfterReconnect(long gapSeconds) {
        var cached = dataCache.getCachedPlayers();
        if (cached.isEmpty()) {
            logger.fine(() -> "Reconnect gap " + gapSeconds + "s with empty cache; nothing to evict");
            return;
        }
        logger.warning("Reconnect gap " + gapSeconds + "s exceeded compensation threshold; "
                + "evicting " + cached.size() + " cached player(s) to force re-sync from source");
        for (UUID id : cached) {
            dataCache.remove(id);
        }
    }

    private boolean isStale(UUID playerId, long version) {
        Long seen = lastSeenVersion.get(playerId);
        return seen != null && seen >= version;
    }

    /**
     * Visible for testing — returns the highest version seen locally for a
     * given player, or 0L when no message has been processed yet.
     */
    public long getLastSeenVersion(UUID playerId) {
        return lastSeenVersion.getOrDefault(playerId, 0L);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        JedisPubSub ps = pubSub;
        if (ps != null) {
            try {
                ps.unsubscribe();
            } catch (Exception ignored) {}
        }
        // Worker thread will exit on its own when running flips to false;
        // join briefly so we don't leak it past the plugin shutdown.
        Thread w = workerThread;
        if (w != null) {
            try {
                w.join(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        shutdownExecutor(executor, "subscriber");
        shutdownExecutor(heartbeatExecutor, "heartbeat");
        pending.clear();
    }

    private void shutdownExecutor(ScheduledExecutorService exec, String name) {
        if (exec == null) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException ie) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
