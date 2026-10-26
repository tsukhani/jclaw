package services.evals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import tools.SchemaKeys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads and validates the eval suites under {@code evals/suites/} (JCLAW-875).
 *
 * <p>Validation is deliberately unforgiving: an unknown key, an unknown check
 * kind, or a JSON Schema keyword {@link EvalScorer} does not implement is a hard
 * failure here rather than a silently skipped assertion at scoring time. A check
 * that quietly does nothing is worse than no check — it reports a pass rate the
 * suite never measured. {@code EvalSuiteConformanceTest} runs this over the
 * shipped suites on every {@code play autotest}, so a malformed dataset fails the
 * build instead of the next eval run.
 */
public final class EvalDatasetLoader {

    /** Suite and case ids: lowercase kebab-case, so they are safe in filenames and report keys. */
    // Possessive outer repetition: java.util.regex backtracks by recursion, so a
    // greedy `(...)*` over a long id can overflow the stack (java:S5998). The
    // separator and the segment classes are disjoint, so nothing that matched
    // before can fail now.
    private static final Pattern ID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*+$");

    /**
     * {@code <id>.json} — the filename is the suite's identity and nothing else.
     * JCLAW-883 removed the {@code .v<version>} segment: git holds the history and
     * {@link EvalSuite#fingerprint()} tells two runs apart, so a hand-maintained
     * number in the name bought nothing that was not already available and cost the
     * ability to edit a suite in place at all.
     */
    private static final Pattern FILE_NAME = Pattern.compile("^([a-z0-9-]+)\\.json$");

    /**
     * The JSON Schema keywords {@link EvalScorer} actually implements. Anything else
     * in a {@code json_schema} check is rejected at load time — JClaw has no schema
     * validator dependency, and a hand-rolled subset that ignores what it does not
     * understand would pass invalid structured output.
     */
    static final Set<String> SUPPORTED_SCHEMA_KEYWORDS = Set.of(
            SchemaKeys.TYPE, SchemaKeys.PROPERTIES, SchemaKeys.REQUIRED, SchemaKeys.ITEMS,
            SchemaKeys.ENUM, SchemaKeys.ADDITIONAL_PROPERTIES, SchemaKeys.DESCRIPTION);

    private static final Set<String> SUITE_KEYS = Set.of("id", "description", "requiredTools", "cases");
    private static final Set<String> CASE_KEYS = Set.of("id", "input", "rubric", "checks");
    private static final String SCHEMA_KEY = "schema";
    private static final Set<String> CHECK_KEYS = Set.of("kind", "args", SCHEMA_KEY, "limit");

    private EvalDatasetLoader() {}

