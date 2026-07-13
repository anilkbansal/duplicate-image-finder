package com.mph.duplicate;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Core engine: scans directories for duplicate images and handles them
 * according to the flags in {@link DuplicateImageConfig}.
 *
 * <h2>4-Phase Algorithm</h2>
 * <pre>
 * Phase 1 – COLLECT   : walk dirs, gather image Paths (no I/O on content)
 * Phase 2 – PARTIAL   : SHA-256(size + first 8 KB) pre-filter (~95 % eliminated)
 * Phase 3 – FULL HASH : SHA-256(entire file) for survivors only
 * Phase 4 – ACTION    : move / rename / report as configured
 * </pre>
 *
 * <p>All phases show an ANSI progress bar in the terminal and log via SLF4J.
 */
public class DuplicateImageFinder {

    private static final Logger log = LoggerFactory.getLogger(DuplicateImageFinder.class);

    private final DuplicateImageConfig config;

    private final ConcurrentHashMap<String, List<Path>> partialKeyMap =
            new ConcurrentHashMap<>(65_536, 0.75f, 64);
    private final ConcurrentHashMap<String, List<Path>> fullHashMap =
            new ConcurrentHashMap<>(8_192, 0.75f, 64);

    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong duplicatesFound = new AtomicLong(0);
    private final AtomicLong movedCount     = new AtomicLong(0);
    private final AtomicLong renamedCount   = new AtomicLong(0);
    private final AtomicLong deletedCount   = new AtomicLong(0);
    private final AtomicLong errorCount     = new AtomicLong(0);

