package org.deju.plugin.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.PayloadCodec;

/**
 * Keeps the last {@value #CAPACITY} executions as {@code .idea/deju/exec-1.json} …
 * {@code exec-5.json} in a ring buffer (oldest dropped), with a small index persisted
 * in the workspace file.
 *
 * <p><b>Security:</b> filenames are fixed integers 1..{@value #CAPACITY}. A path is
 * <i>never</i> derived from socket/payload data, so a hostile payload cannot cause a
 * write outside {@code .idea/deju/} (no path traversal).
 */
@State(name = "DejuExecutionHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DejuHistoryStore
        implements PersistentStateComponent<DejuHistoryStore.IndexState> {

    public static final int CAPACITY = 5;
    private static final Logger LOG = Logger.getInstance(DejuHistoryStore.class);

    /** Serializable index (bean). */
    public static final class IndexState {
        public List<ExecutionEntry> entries = new ArrayList<>(); // most-recent-first
        public long writeCount = 0;
    }

    private IndexState state = new IndexState();
    private final Project project;

    public DejuHistoryStore(Project project) {
        this.project = project;
    }

    public static DejuHistoryStore getInstance(Project project) {
        return project.getService(DejuHistoryStore.class);
    }

    /** Persists a payload into the next ring slot and returns its index entry. */
    public synchronized ExecutionEntry add(DejuPayload payload) throws IOException {
        int slot = (int) (state.writeCount % CAPACITY) + 1; // 1..CAPACITY
        state.writeCount++;

        Path file = slotFile(slot);
        Files.createDirectories(file.getParent());
        writeIgnoreMarker(file.getParent());
        Files.write(file, PayloadCodec.toJson(payload).getBytes(StandardCharsets.UTF_8));

        ExecutionEntry entry = new ExecutionEntry(slot,
                payload.getTarget() == null ? "" : payload.getTarget(),
                payload.getStartedAtIso() == null ? "" : payload.getStartedAtIso(),
                System.currentTimeMillis(),
                payload.getFiles().size(),
                payload.totalLines(),
                payload.getAgentVersion());

        // Drop any previous entry occupying this slot, then push to the front, cap at CAPACITY.
        state.entries.removeIf(e -> e.slot == slot);
        state.entries.add(0, entry);
        while (state.entries.size() > CAPACITY) {
            state.entries.remove(state.entries.size() - 1);
        }
        return entry;
    }

    public synchronized List<ExecutionEntry> entries() {
        return new ArrayList<>(state.entries);
    }

    /** Deletes one stored execution: its index entry and its {@code exec-<slot>.json} file. */
    public synchronized void delete(int slot) {
        state.entries.removeIf(e -> e.slot == slot);
        try {
            Files.deleteIfExists(slotFile(slot));
        } catch (IOException e) {
            LOG.warn("Failed to delete execution " + slot, e);
        }
    }

    /** Deletes every stored execution (all index entries and all ring-slot files). */
    public synchronized void deleteAll() {
        state.entries.clear();
        state.writeCount = 0;
        for (int slot = 1; slot <= CAPACITY; slot++) {
            try {
                Files.deleteIfExists(slotFile(slot));
            } catch (IOException e) {
                LOG.warn("Failed to delete execution " + slot, e);
            }
        }
    }

    /** Loads a stored payload by its fixed slot (1..{@value #CAPACITY}). */
    public @Nullable DejuPayload load(int slot) {
        if (slot < 1 || slot > CAPACITY) {
            return null; // never trust an out-of-range slot
        }
        Path file = slotFile(slot);
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return PayloadCodec.parse(Files.readAllBytes(file));
        } catch (IOException e) {
            LOG.warn("Failed to read execution " + slot, e);
            return null;
        }
    }

    /** {@code <project>/.idea/deju/exec-<slot>.json} with a hardcoded integer name. */
    private Path slotFile(int slot) {
        return dataDir().resolve("exec-" + slot + ".json");
    }

    /** {@code <project>/.idea/deju}: everything this store writes, and nothing else. */
    public Path dataDir() {
        String base = project.getBasePath();
        if (base == null) {
            // Headless/default project fallback, still a fixed, safe location.
            base = System.getProperty("java.io.tmpdir");
        }
        return Paths.get(base, ".idea", "deju");
    }

    /**
     * Drops a {@code .gitignore} containing {@code *} into the data directory.
     *
     * <p>These files sit under {@code .idea/}, which plenty of teams commit in part, and
     * they carry the text of every SQL statement a traced request executed. A self-ignoring
     * directory keeps that out of a commit without touching a {@code .gitignore} the user
     * maintains. Best effort: failing to write it must never lose a recording.
     */
    static void writeIgnoreMarker(Path dir) {   // package-private: tested directly
        if (!Files.isDirectory(dir)) {
            return;
        }
        Path marker = dir.resolve(".gitignore");
        try {
            if (!Files.exists(marker)) {
                Files.write(marker, "# Deju recordings: local only, never committed.\n*\n"
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOG.warn("Could not write " + marker, e);
        }
    }

    @Override
    public @Nullable IndexState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull IndexState loaded) {
        this.state = loaded;
    }
}
