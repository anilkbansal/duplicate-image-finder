package com.mph.duplicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Windows-native themed Swing GUI for the Duplicate Image Finder.
 */
public class DuplicateImageFinderUI extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(DuplicateImageFinderUI.class);

    // ── Colour palette ────────────────────────────────────────────────────────
    static final Color BG_WINDOW  = new Color(240, 240, 240);
    static final Color BG_PANEL   = new Color(248, 248, 248);
    static final Color BG_CONTROL = Color.WHITE;
    static final Color BG_HEADER  = new Color(0,  114, 198);
    static final Color BG_ROW_ALT = new Color(235, 243, 255);
    static final Color BG_TOOLBAR = new Color(230, 230, 230);

    static final Color FG_PRIMARY   = new Color(15,  15,  15);
    static final Color FG_SECONDARY = new Color(80,  80,  80);
    static final Color FG_HINT      = new Color(110, 110, 110);
    static final Color FG_HEADER    = Color.WHITE;
    static final Color FG_PATH      = new Color(0,   70, 140);

    static final Color ACCENT_BLUE   = new Color(0,  114, 198);
    static final Color ACCENT_GREEN  = new Color(16, 124,  16);
    static final Color ACCENT_RED    = new Color(196,  43,  28);
    static final Color ACCENT_ORANGE = new Color(202, 120,  17);
    static final Color ACCENT_TEAL   = new Color(0,  120, 212);

    static final Color BORDER_LIGHT = new Color(200, 200, 200);

    // ── Fonts ─────────────────────────────────────────────────────────────────
    static final Font FONT_TITLE   = new Font("Segoe UI", Font.BOLD,  14);
    static final Font FONT_SECTION = new Font("Segoe UI", Font.BOLD,  11);
    static final Font FONT_BODY    = new Font("Segoe UI", Font.PLAIN, 11);
    static final Font FONT_SMALL   = new Font("Segoe UI", Font.PLAIN,  9);
    static final Font FONT_MONO    = new Font("Consolas",  Font.PLAIN, 11);
    static final Font FONT_LABEL   = new Font("Segoe UI", Font.PLAIN, 10);

    // ── Directory list ────────────────────────────────────────────────────────
    private final DefaultListModel<String> dirListModel = new DefaultListModel<>();
    private final JList<String>            dirList      = new JList<>(dirListModel);

    // ── Option controls ───────────────────────────────────────────────────────

    // Scan scope checkboxes
    private final JCheckBox chkScanImages = new JCheckBox("Images", true);
    private final JCheckBox chkScanVideos = new JCheckBox("Videos", true);

    // Perceptual video matching
    private final JCheckBox chkPerceptual = new JCheckBox("Perceptual Video Matching  (finds re-encoded copies)", false);
    private final JSpinner  spnThreshold  = new JSpinner(new SpinnerNumberModel(10, 0, 30, 1));
    private final JSpinner  spnFrames     = new JSpinner(new SpinnerNumberModel(16, 4, 64, 4));

    // Move / Delete / None — mutually exclusive radio buttons
    private final JRadioButton rbMove   = new JRadioButton("Move Duplicates to a Separate Folder", true);
    private final JRadioButton rbDelete = new JRadioButton("Delete Duplicates Permanently", false);
    private final JRadioButton rbNone   = new JRadioButton("No Action (Scan / Report Only)", false);

    private final JTextField txtOutput = styledField("duplicates_found");
    private final JButton    btnOutput = browseButton();

    private final JCheckBox  chkRename = new JCheckBox("Rename Duplicates In-Place", false);


    private final JCheckBox  chkReport = new JCheckBox("Generate Excel Report (.xlsx)", true);
    private final JTextField txtReport = styledField("duplicate_report.xlsx");
    private final JButton    btnReport = browseButton();

    // Thread dropdown
    private final JComboBox<String> cmbThreads;

    // ── Progress bars ─────────────────────────────────────────────────────────
    private final JProgressBar pbCollect = makeBar(ACCENT_BLUE);
    private final JProgressBar pbPartial = makeBar(ACCENT_TEAL);
    private final JProgressBar pbFull    = makeBar(ACCENT_ORANGE);
    private final JProgressBar pbAction  = makeBar(ACCENT_GREEN);
    private final JLabel       lblPhase  = new JLabel("Ready");

    // ── Log console ───────────────────────────────────────────────────────────
    private final JTextArea logArea = new JTextArea();

    // ── Buttons / status ─────────────────────────────────────────────────────
    private final JButton btnStart  = new JButton("Start Scan", UIIcons.play(Color.WHITE, 11));
    private final JButton btnCancel = new JButton("Cancel",     UIIcons.stop(Color.WHITE, 10));
    private final JLabel  lblStatus = new JLabel("  Ready to Scan.");

    // ── Running task ref ─────────────────────────────────────────────────────
    private final AtomicReference<Thread> runningThread = new AtomicReference<>();

    // ─────────────────────────────────────────────────────────────────────────

    public DuplicateImageFinderUI() {
        super("Duplicate Image & Video Finder");

        ButtonGroup bgAction = new ButtonGroup();
        bgAction.add(rbMove);
        bgAction.add(rbDelete);
        bgAction.add(rbNone);

        int cores = Runtime.getRuntime().availableProcessors();
        String[] options = new String[cores];
        for (int i = 0; i < cores; i++) {
            options[i] = (i + 1) + " thread" + (i > 0 ? "s" : "");
        }
        cmbThreads = new JComboBox<>(options);
        cmbThreads.setSelectedIndex(cores - 1);
        cmbThreads.setFont(FONT_BODY);
        cmbThreads.setBackground(BG_CONTROL);
        cmbThreads.setForeground(FG_PRIMARY);

        buildUI();
        wireEvents();
        hookLogAppender();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setMinimumSize(new Dimension(820, 640));
        setSize(980, 760);
        setLocationRelativeTo(null);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_WINDOW);
        setContentPane(root);

        root.add(buildHeader(), BorderLayout.NORTH);

        // ── Tabbed pane ───────────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(FONT_SECTION);
        tabs.setBackground(BG_WINDOW);
        tabs.setForeground(FG_PRIMARY);

        // Tab 1 – Duplicate Image & Video Finder
        JPanel dupPanel = buildDuplicateFinderPanel();
        tabs.addTab("Duplicate Image & Video Finder", dupPanel);

        // Tab 2 – PDF to Image
        tabs.addTab("PDF to Image", new PdfToImagePanel());

        root.add(tabs, BorderLayout.CENTER);
    }

    /** Builds the full Duplicate Finder panel (was the old CENTER + SOUTH content). */
    private JPanel buildDuplicateFinderPanel() {
        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBackground(BG_WINDOW);
        body.setBorder(new EmptyBorder(6, 8, 4, 8));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildDirPanel(), buildOptionsPanel());
        split.setDividerLocation(340);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setBackground(BG_WINDOW);

        body.add(split,               BorderLayout.CENTER);
        body.add(buildProgressPanel(), BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(0, 3));
        south.setBackground(BG_WINDOW);
        south.setBorder(new EmptyBorder(0, 8, 6, 8));
        south.add(buildLogPanel(),  BorderLayout.CENTER);
        south.add(buildBottomBar(), BorderLayout.SOUTH);

        JPanel full = new JPanel(new BorderLayout(0, 0));
        full.setBackground(BG_WINDOW);
        full.add(body,  BorderLayout.CENTER);
        full.add(south, BorderLayout.SOUTH);
        return full;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_HEADER);
        p.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel title = new JLabel("  Duplicate Image & Video Finder");
        title.setFont(FONT_TITLE);
        title.setForeground(FG_HEADER);
        title.setIcon(UIIcons.dot(Color.WHITE, ACCENT_BLUE));

        p.add(title, BorderLayout.WEST);
        return p;
    }

    // ── Input Directories Panel ───────────────────────────────────────────────

    private JPanel buildDirPanel() {
        JPanel p = sectionPanel("Input Directories");
        p.setLayout(new BorderLayout(3, 4));

        dirList.setBackground(BG_CONTROL);
        dirList.setForeground(FG_PRIMARY);
        dirList.setFont(FONT_BODY);
        dirList.setSelectionBackground(ACCENT_BLUE);
        dirList.setSelectionForeground(Color.WHITE);
        dirList.setFixedCellHeight(22);

        dirList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                lbl.setText("  " + value);
                lbl.setIcon(UIIcons.folder(isSelected ? Color.WHITE : new Color(202, 150, 40), 14));
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

        JScrollPane scroll = new JScrollPane(dirList);
        scroll.setBackground(BG_CONTROL);
        scroll.getViewport().setBackground(BG_CONTROL);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));
        scroll.setPreferredSize(new Dimension(310, 180));

        // Drag-and-drop
        new DropTarget(dirList, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) e.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    files.stream().filter(File::isDirectory)
                         .map(File::getAbsolutePath)
                         .filter(path -> !dirListModel.contains(path))
                         .forEach(dirListModel::addElement);
                } catch (Exception ex) {
                    log.warn("Drag & Drop Failed: {}", ex.getMessage());
                }
            }
        });

        JButton btnAdd    = accentButton("Add",    ACCENT_BLUE,  UIIcons.plus(Color.WHITE, 9));
        JButton btnRemove = accentButton("Remove", ACCENT_RED,   UIIcons.minus(Color.WHITE, 9));
        JButton btnClear  = accentButton("Clear",  new Color(100, 100, 100), null);

        btnAdd.addActionListener(e -> chooseDirectories());
        btnRemove.addActionListener(e -> {
            int[] sel = dirList.getSelectedIndices();
            for (int i = sel.length - 1; i >= 0; i--) dirListModel.remove(sel[i]);
        });
        btnClear.addActionListener(e -> dirListModel.clear());

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        btnBar.setBackground(BG_PANEL);
        btnBar.add(btnAdd); btnBar.add(btnRemove); btnBar.add(btnClear);

        JLabel hint = new JLabel("  Tip: Drag & Drop Folders onto the List");
        hint.setFont(FONT_SMALL);
        hint.setForeground(FG_SECONDARY);

        // ── Scan scope row ────────────────────────────────────────────────────
        styleCheckBox(chkScanImages);
        styleCheckBox(chkScanVideos);

        JLabel scopeLbl = new JLabel("  Scan:");
        scopeLbl.setFont(FONT_SMALL);
        scopeLbl.setForeground(FG_SECONDARY);

        JPanel scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        scopeRow.setBackground(BG_PANEL);
        scopeRow.add(scopeLbl);
        scopeRow.add(chkScanImages);
        scopeRow.add(chkScanVideos);

        JPanel north = new JPanel(new BorderLayout(0, 2));
        north.setBackground(BG_PANEL);
        north.add(hint,     BorderLayout.NORTH);
        north.add(scopeRow, BorderLayout.SOUTH);

        p.add(north,  BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(btnBar, BorderLayout.SOUTH);
        return p;
    }

    // ── Options Panel ─────────────────────────────────────────────────────────
    //
    //  Layout (2-column, 2-row grid):
    //
    //   ┌───────────────────────────────┬──────────────────────────────────────┐
    //   │  Action (Move/Delete/None)    │  Report + Performance                │
    //   ├───────────────────────────────┼──────────────────────────────────────┤
    //   │  Rename                       │  Perceptual Video Matching           │
    //   └───────────────────────────────┴──────────────────────────────────────┘

    private JPanel buildOptionsPanel() {
        JPanel outer = sectionPanel("Options");
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));

        // ── Top row: Action | Report + Performance ────────────────────────────
        JPanel topRow = new JPanel();
        topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));
        topRow.setBackground(BG_PANEL);
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel actionCell = buildActionCell();
        JPanel reportCell = buildReportAndPerfCell();
        // Both cells fill the row equally
        actionCell.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        reportCell.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        topRow.add(actionCell);
        topRow.add(Box.createHorizontalStrut(6));
        topRow.add(reportCell);

        // ── Bottom row: Rename | Perceptual Video Matching ────────────────────
        JPanel botRow = new JPanel();
        botRow.setLayout(new BoxLayout(botRow, BoxLayout.X_AXIS));
        botRow.setBackground(BG_PANEL);
        botRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel renameCell     = buildRenameCell();
        JPanel perceptualCell = buildPerceptualCell();
        renameCell.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        perceptualCell.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        botRow.add(renameCell);
        botRow.add(Box.createHorizontalStrut(6));
        botRow.add(perceptualCell);

        outer.add(topRow);
        outer.add(Box.createVerticalStrut(6));
        outer.add(botRow);

        return outer;
    }

    /** Top-left: Move / Delete / None radio group + conditional folder row */
    private JPanel buildActionCell() {
        JPanel p = cellPanel();

        p.add(sectionHeading("Action"));
        p.add(Box.createVerticalStrut(4));

        // Style all three radio buttons
        for (JRadioButton rb : new JRadioButton[]{rbMove, rbDelete, rbNone}) {
            rb.setFont(FONT_BODY);
            rb.setForeground(FG_PRIMARY);
            rb.setBackground(BG_PANEL);
            rb.setFocusPainted(false);
            rb.setOpaque(true);
            p.add(wrapLeft(rb));
        }
        // Delete radio gets a red warning colour
        rbDelete.setForeground(ACCENT_RED);

        p.add(Box.createVerticalStrut(4));

        // Output-folder row — visible always, enabled only when rbMove is selected
        txtOutput.setColumns(8);
        JPanel folderRow = fieldRow("Folder:", txtOutput, btnOutput);
        p.add(folderRow);

        // Warning note for Delete — uses the system warning icon
        JLabel delNote = new JLabel("Delete is Permanent & can't be Undone.",
                UIManager.getIcon("OptionPane.warningIcon"), SwingConstants.LEFT);
        delNote.setFont(FONT_SMALL);
        delNote.setForeground(ACCENT_RED);
        delNote.setIconTextGap(6);
        // Scale down the system icon by wrapping it
        delNote.setIcon(UIIcons.warning(14));
        p.add(delNote);

        p.add(Box.createVerticalGlue());
        return p;
    }

    /** Top-right: Report + Performance (threads) */
    private JPanel buildReportAndPerfCell() {
        JPanel p = cellPanel();

        // ── Report ────────────────────────────────────────────────────────────
        p.add(sectionHeading("Report"));
        p.add(Box.createVerticalStrut(2));
        styleCheckBox(chkReport);
        p.add(wrapLeft(chkReport));
        p.add(Box.createVerticalStrut(2));
        txtReport.setColumns(8);
        p.add(fieldRow("File:", txtReport, btnReport));
        p.add(Box.createVerticalStrut(6));
        p.add(hairline());
        p.add(Box.createVerticalStrut(4));

        // ── Performance ───────────────────────────────────────────────────────
        p.add(sectionHeading("Performance"));
        p.add(Box.createVerticalStrut(2));

        JPanel thrRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        thrRow.setBackground(BG_PANEL);
        thrRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        thrRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel thrLbl = new JLabel("Threads:");
        thrLbl.setFont(FONT_BODY);
        thrLbl.setForeground(FG_PRIMARY);
        thrRow.add(thrLbl);

        cmbThreads.setFont(FONT_BODY);
        cmbThreads.setBackground(BG_CONTROL);
        cmbThreads.setForeground(FG_PRIMARY);
        cmbThreads.setOpaque(true);
        cmbThreads.setPreferredSize(new Dimension(120, 22));
        cmbThreads.setMaximumSize(new Dimension(120, 22));
        cmbThreads.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
        cmbThreads.setBackground(BG_CONTROL);
        cmbThreads.setForeground(FG_PRIMARY);
        cmbThreads.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));
        thrRow.add(cmbThreads);

        int cores = Runtime.getRuntime().availableProcessors();
        JLabel coreHint = new JLabel("(" + cores + " core" + (cores > 1 ? "s" : "") + ")");
        coreHint.setFont(FONT_SMALL);
        coreHint.setForeground(FG_HINT);
        thrRow.add(coreHint);
        p.add(thrRow);

        p.add(Box.createVerticalGlue());
        return p;
    }

    /** Bottom-right: Perceptual Video Matching controls */
    private JPanel buildPerceptualCell() {
        JPanel p = cellPanel();

        // ── Perceptual Video Matching (only enabled when Videos checkbox is on) ─
        p.add(sectionHeading("Perceptual Video Matching"));
        p.add(Box.createVerticalStrut(2));
        styleCheckBox(chkPerceptual);
        p.add(wrapLeft(chkPerceptual));

        JLabel percNote = new JLabel("  Detects re-encoded / container-changed copies");
        percNote.setFont(FONT_SMALL);
        percNote.setForeground(FG_SECONDARY);
        p.add(percNote);
        p.add(Box.createVerticalStrut(3));

        // Threshold row
        JPanel tRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tRow.setBackground(BG_PANEL);
        tRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        tRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel tLbl = new JLabel("Threshold:");
        tLbl.setFont(FONT_SMALL);
        tLbl.setForeground(FG_PRIMARY);
        spnThreshold.setFont(FONT_SMALL);
        spnThreshold.setPreferredSize(new Dimension(48, 22));
        JLabel tHint = new JLabel("  5=strict | 10=moderate | 15=lenient");
        tHint.setFont(FONT_SMALL);
        tHint.setForeground(FG_HINT);
        tRow.add(tLbl); tRow.add(spnThreshold); tRow.add(tHint);
        p.add(tRow);

        // Frames row
        JPanel fRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fRow.setBackground(BG_PANEL);
        fRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        fRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel fLbl = new JLabel("Frames:    ");
        fLbl.setFont(FONT_SMALL);
        fLbl.setForeground(FG_PRIMARY);
        spnFrames.setFont(FONT_SMALL);
        spnFrames.setPreferredSize(new Dimension(48, 22));
        JLabel fHint = new JLabel("  more = slower but more accurate");
        fHint.setFont(FONT_SMALL);
        fHint.setForeground(FG_HINT);
        fRow.add(fLbl); fRow.add(spnFrames); fRow.add(fHint);
        p.add(fRow);

        p.add(Box.createVerticalGlue());
        return p;
    }

    /** Bottom-left: Rename action */
    private JPanel buildRenameCell() {
        JPanel p = cellPanel();

        p.add(sectionHeading("Rename"));
        p.add(Box.createVerticalStrut(2));
        styleCheckBox(chkRename);
        p.add(wrapLeft(chkRename));

        JLabel rnNote = new JLabel("  photo.jpg  ->  photo_1.jpg, photo_2.jpg ...");
        rnNote.setFont(FONT_SMALL);
        rnNote.setForeground(FG_SECONDARY);
        p.add(rnNote);
        p.add(Box.createVerticalGlue());
        return p;
    }


    /** Creates a uniform inner cell panel (BoxLayout Y-axis, light border + padding). */
    private static JPanel cellPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_PANEL);
        p.setOpaque(true);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_LIGHT, 1),
                new EmptyBorder(4, 6, 4, 6)));
        return p;
    }

    // ── Progress Panel ────────────────────────────────────────────────────────

    private JPanel buildProgressPanel() {
        JPanel p = sectionPanel("Scan Progress");
        p.setLayout(new BorderLayout(0, 4));

        JPanel bars = new JPanel(new GridLayout(1, 4, 8, 0));
        bars.setBackground(BG_PANEL);
        bars.add(namedBar("Phase 1 - Collect",      pbCollect));
        bars.add(namedBar("Phase 2 - Partial Hash", pbPartial));
        bars.add(namedBar("Phase 3 - Full Hash",    pbFull));
        bars.add(namedBar("Phase 4 - Action",       pbAction));

        lblPhase.setFont(FONT_LABEL);
        lblPhase.setForeground(FG_SECONDARY);

        p.add(bars,     BorderLayout.CENTER);
        p.add(lblPhase, BorderLayout.SOUTH);
        return p;
    }

    // ── Log Panel ─────────────────────────────────────────────────────────────

    private JPanel buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(FONT_MONO);
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(204, 232, 204));
        logArea.setCaretColor(Color.WHITE);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(false);
        ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(0, 120));
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));

        JButton btnClear = new JButton("Clear");
        btnClear.setFont(FONT_SMALL);
        btnClear.setForeground(FG_SECONDARY);
        btnClear.addActionListener(e -> logArea.setText(""));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        top.setBackground(BG_PANEL);
        top.add(btnClear);

        JPanel p = sectionPanel("Log Console");
        p.setLayout(new BorderLayout(0, 1));
        p.add(top,    BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ── Bottom Bar ────────────────────────────────────────────────────────────

    private JPanel buildBottomBar() {
        btnStart.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnStart.setBackground(ACCENT_GREEN);
        btnStart.setForeground(Color.WHITE);
        btnStart.setOpaque(true);
        btnStart.setFocusPainted(false);
        btnStart.setBorderPainted(false);
        btnStart.setIconTextGap(6);
        btnStart.setPreferredSize(new Dimension(148, 30));
        btnStart.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

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

        lblStatus.setFont(FONT_BODY);
        lblStatus.setForeground(FG_SECONDARY);

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        p.setBackground(BG_TOOLBAR);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_LIGHT));
        p.add(btnStart);
        p.add(btnCancel);
        p.add(lblStatus);
        return p;
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    private void wireEvents() {
        // Perceptual matching: enable/disable threshold/frames spinners
        chkPerceptual.addActionListener(e -> updatePerceptualControls());

        // Scan-scope: prevent both being unchecked simultaneously
        // Also gate Perceptual matching on Videos being enabled
        chkScanImages.addActionListener(e -> {
            if (!chkScanImages.isSelected() && !chkScanVideos.isSelected())
                chkScanVideos.setSelected(true);
            updatePerceptualControls();
        });
        chkScanVideos.addActionListener(e -> {
            if (!chkScanVideos.isSelected() && !chkScanImages.isSelected())
                chkScanImages.setSelected(true);
            updatePerceptualControls();
        });

        // Move radio: enable folder field; no confirmation needed
        rbMove.addActionListener(e -> updateFolderRow());

        // None radio: disable folder field
        rbNone.addActionListener(e -> updateFolderRow());

        // Delete radio: confirm before selecting; if declined revert to previous
        rbDelete.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    """
                            Delete will permanently remove all duplicate files.
                            This action CANNOT be undone.
                            
                            Are you sure you want to select Delete?""",
                    "Confirm Delete", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                // Revert — restore Move (the default safe option)
                rbMove.setSelected(true);
            }
            updateFolderRow();
        });

        chkReport.addActionListener(e -> {
            txtReport.setEnabled(chkReport.isSelected());
            btnReport.setEnabled(chkReport.isSelected());
        });

        btnOutput.addActionListener(e -> {
            File chosen = chooseSingleFolder(txtOutput.getText());
            if (chosen != null) txtOutput.setText(chosen.getAbsolutePath());
        });
        btnReport.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Save Excel Report As...");
            fc.setSelectedFile(new File("duplicate_report.xlsx"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
                txtReport.setText(fc.getSelectedFile().getAbsolutePath());
        });

        btnStart.addActionListener(this::onStart);
        btnCancel.addActionListener(this::onCancel);

        // Set initial folder-row state (Move is selected by default)
        updateFolderRow();
        // Perceptual spinners start disabled (checkbox is unchecked by default)
        updatePerceptualControls();
    }

    /** Enable/disable the output-folder row depending on which radio is selected. */
    private void updateFolderRow() {
        boolean moveSelected = rbMove.isSelected();
        txtOutput.setEnabled(moveSelected);
        btnOutput.setEnabled(moveSelected);
    }

    /** Enable/disable perceptual-matching controls based on checkbox state and Videos scope. */
    private void updatePerceptualControls() {
        boolean videosOn = chkScanVideos.isSelected();
        // If Videos is disabled, force-uncheck perceptual and disable the whole section
        if (!videosOn) {
            chkPerceptual.setSelected(false);
        }
        chkPerceptual.setEnabled(videosOn);
        boolean on = videosOn && chkPerceptual.isSelected();
        spnThreshold.setEnabled(on);
        spnFrames.setEnabled(on);
    }

    private void hookLogAppender() {
        SwingLogAppender.setConsumer(msg -> SwingUtilities.invokeLater(() -> {
            logArea.append(msg);
            if (logArea.getDocument().getLength() > 200_000)
                logArea.setText(logArea.getText().substring(100_000));
        }));
    }

    // ── Scan logic ────────────────────────────────────────────────────────────

    private void onStart(ActionEvent e) {
        if (dirListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one input directory.",
                    "No Directories", JOptionPane.WARNING_MESSAGE);
            return;
        }

        DuplicateImageConfig cfg = getDuplicateImageConfig();

        List<Path> dirs = new ArrayList<>();
        for (int i = 0; i < dirListModel.size(); i++)
            dirs.add(Paths.get(dirListModel.get(i)));

        resetBars();
        logArea.setText("");
        setStatus("Scanning...", ACCENT_ORANGE);
        btnStart.setEnabled(false);
        btnCancel.setEnabled(true);

        Thread worker = getThread(cfg, dirs);
        runningThread.set(worker);
        worker.start();
    }

    private Thread getThread(DuplicateImageConfig cfg, List<Path> dirs) {
        Thread worker = new Thread(() -> {
            try {
                DuplicateImageFinder finder = new DuplicateImageFinderWithUiProgress(cfg, this);
                DuplicateImageFinder.ScanReport report = finder.run(dirs);
                SwingUtilities.invokeLater(() -> {
                    showReport(report);
                    setStatus("Completed - " + report.duplicateGroups + " Duplicate Group(s) Found.", ACCENT_GREEN);
                    finishUI();
                });
            } catch (InterruptedException ie) {
                SwingUtilities.invokeLater(() -> { setStatus("Scan Cancelled.", ACCENT_ORANGE); finishUI(); });
            } catch (Exception ex) {
                log.error("Scan Failed: {}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> { setStatus("Error: " + ex.getMessage(), ACCENT_RED); finishUI(); });
            }
        }, "scan-worker");
        worker.setDaemon(true);
        return worker;
    }

    private DuplicateImageConfig getDuplicateImageConfig() {
        DuplicateImageConfig cfg = new DuplicateImageConfig();
        cfg.setScanImages(chkScanImages.isSelected());
        cfg.setScanVideos(chkScanVideos.isSelected());
        cfg.setPerceptualVideoMatching(chkPerceptual.isSelected());
        cfg.setPerceptualHammingThreshold((int) spnThreshold.getValue());
        cfg.setVideoSampleFrames((int) spnFrames.getValue());
        cfg.setMoveDuplicates(rbMove.isSelected());
        if (rbMove.isSelected() && !txtOutput.getText().isBlank())
            cfg.setDuplicateOutputDir(txtOutput.getText().trim());
        cfg.setDeleteDuplicates(rbDelete.isSelected());
        cfg.setRenameDuplicates(chkRename.isSelected());
        cfg.setGenerateReport(chkReport.isSelected());
        if (chkReport.isSelected() && !txtReport.getText().isBlank())
            cfg.setReportPath(txtReport.getText().trim());
        cfg.setThreadCount(cmbThreads.getSelectedIndex() + 1);
        return cfg;
    }

    private void onCancel(ActionEvent e) {
        Thread t = runningThread.getAndSet(null);
        if (t != null) { t.interrupt(); log.info("Scan cancelled by User."); }
    }

    private void finishUI() {
        btnStart.setEnabled(true);
        btnCancel.setEnabled(false);
        runningThread.set(null);
    }

    // ── Scan Report Dialog ────────────────────────────────────────────────────

    private void showReport(DuplicateImageFinder.ScanReport r) {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);

        // ── Table data ────────────────────────────────────────────────────────
        Object[][] rows = {
            {"Files Scanned",    nf.format(r.filesScanned)},
            {"Duplicate Groups", nf.format(r.duplicateGroups)},
            {"Duplicates Found", nf.format(r.duplicatesFound)},
            {"Files Moved",      nf.format(r.filesMoved)},
            {"Files Renamed",    nf.format(r.filesRenamed)},
            {"Files Deleted",    nf.format(r.filesDeleted)},
            {"Errors",           nf.format(r.errors)},
        };
        JTable table = getJTable(rows);

        // ── Header styling ────────────────────────────────────────────────────
        javax.swing.table.JTableHeader header = table.getTableHeader();
        header.setFont(FONT_SECTION);
        header.setBackground(BG_HEADER);
        header.setForeground(FG_HEADER);
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);

        // ── Column widths ─────────────────────────────────────────────────────
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);

        // ── Cell renderer: alternating rows + red "Errors" row ───────────────
        DefaultTableCellRenderer cellRenderer = getDefaultTableCellRenderer(r, rows);
        table.getColumnModel().getColumn(0).setCellRenderer(cellRenderer);
        table.getColumnModel().getColumn(1).setCellRenderer(cellRenderer);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_LIGHT));
        scroll.setPreferredSize(new Dimension(320, table.getRowHeight() * rows.length
                + header.getPreferredSize().height + 4));

        // ── Outer panel ───────────────────────────────────────────────────────
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(BG_PANEL);
        panel.setBorder(new EmptyBorder(14, 18, 14, 18));

        JLabel title = new JLabel("Scan Complete");
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(ACCENT_BLUE);
        title.setIcon(UIIcons.play(ACCENT_BLUE, 10)); // small indicator dot
        title.setIconTextGap(8);

        panel.add(title,  BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.DEFAULT_OPTION);
        JDialog dlg = pane.createDialog(this, "Scan Report");
        dlg.setBackground(BG_PANEL);
        dlg.setVisible(true);
    }

    private DefaultTableCellRenderer getDefaultTableCellRenderer(DuplicateImageFinder.ScanReport r, Object[][] rows) {
        final int errorsRowIdx = rows.length - 1;
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean selected,
                    boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                setBorder(new EmptyBorder(2, 8, 2, 8));
                if (row == errorsRowIdx && r.errors > 0) {
                    setBackground(new Color(255, 235, 233));
                    setForeground(ACCENT_RED);
                    setFont(col == 1 ? new Font("Segoe UI", Font.BOLD, 12) : FONT_BODY);
                } else {
                    setBackground(row % 2 == 0 ? BG_CONTROL : BG_ROW_ALT);
                    setForeground(col == 1 ? ACCENT_BLUE : FG_PRIMARY);
                    setFont(col == 1 ? new Font("Segoe UI", Font.BOLD, 12) : FONT_BODY);
                }
                return this;
            }
        };
    }

    private static JTable getJTable(Object[][] rows) {
        String[] cols = {"Metric", "Value"};

        // Non-editable table model
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
        table.setForeground(FG_PRIMARY);
        return table;
    }

    // ── Progress bar update methods ──────────────────────────────────────────

    void setPhase(String text) {
        SwingUtilities.invokeLater(() -> lblPhase.setText("  " + text));
    }

    void initBar(JProgressBar bar, long max) {
        SwingUtilities.invokeLater(() -> {
            if (max < 0) {
                bar.setIndeterminate(true);
                bar.setString("Scanning...");
            } else {
                bar.setIndeterminate(false);
                bar.setMaximum((int) Math.min(max, Integer.MAX_VALUE));
                bar.setValue(0);
                bar.setString("0 / " + NumberFormat.getInstance().format(max));
            }
        });
    }

    void stepBar(JProgressBar bar, long current, long total) {
        SwingUtilities.invokeLater(() -> {
            bar.setValue((int) Math.min(current, Integer.MAX_VALUE));
            if (total > 0) {
                int pct = (int) (current * 100L / total);
                bar.setString(pct + "%  "
                        + NumberFormat.getInstance().format(current)
                        + " / " + NumberFormat.getInstance().format(total));
            } else {
                bar.setString(NumberFormat.getInstance().format(current));
            }
        });
    }

    void doneBar(JProgressBar bar) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(false);
            bar.setValue(bar.getMaximum());
            bar.setString("Done");
        });
    }

    JProgressBar collectBar() { return pbCollect; }
    JProgressBar partialBar() { return pbPartial; }
    JProgressBar fullBar()    { return pbFull;    }
    JProgressBar actionBar()  { return pbAction;  }

    private void resetBars() {
        for (JProgressBar b : new JProgressBar[]{pbCollect, pbPartial, pbFull, pbAction}) {
            b.setValue(0); b.setMaximum(100);
            b.setIndeterminate(false); b.setString("Waiting...");
        }
        lblPhase.setText("  Ready");
    }

    // ── File chooser ──────────────────────────────────────────────────────────

    private void chooseDirectories() {
        FolderPickerDialog picker = new FolderPickerDialog(
                this, "Select Folders to Scan", null, true);
        List<File> chosen = picker.showAndGet();
        for (File f : chosen)
            if (!dirListModel.contains(f.getAbsolutePath()))
                dirListModel.addElement(f.getAbsolutePath());
    }

    private File chooseSingleFolder(String currentPath) {
        File start = (currentPath != null && !currentPath.isBlank())
                ? new File(currentPath) : null;
        FolderPickerDialog picker = new FolderPickerDialog(this, "Select Output Folder for Duplicates", start, false);
        List<File> chosen = picker.showAndGet();
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    // ── Component factories ───────────────────────────────────────────────────

    private void setStatus(String text, Color color) {
        lblStatus.setText("  " + text);
        lblStatus.setForeground(color);
    }

    private static JPanel sectionPanel(String title) {
        JPanel p = new JPanel();
        p.setBackground(BG_PANEL);
        p.setOpaque(true);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER_LIGHT, 1), title);
        tb.setTitleColor(ACCENT_BLUE);
        tb.setTitleFont(FONT_SECTION);
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(4, 6, 4, 6)));
        return p;
    }

    private static JLabel sectionHeading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_SECTION);
        l.setForeground(FG_PRIMARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static void styleCheckBox(JCheckBox cb) {
        cb.setFont(FONT_BODY);
        cb.setForeground(FG_PRIMARY);
        cb.setBackground(BG_PANEL);
        cb.setFocusPainted(false);
        cb.setOpaque(true);
    }

    private static JPanel wrapLeft(JComponent c) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setBackground(BG_PANEL);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 2));
        row.add(c);
        return row;
    }

    /** A 1-pixel horizontal line used as a visual divider inside cell panels. */
    private static JSeparator hairline() {
        JSeparator s = new JSeparator();
        s.setForeground(BORDER_LIGHT);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    private static JTextField styledField(String text) {
        JTextField f = new JTextField(text);
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

    static JButton accentButton(String text, Color bg) {
        return accentButton(text, bg, null);
    }

    static JButton accentButton(String text, Color bg, Icon icon) {
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

    private static JProgressBar makeBar(Color color) {
        JProgressBar b = new JProgressBar(0, 100);
        b.setStringPainted(true);
        b.setString("Waiting...");
        b.setForeground(color);
        b.setBackground(new Color(220, 220, 220));
        b.setFont(FONT_SMALL);
        b.setPreferredSize(new Dimension(0, 18));
        return b;
    }

    private static JPanel namedBar(String label, JProgressBar bar) {
        JPanel p = new JPanel(new BorderLayout(0, 1));
        p.setBackground(BG_PANEL);
        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(FG_PRIMARY);
        p.add(lbl, BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        return p;
    }

    private static JPanel fieldRow(String labelText, JTextField field, JButton btn) {
        JPanel row = new JPanel(new BorderLayout(3, 0));
        row.setBackground(BG_PANEL);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(FG_SECONDARY);
        lbl.setPreferredSize(new Dimension(35, 24));
        row.add(lbl,   BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.add(btn,   BorderLayout.EAST);
        return row;
    }

    private static Icon iconLabel() {
        return UIIcons.dot(Color.WHITE, ACCENT_BLUE);
    }

    // icon factories delegated to UIIcons — kept as thin wrappers for backward compat
    static Icon makePlayIcon(Color c, int sz)  { return UIIcons.play(c, sz);    }
    static Icon makeStopIcon(Color c, int sz)  { return UIIcons.stop(c, sz);    }
    static Icon makeWarningIcon(int sz)        { return UIIcons.warning(sz);    }
}
