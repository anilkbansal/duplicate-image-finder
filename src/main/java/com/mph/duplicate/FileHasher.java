package com.mph.duplicate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streaming, memory-efficient SHA-256 file hasher.
 *
 * <h2>Two-phase approach for 2 TB+ scale</h2>
 * <ol>
 *   <li><b>Partial key</b> – {@code fileSize + SHA-256(first N bytes)}.
 *       Two files with different sizes or different leading bytes are
 *       guaranteed NOT to be duplicates → skip full hash entirely.</li>
 *   <li><b>Full hash</b> – SHA-256 of the complete file, computed only for
 *       files that survived the partial-key pre-filter (typically &lt; 5 %).</li>
 * </ol>
 *
 * <p>Memory usage is bounded by the caller-supplied {@code bufferSize}
 * regardless of file size (files are never fully loaded into memory).
 */
public final class FileHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FileHasher() { /* utility class – no instances */ }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Computes the partial pre-filter key:
     * {@code "<fileSize>:<sha256(first partialHashBytes)>"}.
     *
     * <p>If the file is smaller than {@code partialHashBytes} the full hash
     * is used directly (so partial == full for small files).
     */
    public static String computePartialKey(Path file, int partialHashBytes, int bufferSize)
            throws IOException {
        long size = Files.size(file);
        if (size <= partialHashBytes) {
            // Small file: partial IS the full hash – tag it so the caller knows
            return size + ":FULL:" + computeFullHash(file, bufferSize);
        }
        return size + ":" + hashFirstNBytes(file, partialHashBytes, bufferSize);
    }

    /**
     * Computes SHA-256 of the entire file using streaming I/O.
     * Memory usage = {@code bufferSize} bytes regardless of file size.
     *
     * @return lowercase hex-encoded 64-character SHA-256 digest
     */
    public static String computeFullHash(Path file, int bufferSize) throws IOException {
        MessageDigest digest = sha256();
        byte[] buf = new byte[bufferSize];
        try (InputStream in  = Files.newInputStream(file);
             DigestInputStream dis = new DigestInputStream(in, digest)) {
            //noinspection StatementWithEmptyBody
            while (dis.read(buf) != -1) { /* drain – DigestInputStream feeds bytes to digest */ }
        }
        return toHex(digest.digest());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** SHA-256 of the first {@code maxBytes} bytes of the file. */
    private static String hashFirstNBytes(Path file, int maxBytes, int bufferSize)
            throws IOException {
        MessageDigest digest = sha256();
        byte[] buf = new byte[bufferSize];
        int remaining = maxBytes;
        try (InputStream in = Files.newInputStream(file)) {
            while (remaining > 0) {
                int read = in.read(buf, 0, Math.min(remaining, buf.length));
                if (read == -1) break;
                digest.update(buf, 0, read);
                remaining -= read;
            }
        }
        return toHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2]     = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}

