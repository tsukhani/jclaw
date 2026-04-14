import agents.AgentRunner;
import agents.ToolRegistry;
import llm.LlmTypes.*;
import models.Agent;
import models.Conversation;
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
 * Proves that AgentRunner.executeToolsParallel runs multiple tool calls
 * concurrently on virtual threads — a single-round batch of N tools should
 * complete in ~single-tool wall time, not N × single-tool.
 */
public class ParallelToolExecutionTest extends UnitTest {

    private static final long TOOL_SLEEP_MS = 300;

    private List<ToolRegistry.Tool> originalTools;
    private AtomicInteger concurrentExecutions;
    private AtomicInteger peakConcurrency;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        originalTools = ToolRegistry.listTools();
        concurrentExecutions = new AtomicInteger();
        peakConcurrency = new AtomicInteger();
        ToolRegistry.publish(List.of(sleepTool()));
    }

    @AfterEach
    void restore() {
        ToolRegistry.publish(originalTools);
    }

    private ToolRegistry.Tool sleepTool() {
        return new ToolRegistry.Tool() {
            @Override public String name() { return "slow_tool"; }
            @Override public String description() { return "Sleeps for the test."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
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
                return "ok-from-" + Thread.currentThread().getName();
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
        var m = AgentRunner.class.getDeclaredMethod("executeToolsParallel",
                List.class, Agent.class, Long.class, List.class,
                java.util.function.Consumer.class, List.class, AtomicBoolean.class);
        m.setAccessible(true);
        m.invoke(null, calls, agent, convId, messages, null, null, cancelled);
    }

    @Test
    public void threeToolsRunInParallel() throws Exception {
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.of(
                new ToolCall("call-a", "function", new FunctionCall("slow_tool", "{}")),
                new ToolCall("call-b", "function", new FunctionCall("slow_tool", "{}")),
                new ToolCall("call-c", "function", new FunctionCall("slow_tool", "{}")));
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
    public void singleToolSkipsParallelOverhead() throws Exception {
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var calls = List.<ToolCall>of(
                new ToolCall("solo", "function", new FunctionCall("slow_tool", "{}")));
        var messages = new ArrayList<ChatMessage>();

        invokeParallel(calls, agent, ids[1], messages, new AtomicBoolean(false));

        assertEquals(1, messages.size());
        assertEquals("solo", messages.getFirst().toolCallId());
        // Peak concurrency should be 1 — no spurious virtual thread fan-out.
        assertEquals(1, peakConcurrency.get());
    }

    @Test
    public void cancellationSkipsRemainingTools() throws Exception {
        long[] ids = seedAgentAndConversation();
        var agent = (Agent) Agent.findById(ids[0]);

        var cancelled = new AtomicBoolean(true); // already cancelled
        var calls = List.of(
                new ToolCall("x", "function", new FunctionCall("slow_tool", "{}")),
                new ToolCall("y", "function", new FunctionCall("slow_tool", "{}")));
        var messages = new ArrayList<ChatMessage>();

        invokeParallel(calls, agent, ids[1], messages, cancelled);

        // With isCancelled true going in, no tool should have run.
        assertEquals(0, peakConcurrency.get());
        assertEquals(0, messages.size());
    }
}
