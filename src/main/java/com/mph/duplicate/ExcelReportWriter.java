package com.mph.duplicate;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes an Excel ({@code .xlsx}) report of duplicate image groups using Apache POI.
 *
 * <h2>Columns</h2>
 * <ol>
 *   <li><b>File Name</b> – name of the kept ("original") file</li>
 *   <li><b>File Path</b> – absolute path of the original file</li>
 *   <li><b>Number of Duplicates</b> – how many extra copies exist</li>
 *   <li><b>Duplicate File Paths</b> – comma-separated absolute paths of duplicates</li>
 * </ol>
 *
 * <h2>Highlighting</h2>
 * Rows where {@code Number of Duplicates > 1} are filled with a red background
 * so they stand out at a glance.
 *
 * <h2>Memory</h2>
 * Uses the standard {@link XSSFWorkbook} (in-memory model).  For extreme scales
 * (hundreds of thousands of rows) switch to {@code SXSSFWorkbook} — the API is
 * identical; just change the constructor call.
 */
public class ExcelReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ExcelReportWriter.class);

    // Column indices
    private static final int COL_FILE_NAME       = 0;
    private static final int COL_FILE_PATH       = 1;
    private static final int COL_DUPLICATE_COUNT = 2;
    private static final int COL_DUPLICATE_PATHS = 3;

    private ExcelReportWriter() { /* utility class */ }

    /**
     * Writes the duplicate-group data to an Excel file at {@code reportPath}.
     *
     * @param duplicateGroups map from the kept (original) {@link Path} to the list of
     *                        its duplicate {@link Path}s (duplicates only, not the original)
     * @param reportPath      destination {@code .xlsx} file (parent dirs are created if needed)
     * @throws IOException on any file-system error
     */
    public static void write(Map<Path, List<Path>> duplicateGroups, Path reportPath)
            throws IOException {

        Files.createDirectories(reportPath.getParent() == null
                ? reportPath.toAbsolutePath().getParent()
                : reportPath.getParent());

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Duplicate Images");

            // ── Styles ──────────────────────────��─────────────────────────────
            CellStyle headerStyle  = buildHeaderStyle(wb);
            CellStyle normalStyle  = buildNormalStyle(wb);
            CellStyle redRowStyle  = buildRedRowStyle(wb);

            // ── Header row ───────────────────────────────────────────────────
            Row header = sheet.createRow(0);
            createCell(header, COL_FILE_NAME,       "File Name",             headerStyle);
            createCell(header, COL_FILE_PATH,       "File Path",             headerStyle);
            createCell(header, COL_DUPLICATE_COUNT, "Number of Duplicates",  headerStyle);
            createCell(header, COL_DUPLICATE_PATHS, "Duplicate File Paths",  headerStyle);

            // ── Data rows ────────────────────────────────────────────────────
            int rowNum = 1;
            for (Map.Entry<Path, List<Path>> entry : duplicateGroups.entrySet()) {
                Path       original   = entry.getKey();
                List<Path> duplicates = entry.getValue();
                int        dupCount   = duplicates.size();

                // Red background when more than 1 duplicate exists for this file
                CellStyle style = (dupCount > 1) ? redRowStyle : normalStyle;

                Row row = sheet.createRow(rowNum++);
                createCell(row, COL_FILE_NAME,
                        original.getFileName().toString(), style);
                createCell(row, COL_FILE_PATH,
                        original.toAbsolutePath().toString(), style);
                createNumericCell(row, COL_DUPLICATE_COUNT, dupCount, style);
                createCell(row, COL_DUPLICATE_PATHS,
                        buildPathsCsv(duplicates), style);
            }

            // ── Auto-size columns for readability ────────────────────────────
            for (int col = 0; col <= COL_DUPLICATE_PATHS; col++) {
                sheet.autoSizeColumn(col);
                // Cap extremely wide columns at 100 characters (~36000 units)
                if (sheet.getColumnWidth(col) > 36_000) {
                    sheet.setColumnWidth(col, 36_000);
                }
            }

            // ── Freeze the header row ────────────────────────────────────────
            sheet.createFreezePane(0, 1);

            // ── Write to disk ────────────────────────────────────────────────
            try (OutputStream out = Files.newOutputStream(reportPath)) {
                wb.write(out);
            }
        }

        log.info("Excel report written → {}  ({} groups)",
                reportPath.toAbsolutePath(), duplicateGroups.size());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String buildPathsCsv(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(paths.get(i).toAbsolutePath());
        }
        return sb.toString();
    }

    private static void createCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void createNumericCell(Row row, int col, int value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    // ── Style builders ────────────────────────────────────────────────────────

    private static CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);

        // Dark-blue background, white text
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        font.setColor(IndexedColors.WHITE.getIndex());

        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        return style;
    }

    private static CellStyle buildNormalStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        return style;
    }

    /**
     * Red-background style used for rows where {@code duplicateCount > 1}.
     * Uses a light red (coral) fill so text remains readable.
     */
    private static CellStyle buildRedRowStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        return style;
    }
}

