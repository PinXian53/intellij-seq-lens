package com.pino.seqlens.ui;

import org.jetbrains.annotations.NotNull;

import java.awt.Canvas;
import java.awt.Font;
import java.awt.Rectangle;

/** 將循序圖輸出成 SVG（向量圖，任意縮放都清晰）。 */
final class SvgExporter {

    private SvgExporter() {
    }

    static @NotNull String export(@NotNull DiagramLayout layout, @NotNull DiagramPainter painter, @NotNull Font font) {
        Canvas metricsSource = new Canvas();
        SvgSurface svg = new SvgSurface(font, metricsSource::getFontMetrics);
        painter.paintHeader(svg, layout, null);
        svg.translate(0, layout.headerH);
        painter.paintBody(svg, layout, null, new Rectangle(0, 0, layout.width, layout.height));
        return svg.toSvg(layout.width, layout.height + layout.headerH, DiagramPainter.BACKGROUND);
    }
}
