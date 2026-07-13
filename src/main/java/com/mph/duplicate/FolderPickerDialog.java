package com.mph.duplicate;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Modern browser-style folder picker dialog.
 *
 * <p>Looks and behaves like the folder-select dialog in Chrome/Edge:
 * <ul>
 *   <li>Breadcrumb path bar at the top</li>
 *   <li>Expandable/collapsible folder tree in the centre</li>
 *   <li>Inline "type path" field with Go button</li>
 *   <li>New Folder button</li>
 *   <li>Select / Cancel buttons</li>
 *   <li>Supports multi-select (Ctrl+click / Shift+click)</li>
 * </ul>
 */
public class FolderPickerDialog extends JDialog {

    // ── Colours (reuse from DuplicateImageFinderUI) ───────────────────────────
    private static final Color BG        = new Color(248, 248, 248);
    private static final Color BG_TREE   = Color.WHITE;
    private static final Color BG_HEADER = new Color(0, 114, 198);
    private static final Color BG_CRUMB  = new Color(232, 240, 255);
    private static final Color FG_CRUMB  = new Color(0, 70, 140);
    private static final Color FG_TEXT   = new Color(15, 15, 15);
    private static final Color FG_HINT   = new Color(100, 100, 100);
    private static final Color ACCENT    = new Color(0, 114, 198);
    private static final Color SEL_BG    = new Color(205, 225, 255);
    private static final Color SEL_FG    = new Color(0, 50, 120);
    private static final Color BORDER    = new Color(200, 200, 200);

    private static final Font FONT_TITLE  = new Font("Segoe UI", Font.BOLD,  14);
    private static final Font FONT_BODY   = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_SMALL  = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font FONT_CRUMB  = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_MONO   = new Font("Consolas",  Font.PLAIN, 12);

    // ── State ─────────────────────────────────────────────────────────────────
    private final boolean multiSelect;
    private final List<File> selectedFolders = new ArrayList<>();

    // ── Widgets ───────────────────────────────────────────────────────────────
    private final JTree          tree;
    private final DefaultTreeModel treeModel;
    private final JTextField     txtPath        = new JTextField();
    private final JPanel         crumbPanel     = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JButton        btnSelect;

    // ── Sentinel to detect "already loaded" ──────────────────────────────────
    private static final String PLACEHOLDER = "Loading\u2026";

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * @param owner       parent frame
     * @param title       dialog title
     * @param startDir    directory to open initially (null = home)
     * @param multiSelect allow selecting multiple folders with Ctrl/Shift
     */
    public FolderPickerDialog(Frame owner, String title, File startDir, boolean multiSelect) {
        super(owner, title, true);
        this.multiSelect = multiSelect;
        this.btnSelect   = new JButton(multiSelect ? "Select Folder(s)" : "Select Folder");

        // ── Build tree model ──────────────────────────────────────────────────
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new FileNode(null)); // virtual root
        treeModel = new DefaultTreeModel(root);

        // Add drives / file-system roots
        for (File r : File.listRoots()) {
            DefaultMutableTreeNode driveNode = new DefaultMutableTreeNode(new FileNode(r));
            driveNode.add(new DefaultMutableTreeNode(PLACEHOLDER)); // placeholder
            root.add(driveNode);
        }

        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setBackground(BG_TREE);
        tree.setFont(FONT_BODY);
        tree.setRowHeight(28);
        tree.getSelectionModel().setSelectionMode(
                multiSelect
                        ? TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
                        : TreeSelectionModel.SINGLE_TREE_SELECTION);

        // Custom renderer: folder icon + name
        tree.setCellRenderer(new FolderTreeCellRenderer());

