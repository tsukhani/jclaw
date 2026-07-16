package services;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Probes the host PATH for external coding-harness CLIs usable by the ACP
 * runtime ({@code runtime=acp} / {@code subagent.acp.command}) — {@code claude},
 * {@code pi}, {@code codex}, {@code gemini}, {@code opencode}, plus any
 * operator-added custom commands ({@link #CUSTOM_COMMANDS_KEY}). Surfaced in
 * Settings → Subagents so the operator can pick a detected harness instead of
 * typing the command by hand. Fresh on each call (a quick {@code --version}
 * per binary); only queried when the Subagents panel opens.
 */
public final class AcpHarnessProbe {

    private static final Gson GSON = new Gson();

    /** Config key holding operator-added custom harness commands as a JSON array
     *  of strings (e.g. {@code ["aider --message", "amp -x"]}). Each is probed
     *  like a built-in and, when its binary resolves, shows as a custom chip. */
    public static final String CUSTOM_COMMANDS_KEY = "subagent.acp.customCommands";

    /** A known ACP harness: its adapter id (matches SubagentAcpRunner's
     *  adapters / ACP_HARNESS_IDS), the CLI binary to probe, the suggested
     *  {@code subagent.acp.command}, and a display name. "generic" is a
     *  fallback adapter, not a probeable binary, so it's not listed. */
    public record Harness(String id, String binary, String command, String displayName) {}

    /** Probe result for one harness. {@code harness} is the {@code
     *  subagent.acp.harness} adapter id to write when this chip is picked — its
     *  own id for a built-in, {@code "generic"} for a custom command (no
     *  dedicated adapter). {@code custom} distinguishes operator-added chips
     *  (removable) from the built-in catalog. */
    public record Detected(String id, String displayName, String command, String harness,
                           boolean available, String reason, boolean custom) {}

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

    /** Probe every known harness binary on PATH, then every operator-added
     *  custom command. */
    public static List<Detected> probeAll() {
        var f = forced;
        if (f != null) return f;
        var out = new ArrayList<Detected>();
        HARNESSES.forEach(h -> out.add(probeBuiltIn(h)));
        customCommands().forEach(cmd -> out.add(probeCustom(cmd)));
        return List.copyOf(out);
    }

    /**
     * Probe a custom command and, when its binary resolves, persist it so it
     * shows as a chip. The stored list is deduped; an unavailable command is
     * returned (with the reason) but NOT stored. Returns the probe result.
     */
    public static Detected addCustom(String command) {
        var trimmed = command == null ? "" : command.strip();
        if (trimmed.isEmpty()) {
            return new Detected("custom:", "", "", "generic", false, "empty command", true);
        }
        var probe = probeCustom(trimmed);
        if (probe.available()) {
            var list = new ArrayList<>(customCommands());
            if (!list.contains(trimmed)) {
                list.add(trimmed);
                ConfigService.set(CUSTOM_COMMANDS_KEY, GSON.toJson(list));
            }
        }
        return probe;
    }

    /** Remove an operator-added custom command (no-op if absent). */
    public static void removeCustom(String command) {
        var trimmed = command == null ? "" : command.strip();
        var list = new ArrayList<>(customCommands());
        if (list.remove(trimmed)) {
            ConfigService.set(CUSTOM_COMMANDS_KEY, GSON.toJson(list));
        }
    }

    private static Detected probeBuiltIn(Harness h) {
        var r = ExecutableProbeSupport.probeOnPath(h.binary(), "--version", "AcpHarnessProbe",
                " — install it to use runtime=acp with this harness");
        return new Detected(h.id(), h.displayName(), h.command(), h.id(), r.available(), r.reason(), false);
    }

    /** Probe a custom command by its first token (the binary); the operator's
     *  flags may await values, so only the binary is version-checked. Runs via
     *  the generic adapter, so {@code harness = "generic"}. */
    private static Detected probeCustom(String command) {
        var binary = command.strip().split("\\s+", 2)[0];
        var r = ExecutableProbeSupport.probeOnPath(binary, "--version", "AcpHarnessProbe",
                " — the harness binary must be installed on PATH");
        return new Detected("custom:" + command, binary, command, "generic", r.available(), r.reason(), true);
    }

    private static List<String> customCommands() {
        var json = ConfigService.get(CUSTOM_COMMANDS_KEY, null);
        if (json == null || json.isBlank()) return List.of();
        try {
            var out = new ArrayList<String>();
            JsonParser.parseString(json).getAsJsonArray().forEach(e -> {
                if (!e.isJsonNull()) out.add(e.getAsString());
            });
            return out;
        } catch (RuntimeException e) {  // malformed stored JSON — treat as none
            return List.of();
        }
    }
}
