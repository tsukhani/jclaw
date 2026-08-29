package services.evals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import tools.SchemaKeys;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Scores one agent response against an {@link EvalCase}'s checks (JCLAW-875).
 *
 * <p>Pure and offline: no model call, no I/O, no clock. Every failure is a
 * sentence naming the check and what it saw, because a report that only says
 * "failed" sends the reader back to re-run the case by hand.
 */
public final class EvalScorer {

    /**
     * What the agent produced for one case: the answer, the tools it reached for in
     * call order, and {@code llmCalls} — the model calls the turn spent (the
     * {@code llm_call_count} segment JCLAW-882 adds), which is what
     * {@link EvalCheck.Kind#MAX_LLM_CALLS} asserts against.
     *
     * <p>{@code toolsCalled} holds only calls a tool actually ran; {@code toolsAttempted}
     * holds every name the model emitted, including ones it invented and ones the
     * agent was not granted (JCLAW-883). The checks score against {@code toolsCalled},
     * because that is what produced side effects and did the work — a live sweep once
     * recorded three "calls" to {@code httpFetch}, {@code http_fetch} and
     * {@code webSearch} on a turn that executed nothing at all. The difference between
     * the two lists is {@link #toolsRefused()}, itself a quality signal: an agent
     * guessing tool names is burning model calls to accomplish nothing.
     *
     * <p>{@code error} is non-null when the turn never produced an answer — the
     * provider was down, the run timed out, the agent threw (JCLAW-883). Carrying
     * the reason in the same record is what lets a live capture and a replayed
     * recording share one file format: the alternative, omitting the case
     * entirely, would reach the scorer as an indistinguishable "no response
     * recorded" and throw away the one detail someone debugging the sweep needs.
     * Older recordings have no {@code error} field and deserialize to null.
     */
    public record Response(String output, List<String> toolsCalled, List<String> toolsAttempted,
                           Map<String, List<String>> toolArgs, Map<String, List<String>> toolResults,
                           int llmCalls, String error) {

        public Response {
            output = output == null ? "" : output;
            toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
            // Older recordings carry only toolsCalled, and at that time it meant
            // "attempted" — every emitted call, dispatched or not. Defaulting the new
            // field to it preserves what those files actually recorded.
            toolsAttempted = toolsAttempted == null ? toolsCalled : List.copyOf(toolsAttempted);
            // Optional: only a recording that asserts on arguments needs to carry them,
            // which keeps a hand-written response file as short as it was.
            toolArgs = toolArgs == null ? Map.of() : Map.copyOf(toolArgs);
            toolResults = toolResults == null ? Map.of() : Map.copyOf(toolResults);
        }

        /** A turn that produced an answer, where every attempted call also ran. */
        public Response(String output, List<String> toolsCalled, int llmCalls) {
            this(output, toolsCalled, toolsCalled, Map.of(), Map.of(), llmCalls, null);
        }

        /** A turn where some attempts were refused: {@code toolsCalled} is the dispatched subset. */
        public Response(String output, List<String> toolsCalled, List<String> toolsAttempted, int llmCalls) {
            this(output, toolsCalled, toolsAttempted, Map.of(), Map.of(), llmCalls, null);
        }

        /** Argument JSONs of the dispatched calls to {@code tool}, in call order. */
        public List<String> argsFor(String tool) {
            return toolArgs.getOrDefault(tool, List.of());
        }

        /** Result text of the dispatched calls to {@code tool}, in call order (JCLAW-891). */
        public List<String> resultsFor(String tool) {
            return toolResults.getOrDefault(tool, List.of());
        }

        /** A turn that never produced an answer, and why. */
        public static Response failed(String reason) {
            return new Response("", List.of(), List.of(), Map.of(), Map.of(), 0, reason);
        }

        /** Names the model emitted that never reached a tool — invented, or not granted. */
        public List<String> toolsRefused() {
            var refused = new java.util.ArrayList<>(toolsAttempted);
            toolsCalled.forEach(refused::remove);
            return List.copyOf(refused);
        }
    }

