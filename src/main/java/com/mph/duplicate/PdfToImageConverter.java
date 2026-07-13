package com.mph.duplicate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Converts PDF files to images (PNG) using Apache PDFBox.
 *
 * <h2>Output structure</h2>
 * For each PDF file {@code report.pdf} placed in the chosen output root:
 * <pre>
 *   &lt;outputRoot&gt;/
 *     report/
 *       report_page_001.png
 *       report_page_002.png
 *       …
 * </pre>
 * The subfolder name equals the PDF's base name (without extension).
 */
public class PdfToImageConverter {

    private static final Logger log = LoggerFactory.getLogger(PdfToImageConverter.class);

    /** DPI resolution for rendering.  150 dpi ≈ screen quality; 300 dpi = print quality. */
    private final float dpi;

    /** Image format to write (e.g. "PNG", "JPEG"). */
    private final String imageFormat;

    /**
     * @param dpi         rendering resolution (e.g. 150f or 300f)
     * @param imageFormat output format recognised by {@link ImageIO} (e.g. "PNG")
     */
    public PdfToImageConverter(float dpi, String imageFormat) {
        this.dpi         = dpi;
        this.imageFormat = imageFormat.toUpperCase();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Result record returned by {@link #convertAll}.
     */
    public static class ConvertReport {
        public final int pdfCount;
        public final int pageCount;
        public final int errorCount;
        public final List<String> errors;

        ConvertReport(int pdfCount, int pageCount, int errorCount, List<String> errors) {
            this.pdfCount   = pdfCount;
            this.pageCount  = pageCount;
            this.errorCount = errorCount;
            this.errors     = errors;
        }

        @Override
        public String toString() {
            return String.format("PDFs: %d  |  Pages converted: %d  |  Errors: %d",
                    pdfCount, pageCount, errorCount);
        }
    }

    /**
     * Converts all supplied PDF files, writing images into sub-folders under
     * {@code outputRoot}.
     *
     * @param pdfFiles   list of PDF {@link Path}s to convert
     * @param outputRoot root output directory (created if absent)
     * @param progress   optional callback invoked after each page:
     *                   {@code (pagesCompletedSoFar, totalPages)}. May be {@code null}.
     * @return a {@link ConvertReport} with counts and any error messages
     */
    public ConvertReport convertAll(List<Path> pdfFiles, Path outputRoot,
                                    BiConsumer<Integer, Integer> progress) throws IOException {
        Files.createDirectories(outputRoot);

        int        totalPages  = 0;
        int        errorCount  = 0;
        List<String> errors    = new ArrayList<>();
        AtomicInteger donePages = new AtomicInteger(0);

        // First pass: count total pages so progress is meaningful
        int estimatedTotal = estimateTotalPages(pdfFiles);

        for (Path pdfPath : pdfFiles) {
            // Respect cancellation requests from the UI
            if (Thread.currentThread().isInterrupted()) {
                log.info("Conversion cancelled by user after {} page(s).", totalPages);
                break;
            }
            log.info("Converting PDF: {}", pdfPath.getFileName());
            try {
                int converted = convertSingle(pdfPath, outputRoot,
                        donePages, estimatedTotal, progress);
                totalPages += converted;
            } catch (IOException ex) {
                String msg = "Failed to convert '" + pdfPath.getFileName() + "': " + ex.getMessage();
                log.error(msg);
                errors.add(msg);
                errorCount++;
            }
        }
        return new ConvertReport(pdfFiles.size(), totalPages, errorCount, errors);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Converts a single PDF file, writing one image per page into
     * {@code <outputRoot>/<pdfBaseName>/}.
     *
     * @return number of pages successfully converted
     */
    private int convertSingle(Path pdfPath, Path outputRoot,
                               AtomicInteger donePages, int totalPages,
                               BiConsumer<Integer, Integer> progress) throws IOException {
        String fileName = pdfPath.getFileName().toString();
        String baseName = fileName.endsWith(".pdf") || fileName.endsWith(".PDF")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;

        // Create a sub-folder named after the PDF (without extension)
        Path subDir = outputRoot.resolve(sanitize(baseName));
        Files.createDirectories(subDir);

        int converted = 0;
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int numPages = doc.getNumberOfPages();
            int digits   = String.valueOf(numPages).length();
            String fmt   = "%s_page_%0" + digits + "d.%s";

            for (int page = 0; page < numPages; page++) {
                String imgName = String.format(fmt, baseName, page + 1,
                        imageFormat.toLowerCase());
                Path imgPath = subDir.resolve(imgName);

                try {
                    BufferedImage img = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                    ImageIO.write(img, imageFormat, imgPath.toFile());
                    converted++;
                    int done = donePages.incrementAndGet();
                    if (progress != null) progress.accept(done, totalPages);
                    log.debug("  Page {} → {}", page + 1, imgPath.getFileName());
                } catch (IOException ex) {
                    log.warn("  Could not render page {} of '{}': {}",
                            page + 1, fileName, ex.getMessage());
                }
            }
        }
        log.info("  Converted {}/{} pages → {}", converted, converted, subDir);
        return converted;
    }

    /** Pre-opens all PDFs to count their pages (fast — just reads the page tree). */
    private static int estimateTotalPages(List<Path> pdfFiles) {
        int total = 0;
        for (Path p : pdfFiles) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                total += doc.getNumberOfPages();
            } catch (Exception ignored) {
                total += 1; // fallback estimate
            }
        }
        return Math.max(1, total);
    }

    /** Replaces characters illegal in directory names with underscores. */
    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}

