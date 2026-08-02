package org.deju.agent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Test;

import org.deju.agent.socket.SocketServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code bind=} option, which lets a containerised JVM expose the control socket to the
 * IDE on the host without a socat bridge.
 *
 * <p>The default matters more than the feature: an agent attached to a JVM on a developer's
 * own machine must not start listening on every interface because someone added an
 * unrelated argument.
 */
class BindAddressTest {

    @Test
    void defaultsToLoopbackWhenNotGiven() {
        AgentConfig cfg = AgentConfig.parse("port=7391,token=devtoken,includes=com.example");
        assertEquals("127.0.0.1", cfg.getBind());
        assertTrue(cfg.isLoopbackOnly());
    }

    @Test
    void defaultsToLoopbackForEmptyArguments() {
        assertEquals("127.0.0.1", AgentConfig.parse("").getBind());
        assertEquals("127.0.0.1", AgentConfig.parse(null).getBind());
    }

    @Test
    void acceptsAnExplicitBindAddress() {
        AgentConfig cfg = AgentConfig.parse("port=7391,token=t,bind=0.0.0.0");
        assertEquals("0.0.0.0", cfg.getBind());
        assertFalse(cfg.isLoopbackOnly());
    }

    @Test
    void aBlankBindValueIsIgnoredRatherThanMeaningAllInterfaces() {
        // ServerSocket treats a null/blank address as "every interface", so a stray
        // "bind=" in a compose file must not silently open the port.
        assertEquals("127.0.0.1", AgentConfig.parse("port=7391,bind=").getBind());
    }

    @Test
    void loopbackSpellingsAreAllRecognisedAsSafe() {
        assertTrue(AgentConfig.parse("bind=127.0.0.1").isLoopbackOnly());
        assertTrue(AgentConfig.parse("bind=localhost").isLoopbackOnly());
        assertTrue(AgentConfig.parse("bind=::1").isLoopbackOnly());
    }

    @Test
    void anArbitraryHostnameIsTreatedAsUnsafe() {
        // Resolution inside a container need not match the host's, so a name is never
        // assumed to be loopback.
        assertFalse(AgentConfig.parse("bind=tomcat").isLoopbackOnly());
    }

    @Test
    void bindOrderIndependenceAndOtherKeysStillParse() {
        AgentConfig cfg = AgentConfig.parse("bind=0.0.0.0,includes=com.example:com.other,port=7500,token=s3cret");
        assertEquals("0.0.0.0", cfg.getBind());
        assertEquals(7500, cfg.getPort());
        assertEquals("s3cret", cfg.getToken());
        assertEquals(2, cfg.getIncludes().size());
    }

    @Test
    void refusesToListenBeyondLoopbackWithoutAToken() {
        // An open port with no token would let anything that can route to it arm a trace
        // and read back source lines.
        SocketServer server = new SocketServer(freePort(), "", "0.0.0.0");
        IOException e = assertThrows(IOException.class, server::start);
        assertTrue(e.getMessage().contains("without a token"), e.getMessage());
    }

    @Test
    void allowsLoopbackWithoutAToken() throws IOException {
        // Unchanged behaviour: the only peer that can reach it is already on this machine.
        SocketServer server = new SocketServer(freePort(), "", "127.0.0.1");
        server.start();
        server.stop();
    }

    @Test
    void aBoundSocketIsReachableOnTheAddressItAdvertised() throws IOException {
        int port = freePort();
        SocketServer server = new SocketServer(port, "devtoken", "127.0.0.1");
        server.start();
        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
            assertTrue(client.isConnected());
        } finally {
            server.stop();
        }
    }

    /** An ephemeral port the OS just handed back, so parallel test runs cannot collide. */
    private static int freePort() {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port for the test", e);
        }
    }
}
