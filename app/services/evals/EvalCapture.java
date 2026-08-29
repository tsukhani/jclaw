package services.evals;

import agents.AgentExecutionSink;
import agents.AgentRunner;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import mcp.McpGrants;
import models.Agent;
import models.AgentToolConfig;
import models.Task;
import services.AttachmentService;
import services.EventLogger;
import services.TaskWriteService;
import services.Tx;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives a live agent through an {@link EvalSuite} and records what it produced,
 * in the same file format {@link EvalRunner} replays (JCLAW-883).
 *
 * <p>Capture and scoring stay separate on purpose. Scoring is pure and offline —
 * {@code ./jclaw.sh evals} boots no framework — while driving an agent needs JPA,
 * a provider and the tool registry. Keeping the recorded file as the boundary
 * means a sweep can be scored now, re-scored later against a changed suite, or
 * diffed against a baseline, without paying for the model twice.
 *
 * <h2>What a sweep does not leave behind</h2>
 * <ul>
 *   <li><b>No conversation.</b> Turns run through {@link AgentRunner#runForTask},
 *       which manufactures a transient stub {@link models.Conversation} that is
 *       never persisted. There is no history row to clean up afterwards, which is
 *       a stronger guarantee than creating a throwaway conversation and deleting
 *       it — nothing exists to leak if the sweep dies midway.</li>
 *   <li><b>No memory writes.</b> {@code MemoryAutoCapture.captureAsync} fires from
 *       the two chat entrypoints, not from {@code runForTask}, so an eval sweep
 *       cannot teach the agent anything about its own eval questions.</li>
 *   <li><b>No latency samples.</b> The per-case {@link LatencyTrace} exists only
 *       to count model calls; {@link LatencyTrace#end()} is never called, so
 *       nothing reaches {@link utils.LatencyStats}. Hundreds of eval turns landing
 *       in the request-path histograms would corrupt the baseline JCLAW-833
 *       measures against. Belt and braces: {@code runForTask} never marks
 *       {@code PROLOGUE_DONE}, and {@code end()} discards any trace without it.</li>
 *   <li><b>Nothing runs unless asked.</b> The only caller is an operator-triggered
 *       endpoint; a normal turn never reaches this class.</li>
 * </ul>
 *
 * <h2>What it DOES leave behind: tool side effects</h2>
 *
 * <p>The isolation above covers the turn's own bookkeeping. It does not cover what
 * the agent <i>does</i>. {@code runForTask} hands the agent its full configured
 * tool surface and tools execute for real — a suite case that induces a
 * {@code task_manager} call creates a real scheduled task, one that induces a
 * write tool writes.
 *
 * <p>This is not theoretical: the first live run of {@code tool-selection}
 * against the operator's {@code main} agent created a recurring task that had to
 * be deleted by hand. Point capture at a calibrated agent whose tool surface is
 * scoped to what the suite needs — see {@code __evaltest__} in
 * {@code evals/README.md} — not at an agent you actually use.
 *
 * <p>Scoping that surface is enforced at execution as well as in the schema
 * (JCLAW-883). The second sweep proved why it had to be: pointed at an
 * {@code __evaltest__} granted nothing, the model guessed {@code web_search} and
 * ran it, because per-agent tool config was consulted only when building the list
 * of tools sent to the model.
 */
public final class EvalCapture {

    private static final String EVENT_CATEGORY = "evals";

    private EvalCapture() {}

    /**
     * A recorded run, shaped exactly like the file {@code --responses} consumes so
     * the two paths cannot drift: whatever this writes, the offline scorer reads.
     */
    public record Capture(String suite, String fingerprint, Map<String, EvalScorer.Response> responses) {}

    /**
     * Find {@code __evaltest__}, provisioning it on first use — the same
     * find-or-create shape {@code LoadTestRunner} uses for its benchmark agents.
     *
     * <p>Provider and model are copied from {@code main} as a starting point the
     * operator can change in the agent editor; there is no sensible way to invent a
     * model id, so a deployment with no {@code main} agent gets {@code null} here
     * and the caller reports it rather than guessing.
     *
     * <p>No tool config is seeded. The agent starts with an empty tool surface
     * because {@code ToolRegistry.computeDisabledTools} makes every tool opt-in for
     * it, which stays fail-closed as new tools are registered — seeding disabled
     * rows at creation would not.
     *
     * @return the agent, or {@code null} when there is no {@code main} agent to
     *         derive a provider and model from
     */
    public static Agent ensureEvalAgent() {
        var existing = Agent.findByName(Agent.EVALTEST_AGENT_NAME);
        if (existing != null) return existing;

        var main = Agent.findByName(Agent.MAIN_AGENT_NAME);
        if (main == null) return null;

        // JCLAW-906: the agent must be COMMITTED before it is returned, not merely
        // saved into the caller's transaction. calibrate() writes the tool grants on
        // its own committed transaction (see commitInFreshTx), and that thread cannot
        // see an uncommitted row — the AgentToolConfig insert then violates its
        // foreign key, the whole request rolls back, and the agent this method
        // claimed to provision does not exist. Which made "auto-provisioned on first
        // capture" false in every doc that promised it, and made the failure
        // self-perpetuating: every retry took the same path.
        //
        // Read main's fields on this thread; it belongs to the caller's persistence
        // context and must not be touched from the provisioning thread.
        return provision(main.modelProvider, main.modelId);
    }

    /**
     * Create and COMMIT the eval agent with the given provider and model, returning a
     * managed instance. Split from {@link #ensureEvalAgent} so the commit behavior is
     * testable without a {@code main} agent: several test classes create one under
     * that exact name and this suite runs classes concurrently, so a test that seeded
     * or deleted {@code main} would be racing them.
     *
     * <p>Public for the same reason {@link #calibrate} is — Play's tests live in the
     * default package.
     */
    public static Agent provision(String provider, String modelId) {
        commitInFreshTx("provision", () -> {
            // Re-check inside the committed transaction: a second concurrent first
            // capture would otherwise have seen null too and inserted a duplicate.
            if (Agent.findByName(Agent.EVALTEST_AGENT_NAME) != null) return;
            var agent = new Agent();
            agent.name = Agent.EVALTEST_AGENT_NAME;
            agent.modelProvider = provider;
            agent.modelId = modelId;
            agent.enabled = true;
            agent.description = "Eval sweeps (JCLAW-883). Tools are opt-in — grant only what a suite needs.";
            agent.save();
        });

        EventLogger.info(EVENT_CATEGORY, "Provisioned %s with no tools enabled, on %s/%s"
                .formatted(Agent.EVALTEST_AGENT_NAME, provider, modelId));
        // Re-read in the caller's context. The row was written by another thread's
        // persistence context, so returning that instance would hand back a detached
        // entity from a closed EntityManager.
        return Agent.findByName(Agent.EVALTEST_AGENT_NAME);
    }

    /**
     * One agent turn, as capture needs it: a prompt in, the final assistant text out,
     * everything else routed through the sink.
     *
     * <p>A parameter rather than a static test seam so a test can substitute a turn
     * without mutating process-global provider state — this suite runs its classes
     * concurrently, and a global the tests flip would make them order-dependent.
     */
    @FunctionalInterface
    public interface TurnRunner {
        String run(Agent agent, String prompt, AgentExecutionSink sink) throws Exception;
    }

    /**
     * Run every case in {@code suite} against {@code agent}, at most
     * {@code maxConcurrency} in front of the model at once.
     *
     * <p>A case whose turn throws is recorded with its reason rather than dropped,
     * so the scorer can report "the agent errored" distinctly from "the agent
     * answered wrongly" — a sweep during a provider outage should read as
     * unmeasured, not as a quality collapse.
     */
    public static Capture run(EvalSuite suite, Agent agent, int maxConcurrency) {
        calibrate(suite, agent);
        return run(suite, agent, maxConcurrency,
                (a, prompt, sink) -> AgentRunner.runForTask(a, prompt, sink).content());
    }

    /**
     * Grant the eval agent exactly the tools this suite declares, revoking anything
     * else (JCLAW-883).
     *
     * <p>Without it the agent's tool surface is whatever the last sweep or the last
     * click in the agent editor left behind, so a suite's pass rate silently depends
     * on state that has nothing to do with the suite. Declaring the tools in the
     * suite file and applying them here makes a sweep reproducible from the
     * repository alone.
     *
     * <p>Only ever touches {@code __evaltest__}. Pointing capture at an agent the
     * operator configured themselves must not rewrite their configuration — if that
     * agent lacks a tool the suite needs, the cases fail and say which tool is
     * missing, which is the correct outcome for an agent this code does not own.
     */
    // Public because Play's tests live in the default package. Calling it directly is
    // also legitimate for an operator tool that wants to stage an agent without
    // sweeping — the guard against touching a non-eval agent is inside, not at the
    // call site, so exposure cannot widen what it is willing to rewrite.
    public static void calibrate(EvalSuite suite, Agent agent) {
        if (agent == null || !agent.isEvalTest()) return;

        commitInFreshTx("calibrate", () -> {
            AgentToolConfig.delete("agent = ?1", agent);
            for (var tool : suite.requiredTools()) {
                var config = McpGrants.newRow(agent, tool);
                config.enabled = true;
                config.save();
            }
            clearTaskState(agent);
        });
        ToolRegistry.invalidateDisabledToolsCache(agent);

        EventLogger.info(EVENT_CATEGORY, "Calibrated %s for suite '%s': granted %s"
                .formatted(Agent.EVALTEST_AGENT_NAME, suite.id(),
                        suite.requiredTools().isEmpty() ? "no tools" : suite.requiredTools()));
    }

    /**
     * Delete every task the eval agent owns, so a sweep starts from the same state
     * as the one before it (JCLAW-907).
     *
     * <p>Same argument as the tool-grant reset above, applied to what the tools
     * DID rather than which ones were offered. Measured on 2026-07-31: fifteen
     * sweeps of {@code tool-selection} produced five different action sequences for
     * one case — {@code [createTask]}, {@code [listRecurringTasks]} (found the task
     * a previous sweep left and declined to duplicate it), {@code [listRecurringTasks,
     * updateTask]}, and once {@code [listRecurringTasks, cancelTask, cancelTask,
     * cancelTask, createTask]} clearing three accumulated duplicates. Those are not
     * model variance; several are the agent responding correctly to a world its
     * predecessor left behind. A suite that asserts a fixed call sequence cannot
     * pass against a starting state that changes every run.
     *
     * <p>Routed through {@link TaskWriteService#deleteWithHistory} rather than a bulk
     * delete: a task owns TaskRun, TaskRunMessage and Notification rows, and a live
     * scheduler entry that outlives the row unless canceled. A bulk delete would
     * leave a deleted reminder firing.
     *
     * <p>Caller guarantees this only ever runs for {@code __evaltest__} — see the
     * {@code isEvalTest} guard in {@link #calibrate}. Deleting an operator's tasks
     * because they pointed capture at their own agent would be unforgivable.
     */
    @SuppressWarnings("unchecked") // Play's find().fetch() is a raw List; same copy as TaskTool.findTasks
    private static void clearTaskState(Agent agent) {
        var raw = (List<Object>) (List<?>) Task.find("agent = ?1", agent).fetch();
        for (var row : raw) {
            TaskWriteService.deleteWithHistory((Task) row);
        }
        if (!raw.isEmpty()) {
            EventLogger.info(EVENT_CATEGORY, "Cleared %d task(s) left by a previous sweep"
                    .formatted(raw.size()));
        }
    }

    /**
     * Variant that takes the turn as a parameter — see {@link TurnRunner}. Public
     * because Play's tests live in the default package, and because substituting the
     * turn is a legitimate thing for a caller to want; the production overload above
     * is simply the one that supplies the real agent run.
     */
    public static Capture run(EvalSuite suite, Agent agent, int maxConcurrency, TurnRunner turns) {
        var captured = EvalRunner.mapCasesBounded(suite.cases(), maxConcurrency,
                testCase -> Map.entry(testCase.id(), captureOne(testCase, agent, turns)));

        // LinkedHashMap so the recorded file keeps suite order and two captures of
        // the same suite diff line by line, matching how the report already behaves.
        var responses = new LinkedHashMap<String, EvalScorer.Response>();
        captured.forEach(e -> responses.put(e.getKey(), e.getValue()));
        return new Capture(suite.id(), suite.fingerprint(), responses);
    }

    /**
     * Run {@code block} in a transaction that COMMITS before returning, whatever the
     * caller is inside (JCLAW-883).
     *
     * <p>{@link Tx#run} deliberately joins an ambient transaction rather than
     * orphaning its EntityManager, so calibration called from a controller action
     * writes into the request's transaction and stays uncommitted until that action
     * returns. The sweep starts before then, and each case runs on its own virtual
     * thread with its own persistence context — which cannot see uncommitted rows
     * from another transaction. The first calibrated sweep did exactly this: the
     * grants were written and logged, and every tool still read as disabled, so the
     * model was offered nothing and fell back to guessing names.
     *
     * <p>A fresh thread has no ambient transaction, so {@code Tx.run} takes its
     * {@code JPA.withTransaction} branch and commits. Same shape as the
     * {@code commitInFreshTx} helper the tests use for HTTP-visible seeding.
     */
    private static void commitInFreshTx(String what, Runnable block) {
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().name("eval-" + what).start(() -> {
            try {
                Tx.run(block);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while trying to %s the eval agent".formatted(what), e);
        }
        if (error.get() != null) {
            // Name which step failed: provisioning and calibration fail on the same
            // seam for related reasons, and "could not calibrate" sent the first
            // diagnosis of JCLAW-906 to the wrong half of the code.
            throw new IllegalStateException("Could not %s the eval agent".formatted(what), error.get());
        }
    }

    private static EvalScorer.Response captureOne(EvalCase testCase, Agent agent, TurnRunner turns) {
        // Counts this turn's model calls (JCLAW-882) without ever being ended — see
        // the class comment on why an eval turn must not reach LatencyStats. The
        // binding must be open BEFORE the turn starts: LlmProvider counts at its
        // dispatch point by reading the calling thread's binding, so a turn invoked
        // outside it would report zero calls and silently unscore max_llm_calls.
        var trace = LatencyTrace.forTurn("eval", null);
        var sink = new CaptureSink();
        try (var _ = LatencyTrace.bind(trace)) {
            var content = turns.run(agent, testCase.input(), sink);
            return new EvalScorer.Response(content, sink.toolsCalled(), sink.toolsAttempted(),
                    sink.toolArgs(), sink.toolResults(), trace.llmCallCount(), null);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Eval case '%s' errored: %s".formatted(testCase.id(), e));
            return EvalScorer.Response.failed(e.toString());
        }
    }

    /**
     * Write target that keeps nothing. {@link AgentRunner#runForTask} routes every
     * persistence call through its sink, so supplying one that only observes is what
     * makes a sweep leave no rows behind — the same seam {@code TaskRunSink} uses to
     * redirect task fires away from the chat schema.
     *
     * <p>Its one job beyond discarding is recording tool names in call order, which
     * is what the {@code tools_called_exactly} / {@code tools_called_within} checks
     * score against.
     */
    private static final class CaptureSink implements AgentExecutionSink {

        // ParallelToolExecutor commits results from the turn's thread, but tool
        // dispatch itself fans out across virtual threads; synchronize so a
        // multi-tool round cannot lose a name to a torn ArrayList write.
        private final List<String> attempted = Collections.synchronizedList(new ArrayList<>());

        // Correlates the assistant turn (which carries the name) with the outcome
        // that arrives afterwards keyed by call id. commitToolResults fires
        // appendAssistantMessage, appendToolResult and noteToolOutcome for one call
        // before moving to the next, so the id is always already known here.
        private final Map<String, String> nameByCallId = new ConcurrentHashMap<>();
        private final Map<String, String> argsByCallId = new ConcurrentHashMap<>();
        private final List<String> dispatched = Collections.synchronizedList(new ArrayList<>());

        // Arguments of DISPATCHED calls, per tool, in call order. Keyed by tool name
        // rather than kept as a flat list because that is how a check reads it:
        // "did the datetime call carry action=calculate" needs that tool's calls, not
        // a positional index into every call the turn made.
        private final Map<String, List<String>> argsByTool = new ConcurrentHashMap<>();

        // Result text of dispatched calls, per tool (JCLAW-891). Arguments say what
        // the agent ASKED for; only this says what happened — a live sweep passed a
        // case whose web_fetch returned "Error fetching URL: HTTP 404", because
        // nothing looked at what came back.
        private final Map<String, String> resultByCallId = new ConcurrentHashMap<>();
        private final Map<String, List<String>> resultsByTool = new ConcurrentHashMap<>();

        @Override
        public void appendUserMessage(String content, List<AttachmentService.Input> attachments) {
            // The eval question is the input we already hold; nothing to record.
        }

        @Override
        public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                           String reasoning, boolean truncated) {
            if (toolCalls != null) recordToolName(toolCalls);
        }

        @Override
        public void appendToolResult(String toolCallId, String result, String structuredJson) {
            // Stashed by call id rather than filed under the tool immediately: the
            // outcome that decides whether this call counts arrives next, in
            // noteToolOutcome. A refused call must contribute no result, for the same
            // reason it contributes no name and no arguments.
            if (result != null) resultByCallId.put(toolCallId, result);
        }

        @Override
        public void noteToolOutcome(String toolCallId, ToolRegistry.ToolResult.Outcome outcome) {
            if (outcome != ToolRegistry.ToolResult.Outcome.DISPATCHED) return;
            var name = nameByCallId.get(toolCallId);
            if (name == null) return;
            dispatched.add(name);
            var args = argsByCallId.get(toolCallId);
            if (args != null) {
                argsByTool.computeIfAbsent(name, _ -> Collections.synchronizedList(new ArrayList<>())).add(args);
            }
            var result = resultByCallId.get(toolCallId);
            if (result != null) {
                resultsByTool.computeIfAbsent(name, _ -> Collections.synchronizedList(new ArrayList<>())).add(result);
            }
        }

        @Override
        public String executionLabel() {
            return "eval-capture";
        }

        /**
         * Pull {@code function.name} out of the single serialized
         * {@code LlmTypes.ToolCall} that {@code ParallelToolExecutor} commits per
         * call. A shape we cannot parse is skipped rather than thrown: losing one
         * tool name costs a check its evidence, while throwing would lose the whole
         * case's answer along with it.
         */
        private void recordToolName(String toolCallJson) {
            try {
                var obj = JsonParser.parseString(toolCallJson).getAsJsonObject();
                var fn = obj.getAsJsonObject("function");
                if (fn == null) return;
                var name = fn.get("name");
                if (name == null || name.isJsonNull()) return;
                attempted.add(name.getAsString());
                var id = obj.get("id");
                if (id == null || id.isJsonNull()) return;
                nameByCallId.put(id.getAsString(), name.getAsString());
                // Arguments ride in the same serialized ToolCall the executor commits,
                // so capturing them needs no extra plumbing — only keeping them.
                var args = fn.get("arguments");
                if (args != null && !args.isJsonNull()) {
                    argsByCallId.put(id.getAsString(), args.getAsString());
                }
            } catch (RuntimeException e) {
                EventLogger.warn(EVENT_CATEGORY, "Unparseable tool call in eval capture: " + e);
            }
        }

        /** Calls a tool actually ran. */
        List<String> toolsCalled() {
            return List.copyOf(dispatched);
        }

        /** Every name the model emitted, dispatched or refused. */
        List<String> toolsAttempted() {
            return List.copyOf(attempted);
        }

        /** Arguments of dispatched calls, per tool, in call order. */
        Map<String, List<String>> toolArgs() {
            return snapshot(argsByTool);
        }

        /** Result text of dispatched calls, per tool, in call order. */
        Map<String, List<String>> toolResults() {
            return snapshot(resultsByTool);
        }

        private static Map<String, List<String>> snapshot(Map<String, List<String>> source) {
            var copy = new LinkedHashMap<String, List<String>>();
            source.forEach((tool, values) -> copy.put(tool, List.copyOf(values)));
            return Map.copyOf(copy);
        }
    }
}