    /** Prefix tagging every {@code json_schema} failure with the check that produced it. */
    private static final String JSON_SCHEMA = "json_schema: ";

    private EvalScorer() {}

    /** The failed checks, as human-readable sentences. Empty means the case passed. */
    public static List<String> failures(EvalCase testCase, Response response) {
        var failures = new ArrayList<String>();
        for (var check : testCase.checks()) {
            score(check, response, failures);
        }
        return List.copyOf(failures);
    }

    private static void score(EvalCheck check, Response response, List<String> failures) {
        // Substring matching is case-insensitive: these suites test whether the fact
        // survived the turn, not how the model capitalised it.
        var haystack = response.output().toLowerCase(Locale.ROOT);
        switch (check.kind()) {
            case CONTAINS_ALL -> {
                for (var needle : check.args()) {
                    if (!haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                        failures.add("contains_all: response is missing \"" + needle + "\"");
                    }
                }
            }
            case NOT_CONTAINS_ANY -> {
                for (var needle : check.args()) {
                    if (haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                        failures.add("not_contains_any: response contains \"" + needle + "\"");
                    }
                }
            }
            case MATCHES -> {
                if (!Pattern.compile(check.arg()).matcher(response.output()).find()) {
                    failures.add("matches: response does not match /" + check.arg() + "/");
                }
            }
            case JSON_SCHEMA -> scoreSchema(check, response, failures);
            case TOOLS_CALLED_EXACTLY -> scoreToolsCalledExactly(check, response, failures);
            case TOOLS_CALLED_WITHIN -> scoreToolsCalledWithin(check, response, failures);
            case TOOL_ARGS_INCLUDE -> scoreToolArgsInclude(check, response, failures);
            case TOOL_RESULT_INCLUDES -> scoreToolResultIncludes(check, response, failures);
            case MAX_LLM_CALLS -> {
                if (response.llmCalls() > check.limit()) {
                    failures.add("max_llm_calls: used " + response.llmCalls() + " calls, budget " + check.limit());
                }
            }
        }
    }

    /**
     * Multiset comparison of expected against actual tool calls. Reports the extra
     * calls and the missing ones separately, and always echoes what was actually
     * called: "did the wrong thing" and "did the right thing twice" need different
     * fixes, and a failure that only said "mismatch" would send the reader back to
     * re-run the case by hand.
     */
    private static void scoreToolsCalledExactly(EvalCheck check, Response response, List<String> failures) {
        var outstanding = new ArrayList<>(check.args());
        var unexpected = new ArrayList<String>();
        for (var called : response.toolsCalled()) {
            // remove() takes one occurrence, so a second call to a once-listed tool
            // falls through to unexpected — which is the redundant-call case.
            if (!outstanding.remove(called)) unexpected.add(called);
        }
        if (unexpected.isEmpty() && outstanding.isEmpty()) return;

        var parts = new ArrayList<String>();
        if (!unexpected.isEmpty()) parts.add("unexpected " + unexpected);
        if (!outstanding.isEmpty()) parts.add("missing " + outstanding);
        failures.add("tools_called_exactly: " + String.join(", ", parts)
                + " (expected " + check.args() + ", called " + response.toolsCalled() + ")");
    }

    /**
     * Sub-multiset comparison: every call must be in the allowance, but none of them
     * is required. The "or" form — see {@link EvalCheck.Kind#TOOLS_CALLED_WITHIN}.
     */
    private static void scoreToolsCalledWithin(EvalCheck check, Response response, List<String> failures) {
        var allowance = new ArrayList<>(check.args());
        var unexpected = new ArrayList<String>();
        for (var called : response.toolsCalled()) {
            if (!allowance.remove(called)) unexpected.add(called);
        }
        if (unexpected.isEmpty()) return;
        failures.add("tools_called_within: unexpected " + unexpected
                + " (allowed " + check.args() + ", called " + response.toolsCalled() + ")");
    }

