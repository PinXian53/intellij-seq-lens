package com.pino.seqlens.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.pino.seqlens.SeqLensService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 從游標所在（或選取）的 Java 方法產生循序圖。 */
public final class GenerateSequenceDiagramAction extends DumbAwareAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = project != null && findMethod(e) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
        if (enabled && DumbService.isDumb(project)) {
            e.getPresentation().setEnabled(false);
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiMethod method = findMethod(e);
        if (project == null || method == null) {
            return;
        }
        SeqLensService.getInstance(project).generate(SmartPointerManager.createPointer(method), null);
    }

    private static @Nullable PsiMethod findMethod(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (editor != null && file != null) {
            PsiElement at = file.findElementAt(editor.getCaretModel().getOffset());
            PsiMethod method = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
            if (method != null) {
                return method;
            }
        }
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (element instanceof PsiMethod method && method.getBody() != null) {
            return method;
        }
        return null;
    }
}
