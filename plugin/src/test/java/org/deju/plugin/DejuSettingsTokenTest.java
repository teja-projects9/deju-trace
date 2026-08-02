package org.deju.plugin;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token guards the agent's control socket, and with {@code bind=0.0.0.0} it is the only
 * thing that does. These tests exist to stop a fixed default reappearing.
 */
class DejuSettingsTokenTest {

    @Test
    void everyTokenIsDifferent() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(DejuSettings.newToken());
        }
        assertEquals(200, seen.size(), "a repeated token would mean a broken random source");
    }

    @Test
    void tokenSurvivesBeingPastedIntoAVmOptionUnquoted() {
        // A VM option is split on whitespace and the agent argument list on commas and '=',
        // so any of those in a token would silently truncate it or stop the JVM starting.
        for (int i = 0; i < 200; i++) {
            String token = DejuSettings.newToken();
            assertTrue(token.matches("[A-Za-z0-9_-]+"),
                    () -> "token must stay in the URL-safe alphabet, was: " + token);
            assertEquals(22, token.length(), "128 bits, base64url, unpadded");
        }
    }

    @Test
    void freshSettingsCarryAGeneratedTokenRatherThanAPublishedOne() {
        assertNotEquals(new DejuSettings().token, new DejuSettings().token,
                "two installs must not share a secret that is printed in the plugin source");
    }

    @Test
    void resettingRestoresDefaultsAndIssuesANewToken() {
        DejuSettings settings = new DejuSettings();
        String original = settings.token;
        settings.host = "10.0.0.9";
        settings.port = 9999;
        settings.token = "leaked";
        settings.autoAttach = true;
        settings.includes = "com.example";
        settings.maxOpenFiles = 3;
        settings.containerOrRemoteJvm = true;

        settings.resetToDefaults();

        assertEquals(DejuSettings.DEFAULT_HOST, settings.host);
        assertEquals(DejuSettings.DEFAULT_PORT, settings.port);
        assertEquals(DejuSettings.DEFAULT_AUTO_ATTACH, settings.autoAttach);
        assertEquals(DejuSettings.DEFAULT_INCLUDES, settings.includes);
        assertEquals(DejuSettings.DEFAULT_MAX_OPEN_FILES, settings.maxOpenFiles);
        assertEquals(DejuSettings.DEFAULT_CONTAINER_OR_REMOTE_JVM, settings.containerOrRemoteJvm);
        assertNotEquals("leaked", settings.token, "clearing data must not leave the old secret");
        assertNotEquals(original, settings.token, "nor restore the one it started with");
    }
}
