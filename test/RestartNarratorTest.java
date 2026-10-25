import com.google.gson.JsonParser;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import controllers.ApiSystemController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.libs.Crypto;
import play.mvc.CookieDataCodec;
import play.test.UnitTest;
import utils.RestartNarrator;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static utils.GsonHolder.GSON;

/**
 * The stand-in {@code jclaw.sh restart} runs on the instance's ports while no JClaw JVM does: who it answers,
 * what it answers, how it reads jclaw.sh's status file, and when it goes away. The session cookies are minted
 * with Play's own codec and signature, so the check is held to what JClaw really writes.
 */
class RestartNarratorTest extends UnitTest {

    private static final byte[] SECRET = "a-test-application-secret".getBytes(StandardCharsets.UTF_8);
    private static final long NOW_MILLIS = 1_800_000_000_000L;
    private static final long NOW = NOW_MILLIS / 1000;

    @TempDir Path tmp;

    private final List<RestartNarrator> started = new ArrayList<>();
    private final List<Process> processes = new ArrayList<>();

    @AfterEach
    void stopEverything() throws Exception {
        for (var narrator : started) narrator.stop();
        for (var p : processes) p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }

    // --- standing alone ---

    private static final DescribedPredicate<JavaClass> JAVA_BASE_OR_HTTPSERVER = DescribedPredicate.describe(
            "in the java.base or jdk.httpserver module, or the narrator itself",
            c -> c.getName().startsWith("utils.RestartNarrator") || moduleOf(c).map(m -> m.equals("java.base") || m.equals("jdk.httpserver")).orElse(false));

    private static Optional<String> moduleOf(JavaClass c) {
        var base = c.getBaseComponentType();
        if (base.isPrimitive()) return Optional.of("java.base");
        try {
            return Optional.ofNullable(Class.forName(base.getName(), false, ClassLoader.getPlatformClassLoader()).getModule().getName());
        } catch (ClassNotFoundException _) {
            return Optional.empty();
        }
    }

    @Test
    void theNarratorStandsAlone() {
        // jclaw.sh runs it on the installed classes alone, with no library on the class path and Azul's JRE:
        // a reference past these two modules fails at runtime, in the middle of a restart.
        ArchRule rule = classes().that().haveNameMatching("utils\\.RestartNarrator(\\$.*)?")
                .should().onlyDependOnClassesThat(JAVA_BASE_OR_HTTPSERVER)
                .because("jclaw.sh restart runs RestartNarrator out of process on the compiled classes alone");
        rule.check(ArchitectureTest.APP_CLASSES);

        var importer = ArchitectureTest.APP_CLASSES;
        assertTrue(JAVA_BASE_OR_HTTPSERVER.test(importer.get(RestartNarrator.class)));
        assertFalse(JAVA_BASE_OR_HTTPSERVER.test(importer.get("utils.AppClock")), "an app class is not a JDK module");
        assertFalse(JAVA_BASE_OR_HTTPSERVER.test(importer.get("utils.GsonHolder")));
    }

    // --- who it answers ---

    @Test
    void aSessionPlaySignedForTheLoggedInOperatorIsAccepted() throws Exception {
        assertTrue(RestartNarrator.operatorSession(List.of(cookie(operator(), SECRET)), SECRET, NOW_MILLIS));
        assertTrue(RestartNarrator.operatorSession(List.of("theme=dark; " + cookie(operator(), SECRET) + "; other=1"), SECRET, NOW_MILLIS));
        assertTrue(RestartNarrator.operatorSession(List.of("theme=dark", cookie(operator(), SECRET)), SECRET, NOW_MILLIS));
    }

