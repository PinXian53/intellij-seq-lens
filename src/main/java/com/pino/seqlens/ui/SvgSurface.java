package com.pino.seqlens.ui;

import com.intellij.openapi.util.text.StringUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.PathIterator;
import java.util.Locale;
import java.util.function.Function;

/**
 * 把繪圖指令轉成 SVG 元素。圖形一律轉成 path；文字用 {@code textLength} 固定寬度，
 * 確保在沒有相同字型的環境中版面也不會跑掉。
 */
final class SvgSurface implements Surface {

    private final StringBuilder body = new StringBuilder();
    private final Function<Font, FontMetrics> metrics;
    private Color color = Color.BLACK;
    private Stroke stroke = new BasicStroke(1f);
    private Font font;
    private double offsetX;
    private double offsetY;

    SvgSurface(Font initialFont, Function<Font, FontMetrics> metrics) {
        this.font = initialFont;
        this.metrics = metrics;
    }

    /** 之後的繪圖座標平移（標頭與本體分開畫時使用）。 */
    void translate(double dx, double dy) {
        offsetX += dx;
        offsetY += dy;
    }

    @Override
    public void setColor(Color color) {
        this.color = color;
    }

    @Override
    public void setStroke(Stroke stroke) {
        this.stroke = stroke;
    }

    @Override
    public void setFont(Font font) {
        this.font = font;
    }

    @Override
    public Font getFont() {
        return font;
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        return metrics.apply(font);
    }

    @Override
    public void draw(Shape shape) {
        body.append("<path d=\"").append(path(shape)).append("\" fill=\"none\"").append(strokeAttrs()).append("/>\n");
    }

    @Override
    public void fill(Shape shape) {
        body.append("<path d=\"").append(path(shape)).append("\"").append(fillAttr()).append("/>\n");
    }

    @Override
    public void drawString(String text, int x, int y) {
        if (text.isEmpty()) {
            return;
        }
        int width = metrics.apply(font).stringWidth(text);
        body.append("<text x=\"").append(num(x + offsetX)).append("\" y=\"").append(num(y + offsetY)).append('"')
                .append(" font-family=\"").append(fontFamily(font)).append('"')
                .append(" font-size=\"").append(num(font.getSize2D())).append('"')
                .append(font.isBold() ? " font-weight=\"bold\"" : "")
                .append(" textLength=\"").append(width).append("\" lengthAdjust=\"spacingAndGlyphs\"")
                .append(fillAttr()).append('>')
                .append(StringUtil.escapeXmlEntities(text))
                .append("</text>\n");
    }

    String toSvg(int width, int height, Color background) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                + "\" viewBox=\"0 0 " + width + " " + height + "\" xml:space=\"preserve\">\n"
                + "<rect width=\"100%\" height=\"100%\"" + colorAttr("fill", background) + "/>\n"
                + body
                + "</svg>\n";
    }

    // ------------------------------------------------------------------

    private String path(Shape shape) {
        StringBuilder d = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> d.append('M').append(pt(c[0], c[1]));
                case PathIterator.SEG_LINETO -> d.append('L').append(pt(c[0], c[1]));
                case PathIterator.SEG_QUADTO -> d.append('Q').append(pt(c[0], c[1])).append(' ').append(pt(c[2], c[3]));
                case PathIterator.SEG_CUBICTO -> d.append('C').append(pt(c[0], c[1])).append(' ')
                        .append(pt(c[2], c[3])).append(' ').append(pt(c[4], c[5]));
                case PathIterator.SEG_CLOSE -> d.append('Z');
                default -> {
                }
            }
        }
        return d.toString();
    }

    private String pt(double x, double y) {
        return num(x + offsetX) + "," + num(y + offsetY);
    }

    private String strokeAttrs() {
        StringBuilder sb = new StringBuilder(colorAttr("stroke", color));
        if (stroke instanceof BasicStroke bs) {
            sb.append(" stroke-width=\"").append(num(bs.getLineWidth())).append('"');
            float[] dash = bs.getDashArray();
            if (dash != null && dash.length > 0) {
                sb.append(" stroke-dasharray=\"");
                for (int i = 0; i < dash.length; i++) {
                    sb.append(i > 0 ? "," : "").append(num(dash[i]));
                }
                sb.append('"');
            }
        }
        return sb.toString();
    }

    private String fillAttr() {
        return colorAttr("fill", color);
    }

    private static String colorAttr(String name, Color c) {
        String attr = String.format(Locale.ROOT, " %s=\"#%02x%02x%02x\"", name, c.getRed(), c.getGreen(), c.getBlue());
        if (c.getAlpha() < 255) {
            attr += String.format(Locale.ROOT, " %s-opacity=\"%.3f\"", name, c.getAlpha() / 255.0);
        }
        return attr;
    }

    private static String fontFamily(Font font) {
        String family = font.getFamily();
        boolean mono = Font.MONOSPACED.equals(family) || Font.MONOSPACED.equals(font.getName())
                || family.toLowerCase(Locale.ROOT).contains("mono");
        if (mono) {
            return "'JetBrains Mono', Menlo, Consolas, 'Courier New', monospace";
        }
        String own = family.startsWith(".") || family.equals(Font.DIALOG) ? "" : "'" + family.replace("'", "") + "', ";
        return own + "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', 'Noto Sans TC', 'Microsoft JhengHei', Arial, sans-serif";
    }

    private static String num(double v) {
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
