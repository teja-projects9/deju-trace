package org.deju.plugin.source;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

/**
 * Opens a traced class in the editor, at the method the recording entered it through.
 *
 * <p>Complements {@link SourceResolver}, which answers "which file", with "and where in
 * it". Both halves matter: a 900-line service opened at line 1 is barely more useful than
 * not opening it, and the whole point of jumping from a trace is to land on the frame you
 * were reading about.
 *
 * <p>Two ways to find the line, in this order:
 *
 * <ul>
 *   <li><b>The PSI declaration.</b> Exact, and it lands on the signature, so the method
 *   reads from the top with its painted coverage below it.</li>
 *   <li><b>The recorded line.</b> Used when the index is unavailable or disagrees, an
 *   IDE that is still indexing, a class resolved out of a configured source root that the
 *   index knows nothing about, or a method the source no longer declares. Always available,
 *   because the agent recorded it.</li>
 * </ul>
 *
 * <p>Deliberately built from public platform API only. {@code PsiNavigateUtil} and the
 * navigation helpers around it are convenient but carry {@code @ApiStatus.Internal} in
 * places, and the Marketplace verifier reports those as problems; {@link OpenFileDescriptor}
 * does the same job with a guarantee it will still be there next release.
 */
public final class TypeNavigator {

    private TypeNavigator() {
    }

    /**
     * Opens {@code fqClassName} and puts the caret on {@code methodName}.
     *
     * <p>Must be called on the EDT; it opens an editor.
     *
     * @param recordedLine 1-based line to fall back to, or {@code 0} for none
     * @return {@code false} when the class has no source in this project, in which case
     *         nothing was opened and the caller should say so
     */
    public static boolean open(Project project, String fqClassName,
                               @Nullable String sourceFileName,
                               @Nullable String methodName,
                               int recordedLine) {
        VirtualFile file = SourceResolver.resolve(project, fqClassName, sourceFileName);
        if (file == null || !file.isValid()) {
            return false;
        }
        int offset = declarationOffset(project, file, fqClassName, methodName);
        OpenFileDescriptor descriptor = offset >= 0
                ? new OpenFileDescriptor(project, file, offset)
                // OpenFileDescriptor counts lines from zero; the agent counts from one.
                : new OpenFileDescriptor(project, file, Math.max(0, recordedLine - 1), 0);
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        return true;
    }

    /**
     * Character offset of {@code methodName}'s declaration, or {@code -1} if PSI cannot say.
     *
     * <p>Bails out during indexing rather than blocking the click: the recorded line is
     * already a good answer and waiting for a full index to open one file is not.
     */
    private static int declarationOffset(Project project, VirtualFile file,
                                         String fqClassName, @Nullable String methodName) {
        if (methodName == null || methodName.isEmpty()
                || DumbService.getInstance(project).isDumb()) {
            return -1;
        }
        return ReadAction.compute(() -> {
            PsiClass psiClass = findClass(project, fqClassName);
            if (psiClass == null) {
                return -1;
            }
            PsiElement navigable = psiClass.getNavigationElement();
            PsiFile psiFile = (navigable != null ? navigable : psiClass).getContainingFile();
            // A source root can hand back a copy of the file the index never saw. Offsets
            // from the indexed copy would then point into a different text.
            if (psiFile == null || !file.equals(psiFile.getVirtualFile())) {
                return -1;
            }
            PsiElement target = member(psiClass, methodName);
            return target == null ? -1 : target.getTextOffset();
        });
    }

    /** The named method, a constructor for {@code <init>}, or the class itself. */
    @Nullable
    private static PsiElement member(PsiClass psiClass, String methodName) {
        if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) {
            PsiMethod[] constructors = psiClass.getConstructors();
            // An implicit constructor has no declaration to land on; the class will do.
            return constructors.length > 0 ? constructors[0] : psiClass;
        }
        PsiMethod[] byName = psiClass.findMethodsByName(methodName, false);
        if (byName.length > 0) {
            // Overloads are indistinguishable from the payload's bare method name, and the
            // first declared one is the least surprising of the wrong answers.
            return byName[0];
        }
        // Inherited, synthetic, or renamed since the recording: open the class instead.
        return psiClass;
    }

    /**
     * Resolves the exact class, nested types included.
     *
     * <p>The agent sends binary names ({@code com.acme.Order$Builder}), which
     * {@code findClass} does not accept; the dotted form of the same name is what it
     * indexes. The outer class is the fallback, since it owns the file either way.
     */
    @Nullable
    private static PsiClass findClass(Project project, String fqClassName) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        // Project source first, for the same reason SourceResolver prefers it: a compiled
        // copy of the same class on the classpath has no text to put a caret in.
        for (GlobalSearchScope scope : new GlobalSearchScope[] {
                GlobalSearchScope.projectScope(project), GlobalSearchScope.allScope(project)}) {
            PsiClass exact = facade.findClass(fqClassName.replace('$', '.'), scope);
            if (exact != null) {
                return exact;
            }
            PsiClass outer = facade.findClass(SourceResolver.outerClassName(fqClassName), scope);
            if (outer != null) {
                return outer;
            }
        }
        return null;
    }
}
