import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.BrowserScreenLog;
import tools.BrowserScreenProxy;
import utils.SsrfGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The SOCKS5 screen itself (JCLAW-1283), driven as a client rather than through Chromium, so the
 * handshake, each reply code and the connect address are pinned without a browser. The browser's
 * side — that a WebSocket, a worker and a frame all arrive here — is in {@code PlaywrightToolTest}.
 */
class BrowserScreenProxyTest extends UnitTest {

    private static final int CMD_CONNECT = 0x01;
    private static final int CMD_BIND = 0x02;
    private static final int CMD_UDP_ASSOCIATE = 0x03;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int REP_OK = 0x00;
    private static final int REP_NOT_ALLOWED = 0x02;
    private static final int REP_HOST_UNREACHABLE = 0x04;
    private static final int REP_CMD_UNSUPPORTED = 0x07;

    /** A loopback listener that answers every four bytes with "pong". */
    private static final class EchoSite implements AutoCloseable {

        private final ServerSocket listener;

        EchoSite() throws IOException {
            listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().start(() -> {
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        socket.getInputStream().readNBytes(4);
                        socket.getOutputStream().write("pong".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    } catch (IOException _) {
                        return;
                    }
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() {
            try {
                listener.close();
            } catch (IOException _) { /* best-effort */ }
        }
    }

    private static BrowserScreenLog sinkLog(List<String> lines) {
        return new BrowserScreenLog((level, message) -> lines.add(level + " " + message));
    }

    /** A request naming {@code host} as a domain name, the form Chromium always sends. */
    private static byte[] byName(int command, String host, int port) {
        var name = host.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(7 + name.length)
                .put((byte) 5).put((byte) command).put((byte) 0).put((byte) ATYP_DOMAIN)
                .put((byte) name.length).put(name).putShort((short) port).array();
    }

    /** A request naming a 16-byte IPv6 address, the one shape {@code readHost} brackets. */
    private static byte[] byIpv6(byte[] address, int port) {
        return ByteBuffer.allocate(22)
                .put((byte) 5).put((byte) CMD_CONNECT).put((byte) 0).put((byte) ATYP_IPV6)
                .put(address).putShort((short) port).array();
    }

    /** Greet, send a raw request, and return whatever the proxy answers — empty when it just closes. */
    private static byte[] exchange(Socket client, byte[] request) throws IOException {
        var out = client.getOutputStream();
        out.write(new byte[] {5, 1, 0}); // one method on offer: no authentication
        out.flush();
        assertArrayEquals(new byte[] {5, 0}, client.getInputStream().readNBytes(2),
                "the proxy answers the greeting with no authentication");
        out.write(request);
        out.flush();
        return client.getInputStream().readNBytes(10);
    }

    private static int replyTo(Socket client, byte[] request) throws IOException {
        var reply = exchange(client, request);
        assertEquals(10, reply.length, "a whole SOCKS5 reply");
        assertEquals(5, reply[0] & 0xFF, "the reply is SOCKS5");
        return reply[1] & 0xFF;
    }

    @Test
    void thePermittedOriginConnectsThroughAndBytesFlowBothWays() throws Exception {
        try (var echo = new EchoSite()) {
            var lines = new CopyOnWriteArrayList<String>();
            var answer = SsrfGuard.<String>permitOriginForTest("http://127.0.0.1:" + echo.port(), () -> {
                try (var proxy = new BrowserScreenProxy(sinkLog(lines));
                     var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                    assertEquals(REP_OK, replyTo(client, byName(CMD_CONNECT, "127.0.0.1", echo.port())));
                    client.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    return new String(client.getInputStream().readNBytes(4), StandardCharsets.US_ASCII);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            assertEquals("pong", answer, "the proxy copied the request up and the answer back");
            assertEquals(List.of(), lines, "nothing was refused and nothing failed");
        }
    }

    @Test
    void aDestinationTheGuardRefusesIsAnsweredWithNotAllowedAndLogged() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var proxy = new BrowserScreenProxy(sinkLog(lines));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_NOT_ALLOWED, replyTo(client, byName(CMD_CONNECT, "127.0.0.1", 8080)),
                    "no origin was permitted, so loopback is refused");
            assertEquals(-1, client.getInputStream().read(), "the refused connection carries nothing");
        }
        assertEquals(List.of("WARN Browser refused a request to blocked host 127.0.0.1"), lines);
    }

    /**
     * A destination the guard passed that will not accept. The reply is the same one every other
     * transport failure gets — telling refused from timed out would hand a page a port scan
     * (JCLAW-1229) — so the reason an operator needs is on the event log instead.
     */
    @Test
    void anUnreachableDestinationGetsTheSameReplyAndLeavesTheReasonForTheOperator() throws Exception {
        int dead;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dead = probe.getLocalPort(); // freed on close, so a connect there is refused at once
        }
        var lines = new CopyOnWriteArrayList<String>();
        SsrfGuard.<Void>permitOriginForTest("http://127.0.0.1:" + dead, () -> {
            try (var proxy = new BrowserScreenProxy(sinkLog(lines));
                 var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                assertEquals(REP_HOST_UNREACHABLE, replyTo(client, byName(CMD_CONNECT, "127.0.0.1", dead)));
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().startsWith("WARN Browser network screen: cannot reach host 127.0.0.1: "),
                "the transport reason stays with the operator: " + lines);
    }

    @Test
    void bindIsRefusedBecauseOnlyConnectCarriesOneScreenedDestination() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var proxy = new BrowserScreenProxy(sinkLog(lines));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_CMD_UNSUPPORTED, replyTo(client, byName(CMD_BIND, "example.com", 80)));
        }
        assertEquals(List.of(), lines, "a command the proxy does not serve is not a refused destination");
    }

