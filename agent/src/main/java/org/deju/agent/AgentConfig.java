package org.deju.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the {@code -javaagent} argument string, e.g.
 * <pre>port=7391,token=dejutoken,includes=com.example.web:com.example.svc,arm=com.example.web.Api#handle</pre>
 *
 * <ul>
 *   <li>{@code port}, socket port for the plugin (phase 3).</li>
 *   <li>{@code token}, shared secret for the socket AUTH handshake.</li>
 *   <li>{@code includes}, colon-separated package prefixes to instrument.</li>
 *   <li>{@code bind}, optional; address to listen on. Defaults to {@code 127.0.0.1}.</li>
 *   <li>{@code arm}, optional; arms a target at startup (console proof, phase 2).</li>
 * </ul>
 *
 * Top-level pairs are comma-separated; {@code includes} entries are colon-separated
 * (so package lists never collide with the comma delimiter).
 */
public final class AgentConfig {

    /** The default, and the only address the agent listens on unless told otherwise. */
    public static final String LOOPBACK = "127.0.0.1";

    private int port = 7391;
    private String token = "";
    private String bind = LOOPBACK;
    private final List<String> includes = new ArrayList<>();
    private String armAtStart;

    public static AgentConfig parse(String args) {
        AgentConfig cfg = new AgentConfig();
        if (args == null || args.isEmpty()) {
            return cfg;
        }
        String[] pairs = args.split(",");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            switch (key) {
                case "port":
                    cfg.port = safePort(value, cfg.port);
                    break;
                case "token":
                    cfg.token = value;
                    break;
                case "bind":
                    // A blank value would otherwise mean "all interfaces" to ServerSocket,
                    // which is the opposite of what an empty setting should do.
                    if (!value.isEmpty()) {
                        cfg.bind = value;
                    }
                    break;
                case "includes":
                    for (String prefix : value.split(":")) {
                        String p = prefix.trim();
                        if (!p.isEmpty()) {
                            cfg.includes.add(p);
                        }
                    }
                    break;
                case "arm":
                    cfg.armAtStart = value;
                    break;
                default:
                    // Unknown keys are ignored (forward compatibility).
                    break;
            }
        }
        return cfg;
    }

    private static int safePort(String value, int fallback) {
        try {
            int p = Integer.parseInt(value);
            if (p >= 1 && p <= 65535) {
                return p;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    public int getPort() {
        return port;
    }

    public String getToken() {
        return token;
    }

    /** Address the control socket listens on; {@value #LOOPBACK} unless {@code bind=} was given. */
    public String getBind() {
        return bind;
    }

    /**
     * Whether the socket would be reachable from outside this machine.
     *
     * <p>Only exact loopback literals count as safe. A hostname is deliberately treated as
     * unsafe without resolving it: name resolution inside a container can differ from the
     * host's, and guessing wrong here would silently open the port.
     */
    public boolean isLoopbackOnly() {
        return LOOPBACK.equals(bind) || "::1".equals(bind) || "localhost".equals(bind);
    }

    public List<String> getIncludes() {
        return Collections.unmodifiableList(includes);
    }

    public String getArmAtStart() {
        return armAtStart;
    }
}