    @Test
    void aForgedAbsentOrForeignSessionIsRefused() throws Exception {
        assertFalse(RestartNarrator.operatorSession(List.of(), SECRET, NOW_MILLIS), "no cookie");
        assertFalse(RestartNarrator.operatorSession(List.of("theme=dark"), SECRET, NOW_MILLIS), "no session cookie");
        assertFalse(RestartNarrator.operatorSession(List.of(cookie(operator(), "another-secret".getBytes(StandardCharsets.UTF_8))), SECRET, NOW_MILLIS),
                "signed with another secret");
        var genuine = cookie(operator(), SECRET);
        assertFalse(RestartNarrator.operatorSession(List.of(genuine.replace("username=admin", "username=admim")), SECRET, NOW_MILLIS),
                "data changed under its signature");
        assertFalse(RestartNarrator.operatorSession(List.of("PLAY_SESSION=" + genuine.substring(genuine.indexOf('-') + 1)), SECRET, NOW_MILLIS),
                "no signature");
        assertFalse(RestartNarrator.operatorSession(List.of(cookie(operator(), SECRET)), new byte[0], NOW_MILLIS), "no secret to check against");
    }

    @Test
    void onlyALiveOperatorLoginCounts() throws Exception {
        var expired = operator();
        expired.put("___TS", Long.toString(NOW_MILLIS - 1));
        assertFalse(RestartNarrator.operatorSession(List.of(cookie(expired, SECRET)), SECRET, NOW_MILLIS), "idle timeout passed");
        var lastMillisecond = operator();
        lastMillisecond.put("___TS", Long.toString(NOW_MILLIS + 1));
        assertTrue(RestartNarrator.operatorSession(List.of(cookie(lastMillisecond, SECRET)), SECRET, NOW_MILLIS), "a millisecond to go");

        for (var change : List.of(Map.of("authenticated", "false"), Map.of("principal", "agent"), Map.of("username", "system"), Map.of("sid", ""))) {
            var session = operator();
            session.putAll(change);
            assertFalse(RestartNarrator.operatorSession(List.of(cookie(session, SECRET)), SECRET, NOW_MILLIS), change.toString());
        }
        for (var key : List.of("authenticated", "sid", "___TS")) {
            var session = operator();
            session.remove(key);
            assertFalse(RestartNarrator.operatorSession(List.of(cookie(session, SECRET)), SECRET, NOW_MILLIS), "without " + key);
        }
    }

    // --- what it answers ---

    @Test
    void itServesProgressToTheOperatorAndNothingElseToAnyone() throws Exception {
        var status = writeStatus("""
                {"startedAt":%d,"state":"running","reason":"","finishedAt":0,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":%d},
                 {"id":"java","label":"Installing Java 25.0.5","start":%d,"end":0}]}
                """.formatted(NOW - 10, NOW - 10, NOW - 6, NOW - 6));
        int port = start(options(status, -1, Optional.empty(), Optional.empty())).httpPort();
        var progress = "http://127.0.0.1:" + port + RestartNarrator.PROGRESS_PATH;

        var operator = get(progress, cookie(operator(), SECRET));
        assertEquals(200, operator.status());
        assertEquals("{\"source\":\"narrator\",\"state\":\"running\",\"failure\":null,\"steps\":["
                + "{\"id\":\"stop\",\"label\":\"Stopping JClaw\",\"state\":\"done\",\"seconds\":4},"
                + "{\"id\":\"java\",\"label\":\"Installing Java 25.0.5\",\"state\":\"running\",\"seconds\":6}],\"services\":null}", operator.body());

        var anonymous = get(progress, null);
        assertEquals(401, anonymous.status());
        assertEquals("{\"type\":\"error\",\"code\":\"authentication_required\",\"message\":\"Authentication required\"}", anonymous.body());
        assertEquals(401, get(progress, cookie(operator(), "forged".getBytes(StandardCharsets.UTF_8))).status());

        // The panel's reconnect check reads any 200 from /api/status as JClaw being back.
        for (var path : List.of("/api/status", "/", "/index.html", "/api/system/restart", RestartNarrator.PROGRESS_PATH + "/")) {
            var other = get("http://127.0.0.1:" + port + path, cookie(operator(), SECRET));
            assertEquals(503, other.status(), path);
            assertEquals("{\"type\":\"error\",\"code\":\"restarting\",\"message\":\"JClaw is restarting\"}", other.body(), path);
        }
        var post = (HttpURLConnection) URI.create(progress).toURL().openConnection();
        post.setRequestMethod("POST");
        post.setRequestProperty("Cookie", cookie(operator(), SECRET));
        assertEquals(503, post.getResponseCode());
        post.disconnect();
    }

