package com.pino.seqlens.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.pino.seqlens.model.MessageKind;
import com.pino.seqlens.model.Participant;
import com.pino.seqlens.model.ParticipantKind;
import org.jetbrains.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** 繪製循序圖（標頭的參與者與本體）。 */
final class DiagramPainter {

    static final Color BACKGROUND = new JBColor(0xFFFFFF, 0x1E1F22);
    static final Color TEXT = new JBColor(0x1F2328, 0xDFE1E5);
    static final Color MUTED = new JBColor(0x6B7280, 0x9AA0A6);
    static final Color LIFELINE = new JBColor(0xC3C8D0, 0x4E5157);
    static final Color HOVER = new JBColor(0x2F80ED, 0x589DF6);

    private static final Color CLASS_FILL = new JBColor(0xEAF2FD, 0x25324A);
    private static final Color CLASS_BORDER = new JBColor(0x4A78C2, 0x6A9BE0);
    private static final Color BOUNDARY_FILL = new JBColor(0xF3EAFD, 0x342A47);
    private static final Color BOUNDARY_BORDER = new JBColor(0x8A5CC2, 0xB08BE0);
    private static final Color REPO_FILL = new JBColor(0xE7F5EC, 0x22392A);
    private static final Color REPO_BORDER = new JBColor(0x3C9A5F, 0x6CC48F);
    private static final Color MAPPER_FILL = new JBColor(0xE6F6F7, 0x1F3A3D);
    private static final Color MAPPER_BORDER = new JBColor(0x2A9DA6, 0x5CC3CB);
    private static final Color EXTERNAL_FILL = new JBColor(0xF3E8FF, 0x33284A);
    static final Color HTTP = new JBColor(0x7C3AED, 0xB794F6);
    private static final Color TABLE_FILL = new JBColor(0xFFF3E0, 0x40331F);
    static final Color TABLE_BORDER = new JBColor(0xD9822B, 0xE6A35C);
    private static final Color ACT_FILL = new JBColor(0xF6F8FA, 0x2B2D30);
    private static final Color ACT_BORDER = new JBColor(0x8C959F, 0x6F737A);
    private static final Color CREATE = new JBColor(0x4A78C2, 0x6A9BE0);
    static final Color THROW = new JBColor(0xD93036, 0xF2777A);
    private static final Color FRAG_BORDER = new JBColor(0x9AA4B2, 0x5F6670);
    private static final Color FRAG_TAB = new JBColor(0xEEF1F5, 0x2F3338);
    private static final Color LOOP_TAB = new JBColor(0xE9F6F1, 0x263A33);
    private static final Color NOTE_FILL = new JBColor(0xFFFBE6, 0x3B3826);
    private static final Color NOTE_BORDER = new JBColor(0xE0C96B, 0x8C7E45);

    private final Font font;
    private final Font boldFont;
    private final Font noteFont;

    DiagramPainter(Font font, Font noteFont) {
        this.font = font;
        this.boldFont = font.deriveFont(Font.BOLD);
        this.noteFont = noteFont;
    }

    Font font() {
        return font;
    }

    static void setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ------------------------------------------------------------------ 標頭

