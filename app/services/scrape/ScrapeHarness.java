package services.scrape;

import okhttp3.OkHttpClient;
import tools.WebScrapeTool;
import tools.scrape.ImpersonatedFetcher;
import tools.scrape.RenderedFetcher;
import tools.scrape.ScrapeLadder;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs the CF-100 corpus and reports per-rung access rates (JCLAW-1081).
 *
 * <p>Rungs 1-3 ship; the provider rung (JCLAW-1089/1090) was descoped and never
 * registered here.
 *
 * <p><b>Each numbered rung is measured independently</b> — the ladder returns one
 * aggregate outcome, which is the opposite of the attribution those lanes exist to
 * produce. {@link #rungLadder()} is the deliberate exception, reported beside them.
 *
 * <p><b>Rungs run the real code path, never a stand-in HTTP client.</b> Which client
 * gets through is the whole question — curl's TLS fingerprint is not OkHttp's, is not
 * the impersonation sidecar's, is not Chromium's — so a harness that substituted its
 * own fetcher would measure a client the product does not ship.
 */
public final class ScrapeHarness {

    private ScrapeHarness() {}

    /** One rung's attempt. Failures are returned as observations, not thrown, because
     *  a failure is itself a classifiable outcome. */
    @FunctionalInterface
    public interface Rung {
        ScrapeObservation fetch(String url);
    }

    private static final Map<String, String> HEADERS =
            Map.of("User-Agent", "Mozilla/5.0 (compatible; JClaw/1.0)");

    /** The harness's own guarded client. {@code WebFetchTool.CLIENT} is package-private
     *  in {@code tools}, and duplicating its construction here is the smaller cost —
     *  same {@link SsrfGuard#buildGuardedClient} call, same timeouts. */
    private static final OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(10, 30);

    /**
     * Rung 1: the shipped fetch-and-extract chain — OkHttp, SsrfGuard, manual
     * redirects, Readability, markdown.
     *
     * <p>Calls {@link WebExtraction} directly rather than {@code WebFetchTool.execute}.
     * Since JCLAW-1082 the tool is a thin wrapper over this chain, and the wrapper's
     * contribution is presentation — error strings and the html-mode branch — which the
     * classifier would then have to parse back out. Going direct also gives the raw
     * body, without which JCLAW-1086's classifier cannot tell a gate from a SPA. It is
     * the same code path {@code web_scrape} uses per page.
     */
    public static Rung rung1() {
        return url -> {
            try {
                var fetched = WebExtraction.fetch(url, CLIENT, HEADERS);
                return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
            } catch (Exception e) {
                var m = e.getMessage();
                return ScrapeObservation.failed(url,
                        m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
            }
        };
    }

    /**
     * Rung 1s: the shipped {@code web_scrape} tool's own per-URL path — everything rung 1
     * does, plus SSRF admission, robots.txt and per-host pacing.
     *
     * <p>Not a separate capability but a separate <em>policy</em>. Comparing it against
     * rung 1 answers a question the epic otherwise cannot: what politeness costs in
     * access. The corpus is crawled at depth 0, one URL per entry, because the harness
     * scores per-URL reach and a crawl would conflate that with link discovery.
     */
    public static Rung rungScrape() {
        var tool = new WebScrapeTool();
        return tool::fetchSingle;
    }

    /**
     * Rung 2: the TLS-impersonating fetch sidecar (JCLAW-1087). Same extraction chain
     * as rung 1 — only the transport differs, which is what keeps the two reports
     * comparable.
     *
     * <p>Sends no {@code User-Agent}. curl_cffi fills in the header set that matches the
     * profile it is impersonating, and overriding it with rung 1's {@code JClaw/1.0}
     * would announce a Chrome ClientHello alongside a non-Chrome agent string — a
     * mismatch WAFs check for directly, and one that would waste the handshake this
     * rung exists to forge. The consequence for the comparison is stated plainly: rung 2
     * differs from rung 1 in headers as well as fingerprint, because a rung that sent
     * rung 1's headers is not the rung we would ship.
     */
    public static Rung rung2() {
        return url -> {
            try {
                var fetched = ImpersonatedFetcher.fetch(url,
                        ScrapeLadder.impersonatedHeaders(
                                ScrapeLadder.DEFAULT_LANGUAGE));
                return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
            } catch (Exception e) {
                var m = e.getMessage();
                return ScrapeObservation.failed(url,
                        m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
            }
        };
    }

    /**
     * Rung 3: render through the stealth browser sidecar (JCLAW-1088).
     *
     * <p>Serves two failure modes the epic keeps separate on purpose. {@code THIN_CONTENT}
     * is a rendering problem and occurs at every protection tier including none;
     * {@code JS_CHALLENGE} is a fingerprint gate. {@code TURNSTILE} is not among them:
     * it is an interactive widget, so {@link BlockClassifier#nextRung} routes it to the
     * descoped provider rung and the ladder never spends a render on one. The report
     * separates
     * them structurally rather than by annotation: {@code byRendering} scores against the
     * corpus's client-rendered axis, {@code byStratum} against its protection axis, so a
     * rendering fix can never be credited to anti-bot work or the reverse.
     */
    public static Rung rung3() {
        return url -> {
            try {
                var fetched = RenderedFetcher.fetch(url);
                return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
            } catch (Exception e) {
                var m = e.getMessage();
                return ScrapeObservation.failed(url,
                        m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
            }
        };
    }

    /**
     * The escalation ladder, measured end to end (JCLAW-1099).
     *
     * <p>The other rungs measure one transport in isolation, which is what per-rung
     * attribution needs. This one measures the climb, and it exists because the union of
     * three separate rung runs was being quoted as a ladder figure while no caller could
     * obtain it. A number computed by hand across three runs measures nothing shipped.
     *
     * <p>It is the ladder over rung 1's <em>transport</em>, not over the tool: robots
     * admission, per-host pacing and the crawl's escalation budget belong to
     * {@code web_scrape} and are absent here, so this bounds what the ladder can reach
     * rather than reproducing what a crawl returns. {@link #rungScrape()} is the lane
     * that carries the tool's politeness.
     */
    public static Rung rungLadder() {
        return url -> {
            var plain = rung1().fetch(url);
            var first = new ScrapeLadder.Attempt(
                    ScrapeRung.PLAIN, null, plain.extractedText(),
                    BlockClassifier.classify(plain), plain.error());
            var best = ScrapeLadder.climb(url, first);
            if (best.servedBy() == ScrapeRung.PLAIN) return plain;
            return best.fetched() == null
                    ? ScrapeObservation.failed(url, best.detail())
                    : ScrapeObservation.of(best.fetched(), best.text());
        };
    }

    /** {@code detail} carries the head of a failing fetch's output. Without it a run
     *  reports ERROR without saying what the error was, which makes the harness
     *  unfalsifiable — the failure mode it exists to prevent, one level up.
     *
     *  <p>{@code nextRung} is what the aggregate cannot say: which rung would have to
     *  exist for this failure to become a success. {@code prerender} counts origins that
     *  would serve a declared crawler more than they served us. */
    public record Result(String url, String stratum, String vendor, String outcome,
                         String rendering, boolean ok, ScrapeReason reason,
                         ScrapeRung nextRung, boolean prerender,
                         int chars, boolean titleSeen, long ms, String detail) {}

    public record Score(int total, int ok, double rate) {}

    public record GateCheck(String criterion, double measured, double floor, boolean pass) {}

    public record Gate(boolean pass, List<GateCheck> checks) {}

    /**
     * The gate's floors as re-set on 2026-08-21
     * (docs/spikes/jclaw-1091-scrape-access-gate.md) — regression detectors set below the
     * observed minimum across three runs, not the original targets, which that doc keeps
     * as a stretch goal outside the gate.
     *
     * <p>They describe the local ladder, so only the ladder lane's verdict is the gate; a
     * single-rung run is scored against them for comparison.
     */
    private static final List<Map.Entry<String, Double>> STRATUM_FLOORS = List.of(
            Map.entry("unprotected-ssr", 95.0),
            Map.entry("unprotected-spa", 95.0),
            Map.entry("edge-served", 90.0),
            Map.entry("denied", 40.0),
            Map.entry("challenge", 36.0),
            Map.entry("interactive", 16.0));

    private static final double WEIGHTED_FLOOR = 88.0;
    private static final double EQUAL_ALLOCATION_FLOOR = 60.0;

    /** {@code byStratum} is what the epic gates on; {@code byVendor} answers "which
     *  WAFs can we get past", which an aggregate cannot. Both come from one run.
     *
     *  <p>{@code prevalenceWeighted} answers "what will an agent actually experience",
     *  {@code rate} answers "did we do the hard work". Both are reported because either
     *  alone is misleading: the corpus over-samples difficulty by design, and the web
     *  under-samples it. {@code prevalenceNote} carries the unreachable exclusion, which
     *  is worth about thirty points and must never travel separately from the number.
     *
     *  <p>{@code corpus} names which ruler produced all of it, because a corpus
     *  re-classified between runs is a different ruler. */
    public record RungReport(String rung, ScrapeCorpus.Identity corpus, Gate gate,
                             int attempted, int ok, double rate,
                             double prevalenceWeighted, String prevalenceNote,
                             Map<String, Score> byStratum, Map<String, Score> byVendor,
                             Map<String, Score> byRendering, Map<String, Integer> byReason,
                             Map<String, Integer> byNextRung, int prerenderCapable,
                             List<Result> results) {}

    /** Ceiling on one entry, well above the fetch timeout so it only fires on a hang. */
    private static final int RESULT_TIMEOUT_SECONDS = 120;

    public static RungReport run(String rungName, Rung rung, ScrapeCorpus.Corpus corpus,
                                 int concurrency) {
        return run(rungName, rung, ScrapeRung.PLAIN, corpus, concurrency);
    }

    /** {@code attempted} is the ladder position this rung occupies, so the report can say
     *  what to try <em>next</em> rather than re-suggesting the rung that just ran. */
    public static RungReport run(String rungName, Rung rung, ScrapeRung attempted,
                                 ScrapeCorpus.Corpus corpus, int concurrency) {
        var entries = corpus.entries();
        var results = new ArrayList<Result>();
        try (var pool = Executors.newFixedThreadPool(Math.max(1, concurrency))) {
            var futures = entries.stream()
                    .map(e -> pool.submit(() -> measure(rung, attempted, e)))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                var entry = entries.get(i);
                try {
                    results.add(futures.get(i).get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (InterruptedException _) {
                    // Restore the flag rather than swallow it: the caller decides whether
                    // an interrupted run is fatal, and it cannot if the flag is gone.
                    Thread.currentThread().interrupt();
                    results.add(errored(entry, "interrupted"));
                    break;
                } catch (ExecutionException | TimeoutException e) {
                    // Record it. Dropping the entry shrank the denominator, so a URL that
                    // hung made the reported access rate go UP — the one direction a
                    // measurement must never fail in.
                    results.add(errored(entry, reason(e)));
                }
            }
        }
        return report(rungName, corpus, results);
    }

    private static Result errored(ScrapeCorpus.Entry e, String detail) {
        return new Result(e.url(), e.stratum(), e.vendor(), e.outcome(), e.rendering(),
                false, ScrapeReason.ERROR, ScrapeRung.NONE, false, 0, false, 0, detail);
    }

    private static String reason(Exception e) {
        var cause = e.getCause() == null ? e : e.getCause();
        var m = cause.getMessage();
        return m == null || m.isBlank() ? cause.getClass().getSimpleName() : m;
    }

    private static Result measure(Rung rung, ScrapeRung attempted, ScrapeCorpus.Entry e) {
        long t0 = System.nanoTime();
        ScrapeObservation obs;
        try {
            obs = rung.fetch(e.url());
        } catch (RuntimeException ex) {
            obs = ScrapeObservation.failed(e.url(), String.valueOf(ex));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        var gt = e.groundTruth();
        var reason = BlockClassifier.classify(obs, gt.minChars());

        // The corpus's reject markers stay in play as an INDEPENDENT check on the
        // classifier rather than as its input. If a run ever scores an interstitial as
        // content, this is what catches it — and a benchmark whose only guard is the
        // component under test has no guard at all.
        var text = obs.extractedText() == null ? "" : obs.extractedText();
        if (reason == ScrapeReason.OK && gt.rejected(text)) {
            reason = ScrapeReason.JS_CHALLENGE;
        }

        boolean ok = reason == ScrapeReason.OK;
        var detail = obs.failed() ? obs.error() : text;
        return new Result(e.url(), e.stratum(), e.vendor(), e.outcome(), e.rendering(),
                ok, reason, BlockClassifier.nextRung(reason, attempted),
                BlockClassifier.hasPrerenderMarkers(obs),
                text.length(), gt.titleSeen(text), ms,
                ok ? null : detail.substring(0, Math.min(200, detail.length())).replace('\n', ' '));
    }

    private static RungReport report(String rungName, ScrapeCorpus.Corpus corpus,
                                     List<Result> results) {
        var byReason = new LinkedHashMap<String, Integer>();
        var byNextRung = new LinkedHashMap<String, Integer>();
        for (var r : results) {
            byReason.merge(r.reason().name(), 1, Integer::sum);
            if (!r.ok()) {
                byNextRung.merge(r.nextRung().name(), 1, Integer::sum);
            }
        }
        int ok = (int) results.stream().filter(Result::ok).count();
        int prerender = (int) results.stream()
                .filter(r -> !r.ok() && r.prerender()).count();
        double weighted = 0;
        boolean weightingAvailable = false;
        var note = "prevalence weighting unavailable";
        try {
            var weights = ScrapePrevalence.load();
            double covered = 0;
            var zeroWeight = new ArrayList<String>();
            for (var byOutcome : group(results, Result::outcome).entrySet()) {
                var share = weights.weight(byOutcome.getKey());
                // A renamed outcome is scored and then counts for nothing, so a broken
                // label reads as a lower ceiling unless the report names it.
                if (share == 0) zeroWeight.add(byOutcome.getKey());
                covered += share;
                weighted += share * byOutcome.getValue().rate();
            }
            weightingAvailable = true;
            // Stated, not corrected. An outcome the corpus lacks contributes no weight,
            // so the figure is an absolute share of the web rather than an average over
            // what was measured — dividing by the covered mass here would silently
            // restate every number the gate report already publishes.
            note = weights.note()
                    + "; corpus outcomes cover %.1f%% of the weighted mass".formatted(covered * 100)
                    + (zeroWeight.isEmpty() ? ""
                            : "; ZERO weight, contributing nothing: " + String.join(", ", zeroWeight));
        } catch (IOException _) {
            // A missing or malformed prevalence file must not fail a run: the
            // equal-allocation score is the gate, and this number is context alongside it.
        }
        double weightedPercent = Math.round(weighted * 10) / 10.0;
        double rate = rate(ok, results.size());
        var byStratum = group(results, Result::stratum);
        return new RungReport(rungName, corpus.identity(),
                gate(rate, weightedPercent, weightingAvailable, byStratum),
                results.size(), ok, rate, weightedPercent, note,
                byStratum, group(results, Result::vendor),
                group(results, Result::rendering), byReason, byNextRung, prerender,
                List.copyOf(results));
    }

    private static Gate gate(double rate, double weighted, boolean weightingAvailable,
                             Map<String, Score> byStratum) {
        var checks = new ArrayList<GateCheck>();
        // A prevalence file that would not load is a missing measurement, not a regression.
        if (weightingAvailable) {
            checks.add(check("overall, prevalence-weighted", weighted, WEIGHTED_FLOOR));
        }
        checks.add(check("local-only, equal-allocation", rate, EQUAL_ALLOCATION_FLOOR));
        for (var floor : STRATUM_FLOORS) {
            var score = byStratum.get(floor.getKey());
            if (score != null) {
                checks.add(check(floor.getKey(), score.rate(), floor.getValue()));
            }
        }
        return new Gate(checks.stream().allMatch(GateCheck::pass), List.copyOf(checks));
    }

    private static GateCheck check(String criterion, double measured, double floor) {
        return new GateCheck(criterion, measured, floor, measured >= floor);
    }

    private static Map<String, Score> group(List<Result> results,
                                            Function<Result, String> key) {
        var out = new LinkedHashMap<String, Score>();
        for (var r : results) {
            var k = key.apply(r);
            if (k == null) continue;
            var prev = out.getOrDefault(k, new Score(0, 0, 0));
            int total = prev.total() + 1;
            int ok = prev.ok() + (r.ok() ? 1 : 0);
            out.put(k, new Score(total, ok, rate(ok, total)));
        }
        return out;
    }

    private static double rate(int ok, int total) {
        return total == 0 ? 0 : Math.round(1000.0 * ok / total) / 10.0;
    }
}
