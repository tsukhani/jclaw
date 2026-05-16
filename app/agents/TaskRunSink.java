package agents;

import models.MessageRole;
import models.TaskRun;
import models.TaskRunMessage;
import services.AttachmentService;
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
    private final Instant startedAt;
    private final AtomicInteger turnIndex = new AtomicInteger(0);

    public TaskRunSink(TaskRun taskRun) {
        if (taskRun == null || taskRun.id == null) {
            throw new IllegalArgumentException(
                    "taskRun must be persisted before being wrapped in TaskRunSink");
        }
        this.taskRunId = taskRun.id;
        this.startedAt = taskRun.startedAt;
    }

    public Long taskRunId() {
        return taskRunId;
    }

    @Override
    public void appendUserMessage(String content, List<AttachmentService.Input> attachments) {
        // Attachments don't apply to task runs — there's no external upload
        // path that feeds a task fire. The parameter exists on the interface
        // for ConversationSink's benefit.
        appendInTx(MessageRole.USER, content, null, null, null, null, null, false);
    }

    @Override
    public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                       String reasoning, boolean truncated) {
        appendInTx(MessageRole.ASSISTANT, content, toolCalls, null, null,
                usageJson, reasoning, truncated);
    }

    @Override
    public void appendToolResult(String toolCallId, String result, String structuredJson) {
        // {@code toolCallId} identifies which assistant tool-call this row
        // answers; matches the data layout that ConversationService.appendToolResult
        // uses (the id goes into the tool_results column).
        appendInTx(MessageRole.TOOL, result, null, toolCallId, structuredJson,
                null, null, false);
    }

    private void appendInTx(MessageRole role, String content, String toolCalls, String toolResults,
                             String toolResultStructured, String usageJson, String reasoning,
                             boolean truncated) {
        int idx = turnIndex.getAndIncrement();
        Tx.run(() -> {
            var taskRun = (TaskRun) TaskRun.findById(taskRunId);
            if (taskRun == null) return null;  // defensive: TaskRun deleted mid-run
            var msg = new TaskRunMessage();
            msg.taskRun = taskRun;
            msg.turnIndex = idx;
            msg.role = role;
            msg.content = content;
            msg.toolCalls = toolCalls;
            msg.toolResults = toolResults;
            msg.toolResultStructured = toolResultStructured;
            msg.usageJson = usageJson;
            msg.reasoning = reasoning;
            msg.truncated = truncated;
            msg.save();
            return null;
        });
    }

    @Override
    public void onComplete(String outputSummary) {
        Tx.run(() -> {
            var fresh = (TaskRun) TaskRun.findById(taskRunId);
            if (fresh == null) return null;
            fresh.completedAt = Instant.now();
            fresh.durationMs = Duration.between(fresh.startedAt, fresh.completedAt).toMillis();
            fresh.status = TaskRun.Status.COMPLETED;
            fresh.outputSummary = outputSummary;
            fresh.save();
            return null;
        });
    }

    @Override
    public void onFailure(String error) {
        Tx.run(() -> {
            var fresh = (TaskRun) TaskRun.findById(taskRunId);
            if (fresh == null) return null;
            fresh.completedAt = Instant.now();
            fresh.durationMs = Duration.between(fresh.startedAt, fresh.completedAt).toMillis();
            fresh.status = TaskRun.Status.FAILED;
            fresh.error = error;
            fresh.save();
            return null;
        });
    }

    @Override
    public String executionLabel() {
        return "task-run:" + taskRunId;
    }
}
