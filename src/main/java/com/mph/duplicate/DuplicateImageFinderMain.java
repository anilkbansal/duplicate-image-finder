package com.mph.duplicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Entry point for the Duplicate Image Finder.
 *
 * <ul>
 *   <li>If a graphical display is available → launches the Swing GUI.</li>
 *   <li>If running headless (server / CI / no display) → falls back to the
 *       interactive console wizard.</li>
 * </ul>
 */
public class DuplicateImageFinderMain {

    private static final Logger log = LoggerFactory.getLogger(DuplicateImageFinderMain.class);

    public static void main(String[] args) throws IOException, InterruptedException {

        // ── GUI mode ──────────────────────────────────────────────────────────
        if (isGraphicsAvailable()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    // Use Metal (cross-platform) LAF so all setForeground/setBackground
                    // calls are fully respected — Windows LAF ignores most of them.
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                } catch (Exception ignored) { /* use default */ }


                DuplicateImageFinderUI ui = new DuplicateImageFinderUI();
                ui.setVisible(true);
            });
            return;
        }

        // ── Headless / CLI fallback ───────────────────────────────────────────
        log.info("No graphical display detected – starting interactive console wizard.");
        runCliWizard();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GUI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isGraphicsAvailable() {
        if (GraphicsEnvironment.isHeadless()) return false;
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CLI wizard (headless fallback)
    // ─────────────────────────────────────────────────────────────────────────

    private static void runCliWizard() throws IOException, InterruptedException {
        printWelcome();
        DuplicateImageConfig config = new DuplicateImageConfig();
        List<Path> inputDirs = new ArrayList<>();

        try (Scanner sc = new Scanner(System.in)) {

            // Step 1 – Directories
            System.out.println("\n┌─────────────────────────────────────────────────────┐");
            System.out.println(  "│  STEP 1 – Input Directories                         │");
            System.out.println(  "└─────────────────────────────────────────────────────┘");
            System.out.println("Enter the full path of each directory to scan.");
            System.out.println("Press ENTER on an empty line when done.\n");
            while (true) {
                System.out.printf("  Directory %d (or ENTER to finish): ", inputDirs.size() + 1);
                String line = sc.nextLine().trim();
                if (line.isEmpty()) {
                    if (inputDirs.isEmpty()) { System.out.println("  ⚠  Please enter at least one directory."); continue; }
                    break;
                }
                Path p = Paths.get(line).toAbsolutePath().normalize();
                if (!Files.exists(p))        { System.out.println("  ⚠  Path does not exist: " + p); continue; }
                if (!Files.isDirectory(p))   { System.out.println("  ⚠  Not a directory: " + p);     continue; }
                inputDirs.add(p);
                System.out.println("  ✔  Added: " + p);
            }

            // Step 2 – Move
            System.out.println("\n┌─────────────────────────────────────────────────────┐");
            System.out.println(  "│  STEP 2 – Move Duplicates                           │");
            System.out.println(  "└─────────────────────────────────────────────────────┘");
            boolean doMove = askYesNo(sc, "Move duplicate files to a separate folder? [Y/n]: ", true);
            config.setMoveDuplicates(doMove);
            if (doMove) {
                System.out.print("  Output folder (ENTER = 'duplicates_found'): ");
                String outDir = sc.nextLine().trim();
                if (!outDir.isEmpty()) config.setDuplicateOutputDir(outDir);
            }

            // Step 3 – Rename
            System.out.println("\n┌─────────────────────────────────────────────────────┐");
            System.out.println(  "│  STEP 3 – Rename Duplicates In-Place                │");
            System.out.println(  "└─────────────────────────────────────────────────────┘");
            System.out.println("  e.g.  photo.jpg  →  photo_duplicate.jpg");
            config.setRenameDuplicates(askYesNo(sc, "Rename duplicates in-place? [y/N]: ", false));

            // Step 4 – Report
            System.out.println("\n┌─────────────────────────────────────────────────────┐");
            System.out.println(  "│  STEP 4 – Excel Report                              │");
            System.out.println(  "└─────────────────────────────────────────────────────┘");
            boolean doReport = askYesNo(sc, "Generate Excel (.xlsx) duplicate report? [Y/n]: ", true);
            config.setGenerateReport(doReport);
            if (doReport) {
                System.out.print("  Report file path (ENTER = 'duplicate_report.xlsx'): ");
                String rPath = sc.nextLine().trim();
                if (!rPath.isEmpty()) config.setReportPath(rPath);
            }

            // Step 5 – Threads
            System.out.println("\n┌─────────────────────────────────────────────────────┐");
            System.out.println(  "│  STEP 5 – Performance                               │");
            System.out.println(  "└─────────────────────────────────────────────────────┘");
            int defT = config.getThreadCount();
            System.out.printf("  Parallel threads (ENTER = %d): ", defT);
            String ti = sc.nextLine().trim();
            if (!ti.isEmpty()) {
                try { int t = Integer.parseInt(ti); if (t > 0) config.setThreadCount(t); }
                catch (NumberFormatException e) { System.out.println("  ⚠  Invalid – using " + defT); }
            }
        }

        // Summary
        System.out.println("\n  Input directories :");
        inputDirs.forEach(d -> System.out.println("    • " + d));
        System.out.println("  Move duplicates   : " + (config.isMoveDuplicates()   ? "YES → " + config.getDuplicateOutputDir() : "NO"));
        System.out.println("  Rename duplicates : " + (config.isRenameDuplicates() ? "YES" : "NO"));
        System.out.println("  Excel report      : " + (config.isGenerateReport()   ? "YES → " + config.getReportPath() : "NO"));
        System.out.println("  Threads           : " + config.getThreadCount() + "\n");

        log.info("Starting scan…");
        DuplicateImageFinder finder = new DuplicateImageFinder(config);
        DuplicateImageFinder.ScanReport report = finder.run(inputDirs);
        System.out.println("\n" + report + "\n");
        if (report.errors > 0) { log.warn("Completed with {} error(s).", report.errors); System.exit(1); }
        else log.info("Scan completed successfully.");
    }

    private static boolean askYesNo(Scanner sc, String prompt, boolean defaultYes) {
        while (true) {
            System.out.print("  " + prompt);
            String in = sc.nextLine().trim().toLowerCase();
            if (in.isEmpty()) return defaultYes;
            if (in.equals("y") || in.equals("yes")) return true;
            if (in.equals("n") || in.equals("no"))  return false;
            System.out.println("  ⚠  Please enter Y or N.");
        }
    }

    private static void printWelcome() {
        System.out.println("\n╔═══════════════════════════════════════════════════════════╗");
        System.out.println(  "║       Duplicate Image Finder  –  2 TB Edition            ║");
        System.out.println(  "╚═══════════════════════════════════════════════════════════╝");
        System.out.println("\n  Logs are written to  duplicate-finder.log\n");
    }
}
