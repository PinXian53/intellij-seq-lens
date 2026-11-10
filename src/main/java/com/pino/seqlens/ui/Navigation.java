package com.pino.seqlens.ui;

import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

/** 點擊後跳轉到對應程式碼。 */
final class Navigation {

    private Navigation() {
    }

    static void navigate(Project project, @Nullable SmartPsiElementPointer<? extends PsiElement> pointer) {
        if (pointer == null || project.isDisposed()) {
            return;
        }
        ReadAction.nonBlocking(() -> {
                    PsiElement element = pointer.getElement();
                    if (element == null || !element.isValid()) {
                        return null;
                    }
                    PsiElement target = element.getNavigationElement();
                    Navigatable descriptor = PsiNavigationSupport.getInstance().getDescriptor(target);
                    if (descriptor == null && target instanceof Navigatable n) {
                        descriptor = n;
                    }
                    return descriptor;
                })
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), descriptor -> {
                    if (descriptor != null && descriptor.canNavigate()) {
                        descriptor.navigate(true);
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }
}
