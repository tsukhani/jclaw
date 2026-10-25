import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.InternalApiTokenService;
import services.JavaRuntimeService;
import services.RestartService;
import services.UpgradeService;
import utils.RestartNarrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The restart, upgrade and Java runtime endpoints. Every test here installs the
 * matching {@code spawnerForTest} first — without it a POST would hand off for
 * real and reboot (or replace) the tree the suite is running from. The Java
 * runtime's catalog answer is pinned too, so no GET reaches Azul.
 */
class ApiSystemControllerTest extends FunctionalTest {

    private final ArrayList<RestartService.Plan> spawned = new ArrayList<>();
    private final ArrayList<UpgradeService.Plan> upgrades = new ArrayList<>();
    private final CopyOnWriteArrayList<JavaRuntimeService.Release> stagings = new CopyOnWriteArrayList<>();

    @TempDir Path tmp;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        spawned.clear();
        upgrades.clear();
        RestartService.spawnerForTest = spawned::add;
        UpgradeService.spawnerForTest = upgrades::add;
        stagings.clear();
        JavaRuntimeService.latestForTest = release("25.0.5");
        clearAotFiles();
    }

    private static void clearAotFiles() {
        try {
            Files.deleteIfExists(RestartService.aotDir().resolve("aot-cache.off"));
            Files.deleteIfExists(RestartService.aotDir().resolve("aot-cache.status"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clearRestartStatus() throws IOException {
        Files.deleteIfExists(RestartService.statusFile());
    }

    @AfterEach
    void clearSeam() {
        RestartService.spawnerForTest = null;
        UpgradeService.spawnerForTest = null;
        UpgradeService.latestVersionForTest = null;
        UpgradeService.latestNotesForTest = null;
        JavaRuntimeService.latestForTest = null;
        JavaRuntimeService.environmentForTest = null;
        JavaRuntimeService.stagerForTest = null;
    }

    private void login() {
        var response = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(response);
    }

    // --- Auth gate ---

    @Test
    void preflightRequiresAuth() {
        assertEquals(401, GET("/api/system/restart").status.intValue());
    }

    @Test
    void restartRequiresAuth() {
        assertEquals(401, POST("/api/system/restart", "application/json", "{}").status.intValue());
        // An unauthenticated caller must not even reach the handoff.
        assertEquals(0, spawned.size());
    }

    // --- Preflight ---

    @Test
    void preflightReportsAvailabilityAndInFlightWorkWithoutRestarting() {
        login();
        var response = GET("/api/system/restart");
        assertIsOk(response);

        var body = getContent(response);
        assertTrue(body.contains("\"available\":true"));
        assertTrue(body.contains("\"runningTasks\":0"));
        assertTrue(body.contains("\"activeSubagentRuns\":0"));
        // GET is a preflight; it must never hand off.
        assertEquals(0, spawned.size());
    }

    // --- Restart ---

    @Test
    void restartAcksWith202AndHandsOffExactlyOnce() {
        login();
        var response = POST("/api/system/restart", "application/json", "{}");

        // 202, not 200: the reboot is accepted, not completed. This also pins
        // the bug where the success render sat inside a catch-all — Play's
        // RenderJson is a RuntimeException, so the handler caught its own
        // result and answered 500.
        assertEquals(202, response.status.intValue());
        assertTrue(getContent(response).contains("\"status\":\"ok\""));
        assertEquals(1, spawned.size());
        assertTrue(spawned.getFirst().command().contains("restart"));
    }

    // --- AOT cache switch ---

    @Test
    void theAotCacheSwitchRequiresAuth() {
        assertEquals(401, POST("/api/system/aot-cache", "application/json", "{\"enabled\": false}").status.intValue());
        assertFalse(Files.exists(RestartService.aotDir().resolve("aot-cache.off")));
    }

    @Test
    void theAotCacheSwitchIsOnUntilSwitchedOffAndOnlyEverTouchesTheTestDirectory() {
        // The autotest server shares the checkout's data/, which the live instance's jclaw.sh reads.
        assertEquals("aot-test", RestartService.aotDir().getFileName().toString());
        login();
        assertTrue(getContent(GET("/api/system/restart")).contains("\"aotCacheEnabled\":true"));

        var off = POST("/api/system/aot-cache", "application/json", "{\"enabled\": false}");
        assertIsOk(off);
        assertTrue(getContent(off).contains("\"enabled\":false"));
        assertTrue(Files.exists(RestartService.aotDir().resolve("aot-cache.off")));
        assertTrue(getContent(GET("/api/system/restart")).contains("\"aotCacheEnabled\":false"));

        assertIsOk(POST("/api/system/aot-cache", "application/json", "{\"enabled\": true}"));
        assertFalse(Files.exists(RestartService.aotDir().resolve("aot-cache.off")));
        assertEquals(0, spawned.size());
    }

    @Test
    void theAotCacheSwitchRejectsAnythingButABoolean() {
        login();
        for (var body : new String[] {"{}", "{\"enabled\": \"no\"}", "{\"enabled\": 1}", "{\"enabled\": null}"}) {
            assertEquals(400, POST("/api/system/aot-cache", "application/json", body).status.intValue(), body);
        }
        assertFalse(Files.exists(RestartService.aotDir().resolve("aot-cache.off")));
    }

    @Test
    void preflightReportsWhatTheLastStartDidWithTheCache() throws Exception {
        login();
        assertTrue(getContent(GET("/api/system/restart")).contains("\"aotCacheEnabled\":true"));
        Files.createDirectories(RestartService.aotDir());
        Files.writeString(RestartService.aotDir().resolve("aot-cache.status"), "in use\n");
        var body = getContent(GET("/api/system/restart"));
        assertTrue(body.contains("\"aotCacheStatus\":\"in use\""), body);
        assertTrue(body.contains("\"aotCacheStatusAt\":\""), body);
    }

    // --- Upgrade ---

    @Test
    void upgradeEndpointsRequireAuth() {
        assertEquals(401, GET("/api/system/upgrade").status.intValue());
        assertEquals(401, GET("/api/system/upgrade/status").status.intValue());
        assertEquals(401, POST("/api/system/upgrade", "application/json", "{}").status.intValue());
        assertEquals(0, upgrades.size());
    }

    @Test
    void upgradePreflightReportsThisCheckoutCannotUpgradeItself() {
        login();
        UpgradeService.latestVersionForTest = "99.0.0";
        var response = GET("/api/system/upgrade");
        assertIsOk(response);

        // The suite runs from the repo, so this is the source-checkout branch.
        var body = getContent(response);
        assertTrue(body.contains("\"available\":false"), body);
        assertTrue(body.contains("git pull"), body);
        assertTrue(body.contains("\"currentVersion\""), body);
        assertEquals(0, upgrades.size());
    }

    @Test
    void upgradePreflightStillReportsTheNewestReleaseWhenItCannotAct() {
        login();
        UpgradeService.latestVersionForTest = "99.0.0";
        // An install that cannot upgrade itself still needs to know whether it is
        // behind — without this the panel can only recite the git-pull
        // instruction, including on a checkout that is already current.
        var body = getContent(GET("/api/system/upgrade"));
        assertTrue(body.contains("\"latestVersion\":\"99.0.0\""), body);
        assertTrue(body.contains("\"upgradeAvailable\":true"), body);
    }

    @Test
    void upgradePreflightCarriesTheNewestReleasesNotes() {
        login();
        UpgradeService.latestVersionForTest = "99.0.0";
        UpgradeService.latestNotesForTest = "### Fixes\n\n- **A fix.**";

        var body = getContent(GET("/api/system/upgrade"));
        assertTrue(body.contains("\"releaseNotes\":\"### Fixes\\n\\n- **A fix.**\""), body);
    }

    @Test
    void upgradeIsRefusedWith409OnAnInstallThatCannotUpgrade() {
        login();
        UpgradeService.latestVersionForTest = "99.0.0";

        var response = POST("/api/system/upgrade", "application/json", "{}");

        // 409, not 500: the app is untouched and still serving — this is a
        // refusal, not a half-applied upgrade.
        assertEquals(409, response.status.intValue());
        // The refusal has to happen before the handoff; a helper launched
        // against an unsupported install would already have stopped the app by
        // the time it worked that out.
        assertEquals(0, upgrades.size());
    }

    @Test
    void upgradeStatusIs204BeforeAnyUpgradeHasRun() {
        login();
        // The repo has no logs/upgrade-status.json, so the panel must be able to
        // tell "never upgraded" from "upgrade reported nothing".
        assertEquals(204, GET("/api/system/upgrade/status").status.intValue());
    }

    // --- Restart progress ---

    @Test
    void theNarratorAcceptsTheSessionJClawsLoginSetsAndRefusesTheOneABearerGets() {
        clearCookies();
        var login = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(login);
        var secret = Play.secretKey.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().toEpochMilli();
        var operator = login.cookies.get("PLAY_SESSION").value;
        assertTrue(RestartNarrator.operatorSession(List.of("PLAY_SESSION=" + operator), secret, now));

        clearCookies();
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        var agent = GET(request, "/api/system/restart");
        assertIsOk(agent);
        try {
            var cookie = agent.cookies.get("PLAY_SESSION");
            assertNotNull(cookie, "a bearer request is given the agent principal's session");
            assertFalse(RestartNarrator.operatorSession(List.of("PLAY_SESSION=" + cookie.value), secret, now));
        } finally {
            clearCookies();
        }
    }

    @Test
    void theNarratorReadsTheSessionKeysJClawWrites() throws Exception {
        assertEquals(constant("controllers.ApiAuthController", "SESSION_ID"), RestartNarrator.SESSION_ID_KEY);
        assertEquals(constant("controllers.RequestPrincipal", "PRINCIPAL_KEY"), RestartNarrator.PRINCIPAL_KEY);
        assertEquals(constant("controllers.RequestPrincipal", "AGENT"), RestartNarrator.AGENT_PRINCIPAL);
        assertEquals(InternalApiTokenService.SYSTEM_OWNER, RestartNarrator.SYSTEM_OWNER);
        assertEquals(constant("play.mvc.Scope$Session", "TS_KEY"), RestartNarrator.EXPIRY_KEY);
    }

    @Test
    void restartProgressRequiresTheOperator() {
        assertEquals(401, GET("/api/system/restart/progress").status.intValue());
    }

    @Test
    void restartProgressReportsTheRestartThatBroughtThisJvmUpAndWhatItStillConnects() throws IOException {
        long started = Play.startedAt / 1000;
        writeRestartStatus("""
                {"startedAt":%d,"state":"done","reason":"","finishedAt":%d,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":%d},
                 {"id":"java","label":"Installing Java 25.0.5","start":%d,"end":%d},
                 {"id":"start","label":"Starting JClaw (connecting channels)","start":%d,"end":%d}]}
                """.formatted(started - 30, started + 2, started - 30, started - 25, started - 25, started - 18, started - 18, started + 2));
        login();
        var body = json(GET("/api/system/restart/progress"));

        assertEquals("jclaw", body.get("source").getAsString());
        assertEquals("done", body.get("state").getAsString());
        assertTrue(body.get("failure").isJsonNull());
        assertEquals(JsonParser.parseString("""
                [{"id":"stop","label":"Stopping JClaw","state":"done","seconds":5},
                 {"id":"java","label":"Installing Java 25.0.5","state":"done","seconds":7},
                 {"id":"start","label":"Starting JClaw (connecting channels)","state":"done","seconds":20}]"""), body.get("steps"));
        // Other classes share the database, so its counts are theirs too; the shape is this route's.
        var services = body.getAsJsonObject("services");
        assertEquals(Set.of("channels", "mcpConnected", "mcpConfigured", "settled"), services.keySet());
        assertTrue(services.get("channels").isJsonArray());
        assertTrue(services.get("mcpConnected").getAsInt() >= 0 && services.get("mcpConfigured").getAsInt() >= 0);
        assertTrue(services.get("settled").getAsJsonPrimitive().isBoolean());
    }

    @Test
    void aRestartUnderWayIsReportedWithThePhaseItIsIn() throws IOException {
        long now = Instant.now().getEpochSecond();
        writeRestartStatus("""
                {"startedAt":%d,"state":"running","reason":"","finishedAt":0,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":0}]}""".formatted(now - 3, now - 3));
        login();
        var body = json(GET("/api/system/restart/progress"));

        assertEquals("running", body.get("state").getAsString());
        var phase = body.getAsJsonArray("steps").get(0).getAsJsonObject();
        assertEquals("Stopping JClaw", phase.get("label").getAsString());
        assertEquals("running", phase.get("state").getAsString());
        long seconds = phase.get("seconds").getAsLong();
        assertTrue(seconds >= 3 && seconds < 60, () -> "seconds=" + seconds);
    }

    @Test
    void aRestartThatEndedBeforeThisJvmStartedIsAnEarlierOne() throws IOException {
        long started = Play.startedAt / 1000;
        writeRestartStatus("""
                {"startedAt":%d,"state":"failed","reason":"JClaw did not start","finishedAt":%d,"phases":[
                 {"id":"stop","label":"Stopping JClaw","start":%d,"end":0}]}""".formatted(started - 600, started - 590, started - 600));
        login();
        var body = json(GET("/api/system/restart/progress"));

        assertEquals("none", body.get("state").getAsString());
        assertTrue(body.get("failure").isJsonNull());
        assertEquals(0, body.getAsJsonArray("steps").size());
    }

    @Test
    void aFailureAfterThisJvmStartedCarriesItsReason() throws IOException {
        long started = Play.startedAt / 1000;
        writeRestartStatus("""
                {"startedAt":%d,"state":"failed","reason":"JClaw did not answer within 120 seconds","finishedAt":%d,"phases":[
                 {"id":"start","label":"Starting JClaw (connecting channels)","start":%d,"end":0}]}""".formatted(started - 10, started + 1, started - 10));
        login();
        var body = json(GET("/api/system/restart/progress"));

        assertEquals("failed", body.get("state").getAsString());
        assertEquals("JClaw did not answer within 120 seconds", body.get("failure").getAsString());
        assertEquals(JsonParser.parseString("[{\"id\":\"start\",\"label\":\"Starting JClaw (connecting channels)\",\"state\":\"failed\",\"seconds\":11}]"),
                body.get("steps"));
    }

    @Test
    void noStatusFileOrATornOneReadsAsNoRestart() throws IOException {
        // The autotest server shares the checkout's logs/, where the live instance's jclaw.sh writes.
        assertEquals(Path.of("logs", "restart-test", "restart-status.json"),
                Play.applicationPath.toPath().relativize(RestartService.statusFile()));
        login();
        assertEquals("none", json(GET("/api/system/restart/progress")).get("state").getAsString());
        writeRestartStatus("{\"startedAt\":1,\"state\":\"done\",\"phases\":[{\"id\":");
        assertEquals("none", json(GET("/api/system/restart/progress")).get("state").getAsString());
    }

    // --- Java runtime ---

    @Test
    void javaRuntimeEndpointsRequireAuth() {
        assertEquals(401, GET("/api/system/java-runtime").status.intValue());
        assertEquals(401, POST("/api/system/java-runtime", "application/json", "{}").status.intValue());
        assertEquals(401, DELETE("/api/system/java-runtime").status.intValue());
        assertEquals(0, stagings.size());
    }

    @Test
    void thisCheckoutsJavaIsExternalSoANewerPatchIsOnlyReported() {
        login();
        var current = System.getProperty("java.version");
        // This JVM and this checkout, but never a container: /.dockerenv in a dev container would make it one.
        JavaRuntimeService.environmentForTest = new JavaRuntimeService.Environment(Play.applicationPath.toPath(),
                Path.of(System.getProperty("java.home")), current, System.getProperty("java.vendor"),
                System.getProperty("os.name"), System.getProperty("os.arch"), false);
        JavaRuntimeService.latestForTest = release(current + ".1");
        JavaRuntimeService.stagerForTest = (r, home, p) -> stagings.add(r);

        var body = json(GET("/api/system/java-runtime"));
        assertEquals("external", body.get("kind").getAsString());
        assertEquals(current, body.get("currentVersion").getAsString());
        assertEquals(current + ".1", body.get("latestVersion").getAsString());
        assertTrue(body.get("updateAvailable").getAsBoolean());

        var refused = POST("/api/system/java-runtime", "application/json", "{}");
        assertEquals(409, refused.status.intValue());
        assertEquals("JClaw runs on a Java it did not install (" + System.getProperty("java.home")
                + "), so that Java's vendor provides its updates.", json(refused).get("message").getAsString());
        assertEquals(0, stagings.size());
    }

    @Test
    void theBuiltInJavaDownloadsTheNewerPatchThenWaitsForTheNextRestart() throws Exception {
        login();
        JavaRuntimeService.environmentForTest = builtIn("25.0.4", false);
        JavaRuntimeService.stagerForTest = (r, home, p) -> {
            stagings.add(r);
            writeStaging(home, r.version());
        };

        var before = json(GET("/api/system/java-runtime"));
        assertEquals("built-in", before.get("kind").getAsString());
        assertEquals("25.0.4", before.get("currentVersion").getAsString());
        assertEquals("Azul Systems, Inc.", before.get("vendor").getAsString());
        assertEquals("25.0.5", before.get("latestVersion").getAsString());
        assertTrue(before.get("updateAvailable").getAsBoolean());
        assertTrue(before.get("stagedVersion").isJsonNull());
        assertTrue(before.get("rebuildsStartupCache").getAsBoolean());

        var accepted = POST("/api/system/java-runtime", "application/json", "{}");
        assertEquals(202, accepted.status.intValue());
        assertEquals("{\"status\":\"ok\",\"version\":\"25.0.5\"}", getContent(accepted));
        waitUntil(() -> !JavaRuntimeService.CURRENT.active());
        assertEquals(1, stagings.size());

        var ready = json(GET("/api/system/java-runtime"));
        assertEquals("25.0.5", ready.get("stagedVersion").getAsString());
        assertFalse(ready.get("downloading").getAsBoolean());
        assertTrue(ready.get("downloadError").isJsonNull());

        var again = POST("/api/system/java-runtime", "application/json", "{}");
        assertEquals(409, again.status.intValue());
        assertEquals("Java 25.0.5 will be installed at the next restart.", json(again).get("message").getAsString());
        assertEquals(1, stagings.size());

        assertIsOk(DELETE("/api/system/java-runtime"));
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertTrue(json(GET("/api/system/java-runtime")).get("stagedVersion").isJsonNull());
        assertEquals(404, DELETE("/api/system/java-runtime").status.intValue());
    }

    @Test
    void aDownloadInFlightCannotBeCancelledOrStartedTwice() throws Exception {
        login();
        JavaRuntimeService.environmentForTest = builtIn("25.0.4", false);
        var release = new CountDownLatch(1);
        JavaRuntimeService.stagerForTest = (r, home, p) -> {
            stagings.add(r);
            p.percent(40);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertEquals(202, POST("/api/system/java-runtime", "application/json", "{}").status.intValue());
            waitUntil(() -> Integer.valueOf(40).equals(JavaRuntimeService.CURRENT.currentPercent()));
            var running = json(GET("/api/system/java-runtime"));
            assertTrue(running.get("downloading").getAsBoolean());
            assertEquals(40, running.get("downloadPercent").getAsInt());

            var cancel = DELETE("/api/system/java-runtime");
            assertEquals(409, cancel.status.intValue());
            assertEquals("A newer Java is still downloading; cancel it once the download has finished.",
                    json(cancel).get("message").getAsString());
            var twice = POST("/api/system/java-runtime", "application/json", "{}");
            assertEquals(409, twice.status.intValue());
            assertEquals("A newer Java is already downloading.", json(twice).get("message").getAsString());
        } finally {
            release.countDown();
            waitUntil(() -> !JavaRuntimeService.CURRENT.active());
        }
        assertEquals(1, stagings.size());
        var ended = json(GET("/api/system/java-runtime"));
        assertFalse(ended.get("downloading").getAsBoolean());
        assertTrue(ended.get("downloadPercent").isJsonNull());
    }

    @Test
    void aContainerOrAnUpToDateJavaIsOfferedNoDownload() throws IOException {
        login();
        JavaRuntimeService.stagerForTest = (r, home, p) -> stagings.add(r);

        JavaRuntimeService.environmentForTest = builtIn("25.0.4", true);
        assertEquals("container", json(GET("/api/system/java-runtime")).get("kind").getAsString());
        var container = POST("/api/system/java-runtime", "application/json", "{}");
        assertEquals(409, container.status.intValue());
        assertEquals("The runtime comes with the Docker image; pull a newer image to update Java.",
                json(container).get("message").getAsString());

        JavaRuntimeService.environmentForTest = builtIn("25.0.5", false);
        assertFalse(json(GET("/api/system/java-runtime")).get("updateAvailable").getAsBoolean());
        var current = POST("/api/system/java-runtime", "application/json", "{}");
        assertEquals(409, current.status.intValue());
        assertEquals("Java 25.0.5 is up to date.", json(current).get("message").getAsString());
        assertEquals(0, stagings.size());
    }

    @Test
    void theJavaRuntimeReportsWhatTheLastStartDidAndOmitsTheCacheNoteOnWindows() throws Exception {
        login();
        var windows = new JavaRuntimeService.Environment(tmp.resolve("jclaw"), jreHome(), "25.0.4",
                "Azul Systems, Inc.", "Windows 11", "amd64", false);
        JavaRuntimeService.environmentForTest = windows;
        Files.createDirectories(tmp.resolve("jclaw/data"));
        Files.writeString(tmp.resolve("jclaw/data/java-runtime.status"), "kept Java 25.0.4: Java 25.0.5 failed its SHA-256 check\n");

        var body = json(GET("/api/system/java-runtime"));
        assertEquals("kept Java 25.0.4: Java 25.0.5 failed its SHA-256 check", body.get("lastStart").getAsString());
        assertFalse(body.get("lastStartAt").isJsonNull());
        assertFalse(body.get("rebuildsStartupCache").getAsBoolean());
    }

    @Test
    void theStartupCacheNoteGoesWhenTheCacheIsSwitchedOff() throws Exception {
        login();
        JavaRuntimeService.environmentForTest = builtIn("25.0.4", false);
        assertTrue(json(GET("/api/system/java-runtime")).get("rebuildsStartupCache").getAsBoolean());

        assertIsOk(POST("/api/system/aot-cache", "application/json", "{\"enabled\": false}"));

        assertFalse(json(GET("/api/system/java-runtime")).get("rebuildsStartupCache").getAsBoolean());
    }

    @Test
    void inTestModeNothingIsDownloadedWithoutAStager() throws Exception {
        login();
        JavaRuntimeService.environmentForTest = builtIn("25.0.4", false);

        assertEquals(202, POST("/api/system/java-runtime", "application/json", "{}").status.intValue());
        waitUntil(() -> !JavaRuntimeService.CURRENT.active());

        assertEquals("Java 25.0.5 could not be downloaded: in test mode a Java runtime downloads only through stagerForTest",
                json(GET("/api/system/java-runtime")).get("downloadError").getAsString());
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
    }

    private static JavaRuntimeService.Release release(String version) {
        return new JavaRuntimeService.Release(version, "https://cdn.azul.com/zulu/bin/zulu-jre" + version + ".tar.gz",
                "c3".repeat(32), "zulu-jre" + version + ".tar.gz");
    }

    /** {@code <tmp>/jclaw} running on {@code <tmp>/jre}: the installer's layout. */
    private JavaRuntimeService.Environment builtIn(String version, boolean container) throws IOException {
        Files.createDirectories(tmp.resolve("jclaw/conf"));
        return new JavaRuntimeService.Environment(tmp.resolve("jclaw"), jreHome(), version, "Azul Systems, Inc.",
                "Mac OS X", "aarch64", container);
    }

    private Path jreHome() throws IOException {
        return Files.createDirectories(tmp.resolve("jre/zulu25/Contents/Home"));
    }

    private static void writeStaging(Path home, String version) throws IOException {
        var dir = Files.createDirectories(home.resolve("jre.staged"));
        Files.writeString(dir.resolve("zulu-jre" + version + ".tar.gz"), "bytes");
        Files.writeString(dir.resolve("manifest"), "version=" + version + "\nsha256=" + "c3".repeat(32)
                + "\narchive=zulu-jre" + version + ".tar.gz\n");
    }

    private static Object constant(String type, String field) throws ReflectiveOperationException {
        var f = Class.forName(type, true, Play.classloader).getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void writeRestartStatus(String json) throws IOException {
        Files.createDirectories(RestartService.statusFile().getParent());
        Files.writeString(RestartService.statusFile(), json);
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }
}
