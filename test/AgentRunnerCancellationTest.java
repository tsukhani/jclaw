import agents.AgentRunner;
import agents.CancellationManager;
import models.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for {@link CancellationManager}'s checkpoint
 * contract — the JCLAW-299 Phase 2 home for the cancellation primitives
 * that used to live in {@link AgentRunner}.
 *
 * <p>The streaming code path checks {@code AtomicBoolean isCancelled} at
 * safe boundaries (between LLM rounds, between tool calls, at the top of
 * queue-drain) via the package-private {@code checkCancelled} helper.
 * When the flag is set, the helper:
 * <ul>
 *   <li>logs the cancellation to event_log,</li>
 *   <li>invokes the {@code onCancel} Runnable on the supplied callbacks
 *       (so transports like Telegram can quiesce typing heartbeats), and</li>
 *   <li>returns {@code true} so the caller can short-circuit.</li>
 * </ul>
 *
 * <p>These tests pin the helper's contract via reflection — same pattern
 * {@code StreamingToolRoundTest} uses for {@code cancelledReturn}. The
 * extraction commit moved the methods but must preserve this contract,
 * which is what these tests verify.
 *
 * <p>Integration-level mid-stream cancellation (SSE token cadence with the
 * flag flipping between tokens) lives in a sibling characterization test
 * that requires an SSE-emitting mock LLM and is added in a separate
 * Phase 1 commit; this unit test covers the boolean / callback contract
 * the integration test then composes with.
 */
class AgentRunnerCancellationTest extends UnitTest {

    @BeforeEach
    void setup() {
        play.test.Fixtures.deleteDatabase();
    }

    // === checkCancelled (private static) — unit contract via reflection ===

    private static Method checkCancelledMethod() throws Exception {
        var m = CancellationManager.class.getDeclaredMethod("checkCancelled",
                AtomicBoolean.class,
                Agent.class,
                String.class,
                AgentRunner.StreamingCallbacks.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void checkCancelledReturnsFalseWhenFlagUnset() throws Exception {
        var agent = persistedAgent();
        var counter = new AtomicInteger(0);
        var result = (boolean) checkCancelledMethod().invoke(null,
                new AtomicBoolean(false), agent, "web", callbacksRecordingOnCancel(counter));
        assertFalse(result, "checkCancelled must return false when isCancelled flag is unset");
        assertEquals(0, counter.get(), "onCancel must NOT fire when flag is unset");
    }

    @Test
    void checkCancelledReturnsTrueAndInvokesOnCancelWhenFlagSet() throws Exception {
        var agent = persistedAgent();
        var counter = new AtomicInteger(0);
        var result = (boolean) checkCancelledMethod().invoke(null,
                new AtomicBoolean(true), agent, "web", callbacksRecordingOnCancel(counter));
        assertTrue(result, "checkCancelled must return true when isCancelled flag is set");
        assertEquals(1, counter.get(),
                "onCancel must fire exactly once when flag is set and callbacks are provided");
    }

    @Test
    void checkCancelledHandlesNullCallbacksWithoutNpe() throws Exception {
        var agent = persistedAgent();
        // The caller may pass null for the callbacks record (e.g., test
        // harnesses that don't need transport-side quiesce). checkCancelled
        // still has to return the right boolean and log the cancellation.
        var result = (boolean) checkCancelledMethod().invoke(null,
                new AtomicBoolean(true), agent, "web", null);
        assertTrue(result, "checkCancelled must return true even with null callbacks");
    }

    @Test
    void checkCancelledHandlesNullOnCancelFieldWithoutNpe() throws Exception {
        var agent = persistedAgent();
        // The callbacks record exists but its onCancel Runnable is null —
        // checkCancelled guards both the record itself and the field, so
        // this is the canonical "I don't care about cancel-side quiesce
        // but I do want the rest of the callbacks wired" shape.
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {},   // onInit
                _ -> {},   // onToken
                _ -> {},   // onReasoning
                _ -> {},   // onStatus
                _ -> {},   // onToolCall
                _ -> {},   // onComplete
                _ -> {},   // onError
                null       // onCancel
        );
        var result = (boolean) checkCancelledMethod().invoke(null,
                new AtomicBoolean(true), agent, "web", cb);
        assertTrue(result, "checkCancelled must return true when onCancel field is null");
    }

    // === Helpers ===

    /**
     * Build a {@link AgentRunner.StreamingCallbacks} whose onCancel
     * Runnable increments {@code counter}. All other callbacks are no-ops.
     */
    private AgentRunner.StreamingCallbacks callbacksRecordingOnCancel(AtomicInteger counter) {
        return new AgentRunner.StreamingCallbacks(
                _ -> {},                                   // onInit: Consumer<Conversation>
                _ -> {},                                   // onToken: Consumer<String>
                _ -> {},                                   // onReasoning: Consumer<String>
                _ -> {},                                   // onStatus: Consumer<String>
                _ -> {},                                   // onToolCall: Consumer<ToolCallEvent>
                _ -> {},                                   // onComplete: Consumer<String>
                _ -> {},                                   // onError: Consumer<Exception>
                counter::incrementAndGet                   // onCancel: Runnable
        );
    }

    /**
     * Persist a minimal Agent so EventLogger's read of {@code agent.name}
     * inside checkCancelled doesn't NPE and the event-log write succeeds
     * within the test's JPA transaction. {@code modelProvider} and
     * {@code modelId} are non-null in the schema, hence the placeholder
     * values — checkCancelled doesn't read them, so the values are
     * irrelevant beyond satisfying the NOT NULL constraints.
     */
    private Agent persistedAgent() {
        var agent = new Agent();
        agent.name = "cancel-test-agent";
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        return agent;
    }
}
