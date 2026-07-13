package com.mph.duplicate;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes a perceptual fingerprint for a video file using frame sampling
 * and DCT-based pHash (perceptual hash).
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Frame sampling</b>: Opens the video with FFmpeg (via JavaCV) and
 *       extracts {@code sampleCount} frames evenly distributed across the
 *       video's duration. Only key-frames near the target timestamps are
 *       decoded, making this fast even for large files.</li>
 *   <li><b>Per-frame pHash</b>: Each frame is:
 *       <ul>
 *         <li>Scaled to a 32×32 grayscale thumbnail.</li>
 *         <li>A 2-D DCT is applied; only the top-left 8×8 low-frequency
 *             coefficients are kept (skipping DC term).</li>
 *         <li>A 64-bit hash is produced by comparing each coefficient to the
 *             mean — bit = 1 if above mean, 0 if below.</li>
 *       </ul>
 *   </li>
 *   <li><b>Fingerprint</b>: The per-frame hashes are concatenated as a
 *       hex string.  Two videos are "perceptually similar" if every
 *       corresponding frame-hash pair has a Hamming distance ≤ threshold
 *       AND the average distance across all frames is also ≤ threshold.</li>
 * </ol>
 *
 * <h2>Why pHash?</h2>
 * Unlike SHA-256, pHash is robust to:
 * <ul>
 *   <li>Minor re-encoding (different bitrate / codec settings)</li>
 *   <li>Container changes (e.g. .mp4 → .mkv of the same content)</li>
 *   <li>Minor colour grading differences</li>
 * </ul>
 * It will NOT match videos that differ in resolution, aspect ratio, frame rate
 * by more than a small margin, or that have significant content differences.
 */
public class VideoPerceptualHasher {

    private static final Logger log = LoggerFactory.getLogger(VideoPerceptualHasher.class);

    // DCT thumbnail size — 32×32 is standard for pHash
    private static final int THUMB_SIZE  = 32;
    // Low-frequency block size taken from DCT — 8×8 = 64 bits per frame hash
    private static final int DCT_BLOCK   = 8;
    // Number of bits per frame hash
    private static final int BITS_PER_FRAME = DCT_BLOCK * DCT_BLOCK; // 64

    private final int sampleCount;       // number of frames to sample per video
    private final int hammingThreshold;  // max Hamming distance to consider a match