    /**
     * Did a dispatched call to this tool carry the expected arguments? Passes when
     * ANY such call matches, because a turn may legitimately call one tool several
     * ways and the case is asserting that one of them happened.
     */
    private static void scoreToolArgsInclude(EvalCheck check, Response response, List<String> failures) {
        var tool = check.arg();
        var calls = response.argsFor(tool);
        if (calls.isEmpty()) {
            // Distinguishes "never called it" from "called it differently" — the first
            // is a tool-selection failure, the second an argument failure, and a
            // single "did not match" would send the reader to the wrong one.
            failures.add("tool_args_include: " + tool + " recorded no dispatched call with arguments"
                    + (response.toolsCalled().contains(tool) ? " (called, but the run recorded no arguments)" : ""));
            return;
        }
        for (var raw : calls) {
            if (argsInclude(raw, check.schema())) return;
        }
        failures.add("tool_args_include: no " + tool + " call carried " + check.schema()
                + " (saw " + calls + ")");
    }

    /**
     * Did a dispatched call to this tool return the expected substrings? Passes when
     * ANY such call carries them all, mirroring {@code tool_args_include}: a turn may
     * use one tool several ways and the case asserts one of those happened.
     */
    private static void scoreToolResultIncludes(EvalCheck check, Response response, List<String> failures) {
        var tool = check.arg();
        var expected = check.args().subList(1, check.args().size());
        var results = response.resultsFor(tool);
        if (results.isEmpty()) {
            // Separated from "returned something else" because they need different
            // fixes: one is a tool-selection failure, the other a behavior failure.
            failures.add("tool_result_includes: " + tool + " recorded no dispatched call with a result"
                    + (response.toolsCalled().contains(tool) ? " (called, but the run recorded no result)" : ""));
            return;
        }
        for (var result : results) {
            var haystack = result.toLowerCase(Locale.ROOT);
            if (expected.stream().allMatch(n -> haystack.contains(n.toLowerCase(Locale.ROOT)))) return;
        }
        // Echo what came back: this check exists because a turn passed while its tool
        // returned an HTTP 404, and a failure that hid the result would repeat that.
        failures.add("tool_result_includes: no " + tool + " result carried " + expected
                + " (saw " + results + ")");
    }

    /** Every key in {@code expected} present in {@code rawArgs} with an equal value. */
    private static boolean argsInclude(String rawArgs, JsonObject expected) {
        JsonObject actual;
        try {
            var parsed = JsonParser.parseString(rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs);
            if (!parsed.isJsonObject()) return false;
            actual = parsed.getAsJsonObject();
        } catch (JsonParseException _) {
            // A tool call whose arguments do not parse cannot have carried anything;
            // the truncation guards elsewhere already surface that as its own failure.
            return false;
        }
        for (var entry : expected.entrySet()) {
            var got = actual.get(entry.getKey());
            if (got == null || !got.equals(entry.getValue())) return false;
        }
        return true;
    }

    private static void scoreSchema(EvalCheck check, Response response, List<String> failures) {
        JsonElement body;
        try {
            body = JsonParser.parseString(response.output());
        } catch (JsonParseException e) {
            // Deliberately no fence-stripping or brace-hunting: a caller asking for
            // structured output gets the raw body, so prose around it is the defect.
            failures.add(JSON_SCHEMA + "response is not valid JSON — " + e.getMessage());
            return;
        }
        // EvalCheck.schema is @Nullable across the vocabulary, but a JSON_SCHEMA check
        // can only be built by EvalCheck.schema(JsonObject), and the loader rejects one
        // whose 'schema' is not an object — so it holds here by construction. Assert the
        // invariant instead of dereferencing a nullable field on faith.
        var schema = Objects.requireNonNull(check.schema(), "json_schema check without a schema");
        validate(body, schema, "$", failures);
    }

