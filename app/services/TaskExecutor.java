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
 * {@link AgentRunner#runForTask}, and on success routes the assistant's
 * final reply through {@link DeliveryDispatcher#dispatchSpec} when the
 * Task has a {@link Task#delivery} spec configured.
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
 * {@link TaskExecutionHandler}, the db-scheduler {@code Job} body that
 * fires when a {@link Task}'s scheduled time arrives.
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
     * The returned TaskRun has a terminal status (COMPLETED or FAILED),
     * a populated {@code outputSummary} on success / {@code error} on
     * failure, and — on success when {@link Task#delivery} is set — a
     * populated {@code deliveryStatus} / {@code deliveryTarget} /
     * {@code deliveryError} reflecting the result of dispatching
     * {@code outputSummary} through {@link DeliveryDispatcher#dispatchSpec}
     * (see {@link #dispatchDelivery}). Delivery failure does NOT fail
     * the TaskRun — the body succeeded; only the post-completion push
     * to the configured channel did not.
     */
    public static TaskRun runTask(Task task) {
        Objects.requireNonNull(task, "task");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before being run");
        }

        // Open the TaskRun in RUNNING state before any LLM/tool work so
        // observers (monitoring UI, db-scheduler heartbeat) see a row to
        // attach to even if the body throws partway through.
        final TaskRun run = openRunningTaskRun(task);
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
        TaskLifecycleEvents.recordStarted(task, run);

        var sink = new TaskRunSink(run);
        sink.onStart();
        if (!driveAgentLoop(task, sink)) {
            return run;
        }

        return finalizeRun(task, run);
    }

    /**
     * Open the TaskRun in RUNNING state. The re-query can race with deletion
     * (Fixtures.deleteDatabase in tests, or an operator cancel + delete in
     * prod) — return null so TaskExecutionHandler can drop the orphan via
     * defaultCompletion (same path as its own pre-flight null check).
     */
    private static TaskRun openRunningTaskRun(Task task) {
        return Tx.run(() -> {
            var resolvedTask = (Task) Task.findById(task.id);
            if (resolvedTask == null) return null;
            var r = new TaskRun();
            r.task = resolvedTask;
            r.startedAt = Instant.now();
            r.status = TaskRun.Status.RUNNING;
            r.save();
            return r;
        });
    }

    /**
     * Resolve the executing agent + user prompt, then drive the agent loop.
     * Returns true on success/normal completion, false when the Task was
     * deleted mid-execution (in which case the sink has already been failed).
     * Rethrows on RuntimeException so JClawFailureHandler can classify.
     */
    private static boolean driveAgentLoop(Task task, TaskRunSink sink) {
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
                return false;
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
            return true;
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
    }

    /**
     * Re-read the closed TaskRun, mark one-shot Tasks COMPLETED on success,
     * emit the TASK_COMPLETED lifecycle event, and route the assistant's
     * final reply through {@link DeliveryDispatcher#dispatchSpec} when
     * {@link Task#delivery} is set.
     */
    private static TaskRun finalizeRun(Task task, TaskRun run) {
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
                markTaskCompleted(task.id);
            }
            TaskLifecycleEvents.recordCompleted(task, closed,
                    closed.durationMs != null ? closed.durationMs : 0L);
            dispatchDelivery(task, closed);
        }
        return closed;
    }

    /**
     * Push the closed TaskRun's {@link TaskRun#outputSummary} through
     * {@link DeliveryDispatcher#dispatchSpec} when {@link Task#delivery}
     * is set, and stamp the result onto the TaskRun's delivery columns.
     *
     * <p>Skipped silently (with {@link TaskRun.DeliveryStatus#NOT_REQUESTED})
     * when no delivery spec is configured — that's the legacy default for
     * Tasks created via the HTTP API without a {@code delivery} field, or
     * created in headless contexts where there's no chat to push back to.
     * Empty or whitespace-only output is also skipped: there's nothing
     * useful to deliver, and pushing an empty message to a chat is noise.
     *
     * <p>Delivery failure does NOT fail the TaskRun — the body succeeded,
     * and operators can see the delivery breakdown via the TaskRun's
     * {@link TaskRun#deliveryStatus} / {@link TaskRun#deliveryError}
     * columns plus the TASK_DELIVERY_FAILED lifecycle event.
     */
    private static void dispatchDelivery(Task task, TaskRun closed) {
        var spec = task.delivery;
        if (spec == null || spec.isBlank()) {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.NOT_REQUESTED, null, null);
            return;
        }
        // Dedup: if the fire-time agent already pushed via the `message` tool
        // (e.g. a Radarr-monitor-style progress update), the assistant's final
        // reply was a follow-up to that push, not the user-facing payload —
        // auto-delivering it would land a duplicate in the chat. Detection is
        // a substring scan of TaskRunMessage.tool_calls JSON because the JSON
        // shape (`{"function":{"name":"message"…}}`) is stable across providers
        // (see {@link llm.LlmTypes.ToolCall}). False positives would need
        // another tool literally named "message", which the registry disallows
        // since tool names are uniqued.
        if (alreadyDeliveredViaMessageTool(closed.id)) {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.NOT_REQUESTED, spec,
                    "Skipped auto-delivery: fire called the 'message' tool directly");
            return;
        }
        var content = closed.outputSummary;
        if (content == null || content.isBlank()) {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.NOT_DELIVERED, spec,
                    "TaskRun produced no output to deliver");
            TaskLifecycleEvents.recordDeliveryFailed(task, closed, spec,
                    "TaskRun produced no output to deliver");
            return;
        }
        // dispatchSpec → dispatchTelegram / dispatchWeb / etc. all need a live
        // EntityManager (TelegramBinding lookup, Conversation.findById for the
        // web target, ConversationService.appendMessage for the web append).
        // The db-scheduler carrier thread has no inherited Tx, so wrap.
        var result = Tx.run(() -> DeliveryDispatcher.dispatchSpec(task.agent, spec, content));
        if (result.ok()) {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.DELIVERED, spec, null);
            TaskLifecycleEvents.recordDelivered(task, closed, spec);
        } else {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.NOT_DELIVERED, spec, result.reason());
            TaskLifecycleEvents.recordDeliveryFailed(task, closed, spec, result.reason());
        }
    }

    private static boolean alreadyDeliveredViaMessageTool(Long runId) {
        return Boolean.TRUE.equals(Tx.run(() -> {
            var em = play.db.jpa.JPA.em();
            // Match the JSON substring produced by GSON's compact encoding of
            // the FunctionCall record: `"name":"message"`. Single-call rows
            // dominate (MessageHydrator.parseToolCalls deserialises into a
            // single ToolCall), so the LIKE wildcard count is bounded.
            var count = (Long) em.createQuery(
                    "SELECT COUNT(m) FROM TaskRunMessage m "
                            + "WHERE m.taskRun.id = :runId "
                            + "AND m.toolCalls LIKE :pattern")
                    .setParameter("runId", runId)
                    .setParameter("pattern", "%\"name\":\"" + tools.MessageTool.TOOL_NAME + "\"%")
                    .getSingleResult();
            return count != null && count > 0;
        }));
    }

    private static void stampDelivery(Long runId, TaskRun.DeliveryStatus status,
                                      String target, String error) {
        Tx.run(() -> {
            var fresh = (TaskRun) TaskRun.findById(runId);
            if (fresh == null) return null;
            fresh.deliveryStatus = status;
            fresh.deliveryTarget = target;
            fresh.deliveryError = error;
            fresh.save();
            return null;
        });
    }

    private static void markTaskCompleted(Long taskId) {
        Tx.run(() -> {
            var fresh = (Task) Task.findById(taskId);
            if (fresh != null && fresh.status == Task.Status.PENDING) {
                fresh.status = Task.Status.COMPLETED;
                fresh.save();
            }
            return null;
        });
    }
}