    @Test
    void itWritesTheShapeJClawsOwnRouteSerializes() {
        var progress = new RestartNarrator.Progress("failed", "JClaw did not start",
                List.of(new RestartNarrator.Step("stop", "Stopping \"JClaw\"", "done", 4),
                        new RestartNarrator.Step("start", "Starting JClaw (connecting channels)\n", "failed", 12)), NOW);
        var jclaw = GSON.toJson(new ApiSystemController.RestartProgress("narrator", progress.state(), progress.failure(), progress.steps(), null));
        assertEquals(JsonParser.parseString(jclaw), JsonParser.parseString(RestartNarrator.progressJson(progress)));

        var none = new RestartNarrator.Progress("running", "", List.of(), 0);
        assertEquals(JsonParser.parseString(GSON.toJson(new ApiSystemController.RestartProgress("narrator", "running", null, List.of(), null))),
                JsonParser.parseString(RestartNarrator.progressJson(none)));
    }

    @Test
    void aTornReadServesTheLastWholeOne() throws Exception {
        var status = writeStatus("{\"startedAt\":%d,\"state\":\"running\",\"reason\":\"\",\"finishedAt\":0,\"phases\":[{\"id\":\"stop\",\"label\":\"Stopping JClaw\",\"start\":%d,\"end\":0}]}"
                .formatted(NOW - 3, NOW - 3));
        var narrator = start(options(status, -1, Optional.empty(), Optional.empty()));
        assertEquals(List.of(new RestartNarrator.Step("stop", "Stopping JClaw", "running", 3)), narrator.current().steps());

        Files.writeString(status, "{\"startedAt\":%d,\"state\":\"running\",\"phases\":[{\"id\":\"stop\",\"lab".formatted(NOW));
        assertEquals(List.of(new RestartNarrator.Step("stop", "Stopping JClaw", "running", 3)), narrator.current().steps());
        Files.delete(status);
        assertEquals(List.of(new RestartNarrator.Step("stop", "Stopping JClaw", "running", 3)), narrator.current().steps());
    }

    @Test
    void beforeAnyWholeReadARestartIsSimplyRunning() throws Exception {
        var narrator = start(options(tmp.resolve("not-written-yet.json"), -1, Optional.empty(), Optional.empty()));
        assertEquals(new RestartNarrator.Progress("running", "", List.of(), 0), narrator.current());
    }

    // --- the status file ---

    @Test
    void eachPhaseTakesItsTimeFromTheFile() throws Exception {
        var running = writeStatus("""
                {"startedAt":%d,"state":"running","reason":"","finishedAt":0,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":%d},
                 {"id":"aot","label":"Rebuilding the startup cache","start":%d,"end":0}]}""".formatted(NOW - 40, NOW - 40, NOW - 35, NOW - 35));
        assertEquals(Optional.of(new RestartNarrator.Progress("running", "", List.of(
                new RestartNarrator.Step("stop", "Stopping JClaw", "done", 5),
                new RestartNarrator.Step("aot", "Rebuilding the startup cache", "running", 35)), 0)),
                RestartNarrator.readProgress(running, NOW));

        var done = writeStatus("""
                {"startedAt":%d,"state":"done","reason":"","finishedAt":%d,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":%d},
                 {"id":"start","label":"Starting JClaw (connecting channels)","start":%d,"end":%d}]}""".formatted(NOW - 20, NOW - 2, NOW - 20, NOW - 14, NOW - 14, NOW - 2));
        assertEquals(Optional.of(new RestartNarrator.Progress("done", "", List.of(
                new RestartNarrator.Step("stop", "Stopping JClaw", "done", 6),
                new RestartNarrator.Step("start", "Starting JClaw (connecting channels)", "done", 12)), NOW - 2)),
                RestartNarrator.readProgress(done, NOW));
    }

