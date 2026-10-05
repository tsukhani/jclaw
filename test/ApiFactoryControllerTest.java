import models.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.EventLogger;
import services.factory.FactoryHome;
import services.factory.FactoryProcess;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Software Factory control routes (JCLAW-1392), driven over HTTP with a fake command runner
 * and a temporary factory home — never real docker, never the real {@code ~/.jclaw-factory}.
 */
class ApiFactoryControllerTest extends FunctionalTest {

    @TempDir
    Path home;

    private final FakeRunner runner = new FakeRunner();

    @BeforeEach
    void setup() throws Exception {
        FactoryRunnerSync.acquire();
        AuthFixture.seedAdminPassword("changeme");
        FactoryHome.homeForTest = home;
        FactoryProcess.runnerForTest = runner;
        clearCookies();
    }

    @AfterEach
    void clearSeams() {
        FactoryHome.homeForTest = null;
        FactoryProcess.runnerForTest = null;
        clearCookies();
        FactoryRunnerSync.release();
    }

    /** Records every command and working directory; replies by the longest matching prefix. */
    static final class FakeRunner implements FactoryProcess.Runner {
        final List<List<String>> calls = new ArrayList<>();
        final List<File> dirs = new ArrayList<>();
        final Map<String, FactoryProcess.ExecResult> replies = new LinkedHashMap<>();

        @Override
        public synchronized FactoryProcess.ExecResult run(List<String> command, File workDir, Duration timeout) {
            calls.add(command);
            dirs.add(workDir);
            var joined = String.join(" ", command);
            return replies.entrySet().stream()
                    .filter(e -> joined.startsWith(e.getKey()))
                    .max((a, b) -> a.getKey().length() - b.getKey().length())
                    .map(Map.Entry::getValue)
                    .orElse(new FactoryProcess.ExecResult(0, "ok\n", false));
        }

        boolean ranPrefix(String prefix) {
            return calls.stream().anyMatch(c -> String.join(" ", c).startsWith(prefix));
        }
    }

    private void login() {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private void install() throws Exception {
        Files.writeString(home.resolve(".env"), "x");
        Files.writeString(home.resolve("jira.env"), "x");
    }

    private static String installer() {
        return new File(Play.applicationPath, ".sandcastle/install-agent.sh").toPath().toString();
    }

    private static String sandboxName() {
        return "sandcastle-" + UUID.randomUUID();
    }

    private static long factoryRows(String level, String messageFragment) {
        EventLogger.flush();
        return EventLog.count("category = ?1 AND level = ?2 AND message LIKE ?3",
                "factory", level, "Operator %" + messageFragment + "%");
    }

    // --- harness ---

    @Test
    void harnessStartRunsTheInstallerInTheCheckout() throws Exception {
        install();
        login();
        runner.replies.put(installer(), new FactoryProcess.ExecResult(0, "loaded com.jclaw.factory\n", false));
        var resp = POST("/api/factory/harness/start", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"exitCode\":0"), body);
        assertTrue(body.contains("\"timedOut\":false"), body);
        assertTrue(body.contains("loaded com.jclaw.factory"), body);
        assertEquals(List.of(List.of(installer())), runner.calls);
        assertEquals(Play.applicationPath, runner.dirs.getFirst());
    }

    @Test
    void harnessStopRunsTheInstallerWithRemove() throws Exception {
        install();
        login();
        assertIsOk(POST("/api/factory/harness/stop", "application/json", "{}"));
        assertEquals(List.of(List.of(installer(), "--remove")), runner.calls);
    }

    @Test
    void harnessRoutesRefuseWhenNotInstalled() throws Exception {
        login();
        for (var present : List.of(".env", "jira.env")) {
            Files.deleteIfExists(home.resolve(".env"));
            Files.deleteIfExists(home.resolve("jira.env"));
            Files.writeString(home.resolve(present), "x");
            for (var url : List.of("/api/factory/harness/start", "/api/factory/harness/stop")) {
                var resp = POST(url, "application/json", "{}");
                assertStatus(409, resp);
                assertTrue(getContent(resp).contains("\"conflict\""), present + ": " + getContent(resp));
            }
        }
        assertTrue(runner.calls.isEmpty(), runner.calls::toString);
    }

