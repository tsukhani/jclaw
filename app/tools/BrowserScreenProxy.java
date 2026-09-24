package tools;

import com.google.errorprone.annotations.MustBeClosed;
import org.jspecify.annotations.Nullable;
import utils.SsrfGuard;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SOCKS5 proxy one browser session's Chromium is launched behind (JCLAW-1283), so the network
 * check sits below the page rather than in it.
 *
 * <p><b>The boundary:</b> every TCP connection Chromium opens comes through here — a subresource, a
 * WebSocket, what a dedicated or shared worker opens, a cross-site frame — and is screened with
 * {@link SsrfGuard} before it is made. None of the last three is visible to {@code context.route},
 * and nothing on the page can steer around a check that is not on the page, which is why
 * {@code routeWebSocket} was never shipped: it is a page-world mock a script defeats with
 * {@code __pwWebSocketDispatch}. WebRTC is UDP, which this proxy cannot carry and refusing
 * {@code UDP ASSOCIATE} does not stop, so {@link PlaywrightBrowserTool#launchArgs} disables it at
 * launch instead (JCLAW-1286).
 *
 * <p>Each connection is made to the address the check resolved, so every host is pinned at connect
 * time and not just the entry host. A tunnel is never inspected: the destination is what is
 * screened, and the bytes are copied.
 *
 * <p>SOCKS5 rather than HTTP CONNECT because a SOCKS connection carries one destination by
 * construction, while an HTTP proxy is only safe if it screens every request line — a client may
 * legally send two origins down one connection. The method and path that gives up are what
 * {@code context.route} still provides.
 *
 * <p>The listener is on loopback, so only a local process can reach it, and it forwards only to
 * destinations the guard already permits.
 */
public final class BrowserScreenProxy implements AutoCloseable {

    private static final int VERSION = 0x05;
    private static final int NO_AUTH = 0x00;
    private static final int CMD_CONNECT = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int REP_OK = 0x00;
    private static final int REP_NOT_ALLOWED = 0x02;
    private static final int REP_HOST_UNREACHABLE = 0x04;
    private static final int REP_CMD_UNSUPPORTED = 0x07;
    private static final int BACKLOG = 128;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int ACCEPT_RETRY_MS = 250;
    private static final int COPY_BUFFER = 16_384;

    private final BrowserScreenLog log;
    private final @Nullable String permittedOrigin;
    private final ServerSocket listener;
    /** The live client ends, so {@link #close} can end tunnels the listener's own close leaves running. */
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * Bind a listener and start accepting. The launch must fail when this throws: a browser with no
     * proxy to dial is a browser whose connections nothing screens.
     */
    @MustBeClosed
    public BrowserScreenProxy(BrowserScreenLog log) throws IOException {
        this.log = log;
        // A ScopedValue binding does not reach the threads below, so a test's origin is captured here.
        this.permittedOrigin = SsrfGuard.permittedOrigin().orElse(null);
        this.listener = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("browser-proxy-" + listener.getLocalPort()).start(this::acceptLoop);
    }

    /** The loopback port Chromium is pointed at. */
    public int port() {
        return listener.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException _) { /* best-effort */ }
        // Tunnels must not outlive the session: closing the client end ends both pumps and the
        // upstream socket with them.
        open.forEach(BrowserScreenProxy::closeQuietly);
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                var client = listener.accept();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                if (listener.isClosed()) return;
                // A transient failure (the process out of descriptors) must not end the screen: a
                // running browser whose proxy stopped accepting is the one state to never reach.
                log.screenFailed("cannot accept a connection", String.valueOf(e.getMessage()));
                if (!pause()) return;
            }
        }
    }

    /** Wait out a repeating accept failure so it cannot spin; false when interrupted. */
    private static boolean pause() {
        try {
            Thread.sleep(ACCEPT_RETRY_MS);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void serve(Socket client) {
        open.add(client);
        try (client) {
            if (closed) return;
            client.setTcpNoDelay(true);
            // A local process that connects and sends nothing would otherwise hold this thread and
            // its socket until the session ends. Cleared before anything long-lived flows.
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            var in = new BufferedInputStream(client.getInputStream());
            var out = client.getOutputStream();
            if (!greet(in, out)) return;
            var header = read(in, 4);
            if ((header[0] & 0xFF) != VERSION) return;
            int command = header[1] & 0xFF;
            // The whole request is read before it is answered, so the stream stays at a message boundary.
            var host = readHost(in, header[3] & 0xFF);
            int port = readPort(in);
            if (command != CMD_CONNECT) {
                reply(out, REP_CMD_UNSUPPORTED); // BIND and UDP ASSOCIATE would each open a second path
                return;
            }
            tunnel(client, in, out, host, port);
        } catch (IOException _) { /* the client or the destination went away */
        } finally {
            open.remove(client);
        }
    }

    private void tunnel(Socket client, InputStream in, OutputStream out, String host, int port)
            throws IOException {
        List<InetSocketAddress> destinations;
        try {
            destinations = destinationsFor(host, port, permittedOrigin);
        } catch (SecurityException e) {
            // SOCKS5 carries no scheme; the log reads the host back out of this.
            log.refused("http://" + host + ":" + port, e instanceof SsrfGuard.BlockedAddressException);
            reply(out, REP_NOT_ALLOWED);
            return;
        }
        var upstream = dial(destinations, host);
        if (upstream == null) {
            // One reply for every failure: "refused" against "timed out" is a port scan (JCLAW-1229).
            reply(out, REP_HOST_UNREACHABLE);
            return;
        }
        try (upstream) {
            client.setSoTimeout(0);
            reply(out, REP_OK);
            Thread.ofVirtual().start(() -> copy(in, upstream));
            copy(upstream.getInputStream(), client);
        }
    }

    /** The first destination that accepts, or null with the reason left for the operator. */
    private @Nullable Socket dial(List<InetSocketAddress> destinations, String host) {
        IOException last = null;
        for (var destination : destinations) {
            var upstream = new Socket();
            try {
                upstream.connect(destination, CONNECT_TIMEOUT_MS);
                upstream.setTcpNoDelay(true);
                return upstream;
            } catch (IOException e) {
                last = e;
                closeQuietly(upstream);
            }
        }
        log.screenFailed("cannot reach host " + host,
                last == null ? "no address to try" : String.valueOf(last.getMessage()));
        return null;
    }

    /**
     * Where a connection to {@code host}:{@code port} may go: the addresses {@link SsrfGuard}
     * resolved and passed, in the order the resolver gave them, each bound into the socket address
     * the connect uses. The name is looked up once, so a host that rebinds afterwards has no second
     * lookup to answer differently, and a host with more than one address keeps the rest to try.
     * Exposed for tests.
     *
     * @throws SecurityException when the host does not resolve, or any address it gave is blocked
     */
    public static List<InetSocketAddress> destinationsFor(String host, int port,
                                                          @Nullable String permittedOrigin) {
        var permitted = permitted(permittedOrigin, host, port);
        if (permitted != null) {
            return List.of(new InetSocketAddress(permitted, port));
        }
        return SsrfGuard.resolveSafeAddresses(host).stream()
                .map(address -> new InetSocketAddress(address, port))
                .toList();
    }

    /**
     * The address of the origin the session permitted, when {@code host}:{@code port} is that origin,
     * else null. Matched on address and port, because SOCKS5 carries no scheme to compare; both sides
     * must be IP literals, since honoring a name here would skip the guard on whatever it resolves
     * to, and a literal needs no lookup of its own.
     */
    private static @Nullable InetAddress permitted(@Nullable String permittedOrigin, String host, int port) {
        if (permittedOrigin == null || !SsrfGuard.isLikelyIpLiteral(host)) return null;
        var uri = URI.create(permittedOrigin);
        var permittedHost = uri.getHost();
        if (permittedHost == null || uri.getPort() != port || !SsrfGuard.isLikelyIpLiteral(permittedHost)) {
            return null;
        }
        try {
            var wanted = InetAddress.getByName(permittedHost);
            return wanted.equals(InetAddress.getByName(host)) ? wanted : null;
        } catch (UnknownHostException _) {
            return null; // both sides are literals, so this is a malformed one rather than a lookup
        }
    }

    /** Answer the greeting with "no authentication", or false when the client is not speaking SOCKS5. */
    private static boolean greet(InputStream in, OutputStream out) throws IOException {
        var greeting = read(in, 2);
        if ((greeting[0] & 0xFF) != VERSION) return false;
        read(in, greeting[1] & 0xFF); // the methods on offer; this proxy needs none of them
        out.write(new byte[] {VERSION, NO_AUTH});
        out.flush();
        return true;
    }

    private static String readHost(InputStream in, int addressType) throws IOException {
        return switch (addressType) {
            case ATYP_IPV4 -> InetAddress.getByAddress(read(in, 4)).getHostAddress();
            case ATYP_IPV6 -> "[" + InetAddress.getByAddress(read(in, 16)).getHostAddress() + "]";
            // ISO-8859-1 keeps every byte distinct; a name that is not ASCII fails the guard rather than the parse.
            case ATYP_DOMAIN -> new String(read(in, read(in, 1)[0] & 0xFF), StandardCharsets.ISO_8859_1);
            // The address length is unknown, so the stream cannot be resynchronised: close rather than answer.
            default -> throw new IOException("unknown SOCKS5 address type " + addressType);
        };
    }

    private static int readPort(InputStream in) throws IOException {
        var bytes = read(in, 2);
        return ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
    }

    private static byte[] read(InputStream in, int count) throws IOException {
        var bytes = in.readNBytes(count);
        if (bytes.length != count) throw new EOFException("SOCKS5 request ended early");
        return bytes;
    }

    /** A reply with an all-zero bound address, which a CONNECT client ignores. */
    private static void reply(OutputStream out, int code) throws IOException {
        out.write(new byte[] {VERSION, (byte) code, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private static void copy(InputStream from, Socket to) {
        var buffer = new byte[COPY_BUFFER];
        try {
            var out = to.getOutputStream();
            int read;
            while ((read = from.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
            // A protocol that ends its request with FIN waits for the answer; without passing the FIN
            // on, the peer holds until the other direction closes.
            to.shutdownOutput();
        } catch (IOException _) { /* the other side closed; the caller closes both */ }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException _) { /* best-effort */ }
    }
}
