package com.voidvault.storage.redis;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin wrapper around a Jedis connection pool.
 * <p>
 * The pool is constructed once during plugin enable and lives until disable.
 * Jedis {@code JedisPool} is thread-safe; callers should use the
 * try-with-resources pattern to borrow and return connections.
 */
public class RedisConnectionManager implements AutoCloseable {

    private final RedisConfig config;
    private final Logger logger;
    private JedisPool pool;

    // Hourly pool-metrics scheduler. Separate daemon thread to avoid
    // blocking the virtual-thread executor the storage layer uses.
    private final AtomicBoolean metricsStarted = new AtomicBoolean(false);
    private ScheduledExecutorService metricsExecutor;
    private ScheduledFuture<?> metricsTask;

    public RedisConnectionManager(RedisConfig config, Plugin plugin) {
        this.config = config;
        this.logger = plugin.getLogger();
    }

    /**
     * Initialize the connection pool. Pings the server to fail fast on bad
     * configuration. Returns {@code true} when the server responded to PING.
     * <p>
     * Jedis 7.4.1 does not expose a constructor that accepts both a custom
     * {@link GenericObjectPoolConfig} and {@link JedisClientConfig}, so we
     * build the pool from the {@code (HostAndPort, JedisClientConfig)}
     * constructor and then apply our tuning through
     * {@link org.apache.commons.pool2.impl.GenericObjectPool#setConfig}, which
     * is inherited via the chain {@code JedisPool -> Pool -> GenericObjectPool}.
     */
    public boolean initialize() {
        DefaultJedisClientConfig.Builder clientBuilder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(config.getTimeoutMs())
                .socketTimeoutMillis(config.getTimeoutMs())
                .database(config.getDatabase())
                .ssl(config.isUseSsl());
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            clientBuilder.password(config.getPassword());
        }
        JedisClientConfig clientConfig = clientBuilder.build();

        this.pool = new JedisPool(new HostAndPort(config.getHost(), config.getPort()), clientConfig);

        GenericObjectPoolConfig<Jedis> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(config.getPoolMaxTotal());
        poolConfig.setMaxIdle(config.getPoolMaxIdle());
        poolConfig.setMinIdle(config.getPoolMinIdle());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        // validate returned connections: avoids "stale connection" errors
        // when a Redis primary is swapped behind a load balancer.
        poolConfig.setTestOnReturn(true);
        // Fail fast: when the pool is exhausted, throw NoSuchElementException
        // after 2s instead of blocking the calling thread indefinitely.
        // Combined with setMaxWaitMillis below this prevents a slow Redis
        // from holding up the main thread.
        poolConfig.setBlockWhenExhausted(false);
        poolConfig.setMaxWaitMillis(2000L);
        // GenericObjectPool#setConfig exists and copies our tuning onto the
        // pool's internal config (preserving the factory the constructor set).
        this.pool.setConfig(poolConfig);

        try (Jedis jedis = pool.getResource()) {
            String pong = jedis.ping();
            logger.info("Redis connection established (PING -> " + pong + ") at "
                    + config.getHost() + ":" + config.getPort());
            return true;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Failed to ping Redis at "
                    + config.getHost() + ":" + config.getPort(), ex);
            return false;
        }
    }

    /**
     * Spin up an hourly pool-metrics heartbeat. Safe to call multiple
     * times — only the first call creates the executor.
     */
    public void startMetricsLogger() {
        if (!metricsStarted.compareAndSet(false, true)) {
            return;
        }
        logPoolStats("initial");
        metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-redis-metrics");
            t.setDaemon(true);
            return t;
        });
        metricsTask = metricsExecutor.scheduleAtFixedRate(
                this::logPoolStats,
                1, 1, TimeUnit.HOURS);
    }

    private void logPoolStats() {
        logPoolStats("hourly");
    }

    private void logPoolStats(String source) {
        if (pool == null || pool.isClosed()) {
            return;
        }
        logger.info(() -> "[Jedis:" + source + "] active=" + pool.getNumActive()
                + " idle=" + pool.getNumIdle()
                + " total=" + pool.getNumActive() + "+" + pool.getNumIdle()
                + " waiting=" + pool.getNumWaiters()
                + " (maxTotal=" + config.getPoolMaxTotal()
                + ", maxIdle=" + config.getPoolMaxIdle()
                + ", minIdle=" + config.getPoolMinIdle() + ")");
    }

    public JedisPool getPool() {
        return pool;
    }

    public RedisConfig getConfig() {
        return config;
    }

    @Override
    public void close() {
        // Stop the metrics scheduler first so it cannot try to read pool
        // stats off a closing pool.
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
        if (pool != null && !pool.isClosed()) {
            try {
                pool.close();
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Error closing Redis pool", ex);
            }
        }
    }
}