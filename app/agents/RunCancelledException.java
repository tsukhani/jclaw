package agents;

/**
 * JCLAW-291: thrown by {@link AgentRunner}'s cooperative-cancellation
 * checkpoints when the {@link services.SubagentRegistry} flag for the
 * currently-running subagent has been flipped (by {@code /subagent kill} or
 * the admin SubagentRuns page's kill button).
 *
 * <p>Distinct from generic {@link RuntimeException} so the spawn-tool's
 * outer catch can tell "operator cancelled, registry already stamped
 * KILLED" apart from "run blew up, mark FAILED + announce." Catchers
 * MUST NOT overwrite the run row's terminal status when this fires — the
 * kill path beat them to it.
 *
 * <p>JCLAW-414: also reused by the task tool-loop's cooperative-cancel
 * checkpoint ({@link AgentRunner#checkTaskRunCancel}); use the
 * {@code (runId, entity)} constructor there so the message reads "Task run …"
 * rather than "Subagent run …".
 *
 * <p>Replaces the prior {@code Thread.interrupt()} kill mechanism, which
 * closed H2's MVStore FileChannel out from under the JVM mid-write (the
 * JDK NIO contract: an interrupt during a blocked channel I/O closes
 * the channel). See {@link services.SubagentRegistry#kill} for the full
 * post-mortem.
 */
public class RunCancelledException extends RuntimeException {

    private final Long runId;

    public RunCancelledException(Long runId) {
        super("Subagent run " + runId + " cancelled by operator");
        this.runId = runId;
    }

    /** JCLAW-414: neutral variant so non-subagent callers (task runs) get a
     *  correct message, e.g. {@code new RunCancelledException(id, "Task")}. */
    public RunCancelledException(Long runId, String entity) {
        super(entity + " run " + runId + " cancelled by operator");
        this.runId = runId;
    }

    public Long runId() { return runId; }
}
