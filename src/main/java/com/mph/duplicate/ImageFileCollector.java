package com.mph.duplicate;

import me.tongfei.progressbar.ProgressBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * NIO2 {@link FileVisitor} that collects image file {@link Path}s while
 * walking a directory tree and steps a shared {@link ProgressBar} for
 * every image file found.
 *
 * <p>Not thread-safe — create one instance per walk thread.
 */
public class ImageFileCollector implements FileVisitor<Path> {

    private static final Logger log = LoggerFactory.getLogger(ImageFileCollector.class);

    private final Set<String> supportedExtensions;
    private final List<Path>  collectedFiles;
    private final ProgressBar progressBar;   // shared across threads; ProgressBar is thread-safe

    public ImageFileCollector(Set<String> supportedExtensions, ProgressBar progressBar) {
        this.supportedExtensions = supportedExtensions;
        this.collectedFiles      = new ArrayList<>(4_096);
        this.progressBar         = progressBar;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile() || attrs.size() == 0) return FileVisitResult.CONTINUE;

        String name   = file.getFileName().toString();
        int    dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0 && supportedExtensions.contains(name.substring(dotIdx + 1).toLowerCase())) {
            collectedFiles.add(file);
            progressBar.step();                    // advance the progress bar
            progressBar.setExtraMessage(" " + name); // show current filename
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        log.warn("Cannot access '{}': {}", file, exc.getMessage());
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
        if (exc != null) log.warn("Error after visiting dir '{}': {}", dir, exc.getMessage());
        return FileVisitResult.CONTINUE;
    }

    public List<Path> getCollectedFiles() { return collectedFiles; }
}
