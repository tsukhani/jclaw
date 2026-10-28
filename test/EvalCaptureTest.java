import agents.ToolRegistry;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCapture;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalRunner;
import services.evals.EvalSuite;
import utils.LatencyTrace;

import java.util.List;

/**
 * Live-capture behaviour (JCLAW-883): what a captured record carries, and that it
 * feeds the offline scorer unchanged.
 *
 * <p>Hermetic by construction — every turn here is a substituted
 * {@link EvalCapture.TurnRunner}, so nothing reaches a provider, a sidecar, or the
 * network. What that leaves uncovered is the real {@code AgentRunner.runForTask}
 * wiring; the part of it this class does pin down is the subtle one, that capture
 * opens the {@link LatencyTrace} binding around the turn, because
 * {@code LlmProvider} counts calls by reading the calling thread's binding and a
 * turn invoked outside it would record zero.
 */
class EvalCaptureTest extends UnitTest {

    private static final String DATETIME_CALL =
            "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"datetime\",\"arguments\":\"{}\"}}";
    private static final String SEARCH_CALL =
            "{\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}";

    private static Agent evalAgent() {
        var agent = new Agent();
        agent.name = "__eval__";
        return agent;
    }

    private static EvalSuite suiteAsking(String caseId, EvalCheck... checks) {
        return new EvalSuite("capture-fixture", "fixture",
                List.of(new EvalCase(caseId, "what time is it?", "the clock is a local tool",
                        List.of(checks))));
    }

    // ==================== What a captured record carries ====================

    @Test
    void capturedRecordCarriesTheAnswer() {
        var suite = suiteAsking("clock",
                EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("11:04")));

        var capture = EvalCapture.run(suite, evalAgent(), 1,
                (agent, prompt, sink) -> "It is 11:04.");

