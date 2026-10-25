package utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Answers the Restart panel's progress route while no JClaw JVM serves the browser. {@code jclaw.sh restart}
 * runs it out of process on the installed classes alone, so it stays on java.base and jdk.httpserver:
 * {@code RestartNarratorTest.theNarratorStandsAlone} pins that. JClaw serves the same route, through
 * {@link #readProgress}, once it is up.
 */
public final class RestartNarrator {

    public static final String PROGRESS_PATH = "/api/system/restart/progress";

    /** The variable jclaw.sh hands the application secret in, so it never appears on the command line. */
    public static final String SECRET_ENV = "JCLAW_NARRATOR_SECRET";

    static final String SESSION_COOKIE = "PLAY_SESSION";

    /** The session keys and values JClaw writes, which ApiSystemControllerTest holds equal to the app's own. */
    public static final String AUTHENTICATED_KEY = "authenticated";
    public static final String SESSION_ID_KEY = "sid";
    public static final String PRINCIPAL_KEY = "principal";
    public static final String AGENT_PRINCIPAL = "agent";
    public static final String USERNAME_KEY = "username";
    public static final String SYSTEM_OWNER = "system";
    public static final String EXPIRY_KEY = "___TS";

    /** Longest any narrator serves; the panel's own budget for a source checkout is 15 minutes. */
    public static final long LIFETIME_NANOS = TimeUnit.MINUTES.toNanos(20);

    /** How long a failure stays on offer after jclaw.sh has gone, for the panel to report it. */
    public static final long FAILURE_LINGER_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** How long it keeps trying the HTTP port: it starts before the old JVM stops, which can take a minute. */
    static final long BIND_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(90);

    private static final long TICK_MILLIS = 500;
    private static final long BIND_RETRY_MILLIS = 200;

    /**
     * One phase of the restart.
     *
     * @param state   {@code done}, {@code running} or {@code failed}
     * @param seconds how long it took, or has taken so far
     */
    public record Step(String id, String label, String state, long seconds) {}

    /**
     * What jclaw.sh has recorded of a restart.
     *
     * @param state      {@code running}, {@code done} or {@code failed}; {@code none} when JClaw has no restart to report
     * @param failure    why it failed, or empty
     * @param finishedAt epoch seconds it finished at, or 0 while it runs
     */
    public record Progress(String state, String failure, List<Step> steps, long finishedAt) {}

    /**
     * Command-line options.
     *
     * @param parentPid the jclaw.sh it serves for, which it outlives only to serve a failure; -1 for none
     * @param nowMillis the wall clock; jclaw.sh passes its own reading at launch, which the narrator carries forward
     * @param httpsPort -1 for none
     */
    public record Options(Path statusFile, Optional<Path> pidFile, long parentPid, LongSupplier nowMillis,
                   int httpPort, int httpsPort, Optional<Path> cert, Optional<Path> key) {}

    private final Options options;
    private final byte[] secret;
    private final HttpServer http;
    private volatile Optional<HttpServer> https = Optional.empty();
    private volatile Optional<SSLContext> tls = Optional.empty();
    private volatile Optional<Progress> lastRead = Optional.empty();
    private boolean stopped;

    private RestartNarrator(Options options, byte[] secret, HttpServer http) {
        this.options = options;
        this.secret = secret.clone();
        this.http = http;
    }

    /** Binds the HTTP port, and the HTTPS port when one is configured and its PEM loads. */
    public static RestartNarrator start(Options options, byte[] secret, PrintStream out) throws IOException {
        var http = HttpServer.create(new InetSocketAddress(options.httpPort()), 0);
        var narrator = new RestartNarrator(options, secret, http);
        http.createContext("/", narrator::handle);
        http.start();
        // Before the PEM loads: jclaw.sh reads a listener with no "bound" under it as something else on the port.
        writePidFile(options, true);
        if (options.httpsPort() >= 0 && options.cert().isPresent() && options.key().isPresent()) {
            narrator.tls = tlsContext(options.cert().get(), options.key().get(), out);
            narrator.tryHttps(out);
        }
        return narrator;
    }

    public int httpPort() {
        return http.getAddress().getPort();
    }

    /** The bound HTTPS port, or -1. */
    public int httpsPort() {
        return https.map(s -> s.getAddress().getPort()).orElse(-1);
    }

    /** The old JVM closes its HTTPS listener beside the HTTP one, but nothing guarantees the order. */
    void tryHttps(PrintStream out) {
        if (https.isPresent() || tls.isEmpty()) return;
        try {
            var server = HttpsServer.create(new InetSocketAddress(options.httpsPort()), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls.get()));
            server.createContext("/", this::handle);
            server.start();
            https = Optional.of(server);
            out.println("Restart progress: also serving HTTPS on port " + httpsPort());
        } catch (IOException _) {
            // Held still; the next tick tries again.
        }
    }

    /** Idempotent: the shutdown hook and the main loop may both get here. */
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        http.stop(0);
        https.ifPresent(s -> s.stop(0));
    }

    /** The status file as last read whole; a restart with nothing recorded yet is simply running. */
    public Progress current() {
        var read = readProgress(options.statusFile(), options.nowMillis().getAsLong() / 1000);
        if (read.isPresent()) lastRead = read;
        return lastRead.orElse(new Progress("running", "", List.of(), 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var head = "HEAD".equals(exchange.getRequestMethod());
            if (!"GET".equals(exchange.getRequestMethod()) || !PROGRESS_PATH.equals(exchange.getRequestURI().getRawPath())) {
                // Never a 200: the panel's reconnect check reads any answer from /api/status as JClaw being back.
                exchange.getResponseHeaders().set("Retry-After", "5");
                send(exchange, 503, errorJson("restarting", "JClaw is restarting"), head);
                return;
            }
            var cookies = exchange.getRequestHeaders().get("Cookie");
            if (!operatorSession(cookies == null ? List.of() : cookies, secret, options.nowMillis().getAsLong())) {
                send(exchange, 401, errorJson("authentication_required", "Authentication required"), false);
                return;
            }
            send(exchange, 200, progressJson(current()), false);
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String json, boolean head) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (head) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        var body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    // ==================== the session check ====================

    /**
     * True when one of {@code cookieHeaders} carries Play's session cookie signed with {@code secret} for a
     * logged-in operator whose idle timeout has not passed. The credential generation and the revocation list
     * live in the database, which nothing reads during a restart; a cookie they would refuse sees progress only.
     */
    public static boolean operatorSession(List<String> cookieHeaders, byte[] secret, long nowMillis) {
        if (secret.length == 0) return false;
        for (var header : cookieHeaders) {
            for (var part : header.split(";")) {
                var pair = part.strip();
                if (!pair.startsWith(SESSION_COOKIE + "=")) continue;
                var value = pair.substring(SESSION_COOKIE.length() + 1);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (operatorSessionValue(value, secret, nowMillis)) return true;
            }
        }
        return false;
    }

    private static boolean operatorSessionValue(String value, byte[] secret, long nowMillis) {
        int dash = value.indexOf('-');
        if (dash < 0) return false;
        var data = value.substring(dash + 1);
        var expected = sign(data, secret).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, value.substring(0, dash).getBytes(StandardCharsets.US_ASCII))) return false;
        Map<String, String> session;
        try {
            session = decodeSession(data);
        } catch (IllegalArgumentException _) {
            return false;
        }
        // A bearer request stamps principal=agent.
        if (!"true".equals(session.get(AUTHENTICATED_KEY))) return false;
        if (AGENT_PRINCIPAL.equals(session.get(PRINCIPAL_KEY)) || SYSTEM_OWNER.equals(session.get(USERNAME_KEY))) return false;
        var sid = session.get(SESSION_ID_KEY);
        if (sid == null || sid.isBlank()) return false;
        var expiry = session.get(EXPIRY_KEY);
        try {
            return expiry != null && Long.parseLong(expiry) > nowMillis;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /** Play's {@code Crypto.sign}: lowercase hex HMAC-SHA256. */
    static String sign(String message, byte[] key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            var digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (var b : digest) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Play's {@code CookieDataCodec.decode}, for the {@code key=value&…} encoding it has written since 1.2.6. */
    private static Map<String, String> decodeSession(String data) {
        var out = new HashMap<String, String>();
        for (var keyValue : data.split("&")) {
            var split = keyValue.split("=", 2);
            if (split.length == 2) {
                out.put(URLDecoder.decode(split[0], StandardCharsets.UTF_8), URLDecoder.decode(split[1], StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // ==================== the status file ====================

    /**
     * The status file jclaw.sh writes, or empty when it is missing or not whole.
     *
     * @param nowSeconds epoch seconds, for how long the running phase has taken
     */
    public static Optional<Progress> readProgress(Path statusFile, long nowSeconds) {
        try {
            if (!(Json.parse(Files.readString(statusFile, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)) {
                return Optional.empty();
            }
            var state = switch (text(root, "state")) {
                case "done" -> "done";
                case "failed" -> "failed";
                default -> "running";
            };
            long finishedAt = number(root, "finishedAt");
            var steps = new ArrayList<Step>();
            if (root.get("phases") instanceof List<?> phases) {
                for (var element : phases) {
                    if (element instanceof Map<?, ?> phase) steps.add(step(phase, state, finishedAt, nowSeconds));
                }
            }
            return Optional.of(new Progress(state, "failed".equals(state) ? text(root, "reason") : "", List.copyOf(steps), finishedAt));
        } catch (IOException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static Step step(Map<?, ?> phase, String state, long finishedAt, long nowSeconds) {
        long start = number(phase, "start");
        long end = number(phase, "end");
        // A phase still open when the restart failed is where it failed.
        if (end <= 0 && "failed".equals(state)) {
            return new Step(text(phase, "id"), text(phase, "label"), "failed", Math.max(0, (finishedAt > 0 ? finishedAt : nowSeconds) - start));
        }
        if (end <= 0 && !"done".equals(state)) {
            return new Step(text(phase, "id"), text(phase, "label"), "running", Math.max(0, nowSeconds - start));
        }
        return new Step(text(phase, "id"), text(phase, "label"), "done", Math.max(0, (end > 0 ? end : finishedAt) - start));
    }

    private static String text(Map<?, ?> object, String key) {
        return object.get(key) instanceof String s ? s : "";
    }

    private static long number(Map<?, ?> object, String key) {
        return object.get(key) instanceof Long n ? n : 0;
    }

    /** The route's body, in the shape JClaw's own {@code ApiSystemController.RestartProgress} serializes to. */
    public static String progressJson(Progress progress) {
        var json = new StringBuilder("{\"source\":\"narrator\",\"state\":").append(quote(progress.state()))
                .append(",\"failure\":").append(progress.failure().isEmpty() ? "null" : quote(progress.failure()))
                .append(",\"steps\":[");
        for (int i = 0; i < progress.steps().size(); i++) {
            var step = progress.steps().get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(quote(step.id())).append(",\"label\":").append(quote(step.label()))
                    .append(",\"state\":").append(quote(step.state())).append(",\"seconds\":").append(step.seconds()).append('}');
        }
        return json.append("],\"services\":null}").toString();
    }

    private static String errorJson(String code, String message) {
        return "{\"type\":\"error\",\"code\":" + quote(code) + ",\"message\":" + quote(message) + "}";
    }

    static String quote(String s) {
        var out = new StringBuilder(s.length() + 2).append('"');
        for (var c : s.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c == '\u2028' || c == '\u2029') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    /** The JSON jclaw.sh writes: objects, arrays, strings, integers, booleans. Anything else is not whole. */
    private static final class Json {
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            var json = new Json(text);
            var value = json.value();
            json.space();
            if (json.at != text.length()) throw new IllegalArgumentException("trailing text");
            return value;
        }

        private Object value() {
            space();
            if (at >= text.length()) throw new IllegalArgumentException("truncated");
            char c = text.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
            if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
            if (text.startsWith("null", at)) { at += 4; return ""; }
            int from = at;
            if (c == '-') at++;
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            if (at == from || (c == '-' && at == from + 1)) throw new IllegalArgumentException("unexpected " + c);
            return Long.parseLong(text.substring(from, at));
        }

        private Map<String, Object> object() {
            var out = new LinkedHashMap<String, Object>();
            at++;
            space();
            if (peek('}')) return out;
            do {
                space();
                var key = string();
                space();
                expect(':');
                out.put(key, value());
                space();
            } while (peek(','));
            expect('}');
            return out;
        }

        private List<Object> array() {
            var out = new ArrayList<Object>();
            at++;
            space();
            if (peek(']')) return out;
            do {
                out.add(value());
                space();
            } while (peek(','));
            expect(']');
            return out;
        }

        private String string() {
            expect('"');
            var out = new StringBuilder();
            while (true) {
                if (at >= text.length()) throw new IllegalArgumentException("truncated string");
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) throw new IllegalArgumentException("truncated escape");
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw new IllegalArgumentException("truncated escape");
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(e);
                }
            }
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private boolean peek(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) throw new IllegalArgumentException("expected " + c + " at " + at);
        }
    }

    // ==================== TLS ====================

    /** The instance's PEM certificate chain and unencrypted PKCS#8 key, or empty when either will not load. */
    static Optional<SSLContext> tlsContext(Path cert, Path key, PrintStream out) {
        try {
            var chain = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(cert)))
                    .toArray(new Certificate[0]);
            if (chain.length == 0) throw new GeneralSecurityException("no certificate in " + cert);
            var privateKey = privateKey(Files.readString(key, StandardCharsets.US_ASCII));
            var password = new char[0];
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("jclaw", privateKey, password, chain);
            var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            var context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return Optional.of(context);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            out.println("Restart progress: serving HTTP only, the certificate did not load (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        int begin = pem.indexOf("-----BEGIN PRIVATE KEY-----");
        int end = pem.indexOf("-----END PRIVATE KEY-----");
        if (begin < 0 || end < begin) throw new GeneralSecurityException("the key is not an unencrypted PKCS#8 PEM");
        var der = Base64.getMimeDecoder().decode(pem.substring(begin + "-----BEGIN PRIVATE KEY-----".length(), end));
        for (var algorithm : List.of("RSA", "EC", "Ed25519", "Ed448", "RSASSA-PSS")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException _) {
                // Not this algorithm's key.
            }
        }
        throw new GeneralSecurityException("the key's algorithm is not one this JDK reads");
    }

    // ==================== the process ====================

    /**
     * Whether a running narrator carries on. A narrator whose jclaw.sh has gone stops, unless it is serving that
     * restart's failure, which it keeps for {@link #FAILURE_LINGER_NANOS}; every narrator stops at {@link #LIFETIME_NANOS}.
     *
     * @param failedForNanos how long it has seen the restart as failed, or -1 while it has not
     */
    public static boolean keepServing(boolean parentAlive, long servingNanos, long failedForNanos) {
        if (servingNanos >= LIFETIME_NANOS) return false;
        if (failedForNanos >= 0) return failedForNanos < FAILURE_LINGER_NANOS;
        return parentAlive;
    }

    /**
     * Arguments {@code --status FILE --pid-file FILE --parent PID --now EPOCH_SECONDS --http PORT
     * [--https PORT --cert PEM --key PEM]}, the secret in {@link #SECRET_ENV}. Returns 0 after serving, 1 when it
     * could not bind, 2 on bad arguments.
     */
    static int run(String[] args, String secret, PrintStream out) {
        var parsed = options(args, System.nanoTime());
        if (parsed.isEmpty() || secret.isEmpty()) {
            out.println("usage: RestartNarrator --status FILE --pid-file FILE --parent PID --now EPOCH_SECONDS --http PORT"
                    + " [--https PORT --cert PEM --key PEM], with the secret in " + SECRET_ENV);
            return 2;
        }
        var options = parsed.get();
        // jclaw.sh may delete these class files mid-restart (a source checkout's precompile): load them all now.
        RestartNarrator.class.getNestMembers();
        long started = System.nanoTime();
        // A parent already gone by now is a failed restart's jclaw.sh, which has exited and left this to serve it.
        var parent = options.parentPid() > 0 ? ProcessHandle.of(options.parentPid()) : Optional.<ProcessHandle>empty();
        boolean watchParent = options.parentPid() > 0;
        writePidFile(options, false);

        RestartNarrator narrator = null;
        while (narrator == null) {
            try {
                narrator = start(options, secret.getBytes(StandardCharsets.UTF_8), out);
            } catch (IOException e) {
                boolean abandoned = watchParent && !parent.map(ProcessHandle::isAlive).orElse(false)
                        && !readProgress(options.statusFile(), 0).map(p -> "failed".equals(p.state())).orElse(false);
                if (System.nanoTime() - started >= BIND_WINDOW_NANOS || abandoned) {
                    out.println("Restart progress: port " + options.httpPort() + " stayed busy (" + e.getMessage() + "); the panel counts instead");
                    deletePidFile(options);
                    return 1;
                }
                sleep(BIND_RETRY_MILLIS);
            }
        }
        out.println("Restart progress: serving on port " + narrator.httpPort());
        var serving = narrator;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            serving.stop();
            deletePidFile(options);
        }));

        long failedSince = -1;
        while (true) {
            sleep(TICK_MILLIS);
            serving.tryHttps(out);
            long now = System.nanoTime();
            if (failedSince < 0 && "failed".equals(serving.current().state())) failedSince = now;
            boolean parentAlive = !watchParent || parent.map(ProcessHandle::isAlive).orElse(false);
            if (!keepServing(parentAlive, now - started, failedSince < 0 ? -1 : now - failedSince)) break;
        }
        serving.stop();
        deletePidFile(options);
        return 0;
    }

    public static Optional<Options> options(String[] args, long nanoAtLaunch) {
        var values = new HashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) return Optional.empty();
            values.put(args[i].substring(2), args[i + 1]);
        }
        var status = values.get("status");
        var http = values.get("http");
        var now = values.get("now");
        if (args.length % 2 != 0 || status == null || http == null || now == null) return Optional.empty();
        try {
            long launchMillis = Long.parseLong(now) * 1000;
            LongSupplier clock = () -> launchMillis + (System.nanoTime() - nanoAtLaunch) / 1_000_000;
            var https = values.get("https");
            var parent = values.get("parent");
            return Optional.of(new Options(Path.of(status), Optional.ofNullable(values.get("pid-file")).map(Path::of),
                    parent == null ? -1 : Long.parseLong(parent), clock, Integer.parseInt(http),
                    https == null ? -1 : Integer.parseInt(https),
                    Optional.ofNullable(values.get("cert")).map(Path::of), Optional.ofNullable(values.get("key")).map(Path::of)));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }

    /** Line one is this process's pid, which Windows' taskkill needs; a second line {@code bound} says it holds the port. */
    private static void writePidFile(Options options, boolean bound) {
        options.pidFile().ifPresent(file -> {
            try {
                var tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, ProcessHandle.current().pid() + (bound ? "\nbound\n" : "\n"));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException _) {
                // jclaw.sh then sees no narrator, and starts the JVM without waiting for one.
            }
        });
    }

    private static void deletePidFile(Options options) {
        options.pidFile().ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException _) {
                // jclaw.sh deletes it too.
            }
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        var secret = System.getenv(SECRET_ENV);
        System.exit(run(args, secret == null ? "" : secret, System.out));
    }
}
