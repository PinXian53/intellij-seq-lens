package com.pino.seqlens.ui;

import com.intellij.util.ui.JBFont;
import com.pino.seqlens.analysis.SequenceAnalyzerTest;
import com.pino.seqlens.model.SequenceDiagram;

import javax.imageio.ImageIO;
import java.awt.Canvas;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;

/** 把範例程式的循序圖實際繪製成 PNG（build/diagram-sample.png），確認版面計算與繪製不會出錯。 */
public class DiagramRenderTest extends SequenceAnalyzerTest {

    public void testRenderPng() throws Exception {
        SequenceDiagram diagram = analyze();
        Font font = JBFont.label();
        Font noteFont = new Font(Font.MONOSPACED, Font.PLAIN, font.getSize() - 1);
        Canvas metrics = new Canvas();
        DiagramLayout layout = DiagramLayout.compute(diagram, metrics.getFontMetrics(font), metrics.getFontMetrics(noteFont));
        DiagramPainter painter = new DiagramPainter(font, noteFont);

        assertTrue(layout.width > 0 && layout.height > 0);
        assertEquals(diagram.getParticipants().size(), layout.columns.size());
        assertNotNull("點在第一個訊息上應命中", layout.hitBody(
                layout.messages.getFirst().bounds().x + 5, layout.messages.getFirst().y()));

        PngExporter.Result result = PngExporter.render(layout, painter, PngExporter.DEFAULT_SCALE);
        BufferedImage image = result.image();
        assertEquals(3.0, result.scale(), 0.001);
        assertEquals((int) Math.ceil(layout.width * 3.0), image.getWidth());
        assertEquals((int) Math.ceil((layout.height + layout.headerH) * 3.0), image.getHeight());
        // 左上角應該是標頭的背景，而不是被錯位的內容
        assertEquals(DiagramPainter.BACKGROUND.getRGB() | 0xFF000000, image.getRGB(1, 1) | 0xFF000000);

        File out = new File(System.getProperty("user.dir"), "build/diagram-sample.png");
        out.getParentFile().mkdirs();
        PngExporter.write(image, result.scale(), out);
        assertTrue(out.length() > 0);

        // 寫入的 DPI = 72 × 3
        try (var in = ImageIO.createImageInputStream(out)) {
            var reader = ImageIO.getImageReaders(in).next();
            reader.setInput(in);
            var node = (org.w3c.dom.Element) reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0");
            var phys = (org.w3c.dom.Element) node.getElementsByTagName("pHYs").item(0);
            assertEquals(Math.round(216 / 0.0254), Long.parseLong(phys.getAttribute("pixelsPerUnitXAxis")));
        }
    }

    public void testRenderSvg() throws Exception {
        SequenceDiagram diagram = analyzeGateway();
        Font font = JBFont.label();
        Font noteFont = new Font(Font.MONOSPACED, Font.PLAIN, font.getSize() - 1);
        Canvas metrics = new Canvas();
        DiagramLayout layout = DiagramLayout.compute(diagram, metrics.getFontMetrics(font), metrics.getFontMetrics(noteFont));
        DiagramPainter painter = new DiagramPainter(font, noteFont);

        String svg = SvgExporter.export(layout, painter, font);
        assertTrue(svg.startsWith("<?xml"));
        assertTrue(svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""));
        assertTrue(svg, svg.contains(">GET /stock/{id}</text>"));
        assertTrue(svg, svg.contains("«external api»"));
        assertTrue(svg.trim().endsWith("</svg>"));
        // 必須是合法 XML
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.nio.file.Files.writeString(new File(System.getProperty("user.dir"), "build/diagram-http.svg").toPath(), svg);

