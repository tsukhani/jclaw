import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.BrowserScreenLog;
import tools.BrowserScreenProxy;
import tools.scrape.ScrapeProxy;
import utils.SsrfGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** UDP is never forwarded, which is why WebRTC had to be stopped at launch instead: see PlaywrightBrowserTool.launchArgs (JCLAW-1286). */
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

    // ─── JCLAW-1315: an upstream, the operator's scrape proxy for a stealth render ─────────────────

    /** A public literal, so the screen passes it with no resolver and no origin permitted. */
    private static final String PUBLIC_HOST = "1.1.1.1";
    private static final int PUBLIC_PORT = 443;

    /** A loopback stand-in for the operator's proxy, counting connections and running {@code session} on each in turn. */
    private static final class StubUpstream implements AutoCloseable {

        interface Session {
            void run(Socket socket) throws IOException;
        }

        private final ServerSocket listener;
        private final AtomicInteger connections = new AtomicInteger();

        StubUpstream(Session session) throws IOException {
            listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().start(() -> {
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        connections.incrementAndGet();
                        session.run(socket);
                    } catch (IOException _) {
                        if (listener.isClosed()) return;
                    }
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        @Override
        public void close() {
            try {
                listener.close();
            } catch (IOException _) { /* best-effort */ }
        }
    }

    /** Answer "pong" to the four bytes the client sends once its tunnel is open. */
    private static void pong(Socket socket) throws IOException {
        socket.getInputStream().readNBytes(4);
        socket.getOutputStream().write("pong".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** An HTTP request head, through its blank line. */
    private static String head(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b == -1) break;
            head.write(b);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    /** An HTTP proxy that records each request head and answers {@code status}, tunnelling on a 2xx. */
    private static StubUpstream httpProxy(List<String> heads, String status) throws IOException {
        return new StubUpstream(socket -> {
            heads.add(head(socket.getInputStream()));
            socket.getOutputStream().write((status + "\r\nVia: stub\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            if (status.startsWith("HTTP/1.1 2")) pong(socket);
        });
    }

    /** Send "ping" through an open tunnel and return the answer. */
    private static String ping(Socket client) throws IOException {
        client.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        return new String(client.getInputStream().readNBytes(4), StandardCharsets.US_ASCII);
    }

    @Test
    void anHttpUpstreamIsSentConnectByNameWithTheOperatorsBasicCredential() throws Exception {
        var heads = new CopyOnWriteArrayList<String>();
        var lines = new CopyOnWriteArrayList<String>();
        try (var upstream = httpProxy(heads, "HTTP/1.1 200 Connection established");
             var proxy = new BrowserScreenProxy(sinkLog(lines),
                     new ScrapeProxy(ScrapeProxy.Kind.HTTP, "127.0.0.1", upstream.port(), "user", "pass"));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_OK, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
            assertEquals("pong", ping(client), "the tunnel starts after the proxy's header block, not inside it");
        }
        assertEquals(List.of("CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n"
                + "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n\r\n"), heads);
        assertEquals(List.of(), lines);
    }

    @Test
    void anIpv6LiteralNamedAsADomainIsBracketedInTheConnectLine() throws Exception {
        var heads = new CopyOnWriteArrayList<String>();
        try (var upstream = httpProxy(heads, "HTTP/1.1 200 Connection established");
             var proxy = new BrowserScreenProxy(sinkLog(new CopyOnWriteArrayList<>()),
                     new ScrapeProxy(ScrapeProxy.Kind.HTTP, "127.0.0.1", upstream.port(), null, null));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_OK, replyTo(client, byName(CMD_CONNECT, "2606:4700:4700::1111", PUBLIC_PORT)));
            assertEquals("pong", ping(client));
        }
        assertTrue(heads.getFirst().startsWith("CONNECT [2606:4700:4700::1111]:443 HTTP/1.1\r\n"), heads.toString());
    }

    @Test
    void aSocksUpstreamIsGreetedWithoutAuthenticationAndSentTheDestinationAsAName() throws Exception {
        var greetings = new CopyOnWriteArrayList<String>();
        var requests = new CopyOnWriteArrayList<String>();
        var lines = new CopyOnWriteArrayList<String>();
        try (var upstream = new StubUpstream(socket -> {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();
            var greeting = in.readNBytes(2);
            greetings.add(Arrays.toString(greeting) + Arrays.toString(in.readNBytes(greeting[1])));
            out.write(new byte[] {5, 0});
            out.flush();
            var request = in.readNBytes(5);
            var name = new String(in.readNBytes(request[4]), StandardCharsets.US_ASCII);
            var port = ByteBuffer.wrap(in.readNBytes(2)).getShort() & 0xFFFF;
            requests.add("cmd=" + request[1] + " atyp=" + request[3] + " " + name + ":" + port);
            out.write(new byte[] {5, REP_OK, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            pong(socket);
        });
             var proxy = new BrowserScreenProxy(sinkLog(lines),
                     new ScrapeProxy(ScrapeProxy.Kind.SOCKS5, "127.0.0.1", upstream.port(), null, null));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_OK, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
            assertEquals("pong", ping(client));
        }
        assertEquals(List.of("[5, 1][0]"), greetings, "one method offered: no authentication");
        assertEquals(List.of("cmd=1 atyp=" + ATYP_DOMAIN + " 1.1.1.1:443"), requests,
                "a CONNECT naming the destination, which the proxy resolves");
        assertEquals(List.of(), lines);
    }

    /**
     * Every upstream failure is the one reply every transport failure gets, with the reason on the
     * event log — and it fails that connection only: the next one through the same screen still opens.
     */
    @Test
    void anUpstreamThatRefusesTheTunnelFailsThatConnectionOnly() throws Exception {
        var heads = new CopyOnWriteArrayList<String>();
        var lines = new CopyOnWriteArrayList<String>();
        var answers = new AtomicInteger();
        try (var upstream = new StubUpstream(socket -> {
            heads.add(head(socket.getInputStream()));
            var status = answers.getAndIncrement() == 0
                    ? "HTTP/1.1 407 Proxy Authentication Required" : "HTTP/1.1 200 Connection established";
            socket.getOutputStream().write((status + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            if (status.contains(" 200 ")) pong(socket);
        });
             var proxy = new BrowserScreenProxy(sinkLog(lines),
                     new ScrapeProxy(ScrapeProxy.Kind.HTTP, "127.0.0.1", upstream.port(), null, null))) {
            try (var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                assertEquals(REP_HOST_UNREACHABLE, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
            }
            try (var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                assertEquals(REP_OK, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
                assertEquals("pong", ping(client));
            }
        }
        assertFalse(heads.getFirst().contains("Proxy-Authorization"), "no credential configured, none sent: " + heads);
        assertEquals(List.of("WARN Browser network screen: cannot reach host 1.1.1.1 through the scrape proxy: "
                + "the proxy answered HTTP/1.1 407 Proxy Authentication Required"), lines);
    }

    @Test
    void anUpstreamThatIsDownOrAnswersSocksFailureGetsTheSameReply() throws Exception {
        int dead;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dead = probe.getLocalPort();
        }
        var lines = new CopyOnWriteArrayList<String>();
        try (var proxy = new BrowserScreenProxy(sinkLog(lines),
                new ScrapeProxy(ScrapeProxy.Kind.HTTP, "127.0.0.1", dead, null, null));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_HOST_UNREACHABLE, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
        }
        try (var upstream = new StubUpstream(socket -> {
            var in = socket.getInputStream();
            in.readNBytes(3);
            socket.getOutputStream().write(new byte[] {5, 0});
            socket.getOutputStream().flush();
            in.readNBytes(7 + PUBLIC_HOST.length());
            socket.getOutputStream().write(new byte[] {5, 0x05, 0, 1, 0, 0, 0, 0, 0, 0}); // connection refused
            socket.getOutputStream().flush();
        });
             var proxy = new BrowserScreenProxy(sinkLog(lines),
                     new ScrapeProxy(ScrapeProxy.Kind.SOCKS5, "127.0.0.1", upstream.port(), null, null));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_HOST_UNREACHABLE, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
        }
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("WARN Browser network screen: cannot reach host 1.1.1.1 through the scrape proxy: "),
                lines.toString());
        assertTrue(lines.get(1).endsWith("the proxy answered CONNECT with SOCKS5 reply 5"), lines.toString());
    }

    @Test
    void aDestinationTheGuardRefusesNeverReachesTheUpstream() throws Exception {
        var heads = new CopyOnWriteArrayList<String>();
        var lines = new CopyOnWriteArrayList<String>();
        try (var upstream = httpProxy(heads, "HTTP/1.1 200 Connection established");
             var proxy = new BrowserScreenProxy(sinkLog(lines),
                     new ScrapeProxy(ScrapeProxy.Kind.HTTP, "127.0.0.1", upstream.port(), "user", "pass"));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_NOT_ALLOWED, replyTo(client, byName(CMD_CONNECT, "127.0.0.1", 8080)));
            assertEquals(0, upstream.connections(), "the screen refuses before the proxy is contacted");
        }
        assertEquals(List.of("WARN Browser refused a request to blocked host 127.0.0.1"), lines);
    }

    @Test
    void anUpstreamAtALinkLocalAddressIsNeverDialled() throws Exception {
        // The provider rule, as for every ScrapeProxy use: loopback and the LAN pass, the metadata address does not.
        var lines = new CopyOnWriteArrayList<String>();
        try (var proxy = new BrowserScreenProxy(sinkLog(lines),
                new ScrapeProxy(ScrapeProxy.Kind.HTTP, "169.254.169.254", 80, null, null));
             var client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
            assertEquals(REP_HOST_UNREACHABLE, replyTo(client, byName(CMD_CONNECT, PUBLIC_HOST, PUBLIC_PORT)));
        }
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains("resolves to blocked address 169.254.169.254"), lines.toString());
    }
}