    @Test
    void aFailedRestartNamesThePhaseItStoppedIn() throws Exception {
        var failed = writeStatus("""
                {"startedAt":%d,"state":"failed","reason":"JClaw did not start","finishedAt":%d,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":%d},
                 {"id":"start","label":"Starting JClaw (connecting channels)","start":%d,"end":0}]}""".formatted(NOW - 30, NOW - 10, NOW - 30, NOW - 25, NOW - 25));
        // The time it failed at, not the time it is read at.
        assertEquals(Optional.of(new RestartNarrator.Progress("failed", "JClaw did not start", List.of(
                new RestartNarrator.Step("stop", "Stopping JClaw", "done", 5),
                new RestartNarrator.Step("start", "Starting JClaw (connecting channels)", "failed", 15)), NOW - 10)),
                RestartNarrator.readProgress(failed, NOW + 600));
    }

    @Test
    void aFileThatIsNotWholeReadsAsNothing() throws Exception {
        assertEquals(Optional.empty(), RestartNarrator.readProgress(tmp.resolve("missing.json"), NOW));
        for (var torn : List.of("", "{", "{\"state\":\"running\",\"phases\":[{\"id\":\"stop\"", "[1,2]", "\"running\"", "{\"state\":\"running\"} trailing",
                "{\"state\":\"running\",\"finishedAt\":12a}")) {
            assertEquals(Optional.empty(), RestartNarrator.readProgress(writeStatus(torn), NOW), torn);
        }
    }

    @Test
    void stringsKeepTheirEscapes() throws Exception {
        var status = writeStatus("{\"state\":\"failed\",\"reason\":\"a \\\"quoted\\\" \\\\ path\\u00e9\",\"finishedAt\":5,\"phases\":[]}");
        assertEquals("a \"quoted\" \\ pathé", RestartNarrator.readProgress(status, NOW).orElseThrow().failure());
    }

    // --- TLS ---

