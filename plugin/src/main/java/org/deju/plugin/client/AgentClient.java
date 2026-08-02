package org.deju.plugin.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.PayloadCodec;

/**
 * Connects to the agent's token-gated localhost socket, performs the {@code AUTH}
 * handshake, sends {@code ARM}/{@code DISARM}, and reads pushed JSON payloads on a
 * pooled background thread.
 *
 * <p>Security: connects to the configured host (loopback by default; a mapped host/port
 * for a container or remote box). The payload is parsed with a plain Jackson mapper (no
 * default typing). This is the plugin's only network activity, there is no telemetry.
 */
public final class AgentClient {

    /** Callbacks are delivered on a background thread; implementers marshal to EDT. */
    public interface Listener {
        void onConnected();

        void onDisconnected(String reason);

        void onPayload(DejuPayload payload);
    }

    private static final Logger LOG = Logger.getInstance(AgentClient.class);

    private final String host;
    private final int port;
    private final String token;
    private final Listener listener;

    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile boolean running;

    public AgentClient(String host, int port, String token, Listener listener) {
        this.host = host == null || host.trim().isEmpty() ? "127.0.0.1" : host.trim();
        this.port = port;
        this.token = token == null ? "" : token;
        this.listener = listener;
    }

    public boolean isConnected() {
        Socket s = socket;
        return running && s != null && s.isConnected() && !s.isClosed();
    }

    /** Connects and starts reading on a pooled thread. Safe to call from the EDT. */
    public void connect() {
        if (running) {
            return;
        }
        running = true;
        ApplicationManager.getApplication().executeOnPooledThread(this::run);
    }

    private void run() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            writeLine(out, "AUTH " + token);
            String reply = in.readLine();
            if (!"OK".equals(reply)) {
                fail(s, "authentication failed");
                return;
            }
            synchronized (writeLock) {
                this.socket = s;
                this.writer = out;
            }
            listener.onConnected();

            String line;
            while (running && (line = in.readLine()) != null) {
                dispatch(line);
            }
            fail(s, "connection closed");
        } catch (IOException e) {
            fail(s, e.getMessage() == null ? "connection error" : e.getMessage());
        }
    }

    private void dispatch(String line) {
        try {
            DejuPayload payload = PayloadCodec.parse(line);
            listener.onPayload(payload);
        } catch (IOException e) {
            LOG.warn("Ignoring malformed payload line", e);
        }
    }

    public void arm(String fqMethod) {
        sendCommand("ARM " + fqMethod);
    }

    public void disarm() {
        sendCommand("DISARM");
    }

    private void sendCommand(String command) {
        synchronized (writeLock) {
            if (writer == null) {
                return;
            }
            try {
                writeLine(writer, command);
            } catch (IOException e) {
                LOG.warn("Failed to send command: " + command, e);
            }
        }
    }

    private static void writeLine(BufferedWriter out, String s) throws IOException {
        out.write(s);
        out.write("\n");
        out.flush();
    }

    public void disconnect() {
        running = false;
        Socket s = socket;
        socket = null;
        writer = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private void fail(Socket s, String reason) {
        boolean wasRunning = running;
        running = false;
        synchronized (writeLock) {
            socket = null;
            writer = null;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
        if (wasRunning) {
            listener.onDisconnected(reason);
        }
    }
}
