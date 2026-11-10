package com.pino.seqlens.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Line2D;

/**
 * {@link DiagramPainter} 使用的最小繪圖介面，讓同一套繪製邏輯能輸出到螢幕 / PNG（{@link Graphics2DSurface}）
 * 與 SVG（{@link SvgSurface}）。
 */
interface Surface {

    void setColor(Color color);

    void setStroke(Stroke stroke);

    void setFont(Font font);

    FontMetrics getFontMetrics(Font font);

    void draw(Shape shape);

    void fill(Shape shape);

    void drawString(String text, int x, int y);

    default FontMetrics getFontMetrics() {
        return getFontMetrics(getFont());
    }

    Font getFont();

    default void drawLine(int x1, int y1, int x2, int y2) {
        draw(new Line2D.Float(x1, y1, x2, y2));
    }

    default void drawRect(int x, int y, int w, int h) {
        draw(new Rectangle(x, y, w, h));
    }

    default void fillRect(int x, int y, int w, int h) {
        fill(new Rectangle(x, y, w, h));
    }

    default void fillPolygon(Polygon polygon) {
        fill(polygon);
    }
}
