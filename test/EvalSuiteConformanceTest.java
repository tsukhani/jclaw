import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.evals.EvalCase;
import services.evals.EvalCheck;
import services.evals.EvalDatasetLoader;
import services.evals.EvalScorer;
import services.evals.EvalSuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The CI gate over the shipped eval dataset (JCLAW-875): every suite under
 * {@code evals/suites/} must load, and the format documented in
 * {@code evals/schema/eval-suite.schema.json} must match what the loader accepts.
 *
 * <p>This runs on every {@code play autotest} — which is what makes "eval failures
 * block merge" true for the dataset layer without a live model or a network call.
 * Scoring a real agent needs a responder and is not a build-time concern; this test
 * guarantees the ruler itself is well-formed before anyone measures with it.
 */
class EvalSuiteConformanceTest extends UnitTest {

    private static Path evals(String... parts) {
        var path = Path.of(Play.applicationPath.getAbsolutePath(), "evals");
        for (var part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    private static List<EvalSuite> shippedSuites() {
        return EvalDatasetLoader.loadAll(evals("suites"));
    }

    @Test
    void everyShippedSuiteLoads() {
        // loadAll throws naming the offending file and case on any malformed suite.
        var suites = shippedSuites();

        assertFalse(suites.isEmpty(), "expected at least one suite in evals/suites: restore the shipped suites");
        var cases = suites.stream().mapToInt(s -> s.cases().size()).sum();
        assertTrue(cases >= suites.size(), "a suite under evals/suites carries no case: add one, or delete the suite");
    }

    @Test
    void schemaAndLoaderAgreeOnTheCheckVocabulary() throws Exception {
        // The schema file is what dataset authors read; the loader is what enforces.
        // Drift between them ships a documented kind that no run can use.
        var schema = JsonParser.parseString(Files.readString(evals("schema", "eval-suite.schema.json")))
                .getAsJsonObject();
        var documented = schema.getAsJsonObject("$defs").getAsJsonObject("check")
                .getAsJsonObject("properties").getAsJsonObject("kind").getAsJsonArray("enum")
                .asList().stream().map(JsonElement::getAsString).collect(Collectors.toSet());

        var implemented = Arrays.stream(EvalCheck.Kind.values())
                .map(EvalCheck.Kind::wire).collect(Collectors.toSet());

        assertEquals(implemented, documented, "eval-suite.schema.json and EvalCheck.Kind disagree: add or remove the "
                + "kind in both $defs.check.properties.kind.enum and EvalCheck.Kind");
    }

    @Test
    void everyCheckKindIsExercisedByTheShippedSuites() {
        // A kind no suite uses is a kind whose scoring is only ever exercised by its
        // own unit test — the dataset is where it earns its place.
        Set<EvalCheck.Kind> used = shippedSuites().stream()
                .flatMap(s -> s.cases().stream())
                .flatMap(c -> c.checks().stream())
                .map(EvalCheck::kind)
                .collect(Collectors.toSet());
        var unused = EnumSet.allOf(EvalCheck.Kind.class);
        unused.removeAll(used);

        assertTrue(unused.isEmpty(), "check kinds no shipped suite uses: " + unused
                + ". Add a case checking each to a suite under evals/suites");
    }

    @Test
    void aTurnThatDidNothingFailsEveryShippedCase() {
        // tools_called_within, max_llm_calls and not_contains_any each pass an empty turn, so a case
        // built only from them grades every agent up.
        var emptyTurn = new EvalScorer.Response("", List.of(), 1).withTurn(TURN_START, ZONE);
        var passing = shippedSuites().stream()
                .flatMap(s -> s.cases().stream()
                        .filter(c -> EvalScorer.failures(c, emptyTurn).isEmpty())
                        .map(c -> s.id() + "/" + c.id()))
                .toList();

        assertEquals(List.of(), passing, "these cases pass a turn with no reply and no tool call: add a check on "
                + "what the answer must say (contains_all, matches, json_schema) or on a tool that must run");
    }

    /** 09:53:20Z is 17:53 in Asia/Singapore, which keeps UTC+8 all year. */
    private static final String TURN_START = "2026-11-11T09:53:20Z";
    private static final String ZONE = "Asia/Singapore";

    @Test
    void theWallClockCasePassesTheTurnsTimeAndFailsAWrongOrMissingOne() {
        var wallClock = shippedCase("tool-selection", "wall-clock-datetime-optional");
        var noTime = List.of("states_turn_time: response states no time of day");

        assertEquals(List.of(), EvalScorer.failures(wallClock,
                new EvalScorer.Response("It's 17:53 in Singapore.", List.of(), 1).withTurn(TURN_START, ZONE)));
        assertEquals(List.of(), EvalScorer.failures(wallClock,
                new EvalScorer.Response("It's 5:53 PM.", List.of("datetime"), 2).withTurn(TURN_START, ZONE)));
        assertEquals(List.of("states_turn_time: response states [16:53], more than 2 minutes from the turn's start "
                        + "at 17:53 Asia/Singapore"),
                EvalScorer.failures(wallClock,
                        new EvalScorer.Response("It's 16:53.", List.of(), 1).withTurn(TURN_START, ZONE)));
        assertEquals(noTime, EvalScorer.failures(wallClock,
                new EvalScorer.Response("I can't check the time from here.", List.of(), 1).withTurn(TURN_START, ZONE)));
        assertEquals(noTime, EvalScorer.failures(wallClock,
                new EvalScorer.Response("Today is Wednesday, 11 November 2026.", List.of(), 1)
                        .withTurn(TURN_START, ZONE)));
        assertEquals(List.of("states_turn_time: the recording carries no turn time, so the time the reply states "
                        + "cannot be checked"),
                EvalScorer.failures(wallClock, new EvalScorer.Response("It's 17:53.", List.of(), 1)));
    }

    private static EvalCase shippedCase(String suiteId, String caseId) {
        return shippedSuites().stream()
                .filter(s -> s.id().equals(suiteId))
                .flatMap(s -> s.cases().stream())
                .filter(c -> c.id().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(suiteId + "/" + caseId + " is not a shipped case"));
    }
}
