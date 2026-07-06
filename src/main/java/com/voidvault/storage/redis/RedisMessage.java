package com.voidvault.storage.redis;

import java.util.UUID;

/**
 * Envelope for messages broadcast over the VoidVaults invalidation channel.
 * <p>
 * JSON encoded so it survives server restarts and is easy to inspect with
 * {@code redis-cli}. Three message types are supported:
 * <ul>
 *     <li>{@code INVALIDATE_FULL} — legacy behaviour: another server wrote
 *         the whole vault for the given player, so any locally cached copy
 *         must be discarded. Used for first-time writes or when the
 *         invalidator cannot enumerate which pages changed (e.g. legacy
 *         v1 payloads).</li>
 *     <li>{@code INVALIDATE_PAGE} — fine-grained: only the listed page
 *         numbers changed. Other nodes can keep their cached copies of the
 *         untouched pages, which is a huge win for very large vaults where
 *         a single-slot edit no longer forces a multi-megabyte reload on
 *         every other node.</li>
 *     <li>{@code HEARTBEAT} — periodic liveness ping; currently logged only,
 *         but useful for diagnostics.</li>
 * </ul>
 *
 * <h2>Why a version field?</h2>
 * Each INVALIDATE_PAGE/INVALIDATE_FULL carries a monotonic
 * {@code version} supplied by the writer. The subscriber ignores messages
 * whose version is &le; the one already applied locally, which closes the
 * race where a slow message from node A can clobber a newer write that
 * already won the lock on node B.
 */
public final class RedisMessage {

    public static final String TYPE_INVALIDATE_FULL = "INVALIDATE_FULL";
    public static final String TYPE_INVALIDATE_PAGE = "INVALIDATE_PAGE";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";

    private final String type;
    private final UUID playerId;
    private final String originServer;
    private final long timestampMs;
    private final long version;
    private final int[] pages;

    public RedisMessage(String type, UUID playerId, String originServer, long timestampMs, long version, int[] pages) {
        this.type = type;
        this.playerId = playerId;
        this.originServer = originServer;
        this.timestampMs = timestampMs;
        this.version = version;
        this.pages = pages;
    }

    public static RedisMessage invalidateFull(UUID playerId, String originServer, long version) {
        return new RedisMessage(TYPE_INVALIDATE_FULL, playerId, originServer, System.currentTimeMillis(), version, null);
    }

    public static RedisMessage invalidatePages(UUID playerId, String originServer, long version, int[] pages) {
        int[] copy;
        if (pages == null) {
            copy = null;
        } else {
            copy = new int[pages.length];
            System.arraycopy(pages, 0, copy, 0, pages.length);
        }
        return new RedisMessage(TYPE_INVALIDATE_PAGE, playerId, originServer, System.currentTimeMillis(), version, copy);
    }

    public static RedisMessage heartbeat(String originServer) {
        return new RedisMessage(TYPE_HEARTBEAT, null, originServer, System.currentTimeMillis(), 0L, null);
    }

    public String getType() { return type; }
    public UUID getPlayerId() { return playerId; }
    public String getOriginServer() { return originServer; }
    public long getTimestampMs() { return timestampMs; }
    public long getVersion() { return version; }
    public int[] getPages() { return pages; }

    /** Minimal hand-rolled JSON encoder to avoid pulling in Gson for the hot path. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendJsonField(sb, "type", type, true);
        appendJsonField(sb, "originServer", originServer, false);
        if (playerId != null) {
            appendJsonField(sb, "playerId", playerId.toString(), false);
        } else {
            appendJsonField(sb, "playerId", null, false);
        }
        sb.append("\"timestampMs\":").append(timestampMs);
        sb.append(",\"version\":").append(version);
        if (pages != null) {
            sb.append(",\"pages\":[");
            for (int i = 0; i < pages.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(pages[i]);
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(escape(key)).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Parse a JSON envelope produced by {@link #toJson()}. Returns {@code null}
     * for malformed payloads so the subscriber can log and skip them.
     */
    public static RedisMessage fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            String type = extractString(json, "type");
            String origin = extractString(json, "originServer");
            String playerStr = extractString(json, "playerId");
            UUID playerId = (playerStr == null || playerStr.isEmpty()) ? null : UUID.fromString(playerStr);
            long ts = extractLong(json, "timestampMs");
            long version = extractLong(json, "version");
            int[] pages = extractPages(json);
            if (type == null || origin == null) return null;
            return new RedisMessage(type, playerId, origin, ts, version, pages);
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] extractPages(String json) {
        int idx = json.indexOf("\"pages\":");
        if (idx < 0) return null;
        int start = json.indexOf('[', idx);
        int end = json.indexOf(']', idx);
        if (start < 0 || end < 0 || end <= start) return null;
        String body = json.substring(start + 1, end);
        if (body.isBlank()) return new int[0];
        String[] parts = body.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        int valStart = idx + marker.length();
        // skip whitespace
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\t')) valStart++;
        if (valStart >= json.length()) return null;
        if (json.startsWith("null", valStart)) return null;
        if (json.charAt(valStart) != '"') return null;
        valStart++;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = valStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { sb.append(c); escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') return sb.toString();
            sb.append(c);
        }
        return null;
    }

    private static long extractLong(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) return 0L;
        int valStart = idx + marker.length();
        int end = valStart;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}') break;
            end++;
        }
        try {
            return Long.parseLong(json.substring(valStart, end).trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return "RedisMessage[" + type + " playerId=" + playerId + " origin=" + originServer
                + " version=" + version + " pages=" + (pages == null ? "*" : pages.length) + "]";
    }
}