package org.deju.plugin.action;

import javax.swing.Icon;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.DejuController;

/**
 * Gutter icon on each method name, a brand "DJ" mark. Clicking it sets that method as the
 * Deju point. The current Deju point shows the filled brand mark, and gains a small green ✓
 * badge once the plugin is connected to the running JVM/agent, so you can see at a glance
 * that the point is live.
 */
public final class DejuLineMarkerProvider implements LineMarkerProvider {

    // Brand "DJ" marks, no breakpoint dot, so it can't be mistaken for a debugger point.
    private static final Icon POINT = IconLoader.getIcon("/icons/dejuPoint.svg", DejuLineMarkerProvider.class);
    private static final Icon POINT_SET = IconLoader.getIcon("/icons/dejuPointSet.svg", DejuLineMarkerProvider.class);
    private static final Icon POINT_LIVE = IconLoader.getIcon("/icons/dejuPointLive.svg", DejuLineMarkerProvider.class);

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@Nullable PsiElement element) {
        if (!(element instanceof PsiIdentifier)) {
            return null;
        }
        PsiElement parent = element.getParent();
        if (!(parent instanceof PsiMethod)) {
            return null;
        }
        PsiMethod method = (PsiMethod) parent;
        if (method.getNameIdentifier() != element) {
            return null;
        }

        Project project = element.getProject();
        String fq = DejuTargets.fqOf(method);
        DejuController controller = DejuController.getInstance(project);
        boolean isTracePoint = fq != null && fq.equals(controller.getTarget());
        boolean live = isTracePoint && controller.isConnected();

        Icon icon;
        String tooltip;
        if (live) {
            icon = POINT_LIVE;                 // brand DJ + green ✓, set & connected
            tooltip = "Deju point, live";
        } else if (isTracePoint) {
            icon = POINT_SET;                  // brand DJ, set, not connected yet
            tooltip = "Deju point, set (connect to go live)";
        } else {
            icon = POINT;                      // muted DJ, click to set
            tooltip = "Set as Deju point";
        }

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                icon,
                el -> tooltip,
                (event, elt) -> {
                    if (fq != null && elt.getProject() != null) {
                        DejuTargets.apply(elt.getProject(), fq);
                    }
                },
                GutterIconRenderer.Alignment.LEFT,
                () -> "Deju point marker");
    }
}
