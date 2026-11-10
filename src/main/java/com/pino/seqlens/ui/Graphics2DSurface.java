package com.pino.seqlens.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;

/** 以 {@link Graphics2D} 繪製（螢幕、PNG）。 */
final class Graphics2DSurface implements Surface {

    private final Graphics2D g;

    Graphics2DSurface(Graphics2D g) {
        this.g = g;
    }

    @Override
    public void setColor(Color color) {
        g.setColor(color);
    }

    @Override
    public void setStroke(Stroke stroke) {
        g.setStroke(stroke);
    }

    @Override
    public void setFont(Font font) {
        g.setFont(font);
    }

    @Override
    public Font getFont() {
        return g.getFont();
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        return g.getFontMetrics(font);
    }

    @Override
    public void draw(Shape shape) {
        g.draw(shape);
    }

    @Override
    public void fill(Shape shape) {
        g.fill(shape);
    }

    @Override
    public void drawString(String text, int x, int y) {
        g.drawString(text, x, y);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2) {
        g.drawLine(x1, y1, x2, y2);
    }

    @Override
    public void drawRect(int x, int y, int w, int h) {
        g.drawRect(x, y, w, h);
    }

    @Override
    public void fillRect(int x, int y, int w, int h) {
        g.fillRect(x, y, w, h);
    }
}
