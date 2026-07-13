package com.mph.duplicate;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Configuration for the Duplicate Image &amp; Video Finder.
 * All values have sensible defaults; override via the UI or programmatically.
 *
 * <h2>Tuning for 2 TB+ datasets</h2>
 * <ul>
 *   <li>{@code threadCount} – set to the number of physical CPU cores (not HT threads) for
 *       I/O-bound workloads on spinning disks; use all logical cores for NVMe/SSD arrays.</li>
 *   <li>{@code readBufferSize} – 64 KB is the sweet-spot for most file-systems.
 *       Increase to 256–512 KB for RAID or NAS over high-latency links.</li>
 *   <li>{@code partialHashBytes} – 8 KB filters out 95 %+ of non-duplicates cheaply.
 *       Decrease to 4 KB for very fast storage; increase to 64 KB for extra safety.</li>
 * </ul>
 */
@Getter
@Setter
public class DuplicateImageConfig {

    /** Number of parallel hashing threads. Default: available CPU cores. */
    private int threadCount = Runtime.getRuntime().availableProcessors();

    /** I/O buffer size per read operation (bytes). Default: 64 KB. */
    private int readBufferSize = 64 * 1024;

    /**
     * Number of bytes read from the start of each file for the fast pre-filter hash.
     * Default: 8 KB.
     */
    private int partialHashBytes = 8 * 1024;

    /** Directory where duplicate files will be moved. Created if absent. */
    private String duplicateOutputDir = "duplicates_found";

    /**
     * When {@code true} (default), duplicate files are physically moved to
     * {@link #duplicateOutputDir}.
     * <p>
     * Move behaviour: for each original file {@code abcd.jpeg} with N duplicates,
     * a sub-folder {@code <outputDir>/abcd/} is created and the duplicates are
     * renamed {@code abcd_1.jpeg}, {@code abcd_2.jpeg}, …, {@code abcd_N.jpeg}
     * before being placed inside that sub-folder.
     */
    private boolean moveDuplicates = true;

    /**
     * When {@code true}, duplicate files are permanently deleted.
     * The largest file in each group is kept; all others are removed.
     */
    private boolean deleteDuplicates = false;

    /**
     * When {@code true}, each duplicate in a group is renamed in-place using
     * the pattern {@code <originalBaseName>_N.<ext>} where N starts at 1.
     */
    private boolean renameDuplicates = false;

    /**
     * When {@code true}, an Excel report ({@code .xlsx}) is written after the scan.
     */
    private boolean generateReport = false;

    /**
     * Path for the Excel report file.
     * Default: {@code duplicate_report.xlsx} next to the first input directory.
     */
    private String reportPath = "duplicate_report.xlsx";

    /** Progress log interval (every N files). */
    private int progressLogInterval = 1_000;

    // ── Scan scope ────────────────────────────────────────────────────────────

    /** When {@code true} (default), image files are included in the scan. */
    private boolean scanImages = true;

    /** When {@code true} (default), video files are included in the scan. */
    private boolean scanVideos = true;

    // ── Perceptual hashing (videos) ───────────────────────────────────────────

    /**
     * When {@code true}, video files that survive the exact-hash phase are also
     * grouped by perceptual similarity using frame-sampled DCT pHash.
     * This catches re-encoded copies, bitrate-changed versions, and
     * container-format conversions of the same video content.
     *
     * <p>Requires JavaCV / FFmpeg on the classpath (included by default).
     * Disabled by default because it is significantly slower than exact hashing.
     */
    private boolean perceptualVideoMatching = false;

    /**
     * Number of frames sampled from each video for the perceptual fingerprint.
     * Higher values = more accurate but slower. Default: 16.
     * Minimum: 4.
     */
    private int videoSampleFrames = 16;

    /**
     * Maximum average per-frame Hamming distance (0–64) for two video
     * fingerprints to be considered "similar".
     * <ul>
     *   <li>0  = bit-perfect match (same as exact hashing)</li>
     *   <li>5  = strict   – only near-identical encodes match</li>
     *   <li>10 = moderate – tolerates mild re-encoding (default)</li>
     *   <li>15 = lenient  – catches more variations but risks false positives</li>
     * </ul>
     */
    private int perceptualHammingThreshold = 10;

    // ── Extension lists ───────────────────────────────────────────────────────

    /**
     * Image file extensions (lowercase, no leading dot).
     * Covers all major raster, vector, raw and modern formats.
     */
    private String[] imageExtensions = {
        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif",
        "webp", "heic", "heif", "svg", "ico",
        "raw", "cr2", "cr3", "nef", "arw", "dng", "orf",
        "rw2", "pef", "srw", "raf",
        "psd", "psb", "ai", "eps",
        "avif", "jxl", "jp2", "j2k"
    };

    /**
     * Video file extensions (lowercase, no leading dot).
     * Covers all major container and codec formats.
     */
    private String[] videoExtensions = {
        // Common containers
        "mp4", "m4v", "mov", "avi", "mkv", "wmv", "flv", "f4v", "webm", "ogv", "ogg",
        // MPEG family
        "mpeg", "mpg", "mpe", "m2v", "ts", "mts", "m2ts",
        // Mobile / Apple
        "3gp", "3g2",
        // Professional / broadcast
        "mxf", "dv", "vob", "mod", "tod",
        // High-efficiency / modern
        "hevc", "h264", "h265",
        // Adobe / Windows
        "asf", "rm", "rmvb", "divx", "xvid",
        // Matroska / VP
        "vp8", "vp9",
        // Raw / ProRes / professional
        "r3d", "braw", "ari",
        // Misc
        "amv", "yuv", "m4p"
    };

    /**
     * Returns the effective combined set of extensions to scan, based on
     * {@link #scanImages} and {@link #scanVideos} flags.
     * Falls back to image-only if both flags are {@code false}.
     */
    public String[] getEffectiveSupportedExtensions() {
        Set<String> exts = new LinkedHashSet<>();
        if (scanImages) exts.addAll(Arrays.asList(imageExtensions));
        if (scanVideos) exts.addAll(Arrays.asList(videoExtensions));
        if (exts.isEmpty()) exts.addAll(Arrays.asList(imageExtensions)); // safe fallback
        return exts.toArray(new String[0]);
    }

    /**
     * @deprecated Use {@link #getEffectiveSupportedExtensions()} instead.
     *             Kept for backward compatibility — returns the image-extension array.
     */
    @Deprecated
    public String[] getSupportedExtensions() {
        return getEffectiveSupportedExtensions();
    }
}
