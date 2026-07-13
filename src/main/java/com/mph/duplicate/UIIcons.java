package com.mph.duplicate;

import javax.swing.Icon;
import java.awt.*;

/**
 * Shared painted-icon factory for the Swing UI.
 * All icons are rendered with Java2D (anti-aliased) so they look correct
 * on every platform regardless of font/glyph support.
 */
final class UIIcons {

    private UIIcons() {}

    /**
     * Folder icon — open-top trapezoid with a tab nub, like a classic file-folder.
     *
     * @param color fill colour
     * @param size  width in pixels (height is ~0.75 * size)
     */
    static Icon folder(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int h = size * 3 / 4;
                int tab = size / 3;
                int tabH = size / 5;
                // Body
                g2.setColor(color);
                g2.fillRoundRect(x, y + tabH, size, h - tabH, 3, 3);
                // Tab (top-left nub)
                g2.fillRoundRect(x, y, tab, tabH + 2, 2, 2);
                // Subtle darker border
                g2.setColor(color.darker());
                g2.drawRoundRect(x, y + tabH, size - 1, h - tabH - 1, 3, 3);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size * 3 / 4; }
        };
    }

    /**
     * Document / file page icon — rectangle with a folded top-right corner.
     *
     * @param color fill colour
     * @param size  width in pixels
     */
    static Icon file(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int w = size * 3 / 4;
                int h = size;
                int fold = size / 4;
                // Page body (minus folded corner)
                int[] px = { x, x, x + w - fold, x + w, x + w };
                int[] py = { y, y + h, y + h,    y + fold, y };
                g2.setColor(color);
                g2.fillPolygon(px, py, 5);
                // Folded corner triangle
                g2.setColor(color.darker());
                g2.fillPolygon(new int[]{ x + w - fold, x + w - fold, x + w },
                               new int[]{ y,            y + fold,     y + fold }, 3);
                // Outline
                g2.setColor(color.darker());
                g2.drawPolygon(px, py, 5);
                // Fold crease
                g2.drawLine(x + w - fold, y, x + w - fold, y + fold);
                g2.drawLine(x + w - fold, y + fold, x + w, y + fold);
                // Text lines
                g2.setColor(color.darker().darker());
                int lx1 = x + 3, lx2 = x + w - 5;
                g2.drawLine(lx1, y + h * 2 / 5, lx2, y + h * 2 / 5);
                g2.drawLine(lx1, y + h * 3 / 5, lx2 - 4, y + h * 3 / 5);
                g2.drawLine(lx1, y + h * 4 / 5, lx2 - 8, y + h * 4 / 5);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Check-mark (tick) icon — bold V-shaped stroke.
     *
     * @param color stroke colour
     * @param size  width and height in pixels
     */
    static Icon check(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(Math.max(1.5f, size / 5f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Short leg: top-left to mid-bottom
                int mx = x + size / 3;
                int my = y + size - size / 4;
                g2.drawLine(x + size / 8, y + size / 2, mx, my);
                // Long leg: mid-bottom to top-right
                g2.drawLine(mx, my, x + size - size / 8, y + size / 5);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Minus (remove) icon — horizontal bar.
     *
     * @param color fill colour
     * @param size  width and height in pixels
     */
    static Icon minus(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(color);
                int barH = Math.max(2, size / 5);
                g2.fillRect(x, y + (size - barH) / 2, size, barH);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Plus (add) icon — horizontal + vertical bars.
     *
     * @param color fill colour
     * @param size  width and height in pixels
     */
    static Icon plus(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(color);
                int barH = Math.max(2, size / 5);
                g2.fillRect(x, y + (size - barH) / 2, size, barH);           // horizontal
                g2.fillRect(x + (size - barH) / 2, y, barH, size);           // vertical
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Right-pointing arrow icon.
     *
     * @param color fill colour
     * @param size  width and height in pixels
     */
    static Icon arrowRight(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                int mid = y + size / 2;
                int tipX = x + size;
                // Arrow shaft
                int shaftH = Math.max(2, size / 5);
                g2.fillRect(x, mid - shaftH / 2, size * 2 / 3, shaftH);
                // Arrowhead triangle
                int[] xs = { x + size / 2, tipX, x + size / 2 };
                int[] ys = { y,            mid,  y + size    };
                g2.fillPolygon(xs, ys, 3);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Horizontal ellipsis icon — three small dots.
     *
     * @param color dot colour
     * @param size  overall width (height = size / 3)
     */
    static Icon ellipsis(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                int r = Math.max(2, size / 5);
                int cy = y + r / 2;
                int gap = (size - 3 * r) / 2;
                g2.fillOval(x,              cy, r, r);
                g2.fillOval(x + r + gap,    cy, r, r);
                g2.fillOval(x + 2*r + 2*gap, cy, r, r);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size / 3 + 2; }
        };
    }

    /**
     * Filled right-pointing triangle (play button).
     *
     * @param color fill colour
     * @param size  width and height in pixels
     */
    static Icon play(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                int[] xs = { x,        x,            x + size };
                int[] ys = { y, y + size, y + size / 2 };
                g2.fillPolygon(xs, ys, 3);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Filled square (stop button).
     *
     * @param color fill colour
     * @param size  width and height in pixels
     */
    static Icon stop(final Color color, final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(color);
                g2.fillRect(x + 1, y + 1, size - 2, size - 2);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Yellow warning triangle with a black exclamation mark.
     *
     * @param size width and height in pixels
     */
    static Icon warning(final int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                // Yellow fill
                g2.setColor(new Color(255, 200, 0));
                int[] xs = { x + size / 2, x,        x + size };
                int[] ys = { y,            y + size, y + size };
                g2.fillPolygon(xs, ys, 3);
                // Dark border
                g2.setColor(new Color(160, 100, 0));
                g2.drawPolygon(xs, ys, 3);
                // Exclamation mark
                g2.setColor(Color.BLACK);
                int cx = x + size / 2;
                g2.fillRect(cx - 1, y + size / 3,     2, size / 3);
                g2.fillOval(cx - 1, y + size * 3 / 4, 2, 2);
                g2.dispose();
            }
            @Override public int getIconWidth()  { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /**
     * Small coloured dot — used as the header logo indicator.
     *
     * @param outer outer ring colour
     * @param inner inner dot colour
     */
    static Icon dot(final Color outer, final Color inner) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(outer);
                g.fillOval(x, y + 2, 12, 12);
                g.setColor(inner);
                g.fillOval(x + 2, y + 4, 8, 8);
            }
            @Override public int getIconWidth()  { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }
}

