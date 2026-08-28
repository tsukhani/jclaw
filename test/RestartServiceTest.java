import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.RestartService;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers what a restart WOULD run. The spawn itself is never exercised — this
 * test JVM is the one it would reboot — so {@code spawnerForTest} intercepts
 * the handoff and the assertions land on the composed command.
 */
class RestartServiceTest extends UnitTest {

    @AfterEach
    void clearSeam() {
        RestartService.spawnerForTest = null;
    }

    @Test
    void prodRestartBouncesTheWholeInstance() {
        var plan = RestartService.planFor("/opt/jclaw/jclaw.sh", false, false);

        assertEquals(List.of("/opt/jclaw/jclaw.sh", "restart"), plan.command());
        assertEquals("PROD", plan.mode());
        // Prod is one JVM serving both API and SPA, so there is no second
        // process to spare — backendOnly is meaningless here and stays false.
        assertFalse(plan.backendOnly());
        assertFalse(plan.rebuildExpected());
    }

    @Test
    void devRestartSparesTheNuxtDevServer() {
        var plan = RestartService.planFor("/src/jclaw/jclaw.sh", true, true);

        // --backend-only is the whole point in dev: bouncing Nuxt would kill
        // the dev server that served the page issuing the restart, so the
        // browser could never observe the result.
        assertEquals(List.of("/src/jclaw/jclaw.sh", "--dev", "restart", "--backend-only"),
                plan.command());
        assertEquals("DEV", plan.mode());
        assertTrue(plan.backendOnly());
    }

    @Test
    void developerCloneWarnsThatProdRestartRebuilds() {
        // A source tree pays precompile + SPA build on a prod restart (minutes);
        // a dist install boots straight from precompiled/ (seconds). The UI
        // words its warning off this flag, so the two must not be conflated.
        assertTrue(RestartService.planFor("/src/jclaw.sh", false, true).rebuildExpected());
        assertFalse(RestartService.planFor("/opt/jclaw.sh", false, false).rebuildExpected());

        // Dev never rebuilds the SPA — Nuxt is untouched and Play recompiles
        // lazily on the next request.
        assertFalse(RestartService.planFor("/src/jclaw.sh", true, true).rebuildExpected());
    }

    @Test
    void requestRestartHandsOffThePlanWithoutSpawning() throws Exception {
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        var returned = RestartService.requestRestart();

        assertEquals(1, captured.size());
        assertEquals(returned.command(), captured.getFirst().command());
        assertTrue(captured.getFirst().command().getFirst().endsWith("jclaw.sh"));
        assertTrue(captured.getFirst().command().contains("restart"));
    }

    @Test
    void thisCheckoutCanRestartItself() {
        // The repo ships an executable jclaw.sh at the application root, so the
        // preflight must report the button as available. Guards against a
        // packaging change that strips or de-executables the script — which
        // would otherwise only surface as a dead button in production.
        assertNull(RestartService.unavailableReason());
        assertTrue(RestartService.script().isFile());
        assertNotNull(RestartService.plan().command());
    }
}
