package org.deju.plugin.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recorded runs hold the text of every SQL statement a request executed, and they live under
 * {@code .idea/}, which plenty of teams commit in part. The directory ignores itself.
 */
class HistoryIgnoreMarkerTest {

    @Test
    void aRecordingDirectoryIgnoresItself(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("deju");
        Files.createDirectories(dir);

        DejuHistoryStore.writeIgnoreMarker(dir);

        Path marker = dir.resolve(".gitignore");
        assertTrue(Files.isRegularFile(marker), "a .gitignore must be written beside the recordings");
        String body = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
        assertTrue(body.contains("\n*\n") || body.startsWith("*\n"),
                () -> "the pattern must cover every file in the directory, was:\n" + body);
    }

    @Test
    void anExistingMarkerIsLeftAlone(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("deju");
        Files.createDirectories(dir);
        Path marker = dir.resolve(".gitignore");
        Files.write(marker, "# mine\n!keep.json\n".getBytes(StandardCharsets.UTF_8));

        DejuHistoryStore.writeIgnoreMarker(dir);

        assertEquals("# mine\n!keep.json\n",
                new String(Files.readAllBytes(marker), StandardCharsets.UTF_8),
                "a user's own rules in this directory must not be overwritten");
    }

    @Test
    void aMissingDirectoryIsNotFatal(@TempDir Path tmp) {
        // Best effort by design: failing to write the marker must never lose a recording.
        DejuHistoryStore.writeIgnoreMarker(tmp.resolve("never-created"));
    }
}
