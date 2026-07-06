package com.voidvault.storage.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.CRC32;

/**
 * Self-contained compression helpers used by the Redis and MySQL persistence
 * layers to keep large NBT payloads manageable.
 * <p>
 * The codec is intentionally framework-free so the plugin does not need to
 * shade a third-party compressor. It wraps the JDK's {@link Deflater} (raw
 * zlib, no zlib/gzip header) and writes a tiny preamble so the output is
 * self-describing:
 * <pre>
 *   magic       4 bytes   "VVZ\0"
 *   level       1 byte    0..9 (Deflater compression level at encode time)
 *   rawLen      4 bytes   original byte length (lets us skip the decode loop
 *                          in the hot path and lets tools preallocate)
 *   payload     N bytes   raw-deflated stream
 *   crc32       4 bytes   CRC-32 of the uncompressed payload (defence in
 *                          depth against on-disk / on-wire corruption)
 * </pre>
 * The decoder returns {@code null} (rather than throwing) when the magic or
 * checksum does not match, so an uncompressed payload can never accidentally
 * be inflated — that path used to silently corrupt ItemStack data.
 *
 * <h2>Why zlib (and not zstd / lz4)?</h2>
 * Maven shade already pulls in a lot of bytecode. Adding zstd-jni or aircompressor
 * would force another relocation rule and a native dependency for the JNI variant.
 * JDK {@link Deflater} reaches ~3-6x compression on Bukkit serialised NBT
 * (heavy on repeated tag names and string keys), which covers the bulk of the
 * savings. Callers can still disable compression via the {@code <Compression>}
 * threshold / level configuration if they need raw speed.
 */
public final class CompressionCodec {

    public static final int MIN_COMPRESS_SIZE = 256;

    private static final byte[] MAGIC = {'V', 'V', 'Z', 0x00};
    /**
     * Length of the fixed-size preamble preceding the deflated body:
     * magic(4) + level(1) + rawLen(4) = 9 bytes. The 4-byte CRC32 trailer is
     * written <em>after</em> the body and is therefore not included here.
     */
    private static final int HEADER_LEN = MAGIC.length + 1 + 4;
    private static final int CRC_LEN = 4;
    public static final int MAX_LEVEL = Deflater.BEST_SPEED;
    public static final int DEFAULT_LEVEL = Deflater.DEFAULT_COMPRESSION;

    private CompressionCodec() {}

    /**
     * Decide whether a payload is worth compressing. Below {@link #MIN_COMPRESS_SIZE}
     * bytes the framing overhead and CPU cost outweigh the storage win.
     */
    public static boolean isWorthCompressing(byte[] payload) {
        return payload != null && payload.length >= MIN_COMPRESS_SIZE;
    }

