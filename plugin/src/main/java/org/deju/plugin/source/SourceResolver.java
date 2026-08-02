package org.deju.plugin.source;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.DejuSettings;

/**
 * Resolves a payload class name (as sent by the agent) to its {@code .java} source
 * {@link VirtualFile} in the open project. Shared by the editor painter and the HTML
 * report so both resolve identically.
 *
 * <p>Two things make this non-trivial in a real backend, and both were silently losing
 * source before:
 *
 * <ul>
 *   <li><b>Compiled copies shadow source.</b> The app's own classes are frequently also
 *   present as a compiled dependency on the classpath (monorepo/Docker builds). Resolving
 *   against {@link GlobalSearchScope#allScope} can return the {@code .class} instead of the
 *   {@code .java}; a {@code .class} has no editable document, so the report renders line
 *   numbers with empty code. We therefore try {@link GlobalSearchScope#projectScope}
 *   (source, no libraries) <em>first</em> and only fall back to {@code allScope}.</li>
 *
 *   <li><b>Inner / anonymous / lambda classes.</b> The agent sends dotted names but keeps
 *   the bytecode {@code $} separator, e.g. {@code com.example.Foo$Bar}. {@code findClass} only
 *   resolves top-level or dotted nested names, so these never matched. The source for any
 *   such class is the outer class's {@code .java} (payload line numbers are absolute within
 *   that file), so we strip everything from the first {@code $} and resolve the outer
 *   class.</li>
 * </ul>
 *
 * <p>All PSI access happens inside a {@link ReadAction}.
 */
public final class SourceResolver {

    private SourceResolver() {
    }

    /**
     * Resolves the source file for {@code fqClassName}, preferring project source over any
     * compiled copy on the classpath.
     *
     * @return the {@code .java} (or attached-source) {@link VirtualFile}, or {@code null}
     *         if the class is not in the project index and has no attached source.
     */
    @Nullable
    public static VirtualFile resolve(Project project, @Nullable String fqClassName) {
        return resolve(project, fqClassName, null);
    }

    /**
     * Resolves the source file for {@code fqClassName}. If the user configured source roots
     * (see {@link DejuSettings#sourceRoots}) and one contains the file, that on-disk copy is
     * used and takes precedence over the IDE index; otherwise resolution falls back to PSI.
     *
     * @param sourceFileName the payload's source file name (e.g. {@code Foo.java}); used only
     *                       for the source-root lookup. May be {@code null}.
     */
    @Nullable
    public static VirtualFile resolve(Project project, @Nullable String fqClassName,
                                      @Nullable String sourceFileName) {
        if (fqClassName == null || fqClassName.isEmpty()) {
            return null;
        }
        VirtualFile fromRoots = fromSourceRoots(fqClassName, sourceFileName);
        if (fromRoots != null) {
            return fromRoots;
        }
        String outer = outerClassName(fqClassName);
        return ReadAction.compute(() -> {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            // Prefer the project's own source; only then consider libraries (which may be
            // a compiled .class with no editable document).
            PsiClass psiClass = facade.findClass(outer, GlobalSearchScope.projectScope(project));
            if (psiClass == null) {
                psiClass = facade.findClass(outer, GlobalSearchScope.allScope(project));
            }
            if (psiClass == null) {
                return null;
            }
            // If we landed on a compiled element that has sources attached, navigate to them.
            PsiElement nav = psiClass.getNavigationElement();
            PsiFile file = nav != null ? nav.getContainingFile() : psiClass.getContainingFile();
            return file == null ? null : file.getVirtualFile();
        });
    }

    /**
     * Looks the file up under the user-configured source roots by fully-qualified name:
     * {@code <root>/<package-as-dirs>/<sourceFileName>}. Returns the first existing match, or
     * {@code null} if no roots are configured or none contain the file. Reads straight from
     * disk (no PSI/index), which is what lets it work when the IDE can't resolve the class.
     */
    @Nullable
    private static VirtualFile fromSourceRoots(String fqClassName, @Nullable String sourceFileName) {
        String roots = DejuSettings.getInstance().sourceRoots;
        if (roots == null || roots.trim().isEmpty()) {
            return null;
        }
        String outer = outerClassName(fqClassName);
        int lastDot = outer.lastIndexOf('.');
        String pkgPath = lastDot >= 0 ? outer.substring(0, lastDot).replace('.', '/') : "";
        String leaf = (sourceFileName != null && !sourceFileName.isEmpty())
                ? sourceFileName
                : (lastDot >= 0 ? outer.substring(lastDot + 1) : outer) + ".java";
        String rel = pkgPath.isEmpty() ? leaf : pkgPath + "/" + leaf;

        for (String root : roots.split("[;\\n]+")) {
            String base = root.trim().replace('\\', '/');
            if (base.isEmpty()) {
                continue;
            }
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(base + "/" + rel);
            if (vf != null && vf.isValid() && !vf.isDirectory()) {
                return vf;
            }
        }
        return null;
    }

    /**
     * {@code com.example.Foo$Bar$1 -> com.example.Foo}. Package segments never contain {@code $},
     * so the first {@code $} always begins the nested-class portion; the enclosing top-level
     * class owns the {@code .java} file that holds every nested class's lines.
     */
    static String outerClassName(String fqClassName) {
        int dollar = fqClassName.indexOf('$');
        return dollar >= 0 ? fqClassName.substring(0, dollar) : fqClassName;
    }
}