    void paintHeader(Surface g, DiagramLayout layout, @Nullable DiagramLayout.Column hover) {
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, layout.width, layout.headerH);
        Stroke dashed = dashed();
        for (DiagramLayout.Column c : layout.columns) {
            g.setColor(LIFELINE);
            g.setStroke(dashed);
            g.drawLine(c.x(), layout.headerH - JBUI.scale(10), c.x(), layout.headerH);
            paintParticipant(g, c, layout.boxH, c == hover);
        }
        g.setStroke(new BasicStroke(1f));
        g.setColor(LIFELINE);
        g.drawLine(0, layout.headerH - 1, layout.width, layout.headerH - 1);
    }

    private void paintParticipant(Surface g, DiagramLayout.Column c, int boxH, boolean hover) {
        Participant p = c.participant();
        int top = JBUI.scale(16);
        int h = boxH;
        int x = c.x() - c.boxWidth() / 2;
        int w = c.boxWidth();
        g.setFont(boldFont);
        FontMetrics fm = g.getFontMetrics();
        g.setStroke(new BasicStroke(hover ? 2f : 1.2f));
        switch (p.kind()) {
            case ACTOR -> {
                Color color = hover ? HOVER : TEXT;
                g.setColor(color);
                int cx = c.x();
                int headR = JBUI.scale(5);
                int y0 = top - JBUI.scale(4);
                g.draw(new Ellipse2D.Double(cx - headR, y0, headR * 2, headR * 2));
                int neck = y0 + headR * 2;
                g.drawLine(cx, neck, cx, neck + JBUI.scale(11));
                g.drawLine(cx - JBUI.scale(8), neck + JBUI.scale(4), cx + JBUI.scale(8), neck + JBUI.scale(4));
                g.drawLine(cx, neck + JBUI.scale(11), cx - JBUI.scale(6), neck + JBUI.scale(19));
                g.drawLine(cx, neck + JBUI.scale(11), cx + JBUI.scale(6), neck + JBUI.scale(19));
                g.drawString(p.displayName(), cx - fm.stringWidth(p.displayName()) / 2, neck + JBUI.scale(21) + fm.getAscent());
                return;
            }
            case TABLE -> {
                int ry = JBUI.scale(5);
                Path2D body = new Path2D.Double();
                body.moveTo(x, top + ry);
                body.lineTo(x, top + h - ry);
                body.quadTo(x + w / 2.0, top + h + ry, x + w, top + h - ry);
                body.lineTo(x + w, top + ry);
                body.quadTo(x + w / 2.0, top - ry, x, top + ry);
                g.setColor(TABLE_FILL);
                g.fill(body);
                g.setColor(hover ? HOVER : TABLE_BORDER);
                g.draw(body);
                Path2D rim = new Path2D.Double();
                rim.moveTo(x, top + ry);
                rim.quadTo(x + w / 2.0, top + ry * 3, x + w, top + ry);
                g.draw(rim);
                g.setColor(TEXT);
                g.drawString(p.displayName(), c.x() - fm.stringWidth(p.displayName()) / 2,
                        top + ry + (h - ry) / 2 + fm.getAscent() / 2);
                return;
            }
            default -> {
            }
        }
        Color fill = switch (p.kind()) {
            case EXTERNAL -> EXTERNAL_FILL;
            case MAPPER -> MAPPER_FILL;
            case BOUNDARY -> BOUNDARY_FILL;
            case REPOSITORY -> REPO_FILL;
            default -> CLASS_FILL;
        };
        Color border = switch (p.kind()) {
            case EXTERNAL -> HTTP;
            case MAPPER -> MAPPER_BORDER;
            case BOUNDARY -> BOUNDARY_BORDER;
            case REPOSITORY -> REPO_BORDER;
            default -> CLASS_BORDER;
        };
        RoundRectangle2D box = new RoundRectangle2D.Double(x, top, w, h, JBUI.scale(8), JBUI.scale(8));
        g.setColor(fill);
        g.fill(box);
        g.setColor(hover ? HOVER : border);
        if (p.kind() == ParticipantKind.EXTERNAL) {
            g.setStroke(dashed(hover ? 2f : 1.4f));
        }
        g.draw(box);
        g.setColor(TEXT);
        g.drawString(p.displayName(), c.x() - fm.stringWidth(p.displayName()) / 2,
                top + (h - fm.getHeight()) / 2 + fm.getAscent());
        if (p.kind() == ParticipantKind.REPOSITORY || p.kind() == ParticipantKind.BOUNDARY
                || p.kind() == ParticipantKind.EXTERNAL || p.kind() == ParticipantKind.MAPPER) {
            g.setFont(font.deriveFont(font.getSize2D() - 2f));
            String stereo = switch (p.kind()) {
                case REPOSITORY -> "«repository»";
                case EXTERNAL -> "«external api»";
                case MAPPER -> "«mapper»";
                default -> "«controller»";
            };
            FontMetrics sfm = g.getFontMetrics();
            g.setColor(MUTED);
            g.drawString(stereo, c.x() - sfm.stringWidth(stereo) / 2, top - JBUI.scale(2));
        }
    }

    // ------------------------------------------------------------------ 本體

    void paintBody(Surface g, DiagramLayout layout, @Nullable DiagramLayout.Hit hover, Rectangle clip) {
        g.setColor(BACKGROUND);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        // 生命線
        g.setStroke(dashed());
        g.setColor(LIFELINE);
        for (DiagramLayout.Column c : layout.columns) {
            g.drawLine(c.x(), 0, c.x(), layout.height);
        }

        g.setFont(font);
        // activation
        g.setStroke(new BasicStroke(1f));
        for (DiagramLayout.Activation a : layout.activations) {
            int x = a.x() - DiagramLayout.ACT_W / 2;
            g.setColor(ACT_FILL);
            g.fillRect(x, a.y1(), DiagramLayout.ACT_W, a.y2() - a.y1());
            g.setColor(ACT_BORDER);
            g.drawRect(x, a.y1(), DiagramLayout.ACT_W, a.y2() - a.y1());
        }
        for (DiagramLayout.Message m : layout.messages) {
            paintMessage(g, m, m == hover);
        }
        for (DiagramLayout.Note n : layout.notes) {
            paintNote(g, n, n == hover);
        }
        // 組合片段畫在最上層，框線與標籤不會被 activation、箭頭或附註蓋住
        for (DiagramLayout.Fragment f : layout.fragments) {
            paintFragmentFrame(g, f);
        }
        for (DiagramLayout.Fragment f : layout.fragments) {
            paintFragmentLabels(g, f);
        }
        if (layout.truncated) {
            g.setFont(boldFont);
            g.setColor(TABLE_BORDER);
            g.drawString("⚠ Maximum number of calls reached; the diagram is truncated (adjust in Settings › Tools › SeqLens)",
                    DiagramLayout.PAD_LEFT, layout.height - JBUI.scale(8));
        }
    }

    private void paintFragmentFrame(Surface g, DiagramLayout.Fragment f) {
        Rectangle r = f.bounds();
        g.setStroke(new BasicStroke(1f));
        g.setColor(FRAG_BORDER);
        g.drawRect(r.x, r.y, r.width, r.height);
        Stroke dashed = dashed();
        g.setStroke(dashed);
        for (DiagramLayout.ElseLine line : f.elses()) {
            g.drawLine(r.x, line.y(), r.x + r.width, line.y());
        }
        g.setStroke(new BasicStroke(1f));
    }

    private void paintFragmentLabels(Surface g, DiagramLayout.Fragment f) {
        Rectangle r = f.bounds();
        FontMetrics fm = g.getFontMetrics(font);
        g.setStroke(new BasicStroke(1f));

        String keyword = f.type().keyword();
        int tabW = fm.stringWidth(keyword) + JBUI.scale(14);
        int tabH = fm.getHeight() + JBUI.scale(4);
        Path2D tab = new Path2D.Double();
        tab.moveTo(r.x, r.y);
        tab.lineTo(r.x + tabW, r.y);
        tab.lineTo(r.x + tabW, r.y + tabH - JBUI.scale(5));
        tab.lineTo(r.x + tabW - JBUI.scale(5), r.y + tabH);
        tab.lineTo(r.x, r.y + tabH);
        tab.closePath();
        g.setColor(f.type() == com.pino.seqlens.model.DiagramEvent.FragmentType.LOOP ? LOOP_TAB : FRAG_TAB);
        g.fill(tab);
        g.setColor(FRAG_BORDER);
        g.draw(tab);
        g.setFont(boldFont);
        g.setColor(TEXT);
        g.drawString(keyword, r.x + JBUI.scale(6), r.y + JBUI.scale(2) + fm.getAscent());
        g.setFont(font);
        if (f.type() != com.pino.seqlens.model.DiagramEvent.FragmentType.TRY) {
            labelWithBackground(g, fm, "[" + f.condition() + "]", r.x + tabW + JBUI.scale(8), r.y + JBUI.scale(2) + fm.getAscent());
        }
        for (DiagramLayout.ElseLine line : f.elses()) {
            labelWithBackground(g, fm, "[" + line.label() + "]", r.x + JBUI.scale(8), line.y() + JBUI.scale(2) + fm.getAscent());
        }
    }

    /** 文字底下先填背景色，避免與生命線、activation 重疊時難以閱讀。 */
    private static void labelWithBackground(Surface g, FontMetrics fm, String text, int x, int baseline) {
        int pad = JBUI.scale(2);
        g.setColor(BACKGROUND);
        g.fillRect(x - pad, baseline - fm.getAscent(), fm.stringWidth(text) + pad * 2, fm.getHeight());
        g.setColor(MUTED);
        g.drawString(text, x, baseline);
        g.setStroke(new BasicStroke(1f));
    }

    private void paintMessage(Surface g, DiagramLayout.Message m, boolean hover) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        Color color;
        if (hover) {
            color = HOVER;
        } else if (m.isReturn()) {
            color = MUTED;
        } else if (m.kind() == MessageKind.SQL) {
            color = TABLE_BORDER;
        } else if (m.kind() == MessageKind.CREATE) {
            color = CREATE;
        } else if (m.kind() == MessageKind.THROW) {
            color = THROW;
        } else if (m.kind() == MessageKind.HTTP) {
            color = HTTP;
        } else {
            color = TEXT;
        }
        g.setColor(color);
        boolean isThrow = m.kind() == MessageKind.THROW;
        g.setStroke(m.isReturn() || isThrow ? dashed(hover ? 2f : 1.4f) : new BasicStroke(hover ? 2f : 1.2f));
        int labelY = m.y() - JBUI.scale(4) - fm.getDescent();
        if (m.self()) {
            int right = m.x1() + DiagramLayout.SELF_W;
            g.drawLine(m.x1(), m.y(), right, m.y());
            g.drawLine(right, m.y(), right, m.selfBottom());
            g.drawLine(right, m.selfBottom(), m.x2(), m.selfBottom());
            arrowHead(g, m.x2(), m.selfBottom(), -1, true);
            if (isThrow) {
                cross(g, right, (m.y() + m.selfBottom()) / 2);
            }
            g.setFont(m.kind() == MessageKind.SQL || m.kind() == MessageKind.HTTP || isThrow ? boldFont : font);
            g.drawString(m.label(), m.x1() + JBUI.scale(4), labelY);
            paintBadge(g, m, m.x1() + JBUI.scale(4) + g.getFontMetrics().stringWidth(m.label()), labelY);
            return;
        }
        g.drawLine(m.x1(), m.y(), m.x2(), m.y());
        int dir = m.x2() > m.x1() ? 1 : -1;
        arrowHead(g, m.x2(), m.y(), dir, !m.isReturn());
        if (isThrow) {
            cross(g, m.x1() + dir * JBUI.scale(8), m.y());
        }
        g.setFont(m.kind() == MessageKind.SQL || m.kind() == MessageKind.HTTP || isThrow ? boldFont : font);
        fm = g.getFontMetrics();
        int labelW = fm.stringWidth(m.label());
        int totalW = labelW + DiagramLayout.badgeWidth(fm, m.expansion());
        int lx = (m.x1() + m.x2()) / 2 - totalW / 2;
        lx = Math.max(Math.min(m.x1(), m.x2()) + JBUI.scale(4), lx);
        g.drawString(m.label(), lx, labelY);
        paintBadge(g, m, lx + labelW, labelY);
        if (hover && !m.isReturn()) {
            g.drawLine(lx, labelY + JBUI.scale(2), lx + labelW, labelY + JBUI.scale(2));
        }
    }

    /** 未展開標記：灰色 ⊘（不展開清單）、橘色 ⋯（達最大深度）。 */
    private void paintBadge(Surface g, DiagramLayout.Message m, int x, int baseline) {
        String badge = DiagramLayout.badge(m.expansion());
        if (badge == null) {
            return;
        }
        Font previous = g.getFont();
        g.setFont(boldFont);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(badge) + JBUI.scale(8);
        int h = fm.getAscent();
        int bx = x + JBUI.scale(5);
        int by = baseline - fm.getAscent() + JBUI.scale(1);
        Color color = m.expansion() == com.pino.seqlens.model.DiagramEvent.Expansion.DEPTH_LIMIT ? TABLE_BORDER : MUTED;
        g.setStroke(new BasicStroke(1f));
        g.setColor(BACKGROUND);
        g.fill(new RoundRectangle2D.Double(bx, by, w, h, h, h));
        g.setColor(color);
        g.draw(new RoundRectangle2D.Double(bx, by, w, h, h, h));
        g.drawString(badge, bx + JBUI.scale(4), baseline - JBUI.scale(1));
        g.setFont(previous);
    }

    /** throw 起點的 × 記號。 */
    private static void cross(Surface g, int x, int y) {
        int r = JBUI.scale(4);
        g.setStroke(new BasicStroke(1.6f));
        g.drawLine(x - r, y - r, x + r, y + r);
        g.drawLine(x - r, y + r, x + r, y - r);
    }

    private static void arrowHead(Surface g, int x, int y, int dir, boolean filled) {
        int len = JBUI.scale(8);
        int half = JBUI.scale(4);
        g.setStroke(new BasicStroke(1.2f));
        if (filled) {
            Polygon p = new Polygon();
            p.addPoint(x, y);
            p.addPoint(x - dir * len, y - half);
            p.addPoint(x - dir * len, y + half);
            g.fillPolygon(p);
        } else {
            g.drawLine(x, y, x - dir * len, y - half);
            g.drawLine(x, y, x - dir * len, y + half);
        }
    }

    private void paintNote(Surface g, DiagramLayout.Note n, boolean hover) {
        Rectangle r = n.bounds();
        int fold = JBUI.scale(8);
        Path2D shape = new Path2D.Double();
        shape.moveTo(r.x, r.y);
        shape.lineTo(r.x + r.width - fold, r.y);
        shape.lineTo(r.x + r.width, r.y + fold);
        shape.lineTo(r.x + r.width, r.y + r.height);
        shape.lineTo(r.x, r.y + r.height);
        shape.closePath();
        g.setColor(NOTE_FILL);
        g.fill(shape);
        g.setStroke(new BasicStroke(hover ? 2f : 1f));
        g.setColor(hover ? HOVER : NOTE_BORDER);
        g.draw(shape);
        g.drawLine(r.x + r.width - fold, r.y, r.x + r.width - fold, r.y + fold);
        g.drawLine(r.x + r.width - fold, r.y + fold, r.x + r.width, r.y + fold);
        g.setFont(noteFont);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(TEXT);
        int y = r.y + JBUI.scale(5) + fm.getAscent();
        for (String line : n.lines()) {
            g.drawString(line, r.x + JBUI.scale(8), y);
            y += fm.getHeight();
        }
    }

    private static Stroke dashed() {
        return dashed(1f);
    }

    private static Stroke dashed(float width) {
        return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{JBUI.scale(4), JBUI.scale(4)}, 0f);
    }
}
