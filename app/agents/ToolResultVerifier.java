package agents;

import com.google.gson.JsonParser;
import services.ConfigService;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a tool result before the model consumes it (JCLAW-836, stage 1).
 *
 * <p>Everything here is in-process: no model call, no I/O, no clock. That is the
 * point. The epic's efficiency NFR names this story as its largest regression
 * risk — a critic-model pass on every tool return adds roughly one real TTFT per
 * tool round, measured at ~1.5 s against a harness that accounts for 0.2% of a
 * turn — so the deterministic checks land first and get measured alone. A check
 * that catches a class of failure for free beats a critic call that catches the
 * same class for a model round.
 *
 * <h2>Observe, do not intervene</h2>
 *
 * <p>This stage deliberately does not alter, block, or annotate what the model
 * sees. A tool that failed should still hand the model its error — the model
 * needs it to react — and rewriting results before anyone knows how often they
 * are bad would be changing behavior on a guess. The verdict is recorded as a
 * metric so the failure rate becomes a number first. What to DO about a failing
 * verdict is stage 2's decision, made against that evidence.
 *
 * <h2>What the verdicts mean</h2>
 *
 * <h2>Two layers</h2>
 *
 * <p>Generic checks first — they know nothing about any particular tool, so they can
 * only judge how a result is <em>framed</em>: blank, or prefixed with the codebase's
 * "Error…" convention. Then the tool's own {@code postConditionFailure}, which knows
 * what its fields mean. That second layer exists because the first has a blind spot
 * with teeth: {@code exec} returns {@code {"exitCode":1,…}} on a failed command, which
 * is neither blank nor "Error…"-prefixed, so a failed command scored a clean pass until
 * stage 1.5 added it.
 *
 * <p>{@link Verdict#EMPTY} and {@link Verdict#ERROR_REPORTED} are the two generic shapes
 * that actually occur. The error convention is real rather than assumed: the
 * registry returns "Error: Unknown tool …" and "Error executing tool …", and
 * tools follow it themselves — {@code web_fetch} returns "Error fetching URL:
 * HTTP 404 …", which a live eval sweep recorded on a turn that scored a clean
 * pass (JCLAW-891). Matching on that convention is a heuristic and is treated as
 * one: it feeds a counter, not a control-flow decision.
 *
 * <p>{@link Verdict#MALFORMED_JSON} applies only to a result the tool itself
 * declared as JSON by emitting {@code structuredJson}. Note that the model never
 * sees that field — it is the UI's payload — so this catches a rendering defect
 * rather than an agent-quality one. It is here because it is free, not because it
 * is the AC's "JSON schema validation for structured tool outputs": no tool
 * declares an output schema today, and only {@code web_search} emits structured
 * JSON at all, so that AC has nothing to validate against until output schemas
 * exist. Recorded on the ticket rather than silently reinterpreted.
 */
public final class ToolResultVerifier {

    /** {@code verification.enabled} — master switch; the checks cost nothing, so default on. */
    public static final String CFG_ENABLED = "verification.enabled";

    /**
     * {@code verification.skipTools} — comma-separated tool names to leave
     * unverified. Present because the AC asks for per-tool-type configurability;
     * empty by default, since a free check has no reason to be switched off until
     * one is shown to misfire on a particular tool.
     */
    public static final String CFG_SKIP_TOOLS = "verification.skipTools";

    private ToolResultVerifier() {}

    /** What a check concluded about one tool result. */
    public enum Verdict {
        /** Nothing detectably wrong. Says nothing about whether the answer is CORRECT — that needs stage 2. */
        OK,
        /** The tool ran and returned nothing. A blank tool result reliably confuses the next model round. */
        EMPTY,
        /** The result follows the codebase's "Error…" convention, so the tool ran and reported failure. */
        ERROR_REPORTED,
        /** The tool emitted a structuredJson payload that does not parse. */
        MALFORMED_JSON,
        /**
         * The tool's own post-condition says the result is a failure (JCLAW-836,
         * stage 1.5). Distinct from {@link #ERROR_REPORTED} on purpose: that one means
         * the tool announced its failure in prose the model can read, this one means
         * the tool did NOT announce it and only the tool's own check found it. Those
         * are different defects, and a critic is only worth paying for on the second.
         */
        POSTCONDITION_FAILED,
        /** Verification was switched off for this tool, or globally. Not a pass — an absence of one. */
        SKIPPED
    }

    /** A verdict and the one-line reason behind it, for the metric label and for logs. */
    public record Verification(Verdict verdict, String reason) {
        public boolean failed() {
            return verdict != Verdict.OK && verdict != Verdict.SKIPPED;
        }

        static Verification ok() { return new Verification(Verdict.OK, null); }
        static Verification skipped() { return new Verification(Verdict.SKIPPED, null); }
    }

    /**
     * Verify one dispatched tool result.
     *
     * <p>Only DISPATCHED results are meaningful here: a call the registry refused
     * as unknown or not-enabled never reached a tool, so there is no tool output to
     * judge and counting it as a verification failure would double-count a
     * different defect (JCLAW-883 already separates those).
     */
    public static Verification verify(String toolName, ToolRegistry.ToolResult result) {
        if (result == null || !result.dispatched()) return Verification.skipped();
        if (!ConfigService.getBoolean(CFG_ENABLED, true)) return Verification.skipped();
        if (parseSkipTools(ConfigService.get(CFG_SKIP_TOOLS, "")).contains(normalize(toolName))) {
            return Verification.skipped();
        }
        return check(toolName, result);
    }

    /**
     * The checks themselves, with configuration already resolved. Separate from
     * {@link #verify} so the verdicts can be tested without writing the two config
     * keys — this project runs test classes concurrently, so a test that flipped a
     * process-global setting would change behavior under whatever else is running.
     *
     * <p>Generic checks first, the tool's own post-condition last. A tool that
     * already said {@code "Error…"} should be {@link Verdict#ERROR_REPORTED}: it
     * announced the failure to the model, which is materially different from a
     * failure only its post-condition can see.
     */
    public static Verification check(String toolName, ToolRegistry.ToolResult result) {
        var text = result.text();
        if (text == null || text.isBlank()) {
            return new Verification(Verdict.EMPTY, "tool returned no text");
        }
        if (looksLikeError(text)) {
            return new Verification(Verdict.ERROR_REPORTED, firstLine(text));
        }
        var structured = result.structuredJson();
        if (structured != null && !structured.isBlank() && !parses(structured)) {
            return new Verification(Verdict.MALFORMED_JSON, "structuredJson does not parse");
        }
        return postCondition(toolName, result);
    }

    /**
     * Ask the tool about its own result. An unregistered name yields a pass rather
     * than a failure — MCP tools come and go with their server, and a name that no
     * longer resolves says nothing about the result it produced.
     *
     * <p>A throw from a tool's own check is deliberately NOT swallowed here. It
     * propagates to the caller's guard in {@code ParallelToolExecutor}, which logs it
     * and drops the count. Catching it silently would leave a broken post-condition
     * reporting clean verdicts forever.
     */
    private static Verification postCondition(String toolName, ToolRegistry.ToolResult result) {
        var tool = ToolRegistry.lookupTool(toolName);
        if (tool == null) return Verification.ok();
        return tool.postConditionFailure(result)
                .map(reason -> new Verification(Verdict.POSTCONDITION_FAILED, reason))
                .orElseGet(Verification::ok);
    }

    /**
     * The convention this codebase already follows for tool failures, matched at
     * the start of the result only. Deliberately not a substring search: a search
     * result or a fetched page legitimately containing the word "error" is not a
     * failed tool call, and a check that fired on that would make the metric
     * useless within a day.
     */
    private static boolean looksLikeError(String text) {
        var head = text.stripLeading();
        return head.regionMatches(true, 0, "Error", 0, 5)
                && (head.length() == 5 || !Character.isLetter(head.charAt(5)));
    }

    private static boolean parses(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (RuntimeException _) {
            return false;
        }
    }

    private static String firstLine(String text) {
        var line = text.stripLeading();
        var nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    public static Set<String> parseSkipTools(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(ToolResultVerifier::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
