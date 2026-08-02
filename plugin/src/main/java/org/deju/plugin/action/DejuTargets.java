package org.deju.plugin.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.DejuController;

/** Shared helpers for turning a {@link PsiMethod} into a deju-point target. */
public final class DejuTargets {

    /** Must match the tool-window id declared in plugin.xml. */
    public static final String TOOL_WINDOW_ID = "Deju Trace";

    private DejuTargets() {
    }

    /** Fully-qualified {@code pkg.Class#method}, or null if the class is anonymous/local. */
    public static @Nullable String fqOf(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        if (owner == null) {
            return null;
        }
        String className = owner.getQualifiedName();
        if (className == null) {
            return null;
        }
        return className + "#" + method.getName();
    }

    /** Sets the target on the controller and brings the Deju tool window forward. */
    public static void apply(Project project, String fqTarget) {
        DejuController.getInstance(project).setTarget(fqTarget);
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }
}
