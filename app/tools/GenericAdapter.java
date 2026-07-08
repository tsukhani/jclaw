package tools;

import java.util.List;

/**
 * JCLAW-660: the fallback {@link HarnessAdapter} for a plain, line-oriented CLI
 * that speaks no structured protocol. It launches the operator-configured
 * command verbatim (task delivered on stdin) and treats every stdout line as an
 * opaque {@link HarnessEvent#STEP}; the newline-joined concatenation of those
 * lines is the child's reply. This is the {@code generic} harness and the
 * default when {@code subagent.acp.harness} is unset.
 */
public final class GenericAdapter implements HarnessAdapter {

    /** Launch the base command unchanged — the task rides on stdin. */
    @Override
    public List<String> launchArgs(List<String> baseCommand, String task) {
        return baseCommand;
    }

    /** Every line is an opaque progress/output step carrying the raw text. */
    @Override
    public HarnessEvent parse(String line) {
        return new HarnessEvent(HarnessEvent.STEP, line, null);
    }

    /** A dumb CLI: no incremental protocol, no follow-up input — line-tail only. */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false);
    }
}
