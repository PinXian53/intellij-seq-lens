package com.pino.seqlens.ui;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.pino.seqlens.SeqLensService;
import com.pino.seqlens.export.MermaidExporter;
import com.pino.seqlens.export.PlantUmlExporter;
import com.pino.seqlens.model.DiagramEvent;
import com.pino.seqlens.model.ResourceSummary;
import com.pino.seqlens.model.SequenceDiagram;
import com.pino.seqlens.settings.SeqLensConfigurable;
import com.pino.seqlens.settings.SeqLensSettings;
import com.pino.seqlens.settings.SeqLensSettingsListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Supplier;

/** 工具視窗中一個分頁：工具列 + 可捲動的循序圖 + 資源清單。 */
public final class DiagramView extends JPanel implements Disposable {

    private final Project project;
    private final SmartPsiElementPointer<PsiMethod> root;
    private final DiagramCanvas canvas;
    private final ResourcePanel resources;
    private final OnePixelSplitter splitter = new OnePixelSplitter(false, "SeqLens.resourceSplitter", 0.75f);
    private final EditorNotificationPanel banner = new EditorNotificationPanel(EditorNotificationPanel.Status.Warning);
    private SequenceDiagram diagram;
    private ResourceSummary summary;
    /** 「加深 2 層重新產生」後，重新產生時沿用的深度；null = 使用設定值。 */
    private Integer depthOverride;

    public DiagramView(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiMethod> root, @NotNull SequenceDiagram diagram) {
        super(new BorderLayout());
        this.project = project;
        this.root = root;
        this.canvas = new DiagramCanvas(project);
        this.resources = new ResourcePanel(project);

        JBScrollPane scroll = new JBScrollPane(canvas);
        scroll.setColumnHeaderView(canvas.header);
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        scroll.getHorizontalScrollBar().setUnitIncrement(JBUI.scale(16));
        scroll.setBorder(JBUI.Borders.empty());
        DiagramCanvas.styleViewport(scroll);
        splitter.setFirstComponent(scroll);
        updateResourcePanelVisibility();

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new SimpleAction("Regenerate", "Re-analyze with the current code and settings", AllIcons.Actions.Refresh,
                e -> regenerate()));
        group.addSeparator();
        group.add(new SimpleAction("Zoom In", "Zoom in (Ctrl/⌘ + mouse wheel)", AllIcons.General.ZoomIn, e -> canvas.setZoom(canvas.zoom() * 1.2)));
        group.add(new SimpleAction("Zoom Out", "Zoom out (Ctrl/⌘ + mouse wheel)", AllIcons.General.ZoomOut, e -> canvas.setZoom(canvas.zoom() / 1.2)));
        group.add(new SimpleAction("Actual Size", "Reset zoom to 100%", AllIcons.General.ActualZoom, e -> canvas.setZoom(1.0)));
        group.addSeparator();
        group.add(new SimpleAction("Copy…", "Choose a format and copy to the clipboard", AllIcons.Actions.Copy, e -> showMenu(e, "Copy As", copyMenu())));
        group.add(new SimpleAction("Export…", "Choose a format and save to a file", AllIcons.ToolbarDecorator.Export, e -> showMenu(e, "Export As", exportMenu())));
        group.addSeparator();
        group.add(new ToggleAction("Resources", "Show or hide the list of external APIs, tables and classes used", AllIcons.Actions.ListFiles) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return SeqLensSettings.getInstance().getState().showResourcePanel;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                SeqLensSettings.getInstance().getState().showResourcePanel = state;
                ApplicationManager.getApplication().getMessageBus().syncPublisher(SeqLensSettingsListener.TOPIC).settingsChanged();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        group.add(new SimpleAction("Settings", "Open SeqLens settings", AllIcons.General.Settings,
                e -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SeqLensConfigurable.class)));
        group.addSeparator();
        group.add(new SimpleAction("Close Tab", "Close this sequence diagram", AllIcons.Actions.Close,
                e -> SeqLensToolWindowFactory.close(project, this)));

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("SeqLensToolbar", group, true);
        toolbar.setTargetComponent(this);

        canvas.onMessagePopup(this::showMessagePopup);

