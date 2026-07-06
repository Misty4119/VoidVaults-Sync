package com.voidvault.storage.redis;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small helper that converts a {@code Map<String,String>} into the
 * {@code Map<byte[],byte[]>} shape that the Jedis 7 hash APIs expect.
 * <p>
 * Keeping the helper in one place means callers do not have to repeat the
 * UTF-8 conversion (and the defensive null handling) at every call site.
 * The iteration order of the returned map matches the input map, which
 * matters because some Redis clients treat HSET as order-sensitive when
 * they repackage the reply.
 */
final class RedisObjectMap {

    private RedisObjectMap() {}

    static Map<byte[], byte[]> toBytes(Map<String, String> input) {
        Map<byte[], byte[]> out = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, String> entry : input.entrySet()) {
            byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue() == null
                    ? new byte[0]
                    : entry.getValue().getBytes(StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }
}