package tools;

import java.util.List;

/**
 * JCLAW-659: the pluggable seam over an external coding-harness CLI (the
 * {@code runtime:"acp"} runtime). Each harness — Pi, Claude Code, Codex, or a
 * generic line-oriented CLI — ships one adapter that knows (a) how to launch
 * the process for a task, (b) how to turn a line of its output into a common
 * {@link HarnessEvent}, and (c) what its output stream is capable of.
 *
 * <p>Adapters are registered against a harness id ({@code pi|claude|codex|
 * generic}) in {@link SubagentSpawnTool} and selected at spawn time by the
 * {@code subagent.acp.harness} config key. This story only defines the seam and
 * wires selection; concrete adapters land in later JCLAW-657 stories.
 */
public interface HarnessAdapter {

    /**
     * Build the full argv to launch the harness for one task. Given the
     * operator-configured {@code baseCommand} (e.g. {@code ["claude", "-p"]})
     * and the {@code task} text, an adapter decides whether the task rides as a
     * trailing argument or is delivered on stdin — return the argv to exec, and
     * if the task is passed on stdin simply return {@code baseCommand} unchanged.
     *
     * @param baseCommand the operator-configured command, already whitespace-split
     * @param task        the instruction for the child
     * @return the argv to hand {@link ProcessBuilder}
     */
    List<String> launchArgs(List<String> baseCommand, String task);

    /**
     * Parse one line of harness stdout into a normalized {@link HarnessEvent}, or
     * {@code null} to drop the line — do not surface it on the rails, persist it,
     * or fold it into the reply. Returning {@code null} lets a chatty harness
     * (e.g. Claude Code's session/hook/SSE-frame lines, or prose already emitted
     * incrementally as tokens) filter its own noise so the live monitor and the
     * persisted transcript show only meaningful steps (JCLAW-657 finding B).
     *
     * <p>Tolerant by contract otherwise: a line that is not valid JSON (or that
     * the adapter doesn't recognize) becomes a {@link HarnessEvent#STEP} event
     * whose {@code text} is the raw line — the parser never throws on unexpected
     * input.
     */
    HarnessEvent parse(String line);

    /** What this harness's output stream supports. */
    Capabilities capabilities();

    /**
     * Declares an adapter's transport capabilities.
     *
     * @param streaming     the harness emits incremental {@code token}/{@code
     *                      tool_call} events as it works (json/rpc modes), vs a
     *                      single batch of output at the end
     * @param bidirectional the harness accepts follow-up input mid-run (a
     *                      long-lived RPC session), vs one-shot task-in /
     *                      output-out
     */
    record Capabilities(boolean streaming, boolean bidirectional) {}

    /**
     * JCLAW-670: the adapter's conservative default permission flags, appended
     * after {@link #launchArgs} unless the operator overrides them via
     * {@code subagent.acp.permissionArgs} (whitespace-split; the literal
     * {@code none} disables defaults without adding flags). Adapters whose CLI
     * exposes no restriction surface return an empty list and document that.
     */
    default List<String> defaultPermissionArgs() {
        return List.of();
    }

    /**
     * JCLAW-672: the $HOME-relative (or absolute) paths this harness must read
     * to run — its own config/state/credentials — bound read-only back into an
     * otherwise-sealed home when the sandbox is enabled. Everything not listed
     * stays inaccessible. Empty by default (a harness needing nothing from HOME).
     */
    default List<String> sandboxAllowances() {
        return List.of();
    }
}
