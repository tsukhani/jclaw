import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.EventLogger;
import services.factory.FactoryHome;
import services.factory.FactoryInstallJob;
import services.factory.FactoryProcess;
import services.factory.FactoryStatus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The Software Factory setup routes (JCLAW-1393), driven over HTTP with a fake command runner and
 * a temporary factory home — never real docker, never the real installer or {@code ~/.jclaw-factory}.
 */
class ApiFactorySetupControllerTest extends FunctionalTest {

    private static final String SECRET = "sentinel-s3cr3t-7f1d";

    @TempDir
    Path home;

    private volatile FactoryProcess.Runner installer = (c, d, t) -> new FactoryProcess.ExecResult(0, "", false);
    private final List<List<String>> calls = new ArrayList<>();
    private final List<String> started = new ArrayList<>();

    @BeforeEach
    void setup() {
        FactoryRunnerSync.acquire();
        AuthFixture.seedAdminPassword("changeme");
        FactoryHome.setHomeForTest(home);
        FactoryProcess.setRunnerForTest(new FactoryProcess.Runner() {
            @Override
            public FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout) {
                synchronized (calls) {
                    calls.add(command);
                }
                if (command.getFirst().endsWith("install-agent.sh")) return installer.run(command, workDir, timeout);
                if (command.getFirst().equals("node")) return new FactoryProcess.ExecResult(0, "v24.3.0\n", false);
                return new FactoryProcess.ExecResult(0, "27.0.0\n", false);
            }

            @Override
            public FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout,
                                                 FactoryProcess.OutputSink sink) {
                if (!command.getFirst().endsWith("install-agent.sh")) {
                    return FactoryProcess.Runner.super.run(command, workDir, timeout, sink);
                }
                synchronized (calls) {
                    calls.add(command);
                }
                var hello = "step 1\n".getBytes(StandardCharsets.UTF_8);
                sink.write(hello, 0, hello.length);
                return installer.run(command, workDir, timeout);
            }
        });
        FactoryStatus.clearCache();
        clearCookies();
    }

    @AfterEach
    void clearSeams() throws Exception {
        for (var id : started) awaitFinished(id);
        FactoryHome.setHomeForTest(null);
        FactoryProcess.setRunnerForTest(null);
        clearCookies();
        FactoryRunnerSync.release();
    }

    private void login() {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static JsonObject json(Http.Response resp) {
        return JsonParser.parseString(getContent(resp)).getAsJsonObject();
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static String mode(Path file) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    // Only the factory test classes log to "factory", and FactoryRunnerSync runs them one at a time.
    private static Http.Response postCredentials(List<EventLogger.Captured> events, String body) {
        var resp = new Http.Response[1];
        events.addAll(EventLogger.captureMatchingForTest(e -> mentions(e, SECRET) || "factory".equals(e.category()),
                _ -> resp[0] = POST("/api/factory/setup/credentials", "application/json", body)));
        return resp[0];
    }

    private static boolean mentions(EventLogger.Captured e, String fragment) {
        return e.message().contains(fragment) || (e.details() != null && e.details().contains(fragment));
    }

    private static List<String> messagesContaining(List<EventLogger.Captured> events, String fragment) {
        return events.stream().filter(e -> mentions(e, fragment)).map(EventLogger.Captured::message).toList();
    }

    private JsonObject startInstall() {
        var resp = POST("/api/factory/setup/install", "application/json", "{}");
        assertStatus(202, resp);
        var job = json(resp);
        started.add(job.get("id").getAsString());
        return job;
    }

    private static void awaitFinished(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var view = FactoryInstallJob.get(id);
            if (view != null && !view.state().equals("running")) return;
            Thread.sleep(20);
        }
        throw new AssertionError("install job " + id + " never finished");
    }

    private JsonObject awaitDone(String id) throws Exception {
        awaitFinished(id);
        return json(GET("/api/factory/setup/install/" + id));
    }

    // --- GET ---

    @Test
    void setupReportsPrerequisitesAndBooleansButNoValue() throws Exception {
        Files.writeString(home.resolve(".env"), "ANTHROPIC_API_KEY=" + SECRET + "\n");
        login();
        var resp = GET("/api/factory/setup");
        assertIsOk(resp);
        var body = getContent(resp);
        assertFalse(body.contains(SECRET), body);
        var view = json(resp);
        assertTrue(view.get("hasModelCredential").getAsBoolean(), body);
        assertFalse(view.get("hasJira").getAsBoolean(), body);
        assertFalse(view.get("hasGithub").getAsBoolean(), body);
        var ids = new ArrayList<String>();
        for (var p : view.getAsJsonArray("prerequisites")) {
            var o = p.getAsJsonObject();
            ids.add(o.get("id").getAsString());
            assertTrue(List.of("ok", "missing", "unknown").contains(o.get("state").getAsString()), body);
        }
        assertEquals(List.of("macos", "docker", "node", "checkout"), ids);
    }

    // --- credentials ---

    @Test
    void eachCredentialFieldWritesItsFileOwnerOnly() throws Exception {
        Files.writeString(home.resolve(".env"), "FOO=1\nANTHROPIC_API_KEY=old\n");
        login();
        var events = new ArrayList<EventLogger.Captured>();
        var resp = postCredentials(events, "{\"claudeOauthToken\":\"" + SECRET + "-oauth\"}");
        assertIsOk(resp);
        assertFalse(getContent(resp).contains(SECRET), getContent(resp));
        assertTrue(json(resp).get("hasModelCredential").getAsBoolean());
        assertEquals("FOO=1\nCLAUDE_CODE_OAUTH_TOKEN=" + SECRET + "-oauth\n", Files.readString(home.resolve(".env")));

        resp = postCredentials(events, "{\"jiraUrl\":\"https://jira.example.com/" + SECRET + "\",\"jiraPersonalToken\":\""
                + SECRET + "-jira\"," + "\"githubToken\":\"" + SECRET + "-gh\"}");
        assertIsOk(resp);
        assertFalse(getContent(resp).contains(SECRET), getContent(resp));
        assertTrue(json(resp).get("hasJira").getAsBoolean());
        assertTrue(json(resp).get("hasGithub").getAsBoolean());
        assertEquals("JIRA_URL=https://jira.example.com/" + SECRET + "\nJIRA_PERSONAL_TOKEN=" + SECRET + "-jira\n",
                Files.readString(home.resolve("jira.env")));
        assertEquals("GITHUB_TOKEN=" + SECRET + "-gh\n", Files.readString(home.resolve("github.env")));
        if (posix()) {
            for (var f : List.of(".env", "jira.env", "github.env")) assertEquals("rw-------", mode(home.resolve(f)), f);
        }
        assertEquals(List.of(), messagesContaining(events, SECRET));
        assertFalse(messagesContaining(events, "Operator updated factory credentials: jiraUrl, jiraPersonalToken, githubToken")
                .isEmpty());
    }

    @Test
    void refusedCredentialsWriteNothingAndEchoNoValue() throws Exception {
        Files.writeString(home.resolve(".env"), "ANTHROPIC_API_KEY=keep\n");
        var before = Files.readAllBytes(home.resolve(".env"));
        login();
        var events = new ArrayList<EventLogger.Captured>();
        for (var body : List.of(
                "{\"anthropicApiKey\":\"" + SECRET + "\\nX=1\"}",
                "{\"anthropicApiKey\":\"" + SECRET + "\\r\"}",
                "{\"githubToken\":\"  \"}",
                "{\"anthropicApiKey\":\"" + SECRET + "\",\"claudeOauthToken\":\"" + SECRET + "\"}",
                "{\"githubToken\":\"" + SECRET + "\",\"other\":\"" + SECRET + "\"}",
                "{\"githubToken\":[\"" + SECRET + "\"]}",
                "{}",
                "not json " + SECRET,
                "{\"jiraUrl\":\"file:///" + SECRET + "\",\"githubToken\":\"" + SECRET + "\"}")) {
            var resp = postCredentials(events, body);
            assertStatus(400, resp);
            assertTrue(getContent(resp).contains("invalid_request"), getContent(resp));
            assertFalse(getContent(resp).contains(SECRET), getContent(resp));
            assertArrayEquals(before, Files.readAllBytes(home.resolve(".env")), body);
            assertFalse(Files.exists(home.resolve("jira.env")), body);
            assertFalse(Files.exists(home.resolve("github.env")), body);
        }
        assertEquals(List.of(), messagesContaining(events, SECRET));
    }

    // --- install ---

    @Test
    void installReturns202AtOnceAndThePollShowsTheOutcome() throws Exception {
        var release = new CountDownLatch(1);
        installer = (c, d, t) -> {
            await(release);
            return new FactoryProcess.ExecResult(0, "step 1\ndone\n", false);
        };
        login();
        var job = startInstall();
        assertEquals("running", job.get("state").getAsString(), job.toString());
        var id = job.get("id").getAsString();

        var running = json(GET("/api/factory/setup/install/" + id));
        assertEquals("running", running.get("state").getAsString(), running.toString());
        assertTrue(json(GET("/api/factory/setup")).get("installJobId").getAsString().equals(id));

        release.countDown();
        var done = awaitDone(id);
        assertEquals("succeeded", done.get("state").getAsString(), done.toString());
        assertEquals(0, done.get("exitCode").getAsInt());
        assertTrue(done.get("output").getAsString().contains("step 1"), done.toString());
        assertTrue(done.get("elapsedMillis").getAsLong() >= 0, done.toString());
        assertEquals(List.of(FactoryHome.installer().toString()), calls.stream()
                .filter(c -> c.getFirst().endsWith("install-agent.sh")).findFirst().orElseThrow());
    }

    @Test
    void aNonZeroExitOrAThrowingRunnerIsFailed() throws Exception {
        login();
        installer = (c, d, t) -> new FactoryProcess.ExecResult(2, "boom\n", false);
        var failed = awaitDone(startInstall().get("id").getAsString());
        assertEquals("failed", failed.get("state").getAsString(), failed.toString());
        assertEquals(2, failed.get("exitCode").getAsInt());

        installer = (c, d, t) -> {
            throw new IllegalStateException("runner exploded");
        };
        var threw = awaitDone(startInstall().get("id").getAsString());
        assertEquals("failed", threw.get("state").getAsString(), threw.toString());
        assertTrue(threw.get("exitCode").isJsonNull(), threw.toString());
    }

    @Test
    void aTimedOutOrUnstartableInstallIsFailedWithItsReason() throws Exception {
        login();
        installer = (c, d, t) -> new FactoryProcess.ExecResult(-1, "", true);
        var timedOut = awaitDone(startInstall().get("id").getAsString());
        assertEquals("failed", timedOut.get("state").getAsString(), timedOut.toString());
        assertTrue(timedOut.get("timedOut").getAsBoolean(), timedOut.toString());

        // As execProcess on a spawn failure: the message is in the result and never reaches the sink.
        FactoryProcess.setRunnerForTest(new FactoryProcess.Runner() {
            @Override
            public FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout) {
                return new FactoryProcess.ExecResult(-1, "Cannot run program", false);
            }

            @Override
            public FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout,
                                                 FactoryProcess.OutputSink sink) {
                return run(command, workDir, timeout);
            }
        });
        var unstartable = awaitDone(startInstall().get("id").getAsString());
        assertEquals("failed", unstartable.get("state").getAsString(), unstartable.toString());
        assertFalse(unstartable.get("timedOut").getAsBoolean(), unstartable.toString());
        assertTrue(unstartable.get("output").getAsString().contains("Cannot run program"), unstartable.toString());
    }

    @Test
    void outputOverTheCapKeepsTheTailAndIsTruncated() throws Exception {
        var big = "x".repeat(600 * 1024) + "THE-END\n";
        FactoryProcess.setRunnerForTest((c, d, t) -> new FactoryProcess.ExecResult(0, big, false));
        login();
        var done = awaitDone(startInstall().get("id").getAsString());
        assertEquals("succeeded", done.get("state").getAsString());
        assertTrue(done.get("truncated").getAsBoolean());
        var output = done.get("output").getAsString();
        assertEquals(512 * 1024, output.length());
        assertTrue(output.endsWith("THE-END\n"));
    }

    @Test
    void streamedOutputOverTheCapKeepsTheTailFromACharacterBoundary() throws Exception {
        // 3-byte characters, then a 7-byte tail: the cap's cut lands inside a character.
        var written = ("€".repeat(200_000) + "THE-END").getBytes(StandardCharsets.UTF_8);
        FactoryProcess.setRunnerForTest(new FactoryProcess.Runner() {
            @Override
            public FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout) {
                return new FactoryProcess.ExecResult(0, "", false);
            }

            @Override
            public FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout,
                                                 FactoryProcess.OutputSink sink) {
                for (int off = 0; off < written.length; off += 8192) {
                    sink.write(written, off, Math.min(8192, written.length - off));
                }
                return new FactoryProcess.ExecResult(0, "", false);
            }
        });
        login();
        var done = awaitDone(startInstall().get("id").getAsString());
        assertTrue(done.get("truncated").getAsBoolean(), done.get("truncated")::toString);
        var output = done.get("output").getAsString();
        var bytes = output.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= 512 * 1024, () -> "length " + bytes.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(written, written.length - bytes.length, written.length), bytes);
        assertNotEquals('\uFFFD', output.charAt(0));
        assertTrue(output.endsWith("THE-END"));
    }

    @Test
    void credentialsAnswer500WhenTheFileCannotBeWritten() throws Exception {
        var notADir = home.resolve("plain-file");
        Files.writeString(notADir, "x");
        FactoryHome.setHomeForTest(notADir);
        login();
        var events = new ArrayList<EventLogger.Captured>();
        var resp = postCredentials(events, "{\"githubToken\":\"" + SECRET + "\"}");
        assertStatus(500, resp);
        assertFalse(getContent(resp).contains(SECRET), getContent(resp));
        assertFalse(getContent(resp).contains("Credentials saved"), getContent(resp));
        assertEquals(List.of(), messagesContaining(events, SECRET));
    }

    @Test
    void aFinishedInstallDropsTheCachedProbes() throws Exception {
        login();
        FactoryStatus.current("Mac OS X");
        awaitDone(startInstall().get("id").getAsString());
        long before;
        synchronized (calls) {
            before = calls.stream().filter(c -> String.join(" ", c).startsWith("docker ps")).count();
        }
        FactoryStatus.current("Mac OS X");
        long after;
        synchronized (calls) {
            after = calls.stream().filter(c -> String.join(" ", c).startsWith("docker ps")).count();
        }
        assertEquals(before + 1, after, calls::toString);
    }

    @Test
    void aSecondInstallOrAHarnessCommandWhileOneRunsIs409() throws Exception {
        Files.writeString(home.resolve(".env"), "x");
        Files.writeString(home.resolve("jira.env"), "x");
        var release = new CountDownLatch(1);
        installer = (c, d, t) -> {
            await(release);
            return new FactoryProcess.ExecResult(0, "", false);
        };
        login();
        var id = startInstall().get("id").getAsString();
        try {
            var second = POST("/api/factory/setup/install", "application/json", "{}");
            assertStatus(409, second);
            assertTrue(getContent(second).contains("conflict"), getContent(second));
            assertTrue(Files.isRegularFile(FactoryHome.installer()), FactoryHome.installer()::toString);
            assertStatus(409, POST("/api/factory/harness/start", "application/json", "{}"));
            assertStatus(409, POST("/api/factory/harness/stop", "application/json", "{}"));
        } finally {
            release.countDown();
        }
        awaitDone(id);
        installer = (c, d, t) -> new FactoryProcess.ExecResult(0, "", false);
        startInstall();
    }

    @Test
    void anUnknownJobIs404() {
        login();
        assertStatus(404, GET("/api/factory/setup/install/no-such-job"));
    }

    // --- auth ---

    @Test
    void anUnauthenticatedRequestIs401() {
        assertStatus(401, GET("/api/factory/setup"));
        assertStatus(401, POST("/api/factory/setup/credentials", "application/json", "{\"githubToken\":\"g\"}"));
        assertStatus(401, POST("/api/factory/setup/install", "application/json", "{}"));
        assertStatus(401, GET("/api/factory/setup/install/x"));
        assertFalse(Files.exists(home.resolve("github.env")));
    }

    @Test
    void theAgentPrincipalIsRefusedOnEveryRoute() {
        for (var url : List.of("/api/factory/setup/credentials", "/api/factory/setup/install")) {
            var resp = asAgent(() -> POST(agentRequest(), url, "application/json", "{\"githubToken\":\"g\"}"));
            assertStatus(403, resp);
            assertTrue(getContent(resp).contains("operator_only"), url + ": " + getContent(resp));
        }
        for (var url : List.of("/api/factory/setup", "/api/factory/setup/install/x")) {
            assertStatus(403, asAgent(() -> GET(agentRequest(), url)));
        }
        assertFalse(Files.exists(home.resolve("github.env")));
        synchronized (calls) {
            assertTrue(calls.isEmpty(), calls::toString);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("latch never released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    private static Http.Request agentRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }
}
