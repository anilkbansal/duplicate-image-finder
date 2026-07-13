package com.mph.duplicate;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Duplicate Image Finder — covers all action modes:
 * move, no-move, rename, report, and combinations.
 */
class DuplicateImageFinderTest {

    @TempDir Path tempDir;

    private DuplicateImageConfig config;

    @BeforeEach
    void setUp() {
        config = new DuplicateImageConfig();
        config.setThreadCount(2);
        config.setProgressLogInterval(10);
        config.setDuplicateOutputDir(tempDir.resolve("duplicates").toString());
        config.setReportPath(tempDir.resolve("report.xlsx").toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path writeFile(String relPath, String content) throws IOException {
        return writeFile(relPath, content.getBytes());
    }

    private Path writeFile(String relPath, byte[] content) throws IOException {
        Path p = tempDir.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.write(p, content);
        return p;
    }

    private DuplicateImageFinder.ScanReport runFinder(String... subDirs) throws Exception {
        List<Path> dirs = new java.util.ArrayList<>();
        for (String sub : subDirs) dirs.add(tempDir.resolve(sub));
        return new DuplicateImageFinder(config).run(dirs);
    }

    // ── existing move tests ───────────────────────────────────────────────────

    @Test
    void emptyDirectory_producesEmptyReport() throws Exception {
        Files.createDirectories(tempDir.resolve("empty"));
        var report = runFinder("empty");
        assertThat(report.filesScanned).isZero();
        assertThat(report.filesMoved).isZero();
        assertThat(report.errors).isZero();
    }

    @Test
    void noDuplicates_noFileMoved() throws Exception {
        writeFile("d1/a.jpg", "IMAGE_AAA");
        writeFile("d1/b.jpg", "IMAGE_BBB");
        writeFile("d1/c.png", "IMAGE_CCC");
        var report = runFinder("d1");
        assertThat(report.filesMoved).isZero();
        assertThat(report.duplicatesFound).isZero();
    }

    @Test
    void twoIdenticalFiles_oneGetsMoved() throws Exception {
        byte[] data = "IDENTICAL_IMAGE_BYTES_1234567890".getBytes();
        Path original = writeFile("d1/photo.jpg", data);
        Path copy     = writeFile("d1/photo_copy.jpg", data);

        var report = runFinder("d1");

        assertThat(report.filesMoved).isEqualTo(1);
        assertThat(report.duplicatesFound).isEqualTo(1);
        assertThat(report.errors).isZero();
        assertThat(Files.exists(original) ^ Files.exists(copy))
                .as("Exactly one source file must remain in-place").isTrue();
        assertThat(Files.list(tempDir.resolve("duplicates"))).hasSize(1);
    }

    @Test
    void multipleGroups_allExtrasAreMoved() throws Exception {
        byte[] groupA = "GROUP_A_IMAGE_DATA".getBytes();
        writeFile("d1/a1.jpg", groupA);
        writeFile("d1/a2.jpg", groupA);
        writeFile("d1/a3.jpg", groupA);

        byte[] groupB = "GROUP_B_IMAGE_DATA".getBytes();
        writeFile("d1/b1.png", groupB);
        writeFile("d1/b2.png", groupB);

        writeFile("d1/unique.jpg", "UNIQUE_IMAGE_DATA");

        var report = runFinder("d1");

        assertThat(report.filesMoved).isEqualTo(3);
        assertThat(report.duplicateGroups).isEqualTo(2);
        assertThat(report.errors).isZero();
    }

    @Test
    void crossDirectoryDuplicates_areDetected() throws Exception {
        byte[] shared = "CROSS_DIR_DUPLICATE".getBytes();
        writeFile("dir1/original.jpg", shared);
        writeFile("dir2/backup.jpg",   shared);

        var report = runFinder("dir1", "dir2");

        assertThat(report.filesMoved).isEqualTo(1);
        assertThat(report.duplicatesFound).isEqualTo(1);
    }

    @Test
    void nonImageFiles_areIgnored() throws Exception {
        byte[] data = "SOME_DOCUMENT_CONTENT".getBytes();
        writeFile("d1/doc.pdf",   data);
        writeFile("d1/doc2.pdf",  data);
        writeFile("d1/photo.jpg", "PHOTO_CONTENT");

        var report = runFinder("d1");

        assertThat(report.filesScanned).isEqualTo(1);
        assertThat(report.filesMoved).isZero();
    }

    @Test
    void nearlyIdenticalFiles_areNotDuplicates() throws Exception {
        writeFile("d1/img1.jpg", "CONTENT_VERSION_A_001");
        writeFile("d1/img2.jpg", "CONTENT_VERSION_B_002");

        var report = runFinder("d1");
        assertThat(report.filesMoved).isZero();
    }

    @Test
    void filenameCollisionInOutputDir_resolvedBySuffix() throws Exception {
        byte[] data = "COLLISION_TEST_IMAGE_DATA".getBytes();
        writeFile("d1/photo.jpg", data);
        writeFile("d2/photo.jpg", data);
        writeFile("d3/photo.jpg", data);

        var report = runFinder("d1", "d2", "d3");

        assertThat(report.filesMoved).isEqualTo(2);
        // Duplicates are placed inside duplicates/photo/ sub-folder
        Path subFolder = tempDir.resolve("duplicates").resolve("photo");
        assertThat(Files.exists(subFolder)).as("Sub-folder 'photo' must be created").isTrue();
        assertThat(Files.list(subFolder)).hasSize(2);
    }

    // ── no-move mode ─────────────────────────────────────────────────────────

    @Test
    void noMove_duplicatesDetectedButNotMoved() throws Exception {
        config.setMoveDuplicates(false);

        byte[] data = "NO_MOVE_IMAGE_DATA".getBytes();
        Path f1 = writeFile("d1/a.jpg", data);
        Path f2 = writeFile("d1/b.jpg", data);

        var report = runFinder("d1");

        assertThat(report.duplicatesFound).isEqualTo(1);
        assertThat(report.filesMoved).isZero();
        // Both files must still exist in-place
        assertThat(Files.exists(f1)).isTrue();
        assertThat(Files.exists(f2)).isTrue();
        // Output dir must NOT have been created
        assertThat(Files.exists(tempDir.resolve("duplicates"))).isFalse();
    }

    // ── rename mode ───────────────────────────────────────────────────────────

    @Test
    void rename_duplicateFileRenamedWithSuffix() throws Exception {
        config.setMoveDuplicates(false);
        config.setRenameDuplicates(true);

        byte[] data = "RENAME_IMAGE_DATA".getBytes();
        // photo.jpg is lexicographically first → kept as original
        // copy.jpg  is the duplicate → renamed to photo_1.jpg
        writeFile("d1/photo.jpg", data);
        writeFile("d1/copy.jpg",  data);

        var report = runFinder("d1");

        assertThat(report.filesRenamed).isEqualTo(1);
        assertThat(report.filesMoved).isZero();

        // The duplicate must have been renamed to photo_1.jpg (original base name + _1)
        assertThat(Files.list(tempDir.resolve("d1"))
                .map(p -> p.getFileName().toString())
                .anyMatch(n -> n.matches(".*_\\d+\\.jpg")))
                .as("One file should match the pattern originalBaseName_N.jpg").isTrue();
    }

    @Test
    void rename_multipleGroupsAllRenamed() throws Exception {
        config.setMoveDuplicates(false);
        config.setRenameDuplicates(true);

        byte[] groupA = "RENAME_GROUP_A".getBytes();
        writeFile("d1/a1.jpg", groupA);
        writeFile("d1/a2.jpg", groupA);
        writeFile("d1/a3.jpg", groupA);

        byte[] groupB = "RENAME_GROUP_B".getBytes();
        writeFile("d1/b1.png", groupB);
        writeFile("d1/b2.png", groupB);

        var report = runFinder("d1");

        // Group A: 2 dups renamed; Group B: 1 dup renamed → 3 total
        assertThat(report.filesRenamed).isEqualTo(3);
        // All renamed files match the pattern <baseName>_N.<ext>
        long renamedCount = Files.list(tempDir.resolve("d1"))
                .filter(p -> p.getFileName().toString().matches(".*_\\d+\\.(jpg|png)"))
                .count();
        assertThat(renamedCount).isEqualTo(3);
    }

    @Test
    void rename_insertSuffix_correctBehaviour() {
        assertThat(DuplicateImageFinder.insertSuffix("photo.jpg",    "duplicate"))
                .isEqualTo("photo_duplicate.jpg");
        assertThat(DuplicateImageFinder.insertSuffix("photo.jpg",    "2"))
                .isEqualTo("photo_2.jpg");
        assertThat(DuplicateImageFinder.insertSuffix("photo",        "duplicate"))
                .isEqualTo("photo_duplicate");
        assertThat(DuplicateImageFinder.insertSuffix("a.b.c.jpg",    "duplicate"))
                .isEqualTo("a.b.c_duplicate.jpg");
    }

    // ── Excel report mode ─────────────────────────────────────────────────────

    @Test
    void report_excelFileCreatedWithCorrectRows() throws Exception {
        config.setMoveDuplicates(false);
        config.setGenerateReport(true);

        byte[] data = "REPORT_IMAGE_DATA_XYZ".getBytes();
        writeFile("d1/photo.jpg",      data);
        writeFile("d1/photo_copy.jpg", data);

        var report = runFinder("d1");

        assertThat(report.duplicateGroups).isEqualTo(1);
        Path xlsx = tempDir.resolve("report.xlsx");
        assertThat(Files.exists(xlsx)).as("Excel report should be created").isTrue();

        // Open and verify content
        try (InputStream in = Files.newInputStream(xlsx);
             Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            // Row 0 = header, Row 1 = first (and only) data row
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            Row dataRow = sheet.getRow(1);
            // Column 2 = Number of Duplicates
            assertThat((int) dataRow.getCell(2).getNumericCellValue()).isEqualTo(1);
        }
    }

    @Test
    void report_rowsWithMultipleDuplicatesAreRedHighlighted() throws Exception {
        config.setMoveDuplicates(false);
        config.setGenerateReport(true);

        // Group with 3 files → 2 duplicates → should be red
        byte[] groupA = "RED_ROW_GROUP_A".getBytes();
        writeFile("d1/a1.jpg", groupA);
        writeFile("d1/a2.jpg", groupA);
        writeFile("d1/a3.jpg", groupA);

        // Group with 2 files → 1 duplicate → normal colour
        byte[] groupB = "NORMAL_ROW_GROUP_B".getBytes();
        writeFile("d1/b1.jpg", groupB);
        writeFile("d1/b2.jpg", groupB);

        var report = runFinder("d1");
        assertThat(report.duplicateGroups).isEqualTo(2);

        Path xlsx = tempDir.resolve("report.xlsx");
        assertThat(Files.exists(xlsx)).isTrue();

        try (InputStream in = Files.newInputStream(xlsx);
             Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            // 2 data rows (1 header + 2 groups)
            assertThat(sheet.getLastRowNum()).isEqualTo(2);

            boolean foundRedRow = false;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                int dupCount = (int) row.getCell(2).getNumericCellValue();
                CellStyle style = row.getCell(0).getCellStyle();
                short fillColor = style.getFillForegroundColor();

                if (dupCount > 1) {
                    // Rose / red fill index
                    assertThat(fillColor)
                            .as("Row with >1 duplicate should have red fill")
                            .isEqualTo(IndexedColors.ROSE.getIndex());
                    foundRedRow = true;
                }
            }
            assertThat(foundRedRow).as("At least one red row expected").isTrue();
        }
    }

    @Test
    void report_noMove_reportOnly_noFilesModified() throws Exception {
        config.setMoveDuplicates(false);
        config.setRenameDuplicates(false);
        config.setGenerateReport(true);

        byte[] data = "REPORT_ONLY_DATA".getBytes();
        Path f1 = writeFile("d1/img1.jpg", data);
        Path f2 = writeFile("d1/img2.jpg", data);

        var report = runFinder("d1");

        // Both files still exist (no move, no rename)
        assertThat(Files.exists(f1)).isTrue();
        assertThat(Files.exists(f2)).isTrue();
        // Report written
        assertThat(Files.exists(tempDir.resolve("report.xlsx"))).isTrue();
        assertThat(report.filesMoved).isZero();
        assertThat(report.filesRenamed).isZero();
    }

    // ── FileHasher tests ──────────────────────────────────────────────────────

    @Test
    void fileHasher_sameContent_sameHash() throws Exception {
        byte[] bytes = "HASH_TEST_CONTENT".getBytes();
        Path f1 = writeFile("f1.jpg", bytes);
        Path f2 = writeFile("f2.jpg", bytes);
        assertThat(FileHasher.computeFullHash(f1, 8_192))
                .isEqualTo(FileHasher.computeFullHash(f2, 8_192));
    }

    @Test
    void fileHasher_differentContent_differentHash() throws Exception {
        Path f1 = writeFile("g1.jpg", "CONTENT_AAA");
        Path f2 = writeFile("g2.jpg", "CONTENT_BBB");
        assertThat(FileHasher.computeFullHash(f1, 8_192))
                .isNotEqualTo(FileHasher.computeFullHash(f2, 8_192));
    }
}
