package services.evals;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs an {@link EvalSuite} against a responder and scores it (JCLAW-875).
 *
 * <p>Offline by construction: nothing here touches the request path, the database,
 * or the metrics store, so an eval sweep costs zero LLM calls on the serving path
 * and cannot skew the latency baselines JCLAW-833 measures against. The model
 * calls a sweep does spend are the responder's, and the suite can assert a budget
 * on them via {@link EvalCheck.Kind#MAX_LLM_CALLS}.
 *
 * <p>{@link Responder} is the seam. This CLI's implementation replays recorded
 * responses (see {@link #main}) and stays free of the Play runtime — the command
 * is a plain {@code java -cp} that boots no framework. The agent-backed responder
 * ({@link EvalCapture}, JCLAW-883) necessarily needs JPA and a provider, so it
 * runs inside the app behind {@code POST /api/evals/capture} and writes the same
 * recorded format this CLI consumes. That split is why capture is not simply
 * another flag here.
 */
public final class EvalRunner {

    /** Produces the agent's answer for one case. Implementations may be slow and may throw. */
    @FunctionalInterface
    public interface Responder {
        EvalScorer.Response respond(EvalCase testCase) throws Exception;
    }

    private static final String DEFAULT_SUITE_DIR = "evals/suites";
    private static final String OPT_SUITES = "--suites";
    private static final String OPT_RESPONSES = "--responses";
    private static final String OPT_BASELINE = "--baseline";
    private static final String OPT_OUT = "--out";

    private EvalRunner() {}

    /**
     * Scores every case in {@code suite} in suite order, so two runs of the same suite
     * diff line by line.
     *
     * <p>Sequential on purpose (JF-157): every caller hands this recorded or stubbed
     * answers, so replaying them is pure computation and a fan-out buys nothing, while
     * {@code utils.TaskScope} would pull OpenTelemetry onto the offline CLI's classpath.
     * Capture, which does put a model behind each case, fans out in {@link EvalFanOut}.
     */
    public static EvalReport run(EvalSuite suite, Responder responder) {
        var results = new ArrayList<EvalReport.CaseResult>(suite.cases().size());
        for (var testCase : suite.cases()) {
            results.add(score(testCase, responder));
        }
        return new EvalReport(suite.id(), suite.fingerprint(), results);
    }

    private static EvalReport.CaseResult score(EvalCase testCase, Responder responder) {
        var startNs = System.nanoTime();
        try {
            var response = responder.respond(testCase);
            // A capture that recorded a failure for this case carries the reason
            // rather than an answer. Scoring its empty output would manufacture a
            // pile of check failures that say nothing about the agent's quality.
            if (response.error() != null) {
                return errored(testCase, startNs, response.error());
            }
            var failures = EvalScorer.failures(testCase, response);
            return EvalReport.CaseResult.scored(testCase.id(), failures.isEmpty(), failures,
                    elapsedMs(startNs), response.llmCalls());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return errored(testCase, startNs, "responder interrupted");
        } catch (Exception e) {
            // A responder that throws on one case must not cost the sweep the other
            // cases' verdicts. It is an errored case, not a failing one: nothing was
            // measured, so nothing can be said about the agent.
            return errored(testCase, startNs, "responder failed: " + e);
        }
    }

    private static EvalReport.CaseResult errored(EvalCase testCase, long startNs, String message) {
        return EvalReport.CaseResult.errored(testCase.id(), message, elapsedMs(startNs));
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    /**
     * The recorded-responses file:
     * {@code {"suite":…, "fingerprint":…, "responses":{caseId: {…}}}}.
     *
     * <p>The fingerprint records which suite content produced the recording, so
     * re-scoring it later against an edited suite is caught rather than silently
     * mixing two rulers. It is optional on the way in: a hand-written recording is
     * a legitimate thing to score, and demanding a hash nobody can compute by hand
     * would make the offline path harder to use for no safety gained.
     */
    private record ResponseFile(String suite, String fingerprint, Map<String, EvalScorer.Response> responses) {}

    /**
     * CLI entry point — see {@code ./jclaw.sh evals} and {@code evals/README.md}.
     * With no {@code --responses} it validates the dataset and exits; with one it
     * scores the recorded run, optionally diffing against a baseline report.
     *
     * <p>Exit codes: 0 clean, 1 invalid dataset / failing case / regression,
     * 2 usage error. Output goes to stdout because it is a CLI result, not logging.
     */
    public static void main(String[] args) {
        Map<String, String> opts;
        try {
            opts = parseOptions(args);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            System.out.println("Usage: evals [--suites <dir>] [--responses <file>] [--baseline <file>] [--out <file>]");
            System.exit(2);
            return;
        }

        List<EvalSuite> suites;
        var dir = Path.of(opts.getOrDefault(OPT_SUITES, DEFAULT_SUITE_DIR));
        try {
            suites = EvalDatasetLoader.loadAll(dir);
        } catch (RuntimeException e) {
            System.out.println("Dataset invalid: " + e.getMessage());
            System.exit(1);
            return;
        }

        var responsesPath = opts.get(OPT_RESPONSES);
        if (responsesPath == null) {
            printDatasetSummary(suites);
            return;
        }
        int exit;
        try {
            exit = scoreRecordedRun(suites, opts, Path.of(responsesPath));
        } catch (IOException | JsonParseException e) {
            System.out.println("Cannot read the recorded run or its baseline: " + e);
            exit = 1;
        }
        System.exit(exit);
    }

    private static void printDatasetSummary(List<EvalSuite> suites) {
        var cases = 0;
        var checks = 0;
        for (var suite : suites) {
            var suiteChecks = suite.cases().stream().mapToInt(c -> c.checks().size()).sum();
            System.out.printf("%-28s %2d case(s), %3d check(s)%n",
                    suite.qualifiedId(), suite.cases().size(), suiteChecks);
            cases += suite.cases().size();
            checks += suiteChecks;
        }
        System.out.printf("%d suite(s), %d case(s), %d check(s) — all valid%n", suites.size(), cases, checks);
    }

    private static int scoreRecordedRun(List<EvalSuite> suites, Map<String, String> opts, Path responsesPath)
            throws IOException {
        var recorded = EvalJson.GSON.fromJson(Files.readString(responsesPath), ResponseFile.class);
        if (recorded == null || recorded.suite() == null || recorded.responses() == null) {
            System.out.println(responsesPath + ": expected {\"suite\":…, \"fingerprint\":…, \"responses\":{…}}");
            return 1;
        }
        var suite = suites.stream()
                .filter(s -> s.id().equals(recorded.suite()))
                .findFirst()
                .orElse(null);
        if (suite == null) {
            System.out.println("No suite '" + recorded.suite() + "' in the dataset");
            return 1;
        }
        // Scoring a recording against edited suite content is legal — that is how you
        // re-measure an old run against a sharper check — but it must never happen
        // silently, because the recorded answers came from the questions as they were.
        if (recorded.fingerprint() != null && !recorded.fingerprint().equals(suite.fingerprint())) {
            System.out.println("Note: recorded against " + recorded.suite() + "@" + recorded.fingerprint()
                    + ", scoring against @" + suite.fingerprint() + " — the suite changed since capture.");
        }

        // Missing recordings surface through the same path as a broken responder,
        // so a partial run reads as failing cases rather than a silently short suite.
        var report = run(suite, testCase -> {
            var response = recorded.responses().get(testCase.id());
            if (response == null) throw new IllegalStateException("no response recorded");
            return response;
        });
        System.out.println(report.summary());

        var out = opts.get(OPT_OUT);
        if (out != null) {
            var outPath = Path.of(out);
            if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
            Files.writeString(outPath, report.toJson());
            System.out.println("Report written to " + out);
        }

        var exit = report.passed() == report.results().size() ? 0 : 1;
        var baseline = opts.get(OPT_BASELINE);
        if (baseline != null && regressedAgainstBaseline(report, baseline)) exit = 1;
        return exit;
    }

    /**
     * Compare {@code report} against a recorded baseline, printing any drift, and
     * report whether this run regressed.
     */
    private static boolean regressedAgainstBaseline(EvalReport report, String baseline) throws IOException {
        var before = EvalReport.fromJson(Files.readString(Path.of(baseline)));
        if (report.scoredByDifferentSuiteThan(before)) {
            // Case ids can match across two rulers while meaning different things,
            // so this is stated before the list rather than left for the reader to
            // infer from a suspicious number of "regressions".
            System.out.println("Warning: " + baseline + " was scored against @" + before.fingerprint()
                    + " and this run against @" + report.fingerprint()
                    + " — the suite changed, so the comparison below is between two different measuring sticks.");
        }
        var regressions = report.regressionsAgainst(before);
        if (regressions.isEmpty()) return false;
        System.out.println("Regressions against " + baseline + ": " + String.join(", ", regressions));
        return true;
    }

    private static Map<String, String> parseOptions(String[] args) {
        var known = List.of(OPT_SUITES, OPT_RESPONSES, OPT_BASELINE, OPT_OUT);
        var opts = new HashMap<String, String>();
        for (var i = 0; i < args.length; i += 2) {
            if (!known.contains(args[i])) throw new IllegalArgumentException("Unknown option: " + args[i]);
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            opts.put(args[i], args[i + 1]);
        }
        if (opts.containsKey(OPT_BASELINE) && !opts.containsKey(OPT_RESPONSES)) {
            throw new IllegalArgumentException("--baseline needs --responses (there is nothing to compare otherwise)");
        }
        return opts;
    }
}
