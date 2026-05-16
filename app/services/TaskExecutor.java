package services;

import agents.TaskRunSink;
import models.Task;
import models.TaskRun;

import java.time.Instant;

/**
 * Orchestrates one fire of a {@link Task}. Creates the {@link TaskRun},
 * wraps it in a {@link TaskRunSink}, drives the agent loop through the
 * sink, and returns the closed TaskRun for downstream delivery dispatch.
 *
 * <p><b>Status: skeleton.</b> This first implementation exercises the
 * TaskRun + TaskRunSink lifecycle end-to-end with a placeholder assistant
 * response. Real agent invocation (LLM call, tool loop) lands in a
 * follow-up JCLAW-21 commit once {@link agents.AgentRunner} gains a
 * sink-based entry point — the existing AgentRunner entry points all
 * require a {@link models.Conversation}, which Tasks explicitly do not
 * manufacture.
 *
 * <p>The TaskRun row is created in its own short transaction so
 * db-scheduler's heartbeat monitor and the monitoring UI can observe a
 * row in {@code RUNNING} state even if the body throws partway through.
 * Each {@code sink.append...} call commits its own short Tx as well, so
 * a crash mid-fire leaves the already-written turns visible in the
 * abandoned TaskRun rather than rolling back the whole transcript.
 *
 * <p>Part of JCLAW-21's Tasks foundation. Caller will be
 * {@code TaskExecutionHandler} (subsequent JCLAW-21 commit), which is the
 * db-scheduler {@code Job} body that fires when a {@link Task}'s
 * scheduled time arrives.
 */
public final class TaskExecutor {

    private TaskExecutor() {}

    /**
     * Run one fire of {@code task} and return the persisted TaskRun.
     * The returned TaskRun has a terminal status (COMPLETED or FAILED)
     * and a populated outputSummary on success or error on failure.
     *
     * <p>Does NOT dispatch delivery — that's the
     * {@link TaskExecutionHandler}'s next step (subsequent JCLAW-21
     * commit) which inspects the TaskRun's outcome and routes it via the
     * delivery layer (JCLAW-295 sibling story).
     */
    public static TaskRun runTask(Task task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        if (task.id == null) {
            throw new IllegalArgumentException("task must be persisted before being run");
        }

        TaskRun run = Tx.run(() -> {
            var r = new TaskRun();
            r.task = (Task) Task.findById(task.id);
            r.startedAt = Instant.now();
            r.status = TaskRun.Status.RUNNING;
            r.save();
            return r;
        });

        var sink = new TaskRunSink(run);
        sink.onStart();
        try {
            // PLACEHOLDER BODY — exercises the sink lifecycle end-to-end with a
            // stub assistant response so JCLAW-22 monitoring renders real rows
            // and downstream commits can inspect a fully-formed TaskRun. The
            // real LLM/tool loop replaces the body below once AgentRunner
            // gains a sink-based entry point.
            String userPrompt = task.description != null && !task.description.isBlank()
                    ? task.description : task.name;
            sink.appendUserMessage(userPrompt, null);
            sink.appendAssistantMessage(
                    "[TaskExecutor: real agent invocation pending follow-up JCLAW-21 commit]",
                    null);
            sink.onComplete("Task fired (skeleton); see JCLAW-21 for agent integration progress");
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sink.onFailure(msg);
            throw e;
        }

        return Tx.run(() -> (TaskRun) TaskRun.findById(run.id));
    }
}
