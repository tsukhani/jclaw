import agents.AgentRunner;
import agents.ToolRegistry;
import llm.LlmTypes.*;
import models.Agent;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConversationService;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises {@link AgentRunner#executeToolsParallel}'s scheduling model:
 * calls to different tools run on separate virtual threads (parallel),
 * while calls to the SAME tool run sequentially in declared order on one
 * thread (JCLAW-80 — same-tool calls share backend state so concurrent
 * execution produces nondeterministic order under lock contention).
 */
class ParallelToolExecutionTest extends UnitTest {

    private static final long TOOL_SLEEP_MS = 300;

    private List<ToolRegistry.Tool> originalTools;
    private AtomicInteger concurrentExecutions;
    private AtomicInteger peakConcurrency;
    /** Append-only record of the order in which sleep tools began executing,
     *  including the tool name and the sequence-within-tool so tests can assert
     *  same-tool ordering without racing on wall-clock timestamps. */
    private List<String> executionOrder;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        originalTools = ToolRegistry.listTools();
        concurrentExecutions = new AtomicInteger();
        peakConcurrency = new AtomicInteger();
        executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        ToolRegistry.publish(List.of(
                sleepTool("slow_a", false),
                sleepTool("slow_b", false),
                sleepTool("slow_c", false),
                sleepTool("safe_x", true),
                sleepTool("safe_y", true)));
    }

    @AfterEach
    void restore() {
        // Restore the global tool registry FIRST so no other test sees the
        // sleep stubs published in setup().
        ToolRegistry.publish(originalTools);
        // Defensive: drop the per-test state holders so any straggling
        // virtual-thread callback that captured the old reference can't
        // mutate "stale" state into the next test method's view. The
        // synchronizedList wrapper is the most likely culprit — clearing
        // it makes accidental cross-test pollution loud rather than silent.
        if (executionOrder != null) executionOrder.clear();
        executionOrder = null;
        concurrentExecutions = null;
        peakConcurrency = null;
    }

    private ToolRegistry.Tool sleepTool(String toolName, boolean parallelSafe) {
        return sleepTool(toolName, parallelSafe, null);
    }

    /** Variant that lets a test pin the {@link ToolRegistry.Tool#serializationGroup()}
     *  key, mirroring how {@code SpawnSubagentTool}/{@code YieldToSubagentTool}
     *  override the default to opt into a shared serial queue. {@code null}
     *  preserves the default behavior (group = name when unsafe, no group when
     *  parallel-safe). */
    private ToolRegistry.Tool sleepTool(String toolName, boolean parallelSafe, String groupKey) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "Sleeps for the test."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public boolean parallelSafe() { return parallelSafe; }
            @Override public String serializationGroup() {
                return groupKey != null ? groupKey : ToolRegistry.Tool.super.serializationGroup();
            }
            @Override public String execute(String argsJson, Agent agent) {
                executionOrder.add(toolName + ":start");
                int nowRunning = concurrentExecutions.incrementAndGet();
                // Track the high-water mark so we can assert actual parallelism
                // rather than infer it from wall-clock alone.
                peakConcurrency.updateAndGet(p -> Math.max(p, nowRunning));
                try {
                    Thread.sleep(TOOL_SLEEP_MS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrentExecutions.decrementAndGet();
                }
                return "ok-from-" + toolName;
            }
        };
    }

    /** Create agent + conversation in the DB so the commit phase of
     *  executeToolsParallel has real rows to write to. */
    private long[] seedAgentAndConversation() {
        return Tx.run(() -> {
            var agent = new Agent();
            agent.name = "parallel-tool-test";
            agent.modelProvider = "test";
            agent.modelId = "test";
            agent.save();
            var conv = ConversationService.create(agent, "web", "tester");
            return new long[]{agent.id, conv.id};
        });
    }

    private static void invokeParallel(List<ToolCall> calls, Agent agent, long convId,
                                        List<ChatMessage> messages, AtomicBoolean cancelled)
            throws Exception {
        // JCLAW-170: signature gained the onToolCall Consumer<ToolCallEvent>
        // parameter ahead of the image collector. We pass null here since
        // these tests exercise scheduling semantics, not the per-call event
        // stream — the production code tolerates null onToolCall.
        // JCLAW-299 Phase 2: executeToolsParallel lives on
        // agents.ParallelToolExecutor.
        // JCLAW-21 commit 1: trailing AgentExecutionSink parameter wires
        // per-tool-call assistant + tool-result writes through the sink
        // abstraction. Reflection-construct a ConversationSink from the
        // test's persisted conversation so the writes hit the same
        // conversation_message rows the pre-sink tests assert on.
        var conv = (models.Conversation) services.Tx.run(() ->
                services.ConversationService.findById(convId));
        var sink = new agents.ConversationSink(conv);
        var m = agents.ParallelToolExecutor.class.getDeclaredMethod("executeToolsParallel",
                List.class, Agent.class, Long.class, List.class,
                java.util.function.Consumer.class, java.util.function.Consumer.class,
                List.class, AtomicBoolean.class, agents.AgentExecutionSink.class);
        m.setAccessible(true);
        m.invoke(null, calls, agent, convId, messages, null, null, null, cancelled, sink);
    }

    @Test
    void differentToolsRunInParallel() throws Exception {
        // Three calls to three different tools — each lands in its own
        // tool-name group, so they run on separate virtual threads. Wall
        // clock should be near TOOL_SLEEP_MS (parallel) not 3× it.
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("call-a", "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("call-b", "function", new FunctionCall("slow_b", "{}")),
                new ToolCall("call-c", "function", new FunctionCall("slow_c", "{}")));
        var messages = new ArrayList<ChatMessage>();

        long t0 = System.nanoTime();
        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Assert actual parallelism (not just wall-clock luck): peak
        // concurrent executions should reach 3.
        assertEquals(3, peakConcurrency.get(),
                "expected all 3 tools running concurrently, peak was " + peakConcurrency.get());

        // Wall clock should be closer to TOOL_SLEEP_MS than 3× that. Allow
        // 2× slack to absorb JVM warmup / scheduling jitter in CI.
        assertTrue(elapsedMs < TOOL_SLEEP_MS * 2,
                "expected <%dms, got %dms".formatted(TOOL_SLEEP_MS * 2, elapsedMs));

        // Order invariant: tool results appear in the same order as the
        // input tool calls — LLM history would break if they didn't.
        assertEquals(3, messages.size());
        assertEquals("call-a", messages.get(0).toolCallId());
        assertEquals("call-b", messages.get(1).toolCallId());
        assertEquals("call-c", messages.get(2).toolCallId());
    }

    @Test
    void sameToolCallsRunSequentiallyInDeclaredOrder() throws Exception {
        // JCLAW-80 regression: when the LLM emits multiple calls to the
        // SAME tool in one round, they must run on a single virtual thread
        // in declared order — not race for a shared-state lock. This
        // guarantees behavior is deterministic rather than depending on
        // thread scheduling or lock-acquisition fairness.
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("nav",    "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("click",  "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("shot",   "function", new FunctionCall("slow_a", "{}")));
        var messages = new ArrayList<ChatMessage>();

        long t0 = System.nanoTime();
        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Peak concurrency MUST be 1 — if parallelism leaked through we'd
        // see 3, which is exactly the old bug.
        assertEquals(1, peakConcurrency.get(),
                "same-tool calls must be sequential; peak was " + peakConcurrency.get());

        // Wall clock ≥ 3 × sleep confirms serial execution (minus trivial
        // bookkeeping). Small slack on the lower bound for scheduling.
        assertTrue(elapsedMs >= TOOL_SLEEP_MS * 3 - 50,
                "expected ≥%dms (3× sleep), got %dms".formatted(TOOL_SLEEP_MS * 3, elapsedMs));

        // Declared order preserved in results AND in the actual start order
        // recorded by the tool itself.
        assertEquals(3, messages.size());
        assertEquals("nav",   messages.get(0).toolCallId());
        assertEquals("click", messages.get(1).toolCallId());
        assertEquals("shot",  messages.get(2).toolCallId());
        assertEquals(List.of("slow_a:start", "slow_a:start", "slow_a:start"), executionOrder,
                "all three started by slow_a in order (one thread, serial)");
    }

    @Test
    void parallelSafeToolRunsAllCallsConcurrently() throws Exception {
        // JCLAW-81: a tool that overrides parallelSafe() → true opts out of
        // the same-tool-name group serialization. Three calls to one safe
        // tool run on three virtual threads → peak = 3, wall ≈ 1× sleep.
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("x1", "function", new FunctionCall("safe_x", "{}")),
                new ToolCall("x2", "function", new FunctionCall("safe_x", "{}")),
                new ToolCall("x3", "function", new FunctionCall("safe_x", "{}")));
        var messages = new ArrayList<ChatMessage>();

        long t0 = System.nanoTime();
        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(3, peakConcurrency.get(),
                "parallel-safe tool should run all 3 calls concurrently; peak was "
                        + peakConcurrency.get());
        assertTrue(elapsedMs < TOOL_SLEEP_MS * 2,
                "expected <%dms (parallel), got %dms".formatted(TOOL_SLEEP_MS * 2, elapsedMs));

        // Result order still preserved — parallel execution but in-order commit.
        assertEquals(List.of("x1", "x2", "x3"),
                messages.stream().map(ChatMessage::toolCallId).toList());
    }

    @Test
    void mixedSafeAndUnsafeCallsUsingRightStrategyPerGroup() throws Exception {
        // JCLAW-81: a batch that mixes parallel-safe tools with a same-tool
        // unsafe group should use per-call threads for the safe ones and a
        // single serial thread for the unsafe group. Two safe calls + two
        // unsafe-same-tool calls → 3 work units → peak = 3; the unsafe group
        // bounds wall time at ~2× sleep (two serial steps).
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("u1", "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("s1", "function", new FunctionCall("safe_x", "{}")),
                new ToolCall("u2", "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("s2", "function", new FunctionCall("safe_y", "{}")));
        var messages = new ArrayList<ChatMessage>();

        long t0 = System.nanoTime();
        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 3 work units: {slow_a-group}, safe_x, safe_y — all start concurrently.
        assertEquals(3, peakConcurrency.get(),
                "expected peak concurrency of 3 (slow_a group + 2 safe calls); got "
                        + peakConcurrency.get());

        // Wall time bounded by the slowest work unit: the slow_a group runs
        // 2 calls serially (~2× sleep), safe calls finish in 1× sleep.
        assertTrue(elapsedMs >= TOOL_SLEEP_MS * 2 - 50,
                "expected ≥%dms (slow_a serial group), got %dms"
                        .formatted(TOOL_SLEEP_MS * 2, elapsedMs));
        assertTrue(elapsedMs < TOOL_SLEEP_MS * 3,
                "expected <%dms (not fully serial), got %dms"
                        .formatted(TOOL_SLEEP_MS * 3, elapsedMs));

        // Declared order preserved in commit.
        assertEquals(List.of("u1", "s1", "u2", "s2"),
                messages.stream().map(ChatMessage::toolCallId).toList());
    }

    @Test
    void mixedCallsGroupByNameAndParallelizeAcrossGroups() throws Exception {
        // [a, b, a, b] → two groups (a × 2, b × 2). Each group runs on its
        // own virtual thread → 2 threads active at peak, not 4 (no
        // within-group parallelism) and not 1 (no accidental single-thread
        // serialization). Both groups finish in ~2 × TOOL_SLEEP_MS.
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("a1", "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("b1", "function", new FunctionCall("slow_b", "{}")),
                new ToolCall("a2", "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("b2", "function", new FunctionCall("slow_b", "{}")));
        var messages = new ArrayList<ChatMessage>();

        long t0 = System.nanoTime();
        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(2, peakConcurrency.get(),
                "expected 2 groups running in parallel, peak was " + peakConcurrency.get());

        // Two groups run in parallel, each sequential internally: ~2× sleep.
        // Lower bound: not faster than one group's serial time (2 × sleep).
        // Upper bound: not slower than fully serial (4 × sleep).
        assertTrue(elapsedMs >= TOOL_SLEEP_MS * 2 - 50,
                "groups should run in parallel but each is serial inside; got %dms".formatted(elapsedMs));
        assertTrue(elapsedMs < TOOL_SLEEP_MS * 4,
                "groups should parallelize, not run fully serial; got %dms".formatted(elapsedMs));

        // Commit order still matches declared order regardless of which
        // group finishes first.
        assertEquals(List.of("a1", "b1", "a2", "b2"),
                messages.stream().map(ChatMessage::toolCallId).toList());
    }

    @Test
    void singleToolSkipsParallelOverhead() throws Exception {
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.<ToolCall>of(
                new ToolCall("solo", "function", new FunctionCall("slow_a", "{}")));
        var messages = new ArrayList<ChatMessage>();

        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));

        assertEquals(1, messages.size());
        assertEquals("solo", messages.getFirst().toolCallId());
        // Peak concurrency should be 1 — no spurious virtual thread fan-out.
        assertEquals(1, peakConcurrency.get());
    }

    @Test
    void differentlyNamedToolsWithSharedSerializationGroupRunSequentially() throws Exception {
        // Regression for the spawn_subagent + yield_to_subagent race:
        // two distinct unsafe tools that share state (e.g. one inserts the
        // SubagentRun row, the other reads it) must serialize even when
        // their names differ. Both opt into the shared "subagent_lifecycle"
        // group via {@link ToolRegistry.Tool#serializationGroup}.
        //
        // Before the fix, name-keyed grouping placed these in two separate
        // VTs running in parallel — yield's findById could fire before
        // spawn's INSERT committed and return null. After the fix, the
        // shared group key collapses them into one serial queue in
        // declared order: spawn finishes (commits the row) before yield
        // begins (reads it).
        ToolRegistry.publish(List.of(
                sleepTool("fake_spawn", false, "subagent_lifecycle"),
                sleepTool("fake_yield", false, "subagent_lifecycle"),
                // A separate unsafe tool with the default (name-keyed) group
                // must NOT be dragged into the shared queue — confirms the
                // grouping is by key, not by some global "all-unsafe" sink.
                sleepTool("unrelated_unsafe", false)));
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("spawn", "function", new FunctionCall("fake_spawn", "{}")),
                new ToolCall("yield", "function", new FunctionCall("fake_yield", "{}")),
                new ToolCall("other", "function", new FunctionCall("unrelated_unsafe", "{}")));
        var messages = new ArrayList<ChatMessage>();

        long t0 = System.nanoTime();
        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Shared-group pair runs on ONE VT; unrelated_unsafe runs on its own VT.
        // Peak concurrency therefore is 2, never 3 — if peak == 3, the shared
        // group was not honored and spawn/yield were racing again.
        assertEquals(2, peakConcurrency.get(),
                "expected peak concurrency of 2 (shared group + 1 unrelated VT); got "
                        + peakConcurrency.get());

        // Declared order inside the shared group is the load-bearing invariant:
        // fake_spawn:start MUST appear before fake_yield:start in the
        // execution-order log, regardless of VT scheduling. The unrelated tool
        // can interleave in any position relative to the pair.
        int spawnIdx = executionOrder.indexOf("fake_spawn:start");
        int yieldIdx = executionOrder.indexOf("fake_yield:start");
        assertTrue(spawnIdx >= 0 && yieldIdx >= 0,
                "both shared-group tools must record their start: " + executionOrder);
        assertTrue(spawnIdx < yieldIdx,
                "fake_spawn must start before fake_yield in the shared group; got "
                        + executionOrder);

        // Wall-clock floor: shared group runs 2 calls serially => >= 2x sleep.
        // Upper bound: unrelated_unsafe runs concurrently with the group, so
        // total stays below 3x sleep (fully serial would be 3x sleep).
        assertTrue(elapsedMs >= TOOL_SLEEP_MS * 2 - 50,
                "shared-group serialization should take >=%dms, got %dms"
                        .formatted(TOOL_SLEEP_MS * 2, elapsedMs));
        assertTrue(elapsedMs < TOOL_SLEEP_MS * 3,
                "unrelated_unsafe should still run in parallel with the shared group; got %dms"
                        .formatted(elapsedMs));

        // Commit-order invariant unchanged: results land in declared input order.
        assertEquals(List.of("spawn", "yield", "other"),
                messages.stream().map(ChatMessage::toolCallId).toList());
    }

    @Test
    void serializationGroupOverrideBeatsDefaultNameGrouping() throws Exception {
        // Boundary check: when two tools share a serializationGroup key and
        // a THIRD tool keeps the default (its own name as the key), the
        // override-pair serializes against each other but not against the
        // third tool.
        //
        // This pins the lookup contract in ToolRegistry.serializationGroupFor:
        // the override is honored even when one of the participating tools
        // hasn't opted in by name match. Without this, a future regression
        // that special-cased "share group only when both tools agree on the
        // override" would let yield race spawn again on the first turn after
        // a registry reload.
        ToolRegistry.publish(List.of(
                sleepTool("pair_alpha", false, "shared_state"),
                sleepTool("pair_beta",  false, "shared_state"),
                sleepTool("loner",      false))); // default group = "loner"
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("p1", "function", new FunctionCall("pair_alpha", "{}")),
                new ToolCall("p2", "function", new FunctionCall("pair_beta",  "{}")),
                new ToolCall("p3", "function", new FunctionCall("pair_alpha", "{}")),
                new ToolCall("l1", "function", new FunctionCall("loner",      "{}")));
        var messages = new ArrayList<ChatMessage>();

        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));

        // 4 calls, 2 work units: {shared_state group of 3} + {loner singleton}.
        // Peak == 2: the shared group's single VT + loner's own VT.
        assertEquals(2, peakConcurrency.get(),
                "shared_state group should run on ONE VT in parallel with loner; got peak "
                        + peakConcurrency.get());

        // Inside the shared group, the three calls run in declared order
        // (p1: pair_alpha, p2: pair_beta, p3: pair_alpha). The execution-order
        // log records the start of each — extract just the shared-group rows
        // and assert the order matches LLM-declared.
        var sharedGroupStarts = executionOrder.stream()
                .filter(s -> s.startsWith("pair_alpha:") || s.startsWith("pair_beta:"))
                .toList();
        assertEquals(List.of("pair_alpha:start", "pair_beta:start", "pair_alpha:start"),
                sharedGroupStarts,
                "shared-group calls must execute in declared order on one VT");

        assertEquals(List.of("p1", "p2", "p3", "l1"),
                messages.stream().map(ChatMessage::toolCallId).toList());
    }

    @Test
    void cancellationSkipsRemainingTools() throws Exception {
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var cancelled = new AtomicBoolean(true); // already cancelled
        var calls = List.of(
                new ToolCall("x", "function", new FunctionCall("slow_a", "{}")),
                new ToolCall("y", "function", new FunctionCall("slow_b", "{}")));
        var messages = new ArrayList<ChatMessage>();

        invokeParallel(calls, agent, ids[1], messages, cancelled);

        // With isCancelled true going in, no tool should have run.
        assertEquals(0, peakConcurrency.get());
        assertEquals(0, messages.size());
    }
}
