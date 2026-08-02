package org.deju.plugin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Right-click "Add Deju Point": fills the tool-window target field with the
 * fully-qualified signature of the method under the caret.
 */
public final class AddDejuPointAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(methodAtCaret(e) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiMethod method = methodAtCaret(e);
        if (method == null || e.getProject() == null) {
            return;
        }
        String fq = DejuTargets.fqOf(method);
        if (fq != null) {
            DejuTargets.apply(e.getProject(), fq);
        }
    }

    private static @Nullable PsiMethod methodAtCaret(AnActionEvent e) {
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (file == null || editor == null) {
            return null;
        }
        Caret caret = editor.getCaretModel().getPrimaryCaret();
        PsiElement element = file.findElementAt(caret.getOffset());
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }
}
