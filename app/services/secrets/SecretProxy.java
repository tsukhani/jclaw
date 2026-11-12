package services.secrets;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import utils.HttpFactories;

import javax.net.ssl.SSLSocket;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The loopback HTTPS proxy a command with skill secrets is pointed at (JF-324). Only a command holding a live grant's
 * login gets through. Every HTTPS request it sends is opened here, so the proxy sees each one whole: it writes a
 * secret's value where its placeholder stands only when the request goes to one of that secret's hosts, refuses a
 * request carrying any other placeholder, and turns a value a server sends back into its placeholder. It forwards one
 * request per tunnel, follows no redirect, and carries nothing but HTTPS.
 *
 * <p>Unlike {@link tools.BrowserScreenProxy}, which screens destinations and copies bytes, this proxy must read each
 * request line and header, so it terminates TLS with a certificate {@link SecretsCa} issues for the host. A request
 * whose {@code Host} names another site than its tunnel is refused, since one connection may legally carry two.
 */
public final class SecretProxy {

    private static final int BACKLOG = 128;
    private static final int HEAD_TIMEOUT_MS = 30_000;
    private static final int IO_TIMEOUT_MS = 300_000;
    private static final int MAX_LINE = 16_384;
    private static final int MAX_HEAD = 65_536;
    private static final int MAX_HEADERS = 200;
    private static final int COPY_BUFFER = 16_384;
    /** A body up to this size is rewritten whole and sent with its length; a longer one streams, chunked. */
    static final int BUFFERED_BODY_LIMIT = 1 << 20;
    private static final String NOT_A_PATH = "its request target is not a path";
    private static final Pattern AUTHORITY = Pattern.compile("([A-Za-z0-9.-]+):(\\d{1,5})");
    private static final Set<String> NOT_FORWARDED = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "host",
            "content-length", "expect", "accept-encoding");
    private static final Set<String> NOT_RETURNED = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "content-length", "content-encoding");

    private static volatile @Nullable SecretProxy running;

    private final ServerSocket server;

    private SecretProxy() throws IOException {
        server = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
        var acceptor = Thread.ofPlatform().daemon().name("secrets-proxy").unstarted(this::acceptLoop);
        acceptor.start();
    }

    /** The running proxy, started on first use. */
    static SecretProxy ensure() {
        var proxy = running;
        if (proxy != null) return proxy;
        synchronized (SecretProxy.class) {
            proxy = running;
            if (proxy == null) {
                try {
                    proxy = new SecretProxy();
                } catch (IOException e) {
                    throw new IllegalStateException("The secrets proxy could not listen on loopback", e);
                }
                running = proxy;
            }
            return proxy;
        }
    }

    int port() {
        return server.getLocalPort();
    }

    /**
     * The folder of the trust files commands are pointed at, or null before the first grant. Every sandbox mounts it
     * read-only: a command must be able to read it wherever its sandbox puts the temp folder, and none may replace
     * what other commands trust.
     */
    public static @Nullable Path trustFolder() {
        return SecretsCa.folderIfCreated();
    }

    /** The port, for a test that drives the proxy with a client of its own. */
    public static int portForTest() {
        return ensure().port();
    }

    /** The certificate commands are told to trust, for a test's client. */
    public static java.security.cert.X509Certificate caForTest() {
        return SecretsCa.get().caCertificate();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                var client = server.accept();
                Thread.ofVirtual().name("secrets-proxy-conn").start(() -> serve(client));
            } catch (IOException _) {
                // A closed listener ends the loop; any other accept failure is one connection lost.
            }
        }
    }

    private void serve(Socket raw) {
        try (raw) {
            raw.setSoTimeout(HEAD_TIMEOUT_MS);
            var out = raw.getOutputStream();
            var head = Head.read(raw.getInputStream());
            if (head == null) return;
            if (!head.method().equals("CONNECT")) {
                reply(out, 405, "Method Not Allowed", "JClaw's secrets proxy carries HTTPS only, through CONNECT.");
                return;
            }
            var authority = AUTHORITY.matcher(head.target());
            if (!authority.matches()) {
                reply(out, 400, "Bad Request", "CONNECT names no host and port.");
                return;
            }
            var host = authority.group(1).toLowerCase(Locale.ROOT);
            int port = Integer.parseInt(authority.group(2));
            var grant = login(head.header("proxy-authorization"));
            if (grant == null) {
                out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"jclaw\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var tls = (SSLSocket) SecretsCa.get().serverContext(host).getSocketFactory()
                    .createSocket(raw, host, port, false);
            tls.setUseClientMode(false);
            var params = tls.getSSLParameters();
            params.setApplicationProtocols(new String[] {"http/1.1"});
            tls.setSSLParameters(params);
            try (tls) {
                tls.startHandshake();
                tls.setSoTimeout(IO_TIMEOUT_MS);
                relay(grant, host, port, tls);
            }
        } catch (IOException | RuntimeException _) {
            // The command went away or broke the protocol; its end of the tunnel already knows.
        }
    }

    private static @Nullable SecretGrant login(@Nullable String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return null;
        }
        int colon = decoded.indexOf(':');
        return colon < 0 ? null : SecretGrants.byLogin(decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    private void relay(SecretGrant grant, String host, int port, SSLSocket tls) throws IOException {
        var in = new BufferedInputStream(tls.getInputStream());
        var out = new BufferedOutputStream(tls.getOutputStream());
        var head = Head.read(in);
        if (head == null) return;
        if (!head.version().startsWith("HTTP/1.")) {
            reply(out, 505, "HTTP Version Not Supported", "Only HTTP/1.x is carried.");
            return;
        }
        var hostHeader = head.header("host");
        if (hostHeader == null || !sameAuthority(hostHeader, host, port)) {
            refuse(out, grant, "its Host header names %s but its tunnel goes to %s".formatted(hostHeader, host));
            return;
        }
        // An origin-form target is the only kind a tunnel carries: "@other/..." or ".other/..." after the authority
        // would name another host, and the secret would be written for this one.
        if (!head.target().startsWith("/")) {
            refuse(out, grant, NOT_A_PATH);
            return;
        }
        if (head.header("upgrade") != null) {
            reply(out, 501, "Not Implemented", "JClaw's secrets proxy does not carry protocol upgrades.");
            return;
        }
        try {
            forward(grant, host, port, head, in, out);
        } catch (PlaceholderRewriter.Refused e) {
            refuse(out, grant, e.getMessage());
        }
    }

    private void forward(SecretGrant grant, String host, int port, Head head, InputStream in, OutputStream out)
            throws IOException, PlaceholderRewriter.Refused {
        var target = PlaceholderRewriter.substitute(head.target(), grant, host, port, PlaceholderRewriter.Encoding.PERCENT);
        var headers = new Headers.Builder();
        String contentType = null;
        for (var h : head.headers()) {
            var name = h[0];
            var lower = name.toLowerCase(Locale.ROOT);
            if (NOT_FORWARDED.contains(lower)) continue;
            var value = h[1];
            if (lower.equals("authorization")) value = PlaceholderRewriter.substituteBasic(value, grant, host, port);
            value = PlaceholderRewriter.substitute(value, grant, host, port, PlaceholderRewriter.Encoding.HEADER);
            if (lower.equals("content-type")) contentType = value;
            headers.addUnsafeNonAscii(name, text(value));
        }
        if ("100-continue".equalsIgnoreCase(head.header("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        var body = body(grant, host, port, head, in, contentType);
        var url = upstreamUrl(host, port, text(target));
        if (url == null) throw new PlaceholderRewriter.Refused(NOT_A_PATH);
        var request = new Request.Builder().url(url).headers(headers.build()).method(head.method(), body).build();
        var bound = grant.upstream();
        var client = bound != null ? bound : upstreamClient();
        try (var response = client.newCall(request).execute()) {
            var masker = new PlaceholderRewriter.Masker(grant.bindings());
            var status = new StringBuilder("HTTP/1.1 ").append(response.code()).append(' ')
                    .append(response.message().isBlank() ? "Response" : response.message()).append("\r\n");
            boolean noBody = head.method().equals("HEAD") || response.code() == 204 || response.code() == 304
                    || response.code() < 200;
            for (var i = 0; i < response.headers().size(); i++) {
                var name = response.headers().name(i);
                var lower = name.toLowerCase(Locale.ROOT);
                if (NOT_RETURNED.contains(lower) && !(noBody && lower.equals("content-length"))) continue;
                status.append(name).append(": ").append(masker.mask(response.headers().value(i))).append("\r\n");
            }
            status.append("Connection: close\r\n\r\n");
            out.write(status.toString().getBytes(StandardCharsets.UTF_8));
            if (!noBody) {
                var source = response.body().byteStream();
                var buf = new byte[COPY_BUFFER + 4096];
                int carried = 0;
                int n;
                while ((n = source.read(buf, carried, buf.length - carried)) >= 0) {
                    int len = carried + n;
                    int used = masker.feed(buf, len, false, out);
                    carried = len - used;
                    System.arraycopy(buf, used, buf, 0, carried);
                    if (carried == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                }
                masker.feed(buf, carried, true, out);
            }
            out.flush();
        } catch (IOException e) {
            if (e.getCause() instanceof PlaceholderRewriter.Refused refused) throw refused;
            reply(out, 502, "Bad Gateway", "JClaw could not reach %s: %s".formatted(host, e.getClass().getSimpleName()));
        }
    }

    /** The request body, rewritten: whole when it is small, streamed when it is long. */
    private static @Nullable RequestBody body(SecretGrant grant, String host, int port, Head head, InputStream in,
                                              @Nullable String contentType) throws IOException, PlaceholderRewriter.Refused {
        var transfer = head.header("transfer-encoding");
        boolean chunked = transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked");
        var lengthHeader = head.header("content-length");
        long length = 0;
        if (!chunked && lengthHeader != null) {
            try {
                length = Long.parseLong(lengthHeader.strip());
            } catch (NumberFormatException _) {
                throw new PlaceholderRewriter.Refused("its Content-Length is not a number");
            }
        }
        var media = contentType == null ? null : MediaType.parse(contentType);
        var encoding = contentType == null ? PlaceholderRewriter.Encoding.RAW : PlaceholderRewriter.bodyEncoding(contentType);
        if (!chunked && length == 0) {
            return needsBody(head.method()) ? RequestBody.create(new byte[0], media) : null;
        }
        BodySource source = chunked ? new ChunkedIn(in) : new LimitedIn(in, length);
        var first = readUpTo(source, BUFFERED_BODY_LIMIT + 1);
        if (first.length <= BUFFERED_BODY_LIMIT) {
            var out = new ByteArrayOutputStream(first.length + 64);
            new PlaceholderRewriter.Substituter(grant, host, port, encoding).feed(first, first.length, true, out);
            return RequestBody.create(out.toByteArray(), media);
        }
        return new StreamingBody(media, first, source, new PlaceholderRewriter.Substituter(grant, host, port, encoding));
    }

    /**
     * The general tier without its 60 s call limit, which would cut a long download short, and without redirects.
     * Built on a proxy thread, where no test's {@code HttpFactories.runWith} transport is bound.
     */
    private static OkHttpClient upstreamClient() {
        return HttpFactories.general().newBuilder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(Duration.ZERO).readTimeout(Duration.ofMinutes(5))
                .writeTimeout(Duration.ofMinutes(5)).build();
    }

    /**
     * {@code target} on the tunnel's own host and port, built rather than concatenated, or null when the result would
     * name any other host, port or login.
     */
    private static @Nullable HttpUrl upstreamUrl(String host, int port, String target) {
        int query = target.indexOf('?');
        HttpUrl url;
        try {
            var builder = new HttpUrl.Builder().scheme("https").host(host).port(port)
                    .encodedPath(query < 0 ? target : target.substring(0, query));
            if (query >= 0) builder.encodedQuery(target.substring(query + 1));
            url = builder.build();
        } catch (IllegalArgumentException _) {
            return null;
        }
        boolean same = url.host().equals(host) && url.port() == port && url.username().isEmpty()
                && url.password().isEmpty();
        return same ? url : null;
    }

    private static boolean needsBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }

    /** A long body, rewritten as it streams; a refused placeholder found partway aborts the upload. */
    private static final class StreamingBody extends RequestBody {
        private final @Nullable MediaType media;
        private final byte[] first;
        private final BodySource rest;
        private final PlaceholderRewriter.Substituter substituter;

        StreamingBody(@Nullable MediaType media, byte[] first, BodySource rest, PlaceholderRewriter.Substituter substituter) {
            this.media = media;
            this.first = first;
            this.rest = rest;
            this.substituter = substituter;
        }

        @Override
        public @Nullable MediaType contentType() {
            return media;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            var out = sink.outputStream();
            var buf = Arrays.copyOf(first, Math.max(first.length, COPY_BUFFER) + SecretGrants.PLACEHOLDER_LENGTH);
            int carried = first.length;
            try {
                while (true) {
                    int used = substituter.feed(buf, carried, false, out);
                    carried -= used;
                    System.arraycopy(buf, used, buf, 0, carried);
                    int n = rest.read(buf, carried, buf.length - carried);
                    if (n < 0) break;
                    carried += n;
                }
                substituter.feed(buf, carried, true, out);
            } catch (PlaceholderRewriter.Refused e) {
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    /**
     * A head is read as Latin-1 so every byte survives the rewrite, and OkHttp writes a header as UTF-8: this turns
     * the one into the other, so a non-ASCII value goes out as the bytes that came in.
     */
    private static String text(String latin1) {
        return new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static boolean sameAuthority(String hostHeader, String host, int port) {
        var value = hostHeader.strip().toLowerCase(Locale.ROOT);
        return value.equals(host + ":" + port) || port == 443 && value.equals(host);
    }

    private static void refuse(OutputStream out, SecretGrant grant, @Nullable String reason) throws IOException {
        var message = "JClaw refused this request: " + reason + ".";
        EventLogger.warn("skill", grant.ownerName(), null, "Secrets proxy: " + message);
        reply(out, 403, "Forbidden", message);
    }

    private static void reply(OutputStream out, int code, String reason, String message) throws IOException {
        var body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** A request head: its line and headers, read a byte at a time so nothing past it is consumed. */
    record Head(String method, String target, String version, List<String[]> headers) {

        @Nullable String header(String name) {
            for (var h : headers) {
                if (h[0].equalsIgnoreCase(name)) return h[1];
            }
            return null;
        }

        static @Nullable Head read(InputStream in) throws IOException {
            var line = line(in);
            if (line == null || line.isEmpty()) return null;
            var parts = line.split(" ", 3);
            if (parts.length != 3) throw new IOException("bad request line");
            var headers = new ArrayList<String[]>();
            int total = line.length();
            while (true) {
                var header = line(in);
                if (header == null) throw new IOException("head cut short");
                if (header.isEmpty()) break;
                total += header.length();
                if (total > MAX_HEAD || headers.size() >= MAX_HEADERS) throw new IOException("head too large");
                int colon = header.indexOf(':');
                if (colon <= 0) throw new IOException("bad header");
                headers.add(new String[] {header.substring(0, colon).strip(), header.substring(colon + 1).strip()});
            }
            return new Head(parts[0], parts[1], parts[2], headers);
        }

        private static @Nullable String line(InputStream in) throws IOException {
            var buf = new ByteArrayOutputStream(128);
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    var bytes = buf.toByteArray();
                    int end = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                    return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
                }
                buf.write(c);
                if (buf.size() > MAX_LINE) throw new IOException("line too long");
            }
            return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    /** A request body as it arrives, decoded from its framing. Not a stream: the tunnel's socket owns the closing. */
    private interface BodySource {
        int read(byte[] b, int off, int len) throws IOException;
    }

    private static byte[] readUpTo(BodySource source, int limit) throws IOException {
        var out = new ByteArrayOutputStream();
        var buf = new byte[COPY_BUFFER];
        int n;
        while (out.size() < limit && (n = source.read(buf, 0, Math.min(buf.length, limit - out.size()))) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** A body of a known length. */
    private static final class LimitedIn implements BodySource {
        private final InputStream in;
        private long left;

        LimitedIn(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** A chunked body, decoded; trailers are read and dropped. */
    private static final class ChunkedIn implements BodySource {
        private final InputStream in;
        private long left;
        private boolean done;

        ChunkedIn(InputStream in) {
            this.in = in;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (left == 0) {
                var size = Head.line(in);
                if (size == null) throw new IOException("chunked body cut short");
                int semi = size.indexOf(';');
                try {
                    left = Long.parseLong((semi >= 0 ? size.substring(0, semi) : size).strip(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size", e);
                }
                if (left == 0) {
                    String trailer;
                    do {
                        trailer = Head.line(in);
                    } while (trailer != null && !trailer.isEmpty());
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new IOException("chunked body cut short");
            left -= n;
            if (left == 0) Head.line(in);
            return n;
        }
    }
}