        assertEquals("capture-fixture", capture.suite());
        assertEquals(suite.fingerprint(), capture.fingerprint(), "the recording names which suite content produced it");
        assertEquals("It is 11:04.", capture.responses().get("clock").output());
        assertNull(capture.responses().get("clock").error());
    }

    @Test
    void aRefusedCallIsAttemptedButNotCalled() {
        // JCLAW-883: toolsCalled used to record every emitted call, so a live sweep
        // reported three "calls" to httpFetch/http_fetch/webSearch on a turn that
        // executed nothing. The checks score toolsCalled, so that read as the agent
        // having used tools it never reached.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, DATETIME_CALL, null, null, false);
            sink.noteToolOutcome("call_1", ToolRegistry.ToolResult.Outcome.DISPATCHED);
            sink.appendAssistantMessage(null, SEARCH_CALL, null, null, false);
            sink.noteToolOutcome("call_2", ToolRegistry.ToolResult.Outcome.NOT_ENABLED);
            return "It is 11:04.";
        });

        var recorded = capture.responses().get("clock");
        assertEquals(List.of("datetime"), recorded.toolsCalled(), "only what a tool actually ran");
        assertEquals(List.of("datetime", "web_search"), recorded.toolsAttempted(), "everything emitted");
        assertEquals(List.of("web_search"), recorded.toolsRefused());
    }

    @Test
    void capturedRecordCarriesTheArgumentsOfDispatchedCalls() {
        // Arguments ride in the same serialized ToolCall the executor already commits,
        // so this needs no extra plumbing — only keeping them. Without it a check can
        // see that datetime was called but not which action it was asked for.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, DATETIME_CALL, null, null, false);
            sink.noteToolOutcome("call_1", ToolRegistry.ToolResult.Outcome.DISPATCHED);
            return "It is 11:04.";
        });

        assertEquals(List.of("{}"), capture.responses().get("clock").argsFor("datetime"));
    }

    @Test
    void capturedRecordCarriesTheResultOfDispatchedCalls() {
        // JCLAW-891: arguments say what the agent asked for; only the result says what
        // happened. appendToolResult was already being called and discarded.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, DATETIME_CALL, null, null, false);
            sink.appendToolResult("call_1", "Difference: 108 days", null);
            sink.noteToolOutcome("call_1", ToolRegistry.ToolResult.Outcome.DISPATCHED);
            return "108 days.";
        });

        assertEquals(List.of("Difference: 108 days"),
                capture.responses().get("clock").resultsFor("datetime"));
    }

    @Test
    void aRefusedCallContributesNoResult() {
        // Same rule as names and arguments: a call that reached no tool produced no
        // result, so there is nothing to assert against.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, SEARCH_CALL, null, null, false);
            sink.appendToolResult("call_2", "Error: Tool 'web_search' is not enabled for this agent.", null);
            sink.noteToolOutcome("call_2", ToolRegistry.ToolResult.Outcome.NOT_ENABLED);
            return "no search tool";
        });

        assertTrue(capture.responses().get("clock").resultsFor("web_search").isEmpty());
    }

    @Test
    void aRefusedCallContributesNoArguments() {
        // Symmetric with toolsCalled: a call that reached no tool carried its
        // arguments nowhere, so asserting on them would be asserting on an intent.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, SEARCH_CALL, null, null, false);
            sink.noteToolOutcome("call_2", ToolRegistry.ToolResult.Outcome.NOT_ENABLED);
            return "no search tool";
        });

        assertTrue(capture.responses().get("clock").argsFor("web_search").isEmpty());
    }

    @Test
    void anInventedToolNameCountsAsAttemptedOnly() {
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, SEARCH_CALL, null, null, false);
            sink.noteToolOutcome("call_2", ToolRegistry.ToolResult.Outcome.UNKNOWN_TOOL);
            return "I have no search tool.";
        });

        var recorded = capture.responses().get("clock");
        assertTrue(recorded.toolsCalled().isEmpty(), "a name that reached no tool is not a call");
        assertEquals(List.of("web_search"), recorded.toolsAttempted());
    }

    @Test
    void capturedRecordCarriesToolNamesInCallOrder() {
        // ParallelToolExecutor commits one serialized ToolCall per call, in order;
        // the tools_called_* checks score against exactly this list.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, DATETIME_CALL, null, null, false);
            sink.noteToolOutcome("call_1", ToolRegistry.ToolResult.Outcome.DISPATCHED);
            sink.appendAssistantMessage(null, SEARCH_CALL, null, null, false);
            sink.noteToolOutcome("call_2", ToolRegistry.ToolResult.Outcome.DISPATCHED);
            return "It is 11:04.";
        });

        assertEquals(List.of("datetime", "web_search"), capture.responses().get("clock").toolsCalled());
    }

    @Test
    void capturedRecordCarriesTheTurnsLlmCallCount() {
        // The turn counts through the thread binding exactly as LlmProvider does at
        // its dispatch point, so max_llm_calls asserts against a real number.
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            LatencyTrace.countLlmCall();
            LatencyTrace.countLlmCall();
            return "It is 11:04.";
        });

        assertEquals(2, capture.responses().get("clock").llmCalls());
    }

    @Test
    void theTurnRunsInsideTheTraceBinding() {
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            assertNotNull(LatencyTrace.current(),
                    "capture must bind the trace before the turn, or the call count is silently zero");
            return "ok";
        });

        assertNull(capture.responses().get("clock").error());
    }

    @Test
    void anUnparseableToolCallCostsItsNameNotTheAnswer() {
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            sink.appendAssistantMessage(null, "{not json", null, null, false);
            return "It is 11:04.";
        });

        var recorded = capture.responses().get("clock");
        assertEquals("It is 11:04.", recorded.output(), "the answer survives a tool-call we cannot parse");
        assertTrue(recorded.toolsCalled().isEmpty());
        assertNull(recorded.error());
    }

    // ==================== Errors stay distinct from wrong answers ====================

    @Test
    void aTurnThatThrowsIsRecordedWithItsReason() {
        var capture = EvalCapture.run(suiteAsking("clock"), evalAgent(), 1, (agent, prompt, sink) -> {
            throw new IllegalStateException("provider unreachable");
        });

        var recorded = capture.responses().get("clock");
        assertNotNull(recorded.error(), "a turn that never answered must record why");
        assertTrue(recorded.error().contains("provider unreachable"), recorded.error());
        assertEquals("", recorded.output());
    }

    // ==================== Capture feeds the scorer unchanged ====================

    @Test
    void aCapturedRunScoresThroughTheOfflinePath() {
        // The whole point of the split: capture writes the recorded format, and the
        // scorer consumes it with no knowledge that an agent produced it.
        var suite = suiteAsking("clock",
                EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("11:04")),
                EvalCheck.of(EvalCheck.Kind.TOOLS_CALLED_EXACTLY, List.of("datetime")));

        var capture = EvalCapture.run(suite, evalAgent(), 1, (agent, prompt, sink) -> {
            LatencyTrace.countLlmCall();
            sink.appendAssistantMessage(null, DATETIME_CALL, null, null, false);
            sink.noteToolOutcome("call_1", ToolRegistry.ToolResult.Outcome.DISPATCHED);
            return "It is 11:04.";
        });

        var report = EvalRunner.run(suite, testCase -> capture.responses().get(testCase.id()));

        assertEquals(1, report.results().size());
        assertTrue(report.results().getFirst().passed(), report.summary());
        assertEquals(1, report.totalLlmCalls());
    }

    @Test
    void anErroredCaptureReachesTheReportAsErroredNotFailed() {
        var suite = suiteAsking("clock",
                EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("11:04")));

        var capture = EvalCapture.run(suite, evalAgent(), 1, (agent, prompt, sink) -> {
            throw new IllegalStateException("provider unreachable");
        });
        var report = EvalRunner.run(suite, testCase -> capture.responses().get(testCase.id()));

        var result = report.results().getFirst();
        assertTrue(result.errored(), "an outage must not read as the agent failing a check");
        assertEquals(0, report.scored());
        assertTrue(result.failures().getFirst().contains("provider unreachable"),
                result.failures().toString());
    }
}
