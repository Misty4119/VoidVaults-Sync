package com.voidvault.storage.redis;

import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultDataCloner;
import com.voidvault.model.VaultPage;
import com.voidvault.storage.DataCache;
import com.voidvault.storage.LatencyHistogram;
import com.voidvault.storage.StorageManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis 8.0 backed implementation of {@link StorageManager}.
 *
 * <h2>Key layout</h2>
 * Every vault is stored across four key families (see {@link RedisKeyLayout}):
 * <ul>
 *     <li>{@code {prefix}{uuid}:meta} – Hash with one field per vault
 *         attribute (version, custom slots, page count, …). Tiny.</li>
 *     <li>{@code {prefix}{uuid}:pages} – Hash that maps {@code page number} to
 *         the per-page blob key.</li>
 *     <li>{@code {prefix}{uuid}:page:{n}} – String holding the actual item
 *         data for page {@code n}. Optional compression via
 *         {@link CompressionCodec}.</li>
 *     <li>{@code {prefix}{uuid}:lock} – String used as a per-player
 *         distributed write lock (SET NX PX).</li>
 * </ul>
 * Splitting pages across keys means a single-slot edit rewrites one page
 * blob and one Hash field instead of the whole multi-megabyte vault. The
 * per-player meta Hash is also small enough to embed inside the pub/sub
 * INVALIDATE envelope without blowing up the message bus.
 *
 * <h2>Cache invalidation</h2>
 * After every successful write the implementation publishes a fine-grained
 * {@link RedisMessage#TYPE_INVALIDATE_PAGE} message (or
 * {@link RedisMessage#TYPE_INVALIDATE_FULL} when the diff-sync extension is
 * disabled) so that every other node in the network can drop its local copy
 * of just the touched pages.
 *
 * <h2>Cross-server race protection</h2>
 * {@link RedisDistributedLock} serialises concurrent writers across nodes.
 * The lock TTL doubles as a crash safety net: if the holder dies between
 * SET and DEL, the lock auto-expires.
 */
public class RedisStorageManager implements StorageManager {

    private final Logger logger;
    private final DataCache dataCache;
    private final RedisConnectionManager connection;
    private final RedisDistributedLock lock;
    private final ExecutorService asyncExecutor;
    private final boolean compressionEnabled;
    private final int compressionLevel;
    private final boolean diffSyncEnabled;
    private final long lockTtlMs;
    private final long keyTtlSeconds;

    // Latency histograms. Same shape as MySqlStorage's so MetricsUtil can
    // treat the two backends uniformly.
    private final LatencyHistogram saveHistogram = new LatencyHistogram("redis.save");
    private final LatencyHistogram loadHistogram = new LatencyHistogram("redis.load");

    /**
     * Per-player monotonic version number. The combination of (origin-server,
     * version) is what the invalidator uses to drop replayed messages.
     */
    private final Map<UUID, AtomicLong> localVersion = new ConcurrentHashMap<>();

    public RedisStorageManager(Plugin plugin,
                               DataCache dataCache,
                               RedisConnectionManager connection) {
        this.logger = plugin.getLogger();
        this.dataCache = dataCache;
        this.connection = connection;
        this.lock = new RedisDistributedLock(connection);
        RedisConfig cfg = connection.getConfig();
        this.compressionEnabled = cfg.isCompressionEnabled();
        this.compressionLevel = Math.max(1, Math.min(9, cfg.getCompressionLevel()));
        this.diffSyncEnabled = cfg.isDiffSyncEnabled();
        this.lockTtlMs = Math.max(500L, cfg.getLockTtlMs());
        this.keyTtlSeconds = Math.max(0L, cfg.getKeyTtlSeconds());
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            if (!connection.initialize()) {
                throw new IllegalStateException("Redis initialization failed");
            }
            logger.info("Redis storage initialized (server-id=" + connection.getConfig().getServerId()
                    + ", compression=" + (compressionEnabled ? "level " + compressionLevel : "off")
                    + ", diff-sync=" + diffSyncEnabled + ")");
            connection.startMetricsLogger();
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<PlayerVaultData> loadPlayerData(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            long startNs = System.nanoTime();
            try {
                String prefix = connection.getConfig().getVaultKeyPrefix();
                String metaKey = RedisKeyLayout.meta(prefix, playerId);
                String pagesKey = RedisKeyLayout.pagesIndex(prefix, playerId);
                String legacyKey = RedisKeyLayout.legacyKey(prefix, playerId);

                try (Jedis jedis = connection.getPool().getResource()) {
                    // Pipeline the meta + page index + legacy blob reads so the
                    // whole probe completes in a single network round-trip.
                    Map<String, String> meta;
                    Map<String, String> pageIndex;
                    byte[] legacyBlob;
                    try (Pipeline pipe = jedis.pipelined()) {
                        Response<Map<String, String>> metaR = pipe.hgetAll(metaKey);
                        Response<Map<String, String>> pagesR = pipe.hgetAll(pagesKey);
                        Response<byte[]> legacyR = connection.getConfig().isKeepLegacyV1()
                                ? pipe.get(legacyKey.getBytes()) : null;
                        pipe.sync();
                        meta = metaR.get();
                        pageIndex = pagesR.get();
                        legacyBlob = legacyR == null ? null : legacyR.get();
                    }
                    if ((meta == null || meta.isEmpty()) && (legacyBlob == null || legacyBlob.length == 0)) {
                        logger.fine("No Redis data for player " + playerId + ", returning empty vault");
                        return PlayerVaultData.createEmpty(playerId);
                    }

                    // Migrate from the legacy v1 blob on the fly. The v1 payload
                    // embeds the entire page contents inside one buffer; we
                    // decode it through the v1 path, then schedule a re-write
                    // in the new layout so the next read skips migration.
                    if ((meta == null || meta.isEmpty()) && legacyBlob != null && legacyBlob.length > 0) {
                        PlayerVaultData migrated;
                        try {
                            migrated = RedisSerialization.decodeLegacyV1(legacyBlob);
                        } catch (IOException ex) {
                            logger.log(Level.WARNING,
                                    "Failed to decode legacy v1 vault for " + playerId, ex);
                            return PlayerVaultData.createEmpty(playerId);
                        }
                        // Stash the migrated data in the local cache; the next
                        // savePlayerData() call will promote it to the v2 layout.
                        dataCache.put(playerId, migrated);
                        dataCache.markDirty(playerId);
                        logger.info("Migrated legacy v1 vault to local cache for " + playerId);
                        return migrated;
                    }

                    // Hydrate every page blob listed in the index. Each entry
                    // value is the Redis key under which the page payload lives.
                    Map<Integer, ItemStack[]> pages = new HashMap<>();
                    List<String> pageKeys = new ArrayList<>(pageIndex.size());
                    for (Map.Entry<String, String> entry : pageIndex.entrySet()) {
                        pageKeys.add(entry.getValue());
                    }
                    if (!pageKeys.isEmpty()) {
                        try (Pipeline pipe = jedis.pipelined()) {
                            List<Response<byte[]>> responses = new ArrayList<>(pageKeys.size());
                            for (String key : pageKeys) {
                                responses.add(pipe.get(key.getBytes()));
                            }
                            pipe.sync();
                            int idx = 0;
                            for (Map.Entry<String, String> entry : pageIndex.entrySet()) {
                                byte[] raw = responses.get(idx++).get();
                                if (raw == null) {
                                    continue;
                                }
                                try {
                                    ItemStack[] decoded = RedisSerialization.decodePage(raw);
                                    pages.put(Integer.parseInt(entry.getKey()), decoded);
                                } catch (IOException ex) {
                                    logger.log(Level.WARNING, "Failed to decode page " + entry.getKey()
                                            + " for player " + playerId, ex);
                                }
                            }
                        }
                    }

                    int customSlots = parseIntOrZero(meta.get(RedisKeyLayout.META_FIELD_CUSTOM_SLOTS));
                    int customPages = parseIntOrZero(meta.get(RedisKeyLayout.META_FIELD_CUSTOM_PAGES));
                    Map<Integer, VaultPage> vPages = new ConcurrentHashMap<>(pages.size());
                    for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
                        vPages.put(entry.getKey(), new VaultPage(entry.getKey(), entry.getValue()));
                    }
                    PlayerVaultData loaded = new PlayerVaultData(playerId, vPages, customSlots, customPages);
                    // Cross-thread boundary: this method runs on asyncExecutor
                    // and returns to the caller on whichever thread they await on
                    // (main thread for VaultManager.openVault). Deep-clone so the
                    // caller cannot accidentally mutate the bytes the I/O thread
                    // is still holding a reference to.
                    return VaultDataCloner.deepClone(loaded);
                }
            } finally {
                loadHistogram.record(System.nanoTime() - startNs);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> savePlayerData(UUID playerId, PlayerVaultData data) {
        return CompletableFuture.runAsync(() -> {
            long startNs = System.nanoTime();
            try {
                // Defensive deep copy before any further processing. The caller
                // is typically running on the main thread (e.g. closeVault
                // triggered by InventoryCloseEvent) and may continue to mutate
                // the ItemStacks in their GUI after we return this future;
                // without the clone, the async encoder below could serialise
                // a half-mutated ItemStack into Redis.
                PlayerVaultData snapshot = VaultDataCloner.deepClone(data);

                String prefix = connection.getConfig().getVaultKeyPrefix();
                String token = UUID.randomUUID().toString();
                String lockKey = RedisKeyLayout.lockKey(prefix, playerId);
                if (lock.tryAcquire(playerId, token, lockTtlMs) == null) {
                    // Lock contended. Rather than failing the save we fall back
                    // to a short retry loop: most contenders finish within
                    // milliseconds and the lock TTL guarantees the wait is
                    // bounded. Three retries at 50ms covers >99% of cases.
                    boolean acquired = false;
                    for (int i = 0; i < 3 && !acquired; i++) {
                        try {
                            Thread.sleep(50L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CompletionException(ie);
                        }
                        if (lock.tryAcquire(playerId, token, lockTtlMs) != null) {
                            acquired = true;
                        }
                    }
                    if (!acquired) {
                        throw new java.util.concurrent.CompletionException(
                                new IllegalStateException("Could not acquire Redis write lock for "
                                        + playerId + " after retries"));
                    }
                }
                try {
                    savePlayerDataLocked(playerId, snapshot);
                } finally {
                    lock.release(playerId, token);
                }
            } finally {
                saveHistogram.record(System.nanoTime() - startNs);
            }
        }, asyncExecutor);
    }

    private void savePlayerDataLocked(UUID playerId, PlayerVaultData data) {
        String prefix = connection.getConfig().getVaultKeyPrefix();
        String metaKey = RedisKeyLayout.meta(prefix, playerId);
        String pagesKey = RedisKeyLayout.pagesIndex(prefix, playerId);
        String legacyKey = RedisKeyLayout.legacyKey(prefix, playerId);

        long version;

        // Encode all pages with optional compression. The encoding work is
        // done off the Jedis connection so we minimise the time we hold a
        // pool slot.
        Map<Integer, byte[]> pageBlobs;
        try {
            pageBlobs = new HashMap<>(data.pages().size());
            for (Map.Entry<Integer, VaultPage> entry : data.pages().entrySet()) {
                byte[] encoded = RedisSerialization.encodePage(entry.getValue().contents(),
                        compressionLevel, compressionEnabled);
                pageBlobs.put(entry.getKey(), encoded);
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Failed to encode vault for " + playerId, ex);
            throw new RuntimeException("Vault encode failed", ex);
        }

        SetParams pageParams = keyTtlSeconds > 0 ? SetParams.setParams().ex(keyTtlSeconds) : new SetParams();
        SetParams hashParams = keyTtlSeconds > 0 ? SetParams.setParams().ex(keyTtlSeconds) : new SetParams();

        // Computed up front so the post-connection invalidation publish can
        // use the same page set without re-issuing another HGETALL.
        Set<Integer> newPages = pageBlobs.keySet();
        Set<Integer> removed = new HashSet<>();

        try (Jedis jedis = connection.getPool().getResource()) {
            // The distributed lock serialises writers for this player. Read
            // the authoritative Redis version while holding it so versions
            // remain monotonic across nodes and process restarts.
            String storedVersion = jedis.hget(metaKey, RedisKeyLayout.META_FIELD_VERSION);
            long previousVersion = parseLongOrZero(storedVersion);
            version = Math.max(previousVersion,
                    localVersion.computeIfAbsent(playerId, k -> new AtomicLong(0L)).get()) + 1L;
            localVersion.get(playerId).set(version);
            // First, figure out which pages existed before so we can delete
            // ones that are no longer present in the new payload.
            Map<String, String> existingIndex = jedis.hgetAll(pagesKey);
            Set<Integer> oldPages = new HashSet<>();
            for (String key : existingIndex.keySet()) {
                oldPages.add(Integer.parseInt(key));
            }
            removed.addAll(oldPages);
            removed.removeAll(newPages);

            // Pipeline every Redis op for this save. MULTI/EXEC inside the
            // pipeline gives us atomicity across the key set so a partial
            // failure cannot leave us with a torn vault.
            List<Object> results;
            try (Transaction tx = jedis.multi()) {
                // 1. Write meta hash
                Map<String, String> metaMap = new HashMap<>(8);
                metaMap.put(RedisKeyLayout.META_FIELD_VERSION, Long.toString(version));
                metaMap.put(RedisKeyLayout.META_FIELD_CUSTOM_SLOTS, Integer.toString(data.customSlots()));
                metaMap.put(RedisKeyLayout.META_FIELD_CUSTOM_PAGES, Integer.toString(data.customPages()));
                metaMap.put(RedisKeyLayout.META_FIELD_PAGE_COUNT, Integer.toString(pageBlobs.size()));
                metaMap.put(RedisKeyLayout.META_FIELD_UPDATED_AT, Long.toString(System.currentTimeMillis()));
                metaMap.put(RedisKeyLayout.META_FIELD_UPDATED_BY, connection.getConfig().getServerId());
                tx.hset(metaKey.getBytes(), RedisObjectMap.toBytes(metaMap));
                if (keyTtlSeconds > 0) {
                    tx.expire(metaKey, keyTtlSeconds);
                }

                // 2. Rebuild the page index. We delete it first so deleted
                //    pages are reflected (Hset cannot remove fields).
                tx.del(pagesKey);
                if (!pageBlobs.isEmpty()) {
                    Map<String, String> indexMap = new HashMap<>(pageBlobs.size());
                    for (Integer pageNum : pageBlobs.keySet()) {
                        indexMap.put(Integer.toString(pageNum),
                                RedisKeyLayout.pageBlob(prefix, playerId, pageNum));
                    }
                    tx.hset(pagesKey, indexMap);
                    if (keyTtlSeconds > 0) {
                        tx.expire(pagesKey, keyTtlSeconds);
                    }
                }

                // 3. Write each page blob.
                for (Map.Entry<Integer, byte[]> entry : pageBlobs.entrySet()) {
                    String key = RedisKeyLayout.pageBlob(prefix, playerId, entry.getKey());
                    tx.set(key.getBytes(), entry.getValue(), pageParams);
                }

                // 4. Remove page blobs that no longer exist.
                for (Integer removedPage : removed) {
                    tx.del(RedisKeyLayout.pageBlob(prefix, playerId, removedPage));
                }

                // 5. Maintain a v1-compatible legacy blob when migration is
                //    still in progress. Cheap when disabled; lets the admin
                //    roll the network back to a pre-v2 binary if needed.
                if (connection.getConfig().isKeepLegacyV1()) {
                    byte[] legacy;
                    try {
                        legacy = encodeLegacyV1(data);
                    } catch (IOException ex) {
                        legacy = null;
                        logger.log(Level.WARNING, "Failed to encode legacy v1 mirror for " + playerId, ex);
                    }
                    if (legacy != null) {
                        tx.set(legacyKey.getBytes(), legacy, hashParams);
                    }
                } else {
                    tx.del(legacyKey);
                }

                results = tx.exec();
                if (results == null) {
                    throw new RuntimeException("Redis transaction aborted for " + playerId);
                }
            }

            logger.fine("Saved " + pageBlobs.size() + " page(s) to Redis for player "
                    + playerId + " (version=" + version + ", total-bytes="
                    + sumBytes(pageBlobs) + ")");
        }

        // Publish the invalidation outside the connection to avoid keeping
        // a Jedis resource while we do network I/O on the pub/sub channel.
        if (connection.getConfig().isPublishOnSave()) {
            publishInvalidation(playerId, version, newPages, removed);
        }
    }

    private static long parseLongOrZero(String value) {
        if (value == null) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void publishInvalidation(UUID playerId, long version, Set<Integer> changed, Set<Integer> removed) {
        RedisMessage msg;
        if (diffSyncEnabled) {
            Set<Integer> touched = new HashSet<>(changed);
            touched.addAll(removed);
            int[] pages = touched.stream().mapToInt(Integer::intValue).sorted().toArray();
            msg = RedisMessage.invalidatePages(playerId, connection.getConfig().getServerId(), version, pages);
        } else {
            msg = RedisMessage.invalidateFull(playerId, connection.getConfig().getServerId(), version);
        }
        try (Jedis jedis = connection.getPool().getResource()) {
            long delivered = jedis.publish(connection.getConfig().getInvalidationChannel(), msg.toJson());
            logger.fine("Published invalidation for " + playerId + " (delivered=" + delivered
                    + ", pages=" + (msg.getPages() == null ? "*" : msg.getPages().length) + ")");
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to publish invalidation for " + playerId, ex);
        }
    }

    private byte[] encodeLegacyV1(PlayerVaultData data) throws IOException {
        // Re-use the same per-page encoders then concatenate under the
        // legacy single-blob header so v1 readers still see a consistent
        // payload.
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(1024);
             java.io.DataOutputStream out = new java.io.DataOutputStream(baos)) {
            out.write(new byte[] {'V', 'V', '1', 0x00});
            out.writeByte(RedisSerialization.MAGIC_V1);
            out.writeLong(data.playerId().getMostSignificantBits());
            out.writeLong(data.playerId().getLeastSignificantBits());
            out.writeInt(data.customSlots());
            out.writeInt(data.customPages());
            out.writeInt(data.pages().size());
            for (Map.Entry<Integer, VaultPage> entry : data.pages().entrySet()) {
                VaultPage page = entry.getValue();
                out.writeInt(entry.getKey());
                ItemStack[] contents = page.contents();
                out.writeInt(contents.length);
                for (ItemStack stack : contents) {
                    if (stack == null || stack.getType().isAir()) {
                        out.writeInt(0);
                    } else {
                        try (java.io.ByteArrayOutputStream itemBaos = new java.io.ByteArrayOutputStream(256);
                             org.bukkit.util.io.BukkitObjectOutputStream boos =
                                     new org.bukkit.util.io.BukkitObjectOutputStream(itemBaos)) {
                            boos.writeObject(stack);
                            byte[] payload = itemBaos.toByteArray();
                            out.writeInt(payload.length);
                            out.write(payload);
                        }
                    }
                }
            }
            out.flush();
            return baos.toByteArray();
        }
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        return CompletableFuture.runAsync(() -> {
            var dirty = dataCache.getDirtyPlayers();
            logger.info("Saving " + dirty.size() + " dirty player(s) to Redis");
            List<CompletableFuture<Void>> futures = new ArrayList<>(dirty.size());
            for (UUID id : dirty) {
                var maybe = dataCache.get(id);
                if (maybe.isEmpty()) continue;
                futures.add(savePlayerData(id, maybe.get())
                        .thenRun(() -> dataCache.clearDirty(id))
                        .exceptionally(ex -> {
                            logger.log(Level.SEVERE, "Failed to save data for player " + id, ex);
                            return null;
                        }));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Error during Redis saveAll", ex);
            }
            logger.info("Completed saving all dirty players to Redis");
        }, asyncExecutor);
    }

    @Override
    public void close() {
        try {
            asyncExecutor.shutdown();
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        connection.close();
    }

    /**
     * Expose the underlying connection manager so wrapper classes (such as
     * {@code RedisBackedMySqlStorage}) can share the same Jedis pool for both
     * the Redis primary path and the pub/sub invalidator.
     */
    public RedisConnectionManager getConnection() {
        return connection;
    }

    @Override
    public JedisPool getJedisPoolMetrics() {
        return connection == null ? null : connection.getPool();
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
        return "REDIS";
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long sumBytes(Map<Integer, byte[]> blobs) {
        long total = 0L;
        for (byte[] b : blobs.values()) {
            total += b.length;
        }
        return total;
    }
}
