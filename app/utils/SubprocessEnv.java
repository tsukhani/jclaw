package utils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-779: the single source of truth for the environment a spawned child
 * process inherits.
 *
 * <p>Every subprocess JClaw launches — the {@code exec} shell tool, MCP stdio
 * servers, and external ACP coding harnesses — must <strong>not</strong> inherit
 * the host JVM's secret-bearing environment. {@link ProcessBuilder} otherwise
 * hands the child a full copy of {@link System#getenv()}, which in a real
 * deployment carries {@code PLAY_SECRET}, provider API keys ({@code AWS_*},
 * {@code ANTHROPIC_*}, {@code OPENAI_*}, …), and other credentials. A
 * prompt-injected agent that reaches any spawn site could then exfiltrate them
 * with a plain {@code env}/{@code printenv}.
 *
 * <p>The secret filter (extracted from {@code tools.ShellExecTool}) is
 * centralized here so every spawn site builds its child env the same way: a
 * filtered copy of the host env (PATH/HOME/LANG survive; anything
 * {@link #isSensitive} matches is dropped), with any operator-supplied config
 * env layered on top (operator config wins).
 */
public final class SubprocessEnv {

    private static final Set<String> SENSITIVE_NAME_PATTERNS = Set.of(
            "key", "secret", "token", "password", "credential"
    );
    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "AWS_", "ANTHROPIC_", "OPENAI_", "GOOGLE_", "AZURE_"
    );

    private SubprocessEnv() {}

    /**
     * True when {@code name} names a secret-bearing variable that must be
     * stripped from any spawned child's environment. Matches a set of sensitive
     * prefixes (case-insensitive, {@code AWS_}/{@code ANTHROPIC_}/…) or a set of
     * sensitive substrings ({@code key}/{@code secret}/{@code token}/…).
     */
    public static boolean isSensitive(String name) {
        var upper = name.toUpperCase(Locale.ROOT);
        for (var prefix : SENSITIVE_PREFIXES) {
            if (upper.startsWith(prefix)) return true;
        }
        var lower = name.toLowerCase(Locale.ROOT);
        for (var pattern : SENSITIVE_NAME_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * A secret-filtered copy of the host environment ({@link System#getenv()}):
     * non-sensitive vars (PATH, HOME, LANG, …) are carried through; anything
     * {@link #isSensitive} matches is dropped. The returned map is mutable so
     * callers can layer their own entries on top.
     */
    public static Map<String, String> filteredHostEnv() {
        var env = new LinkedHashMap<String, String>();
        for (var entry : System.getenv().entrySet()) {
            if (!isSensitive(entry.getKey())) {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        return env;
    }

    /**
     * Strip the inherited host secrets from {@code pb}'s environment — the
     * secret-bearing vars {@link ProcessBuilder} would otherwise hand the child.
     * Use at spawn sites that pass no operator config env.
     */
    public static void apply(ProcessBuilder pb) {
        apply(pb, null);
    }

    /**
     * Strip the inherited host secrets from {@code pb}'s environment, then layer
     * {@code configEnv} on top (operator config wins). A fresh
     * {@link ProcessBuilder} starts with a copy of {@link System#getenv()}, so
     * this leaves the child the secret-filtered host env (PATH/HOME survive; the
     * {@code AWS_*}/{@code ANTHROPIC_*}/{@code *_TOKEN}/… keys are removed) plus
     * the operator-provided entries. A null or empty {@code configEnv} yields
     * just the filtered host env.
     */
    public static void apply(ProcessBuilder pb, Map<String, String> configEnv) {
        pb.environment().keySet().removeIf(SubprocessEnv::isSensitive);
        if (configEnv != null) pb.environment().putAll(configEnv);
    }
}
