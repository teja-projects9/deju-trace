package org.deju.plugin.privacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.DejuSettings;
import org.deju.plugin.exclude.DejuExclusions;
import org.deju.plugin.history.DejuHistoryStore;
import org.deju.plugin.run.DejuAgentBundle;

/**
 * Puts the plugin back to how it was the day it was installed.
 *
 * <p>Deletes what {@link DejuDataInventory} lists and nothing else, so the promise made by
 * the confirmation dialog is the promise the code keeps. Anything that cannot be removed is
 * reported rather than swallowed: a "cleared everything" message over a failed delete is
 * worse than no button at all.
 *
 * <p>Exported HTML reports are deliberately out of scope. The user chose where those went
 * and they are ordinary documents; deleting files from arbitrary places on disk is not
 * something a cleanup button should be doing.
 */
public final class DejuReset {

    private static final Logger LOG = Logger.getInstance(DejuReset.class);

    /** What a reset actually managed to do. */
    public static final class Report {
        public final long bytesFreed;
        public final int filesDeleted;
        /** Human-readable reasons, empty when everything succeeded. */
        public final List<String> failures;

        Report(long bytesFreed, int filesDeleted, List<String> failures) {
            this.bytesFreed = bytesFreed;
            this.filesDeleted = filesDeleted;
            this.failures = failures;
        }

        public boolean clean() {
            return failures.isEmpty();
        }
    }

    private DejuReset() {
    }

    /**
     * Clears everything: this project's recordings and rules, plus the application-wide
     * settings and extracted agent jars.
     *
     * @param project        the project whose recordings to remove, or {@code null} for
     *                       application-level data only
     * @param includeAppData whether to also reset settings and delete the extracted agent
     */
    public static Report clearAll(@Nullable Project project, boolean includeAppData) {
        List<String> failures = new ArrayList<>();
        long freed = 0;
        int deleted = 0;

        if (project != null) {
            Counted runs = clearProject(project, failures);
            freed += runs.bytes;
            deleted += runs.files;
        }

        if (includeAppData) {
            DejuSettings.getInstance().resetToDefaults();
            Counted agents = deleteTree(DejuAgentBundle.managedDir(), failures);
            freed += agents.bytes;
            deleted += agents.files;
        }
        return new Report(freed, deleted, failures);
    }

    /** Removes one project's recordings, run index and exclusion rules. */
    private static Counted clearProject(Project project, List<String> failures) {
        DejuHistoryStore history = DejuHistoryStore.getInstance(project);
        Path dir = history.dataDir();
        // Clear the index first: if deleting the files then fails, the UI still shows an
        // empty history rather than entries whose payloads may or may not still be there.
        history.deleteAll();
        DejuExclusions.getInstance(project).resetToDefaults();
        return deleteTree(dir, failures);
    }

    /**
     * Deletes a directory and its contents, deepest first.
     *
     * <p>Both directories this is called with are created and owned by the plugin, so there
     * is no case where it walks into something the user put there. It still refuses to
     * follow symlinks, because a recursive delete that follows links is one misplaced
     * symlink away from deleting a user's source tree.
     */
    static Counted deleteTree(@Nullable Path dir, List<String> failures) {   // package-private: tested directly
        if (dir == null || !Files.isDirectory(dir)) {
            return new Counted(0, 0);
        }
        long bytes = 0;
        int files = 0;
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entries::add);
        } catch (IOException e) {
            failures.add("Could not read " + dir + " (" + e.getMessage() + ")");
            return new Counted(0, 0);
        }
        for (Path p : entries) {
            try {
                if (Files.isSymbolicLink(p)) {
                    Files.deleteIfExists(p);
                    continue;
                }
                boolean regular = Files.isRegularFile(p);
                long size = regular ? Files.size(p) : 0;
                Files.deleteIfExists(p);
                if (regular) {
                    bytes += size;
                    files++;
                }
            } catch (IOException e) {
                // A locked agent jar is the expected case on Windows while a traced JVM
                // holds it open, so name the file rather than failing the whole reset.
                failures.add(p.getFileName() + ": " + e.getMessage());
                LOG.warn("Could not delete " + p, e);
            }
        }
        return new Counted(bytes, files);
    }

    /**
     * Best-effort cleanup for plugin uninstall, covering every project currently open.
     *
     * <p>Deliberately not the same entry point as the button: at uninstall time there is no
     * user watching a dialog, so failures are logged and nothing is reported back.
     */
    static void clearEverythingQuietly() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) {
                continue;
            }
            try {
                clearProject(project, new ArrayList<>());
            } catch (RuntimeException e) {
                LOG.warn("Uninstall cleanup failed for " + project.getName(), e);
            }
        }
        DejuSettings.getInstance().resetToDefaults();
        deleteTree(DejuAgentBundle.managedDir(), new ArrayList<>());
    }

    static final class Counted {
        final long bytes;
        final int files;

        Counted(long bytes, int files) {
            this.bytes = bytes;
            this.files = files;
        }
    }
}
