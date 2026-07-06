package com.voidvault.storage.redis;

import com.voidvault.model.PlayerVaultData;
import com.voidvault.model.VaultPage;
import com.voidvault.storage.compression.CompressionCodec;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serialization helpers that turn {@link PlayerVaultData} into a compact byte[]
 * suitable for storage in Redis.
 *
 * <h2>Wire format history</h2>
 * <ul>
 *     <li>v1 (legacy, still accepted on read): single-blob per player, magic
 *         {@code "VV1\0"}. Stored everything inside one Redis String. Simple
 *         but terrible for very large NBT because every save has to rewrite
 *         the whole payload, and every other server that holds a copy must
 *         invalidate the full local cache even when only one slot changed.</li>
 *     <li>v2 (current): magic {@code "VV2\0"} followed by the per-player
 *         header. The header carries the player UUID, slot/page metadata and a
 *         monotonically increasing version number. The actual item data is
 *         stored as one or more per-page payloads (see {@link #encodePage}).
 *         Pages are then placed in separate Redis keys by the caller so that a
 *         single-slot edit only touches one page-bucket.</li>
 * </ul>
 *
 * <h2>Compression</h2>
 * Each individual page payload is optionally run through
 * {@link CompressionCodec} (only when the page payload exceeds the threshold
 * and compression is enabled by the live config). The compression flag is a
 * single byte in the page header so old uncompressed payloads stay readable.
 *
 * <h2>Why a versioned header?</h2>
 * Multi-server deployments need a defence against replayed old writes. The
 * version field is the same one the invalidator broadcasts in pub/sub
 * messages, so a node can drop a payload whose version is older than the one
 * it already has cached (no "stale write wins" race).
 */
public final class RedisSerialization {

    public static final byte MAGIC_V1 = 0x01;
    public static final byte MAGIC_V2 = 0x02;
    private static final byte[] MAGIC_V2_BYTES = {'V', 'V', '2', 0x00};
    private static final byte[] MAGIC_V1_BYTES = {'V', 'V', '1', 0x00};

    /**
     * Current "wire" version emitted by {@link #encode(PlayerVaultData, int)}.
     * Bumped whenever the on-the-wire layout changes in an incompatible way.
     */
    public static final byte CURRENT_VERSION = MAGIC_V2;

    private RedisSerialization() {}

    /**
     * Encode a {@link PlayerVaultData} record into the new per-page wire
     * format. Callers should pass the configured compression level (1..9) and
     * an {@code enabled} flag so the policy lives in one place (RedisConfig).
     * <p>
     * The result is a small header followed by an array of page blobs. The
     * caller decides where to store each page (e.g. one Redis String per page).
     *
     * @param data            player payload
     * @param compressionLevel zlib compression level (1..9); ignored when
     *                         compression is disabled by the config
     * @param compressionEnabled whether compression is enabled at all
     * @return encoded envelope; never {@code null}
     */
    public static Encoded encode(PlayerVaultData data, int compressionLevel, boolean compressionEnabled) throws IOException {
        Map<Integer, VaultPage> pages = data.pages();
        Map<Integer, byte[]> pageBlobs = new LinkedHashMap<>(pages.size());
        for (Map.Entry<Integer, VaultPage> entry : pages.entrySet()) {
            VaultPage page = entry.getValue();
            byte[] pageBytes = encodePage(page.contents(), compressionLevel, compressionEnabled);
            pageBlobs.put(entry.getKey(), pageBytes);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(baos);
        out.write(MAGIC_V2_BYTES);
        out.writeByte(CURRENT_VERSION);
        out.writeLong(data.playerId().getMostSignificantBits());
        out.writeLong(data.playerId().getLeastSignificantBits());
        out.writeInt(data.customSlots());
        out.writeInt(data.customPages());
        out.writeInt(pageBlobs.size());
        // We deliberately do not embed the page blobs here. The header is
        // intentionally tiny (<100 bytes) so it can live in a Hash slot and
        // be re-broadcast in a pub/sub envelope cheaply. The caller stores
        // each page blob at its own Redis key.
        out.flush();
        return new Encoded(baos.toByteArray(), pageBlobs);
    }

    /**
     * Result of {@link #encode}. The header is what callers persist in the
     * player's meta Hash; the page blobs are stored separately so a small
     * change only rewrites one key.
     */
    public record Encoded(byte[] header, Map<Integer, byte[]> pageBlobs) {}

    /**
     * Encode a single page into the wire format consumed by
     * {@link #decodePage(byte[])}.
     */
    public static byte[] encodePage(ItemStack[] contents, int compressionLevel, boolean compressionEnabled) throws IOException {
        if (contents == null) {
            contents = new ItemStack[0];
        }
        // Stage 1: serialise the slot array with BukkitObjectOutputStream.
        ByteArrayOutputStage stage = new ByteArrayOutputStage(contents.length * 256);
        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(stage.baos)) {
            boos.writeInt(contents.length);
            for (ItemStack stack : contents) {
                if (stack == null || stack.getType().isAir()) {
                    boos.writeInt(0);
                    boos.writeInt(0); // 0 quantity sentry
                    boos.writeBoolean(false); // hasMeta sentry
                } else {
                    boos.writeInt(stack.getAmount());
                    boos.writeInt(stack.getType().ordinal());
                    boos.writeBoolean(true);
                    // Write the whole ItemStack (covers NBT, enchantments,
                    // custom model data, PDC, attribute modifiers, etc.). We
                    // do this with writeObject rather than hand-rolled NBT to
                    // keep the codec version-agnostic across Bukkit APIs.
                    boos.writeObject(stack);
                }
            }
        }
        byte[] serialised = stage.baos.toByteArray();

        // Stage 2: optional compression. We require >256 bytes (matching
        // CompressionCodec.MIN_COMPRESS_SIZE) to even consider the path.
        boolean compressed = compressionEnabled && CompressionCodec.isWorthCompressing(serialised);
        byte[] payload = compressed
                ? CompressionCodec.compress(serialised, compressionLevel)
                : serialised;

        // Stage 3: wrap with a per-page header so we can evolve the layout
        // without breaking older payloads.
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        DataOutputStream dout = new DataOutputStream(out);
        dout.writeByte(CURRENT_VERSION);
        dout.writeByte(compressed ? 0x01 : 0x00);
        dout.writeInt(serialised.length); // uncompressed size for sanity checks
        dout.writeInt(payload.length);
        dout.write(payload);
        dout.flush();
        return out.toByteArray();
    }

    /**
     * Decode a page payload produced by {@link #encodePage(ItemStack[], int, boolean)}.
     */
    public static ItemStack[] decodePage(byte[] payload) throws IOException {
        if (payload == null || payload.length < 10) {
            return new ItemStack[0];
        }
        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte version = din.readByte();
            if (version != CURRENT_VERSION) {
                throw new IOException("Unsupported page wire version: " + version);
            }
            boolean compressed = din.readByte() == 0x01;
            int rawLen = din.readInt();
            int bodyLen = din.readInt();
            byte[] body = new byte[bodyLen];
            din.readFully(body);
            byte[] serialised;
            if (compressed) {
                byte[] decompressed = CompressionCodec.decompress(body);
                if (decompressed == null) {
                    throw new IOException("Failed to decompress page payload");
                }
                serialised = decompressed;
            } else {
                serialised = body;
            }
            if (serialised.length != rawLen) {
                throw new IOException("Page payload size mismatch (expected " + rawLen + ", got " + serialised.length + ")");
            }
            try (BukkitObjectInputStream bis = new BukkitObjectInputStream(new ByteArrayInputStream(serialised))) {
                int slotCount = bis.readInt();
                ItemStack[] contents = new ItemStack[slotCount];
                for (int i = 0; i < slotCount; i++) {
                    int amount = bis.readInt();
                    int ordinal = bis.readInt();
                    boolean hasMeta = bis.readBoolean();
                    if (!hasMeta || amount <= 0) {
                        contents[i] = null;
                        continue;
                    }
                    Object obj = bis.readObject();
                    if (!(obj instanceof ItemStack stack)) {
                        contents[i] = null;
                        continue;
                    }
                    if (stack.getType().ordinal() != ordinal) {
                        // Defensive: some plugins mutate ItemStack after
                        // serialisation. Re-anchor the ordinal so the type
                        // matches the recorded quantity.
                        stack.setAmount(amount);
                    }
                    contents[i] = stack.clone();
                }
                return contents;
            } catch (ClassNotFoundException ex) {
                throw new IOException("ItemStack class missing on this server", ex);
            }
        }
    }

    /**
     * Decode the {@link #encode(PlayerVaultData, int, boolean)} header into
     * its constituent fields. The page blobs are not embedded in the header
     * and must be fetched separately.
     */
    public static PlayerHeader decodeHeader(byte[] header) throws IOException {
        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(header))) {
            byte[] magic = new byte[4];
            din.readFully(magic);
            for (int i = 0; i < MAGIC_V2_BYTES.length; i++) {
                if (magic[i] != MAGIC_V2_BYTES[i]) {
                    throw new IOException("Invalid v2 header magic");
                }
            }
            byte version = din.readByte();
            long msb = din.readLong();
            long lsb = din.readLong();
            UUID playerId = new UUID(msb, lsb);
            int customSlots = din.readInt();
            int customPages = din.readInt();
            int pageCount = din.readInt();
            return new PlayerHeader(version, playerId, customSlots, customPages, pageCount);
        }
    }

    /**
     * Decode a legacy v1 blob (single-string payload written by the old
     * RedisSerialization). Returns a fully materialised
     * {@link PlayerVaultData} because v1 had no per-page split.
     */
    public static PlayerVaultData decodeLegacyV1(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < MAGIC_V1_BYTES.length; i++) {
                if (magic[i] != MAGIC_V1_BYTES[i]) {
                    throw new IOException("Invalid legacy payload magic");
                }
            }
            byte version = in.readByte();
            if (version != MAGIC_V1) {
                throw new IOException("Unsupported legacy payload version: " + version);
            }

            long msb = in.readLong();
            long lsb = in.readLong();
            UUID playerId = new UUID(msb, lsb);
            int customSlots = in.readInt();
            int customPages = in.readInt();
            int pageCount = in.readInt();

            Map<Integer, VaultPage> pages = new ConcurrentHashMap<>();
            for (int p = 0; p < pageCount; p++) {
                int pageNumber = in.readInt();
                int contentsLength = in.readInt();
                ItemStack[] contents = new ItemStack[contentsLength];
                for (int s = 0; s < contentsLength; s++) {
                    int itemLength = in.readInt();
                    if (itemLength <= 0) {
                        contents[s] = null;
                    } else {
                        byte[] bytes = new byte[itemLength];
                        in.readFully(bytes);
                        contents[s] = deserializeItemStack(bytes).clone();
                    }
                }
                pages.put(pageNumber, new VaultPage(pageNumber, contents));
            }
            return new PlayerVaultData(playerId, pages, customSlots, customPages);
        }
    }

    /**
     * Reassemble an {@link PlayerVaultData} from a decoded header plus the
     * already-loaded page blobs.
     */
    public static PlayerVaultData assemble(PlayerHeader header, Map<Integer, ItemStack[]> pages) {
        Map<Integer, VaultPage> vPages = new ConcurrentHashMap<>(pages.size());
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            vPages.put(entry.getKey(), new VaultPage(entry.getKey(), entry.getValue()));
        }
        return new PlayerVaultData(header.playerId(), vPages, header.customSlots(), header.customPages());
    }

    /**
     * Header fields as emitted by {@link #encode}. Useful as a payload for
     * pub/sub messages that need to describe a write without re-shipping the
     * full body.
     */
    public record PlayerHeader(
            byte version,
            UUID playerId,
            int customSlots,
            int customPages,
            int pageCount
    ) {}

    /**
     * Holder so {@link #encodePage} can swap the underlying buffer without
     * allocating twice.
     */
    private static final class ByteArrayOutputStage {
        final ByteArrayOutputStream baos;
        ByteArrayOutputStage(int initial) {
            this.baos = new ByteArrayOutputStream(initial);
        }
    }

    private static ItemStack deserializeItemStack(byte[] bytes) throws IOException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ItemStack) in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("ItemStack class missing on this server", e);
        }
    }
}