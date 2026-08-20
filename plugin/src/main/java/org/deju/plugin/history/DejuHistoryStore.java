package org.deju.plugin.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.DejuSettings;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.PayloadCodec;

/**
 * Keeps the last {@link DejuSettings#historyCapacity} executions as
 * {@code .idea/deju/exec-1.json} … {@code exec-<n>.json} in a ring buffer, with a small
 * index persisted in the workspace file. Oldest unpinned entry dropped first; see
 * {@link #nextSlot()}.
 *
 * <p>The configured capacity can change at any time (a settings edit), independent of a
 * recording landing, so nothing here assumes {@code entries.size()} is already within
 * bounds; {@link #trimToCurrentCapacity()} is what enforces it, called both eagerly from
 * the settings page and lazily from every {@link #add}.
 *
 * <p><b>Security:</b> filenames are fixed integers 1..{@value #MAX_CAPACITY}, the hard
 * ceiling the configurable capacity is clamped within. A path is <i>never</i> derived
 * from socket/payload data, so a hostile payload cannot cause a write outside
 * {@code .idea/deju/} (no path traversal), and this stays true whatever the user sets
 * the capacity to.
 */
@State(name = "DejuExecutionHistory", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DejuHistoryStore
        implements PersistentStateComponent<DejuHistoryStore.IndexState> {

    public static final int MIN_CAPACITY = 1;
    /** Hard ceiling: how the "1..N" filenames stay a small, fixed, safe range. */
    public static final int MAX_CAPACITY = 25;
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

    /** The configured capacity, clamped to {@link #MIN_CAPACITY}..{@link #MAX_CAPACITY}. */
    public static int capacity() {
        int configured = DejuSettings.getInstance().historyCapacity;
        return Math.max(MIN_CAPACITY, Math.min(MAX_CAPACITY, configured));
    }

    /** Persists a payload into the next ring slot and returns its index entry. */
    public synchronized ExecutionEntry add(DejuPayload payload) throws IOException {
        trimToCurrentCapacity();
        int slot = nextSlot();
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

        // Drop any previous entry occupying this slot, then push to the front. entries can
        // never exceed capacity() afterwards: nextSlot() only ever names a slot within it
        // (trimToCurrentCapacity above already made room), and the removeIf means at most
        // one entry claims any given slot.
        state.entries.removeIf(e -> e.slot == slot);
        state.entries.add(0, entry);
        return entry;
    }

    /**
     * Which slot a new recording claims: the oldest free one within the current capacity,
     * or — once every one of those has been used — the oldest <em>unpinned</em> entry's
     * slot, so pinning protects a run from the ordinary churn of new recordings landing.
     *
     * <p>If every slot is pinned there is nowhere unpinned left to take, and refusing to
     * record would lose a run that just finished for a reason nothing on screen explains.
     * Reusing the single oldest entry's slot instead means pinning everything only ever
     * costs you your oldest pin, never a silently dropped recording.
     */
    private int nextSlot() {
        int cap = capacity();
        Set<Integer> used = new HashSet<>();
        for (ExecutionEntry e : state.entries) {
            used.add(e.slot);
        }
        for (int s = 1; s <= cap; s++) {
            if (!used.contains(s)) {
                return s;
            }
        }
        // entries is most-recent-first, so the tail is the oldest. A pinned survivor from
        // before a capacity decrease can carry a slot number above the current cap; such an
        // entry is never picked here (trimToCurrentCapacity is what removes it, on count,
        // not on slot number), so this can still return a slot fresh out of nextSlot's own
        // 1..cap range only via the entries actually holding one.
        for (int i = state.entries.size() - 1; i >= 0; i--) {
            if (!state.entries.get(i).pinned && state.entries.get(i).slot <= cap) {
                return state.entries.get(i).slot;
            }
        }
        for (int i = state.entries.size() - 1; i >= 0; i--) {
            if (state.entries.get(i).slot <= cap) {
                return state.entries.get(i).slot;
            }
        }
        // Every entry (if any) sits at a slot above the current cap, left over from a
        // capacity decrease trimToCurrentCapacity hasn't caught up with yet: slot 1 is
        // always safe to claim since nothing in-range currently holds it.
        return 1;
    }

    /**
     * Evicts down to the current capacity — oldest unpinned first, a pin as only a last
     * resort — deleting the underlying files. Called before anything that assumes
     * {@code entries.size()} is already within bounds: the configured capacity can shrink
     * at any time, independent of a recording.
     */
    public synchronized void trimToCurrentCapacity() {
        int cap = capacity();
        while (state.entries.size() > cap) {
            int idx = -1;
            for (int i = state.entries.size() - 1; i >= 0; i--) {
                if (!state.entries.get(i).pinned) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                idx = state.entries.size() - 1; // every remaining entry is pinned
            }
            ExecutionEntry victim = state.entries.remove(idx);
            try {
                Files.deleteIfExists(slotFile(victim.slot));
            } catch (IOException e) {
                LOG.warn("Failed to delete execution " + victim.slot, e);
            }
        }
    }

    public synchronized List<ExecutionEntry> entries() {
        return new ArrayList<>(state.entries);
    }

    /** Pins or unpins one stored execution, protecting (or no longer protecting) it from
     *  being overwritten by {@link #nextSlot()} once every slot has been used at least once. */
    public synchronized void setPinned(int slot, boolean pinned) {
        for (ExecutionEntry e : state.entries) {
            if (e.slot == slot) {
                e.pinned = pinned;
                return;
            }
        }
    }

    /** Sets or clears a user-chosen display name for one stored execution. */
    public synchronized void rename(int slot, String label) {
        String trimmed = label == null ? "" : label.trim();
        for (ExecutionEntry e : state.entries) {
            if (e.slot == slot) {
                e.label = trimmed;
                return;
            }
        }
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
        // MAX_CAPACITY, not the current (possibly lower) capacity: a run left over from
        // before a decrease can still occupy a slot above today's setting, and "delete all"
        // should mean all.
        for (int slot = 1; slot <= MAX_CAPACITY; slot++) {
            try {
                Files.deleteIfExists(slotFile(slot));
            } catch (IOException e) {
                LOG.warn("Failed to delete execution " + slot, e);
            }
        }
    }

    /** Loads a stored payload by its fixed slot (1..{@value #MAX_CAPACITY}). */
    public @Nullable DejuPayload load(int slot) {
        if (slot < 1 || slot > MAX_CAPACITY) {
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
