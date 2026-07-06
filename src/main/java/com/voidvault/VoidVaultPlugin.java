package com.voidvault;

import com.voidvault.command.EChestCommand;
import com.voidvault.command.VoidVaultCommand;
import com.voidvault.command.VoidVaultTabCompleter;
import com.voidvault.config.ConfigManager;
import com.voidvault.config.MessageManager;
import com.voidvault.integration.PlaceholderAPIHook;
import com.voidvault.listener.EnderChestListener;
import com.voidvault.listener.PlayerConnectionListener;
import com.voidvault.listener.SearchInputListener;
import com.voidvault.listener.VaultInventoryListener;
import com.voidvault.manager.CooldownManager;
import com.voidvault.manager.EconomyManager;
import com.voidvault.manager.PermissionManager;
import com.voidvault.manager.VaultManager;
import com.voidvault.storage.DataCache;
import com.voidvault.storage.MySqlStorage;
import com.voidvault.storage.StorageManager;
import com.voidvault.storage.YamlStorage;
import com.voidvault.storage.redis.RedisBackedMySqlStorage;
import com.voidvault.storage.redis.RedisCacheInvalidator;
import com.voidvault.storage.redis.RedisConfig;
import com.voidvault.storage.redis.RedisConnectionManager;
import com.voidvault.storage.redis.RedisStorageManager;
import com.voidvault.util.MetricsUtil;
import com.voidvault.util.SchedulerUtil;
import org.bstats.bukkit.Metrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Main plugin class for VoidVault
 * Modern, performance-first Ender Chest replacement with dual-mode system
 */
public class VoidVaultPlugin extends JavaPlugin {

    // Core managers
    private ConfigManager configManager;
    private MessageManager messageManager;
    private PermissionManager permissionManager;
    private CooldownManager cooldownManager;
    private EconomyManager economyManager;
    private VaultManager vaultManager;
    
    // Storage
    private DataCache dataCache;
    private StorageManager storageManager;
    private RedisCacheInvalidator redisInvalidator;

    // Integrations
    private PlaceholderAPIHook placeholderAPIHook;

    // Auto-save task
    private AutoSaveTask autoSaveTask;

