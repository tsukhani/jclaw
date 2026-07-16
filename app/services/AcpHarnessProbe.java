package services;

import java.util.List;

/**
 * Probes the host PATH for external coding-harness CLIs usable by the ACP
 * runtime ({@code runtime=acp} / {@code subagent.acp.command}) — {@code claude},
 * {@code pi}, {@code codex}, {@code gemini}, {@code opencode}. Surfaced in
 * Settings → Subagents so the operator can pick a detected harness instead of
 * typing the command by hand. Fresh on each call (a quick {@code --version}
 * per binary); only queried when the Subagents panel opens.
 */
public final class AcpHarnessProbe {

    /** A known ACP harness: its adapter id (matches SubagentAcpRunner's
     *  adapters), the CLI binary to probe, the suggested
     *  {@code subagent.acp.command}, and a display name. "generic" is a
     *  fallback adapter, not a probeable binary, so it's not listed. */
    public record Harness(String id, String binary, String command, String displayName) {}

    /** Probe result for one harness. */
    public record Detected(String id, String displayName, String command, boolean available, String reason) {}

    // Suggested commands are the harness's headless form with the task delivered
    // on STDIN (the generic/batch path runs the command verbatim and pipes the
    // task in) — so each must read its prompt from stdin, not expect it as a
    // trailing arg. gemini's -p triggers non-interactive mode and appends the
    // -p value to stdin; opencode run reads the piped message.
    public static final List<Harness> HARNESSES = List.of(
            new Harness("claude", "claude", "claude -p", "Claude Code"),
            new Harness("pi", "pi", "pi -p", "Pi"),
            new Harness("codex", "codex", "codex exec", "Codex"),
            new Harness("gemini", "gemini", "gemini -p", "Gemini CLI"),
            new Harness("opencode", "opencode", "opencode run", "opencode"));

    private static volatile List<Detected> forced;

    private AcpHarnessProbe() {}

    /** Test seam: force the probe result (or {@code null} to re-enable real
     *  probing). Real probing depends on the host PATH, so tests pin it. */
    public static void setForTest(List<Detected> results) {
        forced = results;
    }

    /** Probe every known harness binary on PATH. */
    public static List<Detected> probeAll() {
        var f = forced;
        if (f != null) return f;
        return HARNESSES.stream().map(AcpHarnessProbe::probe).toList();
    }

    private static Detected probe(Harness h) {
        var r = ExecutableProbeSupport.probeOnPath(h.binary(), "--version", "AcpHarnessProbe",
                " — install it to use runtime=acp with this harness");
        return new Detected(h.id(), h.displayName(), h.command(), r.available(), r.reason());
    }
}