    public DuplicateImageFinder(DuplicateImageConfig config) {
        this.config = config;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public ScanReport run(List<Path> inputDirs) throws IOException, InterruptedException {
        Instant start = Instant.now();
        validateDirs(inputDirs);

        Path outputDir  = resolveOutputDir(inputDirs);
        Path reportFile = resolveReportPath(inputDirs);
        printBanner(inputDirs, outputDir, reportFile);

        // ── Phase 1 – Collect ─────────────────────────────────────────────────
        log.info("Phase 1 – Collecting image file paths from {} director{}...",
                inputDirs.size(), inputDirs.size() == 1 ? "y" : "ies");
        Instant t1 = Instant.now();
        List<Path> allImages = collectImagePaths(inputDirs);
        log.info("Phase 1 – Done in {} – {} image files found.", elapsed(t1), allImages.size());

        if (allImages.isEmpty()) {
            log.info("No image files found. Nothing to do.");
            return buildReport(start, outputDir, reportFile, 0, Collections.emptyMap());
        }

        // ── Phase 2 – Partial hash pre-filter ────────────────────────────────
        log.info("Phase 2 – Computing partial hashes (pre-filter) for {} files...",
                allImages.size());
        Instant t2 = Instant.now();
        computePartialHashes(allImages);
        allImages.clear();

        List<List<Path>> candidates = partialKeyMap.values().stream()
                .filter(g -> g.size() > 1).collect(Collectors.toList());
        long candidateCount = candidates.stream().mapToLong(List::size).sum();
        partialKeyMap.clear();
        log.info("Phase 2 – Done in {} – {} candidate files passed the pre-filter.",
                elapsed(t2), candidateCount);

        if (candidateCount == 0) {
            log.info("No duplicates found.");
            return buildReport(start, outputDir, reportFile, 0, Collections.emptyMap());
        }

        // ── Phase 3 – Full hash confirmation ─────────────────────────────────
        log.info("Phase 3 – Computing full SHA-256 hashes for {} candidates...",
                candidateCount);
        Instant t3 = Instant.now();
        computeFullHashes(candidates, candidateCount);
        candidates.clear();

        Map<Path, List<Path>> duplicateGroups = buildDuplicateGroups();
        long confirmedGroups = duplicateGroups.size();
        long confirmedDups   = duplicateGroups.values().stream().mapToLong(List::size).sum();
        duplicatesFound.set(confirmedDups);
        log.info("Phase 3 – Done in {} – {} exact duplicate groups, {} duplicate files.",
                elapsed(t3), confirmedGroups, confirmedDups);

        // ── Phase 3b – Perceptual video matching (optional) ───────────────────
        if (config.isPerceptualVideoMatching() && config.isScanVideos()) {
            log.info("Phase 3b – Perceptual video matching (frame sampling + pHash)…");
            Instant t3b = Instant.now();
            Set<String> videoExts = new HashSet<>(Arrays.asList(config.getVideoExtensions()));
            // allImages is already cleared; collect video files from the known groups instead
            List<Path> videoFiles = collectVideoFilesFromGroups(duplicateGroups, videoExts);

            Map<Path, List<Path>> perceptualGroups =
                    buildPerceptualVideoGroups(videoFiles, duplicateGroups);
            if (!perceptualGroups.isEmpty()) {
                for (Map.Entry<Path, List<Path>> e : perceptualGroups.entrySet()) {
                    duplicateGroups.merge(e.getKey(), e.getValue(), (existing, newList) -> {
                        existing.addAll(newList);
                        return existing;
                    });
                }
                long percDups = perceptualGroups.values().stream().mapToLong(List::size).sum();
                duplicatesFound.addAndGet(percDups);
                log.info("Phase 3b – Done in {} – {} additional perceptual video duplicate groups, {} files.",
                        elapsed(t3b), perceptualGroups.size(), percDups);
            } else {
                log.info("Phase 3b – Done in {} – no additional perceptual video duplicates found.",
                        elapsed(t3b));
            }
            confirmedGroups = duplicateGroups.size();
            confirmedDups   = duplicateGroups.values().stream().mapToLong(List::size).sum();
        }

        // ── Phase 4 – Action ──────────────────────────────────────────────────
        boolean doMove   = config.isMoveDuplicates();
        boolean doRename = config.isRenameDuplicates();
        boolean doDelete = config.isDeleteDuplicates();
        boolean doReport = config.isGenerateReport();

        if (!doMove && !doRename && !doDelete && !doReport) {
            log.info("Phase 4 – No action flags active. Scan complete – no files modified.");
        } else {
            if (doMove) {
                log.info("Phase 4 – Moving {} duplicate files to: {}", confirmedDups, outputDir);
                Instant t4m = Instant.now();
                Files.createDirectories(outputDir);
                moveDuplicates(duplicateGroups, outputDir, confirmedDups);
                log.info("Phase 4 – Move done in {} – moved {} files, {} errors.",
                        elapsed(t4m), movedCount.get(), errorCount.get());
            }
            if (doRename) {
                log.info("Phase 4 – Renaming {} duplicate files in-place (counter suffix)…",
                        confirmedDups);
                Instant t4r = Instant.now();
                renameDuplicates(duplicateGroups, confirmedDups);
                log.info("Phase 4 – Rename done in {} – renamed {} files, {} errors.",
                        elapsed(t4r), renamedCount.get(), errorCount.get());
            }
            if (doDelete) {
                log.info("Phase 4 – Deleting {} duplicate files…", confirmedDups);
                Instant t4d = Instant.now();
                deleteDuplicates(duplicateGroups, confirmedDups);
                log.info("Phase 4 – Delete done in {} – deleted {} files, {} errors.",
                        elapsed(t4d), deletedCount.get(), errorCount.get());
            }
            if (doReport) {
                log.info("Phase 4 – Generating Excel report → {}", reportFile);
                Instant t4x = Instant.now();
                ExcelReportWriter.write(duplicateGroups, reportFile);
                log.info("Phase 4 – Report done in {}.", elapsed(t4x));
            }
        }

        return buildReport(start, outputDir, reportFile, confirmedGroups, duplicateGroups);
    }

    // ── Phase 1 – Collect ─────────────────────────────────────────────────────

    private List<Path> collectImagePaths(List<Path> inputDirs) throws InterruptedException {
        Set<String> extSet = new HashSet<>(Arrays.asList(config.getEffectiveSupportedExtensions()));
        ExecutorService pool = newPool();
        List<Future<List<Path>>> futures = new ArrayList<>();

        // Indeterminate spinner while walking (we don't know total file count yet)
        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Scanning dirs")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(-1)          // indeterminate
                .setUpdateIntervalMillis(200)
                .build()) {

            for (Path dir : inputDirs) {
                futures.add(pool.submit(() -> {
                    ImageFileCollector collector = new ImageFileCollector(extSet, pb);
                    Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                            Integer.MAX_VALUE, collector);
                    return collector.getCollectedFiles();
                }));
            }
            awaitPool(pool);
        }