    /**
     * Validates {@code value} against the JSON Schema subset the loader admits —
     * type, properties, required, items, enum, additionalProperties. Anything
     * outside that subset never reaches here: {@link EvalDatasetLoader} rejects it
     * when the suite is loaded, so this method never silently skips an assertion.
     */
    private static void validate(JsonElement value, JsonObject schema, String path, List<String> failures) {
        var type = schema.has(SchemaKeys.TYPE) ? schema.get(SchemaKeys.TYPE).getAsString() : null;
        if (type != null && !typeMatches(value, type)) {
            failures.add(JSON_SCHEMA + path + ": expected " + type + ", got " + describe(value));
            return; // the nested keywords all assume the type held
        }
        var allowed = schema.getAsJsonArray(SchemaKeys.ENUM);
        if (allowed != null && !allowed.contains(value)) {
            failures.add(JSON_SCHEMA + path + ": " + value + " is not one of " + allowed);
        }
        if (value.isJsonObject()) {
            validateObject(value.getAsJsonObject(), schema, path, failures);
        } else if (value.isJsonArray()) {
            var items = schema.getAsJsonObject(SchemaKeys.ITEMS);
            if (items != null) {
                var arr = value.getAsJsonArray();
                for (var i = 0; i < arr.size(); i++) {
                    validate(arr.get(i), items, path + "[" + i + "]", failures);
                }
            }
        }
    }

    private static void validateObject(JsonObject obj, JsonObject schema, String path, List<String> failures) {
        checkRequired(obj, schema, path, failures);
        checkProperties(obj, schema, path, failures);
        checkExtraProperties(obj, schema, path, failures);
    }

    private static void checkRequired(JsonObject obj, JsonObject schema, String path, List<String> failures) {
        var required = schema.getAsJsonArray(SchemaKeys.REQUIRED);
        if (required == null) return;
        for (var req : required) {
            if (!obj.has(req.getAsString())) {
                failures.add(JSON_SCHEMA + path + ": missing required property '" + req.getAsString() + "'");
            }
        }
    }

    private static void checkProperties(JsonObject obj, JsonObject schema, String path, List<String> failures) {
        var props = schema.getAsJsonObject(SchemaKeys.PROPERTIES);
        if (props == null) return;
        for (var entry : props.entrySet()) {
            var child = obj.get(entry.getKey());
            if (child != null) {
                validate(child, entry.getValue().getAsJsonObject(), path + "." + entry.getKey(), failures);
            }
        }
    }

    /** {@code additionalProperties: false} only bites when the schema also lists the properties it allows. */
    private static void checkExtraProperties(JsonObject obj, JsonObject schema, String path, List<String> failures) {
        var props = schema.getAsJsonObject(SchemaKeys.PROPERTIES);
        var additional = schema.get(SchemaKeys.ADDITIONAL_PROPERTIES);
        if (props == null || additional == null || additional.getAsBoolean()) return;
        for (var key : obj.keySet()) {
            if (!props.has(key)) {
                failures.add(JSON_SCHEMA + path + ": unexpected property '" + key + "'");
            }
        }
    }

    private static boolean typeMatches(JsonElement value, String type) {
        return switch (type) {
            case SchemaKeys.OBJECT -> value.isJsonObject();
            case SchemaKeys.ARRAY -> value.isJsonArray();
            case SchemaKeys.STRING -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case SchemaKeys.BOOLEAN -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case SchemaKeys.NUMBER -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            // "1" and 1.5 both fail integer: a stringified or fractional id is the
            // structured-output defect this check exists to catch.
            case SchemaKeys.INTEGER -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && new BigDecimal(value.getAsString()).stripTrailingZeros().scale() <= 0;
            default -> throw new IllegalStateException("unsupported schema type: " + type);
        };
    }

    private static String describe(JsonElement value) {
        if (value.isJsonNull()) return "null";
        if (value.isJsonObject()) return SchemaKeys.OBJECT;
        if (value.isJsonArray()) return SchemaKeys.ARRAY;
        var prim = value.getAsJsonPrimitive();
        if (prim.isString()) return SchemaKeys.STRING;
        if (prim.isBoolean()) return SchemaKeys.BOOLEAN;
        return SchemaKeys.NUMBER;
    }
}