    /** UDP is never forwarded, which is also why WebRTC is outside the screen rather than blocked by it. */
    @Test
    void udpAssociateIsRefused() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var proxy = new BrowserScreenProxy(sinkLog(lines));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_CMD_UNSUPPORTED, replyTo(client, byName(CMD_UDP_ASSOCIATE, "example.com", 80)));
        }
        assertEquals(List.of(), lines);
    }

    /**
     * An address type the proxy does not know has an unknown length, so the rest of the request
     * cannot be found: the connection is closed rather than answered into a stream that is now out
     * of step.
     */
    @Test
    void anUnknownAddressTypeClosesTheConnectionUnanswered() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        try (var proxy = new BrowserScreenProxy(sinkLog(lines));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            var unknown = new byte[] {5, (byte) CMD_CONNECT, 0, 0x09, 1, 2, 3, 4, 0, 80};
            assertEquals(0, exchange(client, unknown).length, "no reply, just the close");
        }
        assertEquals(List.of(), lines);
    }

    /** The one path where {@code readHost} emits a bracketed host, which the log and the lookup must both take. */
    @Test
    void anIpv6DestinationIsScreenedAndNamedInItsBracketedForm() throws Exception {
        var lines = new CopyOnWriteArrayList<String>();
        var loopback = new byte[16];
        loopback[15] = 1; // ::1
        try (var proxy = new BrowserScreenProxy(sinkLog(lines));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_NOT_ALLOWED, replyTo(client, byIpv6(loopback, 80)));
        }
        assertEquals(List.of("WARN Browser refused a request to blocked host [0:0:0:0:0:0:0:1]"), lines);
    }

    /**
     * What the connect is handed: the screened address, already bound into the socket address, so
     * nothing looks the destination up a second time. A literal is used deliberately — the property
     * is about the shape of what comes back, and a test of it should not need a resolver.
     */
    @Test
    void theDestinationCarriesTheScreenedAddressReadyToConnect() {
        var destinations = BrowserScreenProxy.destinationsFor("1.1.1.1", 443, null);

        assertEquals(1, destinations.size(), destinations.toString());
        var destination = destinations.getFirst();
        assertFalse(destination.isUnresolved(), "an unresolved address would be looked up again at connect");
        assertEquals("1.1.1.1", destination.getAddress().getHostAddress());
        assertFalse(SsrfGuard.isUnsafe(destination.getAddress()), "and the guard passed what it bound");
        assertEquals(443, destination.getPort());
    }

    @Test
    void thePermittedOriginIsOneOriginAndNeitherItsHostNorAName() {
        assertThrows(SsrfGuard.BlockedAddressException.class,
                () -> BrowserScreenProxy.destinationsFor("127.0.0.1", 8080, null));

        var permitted = BrowserScreenProxy.destinationsFor("127.0.0.1", 8080, "http://127.0.0.1:8080");
        assertEquals(List.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080)), permitted);

        assertThrows(SsrfGuard.BlockedAddressException.class,
                () -> BrowserScreenProxy.destinationsFor("127.0.0.1", 8081, "http://127.0.0.1:8080"),
                "another port on the permitted host is another origin");
        assertThrows(SsrfGuard.BlockedAddressException.class,
                () -> BrowserScreenProxy.destinationsFor("127.0.0.1", 8080, "http://localhost:8080"),
                "a permitted origin naming a host rather than an address is not honoured: what it "
                        + "resolves to would go unscreened");
    }
}