    @Override
    public void onEnable() {
        getLogger().info("VoidVault is enabling...");
        
try {
            // Initialize SchedulerUtil for Folia detection
            SchedulerUtil.init(this);

            // Initialize all managers
            initializeManagers();

            // Initialize storage
            initializeStorage();

            // Register event listeners
            registerListeners();

            // Register commands
            registerCommands();

            // Set up integrations
            setupIntegrations();

            // Start auto-save task
            startAutoSave();

            // Initialize bStats
            initializeMetrics();

            // Start the periodic DataCache cleanup scheduler (P0-1). Started
            // last so the storage manager is already in place when the
            // cleanup pass tries to flush dirty entries.
            startDataCacheCleanup();

            getLogger().info("VoidVault enabled successfully!");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable VoidVault", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("VoidVault is disabling...");

        try {
            // Phase 1: stop the Redis invalidation subscriber before
            // tearing down the connection pool so we don't drop a half-
            // closed Jedis connection.
            if (redisInvalidator != null) {
                redisInvalidator.close();
                getLogger().info("[Shutdown] Phase 1/3: Redis subscriber stopped.");
            }

            // Stop auto-save task so it cannot race saveAll().
            if (autoSaveTask != null) {
                autoSaveTask.cancel();
                getLogger().info("Auto-save task stopped.");
            }

            // Stop DataCache cleanup scheduler before we tear the storage
            // manager down (the cleanup tick calls savePlayerData).
            if (dataCache != null) {
                dataCache.stopCleanup();
            }

            // Phase 2: drain the in-memory cache to storage.
            if (storageManager != null && dataCache != null) {
                getLogger().info("[Shutdown] Phase 2/3: Flushing dirty cache to storage "
                        + "(size=" + dataCache.size() + ", dirty=" + dataCache.dirtyCount() + ")...");
                saveAllDataSync();
                getLogger().info("[Shutdown] Phase 2/3: Flush complete.");
            }

            // Emit a final daily-stats block so the operator has a
            // snapshot of the live pool state right before the pools close.
            MetricsUtil.runDailyStatsNow();

            // Phase 3: tear down storage manager (closes Hikari + Jedis pools).
            if (storageManager != null) {
                getLogger().info("[Shutdown] Phase 3/3: Closing pools...");
                storageManager.close();
                getLogger().info("[Shutdown] Phase 3/3: Pools closed.");
            }

            // Stop the bStats / daily logger scheduler.
            MetricsUtil.shutdown();

            // Stop cooldown cleanup task
            if (cooldownManager != null) {
                cooldownManager.shutdown();
            }

            getLogger().info("VoidVault disabled successfully.");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error during plugin shutdown", e);
        }
    }
    
    /**
     * Initialize all manager instances.
     */
    private void initializeManagers() {
        getLogger().info("Initializing managers...");
        
        // Configuration managers
        configManager = new ConfigManager(this);
        configManager.load();
        
        messageManager = new MessageManager(this);
        messageManager.load();
        
        // Data cache (P0-1: LRU + storage-aware eviction). The storageManager
        // reference is wired in after initializeStorage() via
        // dataCache.setStorageManager(...) below.
        dataCache = new DataCache(getLogger(), resolveCacheMaxSize(), null);
        
        // Permission manager (needs dataCache, so we'll initialize it after storage)
        // Temporarily set to null, will be initialized in initializeStorage()
        permissionManager = null;
        
        // Cooldown manager
        cooldownManager = new CooldownManager(this, configManager);
        
        // Economy manager
        economyManager = new EconomyManager(this);
        
        getLogger().info("Managers initialized.");
    }
    
    /**
     * Initialize storage backend based on configuration.
     */
    private void initializeStorage() {
        getLogger().info("Initializing storage...");
        
        String storageType = configManager.getStorageType();

        // Create appropriate storage implementation
        storageManager = switch (storageType) {
            case "MYSQL" -> {
                getLogger().info("Using MySQL storage backend");
                yield new MySqlStorage(this, dataCache);
            }
            case "REDIS" -> {
                getLogger().info("Using Redis 8.0 storage backend (cross-server sync)");
                RedisConfig redisConfig = RedisConfig.fromConfig(this);
                RedisConnectionManager redisConn = new RedisConnectionManager(redisConfig, this);
                yield new RedisStorageManager(this, dataCache, redisConn);
            }
            case "REDIS_PERSISTENT" -> {
                getLogger().info("Using Redis 8.0 primary + MySQL persistent backup");
                RedisConfig redisConfig = RedisConfig.fromConfig(this);
                RedisConnectionManager redisConn = new RedisConnectionManager(redisConfig, this);
                yield new RedisBackedMySqlStorage(this, dataCache, redisConn);
            }
            case "YAML" -> {
                getLogger().info("Using YAML storage backend");
                yield new YamlStorage(this, dataCache);
            }
            default -> {
                getLogger().warning("Unknown storage type '" + storageType + "', defaulting to YAML");
                yield new YamlStorage(this, dataCache);
            }
        };

        // Initialize storage synchronously with timeout.
        // This ensures MySQL/Redis are ready before the plugin starts
        // accepting commands and events.
        try {
            storageManager.initialize()
                .orTimeout(30, TimeUnit.SECONDS)
                .thenRun(() -> {
                    // Start the Redis pub/sub invalidator for any storage backend
                    // that exposes a Redis connection (pure Redis or the hybrid
                    // Redis+MySQL manager). Doing it after initialize() guarantees
                    // the Jedis pool has already PINGed successfully.
                    RedisConnectionManager conn = null;
                    if (storageManager instanceof RedisStorageManager redisStorage) {
                        conn = redisStorage.getConnection();
                    } else if (storageManager instanceof RedisBackedMySqlStorage hybrid) {
                        conn = hybrid.getRedisConnection();
                    }
                    if (conn != null) {
                        RedisConfig cfg = RedisConfig.fromConfig(this);
                        redisInvalidator = new RedisCacheInvalidator(conn, dataCache, this);
                        redisInvalidator.start();
                        getLogger().info("Redis invalidation subscriber started (server-id="
                                + cfg.getServerId() + ", channel=" + cfg.getInvalidationChannel() + ")");
                    }
                })
                .join();
        } catch (Exception ex) {
            String reason = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
            getLogger().severe("Failed to initialize storage: " + reason);
            getLogger().warning("Falling back to YAML storage to prevent data loss");
            storageManager = new YamlStorage(this, dataCache);
            storageManager.initialize().join();
        }

        // Initialize permission manager (needs dataCache)
        permissionManager = new PermissionManager(configManager, dataCache);

        // Initialize vault manager (depends on storage and permission manager)
        vaultManager = new VaultManager(this, configManager, messageManager,
            permissionManager, storageManager, dataCache);

        // Now that the storage manager exists, inject it into the cache so
        // LRU eviction can flush dirty entries. bStats charts are
        // registered at the same point so they observe a fully-initialised
        // pool state from the first collection onwards.
        wireStorageToCache();

        getLogger().info("Storage initialized.");
    }

    /**
     * Wire the storage manager into the DataCache so LRU eviction can flush
     * dirty entries before dropping them. Also register bStats metrics now
     * that all pool references are stable.
     */
    private void wireStorageToCache() {
        if (dataCache != null && storageManager != null) {
            dataCache.setStorageManager(storageManager);
        }
        MetricsUtil.registerChartsOnly(this, dataCache, storageManager);
    }

    /**
     * Read the configured LRU max-size for the DataCache. Defaults to 1000
     * so a misconfigured YAML file never disables the upper bound.
     */
    private int resolveCacheMaxSize() {
        try {
            return Math.max(1, getConfig().getInt("storage.cache.max-size", 1000));
        } catch (Exception e) {
            return 1000;
        }
    }

    /**
     * Read the configured cleanup interval (seconds) for the DataCache.
     * Defaults to 300s (5 minutes) — the same cadence used by PlayerVaultsX
     * for parity.
     */
    private long resolveCacheCleanupIntervalSeconds() {
        try {
            return Math.max(1L, getConfig().getLong("storage.cache.cleanup-interval-seconds", 300L));
        } catch (Exception e) {
            return 300L;
        }
    }

    /**
     * Spin up the DataCache offline-player cleanup scheduler. Called after
     * storage has been initialised so the cleanup pass can call
     * {@code storageManager.savePlayerData} when flushing dirty entries.
     */
    private void startDataCacheCleanup() {
        if (dataCache == null) {
            return;
        }
        dataCache.startCleanup(this, resolveCacheCleanupIntervalSeconds());
    }

    /**
     * Register all event listeners.
     */
    private void registerListeners() {
        getLogger().info("Registering event listeners...");
        
        // Ender chest interaction listener
        getServer().getPluginManager().registerEvents(
            new EnderChestListener(vaultManager, messageManager, getLogger()), this);
        
        // Vault inventory interaction listener
        getServer().getPluginManager().registerEvents(
            new VaultInventoryListener(vaultManager, getLogger()), this);
        
        // Player connection listener
        getServer().getPluginManager().registerEvents(
            new PlayerConnectionListener(storageManager, dataCache, vaultManager, getLogger()), this);
        
        // Search input listener
        getServer().getPluginManager().registerEvents(
            new SearchInputListener(vaultManager.getSearchManager(), vaultManager, messageManager), this);
        
        getLogger().info("Event listeners registered.");
    }
    
    /**
     * Register all commands and tab completers.
     */
    private void registerCommands() {
        getLogger().info("Registering commands...");
        
        // Register /voidvaults command
        PluginCommand voidVaultCmd = getCommand("voidvaults");
        if (voidVaultCmd != null) {
            VoidVaultCommand voidVaultExecutor = new VoidVaultCommand(
                this, configManager, messageManager, vaultManager, dataCache, storageManager);
            voidVaultCmd.setExecutor(voidVaultExecutor);
            voidVaultCmd.setTabCompleter(new VoidVaultTabCompleter(this, configManager, permissionManager));
            getLogger().info("Successfully registered /voidvaults command with aliases: " + voidVaultCmd.getAliases());
        } else {
            getLogger().severe("Failed to register /voidvaults command - command not found in plugin.yml");
        }
        
        // Register /echest command (with aliases /pv and /vault)
        PluginCommand echestCmd = getCommand("echest");
        if (echestCmd != null) {
            EChestCommand echestExecutor = new EChestCommand(
                this, vaultManager, permissionManager, cooldownManager, messageManager, configManager);
            echestCmd.setExecutor(echestExecutor);
            getLogger().info("Successfully registered /echest command with aliases: " + echestCmd.getAliases());
            getLogger().info("Remote access commands available: /echest, /pv, /vault");
        } else {
            getLogger().severe("Failed to register /echest command - command not found in plugin.yml");
            getLogger().severe("Remote access will NOT work! Check plugin.yml for 'echest' command definition");
        }
        
        getLogger().info("Commands registered.");
    }
    
    /**
     * Set up integrations with external plugins (Vault, PlaceholderAPI).
     */
    private void setupIntegrations() {
        getLogger().info("Setting up integrations...");
        
        // Initialize economy integration (Vault)
        economyManager.initialize();
        
        // Initialize PlaceholderAPI integration (only if PlaceholderAPI is present)
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                placeholderAPIHook = new PlaceholderAPIHook(this, permissionManager, vaultManager);
                placeholderAPIHook.initialize();
            } catch (Exception e) {
                getLogger().warning("Failed to initialize PlaceholderAPI integration: " + e.getMessage());
                getLogger().info("PlaceholderAPI features will be disabled.");
            }
        } else {
            getLogger().info("PlaceholderAPI not found. Placeholder features disabled.");
        }
        
