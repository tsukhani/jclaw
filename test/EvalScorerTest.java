import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalScorer;

import java.util.List;

/**
 * Per-check scoring (JCLAW-875). Covers each deterministic check kind on both
 * verdicts, and the JSON Schema subset in the shapes structured output actually
 * breaks in: a stringified number, a missing key, an invented key, an off-vocabulary
 * enum value, and prose wrapped around the object.
 */
class EvalScorerTest extends UnitTest {

    private static EvalCase caseWith(EvalCheck... checks) {
        return new EvalCase("a-case", "input", "rubric", List.of(checks));
    }

    private static EvalScorer.Response text(String output) {
        return new EvalScorer.Response(output, List.of(), 1);
    }

    private static JsonObject schema(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ==================== Text checks ====================

    @Test
    void containsAllPassesRegardlessOfCasing() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("Titan", "[S2]"))),
                text("titan, per [s2], is Saturn's largest moon"));

        assertTrue(failures.isEmpty(), () -> "expected a pass, got " + failures);
    }

    @Test
    void containsAllNamesEveryMissingNeedle() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("209", "days"))),
                text("the transfer took 211 days"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("missing \"209\""), failures.getFirst());
    }

    @Test
    void notContainsAnyFlagsThePlantedDistractor() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.NOT_CONTAINS_ANY, List.of("Canberra"))),
                text("The dataset records Canberra."));

        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().startsWith("not_contains_any:"), failures.getFirst());
    }

    @Test
    void matchesUsesRegexSearchNotFullMatch() {
        var check = EvalCheck.of(EvalCheck.Kind.MATCHES, List.of("(?i)does not (state|mention)"));

        assertTrue(EvalScorer.failures(caseWith(check),
                text("The source does not state the launch mass.")).isEmpty());
        assertEquals(1, EvalScorer.failures(caseWith(check), text("It was 1,340 kg.")).size());
    }

    // ==================== Tool and call-budget checks ====================

    // ==================== tools_called_exactly (JCLAW-883) ====================

    private static EvalScorer.Response calling(String... tools) {
        return new EvalScorer.Response("answer", List.of(tools), 1);
    }

    private static EvalCheck exactly(String... tools) {
        return EvalCheck.of(EvalCheck.Kind.TOOLS_CALLED_EXACTLY, List.of(tools));
    }

    @Test
    void toolsCalledExactlyPassesOnTheSameMultisetInAnyOrder() {
        var failures = EvalScorer.failures(
                caseWith(exactly("web_fetch", "datetime")),
                calling("datetime", "web_fetch"));

        assertTrue(failures.isEmpty(), () -> "order is not a behaviour difference: " + failures);
    }

    @Test
    void toolsCalledExactlyCatchesTheToolNobodyListed() {
        // The gap this kind exists to close. The tool_not_called it replaced was a
        // denylist, so it only caught rogue calls someone predicted — the first live
        // sweep created a real scheduled task through exactly this hole.
        var failures = EvalScorer.failures(
                caseWith(exactly("datetime")),
                calling("datetime", "task_manager"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("unexpected [task_manager]"), failures.getFirst());
    }

    @Test
    void toolsCalledExactlyCatchesARedundantSecondCall() {
        // Calling the right tool twice is still a superfluous call.
        var failures = EvalScorer.failures(
                caseWith(exactly("web_search")),
                calling("web_search", "web_search"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("unexpected [web_search]"), failures.getFirst());
    }

    @Test
    void toolsCalledExactlyReportsMissingAndUnexpectedSeparately() {
        var failures = EvalScorer.failures(
                caseWith(exactly("web_fetch")),
                calling("web_search"));

        var only = failures.getFirst();
        assertTrue(only.contains("unexpected [web_search]"), only);
        assertTrue(only.contains("missing [web_fetch]"), only);
    }

    @Test
    void toolsCalledExactlyWithNoArgsDemandsNoToolAtAll() {
        // What arithmetic-needs-no-tool's rubric actually says: "any tool call here
        // is pure overhead". v1 could only forbid the two tools it named.
        assertTrue(EvalScorer.failures(caseWith(exactly()), calling()).isEmpty());

        var failures = EvalScorer.failures(caseWith(exactly()), calling("datetime"));
        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("unexpected [datetime]"), failures.getFirst());
    }

    // ==================== tools_called_within — the "or" form ====================

    private static EvalCheck within(String... tools) {
        return EvalCheck.of(EvalCheck.Kind.TOOLS_CALLED_WITHIN, List.of(tools));
    }

    @Test
    void toolsCalledWithinAcceptsBothTheToolAndItsAbsence() {
        // The clock case: CurrentTimeInjector already supplies the time, so answering
        // with no tool is correct — and so is one datetime call.
        assertTrue(EvalScorer.failures(caseWith(within("datetime")), calling()).isEmpty(),
                "no tool call is within the allowance");
        assertTrue(EvalScorer.failures(caseWith(within("datetime")), calling("datetime")).isEmpty(),
                "the optional tool is within the allowance");
    }

    @Test
    void toolsCalledWithinStillRejectsAToolOutsideTheAllowance() {
        var failures = EvalScorer.failures(
                caseWith(within("datetime")),
                calling("web_search"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("unexpected [web_search]"), failures.getFirst());
    }

    @Test
    void toolsCalledWithinStillRejectsARepeat() {
        // Optional does not mean unlimited: a second clock call is still overhead.
        var failures = EvalScorer.failures(
                caseWith(within("datetime")),
                calling("datetime", "datetime"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("unexpected [datetime]"), failures.getFirst());
    }

    // ==================== tool_args_include (JCLAW-883) ====================

    private static EvalScorer.Response callingWithArgs(String tool, String... argJsons) {
        return new EvalScorer.Response("answer", List.of(tool), List.of(tool),
                java.util.Map.of(tool, List.of(argJsons)), java.util.Map.of(), 1, null, null, null);
    }

    private static EvalCheck argsInclude(String tool, String expectedJson) {
        return new EvalCheck(EvalCheck.Kind.TOOL_ARGS_INCLUDE, List.of(tool), schema(expectedJson), 0);
    }

    @Test
    void toolArgsIncludeSeparatesTwoUsesOfOneTool() {
        // The gap the name-only checks cannot close: datetime answers "what time is
        // it" with action=now and a date span with action=calculate. Against
        // tools_called_exactly: [datetime] both pass, so a clock reading would score
        // as correct date arithmetic.
        var calculate = callingWithArgs("datetime", "{\"action\":\"calculate\",\"timestamp\":\"2024-02-14\"}");
        assertTrue(EvalScorer.failures(caseWith(argsInclude("datetime", "{\"action\":\"calculate\"}")), calculate)
                .isEmpty());

        var now = callingWithArgs("datetime", "{\"action\":\"now\"}");
        var failures = EvalScorer.failures(caseWith(argsInclude("datetime", "{\"action\":\"calculate\"}")), now);
        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("no datetime call carried"), failures.getFirst());
    }

    @Test
    void toolArgsIncludeIgnoresArgumentsTheCaseDidNotPin() {
        // Subset, not equality: a timezone the model may default is not the point of
        // the case, and pinning it would make the case brittle for no gain.
        var response = callingWithArgs("datetime",
                "{\"action\":\"calculate\",\"timezone\":\"Asia/Kuala_Lumpur\",\"endTimestamp\":\"2024-06-01\"}");

        assertTrue(EvalScorer.failures(caseWith(argsInclude("datetime", "{\"action\":\"calculate\"}")), response)
                .isEmpty());
    }

    @Test
    void toolArgsIncludePassesWhenAnyCallMatches() {
        // A turn may legitimately use one tool several ways; the case asserts that
        // one of those uses happened, not that all of them did.
        var response = callingWithArgs("datetime", "{\"action\":\"now\"}", "{\"action\":\"calculate\"}");

        assertTrue(EvalScorer.failures(caseWith(argsInclude("datetime", "{\"action\":\"calculate\"}")), response)
                .isEmpty());
    }

    @Test
    void toolArgsIncludeSaysWhenTheToolWasNeverCalled() {
        // Distinct from "called it differently": one is a tool-selection failure and
        // the other an argument failure, and they send the reader to different fixes.
        var failures = EvalScorer.failures(
                caseWith(argsInclude("datetime", "{\"action\":\"calculate\"}")),
                new EvalScorer.Response("answer", List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), 1, null,
                        null, null));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("recorded no dispatched call"), failures.getFirst());
    }

    // ==================== tool_result_includes (JCLAW-891) ====================

    private static EvalScorer.Response returning(String tool, String... results) {
        return new EvalScorer.Response("answer", List.of(tool), List.of(tool),
                java.util.Map.of(), java.util.Map.of(tool, List.of(results)), 1, null, null, null);
    }

    private static EvalCheck resultIncludes(String tool, String... needles) {
        var args = new java.util.ArrayList<String>();
        args.add(tool);
        args.addAll(List.of(needles));
        return EvalCheck.of(EvalCheck.Kind.TOOL_RESULT_INCLUDES, args);
    }

    @Test
    void toolResultIncludesCatchesATurnWhereTheToolFailed() {
        // The gap this kind exists to close, observed live: the agent picked the right
        // tool and passed the right arguments, the tool returned an HTTP 404, and
        // every check passed because none of them looked at what came back.
        var failed = returning("web_fetch", "Error fetching URL: HTTP 404 fetching https://example.com/pricing");

        var failures = EvalScorer.failures(caseWith(resultIncludes("web_fetch", "pricing plan")), failed);
        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("HTTP 404"),
                "the failure must echo what came back, or it repeats the blindness: " + failures.getFirst());
    }

    @Test
    void toolResultIncludesPassesWhenTheResultCarriesEverySubstring() {
        var created = returning("task_manager",
                "Recurring task 'weekday-deploy-queue-check' created with schedule '0 0 9 * * 1-5'.");

        assertTrue(EvalScorer.failures(
                caseWith(resultIncludes("task_manager", "created", "0 0 9 * * 1-5")), created).isEmpty());
    }

    @Test
    void toolResultIncludesCatchesTheRightToolDoingTheWrongThing() {
        // "task_manager called once, with wrong content" — indistinguishable from
        // correct under tools_called_exactly and tool_args_include alike.
        var wrongSchedule = returning("task_manager",
                "Recurring task 'weekday-deploy-queue-check' created with schedule '0 0 9 * * 6,7'.");

        var failures = EvalScorer.failures(
                caseWith(resultIncludes("task_manager", "0 0 9 * * 1-5")), wrongSchedule);
        assertEquals(1, failures.size(), failures::toString);
    }

    @Test
    void toolResultIncludesMatchesCaseInsensitively() {
        assertTrue(EvalScorer.failures(
                caseWith(resultIncludes("task_manager", "CREATED")),
                returning("task_manager", "Recurring task created.")).isEmpty());
    }

    @Test
    void toolResultIncludesSaysWhenTheToolNeverRan() {
        var failures = EvalScorer.failures(
                caseWith(resultIncludes("task_manager", "created")),
                new EvalScorer.Response("answer", List.of(), 1));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("recorded no dispatched call"), failures.getFirst());
    }

    @Test
    void maxLlmCallsFailsWhenTheTurnExceedsItsBudget() {
        // The epic's efficiency NFR (JCLAW-833) expressed as a dataset assertion:
        // quality that costs an extra model call has to declare the cost.
        var withinBudget = new EvalScorer.Response("51", List.of(), 1);
        var overBudget = new EvalScorer.Response("51", List.of(), 3);

        assertTrue(EvalScorer.failures(caseWith(EvalCheck.maxLlmCalls(1)), withinBudget).isEmpty());

        var failures = EvalScorer.failures(caseWith(EvalCheck.maxLlmCalls(1)), overBudget);
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("used 3 calls, budget 1"), failures.getFirst());
    }

    // ==================== JSON Schema subset ====================

    private static final String INVOICE_SCHEMA = """
            {"type": "object", "required": ["invoiceNumber", "total", "paid"], "additionalProperties": false,
             "properties": {"invoiceNumber": {"type": "string"}, "total": {"type": "number"},
                            "paid": {"type": "boolean"}}}
            """;

    @Test
    void jsonSchemaAcceptsAConformingObject() {
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("{\"invoiceNumber\": \"INV-2041\", \"total\": 128.5, \"paid\": true}"));

        assertTrue(failures.isEmpty(), failures::toString);
    }

    @Test
    void jsonSchemaCatchesTheStringifiedNumber() {
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("{\"invoiceNumber\": \"INV-2041\", \"total\": \"128.50\", \"paid\": true}"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("$.total: expected number, got string"), failures.getFirst());
    }

    @Test
    void jsonSchemaReportsMissingAndUnexpectedProperties() {
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("{\"invoiceNumber\": \"INV-2041\", \"total\": 128.5, \"currency\": \"USD\"}"));

        assertEquals(2, failures.size(), failures::toString);
        assertTrue(failures.stream().anyMatch(f -> f.contains("missing required property 'paid'")), failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.contains("unexpected property 'currency'")), failures.toString());
    }

    @Test
    void jsonSchemaRejectsAnOffVocabularyEnumValue() {
        var enumSchema = schema("""
                {"type": "object", "required": ["sentiment"], "additionalProperties": false,
                 "properties": {"sentiment": {"type": "string", "enum": ["positive", "neutral", "negative"]}}}
                """);

        assertTrue(EvalScorer.failures(caseWith(EvalCheck.schema(enumSchema)),
                text("{\"sentiment\": \"negative\"}")).isEmpty());

        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(enumSchema)),
                text("{\"sentiment\": \"mixed\"}"));
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("is not one of"), failures.getFirst());
    }

    @Test
    void jsonSchemaWalksArrayItemsAndNamesTheIndex() {
        var arraySchema = schema("""
                {"type": "object", "required": ["planets"], "additionalProperties": false,
                 "properties": {"planets": {"type": "array", "items": {"type": "object",
                    "required": ["name", "rank"], "additionalProperties": false,
                    "properties": {"name": {"type": "string"}, "rank": {"type": "integer"}}}}}}
                """);

        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(arraySchema)),
                text("{\"planets\": [{\"name\": \"Jupiter\", \"rank\": 1}, {\"name\": \"Saturn\", \"rank\": 2.5}]}"));

        assertEquals(1, failures.size(), failures::toString);
        assertTrue(failures.getFirst().contains("$.planets[1].rank: expected integer"), failures.getFirst());
    }

    @Test
    void jsonSchemaFailsOnProseWrappedJson() {
        // Fence-stripping would make the check pass for a body no machine caller
        // can consume, which is why the scorer parses the response verbatim.
        var failures = EvalScorer.failures(caseWith(EvalCheck.schema(schema(INVOICE_SCHEMA))),
                text("Here is the JSON:\n```json\n{\"invoiceNumber\": \"INV-1\", \"total\": 1, \"paid\": true}\n```"));

        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("not valid JSON"), failures.getFirst());
    }

    @Test
    void everyFailingCheckIsReported() {
        var failures = EvalScorer.failures(
                caseWith(EvalCheck.of(EvalCheck.Kind.CONTAINS_ALL, List.of("51")),
                        exactly(),
                        EvalCheck.maxLlmCalls(1)),
                new EvalScorer.Response("fifty-one", List.of("exec"), 2));

        assertEquals(3, failures.size(), () -> "one line per failed check: " + failures);
    }

    // ==================== states_turn_time (JF-298) ====================

    /** 09:53:20Z is 17:53 in Asia/Singapore, which keeps UTC+8 all year. */
    private static final String TURN_START = "2026-11-11T09:53:20Z";
    private static final String ZONE = "Asia/Singapore";
    private static final EvalCase TELLS_THE_TIME = caseWith(EvalCheck.statesTurnTime(2));
    private static final String NO_TURN_TIME =
            "states_turn_time: the recording carries no turn time, so the time the reply states cannot be checked";

    private static List<String> turnTimeFailures(String turnStart, String output) {
        return EvalScorer.failures(TELLS_THE_TIME, text(output).withTurn(turnStart, ZONE));
    }

    private static String offBy(String stated, String turnTime) {
        return "states_turn_time: response states " + stated + ", more than 2 minutes from the turn's start at "
                + turnTime + " " + ZONE;
    }

    @Test
    void aReplyStatingTheTurnsTimeInTheAgentsZonePasses() {
        assertEquals(List.of(), turnTimeFailures(TURN_START, "It's 17:53 in Singapore."));
    }

    @Test
    void twelveHourFormsPass() {
        for (var reply : List.of("It's 5:53 PM.", "It's 5:53pm.", "It's 5:53 p.m.", "It's 5:53 now.")) {
            assertEquals(List.of(), turnTimeFailures(TURN_START, reply), reply);
        }
        assertEquals(List.of(), turnTimeFailures("2026-11-11T10:01:00Z", "It's just after 6 pm."));
    }

    @Test
    void aTimeAtTheToleranceSidePassesAndOneMinutePastItFails() {
        assertEquals(List.of(), turnTimeFailures(TURN_START, "It's 17:55."));
        assertEquals(List.of(), turnTimeFailures(TURN_START, "It's 17:51."));
        assertEquals(List.of(offBy("[17:56]", "17:53")), turnTimeFailures(TURN_START, "It's 17:56."));
    }

    @Test
    void aReplyAnHourOffFails() {
        assertEquals(List.of(offBy("[16:53]", "17:53")), turnTimeFailures(TURN_START, "It's 16:53."));
        assertEquals(List.of(offBy("[4:53 PM]", "17:53")), turnTimeFailures(TURN_START, "It's 4:53 PM."));
    }

    @Test
    void theToleranceWrapsAroundMidnight() {
        assertEquals(List.of(), turnTimeFailures("2026-11-11T15:59:30Z", "It's 00:01."));
    }

    @Test
    void aUtcOffsetIsNotReadAsTheTime() {
        assertEquals(List.of(offBy("[16:53]", "17:53")), turnTimeFailures(TURN_START, "It's 16:53 (UTC+08:00)."));
    }

    @Test
    void aReplyThatStatesNoTimeOfDayFails() {
        assertEquals(List.of("states_turn_time: response states no time of day"),
                turnTimeFailures(TURN_START, "Today is Wednesday, 11 November 2026."));
    }

    @Test
    void aRecordingWithoutATurnTimeFailsNamingIt() {
        assertEquals(List.of(NO_TURN_TIME), EvalScorer.failures(TELLS_THE_TIME, text("It's 17:53.")));
    }

    @Test
    void aTurnTimeThatDoesNotParseFailsNamingIt() {
        assertEquals(List.of("states_turn_time: the recorded turn time yesterday in Asia/Singapore does not parse"),
                turnTimeFailures("yesterday", "It's 17:53."));
        assertEquals(List.of("states_turn_time: the recorded turn time " + TURN_START + " in Mars/Olympus does not parse"),
                EvalScorer.failures(TELLS_THE_TIME, text("It's 17:53.").withTurn(TURN_START, "Mars/Olympus")));
    }

    @Test
    void aRecordingMadeBeforeTurnTimesStillLoadsAndCannotPass() {
        var recorded = new Gson().fromJson(
                "{\"output\":\"It's 17:53.\",\"toolsCalled\":[\"datetime\"],\"llmCalls\":2}", EvalScorer.Response.class);

        assertEquals("It's 17:53.", recorded.output());
        assertEquals(List.of("datetime"), recorded.toolsCalled());
        assertEquals(2, recorded.llmCalls());
        assertNull(recorded.turnStartedAt());
        assertNull(recorded.timezone());
        assertEquals(List.of(NO_TURN_TIME), EvalScorer.failures(TELLS_THE_TIME, recorded));
    }
}
