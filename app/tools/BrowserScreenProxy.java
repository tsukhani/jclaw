package tools;

import com.google.errorprone.annotations.MustBeClosed;
import okhttp3.Credentials;
import org.jspecify.annotations.Nullable;
import tools.scrape.ScrapeProxy;
import utils.SsrfGuard;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
 * <p>With an upstream — the operator's scrape proxy, for a stealth render (JCLAW-1315) — a connection
 * the check passed goes through that proxy instead, which is sent the destination's name rather
 * than the checked address, as rung 1 sends it ({@link ScrapeProxy}), so the proxy's own DNS answers.
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
    private static final int MAX_CONNECT_REPLY = 8_192;
    private static final Pattern CONNECT_OPENED = Pattern.compile("HTTP/\\d(\\.\\d)? 2\\d\\d( .*)?");

    /** Bound only by {@link PlaywrightBrowserTool#callWithFailingScreenForTest}: an ephemeral bind does not fail when asked. */
    static final ScopedValue<Boolean> FAILS_FOR_TEST = ScopedValue.newInstance();

    private final BrowserScreenLog log;
    private final @Nullable ScrapeProxy upstream;
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
        this(log, null);
    }

    /**
     * Bind a listener and start accepting, carrying every connection the check passes through
     * {@code upstream} when there is one. A failed step with the upstream fails that connection only.
     */
    @MustBeClosed
    public BrowserScreenProxy(BrowserScreenLog log, @Nullable ScrapeProxy upstream) throws IOException {
        if (FAILS_FOR_TEST.isBound()) throw new IOException("bound port in use");
        this.log = log;
        this.upstream = upstream;
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
        var remote = upstream == null
                ? dial(destinations, "cannot reach host " + host)
                : forward(upstream, host, port);
        if (remote == null) {
            // One reply for every failure: "refused" against "timed out" is a port scan (JCLAW-1229).
            reply(out, REP_HOST_UNREACHABLE);
            return;
        }
        try (remote) {
            client.setSoTimeout(0);
            reply(out, REP_OK);
            Thread.ofVirtual().start(() -> copy(in, remote));
            copy(remote.getInputStream(), client);
        }
    }

    /**
     * A tunnel to {@code host}:{@code port} opened by {@code proxy}, or null with the reason left for
     * the operator. The proxy's own address is held to the provider rule, as every {@link ScrapeProxy}
     * use is: the operator may run it on loopback or the LAN.
     */
    private @Nullable Socket forward(ScrapeProxy proxy, String host, int port) {
        var subject = "cannot reach host " + host + " through the scrape proxy";
        List<InetSocketAddress> proxyAddresses;
        try {
            proxyAddresses = SsrfGuard.PROVIDER_SAFE_DNS.lookup(proxy.host()).stream()
                    .map(address -> new InetSocketAddress(address, proxy.port()))
                    .toList();
        } catch (UnknownHostException e) {
            log.screenFailed(subject, String.valueOf(e.getMessage()));
            return null;
        }
        var socket = dial(proxyAddresses, subject);
        if (socket == null) return null;
        try {
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            var refusal = proxy.kind() == ScrapeProxy.Kind.HTTP
                    ? httpConnect(socket, proxy, host, port)
                    : socksConnect(socket, host, port);
            if (refusal == null) {
                socket.setSoTimeout(0);
                return socket;
            }
            log.screenFailed(subject, refusal);
        } catch (IOException e) {
            log.screenFailed(subject, String.valueOf(e.getMessage()));
        }
        closeQuietly(socket);
        return null;
    }

    /** Null when the HTTP proxy opened a tunnel to {@code host}:{@code port}, else why not. */
    private static @Nullable String httpConnect(Socket socket, ScrapeProxy proxy, String host, int port)
            throws IOException {
        // A client may name an IPv6 literal in domain form, unbracketed.
        var authority = (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        // Only a local process, never Chromium, can name such a host; it must not add a header line.
        if (authority.chars().anyMatch(c -> c <= ' ' || c >= 0x7F)) {
            return "the host name cannot be sent in a CONNECT request";
        }
        var request = new StringBuilder("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n");
        var username = proxy.username();
        if (username != null) {
            var password = proxy.password();
            request.append("Proxy-Authorization: ")
                    .append(Credentials.basic(username, password == null ? "" : password)).append("\r\n");
        }
        request.append("\r\n");
        var out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        var status = connectReplyStatus(socket.getInputStream());
        return CONNECT_OPENED.matcher(status).matches() ? null : "the proxy answered " + status;
    }

    /**
     * The status line of a CONNECT reply, read through the blank line that ends it. Byte by byte, so
     * no byte of the tunnel behind it is consumed.
     */
    private static String connectReplyStatus(InputStream in) throws IOException {
        var reply = new ByteArrayOutputStream();
        int lineLength = 0;
        while (reply.size() < MAX_CONNECT_REPLY) {
            int b = in.read();
            if (b == -1) throw new EOFException("the proxy closed the connection before answering CONNECT");
            reply.write(b);
            if (b == '\n') {
                if (lineLength == 0) {
                    return reply.toString(StandardCharsets.ISO_8859_1).lines().findFirst().orElse("");
                }
                lineLength = 0;
            } else if (b != '\r') {
                lineLength++;
            }
        }
        throw new IOException("the proxy's answer to CONNECT ran past " + MAX_CONNECT_REPLY + " bytes");
    }

    /** Null when the SOCKS5 proxy opened a tunnel to {@code host}:{@code port}, else why not. */
    private static @Nullable String socksConnect(Socket socket, String host, int port) throws IOException {
        var in = socket.getInputStream();
        var out = socket.getOutputStream();
        out.write(new byte[] {VERSION, 1, NO_AUTH});
        out.flush();
        var method = read(in, 2);
        if ((method[0] & 0xFF) != VERSION || (method[1] & 0xFF) != NO_AUTH) {
            return "the proxy did not accept a SOCKS5 connection without authentication";
        }
        // A name even for a literal, as the JDK's SOCKS client sends rung 1's unresolved address.
        var bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        var name = bare.getBytes(StandardCharsets.ISO_8859_1);
        out.write(ByteBuffer.allocate(7 + name.length)
                .put((byte) VERSION).put((byte) CMD_CONNECT).put((byte) 0).put((byte) ATYP_DOMAIN)
                .put((byte) name.length).put(name).putShort((short) port).array());
        out.flush();
        var header = read(in, 4);
        if ((header[0] & 0xFF) != VERSION || (header[1] & 0xFF) != REP_OK) {
            return "the proxy answered CONNECT with SOCKS5 reply " + (header[1] & 0xFF);
        }
        readHost(in, header[3] & 0xFF); // the bound address, which a CONNECT client ignores
        readPort(in);
        return null;
    }

    /** The first destination that accepts, or null with the reason left for the operator. */
    private @Nullable Socket dial(List<InetSocketAddress> destinations, String subject) {
        IOException last = null;
        for (var destination : destinations) {
            var socket = new Socket();
            try {
                socket.connect(destination, CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                last = e;
                closeQuietly(socket);
            }
        }
        log.screenFailed(subject, last == null ? "no address to try" : String.valueOf(last.getMessage()));
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