        List<Path> merged = new ArrayList<>();
        for (Future<List<Path>> f : futures) {
            try { merged.addAll(f.get()); }
            catch (ExecutionException ex) {
                log.error("Phase 1 – Error walking a directory: {}", ex.getCause().getMessage());
                errorCount.incrementAndGet();
            }
        }
        return merged;
    }

    // ── Phase 2 – Partial Hash ────────────────────────────────────────────────

    private void computePartialHashes(List<Path> files) throws InterruptedException {
        int total = files.size();
        int batchSize = Math.max(500, total / (config.getThreadCount() * 8));
        ExecutorService pool = newPool();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Partial hash ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total)
                .setUpdateIntervalMillis(200)
                .showSpeed()
                .build()) {

            for (int i = 0; i < total; i += batchSize) {
                List<Path> batch = files.subList(i, Math.min(i + batchSize, total));
                for (Path p : batch) {
                    pool.submit(() -> {
                        try {
                            String key = FileHasher.computePartialKey(
                                    p, config.getPartialHashBytes(), config.getReadBufferSize());
                            partialKeyMap
                                    .computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>(4)))
                                    .add(p);
                            processedCount.incrementAndGet();
                            pb.step();
                        } catch (IOException ex) {
                            log.warn("Phase 2 – Cannot hash '{}': {}", p, ex.getMessage());
                            errorCount.incrementAndGet();
                            pb.step();
                        }
                    });
                }
            }
            awaitPool(pool);
        }
    }

    // ── Phase 3 – Full Hash ───────────────────────────────────────────────────

    private void computeFullHashes(List<List<Path>> groups, long total) throws InterruptedException {
        ExecutorService pool = newPool();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Full SHA-256 ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total)
                .setUpdateIntervalMillis(200)
                .showSpeed()
                .build()) {

            for (List<Path> group : groups) {
                for (Path p : group) {
                    pool.submit(() -> {
                        try {
                            String hash = FileHasher.computeFullHash(p, config.getReadBufferSize());
                            fullHashMap
                                    .computeIfAbsent(hash, k -> Collections.synchronizedList(new ArrayList<>(4)))
                                    .add(p);
                            pb.step();
                        } catch (IOException ex) {
                            log.warn("Phase 3 – Cannot hash '{}': {}", p, ex.getMessage());
                            errorCount.incrementAndGet();
                            pb.step();
                        }
                    });
                }
            }
            awaitPool(pool);
        }
    }

    /**
     * Builds duplicate groups where the key is the <b>largest file</b> by size
     * (i.e. the file that will be kept), and the values are all smaller/other
     * copies that will be acted upon (moved, renamed, or deleted).
     *
     * <p>Tie-breaking (same size): the lexicographically first path is kept.
     */
    private Map<Path, List<Path>> buildDuplicateGroups() {
        Map<Path, List<Path>> result = new LinkedHashMap<>();
        for (List<Path> group : fullHashMap.values()) {
            if (group.size() < 2) continue;
            List<Path> sorted = new ArrayList<>(group);
            // Sort: largest first; ties broken by lex path (smallest path wins = kept)
            sorted.sort(Comparator
                    .comparingLong((Path p) -> fileSize(p)).reversed()
                    .thenComparing(Path::toString));
            result.put(sorted.get(0), new ArrayList<>(sorted.subList(1, sorted.size())));
        }
        return result;
    }

    /** Returns the file size in bytes, or 0 if the size cannot be determined. */
    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    // ── Phase 3b – Perceptual video grouping ──────────────────────────────────

    /**
     * Collects all video files that appear as either keys or values in the
     * existing exact-hash duplicate groups (used when allImages was already cleared).
     */
    private static List<Path> collectVideoFilesFromGroups(
            Map<Path, List<Path>> groups, Set<String> videoExts) {
        List<Path> result = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> e : groups.entrySet()) {
            if (isVideoFile(e.getKey(), videoExts)) result.add(e.getKey());
            for (Path dup : e.getValue())
                if (isVideoFile(dup, videoExts)) result.add(dup);
        }
        return result;
    }

    private static boolean isVideoFile(Path p, Set<String> videoExts) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && videoExts.contains(name.substring(dot + 1));
    }

    /**
     * Runs perceptual fingerprinting on all video files that are NOT already in
     * an exact-hash group, then clusters them by pHash similarity.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Collect every video file that is NOT already confirmed as a duplicate
     *       (i.e. not in the values of {@code existingGroups}).</li>
     *   <li>Compute a {@link VideoPerceptualHasher} fingerprint for each.</li>
     *   <li>Cluster using single-linkage: any file within Hamming threshold of
     *       any member of an existing cluster joins that cluster.</li>
     *   <li>Return groups with ≥ 2 members as new duplicate groups.</li>
     * </ol>
     */
    private Map<Path, List<Path>> buildPerceptualVideoGroups(
            List<Path> videoFiles,
            Map<Path, List<Path>> existingGroups) throws InterruptedException {

        // Build the set of files already identified as duplicates (to skip them)
        Set<Path> alreadyDuplicates = new HashSet<>();
        for (List<Path> dups : existingGroups.values())
            alreadyDuplicates.addAll(dups);

        // Collect candidates: video files that are NOT already confirmed duplicates
        List<Path> candidates = videoFiles.stream()
                .filter(p -> !alreadyDuplicates.contains(p))
                .distinct()
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return Collections.emptyMap();

        log.info("Phase 3b – Computing perceptual fingerprints for {} video file(s)…",
                candidates.size());

        VideoPerceptualHasher hasher = new VideoPerceptualHasher(
                config.getVideoSampleFrames(),
                config.getPerceptualHammingThreshold());

        // Compute fingerprints in parallel
        ConcurrentHashMap<Path, long[]> fingerprints = new ConcurrentHashMap<>();
        ExecutorService pool = newPool();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("PHash video  ")
                .setStyle(me.tongfei.progressbar.ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(candidates.size())
                .setUpdateIntervalMillis(300)
                .showSpeed()
                .build()) {

            for (Path p : candidates) {
                pool.submit(() -> {
                    long[] fp = hasher.computeFingerprint(p);
                    if (fp != null) fingerprints.put(p, fp);
                    pb.step();
                });
            }
            awaitPool(pool);
        }

        if (fingerprints.isEmpty()) return Collections.emptyMap();
        log.info("Phase 3b – Fingerprinted {} video(s), clustering by similarity…",
                fingerprints.size());

        // Single-linkage clustering (O(n²) — acceptable for typical video counts)
        List<Path> fpKeys     = new ArrayList<>(fingerprints.keySet());
        int        n          = fpKeys.size();
        int[]      cluster    = new int[n];
        Arrays.fill(cluster, -1);
        int        nextCluster = 0;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long[] fi = fingerprints.get(fpKeys.get(i));
                long[] fj = fingerprints.get(fpKeys.get(j));
                if (hasher.isSimilar(fi, fj)) {
                    // Merge clusters
                    if (cluster[i] == -1 && cluster[j] == -1) {
                        cluster[i] = cluster[j] = nextCluster++;
                    } else if (cluster[i] == -1) {
                        cluster[i] = cluster[j];
                    } else if (cluster[j] == -1) {
                        cluster[j] = cluster[i];
                    } else {
                        // Both already in a cluster — merge the smaller into larger
                        int from = cluster[j], to = cluster[i];
                        for (int k = 0; k < n; k++)
                            if (cluster[k] == from) cluster[k] = to;
                    }
                }
            }
        }

        // Build result map: for each cluster with ≥2 members,
        // keep the largest file and treat the rest as duplicates
        Map<Integer, List<Path>> clusterMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (cluster[i] < 0) continue;
            clusterMap.computeIfAbsent(cluster[i], k -> new ArrayList<>())
                      .add(fpKeys.get(i));
        }

        Map<Path, List<Path>> result = new LinkedHashMap<>();
        for (List<Path> group : clusterMap.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparingLong((Path p) -> fileSize(p)).reversed()
                                 .thenComparing(Path::toString));
            result.put(group.get(0), new ArrayList<>(group.subList(1, group.size())));
            log.info("Phase 3b – Perceptual group: keep '{}', duplicates: {}",
                    group.get(0).getFileName(),
                    group.subList(1, group.size()).stream()
                         .map(p -> p.getFileName().toString())
                         .collect(Collectors.joining(", ")));
        }
        return result;
    }

    /**
     * For each original file {@code abcd.jpeg} with N duplicates:
     * <ol>
     *   <li>Creates sub-folder {@code <outputDir>/abcd/}</li>
     *   <li>Renames each duplicate to {@code abcd_1.jpeg}, {@code abcd_2.jpeg}, …</li>
     *   <li>Moves the renamed duplicate into the sub-folder</li>
     * </ol>
     */
    private void moveDuplicates(Map<Path, List<Path>> groups, Path outputDir, long total)
            throws InterruptedException {
        ExecutorService pool = newPool();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Moving files ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total)
                .setUpdateIntervalMillis(200)
                .showSpeed()
                .build()) {

            for (Map.Entry<Path, List<Path>> entry : groups.entrySet()) {
                Path original = entry.getKey();
                List<Path> dups = entry.getValue();

                // Derive base name and extension from the original file name
                String origFileName = original.getFileName().toString();
                int dot = origFileName.lastIndexOf('.');
                String baseName = (dot < 0) ? origFileName : origFileName.substring(0, dot);
                String ext      = (dot < 0) ? ""           : origFileName.substring(dot); // ".jpeg"

                // Sub-folder: <outputDir>/<baseName>/
                Path subFolder = outputDir.resolve(baseName);

                // Counter starts at 1 for this group
                int[] counter = {1};
                for (Path dup : dups) {
                    final int idx = counter[0]++;
                    final Path destDir = subFolder;
                    final String targetName = baseName + "_" + idx + ext;
                    pool.submit(() -> {
                        try {
                            Files.createDirectories(destDir);
                            Path target = destDir.resolve(targetName);
                            try { Files.move(dup, target, StandardCopyOption.ATOMIC_MOVE); }
                            catch (AtomicMoveNotSupportedException e) {
                                Files.move(dup, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                            movedCount.incrementAndGet();
                        } catch (IOException ex) {
                            log.error("Phase 4 – FAILED to move '{}': {}", dup, ex.getMessage());
                            errorCount.incrementAndGet();
                        }
                        pb.step();
                    });
                }
            }
            awaitPool(pool);
        }
    }

    // ── Phase 4b – Rename in-place ────────────────────────────────────────────

    /**
     * Renames each duplicate in a group using the original file's base name plus
     * a counter starting at 1.  For example, for original {@code photo.jpg}:
     * duplicates become {@code photo_1.jpg}, {@code photo_2.jpg}, etc.
     */
    private void renameDuplicates(Map<Path, List<Path>> groups, long total) throws InterruptedException {
        ExecutorService pool = newPool();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Renaming     ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total)
                .setUpdateIntervalMillis(200)
                .showSpeed()
                .build()) {

            for (Map.Entry<Path, List<Path>> entry : groups.entrySet()) {
                Path original = entry.getKey();
                List<Path> dups = entry.getValue();

                String origFileName = original.getFileName().toString();
                int dot = origFileName.lastIndexOf('.');
                String baseName = (dot < 0) ? origFileName : origFileName.substring(0, dot);
                String ext      = (dot < 0) ? ""           : origFileName.substring(dot);

                int[] counter = {1};
                for (Path dup : dups) {
                    final int idx = counter[0]++;
                    pool.submit(() -> {
                        if (!Files.exists(dup)) { pb.step(); return; }
                        Path parent = dup.getParent();
                        String newName = baseName + "_" + idx + ext;
                        Path target = parent.resolve(newName);
                        // Avoid collision: if file already exists with that name append extra counter
                        int extra = 1;
                        while (Files.exists(target)) {
                            target = parent.resolve(baseName + "_" + idx + "_" + extra++ + ext);
                        }
                        try {
                            try { Files.move(dup, target, StandardCopyOption.ATOMIC_MOVE); }
                            catch (AtomicMoveNotSupportedException e) {
                                Files.move(dup, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                            renamedCount.incrementAndGet();
                        } catch (IOException ex) {
                            log.error("Phase 4 – FAILED to rename '{}': {}", dup, ex.getMessage());
                            errorCount.incrementAndGet();
                        }
                        pb.step();
                    });
                }
            }
            awaitPool(pool);
        }
    }

    // ── Phase 4c – Delete ─────────────────────────────────────────────────────

    private void deleteDuplicates(Map<Path, List<Path>> groups, long total)
            throws InterruptedException {
        ExecutorService pool = newPool();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Deleting     ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total)
                .setUpdateIntervalMillis(200)
                .showSpeed()
                .build()) {

            for (List<Path> dups : groups.values()) {
                for (Path dup : dups) {
                    pool.submit(() -> {
                        try {
                            Files.deleteIfExists(dup);
                            deletedCount.incrementAndGet();
                            log.info("Phase 4 – Deleted '{}'", dup);
                        } catch (IOException ex) {
                            log.error("Phase 4 – FAILED to delete '{}': {}", dup, ex.getMessage());
                            errorCount.incrementAndGet();
                        }
                        pb.step();
                    });
                }
            }
            awaitPool(pool);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Inserts {@code _label} before the file extension. */
    static String insertSuffix(String fileName, String label) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName + "_" + label
                       : fileName.substring(0, dot) + "_" + label + fileName.substring(dot);
    }

    private ExecutorService newPool() {
        return Executors.newFixedThreadPool(config.getThreadCount(), r -> {
            Thread t = new Thread(r, "dup-finder");
            t.setDaemon(true);
            return t;
        });
    }

    private static void awaitPool(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
            pool.shutdownNow();
            throw new InterruptedException("Processing timed out after 24 hours");
        }
    }

    private static void validateDirs(List<Path> dirs) throws IOException {
        for (Path d : dirs) {
            if (!Files.exists(d))      throw new IOException("Directory not found: " + d);
            if (!Files.isDirectory(d)) throw new IOException("Not a directory: " + d);
            if (!Files.isReadable(d))  throw new IOException("Directory not readable: " + d);
        }
    }

    private Path resolveOutputDir(List<Path> inputDirs) {
        Path raw = Paths.get(config.getDuplicateOutputDir());
        return raw.isAbsolute() ? raw : inputDirs.get(0).toAbsolutePath().getParent().resolve(raw);
    }

    private Path resolveReportPath(List<Path> inputDirs) {
        Path raw = Paths.get(config.getReportPath());
        return raw.isAbsolute() ? raw : inputDirs.get(0).toAbsolutePath().getParent().resolve(raw);
    }

    private static String elapsed(Instant since) {
        Duration d = Duration.between(since, Instant.now());
        return d.toMinutes() > 0
                ? String.format("%dm %ds", d.toMinutes(), d.toSecondsPart())
                : String.format("%ds", d.toSecondsPart());
    }

    private void printBanner(List<Path> inputDirs, Path outputDir, Path reportFile) {
        String dirs = inputDirs.size() + " director" + (inputDirs.size() == 1 ? "y" : "ies");
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║        Duplicate Image Finder  –  2 TB Edition       ║");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  Input dirs   : {}", String.format("%-36s║", dirs));
        log.info("║  Move dups    : {}", String.format("%-36s║", config.isMoveDuplicates()   ? "YES → " + shorten(outputDir.toString(), 30) : "NO"));
        log.info("║  Rename dups  : {}", String.format("%-36s║", config.isRenameDuplicates() ? "YES (originalName_N.ext)"                    : "NO"));
        log.info("║  Delete dups  : {}", String.format("%-36s║", config.isDeleteDuplicates() ? "YES (permanent delete)"                      : "NO"));
        log.info("║  Excel report : {}", String.format("%-36s║", config.isGenerateReport()   ? shorten(reportFile.toString(), 36)            : "NO"));
        log.info("║  Threads      : {}", String.format("%-36s║", config.getThreadCount()));
        log.info("║  Buffer       : {}", String.format("%-36s║", config.getReadBufferSize() / 1024 + " KB"));
        log.info("║  Partial hash : {}", String.format("%-36s║", config.getPartialHashBytes() / 1024 + " KB window"));
        log.info("╚══════════════════════════════════════════════════════╝");
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - (max - 1));
    }

    private ScanReport buildReport(Instant start, Path outputDir, Path reportFile,
                                   long groups, Map<Path, List<Path>> dupGroups) {
        return new ScanReport(
                Duration.between(start, Instant.now()),
                processedCount.get(), duplicatesFound.get(), groups,
                movedCount.get(), renamedCount.get(), deletedCount.get(), errorCount.get(),
                outputDir, reportFile, dupGroups);
    }

    // ── ScanReport ────────────────────────────────────────────────────────────

    public static final class ScanReport {
        public final Duration totalTime;
        public final long     filesScanned;
        public final long     duplicatesFound;
        public final long     duplicateGroups;
        public final long     filesMoved;
        public final long     filesRenamed;
        public final long     filesDeleted;
        public final long     errors;
        public final Path     outputDir;
        public final Path     reportFile;
        public final Map<Path, List<Path>> groups;

        ScanReport(Duration totalTime, long filesScanned, long duplicatesFound,
                   long duplicateGroups, long filesMoved, long filesRenamed,
                   long filesDeleted, long errors, Path outputDir, Path reportFile,
                   Map<Path, List<Path>> groups) {
            this.totalTime       = totalTime;
            this.filesScanned    = filesScanned;
            this.duplicatesFound = duplicatesFound;
            this.duplicateGroups = duplicateGroups;
            this.filesMoved      = filesMoved;
            this.filesRenamed    = filesRenamed;
            this.filesDeleted    = filesDeleted;
            this.errors          = errors;
            this.outputDir       = outputDir;
            this.reportFile      = reportFile;
            this.groups          = Collections.unmodifiableMap(groups);
        }

        @Override
        public String toString() {
            String t = totalTime.toMinutes() > 0
                    ? String.format("%dm %ds", totalTime.toMinutes(), totalTime.toSecondsPart())
                    : String.format("%ds", totalTime.toSecondsPart());
            return String.format("""
                    ╔══════════════════════════════════════════════════════╗
                    ║              SCAN COMPLETE — REPORT                  ║
                    ╠══════════════════════════════════════════════════════╣
                    ║  Total time        : %-32s║
                    ║  Files scanned     : %-32s║
                    ║  Duplicate groups  : %-32s║
                    ║  Duplicates found  : %-32s║
                    ║  Files moved       : %-32s║
                    ║  Files renamed     : %-32s║
                    ║  Files deleted     : %-32s║
                    ║  Errors            : %-32s║
                    ╚══════════════════════════════════════════════════════╝""",
                    t,
                    String.format("%,d", filesScanned),
                    String.format("%,d", duplicateGroups),
                    String.format("%,d", duplicatesFound),
                    String.format("%,d", filesMoved),
                    String.format("%,d", filesRenamed),
                    String.format("%,d", filesDeleted),
                    String.format("%,d", errors));
        }
    }
}

