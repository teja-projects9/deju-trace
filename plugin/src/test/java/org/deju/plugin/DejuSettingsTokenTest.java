package org.deju.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token is a fixed, published value so that {@code token=dejutoken} in a hand-written
 * {@code -javaagent} flag is always correct.
 *
 * <p>These tests pin that down from both ends: the value never varies, and no upgrade path
 * can leave a different one in force. They replace an earlier set that existed to stop a
 * fixed default appearing; the trade-off was made deliberately, and what guards the control
 * socket now is the agent binding to loopback, not this string.
 */
class DejuSettingsTokenTest {

    @Test
    void theTokenIsTheOnePublishedValue() {
        assertEquals("dejutoken", DejuSettings.DEFAULT_TOKEN);
        assertEquals(DejuSettings.DEFAULT_TOKEN, new DejuSettings().token);
    }

    @Test
    void theTokenSurvivesBeingPastedIntoAVmOptionUnquoted() {
        // A VM option is split on whitespace and the agent argument list on commas and '=',
        // so any of those in the token would silently truncate it or stop the JVM starting.
        assertTrue(DejuSettings.DEFAULT_TOKEN.matches("[A-Za-z0-9_-]+"),
                "token must stay in the URL-safe alphabet");
    }

    @Test
    void anInstallationCarryingAnOlderRandomTokenIsNormalisedOnLoad() {
        // Builds before this change generated a token per installation and wrote it to
        // DejuTrace.xml. Honouring that on upgrade would leave the plugin talking to an
        // agent started with token=dejutoken and failing the AUTH handshake.
        DejuSettings stored = new DejuSettings();
        stored.token = "Zq8sV1nR4tK7wLpX0aYbCg";
        stored.host = "10.0.0.9";

        DejuSettings live = new DejuSettings();
        live.loadState(stored);

        assertEquals(DejuSettings.DEFAULT_TOKEN, live.token);
        assertEquals("10.0.0.9", live.host, "every other stored field is still honoured");
    }

    @Test
    void resettingRestoresDefaultsAndKeepsTheToken() {
        DejuSettings settings = new DejuSettings();
        settings.host = "10.0.0.9";
        settings.port = 9999;
        settings.token = "something-else";
        settings.autoAttach = true;
        settings.includes = "com.example";
        settings.maxOpenFiles = 3;
        settings.containerOrRemoteJvm = true;

        settings.resetToDefaults();

        assertEquals(DejuSettings.DEFAULT_HOST, settings.host);
        assertEquals(DejuSettings.DEFAULT_PORT, settings.port);
        assertEquals(DejuSettings.DEFAULT_TOKEN, settings.token);
        assertEquals(DejuSettings.DEFAULT_AUTO_ATTACH, settings.autoAttach);
        assertEquals(DejuSettings.DEFAULT_INCLUDES, settings.includes);
        assertEquals(DejuSettings.DEFAULT_MAX_OPEN_FILES, settings.maxOpenFiles);
        assertEquals(DejuSettings.DEFAULT_CONTAINER_OR_REMOTE_JVM, settings.containerOrRemoteJvm);
    }
}
