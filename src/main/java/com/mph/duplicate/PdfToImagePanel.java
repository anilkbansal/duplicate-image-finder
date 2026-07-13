package com.mph.duplicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.io.File;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.mph.duplicate.DuplicateImageFinderUI.*;

/**
 * Swing panel for the "PDF to Image" tab.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Add multiple PDF files via file-chooser or drag-and-drop.</li>
 *   <li>Choose an output root directory.</li>
 *   <li>Set DPI (75 / 150 / 300 / 600) and format (PNG / JPEG).</li>
 *   <li>Conversion runs on a background thread with a progress bar.</li>
 *   <li>Result report shown in a table dialog.</li>
 * </ul>
 */
public class PdfToImagePanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(PdfToImagePanel.class);

    // ── File list ─────────────────────────────────────────────────────────────
    private final DefaultListModel<String> pdfListModel = new DefaultListModel<>();
    private final JList<String>            pdfList      = new JList<>(pdfListModel);

    // ── Output controls ───────────────────────────────────────────────────────
    private final JTextField txtOutDir  = styledField();
    private final JButton    btnOutDir  = browseButton();
    private final JComboBox<String> cmbDpi    = new JComboBox<>(new String[]{" 75 DPI (Fast)", "150 DPI (Standard)", "300 DPI (High Quality)", "600 DPI (4K Quality)"});
    private final JComboBox<String> cmbFormat = new JComboBox<>(new String[]{"PNG", "JPEG"});

    // ── Progress + log ────────────────────────────────────────────────────────
    private final JProgressBar pdfProgress = makeBar();
    private final JLabel       lblStatus   = new JLabel("  Ready.");
    private final JTextArea    logArea     = new JTextArea();

    // ── Action buttons ────────────────────────────────────────────────────────
    private final JButton btnConvert = new JButton("Convert to Images", UIIcons.play(ACCENT_GREEN, 10));
    private final JButton btnCancel  = new JButton("Cancel",            UIIcons.stop(Color.WHITE, 9));

    private volatile Thread convThread;

    // ─────────────────────────────────────────────────────────────────────────

    public PdfToImagePanel() {
        setLayout(new BorderLayout(6, 6));
        setBackground(BG_WINDOW);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        add(buildFileListPanel(), BorderLayout.CENTER);
        add(buildOptionsAndLogPanel(), BorderLayout.EAST);
        add(buildBottomBar(), BorderLayout.SOUTH);

        wireEvents();
    }

    // ── Left: PDF file list ───────────────────────────────────────────────────

    private JPanel buildFileListPanel() {
        JPanel p = sectionPanel("PDF Files");
        p.setLayout(new BorderLayout(3, 4));

        pdfList.setBackground(BG_CONTROL);
        pdfList.setForeground(FG_PRIMARY);
        pdfList.setFont(FONT_BODY);
        pdfList.setSelectionBackground(ACCENT_BLUE);
        pdfList.setSelectionForeground(Color.WHITE);
        pdfList.setFixedCellHeight(22);
        pdfList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean hasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, hasFocus);
                lbl.setText("  " + value);
                lbl.setIcon(UIIcons.file(isSelected ? Color.WHITE : new Color(100, 140, 200), 13));
                lbl.setIconTextGap(6);
                lbl.setFont(FONT_BODY);
                if (!isSelected) {
                    lbl.setForeground(FG_PATH);
                    lbl.setBackground(index % 2 == 0 ? BG_CONTROL : BG_ROW_ALT);
                }
                lbl.setBorder(new EmptyBorder(1, 4, 1, 4));
                return lbl;
            }
        });

        JScrollPane scroll = new JScrollPane(pdfList);
        scroll.setBackground(BG_CONTROL);
        scroll.getViewport().setBackground(BG_CONTROL);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));

        // Drag-and-drop of PDF files onto the list
        new DropTarget(pdfList, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) e.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    files.stream()
                         .filter(f -> f.isFile() && f.getName().toLowerCase().endsWith(".pdf"))
                         .map(File::getAbsolutePath)
                         .filter(path -> !pdfListModel.contains(path))
                         .forEach(pdfListModel::addElement);
                } catch (Exception ex) {
                    log.warn("Drag & Drop failed: {}", ex.getMessage());
                }
            }
        });

        JButton btnAdd    = accentButton("Add PDF(s)", ACCENT_BLUE,  UIIcons.plus(Color.WHITE, 9));
        JButton btnRemove = accentButton("Remove",     ACCENT_RED,   UIIcons.minus(Color.WHITE, 9));
        JButton btnClear  = accentButton("Clear All",  new Color(100, 100, 100), null);

        btnAdd.addActionListener(e -> choosePdfs());
        btnRemove.addActionListener(e -> {
            int[] sel = pdfList.getSelectedIndices();
            for (int i = sel.length - 1; i >= 0; i--) pdfListModel.remove(sel[i]);
        });
        btnClear.addActionListener(e -> pdfListModel.clear());

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btnBar.setBackground(BG_PANEL);
        btnBar.add(btnAdd); btnBar.add(btnRemove); btnBar.add(btnClear);

        JLabel hint = new JLabel("  Tip: Drag & Drop PDF files onto the list");
        hint.setFont(FONT_SMALL);
        hint.setForeground(FG_SECONDARY);

        p.add(hint,   BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(btnBar, BorderLayout.SOUTH);
        return p;
    }

    // ── Right: Options + log ──────────────────────────────────────────────────

    private JPanel buildOptionsAndLogPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(BG_WINDOW);
        p.setPreferredSize(new Dimension(360, 0));

        p.add(buildOptionsPanel(), BorderLayout.NORTH);
        p.add(buildLogPanel(),     BorderLayout.CENTER);
        return p;
    }

    private JPanel buildOptionsPanel() {
        JPanel p = sectionPanel("Conversion Options");
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // ── Output directory ──────────────────────────────────────────────────
        p.add(sectionHeading("Output Directory"));
        p.add(Box.createVerticalStrut(2));
        txtOutDir.setColumns(10);
        p.add(fieldRow(txtOutDir, btnOutDir));

        JLabel outNote = new JLabel("  Each PDF gets its own sub-folder");
        outNote.setFont(FONT_SMALL);
        outNote.setForeground(FG_SECONDARY);
        p.add(outNote);
        p.add(Box.createVerticalStrut(8));
        p.add(hairline());
        p.add(Box.createVerticalStrut(6));

        // ── Rendering Quality + Image Format — side by side ──────────────────
        cmbDpi.setFont(FONT_BODY);
        cmbDpi.setBackground(BG_CONTROL);
        cmbDpi.setSelectedIndex(1); // 150 DPI default
        cmbDpi.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
        cmbDpi.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));

        cmbFormat.setFont(FONT_BODY);
        cmbFormat.setBackground(BG_CONTROL);
        cmbFormat.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
        cmbFormat.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));

        // Left sub-panel: Rendering Quality label + dropdown
        JPanel dpiCol = new JPanel();
        dpiCol.setLayout(new BoxLayout(dpiCol, BoxLayout.Y_AXIS));
        dpiCol.setBackground(BG_PANEL);
        JLabel dpiLbl = sectionHeading("Rendering Quality");
        dpiLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        dpiCol.add(dpiLbl);
        dpiCol.add(Box.createVerticalStrut(2));
        cmbDpi.setAlignmentX(Component.LEFT_ALIGNMENT);
        dpiCol.add(cmbDpi);

        // Right sub-panel: Image Format label + dropdown
        JPanel fmtCol = new JPanel();
        fmtCol.setLayout(new BoxLayout(fmtCol, BoxLayout.Y_AXIS));
        fmtCol.setBackground(BG_PANEL);
        JLabel fmtLbl = sectionHeading("Image Format");
        fmtLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        fmtCol.add(fmtLbl);
        fmtCol.add(Box.createVerticalStrut(2));
        cmbFormat.setAlignmentX(Component.LEFT_ALIGNMENT);
        fmtCol.add(cmbFormat);

        // Row that holds both columns with a gap between them
        JPanel qualRow = new JPanel(new GridLayout(1, 2, 8, 0));
        qualRow.setBackground(BG_PANEL);
        qualRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        qualRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        qualRow.add(dpiCol);
        qualRow.add(fmtCol);

        p.add(qualRow);
        p.add(Box.createVerticalStrut(6));
        p.add(hairline());
        p.add(Box.createVerticalStrut(4));

        // ── Progress ─────────────────────────────────────────────────────────
        p.add(sectionHeading("Progress"));
        p.add(Box.createVerticalStrut(2));
        pdfProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        pdfProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        p.add(pdfProgress);

        lblStatus.setFont(FONT_SMALL);
        lblStatus.setForeground(FG_SECONDARY);
        lblStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(lblStatus);
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(FONT_MONO);
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(204, 232, 204));
        logArea.setCaretColor(Color.WHITE);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(false);
        ((javax.swing.text.DefaultCaret) logArea.getCaret())
                .setUpdatePolicy(javax.swing.text.DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(0, 180));
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));

        JButton btnClear = new JButton("Clear");
        btnClear.setFont(FONT_SMALL);
        btnClear.setForeground(FG_SECONDARY);
        btnClear.addActionListener(e -> logArea.setText(""));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        top.setBackground(BG_PANEL);
        top.add(btnClear);

        JPanel p = sectionPanel("Log");
        p.setLayout(new BorderLayout(0, 1));
        p.add(top,    BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ── Bottom bar ────────────────────────────────────────────────────────────

    private JPanel buildBottomBar() {
        btnConvert.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnConvert.setBackground(ACCENT_GREEN);
        btnConvert.setForeground(Color.WHITE);
        btnConvert.setOpaque(true);
        btnConvert.setFocusPainted(false);
        btnConvert.setBorderPainted(false);
        btnConvert.setIconTextGap(6);
        btnConvert.setPreferredSize(new Dimension(180, 30));
        btnConvert.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnCancel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnCancel.setBackground(ACCENT_RED);
        btnCancel.setForeground(Color.WHITE);
        btnCancel.setOpaque(true);
        btnCancel.setFocusPainted(false);
        btnCancel.setBorderPainted(false);
        btnCancel.setIconTextGap(6);
        btnCancel.setPreferredSize(new Dimension(110, 30));
        btnCancel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnCancel.setEnabled(false);

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        p.setBackground(BG_TOOLBAR);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_LIGHT));
        p.add(btnConvert);
        p.add(btnCancel);
        return p;
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    private void wireEvents() {
        btnOutDir.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Select Output Root Directory");
            if (!txtOutDir.getText().isBlank())
                fc.setCurrentDirectory(new File(txtOutDir.getText().trim()));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                txtOutDir.setText(fc.getSelectedFile().getAbsolutePath());
        });

        btnConvert.addActionListener(e -> onConvert());
        btnCancel.addActionListener(e -> {
            Thread t = convThread;
            if (t != null) { t.interrupt(); log.info("Conversion Cancelled."); }
        });
    }

    // ── Conversion logic ──────────────────────────────────────────────────────

    private void onConvert() {
        if (pdfListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one PDF file.",
                    "No PDFs", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<Path> pdfs = new ArrayList<>();
        for (int i = 0; i < pdfListModel.size(); i++)
            pdfs.add(java.nio.file.Paths.get(pdfListModel.get(i)));

        String outRaw = txtOutDir.getText().isBlank() ? "pdf_images" : txtOutDir.getText().trim();
        Path   outDir = java.nio.file.Paths.get(outRaw);

        float  dpiVal    = parseDpi();
        String formatVal = (String) cmbFormat.getSelectedItem();

        PdfToImageConverter converter = new PdfToImageConverter(dpiVal, Objects.requireNonNull(formatVal));

        logArea.setText("");
        setStatus("Converting...", ACCENT_ORANGE);
        btnConvert.setEnabled(false);
        btnCancel.setEnabled(true);
        pdfProgress.setValue(0);
        pdfProgress.setString("0 %");

        convThread = new Thread(() -> {
            try {
                appendLog("Starting conversion of " + pdfs.size() + " PDF(s) -> " + outDir);
                appendLog("DPI: " + (int) dpiVal + "  |  Format: " + formatVal);

                PdfToImageConverter.ConvertReport report = converter.convertAll(pdfs, outDir,
                        (done, total) -> SwingUtilities.invokeLater(() -> {
                            int pct = (int) (done * 100L / total);
                            pdfProgress.setValue(pct);
                            pdfProgress.setString(pct + "%  (" + done + "/" + total + " pages)");
                        }));

                SwingUtilities.invokeLater(() -> {
                    pdfProgress.setValue(100);
                    pdfProgress.setString("Done");
                    setStatus("Completed - " + report.pageCount + " pages.", ACCENT_GREEN);
                    appendLog("Finished: " + report);
                    if (!report.errors.isEmpty())
                        report.errors.forEach(err -> appendLog("  ERROR: " + err));
                    showConvertReport(report);
                    finishUI();
                });
            } catch (Exception ex) {
                if (Thread.currentThread().isInterrupted() || ex instanceof java.io.InterruptedIOException) {
                    SwingUtilities.invokeLater(() -> { setStatus("Cancelled.", ACCENT_ORANGE); finishUI(); });
                } else {
                    log.error("Conversion failed: {}", ex.getMessage(), ex);
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Error: " + ex.getMessage(), ACCENT_RED);
                        appendLog("ERROR: " + ex.getMessage());
                        finishUI();
                    });
                }
            }
        }, "pdf-convert-worker");
        convThread.setDaemon(true);
        convThread.start();
    }

    private void finishUI() {
        btnConvert.setEnabled(true);
        btnCancel.setEnabled(false);
        convThread = null;
    }

    private void setStatus(String text, Color color) {
        lblStatus.setText("  " + text);
        lblStatus.setForeground(color);
    }

    private void appendLog(String msg) {
        SwingUtilities.invokeLater(() -> logArea.append(msg + "\n"));
    }

    private float parseDpi() {
        int idx = cmbDpi.getSelectedIndex();
        return switch (idx) {
            case 0 -> 75f;
            case 2 -> 300f;
            case 3 -> 600f;
            default -> 150f;
        };
    }

    private void choosePdfs() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select PDF Files");
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF Files (*.pdf)", "pdf"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : fc.getSelectedFiles())
                if (!pdfListModel.contains(f.getAbsolutePath()))
                    pdfListModel.addElement(f.getAbsolutePath());
        }
    }

    // ── Report dialog ─────────────────────────────────────────────────────────

    private void showConvertReport(PdfToImageConverter.ConvertReport r) {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);

        Object[][] rows = {
            {"PDFs Processed",  nf.format(r.pdfCount)},
            {"Pages Converted", nf.format(r.pageCount)},
            {"Errors",          nf.format(r.errorCount)},
        };
        String[] cols = {"Metric", "Value"};

        javax.swing.table.DefaultTableModel model =
                new javax.swing.table.DefaultTableModel(rows, cols) {
                    @Override public boolean isCellEditable(int row, int col) { return false; }
                };

        JTable table = new JTable(model);
        table.setFont(FONT_BODY);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFocusable(false);
        table.setRowSelectionAllowed(false);
        table.setBackground(BG_CONTROL);

        javax.swing.table.JTableHeader header = table.getTableHeader();
        header.setFont(FONT_SECTION);
        header.setBackground(BG_HEADER);
        header.setForeground(FG_HEADER);
        header.setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);

        final int errRow = 2;
        javax.swing.table.DefaultTableCellRenderer cr =
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(JTable t, Object v,
                            boolean sel, boolean foc, int row, int col) {
                        super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                        setBorder(new EmptyBorder(2, 8, 2, 8));
                        if (row == errRow && r.errorCount > 0) {
                            setBackground(new Color(255, 235, 233));
                            setForeground(ACCENT_RED);
                        } else {
                            setBackground(row % 2 == 0 ? BG_CONTROL : BG_ROW_ALT);
                            setForeground(col == 1 ? ACCENT_BLUE : FG_PRIMARY);
                        }
                        setFont(col == 1 ? new Font("Segoe UI", Font.BOLD, 12) : FONT_BODY);
                        return this;
                    }
                };
        table.getColumnModel().getColumn(0).setCellRenderer(cr);
        table.getColumnModel().getColumn(1).setCellRenderer(cr);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));
        scroll.setPreferredSize(new Dimension(280,
                table.getRowHeight() * rows.length + header.getPreferredSize().height + 4));

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(BG_PANEL);
        panel.setBorder(new EmptyBorder(12, 18, 12, 18));

        JLabel title = new JLabel("Conversion Complete");
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(ACCENT_BLUE);
        panel.add(title,  BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(this, panel, "PDF Conversion Report", JOptionPane.PLAIN_MESSAGE);
    }

    // ── Static factory helpers (reuse DuplicateImageFinderUI's statics) ───────

    private static JPanel sectionPanel(String title) {
        JPanel p = new JPanel();
        p.setBackground(BG_PANEL);
        p.setOpaque(true);
        TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BORDER_LIGHT, 1), title);
        tb.setTitleColor(ACCENT_BLUE);
        tb.setTitleFont(FONT_SECTION);
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(3, 6, 4, 6)));
        return p;
    }

    private static JLabel sectionHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_SECTION);
        l.setForeground(FG_PRIMARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JSeparator hairline() {
        JSeparator s = new JSeparator();
        s.setForeground(BORDER_LIGHT);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    private static JTextField styledField() {
        JTextField f = new JTextField("pdf_images");
        f.setFont(FONT_BODY);
        f.setBackground(BG_CONTROL);
        f.setForeground(FG_PRIMARY);
        f.setCaretColor(FG_PRIMARY);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_LIGHT),
                new EmptyBorder(1, 4, 1, 4)));
        return f;
    }

    private static JButton browseButton() {
        JButton b = new JButton(" Browse ");
        b.setFont(FONT_SMALL);
        b.setBackground(new Color(225, 225, 225));
        b.setForeground(FG_PRIMARY);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static JButton accentButton(String text, Color bg, Icon icon) {
        JButton b = icon != null ? new JButton(text, icon) : new JButton(text);
        b.setFont(FONT_BODY);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setIconTextGap(5);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static JProgressBar makeBar() {
        JProgressBar b = new JProgressBar(0, 100);
        b.setStringPainted(true);
        b.setString("Waiting...");
        b.setForeground(DuplicateImageFinderUI.ACCENT_TEAL);
        b.setBackground(new Color(220, 220, 220));
        b.setFont(FONT_SMALL);
        b.setPreferredSize(new Dimension(0, 18));
        return b;
    }

    private static JPanel fieldRow(JTextField field, JButton btn) {
        JPanel row = new JPanel(new BorderLayout(3, 0));
        row.setBackground(BG_PANEL);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JLabel lbl = new JLabel("Root:");
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(FG_SECONDARY);
        lbl.setPreferredSize(new Dimension(35, 24));
        row.add(lbl,   BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.add(btn,   BorderLayout.EAST);
        return row;
    }
}

