package com.voidvault.storage.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.UUID;

/**
 * Minimal distributed-lock helper built on Redis {@code SET NX PX}.
 * <p>
 * The {@link #tryAcquire(UUID, String, long)} call returns a {@code null}
 * token when the lock is held by someone else; the same token must be passed
 * back to {@link #release(UUID, String, String)} so we never release a lock
 * that was taken over after our TTL expired (the classic "two writers, one
 * stale owner" footgun). The implementation is intentionally allocation-light:
 * one Redis round-trip to acquire, one Lua script to release, one round-trip
 * to fetch the underlying connection.
 *
 * <h2>Why no Redlock?</h2>
 * The Redlock algorithm assumes multiple independent Redis masters, which
 * most Minecraft server networks do not run. For a single-master setup the
 * {@code SET NX PX} + Lua release pattern is the standard recommendation
 * (this is what Redisson and Lettuce ship as the default lock primitive).
 */
public final class RedisDistributedLock {

    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final RedisConnectionManager connection;
    private final RedisConfig config;

    public RedisDistributedLock(RedisConnectionManager connection) {
        this.connection = connection;
        this.config = connection.getConfig();
    }

    /**
     * Attempt to acquire the player-scoped write lock.
     *
     * @param playerId  player whose vault is being written
     * @param token     unique token for this acquisition; callers should pass
     *                  a fresh {@link UUID#randomUUID()} per attempt
     * @param ttlMillis how long the lock stays valid if the holder crashes
     * @return a non-null token when the lock was acquired; {@code null} when
     *         another node already holds it
     */
    public String tryAcquire(UUID playerId, String token, long ttlMillis) {
        String key = RedisKeyLayout.lockKey(config.getVaultKeyPrefix(), playerId);
        try (Jedis jedis = connection.getPool().getResource()) {
            String result = jedis.set(key.getBytes(), token.getBytes(),
                    SetParams.setParams().nx().px(ttlMillis));
            return result == null ? null : token;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Release the lock if (and only if) the supplied token still owns it.
     * @return {@code true} when the lock was held by this caller and removed
     */
    public boolean release(UUID playerId, String token) {
        if (playerId == null || token == null) {
            return false;
        }
        String key = RedisKeyLayout.lockKey(config.getVaultKeyPrefix(), playerId);
        try (Jedis jedis = connection.getPool().getResource()) {
            Object result = jedis.eval(RELEASE_LUA,
                    Collections.singletonList(key),
                    Collections.singletonList(token));
            return result instanceof Long l && l > 0L;
        } catch (Exception ex) {
            return false;
        }
    }
}