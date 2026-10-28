import agents.ToolContext;
import models.SubagentRun;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.SubagentSpawnTool;
import tools.SubagentSpawnTool.SyncRunOutcome;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The outstanding-by-scope registry {@code subagent_yield all=true} collects from.
 *
 * <p>{@code SubagentAsyncRunner} is package-private in {@code tools} and these tests live in the
 * default package, so its private {@code track} is reached via reflection. Run ids and the task
 * scope are negative, so nothing collides with a concurrently-running spawn under the parallel
 * TestEngine.
 */
class SubagentAsyncRunnerTest extends UnitTest {

    @Test
    void collectingARunTakesOnlyThatRunOutOfItsScope() throws Exception {
        Method track = Class.forName("tools.SubagentAsyncRunner")
                .getDeclaredMethod("track", Long.class, String.class);
        track.setAccessible(true);
        long scopeTask = -System.nanoTime();
        long target = scopeTask - 1;
        long other = scopeTask - 2;
        var targetOutcome = outcome(track.invoke(null, target, "task:" + scopeTask));
        var otherOutcome = outcome(track.invoke(null, other, "task:" + scopeTask));

        try {
            assertEquals(List.of(other, target), ToolContext.withScope(null, scopeTask,
                    SubagentSpawnTool::outstandingForCurrentScope), "both runs are outstanding, in run id order");

            targetOutcome.complete(new SyncRunOutcome("TARGET_DONE", SubagentRun.Status.COMPLETED,
                    "TARGET_DONE", null, false, false));
            assertEquals("{\"results\":[{\"run_id\":\"" + target + "\",\"reply\":\"TARGET_DONE\",\"status\":\"COMPLETED\"}],"
                    + "\"count\":1}", SubagentSpawnTool.awaitAsyncOutcomes(List.of(target)));

            assertEquals(List.of(other), ToolContext.withScope(null, scopeTask,
                    SubagentSpawnTool::outstandingForCurrentScope), "the collected run left the scope, the other stayed");
        } finally {
            otherOutcome.complete(new SyncRunOutcome("", SubagentRun.Status.COMPLETED, "", null, false, false));
            SubagentSpawnTool.awaitAsyncOutcomes(List.of(target, other));
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SyncRunOutcome> outcome(Object tracked) {
        return (CompletableFuture<SyncRunOutcome>) tracked;
    }
}
