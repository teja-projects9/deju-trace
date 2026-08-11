package org.deju.plugin.exclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-project set of types the report folds away by default.
 *
 * <p>Stored in {@code .idea/workspace.xml} rather than a committed file: an exclusion list
 * is a reading preference about one codebase, so it belongs to the developer and to this
 * project, package prefixes from one repository are meaningless in the next. Workspace
 * storage is also normally untracked, so nothing here reaches a teammate's checkout.
 *
 * <p>Nothing here changes what the agent records. Exclusions are resolved at export time
 * and the report keeps the complete call tree, so its "Full" detail level always shows
 * every frame that actually executed.
 */
@State(name = "DejuExclusions", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DejuExclusions implements PersistentStateComponent<DejuExclusions.ExclusionState> {

    /** Serializable bean; public mutable fields are what the XML serializer supports. */
    public static final class ExclusionState {
        /** Ticked entries from {@link TypeExclusionMatcher#GENERIC_PATTERNS}. */
        public List<String> genericPatterns = new ArrayList<>();
        /** Raw contents of the custom-pattern text box, one glob per line. */
        public String customPatterns = "";
        /** Individually ticked classes, by fully-qualified name. */
        public List<String> excludedClasses = new ArrayList<>();
        /**
         * Whole packages ticked in the dialog's "By package" tab, as dotted names.
         *
         * <p>Stored as package names rather than as the {@code com.acme.dto.*} globs they
         * compile to, so the tab can show the tick it produced. A glob folded into
         * {@link #customPatterns} would be indistinguishable from something the user typed,
         * and unticking the package would then have to edit their text box.
         */
        public List<String> excludedPackages = new ArrayList<>();
        /** Classes explicitly kept visible; these outrank any matching pattern. */
        public List<String> keptClasses = new ArrayList<>();
        /**
         * Set once the user has opened the dialog and applied anything.
         *
         * <p>Without this, a user who deliberately unticks every generic pattern would find
         * the defaults silently restored on the next IDE start, since an empty list is
         * otherwise indistinguishable from a project that has never been configured.
         */
        public boolean configured = false;
    }

    private ExclusionState state = defaults();

    public static DejuExclusions getInstance(Project project) {
        return project.getService(DejuExclusions.class);
    }

    private static ExclusionState defaults() {
        ExclusionState s = new ExclusionState();
        s.genericPatterns = new ArrayList<>(TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS);
        return s;
    }

    // ------------------------------------------------------------------ access ---

    public synchronized List<String> genericPatterns() {
        return new ArrayList<>(state.genericPatterns);
    }

    public synchronized String customPatterns() {
        return state.customPatterns == null ? "" : state.customPatterns;
    }

    public synchronized List<String> excludedClasses() {
        return new ArrayList<>(state.excludedClasses);
    }

    public synchronized List<String> excludedPackages() {
        return new ArrayList<>(state.excludedPackages);
    }

    public synchronized List<String> keptClasses() {
        return new ArrayList<>(state.keptClasses);
    }

    /**
     * Replaces the whole configuration; called when the settings page is applied.
     *
     * <p>Leaves {@link ExclusionState#excludedPackages} alone: the settings page has no
     * package tab, and silently dropping the dialog's package ticks because an unrelated
     * field was edited would be the worst kind of surprise.
     */
    public synchronized void update(List<String> generic, String custom,
                                    List<String> excluded, List<String> kept) {
        state.genericPatterns = new ArrayList<>(generic);
        state.customPatterns = custom == null ? "" : custom;
        state.excludedClasses = dedup(excluded);
        state.keptClasses = dedup(kept);
        state.configured = true;
    }

    /** As {@link #update}, plus the package tab's ticks. Called when the dialog is applied. */
    public synchronized void update(List<String> generic, String custom,
                                    List<String> excluded, List<String> kept,
                                    List<String> packages) {
        update(generic, custom, excluded, kept);
        state.excludedPackages = dedup(packages);
    }

    /**
     * Forgets everything the user configured and returns to the shipped defaults.
     *
     * <p>Clears {@code configured} too, so the project is genuinely indistinguishable from
     * one that has never opened the dialog rather than one that chose the defaults.
     */
    public synchronized void resetToDefaults() {
        state = defaults();
    }

    /**
     * Every active glob: the ticked generic ones, the ticked packages, and the custom box.
     *
     * <p>Packages arrive as globs here rather than as a separate concept, so every consumer,
     * the painter, the report, the dialog's own preview, keeps working off one rule list and
     * cannot disagree about what a package tick means.
     */
    public synchronized List<String> activePatterns() {
        Set<String> all = new LinkedHashSet<>(state.genericPatterns);
        for (String pkg : state.excludedPackages) {
            if (pkg != null && !pkg.trim().isEmpty()) {
                all.add(PackageGroups.globFor(pkg.trim()));
            }
        }
        all.addAll(TypeExclusionMatcher.parsePatternText(state.customPatterns));
        return new ArrayList<>(all);
    }

    /** A matcher over the current configuration. Cheap enough to build per use. */
    public synchronized TypeExclusionMatcher matcher() {
        return new TypeExclusionMatcher(activePatterns(), state.excludedClasses, state.keptClasses);
    }

    private static List<String> dedup(List<String> in) {
        return in == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(in));
    }

    // -------------------------------------------------- PersistentStateComponent ---

    @Override
    public @Nullable ExclusionState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull ExclusionState loaded) {
        // A project saved before this feature existed has no <DejuExclusions> element at
        // all, so loadState is never called and the defaults above stand. Once it has been
        // configured, take what was stored verbatim, including deliberate emptiness.
        this.state = loaded;
        if (loaded.genericPatterns == null) {
            loaded.genericPatterns = new ArrayList<>();
        }
        if (loaded.excludedClasses == null) {
            loaded.excludedClasses = new ArrayList<>();
        }
        if (loaded.keptClasses == null) {
            loaded.keptClasses = new ArrayList<>();
        }
        // Absent from every workspace.xml written before the package tab existed.
        if (loaded.excludedPackages == null) {
            loaded.excludedPackages = new ArrayList<>();
        }
    }
}
