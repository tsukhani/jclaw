package services;

import agents.AgentRunner;
import agents.TaskRunSink;
import models.Agent;
import models.Task;
import models.TaskRun;

import java.time.Instant;
import java.util.Objects;

/**
 * Orchestrates one fire of a {@link Task}. Creates the {@link TaskRun},
 * wraps it in a {@link TaskRunSink}, drives the agent loop via
 * {@link AgentRunner#runForTask}, and returns the closed TaskRun for
 * downstream delivery dispatch.
 *
 * <p>The TaskRun row is created in its own short transaction so
 * db-scheduler's heartbeat monitor and the monitoring UI can observe a
 * row in {@code RUNNING} state even if the body throws partway through.
 * Each {@code sink.append...} call inside {@link TaskRunSink} commits
 * its own short Tx as well, so a crash mid-fire leaves the
 * already-written turns visible in the abandoned TaskRun rather than
 * rolling back the whole transcript.
 *
 * <p>The agent loop runs <em>outside</em> any JPA transaction —
 * {@link AgentRunner#runForTask} does its own short Tx around each
 * persistence write but releases the JDBC connection during the LLM
 * call and tool execution. This matters because a single task fire
 * can spend tens of seconds on the wire; holding a connection through
 * would starve the connection pool.
 *
 * <p>Part of JCLAW-21's Tasks foundation. Caller is
 * {@code TaskExecutionHandler} (subsequent JCLAW-21 commit), which is
 * the db-scheduler {@code Job} body that fires when a {@link Task}'s
 * scheduled time arrives.
 */
public final class TaskExecutor {

    private TaskExecutor() {}

    /**
     * Resolved-in-Tx capture of the task fields the agent loop needs.
     * Pulling them out in a short transaction guarantees the
     * eager-loaded {@link Task#agent} reference is materialised before
     * the outer body crosses a Tx boundary — the agent loop reads
     * {@code agent.name}, {@code agent.modelProvider}, etc. on what
     * may be a detached entity once the surrounding Tx commits, but
     * those are primitive/String fields that remain accessible.
     */
    private record PreparedFire(Agent agent, String userPrompt) {}

    /**
     * Run one fire of {@code task} and return the persisted TaskRun.
     * The returned TaskRun has a terminal status (COMPLETED or FAILED)
     * and a populated outputSummary on success or error on failure.
     *
     * <p>Does NOT dispatch delivery — that's the
     * {@code TaskExecutionHandler}'s next step (subsequent JCLAW-21
     * commit) which inspects the TaskRun's outcome and routes it via
     * the delivery layer (separate sibling story under the JCLAW-237
     * epic).
     */
    public static TaskRun runTask(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before being run");
        }

        // Open the TaskRun in RUNNING state before any LLM/tool work so
        // observers (monitoring UI, db-scheduler heartbeat) see a row to
        // attach to even if the body throws partway through. The re-query
        // can race with deletion (Fixtures.deleteDatabase in tests, or an
        // operator cancel + delete in prod) — handle null by returning
        // null so TaskExecutionHandler can drop the orphan via
        // defaultCompletion (same path as its own pre-flight null check).
        final TaskRun run = Tx.run(() -> {
            var resolvedTask = (Task) Task.findById(task.id);
            if (resolvedTask == null) return null;
            var r = new TaskRun();
            r.task = resolvedTask;
            r.startedAt = Instant.now();
            r.status = TaskRun.Status.RUNNING;
            r.save();
            return r;
        });
        if (run == null) {
            EventLogger.warn("task", null, null,
                    "TaskExecutor.runTask: Task id %d disappeared between handler resolution and TaskRun creation; skipping"
                            .formatted(task.id));
            return null;
        }

        // JCLAW-21: lifecycle audit — TASK_STARTED bookmark. Operator
        // monitoring (JCLAW-22) reads these to render "running" pills
        // without depending on the heartbeat to catch up. Sibling
        // events COMPLETED (below) and FAILED (in JClawFailureHandler)
        // bracket the fire.
        TaskLifecycleEvents.started(task, run);

        var sink = new TaskRunSink(run);
        sink.onStart();
        try {
            // Resolve the executing agent + user prompt inside a short Tx.
            // task.agent is @ManyToOne (EAGER by default), so the captured
            // reference stays usable for the agent-loop reads even after
            // this Tx commits — only primitive/String fields are touched
            // downstream, none of which need a live persistence context.
            var prep = Tx.run(() -> {
                var t = (Task) Task.findById(task.id);
                if (t == null) return null;
                var prompt = (t.description != null && !t.description.isBlank())
                        ? t.description : t.name;
                return new PreparedFire(t.agent, prompt);
            });
            if (prep == null) {
                // Task deleted after the TaskRun row was opened. Mark the
                // run FAILED so it surfaces in monitoring, and let the
                // scheduler reap the orphan via the normal completion path.
                String msg = "Task disappeared mid-execution";
                EventLogger.warn("task", null, null,
                        "TaskExecutor.runTask: Task id %d deleted after RUNNING row created; marking TaskRun FAILED"
                                .formatted(task.id));
                sink.onFailure(msg);
                return run;
            }

            // Drive the agent loop through the same machinery chat uses —
            // SystemPromptAssembler, ToolCallLoopRunner, ParallelToolExecutor —
            // routed entirely through this sink so writes land in
            // task_run_message rather than conversation_message.
            var outcome = AgentRunner.runForTask(prep.agent(), prep.userPrompt(), sink);

            // The assistant's final reply becomes the outputSummary the
            // monitoring UI surfaces. truncated lives on the TaskRun's
            // task_run_message row already (sink.appendAssistantMessage
            // inside runForTask stamped it); the summary is the surface
            // operators see at a glance.
            sink.onComplete(outcome.content());
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sink.onFailure(msg);
            // NB: we do NOT emit TASK_FAILED here. Whether this failure is
            // permanent or transient is decided by JClawFailureHandler
            // upstream — emitting at this point would fire a "permanent
            // fail" lifecycle bookmark for every transient retry too. The
            // handler emits TASK_FAILED only when its Decision is Fail.
            throw e;
        }

        // Re-read the closed TaskRun so durationMs reflects the
        // sink.onComplete-written value rather than recomputing.
        var closed = Tx.run(() -> (TaskRun) TaskRun.findById(run.id));
        if (closed != null && closed.status == TaskRun.Status.COMPLETED) {
            // Mark one-shot Tasks (IMMEDIATE/SCHEDULED) as COMPLETED so
            // the operator UI shows the terminal state. Recurring Tasks
            // (CRON/INTERVAL) intentionally stay PENDING — the Task row
            // is the recurrence config; the scheduled_tasks row carries
            // the next fire time, and per-fire history lives in
            // task_run rows. Symmetric with JClawFailureHandler's
            // FAILED write on terminal failure (we don't pair this
            // with PENDING resets — once a one-shot succeeds it's done).
            if (task.type == Task.Type.IMMEDIATE || task.type == Task.Type.SCHEDULED) {
                Tx.run(() -> {
                    var fresh = (Task) Task.findById(task.id);
                    if (fresh != null && fresh.status == Task.Status.PENDING) {
                        fresh.status = Task.Status.COMPLETED;
                        fresh.save();
                    }
                    return null;
                });
            }
            TaskLifecycleEvents.completed(task, closed,
                    closed.durationMs != null ? closed.durationMs : 0L);
        }
        return closed;
    }
}