        PngExporter.Result png = PngExporter.render(layout, painter, 2.0);
        PngExporter.write(png.image(), png.scale(), new File(System.getProperty("user.dir"), "build/diagram-http.png"));
    }

    public void testRenderResourcePanel() throws Exception {
        SequenceDiagram diagram = analyze();
        addGateway();
        com.intellij.psi.PsiMethod place = myFixture.findClass("demo.OrderGateway").findMethodsByName("place", false)[0];
        SequenceDiagram http = new com.pino.seqlens.analysis.SequenceAnalyzer(getProject(),
                new com.pino.seqlens.settings.SeqLensSettings.State()).analyze(place);
        int i = 0;
        for (SequenceDiagram d : new SequenceDiagram[]{diagram, http}) {
            ResourcePanel panel = new ResourcePanel(getProject());
            panel.setSummary(com.pino.seqlens.model.ResourceSummary.of(d));
            panel.setSize(520, 420);
            panel.doLayout();
            layoutRecursively(panel);
            BufferedImage image = new BufferedImage(520, 420, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            panel.printAll(g);
            g.dispose();
            ImageIO.write(image, "png", new File(System.getProperty("user.dir"), "build/resource-panel-" + (i++) + ".png"));
        }
    }

    private static int countHelpLabels(java.awt.Container c) {
        int n = 0;
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof com.intellij.ui.ContextHelpLabel) {
                n++;
            }
            if (child instanceof java.awt.Container cc) {
                n += countHelpLabels(cc);
            }
        }
        return n;
    }

    private static void layoutRecursively(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container cc) {
                layoutRecursively(cc);
            }
        }
    }

    public void testRenderSettingsPage() throws Exception {
        var configurable = new com.pino.seqlens.settings.SeqLensConfigurable();
        javax.swing.JComponent page = configurable.createComponent();
        assertNotNull(page);
        assertFalse("剛開啟時不應標記為已修改", configurable.isModified());
        page.setSize(720, 1100);
        layoutRecursively(page);
        BufferedImage image = new BufferedImage(720, 1100, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        page.printAll(g);
        g.dispose();
        ImageIO.write(image, "png", new File(System.getProperty("user.dir"), "build/settings-page.png"));
        assertEquals("每個需要說明的選項都有 ? 按鈕", 13, countHelpLabels(page));
        configurable.disposeUIResources();
    }

    public void testLargerFontGrowsHeader() {
        SequenceDiagram diagram = analyze();
        Canvas metrics = new Canvas();
        Font small = JBFont.label();
        Font large = small.deriveFont(small.getSize2D() * 2.5f);
        DiagramLayout a = DiagramLayout.compute(diagram, metrics.getFontMetrics(small), metrics.getFontMetrics(small));
        DiagramLayout b = DiagramLayout.compute(diagram, metrics.getFontMetrics(large), metrics.getFontMetrics(large));
        assertTrue(b.headerH > a.headerH);
        assertTrue("方框要容得下文字", b.boxH >= metrics.getFontMetrics(large).getHeight());
        assertTrue(b.width > a.width && b.height > a.height);
    }

    @Override
    public void testHttpClients() {
    }

    @Override
    public void testResourceSummary() {
    }

    @Override
    public void testStopList() {
    }

    @Override
    public void testDynamicQuery() {
    }

    @Override
    public void testMapStructNotExpanded() {
    }

    @Override
    public void testServiceMethodNamedLikeGetterIsExpanded() {
    }

    @Override
    public void testInterfaceGetterNameWithoutImplementationIsStillShown() {
    }

    @Override
    public void testRealGettersAreStillSkipped() {
    }

    public void testRenderDynamicQuery() throws Exception {
        SequenceDiagram diagram = analyzeDynamic();
        Font font = JBFont.label();
        Font noteFont = new Font(Font.MONOSPACED, Font.PLAIN, font.getSize() - 1);
        Canvas metrics = new Canvas();
        DiagramLayout layout = DiagramLayout.compute(diagram, metrics.getFontMetrics(font), metrics.getFontMetrics(noteFont));
        PngExporter.Result png = PngExporter.render(layout, new DiagramPainter(font, noteFont), 2.0);
        PngExporter.write(png.image(), png.scale(), new File(System.getProperty("user.dir"), "build/diagram-dynamic.png"));
    }

    public void testImageTransferableRejectsOtherFlavors() throws Exception {
        var t = new DiagramView.ImageTransferable(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        assertTrue(t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor));
        assertFalse(t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor));
        try {
            t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
            fail("要求文字格式應拋 UnsupportedFlavorException，而不是回傳圖片");
        } catch (java.awt.datatransfer.UnsupportedFlavorException expected) {
            // ok
        }
    }

    @Override
    public void testDepthLimit() {
    }

    @Override
    public void testResourceSummaryClasses() {
    }

    public void testRenderBadges() throws Exception {
        com.pino.seqlens.settings.SeqLensSettings.State settings = new com.pino.seqlens.settings.SeqLensSettings.State();
        settings.maxDepth = 1;
        settings.stopPatterns = "User#<init>";
        SequenceDiagram diagram = analyze(settings);
        Font font = JBFont.label();
        Font noteFont = new Font(Font.MONOSPACED, Font.PLAIN, font.getSize() - 1);
        Canvas metrics = new Canvas();
        DiagramLayout layout = DiagramLayout.compute(diagram, metrics.getFontMetrics(font), metrics.getFontMetrics(noteFont));
        PngExporter.Result png = PngExporter.render(layout, new DiagramPainter(font, noteFont), 2.0);
        PngExporter.write(png.image(), png.scale(), new File(System.getProperty("user.dir"), "build/diagram-badges.png"));

        ResourcePanel panel = new ResourcePanel(getProject());
        panel.setSummary(com.pino.seqlens.model.ResourceSummary.of(diagram));
        panel.setSize(560, 640);
        layoutRecursively(panel);
        BufferedImage image = new BufferedImage(560, 640, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        panel.printAll(g);
        g.dispose();
        ImageIO.write(image, "png", new File(System.getProperty("user.dir"), "build/resource-panel-classes.png"));
    }

    // 只跑渲染，不重複跑父類別的測試
    @Override
    public void testParticipantsIncludeTables() {
    }

    @Override
    public void testSqlMessages() {
    }

    @Override
    public void testFragments() {
    }

    @Override
    public void testThrows() {
    }
}