        getLogger().info("Integrations set up.");
    }
    
    /**
     * Initialize bStats metrics. The full MetricsUtil integration lives in
     * {@link com.voidvault.util.MetricsUtil#register}; we still want a
     * base {@link Metrics} instance here so the plugin id is registered
     * even before storage finishes initialising.
     */
    private void initializeMetrics() {
        try {
            new Metrics(this, 28100);
            getLogger().info("bStats base metrics initialized (custom charts registered after storage init).");
        } catch (Exception e) {
            getLogger().warning("Failed to initialize bStats: " + e.getMessage());
        }
    }
    
    /**
     * Start the auto-save task that periodically saves dirty player data.
     */
    private void startAutoSave() {
        int intervalMinutes = configManager.getAutoSaveInterval();
        long intervalTicks = intervalMinutes * 60L * 20L; // Convert minutes to ticks
        
        getLogger().info("Starting auto-save task with interval: " + intervalMinutes + " minutes");
        
        autoSaveTask = new AutoSaveTask(this, storageManager, dataCache, getLogger());
        
        // Schedule repeating task with initial delay equal to the interval
        SchedulerUtil.runAsyncRepeating(this, autoSaveTask, intervalTicks, intervalTicks);
    }
    
    /**
     * Perform a synchronous save of all cached vault data.
     * <p>
     * We delegate to {@link StorageManager#saveAll()} instead of iterating
     * {@link DataCache#getCachedPlayers()} and calling
     * {@link StorageManager#savePlayerData(UUID, com.voidvault.model.PlayerVaultData)}
     * one-by-one. The reason matters on the REDIS_PERSISTENT backend:
     * per-player writes only enqueue a MySQL persistence job and do not
     * wait for it to complete, so calling saveAll() in a loop would
     * leave a flood of queued jobs at shutdown time — exactly what
     * blocked the server stop thread before this fix. {@code saveAll()}
     * is implemented by each backend to flush its own queue atomically
     * (see {@code RedisBackedMySqlStorage.saveAll()} and
     * {@code MySqlStorage.saveAll()}).
     */
    private void saveAllDataSync() {
        var cachedPlayers = dataCache.getCachedPlayers();

        if (cachedPlayers.isEmpty()) {
            getLogger().info("No cached data to save.");
            return;
        }

        int totalDirty = dataCache.dirtyCount();
        getLogger().info("Saving data for " + cachedPlayers.size() + " players ("
                + totalDirty + " dirty)...");

        long timeoutSeconds = resolveShutdownSaveTimeoutSeconds();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        AtomicLong lastProgressLog = new AtomicLong(System.currentTimeMillis());
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-shutdown-monitor");
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> monitorTask = monitor.scheduleAtFixedRate(() -> {
            long remainingDirty = dataCache.dirtyCount();
            long elapsedMs = System.currentTimeMillis() - (deadline - timeoutSeconds * 1000L);
            getLogger().info("[Shutdown] progress: dirty=" + remainingDirty
                    + " (was " + totalDirty + "), elapsed=" + elapsedMs + "ms");
            lastProgressLog.set(System.currentTimeMillis());
        }, 1, 1, TimeUnit.SECONDS);

        try {
            // Each backend implements saveAll() with its own internal
            // drain semantics. Bounding the wait here keeps the
            // server stop thread from hanging on a wedged MySQL — if
            // saveAll() exceeds the budget we still call close() next
            // and the remaining in-flight writes get a final attempt
            // there.
            storageManager.saveAll()
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            getLogger().info("saveAll() finished within " + timeoutSeconds + "s budget");
        } catch (java.util.concurrent.TimeoutException te) {
            int remainingDirty = dataCache.dirtyCount();
            getLogger().warning("saveAll() exceeded " + timeoutSeconds
                    + "s budget during shutdown; " + remainingDirty
                    + " dirty player(s) not flushed. Remaining dirty UUIDs: "
                    + formatDirtyUUIDs(dataCache.getDirtyPlayers()));
            dumpPoolStateOnTimeout();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error during synchronous save", e);
        } finally {
            monitorTask.cancel(false);
            monitor.shutdown();
            try {
                if (!monitor.awaitTermination(1, TimeUnit.SECONDS)) {
                    monitor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                monitor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Read the configured shutdown save-timeout. Falls back to 30 seconds
     * if the YAML file is missing the key (the legacy default was 8s, which
     * is too aggressive for a 200-player server).
     */
    private long resolveShutdownSaveTimeoutSeconds() {
        try {
            return Math.max(1L, getConfig().getLong("shutdown.save-timeout-seconds", 30L));
        } catch (Exception e) {
            return 30L;
        }
    }

    /**
     * Pretty-print the dirty player set so operators can correlate the
     * warning with the player list.
     */
    private String formatDirtyUUIDs(java.util.Set<UUID> dirty) {
        if (dirty.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(dirty.size() * 40);
        sb.append('[');
        int i = 0;
        for (UUID id : dirty) {
            if (i++ > 0) {
                sb.append(", ");
            }
            sb.append(id);
            if (i >= 20) {
                sb.append(", ... (+").append(dirty.size() - 20).append(" more)");
                break;
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Log Hikari + Jedis pool counters at shutdown timeout. Helps the
     * operator figure out whether the hang is on the MySQL or Redis side.
     */
    private void dumpPoolStateOnTimeout() {
        try {
            var hikari = storageManager.getHikariPoolMetrics();
            if (hikari != null) {
                getLogger().warning("[Shutdown] Hikari pool at timeout: active="
                        + hikari.getActiveConnections() + ", idle="
                        + hikari.getIdleConnections() + ", waiting="
                        + hikari.getThreadsAwaitingConnection());
            }
            var jedis = storageManager.getJedisPoolMetrics();
            if (jedis != null) {
                getLogger().warning("[Shutdown] Jedis pool at timeout: active="
                        + jedis.getNumActive() + ", idle=" + jedis.getNumIdle()
                        + ", waiting=" + jedis.getNumWaiters());
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to dump pool state on shutdown", e);
        }
    }
    
    // Public getters for managers (if needed by other components)
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public MessageManager getMessageManager() {
        return messageManager;
    }
    
    public VaultManager getVaultManager() {
        return vaultManager;
    }
    
    public PermissionManager getPermissionManager() {
        return permissionManager;
    }
    
    public StorageManager getStorageManager() {
        return storageManager;
    }
    
    public DataCache getDataCache() {
        return dataCache;
    }
}
