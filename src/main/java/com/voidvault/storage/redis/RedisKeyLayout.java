package com.voidvault.storage.redis;

import java.util.UUID;

/**
 * Centralised naming scheme for every Redis key the plugin owns.
 * <p>
 * Having a single source of truth is important: the storage layer, the
 * pub/sub publisher and the invalidator all need to agree on the exact byte
 * sequence that identifies a player's vault. Otherwise a save on node A
 * would publish an invalidation for key {@code vv:p:UUID}, while node B is
 * watching {@code voidvault:player:UUID} and the change would silently
 * fail to propagate.
 *
 * <h2>Layout</h2>
 * <pre>
 *   {prefix}{uuid}:meta      Hash   one field per vault meta attribute
 *                                  "version", "customSlots", "customPages",
 *                                  "pageCount", "updatedAt", "updatedBy"
 *   {prefix}{uuid}:pages     Hash   page number -&gt; page-bucket key
 *   {prefix}{uuid}:page:{n}  String one page payload (zstd/zlib optional)
 *   {prefix}{uuid}:lock      String ephemeral SETNX token used to serialise
 *                                  concurrent writes from different nodes
 *   {prefix}{uuid}:legacy    String optional fallback of the v1 blob for
 *                                  deployments still in the middle of a
 *                                  migration. Removed once every node has
 *                                  been upgraded.
 * </pre>
 *
 * The default {@code prefix} is {@code "vv:p:"} so the most common lookup
 * is a short Redis key (smaller KEYS scan, smaller replication stream,
 * better Jedis pool throughput). Configurable via
 * {@link RedisConfig#getVaultKeyPrefix()}.
 */
public final class RedisKeyLayout {

    public static final String META_FIELD_VERSION = "version";
    public static final String META_FIELD_CUSTOM_SLOTS = "customSlots";
    public static final String META_FIELD_CUSTOM_PAGES = "customPages";
    public static final String META_FIELD_PAGE_COUNT = "pageCount";
    public static final String META_FIELD_UPDATED_AT = "updatedAt";
    public static final String META_FIELD_UPDATED_BY = "updatedBy";

    private RedisKeyLayout() {}

    public static String meta(String prefix, UUID playerId) {
        return prefix + playerId + ":meta";
    }

    public static String pagesIndex(String prefix, UUID playerId) {
        return prefix + playerId + ":pages";
    }

    public static String pageBlob(String prefix, UUID playerId, int pageNumber) {
        return prefix + playerId + ":page:" + pageNumber;
    }

    public static String pagePattern(String prefix, UUID playerId) {
        return prefix + playerId + ":page:*";
    }

    public static String lockKey(String prefix, UUID playerId) {
        return prefix + playerId + ":lock";
    }

    public static String legacyKey(String prefix, UUID playerId) {
        return prefix + playerId + ":legacy";
    }

    public static String playerKey(String prefix, UUID playerId) {
        return prefix + playerId;
    }
}