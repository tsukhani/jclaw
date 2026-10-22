import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.RestartService;
import services.UpgradeService;

import java.nio.file.Files;
import java.util.ArrayList;

/**
 * The restart and upgrade endpoints. Every test here installs the matching
 * {@code spawnerForTest} first — without it a POST would hand off for real and
 * reboot (or replace) the tree the suite is running from.
 */
class ApiSystemControllerTest extends FunctionalTest {

    private final ArrayList<RestartService.Plan> spawned = new ArrayList<>();
    private final ArrayList<UpgradeService.Plan> upgrades = new ArrayList<>();

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        spawned.clear();
        upgrades.clear();
        RestartService.spawnerForTest = spawned::add;
        UpgradeService.spawnerForTest = upgrades::add;
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
    void clearSeam() {
        RestartService.spawnerForTest = null;
        UpgradeService.spawnerForTest = null;
        UpgradeService.latestVersionForTest = null;
        UpgradeService.latestNotesForTest = null;
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
}