        banner.createActionLabel("Regenerate 2 levels deeper", () -> {
            depthOverride = diagram.getMaxDepth() + 2;
            regenerate();
        });
        banner.createActionLabel("Settings", () ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SeqLensConfigurable.class));
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar.getComponent(), BorderLayout.NORTH);
        north.add(banner, BorderLayout.SOUTH);

        add(north, BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);
        setDiagram(diagram);

        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(SeqLensSettingsListener.TOPIC, () -> {
                    // 字體大小等設定變更：重新排版；資源清單開關：顯示 / 隱藏
                    canvas.setDiagram(this.diagram);
                    updateResourcePanelVisibility();
                });
    }

    public @NotNull SmartPsiElementPointer<PsiMethod> root() {
        return root;
    }

    public void setDiagram(@NotNull SequenceDiagram diagram) {
        this.diagram = diagram;
        this.summary = ResourceSummary.of(diagram);
        canvas.setDiagram(diagram);
        resources.setSummary(summary);
        updateBanner();
    }

    private void regenerate() {
        SeqLensService.getInstance(project).generate(root, this, depthOverride);
    }

    /** 達最大深度或達最大呼叫數時，在圖的上方提醒。 */
    private void updateBanner() {
        long limited = diagram.depthLimitedCount();
        StringBuilder text = new StringBuilder();
        if (limited > 0) {
            text.append("Maximum depth ").append(diagram.getMaxDepth()).append(" reached: ")
                    .append(ResourcePanel.plural((int) limited, "call")).append(limited == 1 ? " was" : " were")
                    .append(" not expanded (marked ⋯). Some of the flow may be missing.");
        }
        if (diagram.isTruncated()) {
            text.append(text.isEmpty() ? "" : " ").append("Maximum number of calls reached; the diagram is truncated.");
        }
        banner.setText(text.toString());
        banner.setVisible(!text.isEmpty());
    }

    // ------------------------------------------------------------------ 訊息右鍵選單

    private void showMessagePopup(DiagramLayout.Message message, java.awt.event.MouseEvent event) {
        DefaultActionGroup menu = new DefaultActionGroup();
        menu.add(new SimpleAction("Jump to Source", null, AllIcons.Actions.EditSource,
                e -> Navigation.navigate(project, message.navigation())));
        String key = message.targetKey();
        if (key != null && key.contains("#")) {
            String cls = key.substring(0, key.indexOf('#'));
            String method = key.substring(key.indexOf('#') + 1);
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            menu.addSeparator();
            String methodLabel = method.equals("<init>") ? simple + " constructor" : simple + "#" + method + "()";
            menu.add(new SimpleAction("Don't Expand " + methodLabel, "Add to the stop list: " + key,
                    AllIcons.Actions.Cancel, e -> addStopPattern(key)));
            menu.add(new SimpleAction("Don't Expand Class " + simple, "Add to the stop list: " + cls,
                    AllIcons.Nodes.Class, e -> addStopPattern(cls)));
            if (message.expansion() == DiagramEvent.Expansion.STOPPED) {
                menu.add(new SimpleAction("Edit Stop List…", null, AllIcons.General.Settings,
                        e -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SeqLensConfigurable.class)));
            }
        }
        ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(message.label(), menu,
                com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
        popup.show(new RelativePoint(event));
    }

    private void addStopPattern(String pattern) {
        SeqLensSettings.State state = SeqLensSettings.getInstance().getState();
        String current = state.stopPatterns == null ? "" : state.stopPatterns.strip();
        boolean exists = java.util.Arrays.stream(current.split("[,;\\n]")).map(String::strip).anyMatch(pattern::equals);
        if (!exists) {
            state.stopPatterns = current.isEmpty() ? pattern : current + "\n" + pattern;
        }
        notify("Added to the stop list: " + pattern, NotificationType.INFORMATION);
        regenerate();
    }

    @Override
    public void dispose() {
    }

    private void updateResourcePanelVisibility() {
        boolean show = SeqLensSettings.getInstance().getState().showResourcePanel;
        splitter.setSecondComponent(show ? resources : null);
        splitter.revalidate();
    }

    // ------------------------------------------------------------------ 複製 / 匯出選單

    private DefaultActionGroup copyMenu() {
        DefaultActionGroup menu = new DefaultActionGroup();
        menu.add(new SimpleAction("Image (PNG)", "Paste directly into chats, documents or slides", AllIcons.FileTypes.Image, e -> copyImage()));
        menu.add(new SimpleAction("SVG", null, AllIcons.FileTypes.Xml, e -> copyText("SVG", this::svgText)));
        menu.addSeparator();
        menu.add(new SimpleAction("PlantUML", null, AllIcons.FileTypes.Text, e -> copyText("PlantUML", () -> PlantUmlExporter.export(diagram))));
        menu.add(new SimpleAction("Mermaid", null, AllIcons.FileTypes.Text, e -> copyText("Mermaid", () -> MermaidExporter.export(diagram))));
        menu.addSeparator();
        menu.add(new SimpleAction("Resources (Markdown)", "List of external APIs, tables and classes", AllIcons.Actions.ListFiles,
                e -> copyText("Resources", this::resourceMarkdown)));
        return menu;
    }

    private DefaultActionGroup exportMenu() {
        DefaultActionGroup menu = new DefaultActionGroup();
        menu.add(new SimpleAction("SVG (Vector)", "Stays sharp at any zoom level", AllIcons.FileTypes.Image, e -> exportSvg()));
        menu.add(new SimpleAction("PNG (Image)", "3x resolution", AllIcons.FileTypes.Image, e -> exportPng()));
        menu.addSeparator();
        menu.add(new SimpleAction("PlantUML (.puml)", null, AllIcons.FileTypes.Text,
                e -> exportText("PlantUML", "puml", () -> PlantUmlExporter.export(diagram))));
        menu.add(new SimpleAction("Mermaid (.mmd)", null, AllIcons.FileTypes.Text,
                e -> exportText("Mermaid", "mmd", () -> MermaidExporter.export(diagram))));
        menu.addSeparator();
        menu.add(new SimpleAction("Resources (.md)", "List of external APIs, tables and classes", AllIcons.Actions.ListFiles,
                e -> exportText("Resources", "md", this::resourceMarkdown)));
        return menu;
    }

    /** 依資源清單目前開啟的區塊輸出 Markdown。 */
    private String resourceMarkdown() {
        SeqLensSettings.State s = SeqLensSettings.getInstance().getState();
        return summary.toMarkdown(diagram.getTitle(), s.resourceShowApis, s.resourceShowTables, s.resourceShowClasses);
    }

    private void showMenu(AnActionEvent e, String title, DefaultActionGroup menu) {
        ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(title, menu, e.getDataContext(),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
        Component source = e.getInputEvent() != null ? e.getInputEvent().getComponent() : null;
        if (source instanceof JComponent c && c.isShowing()) {
            popup.show(RelativePoint.getSouthWestOf(c));
        } else {
            popup.showInCenterOf(this);
        }
    }

    // ------------------------------------------------------------------ 複製

    private void copyText(String format, Supplier<String> text) {
        CopyPasteManager.getInstance().setContents(new StringSelection(text.get()));
        notify(format + " copied to the clipboard", NotificationType.INFORMATION);
    }

    private void copyImage() {
        DiagramLayout layout = canvas.diagramLayout();
        if (layout == null) {
            return;
        }
        PngExporter.Result result = PngExporter.render(layout, canvas.painter(), 2.0);
        try {
            // 直接寫入系統剪貼簿：IntelliJ 的 CopyPasteManager 會把內容當文字放進剪貼簿歷史
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new ImageTransferable(result.image()), null);
        } catch (IllegalStateException ex) {
            notify("The clipboard is temporarily unavailable. Please try again.", NotificationType.WARNING);
            return;
        }
        notify("Image copied to the clipboard (" + result.image().getWidth() + "×" + result.image().getHeight() + ")",
                NotificationType.INFORMATION);
    }

    private String svgText() {
        return SvgExporter.export(canvas.diagramLayout(), canvas.painter(), canvas.painter().font());
    }

    // ------------------------------------------------------------------ 匯出

    private @Nullable File chooseFile(String title, String extension) {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(title, "Save the sequence diagram", extension);
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(diagram.getTitle().replaceAll("[^\\w.-]", "_") + "." + extension);
        return wrapper == null ? null : wrapper.getFile();
    }

    private void exportText(String format, String extension, Supplier<String> text) {
        File file = chooseFile("Export " + format, extension);
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), text.get(), StandardCharsets.UTF_8);
            notify(format + " exported: " + file.getAbsolutePath(), NotificationType.INFORMATION);
        } catch (IOException ex) {
            notify("Export failed: " + ex.getMessage(), NotificationType.ERROR);
        }
    }

    private void exportSvg() {
        if (canvas.diagramLayout() != null) {
            exportText("SVG", "svg", this::svgText);
        }
    }

    private void exportPng() {
        DiagramLayout layout = canvas.diagramLayout();
        if (layout == null) {
            return;
        }
        File file = chooseFile("Export PNG", "png");
        if (file == null) {
            return;
        }
        PngExporter.Result result = PngExporter.render(layout, canvas.painter(), PngExporter.DEFAULT_SCALE);
        try {
            PngExporter.write(result.image(), result.scale(), file);
            String size = result.image().getWidth() + "×" + result.image().getHeight();
            String note = result.scale() < PngExporter.DEFAULT_SCALE
                    ? " (diagram too large, reduced to " + result.scale() + "x)" : " (" + result.scale() + "x)";
            notify("PNG exported " + size + note + ": " + file.getAbsolutePath(), NotificationType.INFORMATION);
        } catch (IOException ex) {
            notify("Export failed: " + ex.getMessage(), NotificationType.ERROR);
        }
    }

    private void notify(String message, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("SeqLens")
                .createNotification(message, type)
                .notify(project);
    }

    // ------------------------------------------------------------------

    private static final class SimpleAction extends DumbAwareAction {
        private final java.util.function.Consumer<AnActionEvent> handler;

        SimpleAction(String text, @Nullable String description, Icon icon, java.util.function.Consumer<AnActionEvent> handler) {
            super(text, description, icon);
            this.handler = handler;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            handler.accept(e);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** 圖片剪貼簿內容：只提供 image 格式，要求其他格式時依規範拋 UnsupportedFlavorException。 */
    record ImageTransferable(Image image) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public @NotNull Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
