import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
import services.RestartService;
import utils.AppClock;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers what a restart WOULD run. The spawn itself is never exercised — this
 * test JVM is the one it would reboot — so {@code spawnerForTest} intercepts
 * the handoff and the assertions land on the composed command.
 */
class RestartServiceTest extends UnitTest {

    private static final Instant HANDED_OFF = Instant.parse("2026-11-12T12:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(2);

    @AfterEach
    void clearSeam() {
        RestartService.spawnerForTest = null;
        RestartService.resetHandOffForTest();
    }

    @Test
    void aHandOffThatForgetsTheStubInTestModeIsRefused() {
        // A subcommand jclaw.sh does not have: were the guard gone, the helper would only print its usage.
        var refused = assertThrows(IllegalStateException.class,
                () -> RestartService.requestMaintenance("jf350-no-such-subcommand", List.of()));

        assertEquals("Refusing to spawn the restart helper in test mode without spawnerForTest", refused.getMessage());
    }

    @Test
    void aSecondHandOffInsideTheHoldIsRefusedAndSpawnsNothing() throws Exception {
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        at(HANDED_OFF, () -> RestartService.requestRestart("the operator"));
        var refused = assertThrows(IllegalStateException.class,
                () -> at(HANDED_OFF.plus(HOLD).minusSeconds(1), () -> RestartService.requestRestart("agent main")));

        assertEquals("A restart is already under way: JClaw is about to stop.", refused.getMessage());
        assertEquals(1, captured.size());
    }

    @Test
    void aHandOffOnceTheHoldHasPassedGoesAhead() throws Exception {
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        at(HANDED_OFF, () -> RestartService.requestRestart("the operator"));
        at(HANDED_OFF.plus(HOLD), () -> RestartService.requestRestart("the operator"));

        assertEquals(2, captured.size(), "a helper that died before stopping JClaw no longer blocks the next restart");
    }

    @Test
    void aRefusedHandOffLeavesNoHoldBehind() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> RestartService.requestMaintenance("jf350-no-such-subcommand", List.of()));
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        RestartService.requestRestart("the operator");

        assertEquals(1, captured.size());
    }

    @Test
    void theRestartsLastLogLineNamesWhoAskedForIt() {
        RestartService.spawnerForTest = plan -> { };

        var events = EventLogger.captureForTest(() -> {
            try {
                RestartService.requestRestart("agent main");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        var restart = events.stream().filter(e -> "restart".equals(e.category())).toList();
        assertEquals(1, restart.size());
        assertEquals("WARN", restart.getFirst().level());
        assertEquals("Restarting at the request of agent main — handing off to jclaw.sh", restart.getFirst().message());
    }

    @Test
    void aProdRestartTellsTheCallerToReadTheProgressOnceJClawIsBack() {
        assertEquals("JClaw stops within a few seconds, and the turn that asked for the restart ends with it. Once "
                        + "JClaw is back, GET /api/system/restart/progress reports how the restart went.",
                RestartService.planFor("/opt/jclaw/jclaw.sh", false, false).restartNote());
    }

    @Test
    void aDevRestartSaysItRecordsNoProgress() {
        assertEquals("JClaw stops within a few seconds, and the turn that asked for the restart ends with it. A "
                        + "dev-mode restart records no progress: JClaw answering again is the sign it is back.",
                RestartService.planFor("/opt/jclaw/jclaw.sh", true, true).restartNote());
    }

    private static <R> R at(Instant instant, ScopedValue.CallableOp<R, Exception> op) throws Exception {
        return AppClock.callWith(Clock.fixed(instant, ZoneOffset.UTC), op);
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
    void maintenanceHandoffsCarryTheSubcommandAndItsArguments() {
        // Restore and repair (JCLAW-1165) ride the same helper: the subcommand and its
        // arguments sit where "restart" would, and dev mode still spares the Nuxt server.
        var prod = RestartService.planFor("/opt/jclaw/jclaw.sh", false, false, "restore", List.of("jclaw-1.zip", "--yes"));
        assertEquals(List.of("/opt/jclaw/jclaw.sh", "restore", "jclaw-1.zip", "--yes"), prod.command());
        assertEquals("PROD", prod.mode());

        var dev = RestartService.planFor("/src/jclaw/jclaw.sh", true, true, "repair", List.of("--yes"));
        assertEquals(List.of("/src/jclaw/jclaw.sh", "--dev", "repair", "--yes", "--backend-only"), dev.command());
        assertTrue(dev.backendOnly());
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

        var returned = RestartService.requestRestart("the operator");

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
