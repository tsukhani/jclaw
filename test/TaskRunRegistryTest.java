import agents.AgentRunner;
import agents.RunCancelledException;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TaskRunRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-414: unit contract for {@link TaskRunRegistry} and the
 * {@link AgentRunner#checkTaskRunCancel} cooperative-cancel checkpoint.
 *
 * <p>The registry is a process-global static, and play1's TestEngine runs
 * unit + functional tests concurrently, so this test never calls
 * {@code clear()} — it uses high sentinel ids it owns end-to-end and asserts
 * with {@code contains}, never equality on the whole keyset (a concurrent
 * real task fire could legitimately have its own entry).
 */
class TaskRunRegistryTest extends UnitTest {

    // Sentinel ids unlikely to collide with a real TaskRun row id in a test run.
    private static final Long ID = 990_414L;
    private static final Long OTHER = 990_415L;

    @Test
    void registerThenFlagThenUnregister() {
        try {
            TaskRunRegistry.register(ID);
            assertFalse(TaskRunRegistry.isCancelled(ID), "fresh registration is not cancelled");
            assertTrue(TaskRunRegistry.activeRunIds().contains(ID), "registered id is active");

            assertTrue(TaskRunRegistry.requestCancel(ID), "requestCancel flips a registered entry");
            assertTrue(TaskRunRegistry.isCancelled(ID), "flag is observable after requestCancel");
        } finally {
            TaskRunRegistry.unregister(ID);
        }
        assertFalse(TaskRunRegistry.isCancelled(ID), "unregistered id is not cancelled");
        assertFalse(TaskRunRegistry.activeRunIds().contains(ID), "unregistered id is gone");
    }

    @Test
    void requestCancelOnUnregisteredReturnsFalse() {
        // OTHER was never registered.
        assertFalse(TaskRunRegistry.requestCancel(OTHER), "no entry → nothing to flip");
        assertFalse(TaskRunRegistry.isCancelled(OTHER), "no entry → not cancelled");
    }

    @Test
    void nullArgsAreNoOps() {
        assertFalse(TaskRunRegistry.isCancelled(null));
        assertFalse(TaskRunRegistry.requestCancel(null));
        assertDoesNotThrow(() -> TaskRunRegistry.register(null));
        assertDoesNotThrow(() -> TaskRunRegistry.unregister(null));
    }

    @Test
    void checkpointThrowsOnlyWhenFlagged() {
        // Null + unregistered are no-ops (chat / subagent paths pass null).
        assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(null));
        assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(OTHER));

        try {
            TaskRunRegistry.register(ID);
            // Registered-but-unflagged → loop proceeds.
            assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(ID));

            TaskRunRegistry.requestCancel(ID);
            var ex = assertThrows(RunCancelledException.class,
                    () -> AgentRunner.checkTaskRunCancel(ID),
                    "a flagged run trips the checkpoint");
            assertEquals(ID, ex.runId(), "the exception carries the cancelled run id");
        } finally {
            TaskRunRegistry.unregister(ID);
        }
    }
}
