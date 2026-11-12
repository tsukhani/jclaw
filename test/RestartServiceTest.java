import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
import services.RestartService;
import utils.AppClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers what a restart WOULD run. The spawn itself is never exercised — this
 * test JVM is the one it would reboot — so {@code spawnerForTest} intercepts
 * the handoff and the assertions land on the composed command.
 */
class RestartServiceTest extends UnitTest {

    private static final Instant HANDED_OFF = Instant.parse("2026-11-12T12:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(2);

    @AfterEach
    void clearSeam() throws IOException {
        RestartService.spawnerForTest = null;
        RestartService.resetHandOffForTest();
        deleteRecursively(RestartService.requestFile());
    }

    @Test
    void aFailedLaunchLeavesNoRecord() {
        RestartService.spawnerForTest = plan -> {
            throw new IllegalStateException("the helper did not start");
        };

        assertThrows(IllegalStateException.class, () -> RestartService.requestRestart("agent main", 42L));

        assertNull(taken(), "JClaw stays up, so no later boot tells the conversation about a restart that never ran");
    }

    @Test
    void aRestartRefusedInsideAnotherAskersHoldKeepsThatAskersRecord() throws Exception {
        RestartService.spawnerForTest = plan -> { };
        RestartService.requestRestart("agent main", 7L);

        assertThrows(IllegalStateException.class, () -> RestartService.requestRestart("agent main", 42L));

        var kept = taken();
        assertEquals(7L, kept == null ? null : kept.conversationId());
    }

    @Test
    void aRecordThatCannotBeWrittenDoesNotStopTheRestart() throws Exception {
        // A directory where the record goes, with a file in it, so neither writing nor clearing it can succeed.
        Files.createDirectories(RestartService.requestFile());
        Files.writeString(RestartService.requestFile().resolve("blocker"), "x");
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        var events = EventLogger.captureForTest(() -> {
            try {
                RestartService.requestRestart("agent main", 42L);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(1, captured.size(), "the restart goes ahead");
        assertEquals(List.of("WARN The record of who asked for the restart could not be written, so nobody will be "
                        + "told JClaw is back", "WARN Restarting at the request of agent main — handing off to jclaw.sh"),
                events.stream().filter(e -> "restart".equals(e.category()) && "WARN".equals(e.level()))
                        .map(e -> e.level() + " " + e.message()).toList());
    }

    @Test
    void aRecordMissingAFieldIsDroppedAndLogged() throws Exception {
        Files.createDirectories(RestartService.requestFile().getParent());
        Files.writeString(RestartService.requestFile(), "{\"conversationId\":42}");
        var taken = new AtomicReference<RestartService.RestartRequest>();

        var events = EventLogger.captureForTest(() -> taken.set(taken()));

        assertNull(taken.get());
        assertFalse(Files.exists(RestartService.requestFile()));
        assertEquals(List.of("WARN The record of who asked for the restart could not be read, so nobody is told "
                        + "JClaw is back"),
                events.stream().filter(e -> "restart".equals(e.category())).map(e -> e.level() + " " + e.message()).toList());
    }

    @Test
    void aRecordFromThisRunsOwnStartIsLeftForTheBootItBelongsTo() throws Exception {
        RestartService.spawnerForTest = plan -> { };
        at(HANDED_OFF, () -> RestartService.requestRestart("agent main", 42L));

        assertNull(RestartService.takeRequestBefore(HANDED_OFF), "a dev-mode reload of this run must not consume it");
        assertTrue(Files.exists(RestartService.requestFile()));
        assertEquals(new RestartService.RestartRequest(42L, HANDED_OFF),
                RestartService.takeRequestBefore(HANDED_OFF.plusMillis(1)));
    }

    @Test
    void aRestartAskedForFromAConversationIsRecordedOnceForTheNextBoot() throws Exception {
        RestartService.spawnerForTest = plan -> { };

        at(HANDED_OFF, () -> RestartService.requestRestart("agent main", 42L));

        assertEquals(new RestartService.RestartRequest(42L, HANDED_OFF), taken());
        assertNull(taken(), "the record is deleted as it is read, so a second boot tells nobody");
    }

    @Test
    void aHandOffNoConversationAskedForClearsAnEarlierRecord() throws Exception {
        RestartService.spawnerForTest = plan -> { };
        RestartService.requestRestart("agent main", 42L);
        RestartService.resetHandOffForTest();

        RestartService.requestMaintenance("repair", List.of("--yes"));

        assertNull(taken());
    }

    @Test
    void aRefusedHandOffRecordsNothing() throws Exception {
        RestartService.spawnerForTest = plan -> { };
        RestartService.requestRestart("the operator", null);

        assertThrows(IllegalStateException.class, () -> RestartService.requestRestart("agent main", 42L));

        assertNull(taken(), "the restart under way was the operator's");
    }

    @Test
    void anUnreadableRecordIsDroppedAndLogged() throws Exception {
        Files.createDirectories(RestartService.requestFile().getParent());
        Files.writeString(RestartService.requestFile(), "not json");
        var taken = new AtomicReference<RestartService.RestartRequest>();

        var events = EventLogger.captureForTest(() -> taken.set(taken()));

        assertNull(taken.get());
        assertFalse(Files.exists(RestartService.requestFile()));
        assertEquals(List.of("WARN The record of who asked for the restart could not be read, so nobody is told "
                        + "JClaw is back"),
                events.stream().filter(e -> "restart".equals(e.category())).map(e -> e.level() + " " + e.message()).toList());
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

        at(HANDED_OFF, () -> RestartService.requestRestart("the operator", null));
        var refused = assertThrows(IllegalStateException.class,
                () -> at(HANDED_OFF.plus(HOLD).minusSeconds(1), () -> RestartService.requestRestart("agent main", null)));

        assertEquals("A restart is already under way: JClaw is about to stop.", refused.getMessage());
        assertEquals(1, captured.size());
    }

    @Test
    void aHandOffOnceTheHoldHasPassedGoesAhead() throws Exception {
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        at(HANDED_OFF, () -> RestartService.requestRestart("the operator", null));
        at(HANDED_OFF.plus(HOLD), () -> RestartService.requestRestart("the operator", null));

        assertEquals(2, captured.size(), "a helper that died before stopping JClaw no longer blocks the next restart");
    }

    @Test
    void aRefusedHandOffLeavesNoHoldBehind() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> RestartService.requestMaintenance("jf350-no-such-subcommand", List.of()));
        var captured = new ArrayList<RestartService.Plan>();
        RestartService.spawnerForTest = captured::add;

        RestartService.requestRestart("the operator", null);

        assertEquals(1, captured.size());
    }

    @Test
    void theRestartsLastLogLineNamesWhoAskedForIt() {
        RestartService.spawnerForTest = plan -> { };

        var events = EventLogger.captureForTest(() -> {
            try {
                RestartService.requestRestart("agent main", null);
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
                        + "JClaw is back, the conversation that asked gets \"JClaw is back after the restart you asked "
                        + "for.\", and GET /api/system/restart/progress reports how the restart went.",
                RestartService.planFor("/opt/jclaw/jclaw.sh", false, false).restartNote());
    }

    @Test
    void aDevRestartSaysItRecordsNoProgress() {
        assertEquals("JClaw stops within a few seconds, and the turn that asked for the restart ends with it. Once "
                        + "JClaw is back, the conversation that asked gets \"JClaw is back after the restart you asked "
                        + "for.\" A dev-mode restart records no progress.",
                RestartService.planFor("/opt/jclaw/jclaw.sh", true, true).restartNote());
    }

    /** The record as any later boot would take it. */
    private static RestartService.@Nullable RestartRequest taken() {
        return RestartService.takeRequestBefore(Instant.MAX);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
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

        var returned = RestartService.requestRestart("the operator", null);

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