    @Test
    void aFailedOrTimedOutCommandIsStillA200() throws Exception {
        install();
        login();
        runner.replies.put(installer() + " --remove", new FactoryProcess.ExecResult(1, "boom\n", false));
        var failed = POST("/api/factory/harness/stop", "application/json", "{}");
        assertIsOk(failed);
        assertTrue(getContent(failed).contains("\"exitCode\":1"), getContent(failed));

        runner.replies.put(installer(), new FactoryProcess.ExecResult(-1, "", true));
        var timedOut = POST("/api/factory/harness/start", "application/json", "{}");
        assertIsOk(timedOut);
        assertTrue(getContent(timedOut).contains("\"exitCode\":-1"), getContent(timedOut));
        assertTrue(getContent(timedOut).contains("\"timedOut\":true"), getContent(timedOut));
    }

    // --- gateway ---

    @Test
    void pauseAndResumeDriveTheGatewayContainer() {
        login();
        var pause = POST("/api/factory/gateway/pause", "application/json", "{}");
        assertIsOk(pause);
        assertTrue(getContent(pause).contains("stories already running will fail"), getContent(pause));
        assertIsOk(POST("/api/factory/gateway/resume", "application/json", "{}"));
        assertEquals(List.of(List.of("docker", "stop", "jclaw-factory-gateway"),
                List.of("docker", "start", "jclaw-factory-gateway")), runner.calls);
    }

    @Test
    void aFailedOrTimedOutPauseSaysSo() {
        login();
        runner.replies.put("docker stop jclaw-factory-gateway", new FactoryProcess.ExecResult(1, "no such container\n", false));
        var failed = getContent(POST("/api/factory/gateway/pause", "application/json", "{}"));
        assertTrue(failed.contains("The command failed with exit code 1."), failed);
        assertFalse(failed.contains("Gateway paused"), failed);

        runner.replies.put("docker stop jclaw-factory-gateway", new FactoryProcess.ExecResult(-1, "", true));
        var timedOut = getContent(POST("/api/factory/gateway/pause", "application/json", "{}"));
        assertTrue(timedOut.contains("The command timed out."), timedOut);
    }

    // --- sandboxes ---

