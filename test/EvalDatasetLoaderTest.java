import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCheck;
import services.evals.EvalDatasetLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Loader validation for the eval dataset format (JCLAW-875). Each test writes one
 * suite file into its own temp directory and asserts the loader either models it
 * exactly or refuses it by name — a dataset defect must surface here, not as a
 * check that silently scores nothing at run time.
 *
 * <p>Every test gets a fresh directory because the play1 engine runs test methods
 * concurrently; a shared fixture directory would have them deleting each other's
 * files.
 */
class EvalDatasetLoaderTest extends UnitTest {

    private static Path root;

    private static final String VALID = """
            {
              "id": "sample",
              "description": "A sample suite",
              "cases": [
                {
                  "id": "greets-back",
                  "input": "hello",
                  "rubric": "the agent answers rather than calling a tool",
                  "checks": [
                    {"kind": "contains_all", "args": ["hello", "there"]},
                    {"kind": "tools_called_within", "args": ["web_search"]},
                    {"kind": "max_llm_calls", "limit": 1}
                  ]
                }
              ]
            }
            """;

    @BeforeAll
    static void makeRoot() throws Exception {
        root = Files.createTempDirectory("eval-suites");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (root != null && Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception _) {
                        // best-effort temp cleanup
                    }
                });
            }
        }
    }

    /** Writes one suite file into a fresh directory and returns its path. */
    private static Path write(String fileName, String body) throws Exception {
        var dir = Files.createTempDirectory(root, "case");
        var file = dir.resolve(fileName);
        Files.writeString(file, body);
        return file;
    }

    /** The loader's refusal message for a suite it should reject, or {@code null} if it accepted it. */
    private static String refusal(String fileName, String body) throws Exception {
        try {
            EvalDatasetLoader.loadSuite(write(fileName, body));
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    // ==================== Happy path ====================

    @Test
    void loadsSuiteWithEveryFieldMapped() throws Exception {
        var suite = EvalDatasetLoader.loadSuite(write("sample.json", VALID));

        assertEquals("sample", suite.id());
        assertTrue(suite.qualifiedId().startsWith("sample@"), suite.qualifiedId());
        assertEquals(1, suite.cases().size());
        var evalCase = suite.cases().getFirst();
        assertEquals("greets-back", evalCase.id());
        assertEquals("hello", evalCase.input());
        assertEquals("the agent answers rather than calling a tool", evalCase.rubric());
        assertEquals(3, evalCase.checks().size());
        assertEquals(EvalCheck.Kind.CONTAINS_ALL, evalCase.checks().get(0).kind());
        assertEquals(2, evalCase.checks().get(0).args().size());
        assertEquals("web_search", evalCase.checks().get(1).arg());
        assertEquals(1, evalCase.checks().get(2).limit());
    }

    @Test
    void loadAllReadsEverySuiteInFilenameOrder() throws Exception {
        var dir = Files.createTempDirectory(root, "all");
        Files.writeString(dir.resolve("sample.json"), VALID);
        Files.writeString(dir.resolve("beta.json"),
                VALID.replace("\"sample\"", "\"beta\""));

        var suites = EvalDatasetLoader.loadAll(dir);

        assertEquals(2, suites.size());
        assertEquals("beta", suites.get(0).id(), "sorted by filename so report order is stable");
        assertEquals("sample", suites.get(1).id());
    }

    @Test
    void parsesRequiredToolsAndFoldsThemIntoTheFingerprint() throws Exception {
        // Granting a suite a different tool set changes what its pass rate measures
        // just as surely as editing a check does, so it belongs in the canonical form.
        var none = EvalDatasetLoader.loadSuite(write("sample.json", VALID));
        assertTrue(none.requiredTools().isEmpty(), "absent means no tools, not a parse error");

        var withTools = EvalDatasetLoader.loadSuite(write("sample.json", VALID.replace(
                "\"description\": \"A sample suite\",",
                "\"description\": \"A sample suite\", \"requiredTools\": [\"datetime\", \"web_search\"],")));

        assertEquals(List.of("datetime", "web_search"), withTools.requiredTools());
        assertNotEquals(none.fingerprint(), withTools.fingerprint());
    }

    @Test
    void rejectsRequiredToolsThatIsNotAnArrayOfNames() throws Exception {
        var msg = refusal("sample.json", VALID.replace(
                "\"description\": \"A sample suite\",",
                "\"description\": \"A sample suite\", \"requiredTools\": \"datetime\","));
        assertNotNull(msg);
        assertTrue(msg.contains("requiredTools"), msg);
    }

    @Test
    void rejectsToolArgsIncludeWithoutExpectedArguments() throws Exception {
        // The kind is meaningless without a subset to match, and an empty object
        // would silently pass on every call to the tool.
        var msg = refusal("sample.json", VALID.replace(
                "{\"kind\": \"tools_called_within\", \"args\": [\"web_search\"]}",
                "{\"kind\": \"tool_args_include\", \"args\": [\"web_search\"], \"schema\": {}}"));
        assertNotNull(msg);
        assertTrue(msg.contains("non-empty object"), msg);
    }

    private static final String WITHIN_CHECK = "{\"kind\": \"tools_called_within\", \"args\": [\"web_search\"]}";

    @Test
    void loadsStatesTurnTimeWithItsToleranceInMinutes() throws Exception {
        var suite = EvalDatasetLoader.loadSuite(write("sample.json",
                VALID.replace(WITHIN_CHECK, "{\"kind\": \"states_turn_time\", \"limit\": 1}")));

        var check = suite.cases().getFirst().checks().get(1);
        assertEquals(EvalCheck.Kind.STATES_TURN_TIME, check.kind());
        assertEquals(1, check.limit());
    }

    @Test
    void rejectsStatesTurnTimeWithoutAToleranceOfAtLeastOneMinute() throws Exception {
        var zero = refusal("sample.json", VALID.replace(WITHIN_CHECK, "{\"kind\": \"states_turn_time\", \"limit\": 0}"));
        assertNotNull(zero);
        assertTrue(zero.endsWith("[greets-back]: states_turn_time: 'limit' must be >= 1"), zero);

        var absent = refusal("sample.json", VALID.replace(WITHIN_CHECK, "{\"kind\": \"states_turn_time\"}"));
        assertNotNull(absent);
        assertTrue(absent.endsWith("[greets-back]: 'limit' must be a number"), absent);
    }

    // ==================== Fingerprint (JCLAW-883) ====================

    @Test
    void editingACheckMovesTheFingerprint() throws Exception {
        // The property the old .v<N> filename scheme asserted by hand and could not
        // enforce: an in-place edit under an unchanged version was undetectable.
        var before = EvalDatasetLoader.loadSuite(write("sample.json", VALID));
        var after = EvalDatasetLoader.loadSuite(write("sample.json",
                VALID.replace("\"max_llm_calls\", \"limit\": 1", "\"max_llm_calls\", \"limit\": 2")));

        assertNotEquals(before.fingerprint(), after.fingerprint(),
                "changing what a case asserts must change the fingerprint");
    }

    @Test
    void rewordingProseLeavesTheFingerprintAlone() throws Exception {
        // Rubric and description explain why a case exists; neither changes a
        // verdict. A fingerprint that moved on prose would produce warnings people
        // learn to click past, which is worse than no warning.
        var before = EvalDatasetLoader.loadSuite(write("sample.json", VALID));
        var after = EvalDatasetLoader.loadSuite(write("sample.json", VALID
                .replace("A sample suite", "A sample suite, reworded")
                .replace("the agent answers rather than calling a tool", "answers directly")));

        assertEquals(before.fingerprint(), after.fingerprint(),
                "clarifying prose must not invalidate a baseline");
    }

    @Test
    void reorderingCasesMovesTheFingerprint() throws Exception {
        // Order is part of the canonical form. Two suites holding the same cases in a
        // different order score the same, but they are not the same file, and a
        // fingerprint that ignored order would need a canonical sort to stay honest.
        var twoCases = VALID.replace("\"cases\": [",
                """
                "cases": [
                    {"id": "second", "input": "hi", "rubric": "another case",
                     "checks": [{"kind": "contains_all", "args": ["hi"]}]},""");
        var swapped = VALID.replace("""
                    {
                      "id": "greets-back",""", """
                    {"id": "second", "input": "hi", "rubric": "another case",
                     "checks": [{"kind": "contains_all", "args": ["hi"]}]},
                    {
                      "id": "greets-back",""");

        assertNotEquals(
                EvalDatasetLoader.loadSuite(write("sample.json", twoCases)).fingerprint(),
                EvalDatasetLoader.loadSuite(write("sample.json", swapped)).fingerprint());
    }

    // ==================== Rejections ====================

    @Test
    void aSecondCopyOfASuiteIsCaughtByTheFilenameGuard() throws Exception {
        // Why loadAll needs no duplicate check of its own: the filename IS the
        // uniqueness key, so a copied suite is rejected for disagreeing with its
        // name long before any id collision could be observed.
        var dir = Files.createTempDirectory(root, "collision");
        Files.writeString(dir.resolve("sample.json"), VALID);
        Files.writeString(dir.resolve("copy-of-sample.json"), VALID);

        var e = assertThrows(IllegalArgumentException.class, () -> EvalDatasetLoader.loadAll(dir));
        assertTrue(e.getMessage().contains("does not match the filename"), e.getMessage());
    }

    @Test
    void rejectsAFilenameThatIsNotJustTheSuiteId() throws Exception {
        // Including a leftover .v<N> segment: the filename is the id and nothing
        // else now, so a versioned name is a stale file rather than an older suite.
        var msg = refusal("sample.v2.json", VALID);
        assertNotNull(msg, "a filename carrying anything but the id must be refused");
        assertTrue(msg.contains("<id>.json"), msg);
    }

    @Test
    void rejectsAVersionKeyLeftInTheBody() throws Exception {
        // Not silently ignored: a suite still carrying "version" was written against
        // the old scheme, and accepting it would leave the author believing a number
        // nothing reads is still protecting their baseline.
        var msg = refusal("sample.json", VALID.replace("\"id\": \"sample\",", "\"id\": \"sample\", \"version\": 1,"));
        assertNotNull(msg);
        assertTrue(msg.contains("version"), msg);
    }

    @Test
    void rejectsDuplicateCaseIds() throws Exception {
        var body = VALID.replace("\"cases\": [",
                """
                "cases": [
                    {"id": "greets-back", "input": "hi", "rubric": "a second case reusing the id",
                     "checks": [{"kind": "contains_all", "args": ["hi"]}]},""");
        var msg = refusal("sample.json", body);
        assertNotNull(msg);
        assertTrue(msg.contains("duplicate case id"), msg);
    }

    @Test
    void rejectsUnknownCheckKind() throws Exception {
        var msg = refusal("sample.json", VALID.replace("\"contains_all\"", "\"vibes_check\""));
        assertNotNull(msg);
        assertTrue(msg.contains("unknown check kind 'vibes_check'"), msg);
    }

    @Test
    void rejectsUnknownKeyRatherThanIgnoringIt() throws Exception {
        var msg = refusal("sample.json", VALID.replace("\"rubric\":", "\"expectd\": \"typo\", \"rubric\":"));
        assertNotNull(msg);
        assertTrue(msg.contains("unknown key 'expectd'"), msg);
    }

    @Test
    void rejectsCheckKindWithTheWrongArgCount() throws Exception {
        var msg = refusal("sample.json", VALID.replace(
                "{\"kind\": \"tools_called_within\", \"args\": [\"web_search\"]}",
                "{\"kind\": \"matches\", \"args\": [\"a\", \"b\"]}"));
        assertNotNull(msg);
        assertTrue(msg.contains("expected 1 arg(s), got 2"), msg);
    }

    @Test
    void acceptsToolsCalledExactlyWithAnEmptyArgList() throws Exception {
        // The one kind where an empty list is the assertion rather than an authoring
        // slip: it means "the turn called no tool at all" (JCLAW-883).
        var suite = EvalDatasetLoader.loadSuite(write("sample.json", VALID.replace(
                "{\"kind\": \"tools_called_within\", \"args\": [\"web_search\"]}",
                "{\"kind\": \"tools_called_exactly\", \"args\": []}")));

        var check = suite.cases().getFirst().checks().stream()
                .filter(c -> c.kind() == EvalCheck.Kind.TOOLS_CALLED_EXACTLY)
                .findFirst()
                .orElseThrow();
        assertTrue(check.args().isEmpty());
    }

    @Test
    void rejectsToolsCalledExactlyWithoutAnArgsArray() throws Exception {
        // Permissive on length, still strict on shape — a missing args array is an
        // authoring mistake, not a way to spell "no tools".
        var msg = refusal("sample.json", VALID.replace(
                "{\"kind\": \"tools_called_within\", \"args\": [\"web_search\"]}",
                "{\"kind\": \"tools_called_exactly\"}"));
        assertNotNull(msg);
        assertTrue(msg.contains("'args' must be an array"), msg);
    }

    @Test
    void rejectsInvalidRegex() throws Exception {
        var msg = refusal("sample.json", VALID.replace(
                "{\"kind\": \"contains_all\", \"args\": [\"hello\", \"there\"]}",
                "{\"kind\": \"matches\", \"args\": [\"([unclosed\"]}"));
        assertNotNull(msg);
        assertTrue(msg.contains("invalid regex"), msg);
    }

    @Test
    void rejectsSchemaKeywordTheScorerDoesNotImplement() throws Exception {
        // The whole point of the load-time gate: an unimplemented keyword would let
        // invalid structured output through while the report claimed a pass.
        var msg = refusal("sample.json", VALID.replace(
                "{\"kind\": \"contains_all\", \"args\": [\"hello\", \"there\"]}",
                "{\"kind\": \"json_schema\", \"schema\": {\"type\": \"object\", \"minProperties\": 2}}"));
        assertNotNull(msg);
        assertTrue(msg.contains("unsupported keyword 'minProperties'"), msg);
    }

    @Test
    void rejectsUnsupportedKeywordNestedInsideAProperty() throws Exception {
        var msg = refusal("sample.json", VALID.replace(
                "{\"kind\": \"contains_all\", \"args\": [\"hello\", \"there\"]}",
                "{\"kind\": \"json_schema\", \"schema\": {\"type\": \"object\", "
                        + "\"properties\": {\"total\": {\"type\": \"number\", \"minimum\": 0}}}}"));
        assertNotNull(msg);
        assertTrue(msg.contains("unsupported keyword 'minimum'"), msg);
        assertTrue(msg.contains("schema.total"), "the message should name the offending path: " + msg);
    }

    @Test
    void rejectsCaseWithNoChecks() throws Exception {
        var body = """
                {
                  "id": "sample", "description": "A sample suite",
                  "cases": [{"id": "empty", "input": "hi", "rubric": "nothing asserted", "checks": []}]
                }
                """;
        var msg = refusal("sample.json", body);
        assertNotNull(msg);
        assertTrue(msg.contains("'checks' must be a non-empty array"), msg);
    }

    @Test
    void rejectsBlankRubric() throws Exception {
        var msg = refusal("sample.json",
                VALID.replace("\"the agent answers rather than calling a tool\"", "\"  \""));
        assertNotNull(msg);
        assertTrue(msg.contains("'rubric' must be a non-blank string"), msg);
    }
}
