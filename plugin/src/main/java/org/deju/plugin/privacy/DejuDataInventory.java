package org.deju.plugin.privacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.history.DejuHistoryStore;
import org.deju.plugin.run.DejuAgentBundle;

/**
 * Everything this plugin has written to disk, in one list.
 *
 * <p>One source of truth for two questions that must never disagree: <i>what does Deju
 * keep about me?</i> and <i>what does Clear remove?</i> Answering those from two separate
 * hardcoded lists is how a plugin ends up promising to delete something it forgot about,
 * so both the disclosure dialog and {@link DejuReset} read this and nothing else.
 *
 * <p>Sizes are read at call time rather than cached: the point of showing them is to be
 * accurate about the moment the user is looking.
 */
public final class DejuDataInventory {

    private static final Logger LOG = Logger.getInstance(DejuDataInventory.class);

    /** Whether an item belongs to one project or to the IDE installation. */
    public enum Scope { PROJECT, APPLICATION }

    /** One thing Deju stores. */
    public static final class Item {
        /** Short name, e.g. "Recorded runs". */
        public final String label;
        /** What it actually contains, in plain words, for the disclosure dialog. */
        public final String contents;
        /** Absolute location, or {@code null} when Deju has written nothing yet. */
        public final @Nullable Path path;
        /** Bytes on disk. Meaningful only when {@link #exclusive} is true. */
        public final long bytes;
        /** How many files make it up. */
        public final int files;
        public final Scope scope;
        /**
         * True when the path is Deju's alone, so its size is meaningful and Clear can
         * delete it outright. False for state that lives inside a file the IDE owns
         * (workspace.xml), where only Deju's own entries are removed.
         */
        public final boolean exclusive;

        Item(String label, String contents, @Nullable Path path, long bytes, int files,
             Scope scope, boolean exclusive) {
            this.label = label;
            this.contents = contents;
            this.path = path;
            this.bytes = bytes;
            this.files = files;
            this.scope = scope;
            this.exclusive = exclusive;
        }

        /** True when there is currently something on disk to remove. */
        public boolean present() {
            return path != null && (files > 0 || !exclusive);
        }
    }

    private DejuDataInventory() {
    }

    /**
     * Reads what is on disk right now.
     *
     * @param project the project whose recordings to include, or {@code null} for only the
     *                application-wide items
     */
    public static List<Item> of(@Nullable Project project) {
        List<Item> items = new ArrayList<>();

        if (project != null) {
            Path runs = DejuHistoryStore.getInstance(project).dataDir();
            Sum s = measure(runs, ".json");
            items.add(new Item("Recorded runs",
                    "For each of the last " + DejuHistoryStore.capacity() + " traced calls: class and"
                            + " source file names, which lines ran and their branch outcome, per-line"
                            + " and per-method timings, and the text of any SQL statement executed."
                            + " No variable values, no bound SQL parameters, and no source code.",
                    runs, s.bytes, s.files, Scope.PROJECT, true));

            items.add(new Item("Exclusion rules and the run index",
                    "Which type patterns you fold out of reports, any per-class overrides, and a"
                            + " short index naming the runs above. Stored inside the IDE's own"
                            + " workspace file, which is normally not committed.",
                    workspaceFile(project), 0, 0, Scope.PROJECT, false));
        }

        Path settings = Paths.get(PathManager.getConfigPath(), "options", "DejuTrace.xml");
        boolean hasSettings = Files.isRegularFile(settings);
        items.add(new Item("Connection settings",
                "Host, port and the shared token the agent must match, plus your instrumented"
                        + " package prefixes, source roots, and the editor-tab limit.",
                hasSettings ? settings : null, sizeOf(settings), hasSettings ? 1 : 0,
                Scope.APPLICATION, true));

        Path agents = DejuAgentBundle.managedDir();
        Sum a = measure(agents, ".jar");
        items.add(new Item("Extracted agent",
                "A copy of the agent jar shipped inside the plugin, unpacked because a"
                        + " -javaagent flag needs a real file path. Program code only, nothing"
                        + " about you or your project.",
                agents, a.bytes, a.files, Scope.APPLICATION, true));

        return items;
    }

    /** Total bytes Deju can account for; state inside shared IDE files is not counted. */
    public static long totalBytes(List<Item> items) {
        long total = 0;
        for (Item i : items) {
            if (i.exclusive) {
                total += i.bytes;
            }
        }
        return total;
    }

    /** {@code <project>/.idea/workspace.xml}, or {@code null} for a project with no base path. */
    private static @Nullable Path workspaceFile(Project project) {
        String base = project.getBasePath();
        return base == null ? null : Paths.get(base, ".idea", "workspace.xml");
    }

    private static final class Sum {
        final long bytes;
        final int files;

        Sum(long bytes, int files) {
            this.bytes = bytes;
            this.files = files;
        }
    }

    /** Sums the direct children of {@code dir} with the given suffix. Never recurses. */
    private static Sum measure(@Nullable Path dir, String suffix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return new Sum(0, 0);
        }
        long bytes = 0;
        int files = 0;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : (Iterable<Path>) children::iterator) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix)) {
                    bytes += sizeOf(p);
                    files++;
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not measure " + dir, e);
        }
        return new Sum(bytes, files);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Byte count for a label, in the units a person reads. */
    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "nothing";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