    /**
     * Compress {@code raw} using the supplied deflater level (1..9). Returns
     * {@code null} when {@code raw} is {@code null} or empty so callers can
     * chain null-checks naturally.
     */
    public static byte[] compress(byte[] raw, int level) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        int clamped = Math.max(Deflater.NO_COMPRESSION, Math.min(level, Deflater.BEST_COMPRESSION));
        ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length / 2);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, new Deflater(clamped))) {
            dos.write(raw);
        } catch (IOException impossible) {
            // ByteArrayOutputStream#write never throws.
            throw new IllegalStateException(impossible);
        }
        byte[] body = baos.toByteArray();
        byte[] out = new byte[HEADER_LEN + body.length + CRC_LEN];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) clamped;
        int rawLen = raw.length;
        out[MAGIC.length + 1] = (byte) (rawLen >>> 24);
        out[MAGIC.length + 2] = (byte) (rawLen >>> 16);
        out[MAGIC.length + 3] = (byte) (rawLen >>> 8);
        out[MAGIC.length + 4] = (byte) rawLen;
        System.arraycopy(body, 0, out, HEADER_LEN, body.length);
        int crcOffset = HEADER_LEN + body.length;
        long crc = crc32(raw);
        out[crcOffset]     = (byte) (crc >>> 24);
        out[crcOffset + 1] = (byte) (crc >>> 16);
        out[crcOffset + 2] = (byte) (crc >>> 8);
        out[crcOffset + 3] = (byte) crc;
        return out;
    }

    /**
     * Decompress a payload produced by {@link #compress(byte[], int)}.
     * Returns {@code null} when the magic header is missing or the CRC
     * mismatches — callers should treat that as "fall back to raw bytes"
     * rather than propagating an exception.
     */
    public static byte[] decompress(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_LEN + CRC_LEN) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (encoded[i] != MAGIC[i]) {
                return null;
            }
        }
        int rawLen = ((encoded[MAGIC.length + 1] & 0xFF) << 24)
                   | ((encoded[MAGIC.length + 2] & 0xFF) << 16)
                   | ((encoded[MAGIC.length + 3] & 0xFF) << 8)
                   |  (encoded[MAGIC.length + 4] & 0xFF);
        int bodyLen = encoded.length - HEADER_LEN - CRC_LEN;
        if (rawLen < 0 || bodyLen < 0) {
            return null;
        }
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(encoded, HEADER_LEN, bodyLen);
            byte[] out = new byte[rawLen];
            int written = inflater.inflate(out);
            inflater.end();
            if (written != rawLen) {
                return null;
            }
            long storedCrc = ((encoded[encoded.length - 4] & 0xFFL) << 24)
                           | ((encoded[encoded.length - 3] & 0xFFL) << 16)
                           | ((encoded[encoded.length - 2] & 0xFFL) << 8)
                           |  (encoded[encoded.length - 1] & 0xFFL);
            if (storedCrc != crc32(out)) {
                return null;
            }
            return out;
        } catch (DataFormatException | IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Streaming variant of {@link #decompress(byte[])} for callers that want
     * to stream the decompressed bytes straight into a {@code DataOutputStream}
     * without first materialising them.
     */
    public static byte[] decompressStreaming(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_LEN + CRC_LEN) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (encoded[i] != MAGIC[i]) {
                return null;
            }
        }
        int rawLen = ((encoded[MAGIC.length + 1] & 0xFF) << 24)
                   | ((encoded[MAGIC.length + 2] & 0xFF) << 16)
                   | ((encoded[MAGIC.length + 3] & 0xFF) << 8)
                   |  (encoded[MAGIC.length + 4] & 0xFF);
        int bodyLen = encoded.length - HEADER_LEN - CRC_LEN;
        if (rawLen < 0 || bodyLen < 0) {
            return null;
        }
        try (InflaterInputStream iis = new InflaterInputStream(
                new ByteArrayInputStream(encoded, HEADER_LEN, bodyLen))) {
            byte[] out = new byte[rawLen];
            int read = 0;
            while (read < rawLen) {
                int n = iis.read(out, read, rawLen - read);
                if (n < 0) break;
                read += n;
            }
            if (read != rawLen) {
                return null;
            }
            long storedCrc = ((encoded[encoded.length - 4] & 0xFFL) << 24)
                           | ((encoded[encoded.length - 3] & 0xFFL) << 16)
                           | ((encoded[encoded.length - 2] & 0xFFL) << 8)
                           |  (encoded[encoded.length - 1] & 0xFFL);
            if (storedCrc != crc32(out)) {
                return null;
            }
            return out;
        } catch (IOException impossible) {
            return null;
        }
    }

    /**
     * Quick check that tells callers (e.g. the Redis serializer) whether they
     * should attempt to interpret a payload as compressed without paying the
     * cost of a full decompression attempt.
     */
    public static boolean looksCompressed(byte[] payload) {
        if (payload == null || payload.length < MAGIC.length) {
            return false;
        }
        return Arrays.equals(MAGIC, 0, MAGIC.length, payload, 0, MAGIC.length);
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }
}