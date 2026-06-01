import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TailscaleFunnel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-84: Tailscale Funnel CLI integration. Exercised with an injected
 * {@link TailscaleFunnel.Runner} so nothing shells out to a real tailscale
 * binary (which need not be installed on CI / dev machines).
 */
class TailscaleFunnelTest extends UnitTest {

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
}
