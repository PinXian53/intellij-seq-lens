package com.pino.seqlens.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.pino.seqlens.settings.SeqLensSettings;
import com.pino.seqlens.settings.SeqLensSettingsListener;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.content.ContentManager;
import com.pino.seqlens.model.SequenceDiagram;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** SeqLens 工具視窗。 */
public final class SeqLensToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final String ID = "SeqLens";

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        applyAnchor(toolWindow);
        Project project = toolWindow.getProject();
        ApplicationManager.getApplication().getMessageBus().connect(project)
                .subscribe(SeqLensSettingsListener.TOPIC, () -> applyAnchor(toolWindow));
    }

    /** 依設定移動工具視窗（右側 / 下方 / 左側）。 */
    private static void applyAnchor(ToolWindow toolWindow) {
        ToolWindowAnchor target = switch (SeqLensSettings.getInstance().getState().toolWindowAnchor) {
            case "BOTTOM" -> ToolWindowAnchor.BOTTOM;
            case "LEFT" -> ToolWindowAnchor.LEFT;
            default -> ToolWindowAnchor.RIGHT;
        };
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!toolWindow.isDisposed() && toolWindow.getAnchor() != target) {
                toolWindow.setAnchor(target, null);
            }
        });
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager manager = toolWindow.getContentManager();
        manager.addContent(createEmptyContent());
        // 分頁全部關閉後回到提示畫面
        manager.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                if (!manager.isDisposed() && manager.getContentCount() == 0) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!manager.isDisposed() && manager.getContentCount() == 0) {
                            manager.addContent(createEmptyContent());
                        }
                    });
                }
            }
        });
        // 標題列：關閉全部分頁（只有一個分頁時 IntelliJ 不顯示分頁列，所以另外提供按鈕）
        toolWindow.setTitleActions(List.of(new DumbAwareAction("Close All Tabs", "Close all sequence diagram tabs", AllIcons.Actions.GC) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                for (Content c : manager.getContents()) {
                    if (!Boolean.TRUE.equals(c.getUserData(EMPTY_KEY))) {
                        manager.removeContent(c, true);
                    }
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(Arrays.stream(manager.getContents())
                        .anyMatch(c -> !Boolean.TRUE.equals(c.getUserData(EMPTY_KEY))));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        }));
    }

    /** 關閉某個循序圖所在的分頁。 */
    static void close(@NotNull Project project, @NotNull DiagramView view) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow == null) {
            return;
        }
        Content content = toolWindow.getContentManager().getContent(view);
        if (content != null) {
            toolWindow.getContentManager().removeContent(content, true);
        }
    }

    static Content createEmptyContent() {
        JBPanelWithEmptyText panel = new JBPanelWithEmptyText();
        panel.getEmptyText().setText("No sequence diagram yet");
        panel.getEmptyText().appendSecondaryText("Place the caret in a Java method and choose 'SeqLens: Generate Sequence Diagram' from the context menu", 
                com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(false);
        content.putUserData(EMPTY_KEY, Boolean.TRUE);
        return content;
    }

    /** 顯示循序圖：若 {@code target} 仍在工具視窗中則就地更新，否則新增分頁。 */
    public static void show(@NotNull Project project, @NotNull SmartPsiElementPointer<PsiMethod> root,
                            @NotNull SequenceDiagram diagram, @Nullable DiagramView target) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (toolWindow == null) {
            return;
        }
        ContentManager manager = toolWindow.getContentManager();
        Content existing = target != null ? manager.getContent(target) : null;
        if (existing != null) {
            target.setDiagram(diagram);
            manager.setSelectedContent(existing);
        } else {
            for (Content c : manager.getContents()) {
                if (Boolean.TRUE.equals(c.getUserData(EMPTY_KEY))) {
                    manager.removeContent(c, true);
                }
            }
            DiagramView view = new DiagramView(project, root, diagram);
            Content content = ContentFactory.getInstance().createContent(view, diagram.getTitle(), false);
            content.setCloseable(true);
            content.setDisposer(view);
            manager.addContent(content);
            manager.setSelectedContent(content);
        }
        toolWindow.activate(null);
    }

    static final com.intellij.openapi.util.Key<Boolean> EMPTY_KEY = com.intellij.openapi.util.Key.create("SeqLens.empty");
}
