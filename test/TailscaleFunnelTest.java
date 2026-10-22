import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TailscaleFunnel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-84: Tailscale Funnel CLI integration. Exercised with an injected
 * {@link TailscaleFunnel.Runner} so nothing shells out to a real tailscale
 * binary (which need not be installed on CI / dev machines).
 */
class TailscaleFunnelTest extends UnitTest {

    // The latches are process-global statics; reset them so a prior method's enable()
    // or stop cannot leak into the next one.
    @BeforeEach
    void clearFunnelLatch() {
        TailscaleFunnel.resetForTest();
    }

    /** Scriptable response for a captured command. */
    interface Responder {
        TailscaleFunnel.ExecResult reply(List<String> command);
    }

    static final class FakeRunner implements TailscaleFunnel.Runner {
        final List<List<String>> calls = new ArrayList<>();
        private final Responder responder;

        FakeRunner(Responder responder) { this.responder = responder; }

        @Override
        public TailscaleFunnel.ExecResult run(List<String> command, Duration timeout) {
            calls.add(command);
            return responder.reply(command);
        }

        boolean ran(String... tokens) {
            return calls.stream().anyMatch(c -> List.of(tokens).stream().allMatch(c::contains));
        }
    }

    private static TailscaleFunnel.ExecResult ok(String stdout) {
        return new TailscaleFunnel.ExecResult(0, stdout, "", false);
    }

    private static TailscaleFunnel.ExecResult failed(String stderr) {
        return new TailscaleFunnel.ExecResult(1, "", stderr, false);
    }

    private static final String RUNNING_STATUS = """
            {"BackendState":"Running","Self":{"DNSName":"jclaw-host.tailnet-abc.ts.net.",\
            "TailscaleIPs":["100.64.0.1"]}}""";

    // ----- pure parsing -----

    @Test
    void publicBaseUrlStripsTrailingDotFromDnsName() {
        assertEquals("https://jclaw-host.tailnet-abc.ts.net",
                TailscaleFunnel.publicBaseUrlFrom(RUNNING_STATUS));
    }

    @Test
    void publicBaseUrlFallsBackToTailscaleIp() {
        assertEquals("https://100.64.0.1",
                TailscaleFunnel.publicBaseUrlFrom("{\"Self\":{\"TailscaleIPs\":[\"100.64.0.1\"]}}"));
    }

    @Test
    void publicBaseUrlNullWhenNoSelf() {
        assertNull(TailscaleFunnel.publicBaseUrlFrom("{\"BackendState\":\"NeedsLogin\"}"));
    }

    @Test
    void parsesNoisyJsonWithSurroundingOutput() {
        var noisy = "Warning: something\n" + RUNNING_STATUS + "\n(trailing noise)";
        assertEquals("https://jclaw-host.tailnet-abc.ts.net",
                TailscaleFunnel.publicBaseUrlFrom(noisy));
        assertEquals("Running", TailscaleFunnel.backendStateFrom(noisy));
    }

    @Test
    void commandBuildersMatchTheCliSyntax() {
        assertEquals(List.of("tailscale", "funnel", "--bg", "--yes", "9000"),
                TailscaleFunnel.enableCmd("tailscale", 9000));
        assertEquals(List.of("tailscale", "funnel", "reset"),
                TailscaleFunnel.resetCmd("tailscale"));
        assertEquals(List.of("tailscale", "status", "--json"),
                TailscaleFunnel.statusCmd("tailscale"));
    }

    // ----- status() with injected runner -----

    @Test
    void statusUnavailableWhenExecFails() {
        var fake = new FakeRunner(cmd -> failed("command not found"));
        var st = TailscaleFunnel.status(fake);
        assertFalse(st.available());
        assertNull(st.publicUrl());
        assertNotNull(st.error());
    }

