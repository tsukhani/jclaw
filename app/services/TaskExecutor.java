package services;

import agents.AgentRunner;
import agents.TaskRunSink;
import models.Agent;
import models.Task;
import models.TaskRun;

import java.time.Instant;
import java.util.List;
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
     * Per-task cap on persisted run history. Only the most recent
     * {@code MAX_RUNS_PER_TASK} fires of any single Task are retained; older
     * {@code task_run} rows (and their transcripts) are pruned right after each
     * new run opens, so a frequently-recurring task can't grow the table
     * without bound. Operators keep recent per-fire history; durable audit
     * lives in the structured event log, not the transcript.
     */
    public static final int MAX_RUNS_PER_TASK = 10;

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

        // Cap persisted run history to the most recent MAX_RUNS_PER_TASK fires.
        // Best-effort and in its own transaction (the run above is already
        // committed), so a prune hiccup never fails the fire that just opened.
        pruneRunHistory(task.id);

        // JCLAW-414: register the in-flight run so an operator cancel
        // (POST /api/task-runs/{id}/cancel) can flip its cooperative-
        // cancellation flag, which the tool loop polls at its checkpoints.
        // The finally below clears the slot on every terminal outcome —
        // normal completion, failure (rethrown), or cancel.
        TaskRunRegistry.register(run.id);
        try {
            // JCLAW-21: lifecycle audit — TASK_STARTED bookmark. Operator
            // monitoring (JCLAW-22) reads these to render "running" pills
            // without depending on the heartbeat to catch up. Sibling
            // events COMPLETED (below) and FAILED (in JClawFailureHandler)
            // bracket the fire.
            TaskLifecycleEvents.recordStarted(task, run);

            var sink = new TaskRunSink(run);
            sink.onStart();

            // Reminder short-circuit: payloadType="reminder" tasks are
            // operator-visible nudges, not agent work. The description IS the
            // delivered text verbatim — no LLM round, no tool loop, no
            // transcript turns. Closing the TaskRun with the description as
            // outputSummary keeps the monitoring UI consistent (a one-row run
            // whose content is the reminder body) and lets dispatchDelivery
            // route the same string through {@link ReminderDispatcher}.
            if (isReminder(task)) {
                String body = task.description != null && !task.description.isBlank()
                        ? task.description : task.name;
                sink.onComplete(body);
                return finalizeRun(task, run);
            }

            if (!driveAgentLoop(task, sink)) {
                return run;
            }

            return finalizeRun(task, run);
        } finally {
            TaskRunRegistry.unregister(run.id);
        }
    }

    /**
     * True when the task's {@code payloadType} marks it as a reminder.
     * Stable single-point check so the firing-time branch and the
     * delivery-time routing decision can stay in lockstep without
     * comparing strings in two places.
     */
    public static boolean isReminder(Task task) {
        return task != null && "reminder".equalsIgnoreCase(task.payloadType);
    }

    /**
     * JCLAW-260: resolve the user prompt for an agent fire. A blank
     * description falls back to the task name (unchanged from before).
     * For a genuine agent task an ordered step list in {@code description}
     * is flattened into a numbered prompt via
     * {@link TaskSteps#flattenForPrompt}. {@code noAgent} tasks (a shell
     * script, or a verbatim-delivery body) are exempt — their description
     * is used as-is, never numbered. Reminders never reach here; they
     * short-circuit in {@link #runTask} with their description delivered
     * verbatim.
     */
    public static String resolveAgentPrompt(Task task) {
        if (task.description == null || task.description.isBlank()) return task.name;
        var prompt = task.noAgent ? task.description : TaskSteps.flattenForPrompt(task.description);
        return appendToolDeliveryDirective(prompt, task);
    }

    /**
     * JCLAW-419: when the task declares {@code tool:<name>} delivery, append a
     * deterministic instruction so the agent actually calls that tool to deliver
     * its output — making the typed {@code delivery} field <em>drive</em>
     * execution rather than merely label it. Only agent tasks are affected:
     * {@code noAgent} tasks (script / verbatim) and reminders don't run a tool
     * loop, and CHANNEL / NONE delivery are handled post-run by
     * {@link #dispatchDelivery} (the dispatcher) or not at all.
     */
    private static String appendToolDeliveryDirective(String prompt, Task task) {
        if (task.noAgent || isReminder(task)) return prompt;
        var spec = DeliverySpec.parse(task.delivery);
        if (spec.kind() != DeliverySpec.Kind.TOOL || spec.tool().isBlank()) return prompt;
        return prompt + "\n\nWhen you have produced the final output, deliver it by calling the `"
                + spec.tool() + "` tool.";
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
     * Cap {@code taskId}'s persisted run history at {@link #MAX_RUNS_PER_TASK}
     * most recent fires: delete the transcript rows first (FK order), then the
     * run rows, keeping the newest by {@code startedAt} ({@code id} as a stable
     * tiebreaker for same-instant fires). Best-effort — runs in its own short
     * transaction and swallows-with-warn any failure, since this is housekeeping
     * that must never break the fire that triggered it. Public so a focused unit
     * test can exercise the cap without driving a full agent loop.
     */
    public static void pruneRunHistory(Long taskId) {
        try {
            Tx.run(() -> {
                var em = play.db.jpa.JPA.em();
                @SuppressWarnings("unchecked")
                List<Long> keepIds = em.createQuery(
                        "SELECT r.id FROM TaskRun r WHERE r.task.id = :tid "
                                + "ORDER BY r.startedAt DESC, r.id DESC")
                        .setParameter("tid", taskId)
                        .setMaxResults(MAX_RUNS_PER_TASK)
                        .getResultList();
                // Fewer rows than the cap means there is nothing to prune.
                if (keepIds.size() < MAX_RUNS_PER_TASK) return null;
                em.createQuery("DELETE FROM TaskRunMessage m "
                                + "WHERE m.taskRun.task.id = :tid AND m.taskRun.id NOT IN :keep")
                        .setParameter("tid", taskId)
                        .setParameter("keep", keepIds)
                        .executeUpdate();
                em.createQuery("DELETE FROM TaskRun r "
                                + "WHERE r.task.id = :tid AND r.id NOT IN :keep")
                        .setParameter("tid", taskId)
                        .setParameter("keep", keepIds)
                        .executeUpdate();
                return null;
            });
        } catch (Exception e) {
            EventLogger.warn("task", null, null,
                    "TaskExecutor.pruneRunHistory: failed to cap run history for Task id %d: %s"
                            .formatted(taskId, e.getMessage()));
        }
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
                return new PreparedFire(t.agent, resolveAgentPrompt(t));
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
        } catch (agents.RunCancelledException e) {
            // JCLAW-414: operator cancelled this fire mid-run. The cancel
            // endpoint already flipped the flag and stamps the run CANCELLED
            // for instant UI; close here too (idempotent — onCancelled only
            // acts on a still-RUNNING row) so the run is terminal even if the
            // endpoint's write lagged. Do NOT rethrow — a clean operator
            // action, not a failure for JClawFailureHandler to classify, and
            // not a reschedule trigger.
            sink.onCancelled("Cancelled by operator");
            EventLogger.info("task", null, null,
                    "TaskExecutor.runTask: task run %d cancelled by operator".formatted(sink.taskRunId()));
            return false;
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
        // sink.onComplete-written value rather than recomputing. When a
        // delivery spec is configured (and this isn't a reminder, which
        // never invokes the message tool), fold the dedup signal — did the
        // fire push via the `message` tool? — into this same Tx so it
        // shares the connection with the re-read instead of opening a
        // second transaction in dispatchDelivery.
        boolean deliveryConfigured = task.delivery != null && !task.delivery.isBlank()
                && !isReminder(task);
        var resolved = Tx.run(() -> {
            var c = (TaskRun) TaskRun.findById(run.id);
            if (c == null) return new Resolved(null, false);
            // Only when we'll actually dispatch delivery below — keeps the
            // LIKE count off the no-delivery / non-COMPLETED paths exactly
            // as before, when it lived inside dispatchDelivery.
            boolean dedup = deliveryConfigured
                    && c.status == TaskRun.Status.COMPLETED
                    && deliveredViaMessageTool(c.id);
            return new Resolved(c, dedup);
        });
        var closed = resolved.run();
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
            dispatchDelivery(task, closed, resolved.deliveredViaMessageTool());
            // Auto-delete a one-shot reminder that opted in — the fire delivered,
            // so the reminder has served its purpose. Runs after delivery so the
            // nudge is sent first; the Notification row survives (not a FK).
            autoDeleteIfRequested(task);
        }
        return closed;
    }

    /**
     * Carries the re-read TaskRun plus the dedup signal computed in the
     * same Tx (whether the fire pushed via the {@code message} tool), so
     * {@link #dispatchDelivery} doesn't have to open a second transaction
     * for the LIKE count.
     */
    private record Resolved(TaskRun run, boolean deliveredViaMessageTool) {}

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
    private static void dispatchDelivery(Task task, TaskRun closed, boolean deliveredViaMessageTool) {
        var spec = task.delivery;
        if (spec == null || spec.isBlank()) {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.NOT_REQUESTED, null, null);
            return;
        }
        // JCLAW-419: only CHANNEL delivery is routed through the dispatcher.
        // TOOL delivery is performed by the agent in-run (resolveAgentPrompt
        // injects a directive to call the tool), and the explicit "none"
        // literal is no-delivery — both leave the run NOT_REQUESTED here so a
        // tool: spec never reaches the channel switch and mis-dispatches.
        if (DeliverySpec.parse(spec).kind() != DeliverySpec.Kind.CHANNEL) {
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
        // since tool names are uniqued. Reminders skip the dedup — their
        // fire path doesn't invoke the message tool, so the scan can't
        // produce a meaningful signal. The signal is precomputed in
        // finalizeRun's re-read Tx (see {@link Resolved}) so the LIKE count
        // shares that transaction rather than opening its own.
        if (deliveredViaMessageTool) {
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
        // Route through ReminderDispatcher for reminder tasks (writes a
        // Notification row on web, frames the body for telegram).
        // Non-reminders go through the standard chat-message dispatcher.
        // Both need a live EntityManager (TelegramBinding lookup,
        // Conversation.findById, ConversationService.appendMessage, the
        // Notification row save) so each invocation is wrapped in its own
        // short Tx — the db-scheduler carrier thread has no inherited one.
        var result = isReminder(task)
                ? Tx.run(() -> ReminderDispatcher.dispatch(task, closed, spec, content))
                : Tx.run(() -> DeliveryDispatcher.dispatchSpec(task.agent, spec, content));
        if (result.ok()) {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.DELIVERED, spec, null);
            TaskLifecycleEvents.recordDelivered(task, closed, spec);
        } else {
            stampDelivery(closed.id, TaskRun.DeliveryStatus.NOT_DELIVERED, spec, result.reason());
            TaskLifecycleEvents.recordDeliveryFailed(task, closed, spec, result.reason());
        }
    }

    /**
     * Whether the fire pushed via the {@code message} tool. Runs inside the
     * caller's transaction (finalizeRun's re-read Tx) — no own {@code Tx.run}
     * wrapper — so the LIKE count shares that connection.
     */
    private static boolean deliveredViaMessageTool(Long runId) {
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

    /**
     * JCLAW: auto-delete a one-shot reminder after a successful fire when it
     * opted in ({@link Task#autoDeleteOnComplete}). Hard-deletes the task and
     * its run history; the user-visible {@link models.Notification} (the nudge)
     * is left intact because {@code Notification.sourceTaskId} is a plain id,
     * not a foreign key. Gated on reminder + one-shot type, so a recurring
     * reminder (which never completes) and every regular task (audit history
     * preserved) are untouched. Best-effort — a failure is logged, never
     * propagated into the fire path.
     */
    private static void autoDeleteIfRequested(Task task) {
        if (!isReminder(task) || !task.autoDeleteOnComplete) return;
        if (task.type != Task.Type.IMMEDIATE && task.type != Task.Type.SCHEDULED) return;
        try {
            Tx.run(() -> {
                var em = play.db.jpa.JPA.em();
                em.createQuery("DELETE FROM TaskRunMessage m WHERE m.taskRun.task.id = :tid")
                        .setParameter("tid", task.id).executeUpdate();
                em.createQuery("DELETE FROM TaskRun r WHERE r.task.id = :tid")
                        .setParameter("tid", task.id).executeUpdate();
                em.createQuery("DELETE FROM Task t WHERE t.id = :tid")
                        .setParameter("tid", task.id).executeUpdate();
                em.flush();
                return null;
            });
            // One-shots are reaped by db-scheduler's OnCompleteRemove, but cancel
            // is idempotent and closes any race that leaves a scheduled_tasks row.
            TaskSchedulingService.cancel(task.id);
            EventLogger.info("TASK_MGMT_AUTO_DELETE",
                    task.agent != null ? task.agent.name : null, null,
                    "Reminder '%s' (id=%d) auto-deleted after a successful fire"
                            .formatted(task.name, task.id));
        } catch (Exception e) {
            EventLogger.warn("task", null, null,
                    "TaskExecutor.autoDeleteIfRequested: failed for Task id %d: %s"
                            .formatted(task.id, e.getMessage()));
        }
    }
}
