package controllers;

import play.mvc.Before;
import play.mvc.Controller;
import services.scrape.ScrapeCorpus;
import services.scrape.ScrapeHarness;
import services.scrape.ScrapeRung;
import utils.ApiResponses;

import java.io.IOException;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Runs the CF-100 corpus against one rung and returns the scored report (JCLAW-1081).
 *
 * <p>Lives behind an endpoint rather than in the offline {@code ./jclaw.sh evals} path
 * for the same reason {@code /api/evals/capture} does: the thing being measured is the
 * shipped fetch stack — OkHttp, SsrfGuard, Readability, and later the sidecars — which
 * only exists inside a booted app. A shell script with curl would measure curl.
 *
 * <p>Gated by {@link LoadtestAuthCheck}: loopback origin plus {@code X-Loadtest-Auth}
 * carrying {@code application.secret}. Same gate as loadtest and eval capture rather
 * than a third one, and it fits — this endpoint makes outbound requests to a hundred
 * third-party origins, which is not something a passing visitor should be able to
 * trigger.
 */
public class ApiScrapeTestController extends Controller {

    /** Bound on outbound fan-out: the corpus is a hundred unrelated third parties and
     *  the point is to measure access, not to arrive as a burst. */
    private static final int MAX_CONCURRENCY = 16;

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/scrape/harness} with {@code {"rung": "1", "concurrency": <n>?}}.
     *
     * <p>One rung per call, never the escalation ladder: the ladder returns a single
     * outcome, and per-rung attribution is the only reason this harness exists.
     */
    @AgentAccess(value = OPERATOR_ONLY, reason = "one call issues up to 150 outbound fetches")
    public static void harness() {
        var body = JsonBodyReader.readJsonBody();
        var rungId = body != null && body.has("rung") ? body.get("rung").getAsString() : "1";
        int concurrency = Math.clamp(
                body != null && body.has("concurrency") ? body.get("concurrency").getAsInt() : 8,
                1, MAX_CONCURRENCY);

        ScrapeCorpus.Corpus corpus;
        try {
            corpus = ScrapeCorpus.load();
        } catch (IOException _) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "No corpus at %s — build it with evals/scrape/build_corpus.py"
                            .formatted(ScrapeCorpus.DEFAULT_PATH));
            throw ApiResponses.unreachable();
        }

        if (!corpus.isEqualAllocation()) {
            // The epic gate only forces work on the hard tiers under equal allocation;
            // scoring a proportional corpus against the same threshold would report a
            // pass that means nothing. Refuse rather than qualify it in a footnote.
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    ("Corpus is not equal-allocation (declared '%s', realised %s) — the gate "
                            + "threshold is only meaningful against an equal-allocation corpus.")
                            .formatted(corpus.allocation(), corpus.realisedCounts()));
            throw ApiResponses.unreachable();
        }

        var rung = switch (rungId) {
            case "1" -> ScrapeHarness.rung1();
            case "2" -> ScrapeHarness.rung2();
            case "3" -> ScrapeHarness.rung3();
            case "ladder" -> ScrapeHarness.rungLadder();
            case "scrape" -> ScrapeHarness.rungScrape();
            default -> null;
        };
        // Ladder position, not rung id: "scrape" is rung 1's transport wearing the tool's
        // politeness, so it must not be told to escalate past impersonation.
        // "ladder" sits at PLAIN, not BROWSER: it climbs per URL, so no single rung
        // describes the run. Pinning it at the ceiling made nextRung answer BROWSER+1 —
        // the descoped provider rung — for every failure, and byNextRung is the column
        // used to decide what to build next. At PLAIN it names the cheapest rung that
        // addresses the reason, which is a claim the data supports.
        var attempted = switch (rungId) {
            case "2" -> ScrapeRung.IMPERSONATE;
            case "3" -> ScrapeRung.BROWSER;
            default -> ScrapeRung.PLAIN;
        };
        if (rung == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Unknown rung '%s'. Available: 1, 2, 3, ladder, scrape."
                            .formatted(rungId));
            throw ApiResponses.unreachable();
        }

        renderJSON(GSON.toJson(
                ScrapeHarness.run("rung" + rungId, rung, attempted, corpus, concurrency)));
    }
}