    @Test
    void itAnswersOverTlsWithTheInstancesRsaOrEcCertificate() throws Exception {
        for (var algorithm : List.of("RSA", "EC")) {
            var pem = pemPair(algorithm);
            var status = writeStatus("{\"state\":\"running\",\"finishedAt\":0,\"phases\":[]}");
            var narrator = start(options(status, 0, Optional.of(pem[0]), Optional.of(pem[1])));
            int https = narrator.httpsPort();
            assertTrue(https > 0, algorithm + ": no HTTPS port bound");

            var connection = (HttpsURLConnection) URI.create("https://localhost:" + https + RestartNarrator.PROGRESS_PATH).toURL().openConnection();
            connection.setSSLSocketFactory(trusting(pem[0]).getSocketFactory());
            connection.setRequestProperty("Cookie", cookie(operator(), SECRET));
            assertEquals(200, connection.getResponseCode(), algorithm);
            assertEquals("{\"source\":\"narrator\",\"state\":\"running\",\"failure\":null,\"steps\":[],\"services\":null}",
                    new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            connection.disconnect();
        }
    }

    @Test
    void itSaysBoundAsSoonAsTheHttpPortIsHeldBeforeTheCertificateLoads() throws Exception {
        // jclaw.sh's port check is a bare connect: a listener with no "bound" under it reads as a foreign holder.
        var pem = pemPair("EC");
        var key = tmp.resolve("host.key.fifo");
        var mkfifo = new ProcessBuilder("mkfifo", key.toString()).start();
        assumeTrue(mkfifo.waitFor(10, TimeUnit.SECONDS) && mkfifo.exitValue() == 0, "no mkfifo to hold the key read open");
        var pidFile = tmp.resolve("narrator.pid");
        LongSupplier clock = () -> NOW_MILLIS;
        var options = new RestartNarrator.Options(writeStatus("{\"state\":\"running\",\"phases\":[]}"), Optional.of(pidFile), -1, clock,
                0, 0, Optional.of(pem[0]), Optional.of(key));
        var narrator = new CompletableFuture<RestartNarrator>();
        var starting = Thread.ofPlatform().start(() -> {
            try {
                narrator.complete(start(options));
            } catch (IOException e) {
                narrator.completeExceptionally(e);
            }
        });
        try {
            waitFor(() -> lines(pidFile).equals(List.of(Long.toString(ProcessHandle.current().pid()), "bound")), "bound while the key still loads");
            assertFalse(narrator.isDone(), "the key read was meant to be holding start() open");
        } finally {
            Files.writeString(key, Files.readString(pem[1]));
            starting.join(TimeUnit.SECONDS.toMillis(20));
        }
        assertTrue(narrator.get(20, TimeUnit.SECONDS).httpsPort() > 0, "the key loaded once written");
    }

    @Test
    void aCertificateThatWillNotLoadLeavesHttpOnly() throws Exception {
        var cert = Files.writeString(tmp.resolve("host.cert"), "not a certificate\n");
        var key = Files.writeString(tmp.resolve("host.key"), "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n");
        var out = new ByteArrayOutputStream();
        var narrator = start(options(writeStatus("{\"state\":\"running\",\"phases\":[]}"), 0, Optional.of(cert), Optional.of(key)), new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals(-1, narrator.httpsPort());
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Restart progress: serving HTTP only, the certificate did not load"), out.toString(StandardCharsets.UTF_8));
        assertEquals(200, get("http://127.0.0.1:" + narrator.httpPort() + RestartNarrator.PROGRESS_PATH, cookie(operator(), SECRET)).status());
    }

    // --- when it goes away ---

    @Test
    void itLastsAsLongAsItsJclawShUnlessItServesAFailure() {
        long minute = TimeUnit.MINUTES.toNanos(1);
        assertTrue(RestartNarrator.keepServing(true, minute, -1));
        assertFalse(RestartNarrator.keepServing(false, minute, -1), "jclaw.sh has gone");
        assertTrue(RestartNarrator.keepServing(false, minute, 0), "jclaw.sh exits on a failure, which is kept on offer");
        assertTrue(RestartNarrator.keepServing(false, minute, RestartNarrator.FAILURE_LINGER_NANOS - 1));
        assertFalse(RestartNarrator.keepServing(false, minute, RestartNarrator.FAILURE_LINGER_NANOS), "for a bounded time");
        assertTrue(RestartNarrator.keepServing(true, RestartNarrator.LIFETIME_NANOS - 1, -1));
        assertFalse(RestartNarrator.keepServing(true, RestartNarrator.LIFETIME_NANOS, -1), "every narrator ends at the cap");
        assertFalse(RestartNarrator.keepServing(false, RestartNarrator.LIFETIME_NANOS, 0));
    }

    @Test
    void theCommandLineIsTakenWholeOrNotAtAll() throws Exception {
        assertTrue(options("--status", "s.json", "--http", "9000", "--now", "100").isPresent());
        assertFalse(options("--status", "s.json", "--http", "9000").isPresent(), "no --now");
        assertFalse(options("--http", "9000", "--now", "100").isPresent(), "no --status");
        assertFalse(options("--status", "s.json", "--http", "9000", "--now", "100", "--https").isPresent(), "a flag without its value");
        assertFalse(options("--status", "s.json", "--http", "nine", "--now", "100").isPresent(), "a port that is not a number");
    }

    @Test
    void itBindsOnlyOnceTheOldJvmLetsGoOfThePortAndSaysSo() throws Exception {
        var status = writeStatus("{\"state\":\"running\",\"phases\":[]}");
        var pidFile = tmp.resolve("narrator.pid");
        int port;
        Process narrator;
        try (var oldJvm = new ServerSocket(0)) {
            port = oldJvm.getLocalPort();
            narrator = launch(status, pidFile, port, ProcessHandle.current().pid());
            waitFor(() -> Files.exists(pidFile), "the pid file");
            Thread.sleep(1500);
            assertEquals(List.of(Long.toString(narrator.pid())), Files.readAllLines(pidFile), "it does not claim the port the old JVM holds");
        }
        waitFor(() -> lines(pidFile).equals(List.of(Long.toString(narrator.pid()), "bound")), "the narrator to bind");
        assertEquals(503, get("http://127.0.0.1:" + port + "/api/status", null).status());

        narrator.destroy();
        assertTrue(narrator.waitFor(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(pidFile), "it removes its pid file as it stops");
    }

    @Test
    void itTakesTheHttpsPortOnceTheOldJvmLetsGoOfIt() throws Exception {
        var pem = pemPair("RSA");
        var pidFile = tmp.resolve("narrator.pid");
        var oldJvm = new ServerSocket(0);
        int https = oldJvm.getLocalPort();
        try {
            launch(writeStatus("{\"state\":\"running\",\"phases\":[]}"), pidFile, freePort(), ProcessHandle.current().pid(),
                    "--https", Integer.toString(https), "--cert", pem[0].toString(), "--key", pem[1].toString());
            waitFor(() -> lines(pidFile).contains("bound"), "the narrator to bind HTTP");
            Thread.sleep(1500);
            // The old JVM's listener takes the connection and never answers the handshake.
            assertThrows(IOException.class, () -> httpsProgress(https, pem[0]));
        } finally {
            oldJvm.close();
        }
        waitFor(() -> {
            try {
                return httpsProgress(https, pem[0]) == 200;
            } catch (IOException _) {
                return false;
            }
        }, "HTTPS once the old JVM let go");
    }

    private static int httpsProgress(int port, Path cert) throws IOException {
        var connection = (HttpsURLConnection) URI.create("https://localhost:" + port + RestartNarrator.PROGRESS_PATH).toURL().openConnection();
        try {
            connection.setSSLSocketFactory(trusting(cert).getSocketFactory());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setRequestProperty("Cookie", cookie(operator(), SECRET));
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void itGoesWithItsJclawShButServesAFailureOn() throws Exception {
        var pidFile = tmp.resolve("narrator.pid");
        var running = writeStatus("{\"state\":\"running\",\"phases\":[]}");
        var shell = new ProcessBuilder("sleep", "60").start();
        processes.add(shell);
        var narrator = launch(running, pidFile, freePort(), shell.pid());
        waitFor(() -> lines(pidFile).contains("bound"), "the narrator to bind");
        shell.destroy();
        assertTrue(narrator.waitFor(10, TimeUnit.SECONDS), "it outlived the jclaw.sh it narrates for");
        assertEquals(0, narrator.exitValue());

        var failed = Files.writeString(tmp.resolve("failed.json"), "{\"state\":\"failed\",\"reason\":\"JClaw did not start\",\"finishedAt\":%d,\"phases\":[]}".formatted(NOW));
        var exited = new ProcessBuilder("true").start();
        assertTrue(exited.waitFor(10, TimeUnit.SECONDS));
        var lingering = launch(failed, tmp.resolve("lingering.pid"), freePort(), exited.pid());
        waitFor(() -> lines(tmp.resolve("lingering.pid")).contains("bound"), "the narrator to bind");
        Thread.sleep(2000);
        assertTrue(lingering.isAlive(), "a failure is served after jclaw.sh has exited");
    }

    @Test
    void itRefusesToStartWithoutItsArgumentsOrTheSecret() throws Exception {
        var noSecret = new ProcessBuilder(java(), "-cp", "build/classes/java/main", "utils.RestartNarrator",
                "--status", tmp.resolve("s.json").toString(), "--http", "0", "--now", "100").redirectErrorStream(true);
        noSecret.environment().remove(RestartNarrator.SECRET_ENV);
        var p = noSecret.start();
        processes.add(p);
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(2, p.exitValue(), out);
        assertTrue(out.startsWith("usage: RestartNarrator"), out);
    }

    // --- helpers ---

    private record Answer(int status, String body) {}

    private static Map<String, String> operator() {
        var session = new LinkedHashMap<String, String>();
        session.put("___TS", Long.toString(NOW_MILLIS + 3_600_000));
        session.put("authenticated", "true");
        session.put("username", "admin");
        session.put("cv", "3");
        session.put("sid", "b0c3b6a1-session");
        return session;
    }

    /** The PLAY_SESSION cookie Play's CookieSessionStore would set for {@code data}. */
    private static String cookie(Map<String, String> data, byte[] secret) throws IOException {
        var encoded = CookieDataCodec.encode(data);
        return "PLAY_SESSION=" + Crypto.sign(encoded, secret) + "-" + encoded;
    }

    private Path writeStatus(String json) throws IOException {
        var file = tmp.resolve("status-" + System.nanoTime() + ".json");
        return Files.writeString(file, json);
    }

    /** The narrator on an ephemeral port, reading a clock stopped at {@link #NOW_MILLIS}. */
    private static RestartNarrator.Options options(Path status, int httpsPort, Optional<Path> cert, Optional<Path> key) {
        LongSupplier clock = () -> NOW_MILLIS;
        return new RestartNarrator.Options(status, Optional.empty(), -1, clock, 0, httpsPort, cert, key);
    }

    private static Optional<RestartNarrator.Options> options(String... args) {
        return RestartNarrator.options(args, 0);
    }

    private RestartNarrator start(RestartNarrator.Options options) throws IOException {
        return start(options, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private RestartNarrator start(RestartNarrator.Options options, PrintStream out) throws IOException {
        var narrator = RestartNarrator.start(options, SECRET, out);
        started.add(narrator);
        return narrator;
    }

    private static Answer get(String url, String cookie) throws IOException {
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        int status = connection.getResponseCode();
        var stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        var body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Answer(status, body);
    }

    private Process launch(Path status, Path pidFile, int port, long parent, String... more) throws IOException {
        var command = new ArrayList<>(List.of(java(), "-cp", "build/classes/java/main", "utils.RestartNarrator",
                "--status", status.toString(), "--pid-file", pidFile.toString(), "--parent", Long.toString(parent),
                "--now", Long.toString(NOW), "--http", Integer.toString(port)));
        command.addAll(List.of(more));
        var pb = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(tmp.resolve("narrator-" + port + ".out").toFile());
        pb.environment().put(RestartNarrator.SECRET_ENV, new String(SECRET, StandardCharsets.UTF_8));
        var p = pb.start();
        processes.add(p);
        return p;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException _) {
            return List.of();
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    /** A certificate for localhost and its unencrypted PKCS#8 key, as mkcert writes them, made with the JDK's keytool. */
    private Path[] pemPair(String algorithm) throws Exception {
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "the test JVM has no keytool");
        var store = tmp.resolve(algorithm + ".p12");
        var p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "jclaw", "-keyalg", algorithm,
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, out);
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        var mime = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        var cert = Files.writeString(tmp.resolve(algorithm + ".cert"), "-----BEGIN CERTIFICATE-----\n"
                + mime.encodeToString(keys.getCertificate("jclaw").getEncoded()) + "\n-----END CERTIFICATE-----\n");
        var key = Files.writeString(tmp.resolve(algorithm + ".key"), "-----BEGIN PRIVATE KEY-----\n"
                + mime.encodeToString(((PrivateKey) keys.getKey("jclaw", "changeit".toCharArray())).getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        return new Path[] {cert, key};
    }

    /** A client that trusts exactly the certificate in {@code pem}. */
    private static SSLContext trusting(Path pem) throws IOException, GeneralSecurityException {
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (var in = Files.newInputStream(pem)) {
            trust.setCertificateEntry("jclaw", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
