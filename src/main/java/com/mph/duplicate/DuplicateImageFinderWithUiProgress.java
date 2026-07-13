package com.mph.duplicate;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Subclass of {@link DuplicateImageFinder} that drives both the terminal
 * progress bars (for CLI) and the Swing progress bars in
 * {@link DuplicateImageFinderUI} simultaneously.
 *
 * <p>All Swing updates are posted via {@link SwingUtilities#invokeLater} so
 * this class is safe to call from any thread.
 */
public class DuplicateImageFinderWithUiProgress extends DuplicateImageFinder {

    private static final Logger log = LoggerFactory.getLogger(DuplicateImageFinderWithUiProgress.class);

    private final DuplicateImageFinderUI ui;
    private final DuplicateImageConfig   config;

    // Shared counters exposed to inner lambdas
    private final ConcurrentHashMap<String, List<Path>> partialKeyMap2 =
            new ConcurrentHashMap<>(65_536, 0.75f, 64);
    private final ConcurrentHashMap<String, List<Path>> fullHashMap2 =
            new ConcurrentHashMap<>(8_192, 0.75f, 64);

    private final AtomicLong processedCount2 = new AtomicLong(0);
    private final AtomicLong duplicatesFound2 = new AtomicLong(0);
    private final AtomicLong movedCount2     = new AtomicLong(0);
    private final AtomicLong renamedCount2   = new AtomicLong(0);
    private final AtomicLong deletedCount2   = new AtomicLong(0);
    private final AtomicLong errorCount2     = new AtomicLong(0);

    public DuplicateImageFinderWithUiProgress(DuplicateImageConfig config,
                                               DuplicateImageFinderUI ui) {
        super(config);
        this.config = config;
        this.ui     = ui;
    }

    @Override
    public ScanReport run(List<Path> inputDirs) throws IOException, InterruptedException {
        Instant start = Instant.now();
        validateDirsUI(inputDirs);

        Path outputDir  = resolveOutputDirUI(inputDirs);
        Path reportFile = resolveReportPathUI(inputDirs);

        // ── Phase 1 – Collect ─────────────────────────────────────────────────
        ui.setPhase("Phase 1 – Collecting image file paths…");
        ui.initBar(ui.collectBar(), -1);   // indeterminate
        log.info("Phase 1 – Collecting image file paths from {} director{}…",
                inputDirs.size(), inputDirs.size() == 1 ? "y" : "ies");

        Instant t1 = Instant.now();
        List<Path> allImages = collectImagePathsUI(inputDirs);
        ui.doneBar(ui.collectBar());
        log.info("Phase 1 – Done in {} – {} image files found.", elapsed(t1), allImages.size());

        if (allImages.isEmpty()) {
            log.info("No image files found. Nothing to do.");
            ui.setPhase("No image files found.");
            return buildReportUI(start, outputDir, reportFile, 0, Collections.emptyMap());
        }

        // ── Phase 2 – Partial hash pre-filter ────────────────────────────────
        ui.setPhase("Phase 2 – Partial hash pre-filter…");
        ui.initBar(ui.partialBar(), allImages.size());
        log.info("Phase 2 – Computing partial hashes for {} files…", allImages.size());

        Instant t2 = Instant.now();
        computePartialHashesUI(allImages);
        allImages.clear();

        List<List<Path>> candidates = partialKeyMap2.values().stream()
                .filter(g -> g.size() > 1).collect(Collectors.toList());
        long candidateCount = candidates.stream().mapToLong(List::size).sum();
        partialKeyMap2.clear();
        ui.doneBar(ui.partialBar());
        log.info("Phase 2 – Done in {} – {} candidate files passed the pre-filter.",
                elapsed(t2), candidateCount);

        if (candidateCount == 0) {
            log.info("No duplicates found.");
            ui.setPhase("No duplicates found.");
            return buildReportUI(start, outputDir, reportFile, 0, Collections.emptyMap());
        }

        // ── Phase 3 – Full hash confirmation ─────────────────────────────────
        ui.setPhase("Phase 3 – Full SHA-256 hash confirmation…");
        ui.initBar(ui.fullBar(), candidateCount);
        log.info("Phase 3 – Computing full SHA-256 for {} candidates…", candidateCount);

        Instant t3 = Instant.now();
        computeFullHashesUI(candidates, candidateCount);
        candidates.clear();

        Map<Path, List<Path>> duplicateGroups = buildDuplicateGroupsUI();
        long confirmedGroups = duplicateGroups.size();
        long confirmedDups   = duplicateGroups.values().stream().mapToLong(List::size).sum();
        duplicatesFound2.set(confirmedDups);
        ui.doneBar(ui.fullBar());
        log.info("Phase 3 – Done in {} – {} exact duplicate groups, {} duplicate files.",
                elapsed(t3), confirmedGroups, confirmedDups);

        // ── Phase 3b – Perceptual video matching (optional) ───────────────────
        if (config.isPerceptualVideoMatching() && config.isScanVideos()) {
            ui.setPhase("Phase 3b – Perceptual video fingerprinting…");
            log.info("Phase 3b – Perceptual video matching (frame sampling + pHash)…");
            Instant t3b = Instant.now();

            Set<String> videoExts = new HashSet<>(Arrays.asList(config.getVideoExtensions()));
            List<Path> videoFiles = collectVideoFilesFromGroupsUI(duplicateGroups, videoExts);

            Map<Path, List<Path>> perceptualGroups =
                    buildPerceptualVideoGroupsUI(videoFiles, duplicateGroups);

            if (!perceptualGroups.isEmpty()) {
                for (Map.Entry<Path, List<Path>> e : perceptualGroups.entrySet()) {
                    duplicateGroups.merge(e.getKey(), e.getValue(), (existing, newList) -> {
                        existing.addAll(newList);
                        return existing;
                    });
                }
                long percDups = perceptualGroups.values().stream().mapToLong(List::size).sum();
                duplicatesFound2.addAndGet(percDups);
                log.info("Phase 3b – Done in {} – {} perceptual video groups, {} files.",
                        elapsed(t3b), perceptualGroups.size(), percDups);
            } else {
                log.info("Phase 3b – Done in {} – no perceptual video duplicates found.", elapsed(t3b));
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
            ui.setPhase("Scan complete – no action flags active.");
            log.info("Phase 4 – No action flags active. Scan complete – no files modified.");
        } else {
            ui.initBar(ui.actionBar(), confirmedDups);

            if (doMove) {
                ui.setPhase("Phase 4 – Moving duplicates…");
                log.info("Phase 4 – Moving {} duplicates to: {}", confirmedDups, outputDir);
                Instant t4m = Instant.now();
                Files.createDirectories(outputDir);
                moveDuplicatesUI(duplicateGroups, outputDir, confirmedDups);
                log.info("Phase 4 – Move done in {} – moved {}, {} errors.",
                        elapsed(t4m), movedCount2.get(), errorCount2.get());
            }
            if (doRename) {
                ui.setPhase("Phase 4 – Renaming duplicates…");
                log.info("Phase 4 – Renaming {} duplicates in-place…", confirmedDups);
                Instant t4r = Instant.now();
                renameDuplicatesUI(duplicateGroups, confirmedDups);
                log.info("Phase 4 – Rename done in {} – renamed {}, {} errors.",
                        elapsed(t4r), renamedCount2.get(), errorCount2.get());
            }
            if (doDelete) {
                ui.setPhase("Phase 4 – Deleting duplicates…");
                log.info("Phase 4 – Deleting {} duplicates…", confirmedDups);
                Instant t4d = Instant.now();
                deleteDuplicatesUI(duplicateGroups, confirmedDups);
                log.info("Phase 4 – Delete done in {} – deleted {}, {} errors.",
                        elapsed(t4d), deletedCount2.get(), errorCount2.get());
            }
            if (doReport) {
                ui.setPhase("Phase 4 – Generating Excel report…");
                log.info("Phase 4 – Generating Excel report → {}", reportFile);
                Instant t4x = Instant.now();
                ExcelReportWriter.write(duplicateGroups, reportFile);
                log.info("Phase 4 – Report done in {}.", elapsed(t4x));
            }
            ui.doneBar(ui.actionBar());
        }

        ui.setPhase("✔  Scan complete.");
        return buildReportUI(start, outputDir, reportFile, confirmedGroups, duplicateGroups);
    }

    // ── Phase 1 ───────────────────────────────────────────────────────────────

    private List<Path> collectImagePathsUI(List<Path> inputDirs) throws InterruptedException {
        Set<String> extSet = new HashSet<>(Arrays.asList(config.getEffectiveSupportedExtensions()));
        ExecutorService pool = newPoolUI();
        List<Future<List<Path>>> futures = new ArrayList<>();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Scanning dirs")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(-1)
                .setUpdateIntervalMillis(200)
                .build()) {

            for (Path dir : inputDirs) {
                futures.add(pool.submit(() -> {
                    ImageFileCollector collector = new ImageFileCollector(extSet, pb);
                    Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                            Integer.MAX_VALUE, collector);
                    long n = collector.getCollectedFiles().size();
                    ui.stepBar(ui.collectBar(), n, -1);
                    return collector.getCollectedFiles();
                }));
            }
            awaitPoolUI(pool);
        }

        List<Path> merged = new ArrayList<>();
        for (Future<List<Path>> f : futures) {
            try { merged.addAll(f.get()); }
            catch (ExecutionException ex) {
                log.error("Phase 1 – Directory walk error: {}", ex.getCause().getMessage());
                errorCount2.incrementAndGet();
            }
        }
        return merged;
    }

    // ── Phase 2 ───────────────────────────────────────────────────────────────

    private void computePartialHashesUI(List<Path> files) throws InterruptedException {
        int total = files.size();
        int batchSize = Math.max(500, total / (config.getThreadCount() * 8));
        ExecutorService pool = newPoolUI();

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Partial hash ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total).setUpdateIntervalMillis(200).showSpeed().build()) {

            for (int i = 0; i < total; i += batchSize) {
                List<Path> batch = files.subList(i, Math.min(i + batchSize, total));
                for (Path p : batch) {
                    pool.submit(() -> {
                        try {
                            String key = FileHasher.computePartialKey(
                                    p, config.getPartialHashBytes(), config.getReadBufferSize());
                            partialKeyMap2.computeIfAbsent(key,
                                    k -> Collections.synchronizedList(new ArrayList<>(4))).add(p);
                            processedCount2.incrementAndGet();
                            pb.step();
                            long n = processedCount2.get();
                            ui.stepBar(ui.partialBar(), n, total);
                        } catch (IOException ex) {
                            log.warn("Phase 2 – Cannot hash '{}': {}", p, ex.getMessage());
                            errorCount2.incrementAndGet();
                            pb.step();
                        }
                    });
                }
            }
            awaitPoolUI(pool);
        }
    }

    // ── Phase 3 ───────────────────────────────────────────────────────────────

    private void computeFullHashesUI(List<List<Path>> groups, long total)
            throws InterruptedException {
        ExecutorService pool = newPoolUI();
        AtomicLong done = new AtomicLong(0);

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Full SHA-256 ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total).setUpdateIntervalMillis(200).showSpeed().build()) {

            for (List<Path> group : groups) {
                for (Path p : group) {
                    pool.submit(() -> {
                        try {
                            String hash = FileHasher.computeFullHash(p, config.getReadBufferSize());
                            fullHashMap2.computeIfAbsent(hash,
                                    k -> Collections.synchronizedList(new ArrayList<>(4))).add(p);
                            pb.step();
                            ui.stepBar(ui.fullBar(), done.incrementAndGet(), total);
                        } catch (IOException ex) {
                            log.warn("Phase 3 – Cannot hash '{}': {}", p, ex.getMessage());
                            errorCount2.incrementAndGet();
                            pb.step();
                        }
                    });
                }
            }
            awaitPoolUI(pool);
        }
    }

    /**
     * Builds duplicate groups where the key is the <b>largest file</b> by size
     * (i.e. the file that will be kept), and the values are all smaller/other
     * copies that will be acted upon (moved, renamed, or deleted).
     *
     * <p>Tie-breaking (same size): the lexicographically first path is kept.
     */
    private Map<Path, List<Path>> buildDuplicateGroupsUI() {
        Map<Path, List<Path>> result = new LinkedHashMap<>();
        for (List<Path> group : fullHashMap2.values()) {
            if (group.size() < 2) continue;
            List<Path> sorted = new ArrayList<>(group);
            // Sort: largest first; ties broken by lex path (smallest path wins = kept)
            sorted.sort(Comparator
                    .comparingLong(DuplicateImageFinderWithUiProgress::fileSize).reversed()
                    .thenComparing(Path::toString));
            result.put(sorted.get(0), new ArrayList<>(sorted.subList(1, sorted.size())));
        }
        return result;
    }

    /** Returns the file size in bytes, or 0 if the size cannot be determined. */
    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    // ── Phase 3b helpers (UI variant) ─────────────────────────────────────────

    private static List<Path> collectVideoFilesFromGroupsUI(
            Map<Path, List<Path>> groups, Set<String> videoExts) {
        List<Path> result = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> e : groups.entrySet()) {
            if (isVideoFileUI(e.getKey(), videoExts))   result.add(e.getKey());
            for (Path dup : e.getValue())
                if (isVideoFileUI(dup, videoExts)) result.add(dup);
        }
        return result;
    }

    private static boolean isVideoFileUI(Path p, Set<String> videoExts) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && videoExts.contains(name.substring(dot + 1));
    }

    /**
     * Runs perceptual fingerprinting on all video files not already confirmed
     * as exact duplicates, clusters by pHash similarity, and returns new groups.
     * Updates the UI progress bar as fingerprints are computed.
     */
    private Map<Path, List<Path>> buildPerceptualVideoGroupsUI(
            List<Path> videoFiles,
            Map<Path, List<Path>> existingGroups) throws InterruptedException {

        Set<Path> alreadyDuplicates = new HashSet<>();
        for (List<Path> dups : existingGroups.values()) alreadyDuplicates.addAll(dups);

        List<Path> candidates = videoFiles.stream()
                .filter(p -> !alreadyDuplicates.contains(p))
                .distinct()
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return Collections.emptyMap();
        log.info("Phase 3b – Fingerprinting {} video candidate(s)…", candidates.size());

        VideoPerceptualHasher hasher = new VideoPerceptualHasher(
                config.getVideoSampleFrames(),
                config.getPerceptualHammingThreshold());

        ConcurrentHashMap<Path, long[]> fingerprints = new ConcurrentHashMap<>();
        ExecutorService pool = newPoolUI();
        AtomicLong done = new AtomicLong(0);
        long total = candidates.size();

        ui.initBar(ui.fullBar(), total);   // reuse Phase 3 bar for pHash progress

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("PHash video  ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total).setUpdateIntervalMillis(300).showSpeed().build()) {

            for (Path p : candidates) {
                pool.submit(() -> {
                    long[] fp = hasher.computeFingerprint(p);
                    if (fp != null) fingerprints.put(p, fp);
                    pb.step();
                    ui.stepBar(ui.fullBar(), done.incrementAndGet(), total);
                });
            }
            awaitPoolUI(pool);
        }
        ui.doneBar(ui.fullBar());

        if (fingerprints.isEmpty()) return Collections.emptyMap();

        // Single-linkage clustering
        List<Path> fpKeys  = new ArrayList<>(fingerprints.keySet());
        int        n       = fpKeys.size();
        int[]      cluster = new int[n];
        Arrays.fill(cluster, -1);
        int nextCluster = 0;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long[] fi = fingerprints.get(fpKeys.get(i));
                long[] fj = fingerprints.get(fpKeys.get(j));
                if (hasher.isSimilar(fi, fj)) {
                    if      (cluster[i] == -1 && cluster[j] == -1) { cluster[i] = cluster[j] = nextCluster++; }
                    else if (cluster[i] == -1)                       { cluster[i] = cluster[j]; }
                    else if (cluster[j] == -1)                       { cluster[j] = cluster[i]; }
                    else {
                        int from = cluster[j], to = cluster[i];
                        for (int k = 0; k < n; k++) if (cluster[k] == from) cluster[k] = to;
                    }
                }
            }
        }

        Map<Integer, List<Path>> clusterMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (cluster[i] < 0) continue;
            clusterMap.computeIfAbsent(cluster[i], k -> new ArrayList<>()).add(fpKeys.get(i));
        }

        Map<Path, List<Path>> result = new LinkedHashMap<>();
        for (List<Path> group : clusterMap.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparingLong(DuplicateImageFinderWithUiProgress::fileSize)
                                 .reversed().thenComparing(Path::toString));
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
    private void moveDuplicatesUI(Map<Path, List<Path>> groups, Path outputDir, long total)
            throws InterruptedException {
        ExecutorService pool = newPoolUI();
        AtomicLong done = new AtomicLong(0);

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Moving files ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total).setUpdateIntervalMillis(200).showSpeed().build()) {

            for (Map.Entry<Path, List<Path>> entry : groups.entrySet()) {
                Path original = entry.getKey();
                List<Path> dups = entry.getValue();

                String origFileName = original.getFileName().toString();
                int dot = origFileName.lastIndexOf('.');
                String baseName = (dot < 0) ? origFileName : origFileName.substring(0, dot);
                String ext      = (dot < 0) ? ""           : origFileName.substring(dot);

                Path subFolder = outputDir.resolve(baseName);

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
                            movedCount2.incrementAndGet();
                        } catch (IOException ex) {
                            log.error("Move failed '{}': {}", dup, ex.getMessage());
                            errorCount2.incrementAndGet();
                        }
                        pb.step();
                        ui.stepBar(ui.actionBar(), done.incrementAndGet(), total);
                    });
                }
            }
            awaitPoolUI(pool);
        }
    }

    // ── Phase 4b – Rename ─────────────────────────────────────────────────────

    /**
     * Renames each duplicate using the original file's base name + counter.
     * e.g. original {@code photo.jpg} → duplicates become {@code photo_1.jpg},
     * {@code photo_2.jpg}, etc.
     */
    private void renameDuplicatesUI(Map<Path, List<Path>> groups, long total)
            throws InterruptedException {
        ExecutorService pool = newPoolUI();
        AtomicLong done = new AtomicLong(0);

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Renaming     ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total).setUpdateIntervalMillis(200).showSpeed().build()) {

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
                        int extra = 1;
                        while (Files.exists(target)) {
                            target = parent.resolve(baseName + "_" + idx + "_" + extra++ + ext);
                        }
                        try {
                            try { Files.move(dup, target, StandardCopyOption.ATOMIC_MOVE); }
                            catch (AtomicMoveNotSupportedException e) {
                                Files.move(dup, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                            renamedCount2.incrementAndGet();
                        } catch (IOException ex) {
                            log.error("Rename failed '{}': {}", dup, ex.getMessage());
                            errorCount2.incrementAndGet();
                        }
                        pb.step();
                        ui.stepBar(ui.actionBar(), done.incrementAndGet(), total);
                    });
                }
            }
            awaitPoolUI(pool);
        }
    }

    // ── Phase 4c – Delete ─────────────────────────────────────────────────────

    private void deleteDuplicatesUI(Map<Path, List<Path>> groups, long total)
            throws InterruptedException {
        ExecutorService pool = newPoolUI();
        AtomicLong done = new AtomicLong(0);

        try (ProgressBar pb = new ProgressBarBuilder()
                .setTaskName("Deleting     ")
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setInitialMax(total).setUpdateIntervalMillis(200).showSpeed().build()) {

            for (List<Path> dups : groups.values()) {
                for (Path dup : dups) {
                    pool.submit(() -> {
                        try {
                            Files.deleteIfExists(dup);
                            deletedCount2.incrementAndGet();
                            log.info("Phase 4 – Deleted '{}'", dup);
                        } catch (IOException ex) {
                            log.error("Delete failed '{}': {}", dup, ex.getMessage());
                            errorCount2.incrementAndGet();
                        }
                        pb.step();
                        ui.stepBar(ui.actionBar(), done.incrementAndGet(), total);
                    });
                }
            }
            awaitPoolUI(pool);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ExecutorService newPoolUI() {
        return Executors.newFixedThreadPool(config.getThreadCount(), r -> {
            Thread t = new Thread(r, "dup-finder-ui");
            t.setDaemon(true);
            return t;
        });
    }

    private static void awaitPoolUI(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
            pool.shutdownNow();
            throw new InterruptedException("Processing timed out after 24 hours");
        }
    }

    private static void validateDirsUI(List<Path> dirs) throws IOException {
        for (Path d : dirs) {
            if (!Files.exists(d))      throw new IOException("Directory not found: " + d);
            if (!Files.isDirectory(d)) throw new IOException("Not a directory: " + d);
            if (!Files.isReadable(d))  throw new IOException("Directory not readable: " + d);
        }
    }

    private Path resolveOutputDirUI(List<Path> inputDirs) {
        Path raw = Paths.get(config.getDuplicateOutputDir());
        return raw.isAbsolute() ? raw
                : inputDirs.get(0).toAbsolutePath().getParent().resolve(raw);
    }

    private Path resolveReportPathUI(List<Path> inputDirs) {
        Path raw = Paths.get(config.getReportPath());
        return raw.isAbsolute() ? raw
                : inputDirs.get(0).toAbsolutePath().getParent().resolve(raw);
    }

    private static String elapsed(Instant since) {
        Duration d = Duration.between(since, Instant.now());
        return d.toMinutes() > 0
                ? String.format("%dm %ds", d.toMinutes(), d.toSecondsPart())
                : String.format("%ds", d.toSecondsPart());
    }

    private ScanReport buildReportUI(Instant start, Path outputDir, Path reportFile,
                                      long groups, Map<Path, List<Path>> dupGroups) {
        return new ScanReport(
                Duration.between(start, Instant.now()),
                processedCount2.get(), duplicatesFound2.get(), groups,
                movedCount2.get(), renamedCount2.get(), deletedCount2.get(), errorCount2.get(),
                outputDir, reportFile, dupGroups);
    }
}

