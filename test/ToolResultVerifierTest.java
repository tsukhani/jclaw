import agents.ToolRegistry;
import agents.ToolResultVerifier;
import agents.ToolResultVerifier.Verdict;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.ShellExecTool;
import utils.LatencyStats;
import utils.LatencyTrace;

/**
 * JCLAW-836 stages 1 and 1.5: the deterministic tool-result checks, and the
 * per-tool post-conditions layered on top of them.
 *
 * <p>Exercises {@code check} rather than {@code verify} for the verdict cases, so
 * nothing here writes {@code verification.enabled} or {@code verification.skipTools} —
 * play1 runs test classes concurrently and a flipped process-global setting would
 * silently change tool behavior under whatever else is mid-run.
 */
class ToolResultVerifierTest extends UnitTest {

    /** No tool answers to this, so check() exercises the generic path only —
     *  no dependency on what ToolRegistrationJob happened to publish. */
    private static final String UNREGISTERED = "__no_such_tool__";

    @Test
    void ordinaryResultPasses() {
        var v = ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text("Singapore is UTC+8."));
        assertEquals(Verdict.OK, v.verdict());
        assertFalse(v.failed());
    }

    @Test
    void blankResultIsEmpty() {
        assertEquals(Verdict.EMPTY, ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text("")).verdict());
        assertEquals(Verdict.EMPTY, ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text("   \n ")).verdict());
        assertEquals(Verdict.EMPTY, ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text(null)).verdict());
    }

    @Test
    void codebaseErrorConventionIsFlagged() {
        // The two shapes ToolRegistry itself emits, plus a tool following the same
        // convention (web_fetch on a 404 — observed on a live eval turn, JCLAW-891).
        for (var text : new String[]{
                "Error: Unknown tool 'nope'",
                "Error executing tool: connection reset",
                "Error fetching URL: HTTP 404"}) {
            var v = ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text(text));
            assertEquals(Verdict.ERROR_REPORTED, v.verdict(), text);
            assertTrue(v.failed(), text);
        }
    }

    /**
     * The heuristic's whole value depends on this: a page or search result that
     * merely mentions an error is not a failed tool call. A substring match would
     * flag most of the web and make the metric unreadable within a day.
     */
    @Test
    void proseMentioningErrorIsNotFlagged() {
        for (var text : new String[]{
                "The report describes an error in the 1997 census.",
                "Errors and Omissions Insurance — a primer",
                "Erroneous data was corrected in v2."}) {
            assertEquals(Verdict.OK, ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text(text)).verdict(), text);
        }
    }

    @Test
    void unparseableStructuredJsonIsFlagged() {
        var bad = new ToolRegistry.ToolResult("3 results found", "{not json");
        assertEquals(Verdict.MALFORMED_JSON, ToolResultVerifier.check(UNREGISTERED, bad).verdict());

        var good = new ToolRegistry.ToolResult("3 results found", "{\"results\":[]}");
        assertEquals(Verdict.OK, ToolResultVerifier.check(UNREGISTERED, good).verdict());
    }

    /**
     * A call the registry refused never reached a tool, so there is no output to
     * judge. Counting it here would double-count the offered-set defect JCLAW-883
     * already measures separately.
     */
    @Test
    void nonDispatchedResultIsSkipped() {
        var refused = ToolRegistry.ToolResult.refused(
                "Error: tool 'web_search' is not enabled", ToolRegistry.ToolResult.Outcome.NOT_ENABLED);
        var v = ToolResultVerifier.verify("web_search", refused);
        assertEquals(Verdict.SKIPPED, v.verdict());
        assertFalse(v.failed(), "SKIPPED is an absence of a verdict, not a failure");

        assertEquals(Verdict.SKIPPED, ToolResultVerifier.verify("web_search", null).verdict());
    }

    @Test
    void skipListParsesLenientlyAndCaseInsensitively() {
        assertEquals(java.util.Set.of(), ToolResultVerifier.parseSkipTools(null));
        assertEquals(java.util.Set.of(), ToolResultVerifier.parseSkipTools("  "));
        assertEquals(java.util.Set.of("web_fetch", "shell_exec"),
                ToolResultVerifier.parseSkipTools(" Web_Fetch , ,shell_exec "));
    }

    /** Verification is on by default, so an ordinary result goes through the checks. */
    @Test
    void verifyAppliesChecksUnderDefaultConfig() {
        assertEquals(Verdict.ERROR_REPORTED,
                ToolResultVerifier.verify("datetime", ToolRegistry.ToolResult.text("Error: no such zone")).verdict());
    }

    // ---- Stage 1.5: per-tool post-conditions ----

    private static String execEnvelope(int exitCode, boolean timedOut) {
        return "{\"exitCode\":%d,\"output\":\"...\",\"durationMs\":12,\"truncated\":false,\"timedOut\":%s}"
                .formatted(exitCode, timedOut);
    }

    @Test
    void execSuccessHasNoPostConditionFailure() {
        assertTrue(new ShellExecTool()
                .postConditionFailure(ToolRegistry.ToolResult.text(execEnvelope(0, false))).isEmpty());
    }

    @Test
    void execNonZeroExitIsAFailure() {
        var reason = new ShellExecTool()
                .postConditionFailure(ToolRegistry.ToolResult.text(execEnvelope(1, false)));
        assertEquals("command exited 1", reason.orElse(null));
    }

    @Test
    void execTimeoutIsAFailure() {
        // executeCommand pairs timedOut with exitCode -1, so timedOut must be
        // checked before the exit code or this lands in the early-return case below.
        var reason = new ShellExecTool()
                .postConditionFailure(ToolRegistry.ToolResult.text(execEnvelope(-1, true)));
        assertEquals("command timed out", reason.orElse(null));
    }

    /**
     * The trap this check was written around. {@code buildTerminalImageEarlyReturn}
     * reports exitCode -1 on a SUCCESS path — a terminal image was detected and the
     * process is deliberately still running so the user can interact with it. A bare
     * {@code exitCode != 0} would score every one of those a failure and poison the
     * very metric this story exists to produce.
     */
    @Test
    void execTerminalImageEarlyReturnIsNotAFailure() {
        assertTrue(new ShellExecTool()
                .postConditionFailure(ToolRegistry.ToolResult.text(execEnvelope(-1, false))).isEmpty(),
                "exitCode -1 with timedOut=false is a process left running for the user, not a failure");
    }

    @Test
    void execPostConditionIgnoresNonEnvelopeText() {
        var tool = new ShellExecTool();
        // Prose early-returns (allowlist refusal, bad workdir) never reach here in
        // production — the generic Error check rules first — but the parse must not throw.
        assertTrue(tool.postConditionFailure(
                ToolRegistry.ToolResult.text("Error: command is required")).isEmpty());
        assertTrue(tool.postConditionFailure(ToolRegistry.ToolResult.text("plain output")).isEmpty());
        assertTrue(tool.postConditionFailure(ToolRegistry.ToolResult.text("[1,2,3]")).isEmpty());
        assertTrue(tool.postConditionFailure(ToolRegistry.ToolResult.text("{\"output\":\"no exit code\"}")).isEmpty());
    }

    /** The verifier consults the tool, so a failed command stops scoring a clean pass. */
    @Test
    void checkRoutesThroughTheToolsPostCondition() throws Exception {
        // Establish the precondition rather than inherit it. This is the one case in
        // this class that needs a real registered tool, and the native set is a
        // process-global that concurrently-running classes replace: McpServerToolTest
        // publishes List.of() in @BeforeEach and restores in @AfterEach, so there are
        // as many wipe windows as it has test methods. Reading the registry without
        // re-publishing first made this assertion fail on whichever run happened to
        // land inside one. registerAll() is the same "restore the canonical set" call
        // McpConnectionManagerTest and McpServerServiceTest already use for this.
        // JCLAW-894: hold the registry lock for the whole assertion. The original
        // version of this test read the registry without establishing it and failed
        // on the v0.17.18 push; 292173da then called registerAll() bare, which fixed
        // that but could itself wipe a concurrent class's published adapter. The
        // lock is what makes both directions safe.
        ToolRegistrySync.withCanonicalTools(() -> {
            assertNotNull(ToolRegistry.lookupTool("exec"),
                    "exec must be registered for this wiring assertion to mean anything");

            var failed = ToolResultVerifier.check("exec", ToolRegistry.ToolResult.text(execEnvelope(1, false)));
            assertEquals(Verdict.POSTCONDITION_FAILED, failed.verdict());
            assertTrue(failed.failed());

            assertEquals(Verdict.OK,
                    ToolResultVerifier.check("exec", ToolRegistry.ToolResult.text(execEnvelope(0, false))).verdict());
        });
    }

    /**
     * Ordering: a tool that announced its failure in prose is ERROR_REPORTED, not
     * POSTCONDITION_FAILED. The two verdicts answer different questions — "did the
     * model get told" versus "did anyone notice" — and collapsing them would waste
     * the distinction the critic decision rests on.
     */
    @Test
    void announcedFailureStaysErrorReportedRatherThanPostcondition() {
        assertEquals(Verdict.ERROR_REPORTED,
                ToolResultVerifier.check("exec",
                        ToolRegistry.ToolResult.text("Error: command is required")).verdict());
    }

    /** An unregistered name passes: MCP tools come and go with their server. */
    @Test
    void unknownToolNamePasses() {
        assertEquals(Verdict.OK,
                ToolResultVerifier.check(UNREGISTERED, ToolRegistry.ToolResult.text("anything")).verdict());
    }

    /**
     * The story's deliverable is a per-turn rate, so both halves have to reach
     * {@link LatencyStats}. Unique channel names per assertion: LatencyStats is a
     * JVM-global singleton and play1 runs test classes concurrently, so a shared
     * channel would mix in samples from whatever else is mid-run (the constraint
     * {@code LatencyTraceTest} already documents).
     */
    @Test
    void cleanTurnRecordsTheDenominatorAndNoFailures() {
        var channel = "tv-clean-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        try (var _ = LatencyTrace.bind(trace)) {
            LatencyTrace.countToolVerification(false);
            LatencyTrace.countToolVerification(false);
        }
        trace.end();

        var stats = LatencyStats.snapshot().getAsJsonObject(channel);
        assertEquals(2L, stats.getAsJsonObject("tool_verify_count").get("sum_ms").getAsLong());
        // Zero-suppressed: LatencyStats clamps to a minimum of 1, so emitting
        // "0 failures" would record as 1 and invent a failure rate on every
        // clean turn — which would make the metric worse than not having it.
        assertFalse(stats.has("tool_verify_failed"),
                "a turn with no flagged result must not emit the failure segment");
    }

    @Test
    void flaggedResultRecordsBothHalvesOfTheRate() {
        var channel = "tv-failed-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        try (var _ = LatencyTrace.bind(trace)) {
            LatencyTrace.countToolVerification(false);
            LatencyTrace.countToolVerification(true);
        }
        trace.end();

        var stats = LatencyStats.snapshot().getAsJsonObject(channel);
        assertEquals(2L, stats.getAsJsonObject("tool_verify_count").get("sum_ms").getAsLong(),
                "the denominator counts every verified result, flagged or not");
        assertEquals(1L, stats.getAsJsonObject("tool_verify_failed").get("sum_ms").getAsLong());
    }

    /** Outside a turn there is nothing to bill, matching {@code countLlmCall}. */
    @Test
    void countingOutsideATurnIsANoOp() {
        // Assert the precondition rather than assume it (java:S2699): "no-op outside
        // a turn" only means anything if no trace is actually bound to this thread,
        // and a test that merely called the method would still pass if the binding
        // leaked from somewhere else.
        assertNull(LatencyTrace.current(), "this assertion is only meaningful outside a turn");
        assertDoesNotThrow(() -> LatencyTrace.countToolVerification(true),
                "counting with no bound trace must be a silent no-op, not an NPE");
    }
}