    @Test
    void statusAvailableWithPublicUrlWhenConnected() {
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            return ok(RUNNING_STATUS);
        });
        var st = TailscaleFunnel.status(fake);
        assertTrue(st.available());
        assertEquals("https://jclaw-host.tailnet-abc.ts.net", st.publicUrl());
        assertNull(st.error());
    }

    @Test
    void statusUnavailableWhenNotConnected() {
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            return ok("{\"BackendState\":\"NeedsLogin\",\"Self\":{}}");
        });
        var st = TailscaleFunnel.status(fake);
        assertFalse(st.available());
        assertNull(st.publicUrl());
        assertTrue(st.error().contains("NeedsLogin"), st.error());
    }

    // ----- enable / disable dispatch -----

    @Test
    void enableRunsFunnelBgAndReportsSuccessWhenServing() {
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            if (cmd.contains("status")) return ok("https://host.tailnet.ts.net (Funnel on)\n|-- / proxy http://127.0.0.1:9000");
            return ok("");  // funnel --bg exits 0
        });
        assertTrue(TailscaleFunnel.enable(9000, fake, 1, 0));
        assertTrue(fake.ran("funnel", "--bg", "9000"));
        assertTrue(fake.ran("funnel", "status"));  // JCLAW-337: verifies the serve state
    }

    @Test
    void enableReportsFailureOnNonZeroExit() {
        var fake = new FakeRunner(cmd ->
                cmd.contains("which") ? ok("/usr/bin/tailscale") : failed("Funnel not available; 'funnel' node attribute not set."));
        assertFalse(TailscaleFunnel.enable(9000, fake, 1, 0));
    }

    @Test
    void enableReportsFailureWhenExit0ButNotServing() {
        // JCLAW-337: funnel --bg exits 0 but funnel status shows nothing served
        // (transient node-attribute sync) -> must report failure, not false success.
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            if (cmd.contains("status")) return ok("No serve config");
            return ok("");  // funnel --bg exits 0
        });
        assertFalse(TailscaleFunnel.enable(9000, fake, 1, 0));
    }

    @Test
    void enableRetriesUntilFunnelIsServing() {
        // JCLAW-337: not serving on the first check, serving on the second -> retry then succeed.
        var statusChecks = new int[]{0};
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            if (cmd.contains("status")) {
                statusChecks[0]++;
                return statusChecks[0] >= 2
                        ? ok("https://host.tailnet.ts.net (Funnel on)")
                        : ok("No serve config");
            }
            return ok("");  // funnel --bg exits 0
        });
        assertTrue(TailscaleFunnel.enable(9000, fake, 3, 0));
        assertEquals(2, statusChecks[0]);
    }

    @Test
    void disableRunsFunnelReset() {
        var fake = new FakeRunner(cmd -> ok(""));
        assertTrue(TailscaleFunnel.disable(fake));
        assertTrue(fake.ran("funnel", "reset"));
    }

    @Test
    void reconcileIsNoOpWhenDisabled() {
        // No tailscale.funnel.enabled config row → treated as off → reconcile must
        // short-circuit (spawning no tailscale subprocess) and report disabled.
        var st = TailscaleFunnel.reconcile();
        assertFalse(st.available());
        assertNotNull(st.error());
    }

    // ---- JCLAW-1143: teardown must not depend on the database ----

    /**
     * disableIfEnabled() used to answer "should I tear down?" by reading
     * tailscale.funnel.enabled out of the config table, which put a DB read on the
     * shutdown path. It now reads a latch set when this JVM actually started a funnel.
     * These cover the latch itself; they do not prove the absence of a config read,
     * which would need a seam on ConfigService.
     */
    @Test
    void funnelLatchStartsClearSoShutdownDoesNothingByDefault() {
        assertFalse(TailscaleFunnel.funnelStartedHere());
        TailscaleFunnel.disableIfEnabled();
        assertFalse(TailscaleFunnel.funnelStartedHere(),
                "a JVM that never enabled a funnel must stay latched off");
    }

    @Test
    void successfulEnableSetsTheLatchAndSuccessfulDisableClearsIt() {
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            if (cmd.contains("status")) return ok("https://host.tailnet.ts.net (Funnel on)\n|-- / proxy http://127.0.0.1:9000");
            return ok("");
        });
        assertTrue(TailscaleFunnel.enable(9000, fake, 1, 0));
        assertTrue(TailscaleFunnel.funnelStartedHere(), "enable must latch on");

        assertTrue(TailscaleFunnel.disable(fake));
        assertFalse(TailscaleFunnel.funnelStartedHere(), "disable must latch off");
    }

    @Test
    void failedEnableLeavesTheLatchClear() {
        var fake = new FakeRunner(cmd ->
                cmd.contains("which") ? ok("/usr/bin/tailscale") : failed("Funnel not available"));
        assertFalse(TailscaleFunnel.enable(9000, fake, 1, 0));
        assertFalse(TailscaleFunnel.funnelStartedHere(),
                "a failed enable must not make shutdown try to tear anything down");
    }

    // ---- JF-16: the boot reconcile runs after the port opens, so a stop can land mid-enable ----

    private static final String SERVING_STATUS = "https://host.tailnet.ts.net (Funnel on)\n|-- / proxy http://127.0.0.1:9000";

    /** The funnel subcommand of every funnel call, in order: {@code --bg}, {@code status}, {@code reset}. */
    private static List<String> funnelVerbs(FakeRunner fake) {
        return fake.calls.stream().filter(c -> c.size() > 2 && c.get(1).equals("funnel")).map(c -> c.get(2)).toList();
    }

    @Test
    void aStopDuringAnEnableWaitsForItAndTakesTheFunnelDown() throws Exception {
        var enableRunning = new CountDownLatch(1);
        var releaseEnable = new CountDownLatch(1);
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            if (cmd.contains("--bg")) {
                enableRunning.countDown();
                try {
                    assertTrue(releaseEnable.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return ok("");
            }
            if (cmd.contains("status")) return ok(SERVING_STATUS);
            return ok("");
        });

        var enabled = new AtomicBoolean();
        var enabler = Thread.ofPlatform().start(() -> enabled.set(TailscaleFunnel.enable(9000, fake, 1, 0)));
        assertTrue(enableRunning.await(5, TimeUnit.SECONDS));
        var stopper = startStopAndAwaitIt(fake);
        releaseEnable.countDown();
        joinBoth(enabler, stopper);

        assertTrue(enabled.get(), "the enable in flight must finish");
        assertEquals(List.of("--bg", "status", "reset"), funnelVerbs(fake));
        assertFalse(TailscaleFunnel.funnelStartedHere());
    }

    @Test
    void aStopBetweenAttemptsEndsTheRetries() throws Exception {
        var firstAttemptRunning = new CountDownLatch(1);
        var failFirstAttempt = new CountDownLatch(1);
        var fake = new FakeRunner(cmd -> {
            if (cmd.contains("which")) return ok("/usr/bin/tailscale");
            if (cmd.contains("--bg")) {
                firstAttemptRunning.countDown();
                try {
                    assertTrue(failFirstAttempt.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return failed("Funnel not available");
            }
            return ok("");
        });

        var enabled = new AtomicBoolean(true);
        var enabler = Thread.ofPlatform().start(() -> enabled.set(TailscaleFunnel.enable(9000, fake, 3, 0)));
        assertTrue(firstAttemptRunning.await(5, TimeUnit.SECONDS));
        var stopper = startStopAndAwaitIt(fake);
        failFirstAttempt.countDown();
        joinBoth(enabler, stopper);

        assertFalse(enabled.get());
        assertEquals(List.of("--bg"), funnelVerbs(fake));
    }

    @Test
    void reconcileDoesNothingOnceAStopHasBegun() {
        var fake = new FakeRunner(cmd -> ok(""));
        TailscaleFunnel.disableIfEnabled(fake);

        assertEquals(new TailscaleFunnel.Status(false, null, "JClaw is stopping"), TailscaleFunnel.reconcile());
    }

    /** Starts a stop on its own thread and returns once it has finished or queued behind the lock. */
    private static Thread startStopAndAwaitIt(FakeRunner fake) throws InterruptedException {
        var stopper = Thread.ofPlatform().start(() -> TailscaleFunnel.disableIfEnabled(fake));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stopper.isAlive() && stopper.getState() != Thread.State.WAITING) {
            assertTrue(System.nanoTime() < deadline, "the stop neither finished nor queued behind the enable");
            Thread.sleep(5);
        }
        return stopper;
    }

    private static void joinBoth(Thread enabler, Thread stopper) throws InterruptedException {
        enabler.join(5000);
        stopper.join(5000);
        assertFalse(enabler.isAlive(), "the enable never returned");
        assertFalse(stopper.isAlive(), "the stop never returned");
    }

    @Test
    void noEnableRunsOnceAStopHasBegun() {
        var fake = new FakeRunner(cmd -> cmd.contains("which") ? ok("/usr/bin/tailscale") : ok(SERVING_STATUS));
        TailscaleFunnel.disableIfEnabled(fake);

        assertFalse(TailscaleFunnel.enable(9000, fake, 1, 0));
        assertEquals(List.of(), fake.calls);
        assertFalse(TailscaleFunnel.funnelStartedHere());
    }
}
