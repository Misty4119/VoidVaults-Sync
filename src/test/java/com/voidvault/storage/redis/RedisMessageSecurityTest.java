package com.voidvault.storage.redis;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisMessageSecurityTest {

    @Test
    void validInvalidationRoundTrips() {
        UUID playerId = UUID.randomUUID();
        RedisMessage decoded = RedisMessage.fromJson(
                RedisMessage.invalidatePages(playerId, "server-a", 7L, new int[]{1, 3}).toJson());

        assertEquals(playerId, decoded.getPlayerId());
        assertEquals(7L, decoded.getVersion());
        assertArrayEquals(new int[]{1, 3}, decoded.getPages());
    }

    @Test
    void rejectsUnknownAndStructurallyInvalidMessages() {
        String id = UUID.randomUUID().toString();
        assertNull(RedisMessage.fromJson("{\"type\":\"EVICT_ALL\",\"originServer\":\"evil\","
                + "\"playerId\":\"" + id + "\",\"timestampMs\":1,\"version\":1}"));
        assertNull(RedisMessage.fromJson("{\"type\":\"INVALIDATE_PAGE\",\"originServer\":\"evil\","
                + "\"playerId\":\"" + id + "\",\"timestampMs\":1,\"version\":0,\"pages\":[-1]}"));
        assertNull(RedisMessage.fromJson("x".repeat(4097)));
    }
}
