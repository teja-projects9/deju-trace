package org.deju.plugin.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recursive delete behaviour for "Clear Deju data"; no IntelliJ fixture needed. */
class DejuResetTest {

    @Test
    void removesEverythingUnderTheDirectoryAndCountsIt(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("deju");
        Files.createDirectories(dir.resolve("nested"));
        Files.write(dir.resolve("exec-1.json"), new byte[100]);
        Files.write(dir.resolve("exec-2.json"), new byte[50]);
        Files.write(dir.resolve("nested/deep.json"), new byte[25]);

        List<String> failures = new ArrayList<>();
        DejuReset.Counted counted = DejuReset.deleteTree(dir, failures);

        assertFalse(Files.exists(dir), "the directory itself goes too, not just its contents");
        assertEquals(3, counted.files);
        assertEquals(175, counted.bytes);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);
    }

    @Test
    void aMissingDirectoryIsNotAFailure(@TempDir Path tmp) {
        List<String> failures = new ArrayList<>();
        DejuReset.Counted counted = DejuReset.deleteTree(tmp.resolve("never-created"), failures);

        assertEquals(0, counted.files);
        assertEquals(0, counted.bytes);
        assertTrue(failures.isEmpty(), "clearing data on a fresh install must be silent");
    }

    /**
     * The guard that matters. Both directories cleared in production are created by the
     * plugin, but a recursive delete that follows symlinks is one stray link away from
     * deleting a source tree, so the link is removed and its target is left alone.
     */
    @Test
    void deletesASymlinkWithoutFollowingItToTheTarget(@TempDir Path tmp) throws IOException {
        Path outside = tmp.resolve("precious");
        Files.createDirectories(outside);
        Files.write(outside.resolve("Source.java"), "keep me".getBytes(StandardCharsets.UTF_8));

        Path dir = tmp.resolve("deju");
        Files.createDirectories(dir);
        Files.write(dir.resolve("exec-1.json"), new byte[10]);
        try {
            Files.createSymbolicLink(dir.resolve("escape"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            return;   // filesystem or OS refuses symlinks; nothing to assert
        }

        DejuReset.deleteTree(dir, new ArrayList<>());

        assertFalse(Files.exists(dir), "the plugin's own directory is still removed");
        assertTrue(Files.exists(outside.resolve("Source.java")),
                "a symlink target outside the managed directory must never be deleted");
    }

    @Test
    void reportsFilesItCouldNotRemoveRatherThanClaimingSuccess(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("deju");
        Files.createDirectories(dir);
        Files.write(dir.resolve("exec-1.json"), new byte[10]);

        // A directory that cannot be traversed stands in for the Windows case of a jar held
        // open by a running JVM: the delete fails and the user has to be told which file.
        Path locked = dir.resolve("locked");
        Files.createDirectories(locked);
        Files.write(locked.resolve("held.jar"), new byte[10]);
        if (!locked.toFile().setWritable(false)) {
            return;   // running as root, or a filesystem that ignores permissions
        }
        try {
            List<String> failures = new ArrayList<>();
            DejuReset.deleteTree(dir, failures);
            assertFalse(failures.isEmpty(), "an undeletable file must be named, not swallowed");
        } finally {
            locked.toFile().setWritable(true);
        }
    }
}
