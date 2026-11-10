package com.pino.seqlens.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ui.JBFont;
import com.pino.seqlens.model.SequenceDiagram;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.function.DoubleConsumer;

/** 循序圖本體（訊息、片段、附註），另有 {@link Header} 作為捲動時固定的參與者標頭。 */
final class DiagramCanvas extends JComponent {

    private final Project project;
    private DiagramLayout layout;
    private DiagramPainter painter;
    private double zoom = 1.0;
    private DiagramLayout.Hit hover;
    private DoubleConsumer zoomListener = z -> {
    };
    /** 在訊息上按右鍵時呼叫（由 DiagramView 顯示選單）。 */
    private java.util.function.BiConsumer<DiagramLayout.Message, MouseEvent> messagePopup = (m, e) -> {
    };
    final Header header = new Header();

    DiagramCanvas(Project project) {
        this.project = project;
        setOpaque(true);
        setToolTipText("");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(layout == null ? null : layout.hitBody(unscale(e.getX()), unscale(e.getY())));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                updateHover(null);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (layout != null && e.isPopupTrigger()
                        && layout.hitBody(unscale(e.getX()), unscale(e.getY())) instanceof DiagramLayout.Message m) {
                    messagePopup.accept(m, e);
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (layout == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                DiagramLayout.Hit hit = layout.hitBody(unscale(e.getX()), unscale(e.getY()));
                if (hit != null) {
                    Navigation.navigate(project, hit.navigation());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown() || e.isMetaDown()) {
                    setZoom(zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1));
                    e.consume();
                } else {
                    JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, DiagramCanvas.this);
                    if (scroll != null) {
                        scroll.dispatchEvent(SwingUtilities.convertMouseEvent(DiagramCanvas.this, e, scroll));
                    }
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    void setDiagram(SequenceDiagram diagram) {
        Font font = diagramFont();
        Font noteFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(9, font.getSize() - 1));
        painter = new DiagramPainter(font, noteFont);
        layout = DiagramLayout.compute(diagram, getFontMetrics(font), getFontMetrics(noteFont));
        hover = null;
        header.hover = null;
        revalidateAll();
    }

    /** 依設定的字體大小（0 = 跟隨 IDE）取得圖的字型。 */
    static Font diagramFont() {
        int size = com.pino.seqlens.settings.SeqLensSettings.getInstance().getState().fontSize;
        return size > 0 ? JBFont.label().deriveFont((float) com.intellij.ui.scale.JBUIScale.scale(size)) : JBFont.label();
    }

    DiagramLayout diagramLayout() {
        return layout;
    }

    DiagramPainter painter() {
        return painter;
    }

    double zoom() {
        return zoom;
    }

    void onMessagePopup(java.util.function.BiConsumer<DiagramLayout.Message, MouseEvent> handler) {
        this.messagePopup = handler;
    }

    void onZoomChanged(DoubleConsumer listener) {
        this.zoomListener = listener;
    }

    void setZoom(double value) {
        double z = Math.max(0.3, Math.min(3.0, value));
        if (Math.abs(z - zoom) < 0.001) {
            return;
        }
        zoom = z;
        revalidateAll();
        zoomListener.accept(zoom);
    }

    private void revalidateAll() {
        revalidate();
        repaint();
        header.revalidate();
        header.repaint();
    }

    private int unscale(int v) {
        return (int) Math.round(v / zoom);
    }

    private void updateHover(@Nullable DiagramLayout.Hit hit) {
        if (hit != hover) {
            hover = hit;
            setCursor(hit != null && hit.navigation() != null
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (layout == null) {
            return new Dimension(100, 100);
        }
        return new Dimension((int) Math.ceil(layout.width * zoom), (int) Math.ceil(layout.height * zoom));
    }

    @Override
    public @Nullable String getToolTipText(MouseEvent e) {
        if (layout == null) {
            return null;
        }
        DiagramLayout.Hit hit = layout.hitBody(unscale(e.getX()), unscale(e.getY()));
        return hit == null ? null : html(hit.tooltip());
    }

    static @Nullable String html(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return "<html><code>" + StringUtil.escapeXmlEntities(text).replace("\n", "<br>") + "</code></html>";
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            DiagramPainter.setup(g);
            Rectangle clip = g.getClipBounds();
            if (clip == null) {
                clip = new Rectangle(getSize());
            }
            g.setColor(DiagramPainter.BACKGROUND);
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            if (layout == null) {
                return;
            }
            g.scale(zoom, zoom);
            Rectangle logical = new Rectangle((int) (clip.x / zoom) - 1, (int) (clip.y / zoom) - 1,
                    (int) (clip.width / zoom) + 2, (int) (clip.height / zoom) + 2);
            painter.paintBody(new Graphics2DSurface(g), layout, hover, logical);
        } finally {
            g.dispose();
        }
    }

    /** 參與者標頭，放在 JScrollPane 的 column header 中，垂直捲動時保持可見。 */
    final class Header extends JComponent {
        private DiagramLayout.Column hover;

        Header() {
            setOpaque(true);
            setToolTipText("");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    DiagramLayout.Column c = hit(e.getPoint());
                    if (c != hover) {
                        hover = c;
                        setCursor(c != null && c.participant().navigation() != null
                                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = null;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    DiagramLayout.Column c = hit(e.getPoint());
                    if (c != null && SwingUtilities.isLeftMouseButton(e)) {
                        Navigation.navigate(project, c.participant().navigation());
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private @Nullable DiagramLayout.Column hit(Point p) {
            return layout == null ? null : layout.hitHeader(unscale(p.x), unscale(p.y));
        }

        @Override
        public @Nullable String getToolTipText(MouseEvent e) {
            DiagramLayout.Column c = hit(e.getPoint());
            return c == null ? null : html(c.participant().tooltip());
        }

        @Override
        public Dimension getPreferredSize() {
            if (layout == null) {
                return new Dimension(100, DiagramLayout.DEFAULT_HEADER_H);
            }
            return new Dimension((int) Math.ceil(layout.width * zoom), (int) Math.ceil(layout.headerH * zoom));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                DiagramPainter.setup(g);
                g.setColor(DiagramPainter.BACKGROUND);
                g.fillRect(0, 0, getWidth(), getHeight());
                if (layout == null) {
                    return;
                }
                g.scale(zoom, zoom);
                painter.paintHeader(new Graphics2DSurface(g), layout, hover);
            } finally {
                g.dispose();
            }
        }
    }

    /** 背景色延伸到 viewport 空白處。 */
    static void styleViewport(JScrollPane scroll) {
        scroll.getViewport().setBackground(DiagramPainter.BACKGROUND);
        scroll.setBackground(DiagramPainter.BACKGROUND);
        if (scroll.getColumnHeader() != null) {
            scroll.getColumnHeader().setBackground(DiagramPainter.BACKGROUND);
        }
    }
}