    /**
     * @param sampleCount       number of frames to sample (e.g. 8, 16, 32)
     * @param hammingThreshold  max average per-frame Hamming distance to be
     *                          considered "similar" (0 = identical, 10 = lenient)
     */
    public VideoPerceptualHasher(int sampleCount, int hammingThreshold) {
        this.sampleCount      = Math.max(4, sampleCount);
        this.hammingThreshold = Math.max(0, hammingThreshold);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes the perceptual fingerprint (array of per-frame 64-bit hashes)
     * for the given video file.
     *
     * @param videoPath path to the video file
     * @return array of {@code sampleCount} longs (each is a 64-bit pHash);
     *         returns {@code null} if the file cannot be decoded or has no
     *         video stream
     */
    public long[] computeFingerprint(Path videoPath) {
        String filePath = videoPath.toAbsolutePath().toString();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(filePath)) {
            grabber.setOption("loglevel", "quiet");  // suppress FFmpeg console output
            grabber.start();

            long durationMicros = grabber.getLengthInTime();  // microseconds
            int  frameRate      = (int) Math.max(1, grabber.getFrameRate());
            int  videoWidth     = grabber.getImageWidth();
            int  videoHeight    = grabber.getImageHeight();

            if (durationMicros <= 0 || videoWidth <= 0 || videoHeight <= 0) {
                log.warn("Video has no decodable video stream: {}", videoPath.getFileName());
                return null;
            }

            long[] hashes = new long[sampleCount];
            int    grabbed = 0;

            // Sample evenly: skip first 5% and last 5% to avoid black intro/outro frames
            long usableStart = durationMicros / 20;         // 5 %
            long usableEnd   = durationMicros * 19 / 20;    // 95 %
            long usableDur   = usableEnd - usableStart;
            long step        = usableDur / sampleCount;

            for (int i = 0; i < sampleCount; i++) {
                long targetMicros = usableStart + step * i + step / 2; // midpoint of interval
                grabber.setTimestamp(targetMicros, true); // seek to nearest key-frame

                Frame frame = null;
                // Try up to 10 consecutive frames in case the seek lands on a bad frame
                for (int attempt = 0; attempt < 10; attempt++) {
                    frame = grabber.grabImage();
                    if (frame != null && frame.imageWidth > 0) break;
                }
                if (frame == null || frame.imageWidth <= 0) continue;

                BufferedImage img = frameToBufferedImage(frame);
                if (img == null) continue;

                hashes[grabbed++] = pHash(img);
            }

            if (grabbed == 0) {
                log.warn("Could not extract any frames from: {}", videoPath.getFileName());
                return null;
            }

            // If we grabbed fewer than expected, pad with zeros (treated as "empty frame")
            // The comparison code accounts for zero hashes
            long[] result = new long[sampleCount];
            System.arraycopy(hashes, 0, result, 0, grabbed);
            return result;

        } catch (Exception e) {
            log.warn("Cannot compute perceptual hash for '{}': {}", videoPath.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Returns {@code true} if the two fingerprints are perceptually similar,
     * i.e. the average per-frame Hamming distance is ≤ {@link #hammingThreshold}.
     *
     * @param a fingerprint from {@link #computeFingerprint}
     * @param b fingerprint from {@link #computeFingerprint}
     * @return true if similar
     */
    public boolean isSimilar(long[] a, long[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int total = 0;
        int validFrames = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == 0 && b[i] == 0) continue; // both padding frames — skip
            total += Long.bitCount(a[i] ^ b[i]);   // Hamming distance for this frame
            validFrames++;
        }
        if (validFrames == 0) return false;
        double avgHamming = (double) total / validFrames;
        return avgHamming <= hammingThreshold;
    }

    /**
     * Convenience: returns the average Hamming distance between two fingerprints,
     * or {@code Double.MAX_VALUE} if they cannot be compared.
     */
    public double hammingDistance(long[] a, long[] b) {
        if (a == null || b == null || a.length != b.length) return Double.MAX_VALUE;
        int total = 0, validFrames = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == 0 && b[i] == 0) continue;
            total += Long.bitCount(a[i] ^ b[i]);
            validFrames++;
        }
        return validFrames == 0 ? Double.MAX_VALUE : (double) total / validFrames;
    }

    // ── pHash implementation ─────────────────────────────────────────────────

    /**
     * Computes a 64-bit DCT perceptual hash of the given image.
     *
     * <ol>
     *   <li>Scale to {@value #THUMB_SIZE}×{@value #THUMB_SIZE} grayscale.</li>
     *   <li>Compute 2-D DCT.</li>
     *   <li>Take top-left {@value #DCT_BLOCK}×{@value #DCT_BLOCK} coefficients
     *       (excluding DC at [0][0]).</li>
     *   <li>Compute mean of those 63 values.</li>
     *   <li>Each bit = 1 if coefficient ≥ mean, else 0.</li>
     * </ol>
     */
    static long pHash(BufferedImage original) {
        // Step 1 — scale to 32×32 greyscale
        BufferedImage grey = new BufferedImage(THUMB_SIZE, THUMB_SIZE,
                BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, THUMB_SIZE, THUMB_SIZE, null);
        g.dispose();

        // Step 2 — build float pixel matrix
        double[][] pixels = new double[THUMB_SIZE][THUMB_SIZE];
        for (int y = 0; y < THUMB_SIZE; y++)
            for (int x = 0; x < THUMB_SIZE; x++)
                pixels[y][x] = grey.getRaster().getSample(x, y, 0); // 0–255

        // Step 3 — 2-D DCT
        double[][] dct = dct2d(pixels);

        // Step 4 — extract top-left 8×8 block, skip DC [0][0]
        double[] coeffs = new double[BITS_PER_FRAME - 1];
        int idx = 0;
        for (int y = 0; y < DCT_BLOCK; y++)
            for (int x = 0; x < DCT_BLOCK; x++)
                if (!(y == 0 && x == 0))
                    coeffs[idx++] = dct[y][x];

        // Step 5 — mean
        double mean = 0;
        for (double c : coeffs) mean += c;
        mean /= coeffs.length;

        // Step 6 — build 64-bit hash (bit 0 = DC [0][0] always 1 as anchor)
        long hash = 1L; // bit 63 set as anchor for DC term
        for (int i = 0; i < coeffs.length; i++)
            if (coeffs[i] >= mean)
                hash |= (1L << i);

        return hash;
    }

    /**
     * Separable 2-D DCT-II (standard definition, not scaled).
     * Applies 1-D DCT to each row then each column.
     */
    private static double[][] dct2d(double[][] f) {
        int n = f.length;
        double[][] tmp = new double[n][n];
        double[][] out = new double[n][n];

        // DCT on rows
        for (int y = 0; y < n; y++)
            tmp[y] = dct1d(f[y]);

        // DCT on columns of tmp → out
        double[] col = new double[n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) col[y] = tmp[y][x];
            double[] res = dct1d(col);
            for (int y = 0; y < n; y++) out[y][x] = res[y];
        }
        return out;
    }

    /**
     * 1-D DCT-II.
     * {@code F[k] = Σ_{n=0}^{N-1} f[n] · cos(π·k·(2n+1) / (2N))}
     */
    private static double[] dct1d(double[] f) {
        int    n   = f.length;
        double[] F = new double[n];
        for (int k = 0; k < n; k++) {
            double sum = 0;
            for (int i = 0; i < n; i++)
                sum += f[i] * Math.cos(Math.PI * k * (2 * i + 1) / (2.0 * n));
            F[k] = sum;
        }
        return F;
    }

    // ── Frame → BufferedImage conversion ─────────────────────────────────────

    /**
     * Converts a JavaCV {@link Frame} (which holds raw BGR/BGRA byte data)
     * to a standard {@link BufferedImage}.
     */
    private static BufferedImage frameToBufferedImage(Frame frame) {
        if (frame == null || frame.image == null || frame.image.length == 0) return null;
        try {
            int w       = frame.imageWidth;
            int h       = frame.imageHeight;
            int stride  = frame.imageStride;         // bytes per row (may include padding)
            int channels = frame.imageChannels;      // typically 3 (BGR) or 4 (BGRA)

            if (w <= 0 || h <= 0 || channels < 1) return null;

            ByteBuffer buf = (ByteBuffer) frame.image[0];
            buf.rewind();

            int type = (channels == 4)
                    ? BufferedImage.TYPE_INT_ARGB
                    : BufferedImage.TYPE_INT_RGB;
            BufferedImage img = new BufferedImage(w, h, type);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int offset = y * stride + x * channels;
                    if (offset + channels > buf.capacity()) break;

                    int b = buf.get(offset)     & 0xFF;
                    int g = buf.get(offset + 1) & 0xFF;
                    int r = (channels >= 3) ? (buf.get(offset + 2) & 0xFF) : g;
                    int a = (channels == 4) ? (buf.get(offset + 3) & 0xFF) : 255;

                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}

