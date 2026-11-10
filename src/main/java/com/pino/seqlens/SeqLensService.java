package com.pino.seqlens;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.pino.seqlens.analysis.SequenceAnalyzer;
import com.pino.seqlens.model.SequenceDiagram;
import com.pino.seqlens.settings.SeqLensSettings;
import com.pino.seqlens.ui.DiagramView;
import com.pino.seqlens.ui.SeqLensToolWindowFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 在背景分析方法並把結果顯示到工具視窗。 */
@Service(Service.Level.PROJECT)
public final class SeqLensService {

    private final Project project;

    public SeqLensService(@NotNull Project project) {
        this.project = project;
    }

    public static SeqLensService getInstance(@NotNull Project project) {
        return project.getService(SeqLensService.class);
    }

    public void generate(@NotNull SmartPsiElementPointer<PsiMethod> root, @Nullable DiagramView target) {
        generate(root, target, null);
    }

    /**
     * @param maxDepthOverride 只對這次分析生效的最大深度（例如「加深 2 層重新產生」），null 代表使用設定值
     */
    public void generate(@NotNull SmartPsiElementPointer<PsiMethod> root, @Nullable DiagramView target,
                         @Nullable Integer maxDepthOverride) {
        SeqLensSettings.State settings = SeqLensSettings.getInstance().getState().copy();
        if (maxDepthOverride != null) {
            settings.maxDepth = maxDepthOverride;
        }
        new Task.Backgroundable(project, "SeqLens: Analyzing call flow…", true) {
            private SequenceDiagram diagram;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                diagram = ReadAction.nonBlocking(() -> {
                            PsiMethod method = root.getElement();
                            return method == null ? null : new SequenceAnalyzer(project, settings).analyze(method);
                        })
                        .inSmartMode(project)
                        .wrapProgress(indicator)
                        .executeSynchronously();
            }

            @Override
            public void onSuccess() {
                if (diagram == null) {
                    NotificationGroupManager.getInstance().getNotificationGroup("SeqLens")
                            .createNotification("Method not found. It may have been deleted or renamed.", NotificationType.WARNING)
                            .notify(project);
                    return;
                }
                SeqLensToolWindowFactory.show(project, root, diagram, target);
            }
        }.queue();
    }
}
