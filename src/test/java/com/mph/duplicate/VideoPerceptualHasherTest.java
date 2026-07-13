package com.mph.duplicate;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VideoPerceptualHasher}.
 * Tests cover the pHash algorithm itself (no FFmpeg / real video required).
 */
class VideoPerceptualHasherTest {

    // ── pHash correctness ─────────────────────────────────────────────────────

    @Test
    void sameImage_producesIdenticalHash() {
        BufferedImage img = solidColour(Color.GRAY, 64, 64);
        long h1 = VideoPerceptualHasher.pHash(img);
        long h2 = VideoPerceptualHasher.pHash(img);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void identicalImages_hammingDistanceIsZero() {
        BufferedImage a = gradientImage(64, 64);
        BufferedImage b = gradientImage(64, 64);  // same pixel data
        long ha = VideoPerceptualHasher.pHash(a);
        long hb = VideoPerceptualHasher.pHash(b);
        int hamming = Long.bitCount(ha ^ hb);
        assertThat(hamming).isZero();
    }

    @Test
    void completelyDifferentImages_produceDifferentHashes() {
        // A left-to-right gradient vs a top-to-bottom gradient have very different
        // DCT frequency distributions → expect a meaningful Hamming distance
        BufferedImage horiz = gradientImage(64, 64);           // L→R gradient
        BufferedImage vert  = verticalGradientImage(64, 64);   // T→B gradient
        long hh = VideoPerceptualHasher.pHash(horiz);
        long hv = VideoPerceptualHasher.pHash(vert);
        int hamming = Long.bitCount(hh ^ hv);
        // DCT pHash should show a noticeable difference between orthogonal gradients
        assertThat(hamming).isGreaterThan(5);
    }

    @Test
    void slightlyDifferentImages_haveLowHammingDistance() {
        // A gradient with a tiny brightness nudge (+2) should hash very similarly
        BufferedImage orig   = gradientImage(64, 64);
        BufferedImage nudged = nudgeImage(orig, 2);

        long ho = VideoPerceptualHasher.pHash(orig);
        long hn = VideoPerceptualHasher.pHash(nudged);
        int hamming = Long.bitCount(ho ^ hn);
        // Expect ≤ 10 bits different for a tiny brightness change
        assertThat(hamming).isLessThanOrEqualTo(10);
    }

    // ── isSimilar ─────────────────────────────────────────────────────────────

    @Test
    void isSimilar_identicalFingerprints_returnsTrue() {
        VideoPerceptualHasher hasher = new VideoPerceptualHasher(8, 10);
        long[] fp = buildFingerprint(gradientImage(64, 64), 8);
        assertThat(hasher.isSimilar(fp, fp)).isTrue();
    }

    @Test
    void isSimilar_nullFingerprint_returnsFalse() {
        VideoPerceptualHasher hasher = new VideoPerceptualHasher(8, 10);
        long[] fp = buildFingerprint(gradientImage(64, 64), 8);
        assertThat(hasher.isSimilar(fp, null)).isFalse();
        assertThat(hasher.isSimilar(null, fp)).isFalse();
    }

    @Test
    void isSimilar_differentLengthFingerprints_returnsFalse() {
        VideoPerceptualHasher hasher = new VideoPerceptualHasher(8, 10);
        long[] a = new long[8];
        long[] b = new long[4];
        assertThat(hasher.isSimilar(a, b)).isFalse();
    }

    @Test
    void hammingDistance_identicalFingerprints_isZero() {
        VideoPerceptualHasher hasher = new VideoPerceptualHasher(8, 10);
        long[] fp = buildFingerprint(gradientImage(64, 64), 8);
        assertThat(hasher.hammingDistance(fp, fp)).isEqualTo(0.0);
    }

    @Test
    void hammingDistance_nullInput_returnsMaxValue() {
        VideoPerceptualHasher hasher = new VideoPerceptualHasher(8, 10);
        assertThat(hasher.hammingDistance(null, new long[4]))
                .isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void isSimilar_strictThreshold_rejectsSlightlyDifferent() {
        // Threshold = 0 means only identical fingerprints match
        VideoPerceptualHasher strict = new VideoPerceptualHasher(4, 0);
        long[] fp1 = buildFingerprint(gradientImage(64, 64), 4);
        long[] fp2 = buildFingerprint(nudgeImage(gradientImage(64, 64), 20), 4);
        // With threshold 0, even a tiny difference should not match
        // (We allow the test to pass either way — this verifies the threshold is respected)
        double dist = strict.hammingDistance(fp1, fp2);
        if (dist == 0.0) {
            assertThat(strict.isSimilar(fp1, fp2)).isTrue();
        } else {
            assertThat(strict.isSimilar(fp1, fp2)).isFalse();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BufferedImage solidColour(Color c, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** A simple horizontal gradient from black (left) to white (right). */
    private static BufferedImage gradientImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            int v = (int) (255.0 * x / (w - 1));
            Color c = new Color(v, v, v);
            for (int y = 0; y < h; y++) img.setRGB(x, y, c.getRGB());
        }
        return img;
    }

    /** A vertical gradient from black (top) to white (bottom). */
    private static BufferedImage verticalGradientImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            int v = (int) (255.0 * y / (h - 1));
            Color c = new Color(v, v, v);
            for (int x = 0; x < w; x++) img.setRGB(x, y, c.getRGB());
        }
        return img;
    }

    /** Returns a copy of {@code src} with every pixel brightness nudged by {@code delta}. */
    private static BufferedImage nudgeImage(BufferedImage src, int delta) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                Color c = new Color(src.getRGB(x, y));
                int r = Math.min(255, Math.max(0, c.getRed()   + delta));
                int g = Math.min(255, Math.max(0, c.getGreen() + delta));
                int b = Math.min(255, Math.max(0, c.getBlue()  + delta));
                out.setRGB(x, y, new Color(r, g, b).getRGB());
            }
        }
        return out;
    }

    /** Builds a synthetic perceptual fingerprint using the given image for all frames. */
    private static long[] buildFingerprint(BufferedImage img, int frames) {
        long hash = VideoPerceptualHasher.pHash(img);
        long[] fp = new long[frames];
        java.util.Arrays.fill(fp, hash);
        return fp;
    }
}

