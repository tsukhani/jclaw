package tools;

import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool.SyncRunOutcome;

import java.time.Instant;

/**
 * JCLAW-677: all {@link SubagentRun} audit-row DB access for the spawn flow,
 * extracted from {@link SubagentSpawnTool}: the RUNNING insert, the terminal
 * updates (sync + async), and the lookups the run paths need.
 */
final class SubagentRunStore {

    private SubagentRunStore() {}

    /** Insert the SubagentRun audit row in its own short Tx and return the
     *  generated id. JCLAW-326: persist the spawn-time {@code label} on the
     *  row so {@code conversation_list} can filter / display without re-parsing
     *  per-run announce-message metadata JSON. */
    static Long insertSubagentRun(Long parentAgentId, Long childAgentId,
                                  Long parentConvId, Long childConvId, String label) {
        return Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = Agent.findById(parentAgentId);
            run.childAgent = Agent.findById(childAgentId);
            run.parentConversation = Conversation.findById(parentConvId);
            run.childConversation = Conversation.findById(childConvId);
            run.label = label != null && !label.isBlank() ? label : null;
            // status defaults to RUNNING, startedAt populated by @PrePersist.
            run.save();
            return run.id;
        });
    }

    /**
     * Step 5: write the terminal SubagentRun update in its own short Tx.
     * JCLAW-271 / JCLAW-291: belt-and-suspenders against a race where the
     * kill flipped the row but our future.get returned a non-cancel
     * exception path first — re-check the row's status in DB before
     * overwriting.
     */
    static void persistTerminalRun(Long runId, SubagentRun.Status status, String outcome) {
        Tx.run(() -> {
            var fresh = (SubagentRun) SubagentRun.findById(runId);
            if (fresh != null && fresh.status != SubagentRun.Status.KILLED) {
                fresh.status = status;
                fresh.endedAt = Instant.now();
                fresh.outcome = outcome;
                fresh.save();
            }
        });
    }

    /**
     * Async variant of {@link #persistTerminalRun}: wraps the Tx in a
     * Throwable catch so a persistence failure logs but never aborts the
     * announce post. {@link SubagentRun#outcome} is the reply on COMPLETED
     * and the error reason otherwise — matches the synchronous path's
     * semantics.
     */
    @SuppressWarnings("java:S1181")
    static void persistAsyncTerminalRun(Long runId, SyncRunOutcome outcome) {
        final var finalStatus = outcome.terminalStatus();
        final var outcomeText = finalStatus == SubagentRun.Status.COMPLETED
                ? outcome.reply()
                : outcome.errorReason();
        try {
            Tx.run(() -> {
                var fresh = (SubagentRun) SubagentRun.findById(runId);
                if (fresh != null && fresh.status != SubagentRun.Status.KILLED) {
                    fresh.status = finalStatus;
                    fresh.endedAt = Instant.now();
                    fresh.outcome = outcomeText;
                    fresh.save();
                }
            });
        } catch (Throwable t) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL,
                    "Failed to persist terminal SubagentRun update for run " + runId
                            + ": " + t.getMessage());
        }
    }

    static String lookupAgentName(Long id) {
        if (id == null) return null;
        return Tx.run(() -> {
            var a = (Agent) Agent.findById(id);
            return Agent.nameOf(a);
        });
    }

    /**
     * JCLAW-424 (AC5): the child's most recent assistant message, captured on a
     * timeout so the parent can salvage partial work. Empty when the child timed
     * out before producing any response, or on any lookup error (defensive — a
     * partial-capture failure must never mask the timeout outcome itself).
     */
    @SuppressWarnings("java:S1181")
    static String capturePartialReply(Long runId) {
        if (runId == null) return "";
        try {
            return Tx.run(() -> {
                var run = (SubagentRun) SubagentRun.findById(runId);
                if (run == null || run.childConversation == null) return "";
                Message last = Message.find(
                        "conversation.id = ?1 and role = ?2 order by createdAt desc, id desc",
                        run.childConversation.id, MessageRole.ASSISTANT.value).first();
                return last != null && last.content != null ? last.content : "";
            });
        } catch (Throwable t) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL,
                    "Failed to capture partial reply for run " + runId + ": " + t.getMessage());
            return "";
        }
    }
}
