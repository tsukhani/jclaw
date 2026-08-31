package agents;

import models.MessageRole;
import models.TaskRun;
import models.TaskRunMessage;
import play.Logger;
import services.AttachmentService;
import services.EventLogger;
import services.Tx;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AgentExecutionSink} backed by a {@link TaskRun}. Each
 * {@code append...} call writes a {@link TaskRunMessage} row carrying the
 * same data shape AgentRunner currently writes to {@code Message} via
 * {@link ConversationSink}. Lifecycle hooks ({@link #onComplete},
 * {@link #onFailure}) close the TaskRun row with timing, status, and
 * outcome.
 *
 * <p>The caller (TaskExecutor, future JCLAW-21 commit) creates and
 * persists the {@link TaskRun} row, then wraps it here. This sink does
 * not create or own the TaskRun — it just writes to it. Mirrors the
 * {@link ConversationSink} pattern where the Conversation is constructed
 * by the caller.
 *
 * <p>Each {@code append...} runs in its own short {@link Tx} so the
 * transcript is durable incrementally — a crash mid-fire leaves the
 * already-written turns in the DB rather than the whole fire as an
 * all-or-nothing transaction. db-scheduler's heartbeat-based recovery
 * will re-fire the task; the abandoned TaskRun keeps the partial
 * transcript as an audit record.
 *
 * <p>Attachments on user messages and structured tool-result payloads
 * are accepted at the interface level but ignored on this sink:
 * task fires don't have external file uploads, and the structured-payload
 * UI rendering happens in the PeekPanel which queries
 * {@link TaskRunMessage#toolResultStructured} directly.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public class TaskRunSink implements AgentExecutionSink {

    private final Long taskRunId;
    private final AtomicInteger turnIndex = new AtomicInteger(0);

    public TaskRunSink(TaskRun taskRun) {
        if (taskRun == null || taskRun.id == null) {
            throw new IllegalArgumentException(
                    "taskRun must be persisted before being wrapped in TaskRunSink");
        }
        this.taskRunId = taskRun.id;
    }

    public Long taskRunId() {
        return taskRunId;
    }

    @Override
    public void appendUserMessage(String content, List<AttachmentService.Input> attachments) {
        // Attachments don't apply to task runs — there's no external upload
        // path that feeds a task fire. The parameter exists on the interface
        // for ConversationSink's benefit.
        appendInTx(new MessageFields(MessageRole.USER, content, null, null, null, null, null, false));
    }

    @Override
    public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                       String reasoning, boolean truncated) {
        appendInTx(new MessageFields(MessageRole.ASSISTANT, content, toolCalls, null, null,
                usageJson, reasoning, truncated));
    }

    @Override
    public void appendToolResult(String toolCallId, String result, String structuredJson) {
        // {@code toolCallId} identifies which assistant tool-call this row
        // answers; matches the data layout that ConversationService.appendToolResult
        // uses (the id goes into the tool_results column).
        appendInTx(new MessageFields(MessageRole.TOOL, result, null, toolCallId, structuredJson,
                null, null, false));
    }

    /** Column-shaped bundle mirroring the {@link TaskRunMessage} write surface. */
    private record MessageFields(MessageRole role, String content, String toolCalls, String toolResults,
                                  String toolResultStructured, String usageJson, String reasoning,
                                  boolean truncated) {}

    private void appendInTx(MessageFields fields) {
        int idx = turnIndex.getAndIncrement();
        Tx.run(() -> {
            var taskRun = (TaskRun) TaskRun.findById(taskRunId);
            if (taskRun == null) return null;  // defensive: TaskRun deleted mid-run
            var msg = new TaskRunMessage();
            msg.taskRun = taskRun;
            msg.turnIndex = idx;
            msg.role = fields.role();
            msg.content = fields.content();
            msg.toolCalls = fields.toolCalls();
            msg.toolResults = fields.toolResults();
            msg.toolResultStructured = fields.toolResultStructured();
            msg.usageJson = fields.usageJson();
            msg.reasoning = fields.reasoning();
            msg.truncated = fields.truncated();
            msg.save();
            return null;
        });
    }

    /**
     * Persist a terminal status, tolerating a store that is already gone.
     *
     * <p>JCLAW-1144: db-scheduler interrupts its executor threads at shutdown, and an
     * interrupt inside H2 file I/O closes the store's FileChannel for every caller. The
     * close-out write then fails, and letting that escape replaces the run's real error with
     * an MVStoreException and reaches db-scheduler as "Unhandled exception" plus "Failed
     * while completing execution". Nothing is lost by swallowing it: the row stays RUNNING
     * and BootConsistencyCheck reconciles it to FAILED on the next boot.
     *
     * <p>Swallowed only while shutting down. At any other time a failed close-out write is a
     * real fault and must propagate. Deliberately not an isShuttingDown() early-return: the
     * write usually succeeds even mid-shutdown, and skipping it would discard a terminal
     * status that would have persisted fine.
     */
    private void persistTerminalStatus(String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            if (!EventLogger.isShuttingDown()) throw e;
            // play.Logger, not EventLogger: this path exists because the DB is unreachable.
            Logger.warn("TaskRunSink: %s for run %d not persisted during shutdown (%s); "
                    + "BootConsistencyCheck will reconcile it", what, taskRunId, e.toString());
        }
    }

    @Override
    public void onComplete(String outputSummary) {
        persistTerminalStatus("completion", () -> Tx.run(() -> {
            var fresh = (TaskRun) TaskRun.findById(taskRunId);
            if (fresh == null) return null;
            fresh.completedAt = Instant.now();
            fresh.durationMs = Duration.between(fresh.startedAt, fresh.completedAt).toMillis();
            fresh.status = TaskRun.Status.COMPLETED;
            fresh.outputSummary = outputSummary;
            fresh.save();
            return null;
        }));
    }

    @Override
    public void onFailure(String error) {
        persistTerminalStatus("failure", () -> Tx.run(() -> {
            var fresh = (TaskRun) TaskRun.findById(taskRunId);
            if (fresh == null) return null;
            fresh.completedAt = Instant.now();
            fresh.durationMs = Duration.between(fresh.startedAt, fresh.completedAt).toMillis();
            fresh.status = TaskRun.Status.FAILED;
            fresh.error = error;
            fresh.save();
            return null;
        }));
    }

    /**
     * JCLAW-414: close the run as {@link TaskRun.Status#CANCELLED} when the tool
     * loop bailed on an operator cancel ({@link agents.RunCancelledException}).
     * Idempotent on the terminal status: only a still-RUNNING row is transitioned,
     * so this never clobbers the CANCELED stamp the cancel endpoint already wrote
     * (it sets the row CANCELED immediately for instant UI feedback). Whichever
     * writer reaches a RUNNING row first sets the timing.
     */
    public void onCancelled(String note) {
        persistTerminalStatus("cancellation", () -> Tx.run(() -> {
            var fresh = (TaskRun) TaskRun.findById(taskRunId);
            if (fresh == null || fresh.status != TaskRun.Status.RUNNING) return null;
            fresh.completedAt = Instant.now();
            fresh.durationMs = Duration.between(fresh.startedAt, fresh.completedAt).toMillis();
            fresh.status = TaskRun.Status.CANCELLED;
            fresh.outputSummary = note;
            fresh.save();
            return null;
        }));
    }

    @Override
    public String executionLabel() {
        return "task-run:" + taskRunId;
    }
}