    /**
     * Every {@code *.json} suite in {@code dir}, in filename order.
     *
     * <p>No duplicate check here: {@link #loadSuite} refuses a file whose body id
     * disagrees with its {@code <id>.json} name, so two suites sharing an id would
     * have to share a filename. The uniqueness guard is the filename.
     */
    public static List<EvalSuite> loadAll(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Eval suite directory not found: " + dir);
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list eval suites in " + dir, e);
        }
        var suites = new ArrayList<EvalSuite>(files.size());
        for (var file : files) {
            suites.add(loadSuite(file));
        }
        return List.copyOf(suites);
    }

    /** One suite file, fully validated. Throws {@link IllegalArgumentException} naming the offending case. */
    public static EvalSuite loadSuite(Path file) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read eval suite " + file, e);
        }
        var name = file.getFileName().toString();
        var nameMatch = FILE_NAME.matcher(name);
        if (!nameMatch.matches()) {
            throw new IllegalArgumentException(name + ": filename must be <id>.json");
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException(name + ": not a JSON object — " + e.getMessage(), e);
        }
        rejectUnknownKeys(name, root, SUITE_KEYS);

        var id = requireString(name, root, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(name + ": suite id '" + id + "' must be lowercase kebab-case");
        }
        if (!id.equals(nameMatch.group(1))) {
            throw new IllegalArgumentException(name + ": suite id '" + id + "' does not match the filename");
        }
        var description = requireString(name, root, "description");
        var requiredTools = parseRequiredTools(name, root);

        var casesEl = root.get("cases");
        if (casesEl == null || !casesEl.isJsonArray() || casesEl.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(name + ": 'cases' must be a non-empty array");
        }
        var cases = new ArrayList<EvalCase>();
        var seenCaseIds = new HashSet<String>();
        for (var el : casesEl.getAsJsonArray()) {
            var evalCase = parseCase(name, el);
            if (!seenCaseIds.add(evalCase.id())) {
                throw new IllegalArgumentException(name + ": duplicate case id '" + evalCase.id() + "'");
            }
            cases.add(evalCase);
        }
        return new EvalSuite(id, description, requiredTools, cases);
    }

    private static EvalCase parseCase(String file, JsonElement el) {
        if (!el.isJsonObject()) throw new IllegalArgumentException(file + ": each case must be an object");
        var obj = el.getAsJsonObject();
        rejectUnknownKeys(file, obj, CASE_KEYS);
        var id = requireString(file, obj, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(file + ": case id '" + id + "' must be lowercase kebab-case");
        }
        var ctx = file + " [" + id + "]";
        var input = requireString(ctx, obj, "input");
        var rubric = requireString(ctx, obj, "rubric");

        var checksEl = obj.get("checks");
        if (checksEl == null || !checksEl.isJsonArray() || checksEl.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(ctx + ": 'checks' must be a non-empty array");
        }
        var checks = new ArrayList<EvalCheck>();
        for (var checkEl : checksEl.getAsJsonArray()) {
            checks.add(parseCheck(ctx, checkEl));
        }
        return new EvalCase(id, input, rubric, checks);
    }

    private static EvalCheck parseCheck(String ctx, JsonElement el) {
        if (!el.isJsonObject()) throw new IllegalArgumentException(ctx + ": each check must be an object");
        var obj = el.getAsJsonObject();
        rejectUnknownKeys(ctx, obj, CHECK_KEYS);
        var wire = requireString(ctx, obj, "kind");
        var kind = EvalCheck.Kind.fromWire(wire).orElseThrow(() ->
                new IllegalArgumentException(ctx + ": unknown check kind '" + wire + "'"));

        return switch (kind) {
            case CONTAINS_ALL, NOT_CONTAINS_ANY -> EvalCheck.of(kind, requireArgs(ctx, obj, kind, 0));
            case MATCHES -> {
                var args = requireArgs(ctx, obj, kind, 1);
                try {
                    Pattern.compile(args.getFirst());
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(ctx + ": matches: invalid regex — " + e.getMessage(), e);
                }
                yield EvalCheck.of(kind, args);
            }
            // Empty args is meaningful here — it asserts the agent called no tool at
            // all — so this kind is the one that accepts a zero-length list.
            case TOOLS_CALLED_EXACTLY -> EvalCheck.of(kind, requireArgs(ctx, obj, kind, ANY_ARGS));
            // "one or more": an empty allowance would mean "no tool permitted", which
            // tools_called_exactly already says more clearly.
            case TOOLS_CALLED_WITHIN -> EvalCheck.of(kind, requireArgs(ctx, obj, kind, 0));
            // args[0] is the tool, the rest are the substrings its result must carry —
            // so two is the minimum that asserts anything.
            case TOOL_RESULT_INCLUDES -> {
                var args = requireArgs(ctx, obj, kind, ANY_ARGS);
                if (args.size() < 2) {
                    throw new IllegalArgumentException(ctx + ": tool_result_includes: expected a tool name "
                            + "followed by at least one substring, got " + args.size() + " arg(s)");
                }
                yield EvalCheck.of(kind, args);
            }
            case TOOL_ARGS_INCLUDE -> {
                var args = requireArgs(ctx, obj, kind, 1);
                var expected = obj.get(SCHEMA_KEY);
                if (expected == null || !expected.isJsonObject() || expected.getAsJsonObject().isEmpty()) {
                    throw new IllegalArgumentException(
                            ctx + ": tool_args_include: 'schema' must be a non-empty object of expected arguments");
                }
                // Deliberately NOT validateSchema'd: this is a literal argument subset
                // to match, not a JSON Schema, so its keys are the tool's parameter
                // names and none of the JSON Schema vocabulary applies.
                yield new EvalCheck(kind, args, expected.getAsJsonObject(), 0);
            }
            case JSON_SCHEMA -> {
                var schemaEl = obj.get(SCHEMA_KEY);
                if (schemaEl == null || !schemaEl.isJsonObject()) {
                    throw new IllegalArgumentException(ctx + ": json_schema: 'schema' must be an object");
                }
                validateSchema(ctx, schemaEl.getAsJsonObject(), SCHEMA_KEY);
                yield EvalCheck.schema(schemaEl.getAsJsonObject());
            }
            case MAX_LLM_CALLS -> {
                var limit = requireInt(ctx, obj, "limit");
                if (limit < 1) throw new IllegalArgumentException(ctx + ": max_llm_calls: 'limit' must be >= 1");
                yield EvalCheck.maxLlmCalls(limit);
            }
        };
    }

    /**
     * The tools a suite needs granted to run honestly (JCLAW-883). Optional — a suite
     * whose cases are self-contained prompts (grounding, structured-output) declares
     * nothing and gets nothing.
     *
     * <p>It lives with the suite rather than in the agent config because it is a
     * property of what the suite measures: {@code tool-selection} is not a meaningful
     * measurement of anything if the agent cannot reach the tools it is being scored
     * on selecting. Capture uses it to calibrate the eval agent per suite, which is
     * what stops "2/6, because nobody clicked the right toggles" being mistaken for
     * a quality signal.
     */
    private static List<String> parseRequiredTools(String name, JsonObject root) {
        var el = root.get("requiredTools");
        if (el == null || el.isJsonNull()) return List.of();
        if (!el.isJsonArray()) {
            throw new IllegalArgumentException(name + ": 'requiredTools' must be an array of tool names");
        }
        var tools = new ArrayList<String>();
        for (var entry : el.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || entry.getAsString().isBlank()) {
                throw new IllegalArgumentException(name + ": 'requiredTools' entries must be non-blank strings");
            }
            tools.add(entry.getAsString());
        }
        return List.copyOf(tools);
    }

    /** Sentinel for {@link #requireArgs}: any number of args, including none. */
    private static final int ANY_ARGS = -1;

    /**
     * {@code exactly == 0} means "one or more"; {@link #ANY_ARGS} means no arity
     * constraint; otherwise the kind takes exactly that many args. The array itself
     * is still required and its entries must still be non-blank strings — an absent
     * {@code args} is an authoring mistake even for the unconstrained kind.
     */
    private static List<String> requireArgs(String ctx, JsonObject obj, EvalCheck.Kind kind, int exactly) {
        var el = obj.get("args");
        if (el == null || !el.isJsonArray()) {
            throw new IllegalArgumentException(ctx + ": " + kind.wire() + ": 'args' must be an array of strings");
        }
        var args = new ArrayList<String>();
        for (var arg : el.getAsJsonArray()) {
            if (!arg.isJsonPrimitive() || !arg.getAsJsonPrimitive().isString() || arg.getAsString().isBlank()) {
                throw new IllegalArgumentException(ctx + ": " + kind.wire() + ": 'args' entries must be non-blank strings");
            }
            args.add(arg.getAsString());
        }
        if (exactly != ANY_ARGS && (exactly == 0 ? args.isEmpty() : args.size() != exactly)) {
            throw new IllegalArgumentException(ctx + ": " + kind.wire() + ": expected "
                    + (exactly == 0 ? "at least one arg" : exactly + " arg(s)") + ", got " + args.size());
        }
        return args;
    }

    /** Walks a {@code json_schema} payload rejecting anything {@link EvalScorer} would not enforce. */
    private static void validateSchema(String ctx, JsonObject schema, String path) {
        for (var key : schema.keySet()) {
            if (!SUPPORTED_SCHEMA_KEYWORDS.contains(key)) {
                throw new IllegalArgumentException(ctx + ": json_schema at " + path + ": unsupported keyword '"
                        + key + "' (supported: " + SUPPORTED_SCHEMA_KEYWORDS.stream().sorted().toList() + ")");
            }
        }
        var props = schema.getAsJsonObject(SchemaKeys.PROPERTIES);
        if (props != null) {
            for (var entry : props.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException(ctx + ": json_schema at " + path + "." + entry.getKey()
                            + ": property schema must be an object");
                }
                validateSchema(ctx, entry.getValue().getAsJsonObject(), path + "." + entry.getKey());
            }
        }
        var items = schema.get(SchemaKeys.ITEMS);
        if (items != null) {
            if (!items.isJsonObject()) {
                throw new IllegalArgumentException(ctx + ": json_schema at " + path + ": 'items' must be an object");
            }
            validateSchema(ctx, items.getAsJsonObject(), path + "[]");
        }
    }

    private static void rejectUnknownKeys(String ctx, JsonObject obj, Set<String> allowed) {
        for (var key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(ctx + ": unknown key '" + key + "' (allowed: "
                        + allowed.stream().sorted().toList() + ")");
            }
        }
    }

    private static String requireString(String ctx, JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString() || el.getAsString().isBlank()) {
            throw new IllegalArgumentException(ctx + ": '" + key + "' must be a non-blank string");
        }
        return el.getAsString();
    }

    private static int requireInt(String ctx, JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(ctx + ": '" + key + "' must be a number");
        }
        return el.getAsInt();
    }
}
