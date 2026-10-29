import agents.ToolContext;
import agents.TurnCancellation;
import models.SubagentRun;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.SubagentSpawnTool;
import tools.SubagentSpawnTool.SyncRunOutcome;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final String STOPPED = "{\"results\":[],\"count\":0,\"stopped\":true,"
            + "\"note\":\"The turn was stopped while waiting; every run stays collectable by the next subagent_yield.\"}";

    @Test
    void stoppingTheTurnEndsTheWaitAndLeavesEveryRunCollectable() throws Exception {
        long scopeTask = -System.nanoTime();
        long done = scopeTask - 1;
        long running = scopeTask - 2;
        var doneOutcome = track(done, "task:" + scopeTask);
        var runningOutcome = track(running, "task:" + scopeTask);
        doneOutcome.complete(completed("DONE_REPLY"));
        var turnCancel = new AtomicBoolean(false);
        try {
            var waiting = CompletableFuture.supplyAsync(() -> ToolContext.withScope(null, scopeTask,
                    turnCancel::get, turnCancel, () -> SubagentSpawnTool.awaitAsyncOutcomes(List.of(done, running))));
            TurnCancellation.cancel(turnCancel);
            assertEquals(STOPPED, waiting.get(10, TimeUnit.SECONDS));

            assertEquals(List.of(running, done), ToolContext.withScope(null, scopeTask,
                    SubagentSpawnTool::outstandingForCurrentScope), "both runs went back, the finished one included");
            runningOutcome.complete(completed("RUNNING_REPLY"));
            assertEquals("{\"results\":[{\"run_id\":\"" + done + "\",\"reply\":\"DONE_REPLY\",\"status\":\"COMPLETED\"},"
                    + "{\"run_id\":\"" + running + "\",\"reply\":\"RUNNING_REPLY\",\"status\":\"COMPLETED\"}],\"count\":2}",
                    SubagentSpawnTool.awaitAsyncOutcomes(List.of(done, running)));
        } finally {
            runningOutcome.complete(completed(""));
            SubagentSpawnTool.awaitAsyncOutcomes(List.of(done, running));
        }
    }

    @Test
    void aTurnAlreadyStoppedHandsBackRunsThatHaveFinished() throws Exception {
        long scopeTask = -System.nanoTime();
        long run = scopeTask - 1;
        track(run, "task:" + scopeTask).complete(completed("REPLY"));
        var turnCancel = new AtomicBoolean(false);
        TurnCancellation.cancel(turnCancel);
        try {
            assertEquals(STOPPED, ToolContext.withScope(null, scopeTask, turnCancel::get, turnCancel,
                    () -> SubagentSpawnTool.awaitAsyncOutcomes(List.of(run))));
            assertEquals(List.of(run), ToolContext.withScope(null, scopeTask,
                    SubagentSpawnTool::outstandingForCurrentScope), "a stopped turn persists nothing, so the reply stays collectable");
        } finally {
            SubagentSpawnTool.awaitAsyncOutcomes(List.of(run));
        }
    }

    @Test
    void stoppingATaskFireEndsASingleRunWaitAndLeavesTheRunCollectable() throws Exception {
        long scopeTask = -System.nanoTime();
        long run = scopeTask - 1;
        var runOutcome = track(run, "task:" + scopeTask);
        var turnCancel = new AtomicBoolean(false);
        try {
            var waiting = CompletableFuture.supplyAsync(() -> ToolContext.withScope(null, scopeTask,
                    turnCancel::get, turnCancel, () -> SubagentSpawnTool.awaitAsyncOutcome(run)));
            TurnCancellation.cancel(turnCancel);
            assertEquals("Error: the turn was stopped while awaiting async subagent " + run
                    + "; it stays collectable by the next subagent_yield.", waiting.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(run), ToolContext.withScope(null, scopeTask,
                    SubagentSpawnTool::outstandingForCurrentScope));
        } finally {
            runOutcome.complete(completed(""));
            SubagentSpawnTool.awaitAsyncOutcomes(List.of(run));
        }
    }

    private static CompletableFuture<SyncRunOutcome> track(long runId, String scopeKey) throws Exception {
        Method track = Class.forName("tools.SubagentAsyncRunner")
                .getDeclaredMethod("track", Long.class, String.class);
        track.setAccessible(true);
        return outcome(track.invoke(null, runId, scopeKey));
    }

    private static SyncRunOutcome completed(String reply) {
        return new SyncRunOutcome(reply, SubagentRun.Status.COMPLETED, reply, null, false, false);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SyncRunOutcome> outcome(Object tracked) {
        return (CompletableFuture<SyncRunOutcome>) tracked;
    }
}
