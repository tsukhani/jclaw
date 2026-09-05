package services.evals;

import com.google.gson.Gson;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The outcome of running one {@link EvalSuite}: per-case verdicts plus the three
 * numbers the epic is judged on — pass rate, per-case latency, and LLM calls spent
 * (JCLAW-875, JCLAW-833).
 *
 * <p>Latency is measured with a plain {@code nanoTime} span in {@link EvalRunner}
 * rather than {@link utils.LatencyTrace}: that instrumentation persists samples
 * into the request-path histograms behind the Chat Performance dashboard, and an
 * offline eval sweep firing hundreds of turns would skew the very baseline the
 * epic compares against.
 *
 * <p>Serialized with Gson's record support so a run can be written to disk and fed
 * back as the baseline of a later run — which is what makes regression detection
 * mean anything across commits.
 */
public record EvalReport(String suiteId, String fingerprint, List<CaseResult> results) {

    /**
     * One case's verdict. {@code failures} is empty exactly when {@code passed}.
     *
     * <p>{@code errored} separates "the agent never produced an answer" from "the
     * agent answered wrongly" (JCLAW-883). Both leave {@code passed} false, but
     * they are different findings: a wrong answer is a quality signal the suite
     * exists to catch, while an error is the sweep failing to measure anything —
     * a provider outage scored as a quality regression would send someone hunting
     * a prompt bug that does not exist. An errored case therefore stays out of
     * {@link EvalReport#passRate()} and {@link EvalReport#regressionsAgainst}.
     *
     * <p>Older report JSON has no {@code errored} field and deserializes to
     * false, which is the honest reading: before this distinction existed, an
     * error really was recorded as an ordinary failure.
     */
    public record CaseResult(String caseId, boolean passed, List<String> failures,
                             long latencyMs, int llmCalls, boolean errored) {

        public CaseResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
            if (passed && errored) {
                throw new IllegalArgumentException("A case cannot both pass and error: " + caseId);
            }
        }

        /** A case that was actually scored — it produced an answer, right or wrong. */
        public static CaseResult scored(String caseId, boolean passed, List<String> failures,
                                        long latencyMs, int llmCalls) {
            return new CaseResult(caseId, passed, failures, latencyMs, llmCalls, false);
        }

        /** A case that never yielded an answer: the responder threw, timed out, or had nothing recorded. */
        public static CaseResult errored(String caseId, String reason, long latencyMs) {
            return new CaseResult(caseId, false, List.of(reason), latencyMs, 0, true);
        }
    }

    // Derived from the app's shared serializer so the report inherits its contract
    // (serializeNulls, Instant adapter, no HTML escaping); pretty-printed on top
    // because a report file is read by people and diffed between runs.
    private static final Gson GSON = GsonHolder.GSON.newBuilder().setPrettyPrinting().create();

    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * Fraction of SCORED cases that passed, 0.0–1.0. An empty suite — or one where
     * every case errored — scores 0 rather than a vacuous 1.
     *
     * <p>Errored cases are excluded from the denominator rather than counted as
     * failures, because they were never measured. Counting them would let a
     * provider outage read as a quality collapse; excluding them silently would
     * let a sweep that mostly failed to run report a confident number, so
     * {@link #summary()} always prints the errored count beside this rate.
     */
    public double passRate() {
        var scored = scored();
        if (scored == 0) return 0.0;
        return (double) passed() / scored;
    }

    public int passed() {
        return (int) results.stream().filter(CaseResult::passed).count();
    }

    /** Cases that produced an answer and were therefore actually judged. */
    public int scored() {
        return (int) results.stream().filter(r -> !r.errored()).count();
    }

    /** Cases where the agent never produced an answer (JCLAW-883). */
    public int errored() {
        return (int) results.stream().filter(CaseResult::errored).count();
    }

    /** Model calls spent across the whole suite — the epic's call-budget number (JCLAW-833). */
    public int totalLlmCalls() {
        return results.stream().mapToInt(CaseResult::llmCalls).sum();
    }

    /**
     * True when {@code baseline} was scored by different suite content (JCLAW-883).
     * Case ids can match across two rulers while meaning different things, so a
     * regression list computed across a fingerprint change is not trustworthy and
     * the CLI says so instead of printing it as fact.
     */
    public boolean scoredByDifferentSuiteThan(EvalReport baseline) {
        return baseline != null && fingerprint != null && !fingerprint.equals(baseline.fingerprint());
    }

    /**
     * Case ids that passed in {@code baseline} and fail here. Cases absent from the
     * baseline are not regressions — a new case has no history to regress from, and
     * counting it as one would make every suite addition look like a break.
     *
     * <p>Errored cases are not regressions either (JCLAW-883): the agent never
     * answered, so nothing about its quality changed. They still surface in
     * {@link #summary()} and still fail the run — they just do not masquerade as
     * a behavior change, which is the one reading that would send someone
     * bisecting commits over a provider outage.
     */
    public List<String> regressionsAgainst(EvalReport baseline) {
        Map<String, Boolean> before = baseline.results().stream()
                .collect(Collectors.toMap(CaseResult::caseId, CaseResult::passed, (a, b) -> a));
        return results.stream()
                .filter(r -> !r.passed() && !r.errored() && Boolean.TRUE.equals(before.get(r.caseId())))
                .map(CaseResult::caseId)
                .toList();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static EvalReport fromJson(String json) {
        return GSON.fromJson(json, EvalReport.class);
    }

    /** One line per case plus a totals line, for the CLI. */
    public String summary() {
        var out = new ArrayList<String>();
        out.add(suiteId + "@" + fingerprint);
        for (var r : results) {
            out.add(String.format(Locale.ROOT, "  %-5s %-40s %5d ms  %d call(s)",
                    label(r), r.caseId(), r.latencyMs(), r.llmCalls()));
            r.failures().forEach(f -> out.add("          " + f));
        }
        // The errored count rides on the same line as the pass rate rather than
        // below it: the rate is computed over scored cases only, so reading it
        // without knowing how many never ran would overstate what was measured.
        var totals = String.format(Locale.ROOT, "  %d/%d passed (%.0f%%), %d LLM call(s) total",
                passed(), scored(), passRate() * 100, totalLlmCalls());
        if (errored() > 0) {
            totals += String.format(Locale.ROOT, ", %d case(s) errored and were not scored", errored());
        }
        out.add(totals);
        return String.join("\n", out);
    }

    private static String label(CaseResult r) {
        if (r.errored()) return "ERROR";
        return r.passed() ? "PASS" : "FAIL";
    }
}
