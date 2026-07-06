package com.voidvault.storage.redis;

import com.voidvault.model.PlayerVaultData;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One unit of asynchronous MySQL persistence work produced by
 * {@link RedisBackedMySqlStorage}.
 * <p>
 * Each job is identified by a monotonically increasing sequence so the retry
 * dispatcher can safely drop older copies of the same player if a newer write
 * arrives before the old one finishes.
 */
public final class PersistJob {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);

    private final UUID playerId;
    private final PlayerVaultData data;
    private final long scheduledAtMs;
    private final int sequence;
    private int attempts;

    public PersistJob(UUID playerId, PlayerVaultData data) {
        this.playerId = playerId;
        this.data = data;
        this.scheduledAtMs = System.currentTimeMillis();
        this.sequence = SEQUENCE.incrementAndGet();
        this.attempts = 0;
    }

    public UUID getPlayerId() { return playerId; }
    public PlayerVaultData getData() { return data; }
    public long getScheduledAtMs() { return scheduledAtMs; }
    public int getSequence() { return sequence; }
    public int getAttempts() { return attempts; }

    public void incrementAttempts() { this.attempts++; }
}