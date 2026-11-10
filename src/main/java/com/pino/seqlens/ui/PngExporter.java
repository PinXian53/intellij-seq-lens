package com.pino.seqlens.ui;

import org.jetbrains.annotations.NotNull;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 將循序圖輸出成高解析度 PNG。
 * <p>
 * 刻意使用一般的 {@link BufferedImage}（而不是 IntelliJ 的 HiDPI 影像），由這裡明確決定倍率，
 * 並在 PNG 的 pHYs 區塊寫入對應 DPI，讓看圖軟體以原始尺寸、Retina 清晰度顯示。
 */
final class PngExporter {

    /** 預設輸出倍率。 */
    static final double DEFAULT_SCALE = 3.0;
    /** 單張影像最多像素數（約 360 MB 記憶體），超過時自動降低倍率。 */
    private static final long MAX_PIXELS = 90_000_000L;

    record Result(BufferedImage image, double scale) {
    }

    private PngExporter() {
    }

    static @NotNull Result render(@NotNull DiagramLayout layout, @NotNull DiagramPainter painter, double requestedScale) {
        long logicalW = layout.width;
        long logicalH = (long) layout.height + layout.headerH;
        double maxScale = Math.sqrt((double) MAX_PIXELS / (logicalW * logicalH));
        double scale = Math.max(1.0, Math.min(requestedScale, Math.floor(maxScale * 4) / 4));
        int w = (int) Math.ceil(logicalW * scale);
        int h = (int) Math.ceil(logicalH * scale);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            DiagramPainter.setup(g);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setColor(DiagramPainter.BACKGROUND);
            g.fillRect(0, 0, w, h);
            g.scale(scale, scale);
            painter.paintHeader(new Graphics2DSurface(g), layout, null);
            g.translate(0, layout.headerH);
            painter.paintBody(new Graphics2DSurface(g), layout, null, new Rectangle(0, 0, layout.width, layout.height));
        } finally {
            g.dispose();
        }
        return new Result(image, scale);
    }

    static void write(@NotNull BufferedImage image, double scale, @NotNull File file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
            setDpi(metadata, 72.0 * scale);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally {
            writer.dispose();
        }
    }

    private static void setDpi(IIOMetadata metadata, double dpi) {
        try {
            String pixelsPerMeter = Integer.toString((int) Math.round(dpi / 0.0254));
            IIOMetadataNode phys = new IIOMetadataNode("pHYs");
            phys.setAttribute("pixelsPerUnitXAxis", pixelsPerMeter);
            phys.setAttribute("pixelsPerUnitYAxis", pixelsPerMeter);
            phys.setAttribute("unitSpecifier", "meter");
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
            root.appendChild(phys);
            metadata.mergeTree("javax_imageio_png_1.0", root);
        } catch (IIOInvalidTreeException | IllegalStateException ignored) {
            // DPI 只是顯示提示，寫不進去也不影響影像本身
        }
    }
}
