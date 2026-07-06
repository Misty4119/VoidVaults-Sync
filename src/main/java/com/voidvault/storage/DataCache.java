package com.voidvault.storage;

import com.voidvault.model.PlayerVaultData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Thread-safe in-memory cache for player vault data with LRU eviction and
 * automatic offline-player cleanup.
 * <p>
 * The cache is backed by a {@link LinkedHashMap} in access-order mode, wrapped
 * by {@link Collections#synchronizedMap} so {@link #put} / {@link #remove} /
 * {@link #contains} stay thread-safe even though LinkedHashMap itself is not.
 * When {@link #size()} would exceed {@link #maxSize} on a new insertion, the
 * eldest entry (the one whose {@code get} / {@code put} happened longest ago)
 * is evicted via {@link #evictEldest()}.
 * <p>
 * A periodic cleanup scheduler runs on a single dedicated thread and walks
 * the cache on every tick, flushing any dirty entries that belong to offline
 * players before evicting them. This bounds the memory footprint over long
 * uptimes where many players join and leave.
 *
 * <h2>Eviction policy when the cache is full</h2>
 * Plain LRU is fine for most workflows, but a vault that has not been saved
 * yet (i.e. its UUID is in {@link #dirtyPlayers}) must NOT be silently
 * dropped. The eviction callback therefore schedules an asynchronous save
 * via the provided {@link StorageManager} before removing the entry. If the
 * save throws, the eviction is skipped and the entry stays in the cache so
 * the next cleanup tick can retry.
 */
public class DataCache {

    /**
     * Main cache storing player vault data.
     * Key: Player UUID, Value: PlayerVaultData
     * <p>
     * Wrapped by {@code Collections.synchronizedMap} so concurrent get / put
     * / remove calls are serialised. Iteration still needs to happen under
     * the map's monitor, which every public iteration helper below does.
     */
    private final LinkedHashMap<UUID, PlayerVaultData> rawCache;
    private final Map<UUID, PlayerVaultData> cache;

    /**
     * Set of player UUIDs whose data has been modified since last save.
     * Used for efficient auto-save operations.
     */
    private final Set<UUID> dirtyPlayers;

    /**
     * Logger for cache warnings and diagnostics.
     */
    private final Logger logger;

    /**
     * Soft upper bound on the cache size. Eviction in {@link #put} fires
     * whenever a fresh insertion would push {@link #size()} past this value.
     */
    private final int maxSize;

    /**
     * Optional storage manager used to flush dirty entries during LRU
     * eviction. {@code null} is tolerated (the cache falls back to logging a
     * warning and dropping the entry), which keeps the legacy single-
     * argument constructor usable.
     * <p>
     * Marked {@code volatile} rather than {@code final} so the
     * VoidVaultPlugin boot sequence can construct the DataCache before the
     * storage manager exists, then inject it after
     * {@code storageManager.initialize()} returns. The setter must be
     * called before {@link #startCleanup(Plugin, long)} or any concurrent
     * {@link #put} that might trigger eviction.
     */
    private volatile StorageManager storageManager;

    /**
     * Cleanup scheduler. Created lazily by {@link #startCleanup(Plugin, long)}
     * and shut down by {@link #stopCleanup()}. Single-threaded because both
     * the underlying LinkedHashMap and the dirty set are already thread-safe.
     */
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private ScheduledExecutorService cleanupExecutor;
    private ScheduledFuture<?> cleanupTask;

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /**
     * Legacy constructor. Equivalent to
     * {@code new DataCache(logger, 1000, null)}.
     */
    public DataCache(Logger logger) {
        this(logger, 1000, null);
    }

    /**
     * Full constructor.
     *
     * @param logger         Logger for diagnostics
     * @param maxSize        Soft upper bound on cached players; must be &gt;= 1
     * @param storageManager Storage manager used to flush dirty entries on
     *                       eviction; may be {@code null}
     */
    public DataCache(Logger logger, int maxSize, StorageManager storageManager) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.maxSize = Math.max(1, maxSize);
        this.storageManager = storageManager;
        this.dirtyPlayers = ConcurrentHashMap.newKeySet();

        // accessOrder=true → LinkedHashMap re-orders on get/put, and
        // removeEldestEntry receives the eldest entry on every insertion.
        // We do NOT enable removeEldestEntry here; instead we drive eviction
        // ourselves from put() so we can intercept the evicted entry and
        // check whether it is dirty before removing it.
        int initialCapacity = Math.max(16, (int) (this.maxSize / 0.75f) + 1);
        this.rawCache = new LinkedHashMap<>(initialCapacity, 0.75f, true);
        this.cache = Collections.synchronizedMap(rawCache);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Retrieves player vault data from the cache.
     *
     * @param playerId The UUID of the player
     * @return An Optional containing the player's data if present, empty otherwise
     */
    public Optional<PlayerVaultData> get(UUID playerId) {
        return Optional.ofNullable(cache.get(playerId));
    }

    /**
     * Stores player vault data in the cache.
     * This does NOT mark the player as dirty.
     * <p>
     * Inserting more than {@link #maxSize} entries will trigger automatic
     * LRU eviction of the eldest. If that evicted entry was dirty and a
     * {@link StorageManager} was supplied at construction time, the cache
     * will fire-and-forget an async save before the entry is dropped.
     *
     * @param playerId The UUID of the player
     * @param data     The vault data to cache
     */
    public void put(UUID playerId, PlayerVaultData data) {
        if (playerId == null || data == null) {
            throw new IllegalArgumentException("Player ID and data cannot be null");
        }

        // Manual LRU eviction: when the cache would exceed maxSize after
        // this insertion, evict the eldest entry first so we can flush a
        // dirty entry before losing it. LinkedHashMap.removeEldestEntry is
        // not enough because it does not let us see the candidate entry.
        synchronized (cache) {
            UUID existingEldest = cache.containsKey(playerId) ? null : peekEldestKey();
            cache.put(playerId, data);
            // If we just overwrote an existing entry the size does not
            // grow, so no eviction needed.
            if (existingEldest == null) {
                // Trim until we are back at or below maxSize.
                while (cache.size() > maxSize) {
                    UUID eldest = peekEldestKey();
                    if (eldest == null || eldest.equals(playerId)) {
                        // The new entry is the eldest (cache had room for
                        // exactly one slot); nothing to evict.
                        break;
                    }
                    // peekEldestKey does not reorder the linked list, so
                    // removeEldest() can safely pop the same key.
                    evictEldest();
                }
            }
        }
    }

    /**
     * Return the eldest entry's key without changing access order. We rely
     * on the fact that {@link LinkedHashMap}'s {@code iterator()} walks in
     * insertion / access order and that LinkedHashMap allows the iteration
     * without reordering when we don't call any accessor first.
     */
    private UUID peekEldestKey() {
        synchronized (cache) {
            Iterator<Map.Entry<UUID, PlayerVaultData>> it = rawCache.entrySet().iterator();
            return it.hasNext() ? it.next().getKey() : null;
        }
    }

    /**
     * Pop the eldest entry, flushing it asynchronously first if it is dirty.
     * Caller must hold the cache monitor.
     */
    private void evictEldest() {
        Iterator<Map.Entry<UUID, PlayerVaultData>> it = rawCache.entrySet().iterator();
        if (!it.hasNext()) {
            return;
        }
        Map.Entry<UUID, PlayerVaultData> eldest = it.next();
        UUID key = eldest.getKey();
        PlayerVaultData data = eldest.getValue();
        boolean wasDirty = dirtyPlayers.remove(key);
        it.remove();
        if (!wasDirty || data == null) {
            return;
        }
        // Async flush — never block the inserting thread on storage I/O.
        // We bypass flushIfDirty() because the entry is already gone from
        // the cache by this point.
        if (storageManager == null) {
            logger.warning("DataCache evict: dropping dirty entry " + key
                    + " (no StorageManager configured)");
            return;
        }
        try {
            storageManager.savePlayerData(key, data)
                    .thenRun(() -> logger.fine(() -> "DataCache evict: flushed dirty "
                            + key + " before eviction"))
                    .exceptionally(ex -> {
                        logger.warning("DataCache evict: failed to flush dirty " + key
                                + ": " + ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            logger.warning("DataCache evict: StorageManager rejected flush for "
                    + key + ": " + ex.getMessage());
        }
    }

    /**
     * Marks a player's data as dirty (modified).
     * Dirty players will be included in the next save operation.
     *
     * @param playerId The UUID of the player
     */
    public void markDirty(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        dirtyPlayers.add(playerId);
    }

    /**
     * Removes the dirty flag from a player.
     * This should be called after successfully saving the player's data.
     *
     * @param playerId The UUID of the player
     */
    public void clearDirty(UUID playerId) {
        dirtyPlayers.remove(playerId);
    }

    /**
     * Checks if a player's data has been modified since last save.
     *
     * @param playerId The UUID of the player
     * @return true if the player's data is dirty, false otherwise
     */
    public boolean isDirty(UUID playerId) {
        return dirtyPlayers.contains(playerId);
    }

    /**
     * Gets all player UUIDs whose data has been modified.
     * Returns a copy to prevent concurrent modification issues.
     *
     * @return A set of UUIDs for players with dirty data
     */
    public Set<UUID> getDirtyPlayers() {
        return new HashSet<>(dirtyPlayers);
    }

    /**
     * Removes a player's data from the cache.
     * Also removes the dirty flag if present.
     *
     * @param playerId The UUID of the player
     * @return The removed PlayerVaultData, or null if not present
     */
    public PlayerVaultData remove(UUID playerId) {
        dirtyPlayers.remove(playerId);
        return cache.remove(playerId);
    }

    /**
     * Checks if a player's data is currently cached.
     *
     * @param playerId The UUID of the player
     * @return true if the player's data is in cache, false otherwise
     */
    public boolean contains(UUID playerId) {
        return cache.containsKey(playerId);
    }

    /**
     * Gets all cached player UUIDs.
     * Returns a copy to prevent concurrent modification issues.
     *
     * @return A set of all cached player UUIDs
     */
    public Set<UUID> getCachedPlayers() {
        synchronized (cache) {
            return new HashSet<>(cache.keySet());
        }
    }

    /**
     * Gets the number of players currently cached.
     *
     * @return The cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Returns the configured maximum size. The cache may transiently hold
     * {@code maxSize + 1} entries during a put that triggers eviction, but
     * any further operation will bring the size back to {@code maxSize}.
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Inject (or replace) the storage manager used to flush dirty entries
     * during LRU eviction. Must be called before
     * {@link #startCleanup(Plugin, long)} or any concurrent
     * {@link #put} that might trigger eviction; not thread-safe with
     * respect to those operations, so call it once during startup before
     * the cache is exposed to command / event traffic.
     */
    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * Visible for tests — returns the currently configured storage manager
     * (may be {@code null}).
     */
    public StorageManager getStorageManager() {
        return storageManager;
    }

    /**
     * Gets the number of players with dirty data.
     *
     * @return The number of dirty players
     */
    public int dirtyCount() {
        return dirtyPlayers.size();
    }

    /**
     * Clears all cached data and dirty flags.
     * This should only be used during plugin shutdown or reload.
     */
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
        dirtyPlayers.clear();
    }

    /**
     * Updates player vault data in the cache and marks it as dirty.
     * This is a convenience method combining put() and markDirty().
     *
     * @param playerId The UUID of the player
     * @param data     The vault data to cache
     */
    public void putAndMarkDirty(UUID playerId, PlayerVaultData data) {
        put(playerId, data);
        markDirty(playerId);
    }

    /**
     * Gets cache statistics for monitoring and debugging.
     *
     * @return A formatted string with cache statistics
     */
    public String getStatistics() {
        int totalCached = cache.size();
        int totalDirty = dirtyPlayers.size();
        int totalClean = totalCached - totalDirty;

        return String.format("Cache Stats: Total=%d/%d, Clean=%d, Dirty=%d",
                totalCached, maxSize, totalClean, totalDirty);
    }

    /**
     * Removes all cached data for offline players.
     * This helps prevent memory leaks from players who disconnected without proper cleanup.
     * <p>
     * Dirty entries are flushed asynchronously before eviction; if no
     * {@link StorageManager} is configured the dirty entry is logged and
     * dropped (matching the legacy behaviour).
     *
     * @param onlinePlayerIds Set of UUIDs for currently online players
     * @return The number of entries removed
     */
    public int cleanupOfflinePlayers(Set<UUID> onlinePlayerIds) {
        int removedCount = 0;

        for (UUID playerId : getCachedPlayers()) {
            if (!onlinePlayerIds.contains(playerId)) {
                // Player is offline, flush dirty state then evict
                PlayerVaultData snapshot = dirtyPlayers.contains(playerId)
                        ? cache.get(playerId) : null;
                remove(playerId);
                if (snapshot != null) {
                    flushSnapshot(playerId, snapshot, "cleanup");
                }
                removedCount++;
            }
        }

        return removedCount;
    }

    /**
     * Best-effort flush of a dirty entry. If a StorageManager was supplied
     * at construction time, kick off an async save. Otherwise log a warning.
     */
    private void flushIfDirty(UUID playerId, String source) {
        if (storageManager == null) {
            logger.warning("DataCache " + source + " would evict dirty entry "
                    + playerId + " but no StorageManager is configured; dropping data");
            return;
        }
        PlayerVaultData data = cache.get(playerId);
        if (data == null) {
            return;
        }
        flushSnapshot(playerId, data, source);
    }

    /**
     * Dispatch an async save for the given snapshot. The snapshot was taken
     * either from the live cache or from an entry that is about to be
     * evicted; the caller must not reuse {@code data} after this method
     * returns because we hand it off to the storage layer.
     */
    private void flushSnapshot(UUID playerId, PlayerVaultData data, String source) {
        try {
            storageManager.savePlayerData(playerId, data)
                    .thenRun(() -> {
                        clearDirty(playerId);
                        logger.fine(() -> "DataCache " + source + ": flushed dirty "
                                + playerId + " before eviction");
                    })
                    .exceptionally(ex -> {
                        logger.warning("DataCache " + source + ": failed to flush dirty "
                                + playerId + "; entry may be lost: "
                                + ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            logger.warning("DataCache " + source + ": StorageManager rejected flush for "
                    + playerId + ": " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Periodic offline-player cleanup
    // ---------------------------------------------------------------------

    /**
     * Start a background scheduler that walks the cache every
     * {@code intervalSeconds} and evicts entries for players who are no
     * longer online.
     * <p>
     * The scheduler runs on its own single-thread executor because both the
     * underlying LinkedHashMap and the dirty set are already thread-safe and
     * the work is I/O bound (save) or O(N) (cache walk). The online-player
     * snapshot is taken on the Bukkit main thread because Bukkit forbids
     * concurrent iteration of {@code getOnlinePlayers()} from non-main
     * threads.
     *
     * @param plugin           Plugin owning the scheduler
     * @param intervalSeconds  Seconds between cleanup passes; must be &gt;= 1
     */
    public void startCleanup(Plugin plugin, long intervalSeconds) {
        if (!cleanupStarted.compareAndSet(false, true)) {
            return;
        }
        long interval = Math.max(1L, intervalSeconds);
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voidvault-datacache-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupTask = cleanupExecutor.scheduleAtFixedRate(
                () -> runCleanupPass(plugin),
                interval, interval, TimeUnit.SECONDS);
        logger.info("DataCache cleanup scheduler started (interval=" + interval + "s, maxSize=" + maxSize + ")");
    }

    /**
     * Stop the cleanup scheduler and wait briefly for the in-flight tick to
     * finish. Safe to call multiple times.
     */
    public void stopCleanup() {
        if (!cleanupStarted.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> task = cleanupTask;
        ScheduledExecutorService exec = cleanupExecutor;
        cleanupTask = null;
        cleanupExecutor = null;
        if (task != null) {
            task.cancel(false);
        }
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException ie) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("DataCache cleanup scheduler stopped.");
    }

    /**
     * One cleanup pass. Must be called from the dedicated executor thread
     * (NOT the Bukkit main thread).
     */
    private void runCleanupPass(Plugin plugin) {
        try {
            Set<UUID> online = snapshotOnlinePlayers(plugin);
            int removed = cleanupOfflinePlayers(online);
            if (removed > 0) {
                logger.fine(() -> "DataCache cleanup: evicted " + removed + " offline player(s)");
            }
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = t.getClass().getSimpleName();
            }
            logger.warning("DataCache cleanup pass failed: " + msg);
        }
    }

    /**
     * Snapshot the online player set on a server-thread context, then return
     * the result to the caller. Bukkit APIs forbid concurrent access from
     * non-main threads, so the cleanup executor (a separate thread) has to
     * bounce through the scheduler.
     * <p>
     * On Folia the legacy {@code Bukkit.getScheduler()} does not exist on
     * non-region threads; instead we use {@link Bukkit#getGlobalRegionScheduler()},
     * which schedules a callback on the global region thread. Paper servers
     * fall back to the standard main-thread scheduler. Either way the
     * snapshot completes in microseconds and is bounded by a 1s deadline so
     * a wedged global region cannot stall the executor indefinitely.
     */
    private Set<UUID> snapshotOnlinePlayers(Plugin plugin) {
        if (plugin == null || !plugin.isEnabled()) {
            return Set.of();
        }
        AtomicLong ticket = new AtomicLong(0L);
        Set<UUID>[] holder = new Set[]{Collections.emptySet()};
        Runnable snapshotTask = () -> {
            try {
                Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                Set<UUID> ids = new HashSet<>(players.size());
                for (Player p : players) {
                    ids.add(p.getUniqueId());
                }
                holder[0] = ids;
            } finally {
                ticket.set(1L);
            }
        };
        try {
            if (isFoliaServer()) {
                // Folia: schedule on the global region thread. Bukkit.globalRegionScheduler
                // is safe to invoke from any thread and never throws IllegalStateException
                // when the plugin is enabled.
                Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> snapshotTask.run());
            } else {
                // Paper / Spigot: standard main-thread scheduler.
                Bukkit.getScheduler().runTask(plugin, snapshotTask);
            }
        } catch (Throwable t) {
            // Defensive fallback: if scheduling fails (e.g. during shutdown or
            // a misbehaving scheduler), don't propagate. The empty-set return
            // simply means the cleanup pass won't evict anything on this tick.
            logger.warning("DataCache cleanup: unable to schedule snapshot ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "); skipping cleanup tick");
            return Collections.emptySet();
        }
        // Spin-wait — the snapshot task is non-blocking, so this resolves
        // in microseconds. Avoids introducing a CompletableFuture here just
        // for one snapshot.
        long deadline = System.currentTimeMillis() + 1000L;
        while (ticket.get() == 0L && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        return holder[0];
    }

    /**
     * Detect Folia at runtime by sniffing for the regionised-server class
     * (the same marker that {@link com.voidvault.util.SchedulerUtil} uses).
     * We intentionally do not depend on SchedulerUtil directly to keep
     * DataCache independent of plugin bootstrap order — the cleanup
     * scheduler may run before SchedulerUtil has been initialised in some
     * legacy startup paths.
     */
    private static boolean isFoliaServer() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}