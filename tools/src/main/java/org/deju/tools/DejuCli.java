package org.deju.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Throwaway command-line client that proves the agent socket end-to-end:
 * connects to {@code 127.0.0.1:<port>}, authenticates, optionally arms a target,
 * and prints every JSON payload the agent pushes.
 *
 * <pre>
 *   ./gradlew :tools:run --args="7391 devtoken org.acme.web.Api#handle"
 *   # or, after installDist:
 *   deju-cli &lt;port&gt; &lt;token&gt; [fqMethod]
 * </pre>
 *
 * With an {@code fqMethod} it sends {@code ARM}; without one it just listens. This is
 * a development aid, the IntelliJ plugin is the real client.
 */
public final class DejuCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: deju-cli <port> <token> [fqMethod-to-arm]");
            System.exit(2);
            return;
        }
        int port = Integer.parseInt(args[0]);
        String token = args[1];
        String armTarget = args.length >= 3 ? args[2] : null;

        // Connect to loopback only.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            send(out, "AUTH " + token);
            String reply = in.readLine();
            if (!"OK".equals(reply)) {
                System.err.println("auth failed (server said: " + reply + ")");
                return;
            }
            System.out.println("[cli] authenticated");

            if (armTarget != null) {
                send(out, "ARM " + armTarget);
                System.out.println("[cli] armed " + armTarget + ", waiting for sessions (Ctrl-C to quit)");
            } else {
                System.out.println("[cli] listening, waiting for sessions (Ctrl-C to quit)");
            }

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[cli] payload: " + line);
            }
            System.out.println("[cli] server closed the connection");
        }
    }

    private static void send(BufferedWriter out, String command) throws java.io.IOException {
        out.write(command);
        out.write("\n");
        out.flush();
    }
}