    @Test
    void sandboxStopNamesTheStoryFromTheBoard() throws Exception {
        var name = sandboxName();
        Files.writeString(home.resolve("board.json"),
                "{\"stories\":[{\"key\":\"JCLAW-7\",\"sandbox\":\"" + name + "\"}]}");
        runner.replies.put("docker ps", new FactoryProcess.ExecResult(0, "  " + name + "\n", false));
        login();
        var resp = POST("/api/factory/sandboxes/" + name + "/stop", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"story\":\"JCLAW-7\""), body);
        assertTrue(body.contains("Story JCLAW-7 will fail."), body);
        assertEquals(List.of("docker", "stop", name), runner.calls.getLast());
        assertEquals(1, factoryRows("INFO", name));
    }

    @Test
    void sandboxStopWithNoBoardEntryStillStops() {
        var name = sandboxName();
        runner.replies.put("docker ps", new FactoryProcess.ExecResult(0, name + "\n", false));
        login();
        var resp = POST("/api/factory/sandboxes/" + name + "/stop", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"story\":null"), body);
        assertTrue(body.contains("No story on the board ran in this sandbox."), body);
        assertTrue(runner.ranPrefix("docker stop " + name));
    }

    @Test
    void aFailedSandboxStopSaysSoAndStillNamesTheStory() throws Exception {
        var name = sandboxName();
        Files.writeString(home.resolve("board.json"),
                "{\"stories\":[{\"key\":\"JCLAW-7\",\"sandbox\":\"" + name + "\"}]}");
        runner.replies.put("docker ps", new FactoryProcess.ExecResult(0, name + "\n", false));
        runner.replies.put("docker stop", new FactoryProcess.ExecResult(1, "daemon error\n", false));
        login();
        var resp = POST("/api/factory/sandboxes/" + name + "/stop", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("The command failed with exit code 1."), body);
        assertFalse(body.contains("will fail"), body);
        assertTrue(body.contains("\"story\":\"JCLAW-7\""), body);
    }

    @Test
    void sandboxStopRefusesAnyOtherNameWithoutCallingDocker() {
        login();
        var uuid = UUID.randomUUID().toString();
        for (var name : List.of("sandcastle-abc", "foo", "sandcastle-" + uuid + "x", "jclaw-factory-gateway")) {
            var resp = POST("/api/factory/sandboxes/" + name + "/stop", "application/json", "{}");
            assertStatus(404, resp);
            assertTrue(getContent(resp).contains("\"not_found\""), getContent(resp));
        }
        assertTrue(runner.calls.isEmpty(), runner.calls::toString);
        assertEquals(1, factoryRows("WARN", "sandcastle-" + uuid + "x"));
    }

    @Test
    void sandboxStopRefusesAContainerDockerDoesNotListAsRunning() {
        var name = sandboxName();
        // A prefix match on the filter is not the container itself.
        runner.replies.put("docker ps", new FactoryProcess.ExecResult(0, name + "-other\n", false));
        login();
        assertStatus(404, POST("/api/factory/sandboxes/" + name + "/stop", "application/json", "{}"));
        assertFalse(runner.ranPrefix("docker stop"), runner.calls::toString);
    }

    // --- settings ---

    @Test
    void settingsGetShowsValuesAndDefaults() throws Exception {
        login();
        var missing = getContent(GET("/api/factory/settings"));
        assertTrue(missing.contains("{\"key\":\"FACTORY_CPUS\",\"value\":\"6\",\"defaultValue\":\"6\",\"set\":false}"),
                missing);

        Files.writeString(home.resolve("settings.env"), "FACTORY_CPUS=4\n");
        var resp = GET("/api/factory/settings");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("{\"key\":\"FACTORY_CPUS\",\"value\":\"4\",\"defaultValue\":\"6\",\"set\":true}"), body);
        assertTrue(body.contains("{\"key\":\"FACTORY_MAX_PARALLEL\",\"value\":\"2\",\"defaultValue\":\"2\",\"set\":false}"), body);
        assertTrue(body.contains("{\"key\":\"FACTORY_POLL_SECONDS\",\"value\":\"120\",\"defaultValue\":\"120\",\"set\":false}"), body);
        assertTrue(body.contains("{\"key\":\"FACTORY_MODEL\",\"value\":\"claude-opus-5-5\",\"defaultValue\":\"claude-opus-5-5\",\"set\":false}"), body);
    }

    @Test
    void settingsPutRewritesTheFileKeepingWhatItDoesNotManage() throws Exception {
        var file = home.resolve("settings.env");
        Files.writeString(file, "# mine\nFACTORY_MAX_PARALLEL=2\nOTHER=1\n");
        runner.replies.put("docker info", new FactoryProcess.ExecResult(0, "8\n", false));
        login();
        var resp = PUT("/api/factory/settings", "application/json",
                "{\"FACTORY_MAX_PARALLEL\":\"3\",\"FACTORY_CPUS\":8}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("The harness applies changes once it is idle."), body);
        assertTrue(body.contains("{\"key\":\"FACTORY_MAX_PARALLEL\",\"value\":\"3\",\"defaultValue\":\"2\",\"set\":true}"), body);
        assertEquals("# mine\nFACTORY_MAX_PARALLEL=3\nOTHER=1\nFACTORY_CPUS=8\n", Files.readString(file));
    }

    @Test
    void settingsPutRefusesInvalidBodiesAndLeavesTheFileUntouched() throws Exception {
        var file = home.resolve("settings.env");
        var original = "FACTORY_CPUS=4\n";
        Files.writeString(file, original);
        runner.replies.put("docker info", new FactoryProcess.ExecResult(0, "8\n", false));
        login();
        for (var body : List.of(
                "{\"FACTORY_HOME\":\"/tmp\"}",
                "{\"FACTORY_MAX_PARALLEL\":\"0\"}",
                "{\"FACTORY_MAX_PARALLEL\":-1}",
                "{\"FACTORY_POLL_SECONDS\":1.5}",
                "{\"FACTORY_POLL_SECONDS\":\"abc\"}",
                "{\"FACTORY_POLL_SECONDS\":\" 2\"}",
                "{\"FACTORY_MAX_PARALLEL\":2147483648}",
                "{\"FACTORY_MODEL\":\"  \"}",
                "{\"FACTORY_CPUS\":9}",
                "{\"FACTORY_CPUS\":{}}",
                "[1]",
                "not json",
                "{}")) {
            var resp = PUT("/api/factory/settings", "application/json", body);
            assertStatus(400, resp);
            assertTrue(getContent(resp).contains("\"invalid_request\""), body + " -> " + getContent(resp));
            assertEquals(original, Files.readString(file), body);
        }
        assertTrue(getContent(PUT("/api/factory/settings", "application/json", "{\"FACTORY_CPUS\":9}"))
                .contains("FACTORY_CPUS"));
    }

    @Test
    void settingsCpuCapIsLiftedWhenDockerCannotSay() throws Exception {
        runner.replies.put("docker info", new FactoryProcess.ExecResult(1, "Cannot connect\n", false));
        login();
        assertIsOk(PUT("/api/factory/settings", "application/json", "{\"FACTORY_CPUS\":64}"));
        runner.replies.put("docker info", new FactoryProcess.ExecResult(0, "lots\n", false));
        assertIsOk(PUT("/api/factory/settings", "application/json", "{\"FACTORY_CPUS\":65}"));
        assertEquals("FACTORY_CPUS=65\n", Files.readString(home.resolve("settings.env")));
    }

    @Test
    void settingsPutAnswers500WhenTheFileCannotBeWritten() throws Exception {
        Files.createDirectory(home.resolve("settings.env"));
        login();
        var resp = PUT("/api/factory/settings", "application/json", "{\"FACTORY_MAX_PARALLEL\":3}");
        assertStatus(500, resp);
        assertTrue(getContent(resp).contains("\"internal_error\""), getContent(resp));
        assertFalse(getContent(resp).contains("applies changes once it is idle"), getContent(resp));
    }

    @Test
    void settingsCpuCapReadsTheCountPastDockerWarnings() {
        runner.replies.put("docker info", new FactoryProcess.ExecResult(0, "WARNING: No swap limit support\n8\n", false));
        login();
        assertStatus(400, PUT("/api/factory/settings", "application/json", "{\"FACTORY_CPUS\":9}"));
        assertIsOk(PUT("/api/factory/settings", "application/json", "{\"FACTORY_CPUS\":8}"));
    }

    @Test
    void settingsPutRefusesWhenTheHomeDoesNotExist() {
        var absent = home.resolve("absent");
        FactoryHome.homeForTest = absent;
        login();
        var resp = PUT("/api/factory/settings", "application/json", "{\"FACTORY_MAX_PARALLEL\":3}");
        assertStatus(409, resp);
        assertFalse(Files.exists(absent));
    }

    // --- auth ---

    @Test
    void anUnauthenticatedRequestIs401() {
        assertStatus(401, POST("/api/factory/gateway/pause", "application/json", "{}"));
        assertStatus(401, GET("/api/factory/settings"));
        assertTrue(runner.calls.isEmpty());
    }

    @Test
    void theAgentPrincipalIsRefusedOnEveryRoute() throws Exception {
        install();
        var name = sandboxName();
        runner.replies.put("docker ps", new FactoryProcess.ExecResult(0, name + "\n", false));
        for (var url : List.of("/api/factory/harness/start", "/api/factory/harness/stop",
                "/api/factory/gateway/pause", "/api/factory/gateway/resume",
                "/api/factory/sandboxes/" + name + "/stop")) {
            var resp = asAgent(() -> POST(agentRequest(), url, "application/json", "{}"));
            assertStatus(403, resp);
            assertTrue(getContent(resp).contains("operator_only"), url + ": " + getContent(resp));
        }
        var get = asAgent(() -> GET(agentRequest(), "/api/factory/settings"));
        assertStatus(403, get);
        var put = asAgent(() -> PUT(agentRequest(), "/api/factory/settings", "application/json",
                "{\"FACTORY_MAX_PARALLEL\":3}"));
        assertStatus(403, put);
        assertTrue(runner.calls.isEmpty(), runner.calls::toString);
        assertFalse(Files.exists(home.resolve("settings.env")));
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
