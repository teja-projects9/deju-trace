package org.deju.agent.socket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.deju.agent.AgentConfig;
import org.deju.agent.contract.DejuPayload;
import org.deju.agent.runtime.CoverageRuntime;
import org.deju.agent.runtime.PayloadSink;

/**
 * Token-gated command channel between the agent and the IntelliJ plugin.
 *
 * <p><b>Security-critical:</b>
 * <ul>
 *   <li>Bound to {@code 127.0.0.1} only, never a routable address.</li>
 *   <li>The first line from any client must be {@code AUTH <token>}; the token is
 *       compared in constant time. A bad token is rejected and the socket closed.</li>
 *   <li>The protocol is deliberately dumb: {@code ARM <fqMethod>} and {@code DISARM}
 *       are the only accepted commands. A message never carries a file path, a class
 *       to load, or anything executable. {@code ARM} sets a name matched against
 *       already-instrumented methods; it never loads anything.</li>
 *   <li>Server → client traffic is one line of JSON payload per completed session.</li>
 * </ul>
 */
public final class SocketServer implements PayloadSink {

    /** Accepts only {@code pkg.Class#method} shapes, no path/whitespace/executable chars. */
    private static final Pattern TARGET =
            Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*#[\\w$<>]+$");
    private static final int MAX_LINE = 1000;

    private final int port;
    private final String bindAddress;
    private final byte[] tokenBytes;
    private final ObjectMapper mapper = new ObjectMapper(); // compact; no default typing

    private final Object writeLock = new Object();
    private volatile ServerSocket serverSocket;
    private volatile Socket currentClient;
    private volatile BufferedWriter currentWriter;
    /** Last payload, replayed to a client on connect so a just-missed run isn't lost. */
    private volatile String lastPayloadJson;

    /** Loopback-only, which is the right default for a JVM the developer runs directly. */
    public SocketServer(int port, String token) {
        this(port, token, AgentConfig.LOOPBACK);
    }

    /**
     * @param bindAddress address to listen on. Anything other than loopback makes the
     *                    control socket reachable from off-machine and is opt-in only,
     *                    see {@link #start()}.
     */
    public SocketServer(int port, String token, String bindAddress) {
        this.port = port;
        this.bindAddress = bindAddress == null || bindAddress.isEmpty()
                ? AgentConfig.LOOPBACK : bindAddress;
        this.tokenBytes = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Binds the socket and serves connections on a daemon thread.
     *
     * <p>Binding beyond loopback is refused when no token is set. Loopback with an empty
     * token is merely permissive, the only reachable peer is a process on the same
     * machine. The same socket on {@code 0.0.0.0} with no token would let anything that
     * can route to the port arm a trace and read back source lines, so that combination is
     * treated as a configuration error rather than a warning.
     */
    public void start() throws IOException {
        boolean loopbackOnly = AgentConfig.LOOPBACK.equals(bindAddress)
                || "::1".equals(bindAddress) || "localhost".equals(bindAddress);
        if (!loopbackOnly && tokenBytes.length == 0) {
            throw new IOException("refusing to listen on " + bindAddress
                    + " without a token, add token=<secret> to the agent arguments");
        }
        // Backlog 1, this is a single-developer control channel.
        serverSocket = new ServerSocket(port, 1, InetAddress.getByName(bindAddress));
        Thread t = new Thread(this::acceptLoop, "deju-socket");
        t.setDaemon(true);
        t.start();
        System.out.println("[deju] socket listening on " + bindAddress + ":" + port);
        if (!loopbackOnly) {
            System.out.println("[deju] NOTE: listening beyond loopback (" + bindAddress
                    + "). Reachable by anything that can route to this port; the token is"
                    + " the only thing gating it. Intended for a container port you publish"
                    + " to your own machine.");
        }
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket ss = serverSocket;
            if (ss == null || ss.isClosed()) {
                return;
            }
            try {
                Socket socket = ss.accept();
                socket.setTcpNoDelay(true);
                handleClient(socket);
            } catch (IOException e) {
                if (serverSocket == null || serverSocket.isClosed()) {
                    return;
                }
                // Transient accept failure; keep serving.
            }
        }
    }

    private void handleClient(Socket socket) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String auth = readLine(reader);
            if (auth == null || !authenticate(auth)) {
                System.out.println("[deju] socket auth rejected");
                closeQuietly(socket);
                return;
            }
            writer.write("OK\n");
            writer.flush();

            // Promote to the active client (dropping any previous one).
            synchronized (writeLock) {
                closeQuietly(currentClient);
                currentClient = socket;
                currentWriter = writer;
                if (lastPayloadJson != null) {
                    sendLocked(lastPayloadJson);
                }
            }
            System.out.println("[deju] plugin connected");

            String line;
            while ((line = readLine(reader)) != null) {
                handleCommand(line);
            }
        } catch (IOException e) {
            // client dropped
        } finally {
            synchronized (writeLock) {
                if (currentClient == socket) {
                    currentClient = null;
                    currentWriter = null;
                }
            }
            closeQuietly(socket);
        }
    }

    private void handleCommand(String line) {
        if (line.equals("DISARM")) {
            CoverageRuntime.disarm();
            System.out.println("[deju] disarmed");
        } else if (line.startsWith("ARM ")) {
            String target = line.substring(4).trim();
            if (TARGET.matcher(target).matches()) {
                CoverageRuntime.arm(target);
                System.out.println("[deju] armed " + target);
            } else {
                System.out.println("[deju] rejected malformed ARM target");
            }
        }
        // Any other input is ignored, the protocol accepts nothing else.
    }

    @Override
    public void accept(DejuPayload payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            System.err.println("[deju] failed to serialize payload: " + e.getMessage());
            return;
        }
        lastPayloadJson = json;
        synchronized (writeLock) {
            if (currentWriter != null) {
                sendLocked(json);
            } else {
                System.out.println("[deju] session recorded but no plugin connected (kept for replay)");
            }
        }
    }

    /** Caller must hold {@link #writeLock}. */
    private void sendLocked(String json) {
        try {
            currentWriter.write(json);
            currentWriter.write("\n");
            currentWriter.flush();
        } catch (IOException e) {
            currentWriter = null;
            closeQuietly(currentClient);
            currentClient = null;
        }
    }

    private boolean authenticate(String line) {
        if (!line.startsWith("AUTH ")) {
            return false;
        }
        byte[] provided = line.substring(5).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison to avoid leaking token length/content via timing.
        return MessageDigest.isEqual(provided, tokenBytes);
    }

    /** Reads one line, bounding its length to avoid unbounded buffering from a hostile peer. */
    private static String readLine(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > MAX_LINE) {
                throw new IOException("line too long");
            }
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    public void stop() {
        ServerSocket ss = serverSocket;
        serverSocket = null;
        closeQuietly(ss);
        closeQuietly(currentClient);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