        // Lazy-load children on expand
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) e.getPath().getLastPathComponent();
                loadChildren(node);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent e) {}
        });

        // Update breadcrumb + path field on selection change
        tree.addTreeSelectionListener(evt -> {
            File f = getFirstSelectedFile();
            if (f != null) {
                updateBreadcrumb(f);
                txtPath.setText(f.getAbsolutePath());
                btnSelect.setEnabled(true);
            } else {
                // A node is selected but it might be a placeholder – still allow
                // selecting if the typed path field has a valid directory
                String typed = txtPath.getText().trim();
                btnSelect.setEnabled(!typed.isEmpty() && new File(typed).isDirectory());
            }
        });

        // Single-click selects; double-click also confirms (closes dialog)
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int row = tree.getRowForLocation(e.getX(), e.getY());
                if (row < 0) return;
                if (e.getClickCount() == 2) {
                    // Expand/collapse on double-click
                    if (tree.isExpanded(row)) {
                        tree.collapseRow(row);
                    } else {
                        tree.expandRow(row);
                    }
                    // Also confirm selection if a valid folder is selected
                    if (getFirstSelectedFile() != null) {
                        onSelect();
                    }
                }
            }
        });

        // Enter key confirms selection
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "select");
        tree.getActionMap().put("select", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (getFirstSelectedFile() != null) onSelect();
            }
        });

        buildUI();

        // Navigate to startDir or home
        File start = (startDir != null && startDir.isDirectory())
                ? startDir
                : new File(System.getProperty("user.home"));
        SwingUtilities.invokeLater(() -> navigateTo(start));

        setSize(620, 560);
        setMinimumSize(new Dimension(500, 420));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG);
        setContentPane(root);

        root.add(buildHeader(),   BorderLayout.NORTH);
        root.add(buildTreeArea(), BorderLayout.CENTER);
        root.add(buildFooter(),   BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(BG_HEADER);
        p.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel title = new JLabel(multiSelect ? "  Select Folders" : "  Select a Folder");
        title.setFont(FONT_TITLE);
        title.setForeground(Color.WHITE);
        title.setIcon(ICON_HEADER_FOLDER);
        p.add(title, BorderLayout.NORTH);

        // Breadcrumb bar
        crumbPanel.setBackground(BG_CRUMB);
        crumbPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 210, 255)),
                new EmptyBorder(2, 4, 2, 4)));
        p.add(crumbPanel, BorderLayout.SOUTH);
        return p;
    }

    private JScrollPane buildTreeArea() {
        JScrollPane sp = new JScrollPane(tree);
        sp.setBackground(BG_TREE);
        sp.getViewport().setBackground(BG_TREE);
        sp.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        return sp;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(8, 12, 10, 12));

        // Path entry row
        JLabel pathLbl = new JLabel("Folder path:");
        pathLbl.setFont(FONT_SMALL);
        pathLbl.setForeground(FG_HINT);

        txtPath.setFont(FONT_MONO);
        txtPath.setBackground(Color.WHITE);
        txtPath.setForeground(FG_TEXT);
        txtPath.setCaretColor(FG_TEXT);
        txtPath.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(3, 6, 3, 6)));
        txtPath.addActionListener(e -> goToTypedPath());
        // Enable Select button as user types a valid path
        txtPath.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                String txt = txtPath.getText().trim();
                boolean valid = !txt.isEmpty() && new File(txt).isDirectory();
                btnSelect.setEnabled(valid || getFirstSelectedFile() != null);
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { update(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { update(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });

        JButton btnGo = new JButton("Go");
        btnGo.setFont(FONT_SMALL);
        btnGo.setBackground(ACCENT);
        btnGo.setForeground(Color.WHITE);
        btnGo.setOpaque(true);
        btnGo.setBorderPainted(false);
        btnGo.setFocusPainted(false);
        btnGo.setPreferredSize(new Dimension(50, 28));
        btnGo.addActionListener(e -> goToTypedPath());

        JButton btnNewFolder = new JButton("New Folder");
        btnNewFolder.setFont(FONT_SMALL);
        btnNewFolder.setBackground(new Color(220, 220, 220));
        btnNewFolder.setForeground(FG_TEXT);
        btnNewFolder.setFocusPainted(false);
        btnNewFolder.setBorder(BorderFactory.createLineBorder(BORDER));
        btnNewFolder.addActionListener(e -> createNewFolder());

        JPanel pathRow = new JPanel(new BorderLayout(4, 0));
        pathRow.setBackground(BG);
        pathRow.add(pathLbl,   BorderLayout.WEST);
        pathRow.add(txtPath,   BorderLayout.CENTER);
        pathRow.add(btnGo,     BorderLayout.EAST);

        // Button row
        btnSelect.setFont(FONT_BODY);
        btnSelect.setBackground(ACCENT);
        btnSelect.setForeground(Color.WHITE);
        btnSelect.setOpaque(true);
        btnSelect.setBorderPainted(false);
        btnSelect.setFocusPainted(false);
        btnSelect.setPreferredSize(new Dimension(150, 34));
        btnSelect.setEnabled(true);   // enabled by default; navigateTo will confirm selection
        btnSelect.addActionListener(e -> onSelect());

        JButton btnCancel = new JButton("Cancel");
        btnCancel.setFont(FONT_BODY);
        btnCancel.setBackground(new Color(220, 220, 220));
        btnCancel.setForeground(FG_TEXT);
        btnCancel.setFocusPainted(false);
        btnCancel.setBorder(BorderFactory.createLineBorder(BORDER));
        btnCancel.setPreferredSize(new Dimension(90, 34));
        btnCancel.addActionListener(e -> { selectedFolders.clear(); dispose(); });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnRow.setBackground(BG);
        if (multiSelect) {
            JLabel hint = new JLabel("Ctrl+click or Shift+click to select multiple folders");
            hint.setFont(FONT_SMALL);
            hint.setForeground(FG_HINT);
            btnRow.add(hint);
        }
        btnRow.add(btnNewFolder);
        btnRow.add(btnCancel);
        btnRow.add(btnSelect);

        p.add(pathRow, BorderLayout.CENTER);
        p.add(btnRow,  BorderLayout.SOUTH);
        return p;
    }

    // ── Tree helpers ──────────────────────────────────────────────────────────

    /** Lazily loads child directories for a node the first time it is expanded. */
    private void loadChildren(DefaultMutableTreeNode node) {
        // Check if already loaded: if first child is a real FileNode, skip
        if (node.getChildCount() > 0) {
            Object firstChild = ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject();
            if (firstChild instanceof FileNode) return; // already loaded with real children
            // Otherwise it's the PLACEHOLDER string — remove it and load real children
            node.removeAllChildren();
        }

        Object userObj = node.getUserObject();
        if (!(userObj instanceof FileNode fn)) return;
        File dir = fn.file;
        if (dir == null) return;

        // List all subdirectories (include hidden on Windows so system folders appear)
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null || subs.length == 0) {
            // No children — fire reload so the expand arrow disappears
            treeModel.nodeStructureChanged(node);
            return;
        }
        Arrays.sort(subs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        int[] indices = new int[subs.length];
        for (int i = 0; i < subs.length; i++) {
            File sub = subs[i];
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(new FileNode(sub));
            // Add placeholder so the expand arrow appears if subdirectory has children
            if (hasSubDirs(sub)) child.add(new DefaultMutableTreeNode(PLACEHOLDER));
            node.add(child);
            indices[i] = i;
        }
        treeModel.nodesWereInserted(node, indices);
    }

    private static boolean hasSubDirs(File dir) {
        try {
            File[] kids = dir.listFiles(File::isDirectory);
            return kids != null && kids.length > 0;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Expands the tree to reveal and select the given directory. */
    private void navigateTo(File target) {
        if (target == null || !target.isDirectory()) return;

        // Canonicalize to resolve symlinks / relative paths
        try { target = target.getCanonicalFile(); } catch (IOException ignored) { target = target.getAbsoluteFile(); }

        // Build ancestor chain from filesystem root down to target
        List<File> chain = new ArrayList<>();
        File cur = target;
        while (cur != null) { chain.add(0, cur); cur = cur.getParentFile(); }

        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) treeModel.getRoot();

        // Find the matching drive/root node
        DefaultMutableTreeNode current = null;
        File driveFile = chain.get(0);
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode driveNode = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            Object uo = driveNode.getUserObject();
            if (!(uo instanceof FileNode fn)) continue;
            if (fn.file == null) continue;
            // Compare canonical paths for robustness
            try {
                if (fn.file.getCanonicalPath().equalsIgnoreCase(driveFile.getCanonicalPath())) {
                    current = driveNode;
                    break;
                }
            } catch (IOException e) {
                if (fn.file.getAbsolutePath().equalsIgnoreCase(driveFile.getAbsolutePath())) {
                    current = driveNode;
                    break;
                }
            }
        }
        if (current == null) {
            // Drive not found — just update the text fields and return
            txtPath.setText(target.getAbsolutePath());
            updateBreadcrumb(target);
            return;
        }

        // Walk down the chain, loading children at each level
        for (int depth = 1; depth < chain.size(); depth++) {
            loadChildren(current);
            File next = chain.get(depth);
            DefaultMutableTreeNode found = null;
            for (int i = 0; i < current.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) current.getChildAt(i);
                Object uo = child.getUserObject();
                if (!(uo instanceof FileNode cfn)) continue;
                if (cfn.file == null) continue;
                try {
                    if (cfn.file.getCanonicalPath().equalsIgnoreCase(next.getCanonicalPath())) {
                        found = child;
                        break;
                    }
                } catch (IOException e) {
                    if (cfn.file.getAbsolutePath().equalsIgnoreCase(next.getAbsolutePath())) {
                        found = child;
                        break;
                    }
                }
            }
            if (found == null) break; // Can't navigate further — stop at current level
            current = found;
        }

        // Select, expand, and scroll to the node
        final DefaultMutableTreeNode finalNode = current;
        TreePath tp = new TreePath(finalNode.getPath());
        tree.setSelectionPath(tp);
        tree.expandPath(tp);
        tree.scrollPathToVisible(tp);
        updateBreadcrumb(target);
        txtPath.setText(target.getAbsolutePath());
        btnSelect.setEnabled(true);
    }

    /** Builds the clickable breadcrumb from a file path. */
    private void updateBreadcrumb(File file) {
        crumbPanel.removeAll();

        List<File> chain = new ArrayList<>();
        File cur = file;
        while (cur != null) { chain.add(0, cur); cur = cur.getParentFile(); }

        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            String label = (i == 0) ? f.getAbsolutePath() : f.getName();
            if (label == null || label.isEmpty()) label = f.getAbsolutePath();

            JButton crumb = new JButton(label);
            crumb.setFont(FONT_CRUMB);
            crumb.setForeground(FG_CRUMB);
            crumb.setBackground(BG_CRUMB);
            crumb.setBorderPainted(false);
            crumb.setFocusPainted(false);
            crumb.setOpaque(false);
            crumb.setContentAreaFilled(false);
            crumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final File nav = f;
            crumb.addActionListener(e -> navigateTo(nav));
            crumbPanel.add(crumb);

            if (i < chain.size() - 1) {
                JLabel sep = new JLabel("›");
                sep.setFont(FONT_CRUMB);
                sep.setForeground(new Color(120, 150, 200));
                crumbPanel.add(sep);
            }
        }
        crumbPanel.revalidate();
        crumbPanel.repaint();
    }

    private void goToTypedPath() {
        String txt = txtPath.getText().trim();
        if (txt.isEmpty()) return;
        File f = new File(txt);
        if (f.isDirectory()) {
            navigateTo(f);
        } else {
            txtPath.setBackground(new Color(255, 230, 230));
            Timer t = new Timer(600, e -> txtPath.setBackground(Color.WHITE));
            t.setRepeats(false);
            t.start();
        }
    }

    private void createNewFolder() {
        File parent = getFirstSelectedFile();
        if (parent == null) {
            // Use typed path if available
            String typed = txtPath.getText().trim();
            if (!typed.isEmpty()) {
                File f = new File(typed);
                if (f.isDirectory()) parent = f;
            }
        }
        if (parent == null) {
            JOptionPane.showMessageDialog(this, "Please select a parent folder first.",
                    "New Folder", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New folder name:", "New Folder",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) return;
        File newDir = new File(parent, name.trim());
        if (newDir.mkdir()) {
            navigateTo(newDir);
        } else {
            JOptionPane.showMessageDialog(this, "Could not create folder: " + newDir,
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onSelect() {
        selectedFolders.clear();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath tp : paths) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
                if (node.getUserObject() instanceof FileNode fn && fn.file != null)
                    selectedFolders.add(fn.file);
            }
        }
        if (selectedFolders.isEmpty()) {
            // fallback: typed path
            String typed = txtPath.getText().trim();
            if (!typed.isEmpty()) {
                File f = new File(typed);
                if (f.isDirectory()) selectedFolders.add(f);
            }
        }
        if (!selectedFolders.isEmpty()) {
            dispose();
        } else {
            JOptionPane.showMessageDialog(this,
                    "Please select a folder from the tree, or type a valid folder path.",
                    "No Folder Selected", JOptionPane.WARNING_MESSAGE);
        }
    }

    private File getFirstSelectedFile() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
        Object uo = node.getUserObject();
        return (uo instanceof FileNode fn && fn.file != null) ? fn.file : null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Shows the dialog and returns selected folders.
     * Returns an empty list if the user cancelled.
     */
    public List<File> showAndGet() {
        setVisible(true);    // blocks until disposed
        return new ArrayList<>(selectedFolders);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Wraps a {@link File} for use as a tree node user-object. */
    private record FileNode(File file) {
        @Override public String toString() {
            if (file == null) return "Computer";
            String name = file.getName();
            return name.isEmpty() ? file.getAbsolutePath() : name;
        }
    }

    // ── Custom icons (painted, no external resources needed) ─────────────────

    /** 16×16 hard-drive icon: silver box with a green activity LED. */
    private static final Icon ICON_DRIVE = new Icon() {
        @Override public int getIconWidth()  { return 18; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Drive body
            g2.setColor(new Color(180, 180, 190));
            g2.fillRoundRect(x + 1, y + 3, 16, 12, 3, 3);
            g2.setColor(new Color(130, 130, 140));
            g2.drawRoundRect(x + 1, y + 3, 16, 12, 3, 3);
            // Top slot line
            g2.setColor(new Color(210, 210, 220));
            g2.fillRect(x + 3, y + 5, 10, 2);
            // Activity LED (green)
            g2.setColor(new Color(0, 200, 80));
            g2.fillOval(x + 13, y + 10, 3, 3);
            // Platter circle
            g2.setColor(new Color(150, 150, 160));
            g2.drawOval(x + 4, y + 8, 6, 5);
            g2.dispose();
        }
    };

    /** 16×16 closed-folder icon: Windows Explorer yellow folder. */
    private static final Icon ICON_FOLDER_CLOSED = new Icon() {
        @Override public int getIconWidth()  { return 18; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Folder back (darker amber)
            g2.setColor(new Color(210, 160, 40));
            g2.fillRoundRect(x + 1, y + 5, 16, 10, 2, 2);
            // Tab on top-left
            g2.fillRoundRect(x + 1, y + 3, 7, 4, 2, 2);
            // Folder front (bright yellow)
            g2.setColor(new Color(255, 200, 60));
            g2.fillRoundRect(x + 1, y + 7, 16, 8, 2, 2);
            // Outline
            g2.setColor(new Color(180, 130, 20));
            g2.drawRoundRect(x + 1, y + 5, 16, 10, 2, 2);
            g2.drawRoundRect(x + 1, y + 3, 7, 4, 2, 2);
            g2.dispose();
        }
    };

    /** 16×16 open-folder icon: Windows Explorer open yellow folder. */
    private static final Icon ICON_FOLDER_OPEN = new Icon() {
        @Override public int getIconWidth()  { return 18; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Folder back
            g2.setColor(new Color(210, 160, 40));
            g2.fillRoundRect(x + 1, y + 5, 16, 10, 2, 2);
            // Tab on top-left
            g2.fillRoundRect(x + 1, y + 3, 7, 4, 2, 2);
            // Open flap (lighter, tilted look via polygon)
            g2.setColor(new Color(255, 220, 100));
            int[] px = { x+1, x+17, x+14, x+1 };
            int[] py = { y+7,  y+7,  y+15, y+15 };
            g2.fillPolygon(px, py, 4);
            // Contents visible inside
            g2.setColor(new Color(255, 240, 180));
            g2.fillRect(x + 4, y + 10, 8, 3);
            // Outline
            g2.setColor(new Color(180, 130, 20));
            g2.drawRoundRect(x + 1, y + 5, 16, 10, 2, 2);
            g2.drawRoundRect(x + 1, y + 3, 7, 4, 2, 2);
            g2.dispose();
        }
    };

    /** 14×14 folder icon used in the dialog header title. */
    private static final Icon ICON_HEADER_FOLDER = new Icon() {
        @Override public int getIconWidth()  { return 20; }
        @Override public int getIconHeight() { return 20; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(255, 220, 80));
            g2.fillRoundRect(x + 1, y + 5, 18, 12, 3, 3);
            g2.fillRoundRect(x + 1, y + 3, 8, 5, 3, 3);
            g2.setColor(new Color(255, 240, 160));
            g2.fillRoundRect(x + 1, y + 7, 18, 10, 3, 3);
            g2.setColor(new Color(200, 150, 20));
            g2.drawRoundRect(x + 1, y + 5, 18, 12, 3, 3);
            g2.drawRoundRect(x + 1, y + 3, 8, 5, 3, 3);
            g2.dispose();
        }
    };

    /** Renders tree nodes with folder icons and styled text. */
    private static class FolderTreeCellRenderer extends DefaultTreeCellRenderer {

        FolderTreeCellRenderer() {
            setBackgroundSelectionColor(SEL_BG);
            setTextSelectionColor(SEL_FG);
            setBackgroundNonSelectionColor(BG_TREE);
            setTextNonSelectionColor(FG_TEXT);
            setBorderSelectionColor(ACCENT);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object uo = node.getUserObject();

            if (uo instanceof FileNode fn) {
                File f = fn.file;
                if (f == null) {
                    setText(" Computer");
                    setIcon(ICON_DRIVE);
                } else if (f.getParent() == null) {
                    // Drive root  e.g. "C:\"
                    setText(" " + f.getAbsolutePath());
                    setIcon(ICON_DRIVE);
                } else {
                    setText(" " + f.getName());
                    setIcon(expanded ? ICON_FOLDER_OPEN : ICON_FOLDER_CLOSED);
                }
                setFont(FONT_BODY);
            } else {
                // Placeholder "Loading…"
                setText("   " + uo);
                setFont(new Font("Segoe UI", Font.ITALIC, 11));
                setForeground(FG_HINT);
                setIcon(null);
            }
            return this;
        }
    }
}
