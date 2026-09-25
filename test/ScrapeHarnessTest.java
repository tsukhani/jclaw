import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeCorpus;
import services.scrape.ScrapeHarness;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared block classifier and the escalation decision (JCLAW-1081, JCLAW-1086).
 *
 * <p>The known-zero and known-one cases are the point: a harness that reports plausible
 * nonsense is worse than no harness, because it gets believed. Everything else here
 * keeps those two honest.
 *
 * <p>Fixtures carry raw markup and extracted text separately, because that separation is
 * what JCLAW-1086 bought. Readability strips scripts, so after extraction a Cloudflare
 * gate and a client-rendered app are both zero characters — the markers that tell them
 * apart survive only in the raw body.
 */
class ScrapeHarnessTest extends UnitTest {

    private static ScrapeObservation obs(String rawHtml, String extracted) {
        var fr = new WebExtraction.FetchResult(rawHtml.getBytes(StandardCharsets.UTF_8),
                "text/html", "https://x.test/");
        return ScrapeObservation.of(fr, extracted);
    }

    /** A real interstitial: valid HTML that extracts to a couple of readable lines. */
    private static final String CHALLENGE_RAW = """
            <html><head><title>Just a moment...</title>
            <script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1"></script>
            </head><body><div>Verifying you are human. This may take a few seconds.</div>
            </body></html>""";
    private static final String CHALLENGE_TEXT =
            "# Just a moment...\n\nVerifying you are human. This may take a few seconds.";

    private static final String ARTICLE_TEXT =
            "# Understanding Widgets\n\nWidgets combine several parts into one. ".repeat(20);

    @Test
    void knownZero_aChallengePageIsNeverScoredAsContent() {
        assertEquals(ScrapeReason.JS_CHALLENGE,
                BlockClassifier.classify(obs(CHALLENGE_RAW, CHALLENGE_TEXT)));
    }

    @Test
    void knownOne_aRealArticleScoresOk() {
        assertEquals(ScrapeReason.OK,
                BlockClassifier.classify(obs("<html><body>...</body></html>", ARTICLE_TEXT)));
    }

    @Test
    void aTurnstileGateWithNoContentBehindItIsTurnstile() {
        var raw = "<html><head><script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\">"
                + "</script></head><body><div class=\"cf-turnstile\"></div></body></html>";
        assertEquals(ScrapeReason.TURNSTILE, BlockClassifier.classify(obs(raw, "")));
    }

    @Test
    void aPageThatMerelyEmbedsTurnstileIsNotAGate() {
        // The defect that inverted the first corpus: wiley.com and onetrust.com embed the
        // widget and serve real content. Marker presence alone is not a gate — it takes a
        // marker AND nothing readable behind it.
        var raw = "<html><body><div class=\"cf-turnstile\"></div><article>real</article></body></html>";
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(obs(raw, ARTICLE_TEXT)));
    }

    @Test
    void aClientRenderedShellIsThinContentNotABlock() {
        // No gate marker, no text: the origin served us, there is simply nothing
        // server-rendered. A rendering gap, not an anti-bot one — and the distinction the
        // provisional classifier could not make, because after extraction this and a JS
        // gate are both zero characters.
        var raw = "<html><head><title>App</title><script src=\"/app.js\"></script></head>"
                + "<body><div id=\"root\"></div></body></html>";
        assertEquals(ScrapeReason.THIN_CONTENT, BlockClassifier.classify(obs(raw, "# App")));
    }

    @Test
    void shortNonHtmlResponsesAreContentNotThinPages() {
        // Regression: the thin-content floor was applied to every content type, so a
        // seven-character JSON body — a complete, valid response — was discarded as an
        // empty page. A gate is an HTML phenomenon; JSON, plain text and extracted PDF
        // prose are content at any length.
        var fr = new WebExtraction.FetchResult("{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                "application/json", "https://api.test/x");
        assertEquals(ScrapeReason.OK,
                BlockClassifier.classify(ScrapeObservation.of(fr, "{\"a\":1}")));
    }

    @Test
    void anEmptyNonHtmlResponseIsStillThin() {
        var fr = new WebExtraction.FetchResult(new byte[0], "application/json",
                "https://api.test/x");
        assertEquals(ScrapeReason.THIN_CONTENT,
                BlockClassifier.classify(ScrapeObservation.of(fr, "")));
    }

    @Test
    void aLongPageDiscussingScrapingIsNotAPolicyBlock() {
        // Regression: oxylabs.io scored POLICY_BLOCK off 7,947 characters of marketing
        // copy. A proxy vendor's own page says "scraping is prohibited"; that is content.
        var raw = "<html><body>our terms note that scraping is prohibited on some targets</body></html>";
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(obs(raw, ARTICLE_TEXT)));
    }

    @Test
    void aShortPolicyRefusalIsStillDetected() {
        var raw = "<html><body>automated access is not permitted</body></html>";
        assertEquals(ScrapeReason.POLICY_BLOCK,
                BlockClassifier.classify(obs(raw, "automated access is not permitted")));
    }

    @Test
    void httpStatusIsRecoveredFromTheFailureMessage() {
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(
                ScrapeObservation.failed("https://x.test/", "HTTP 403 fetching https://x.test/")));
        assertEquals(ScrapeReason.POLICY_BLOCK, BlockClassifier.classify(
                ScrapeObservation.failed("https://x.test/", "HTTP 451 fetching https://x.test/")));
        assertEquals(ScrapeReason.TIMEOUT, BlockClassifier.classify(
                ScrapeObservation.failed("https://x.test/", "Read timed out")));
        assertEquals(ScrapeReason.TIMEOUT, BlockClassifier.classify(
                ScrapeObservation.failed("https://x.test/", "timeout")));
    }

    @Test
    void nullIsErrorNotSilentPass() {
        assertEquals(ScrapeReason.ERROR, BlockClassifier.classify(null));
    }

    @Test
    void thinContentEscalatesToTheBrowserSkippingImpersonation() {
        // A different TLS fingerprint cannot execute JavaScript, so sending a SPA to the
        // impersonation rung spends a request arriving at the same empty page.
        assertEquals(ScrapeRung.BROWSER, BlockClassifier.nextRung(ScrapeReason.THIN_CONTENT));
        assertEquals(ScrapeRung.IMPERSONATE, BlockClassifier.nextRung(ScrapeReason.TRUST_BLOCK));
        assertEquals(ScrapeRung.BROWSER, BlockClassifier.nextRung(ScrapeReason.JS_CHALLENGE));
        assertEquals(ScrapeRung.PROVIDER, BlockClassifier.nextRung(ScrapeReason.TURNSTILE));
    }

    @Test
    void aPolicyBlockEscalatesToNothingBecauseTheAnswerIsIdentityNotEvasion() {
        assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.POLICY_BLOCK));
        assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.OK));
    }

    @Test
    void prerenderMarkersAreRecorded() {
        // abundent.academy: 68 characters to a browser UA, 5,169 to Googlebot. Counted
        // as evidence for whether the descoped identity lane has measurable value.
        var raw = "<html><head><script>window.prerenderready = false;</script>"
                + "<meta name=\"fragment\" content=\"!\"></head><body><div id=\"app\"></div></body></html>";
        var o = obs(raw, "# Site");
        assertEquals(ScrapeReason.THIN_CONTENT, BlockClassifier.classify(o));
        assertTrue(BlockClassifier.hasPrerenderMarkers(o));
        assertFalse(BlockClassifier.hasPrerenderMarkers(obs("<html><body>x</body></html>", ARTICLE_TEXT)));
    }

    @Test
    void harnessScoresACorpusWithoutTouchingTheNetwork() throws Exception {
        var json = """
                {"tranco_list_id":"TEST","probed_on":"2026-08-20","allocation":"equal",
                 "per_stratum":1,"strata":["unprotected-ssr","interactive"],
                 "entries":[
                  {"url":"https://ok.test","stratum":"unprotected-ssr","vendor":"none",
                   "outcome":"served","rendering":"ssr","rank":1,
                   "ground_truth":{"min_chars":300,"reject_markers":["just a moment"]}},
                  {"url":"https://blocked.test","stratum":"interactive","vendor":"datadome",
                   "outcome":"interactive","rendering":null,"rank":2,
                   "ground_truth":{"min_chars":500,"reject_markers":["just a moment"]}}]}
                """;
        var f = Files.createTempFile("scrape-corpus", ".json");
        Files.writeString(f, json);
        var corpus = ScrapeCorpus.load(f);
        assertTrue(corpus.isEqualAllocation());

        ScrapeHarness.Rung stub = url -> url.contains("ok.test")
                ? obs("<html><body>ok</body></html>", ARTICLE_TEXT)
                : obs(CHALLENGE_RAW, CHALLENGE_TEXT);
        var rep = ScrapeHarness.run("stub", stub, corpus, 2);

        assertEquals(2, rep.attempted());
        assertEquals(1, rep.ok());
        assertEquals(50.0, rep.rate(), 0.01);
        assertEquals(100.0, rep.byStratum().get("unprotected-ssr").rate(), 0.01);
        assertEquals(0.0, rep.byStratum().get("interactive").rate(), 0.01);
        assertEquals(0.0, rep.byVendor().get("datadome").rate(), 0.01);
        assertFalse(rep.byRendering().containsKey("null"));
        // Failures are attributed to the rung that would address them.
        assertEquals(1, rep.byNextRung().get(ScrapeRung.BROWSER.name()));
    }

    @Test
    void groundTruthOverridesTheClassifierRatherThanFeedingIt() throws Exception {
        // The corpus's reject markers are an INDEPENDENT check. A benchmark whose only
        // guard is the component under test has no guard at all: here the classifier is
        // fed a body with no marker and plenty of text, so it says OK, and the entry's
        // ground truth is what catches it.
        var json = """
                {"allocation":"equal","strata":["unprotected-ssr"],"entries":[
                  {"url":"https://sneaky.test","stratum":"unprotected-ssr","vendor":"none",
                   "outcome":"served","rendering":"ssr","rank":1,
                   "ground_truth":{"min_chars":50,"reject_markers":["site-specific gate phrase"]}}]}
                """;
        var f = Files.createTempFile("scrape-gt", ".json");
        Files.writeString(f, json);
        var corpus = ScrapeCorpus.load(f);

        ScrapeHarness.Rung stub = url ->
                obs("<html><body>x</body></html>", "site-specific gate phrase " + ARTICLE_TEXT);
        var rep = ScrapeHarness.run("stub", stub, corpus, 1);

        assertEquals(0, rep.ok(), "ground truth must veto a classifier OK");
        assertEquals(ScrapeReason.JS_CHALLENGE, rep.results().get(0).reason());
        Files.deleteIfExists(f);
    }

    // ==================== The gate verdict ====================

    /** One entry per stratum, each carrying the outcome that stratum's prevalence weight
     *  is filed under — so the four together cover the reachable web exactly once. */
    private static ScrapeCorpus.Corpus fourStratumCorpus() throws Exception {
        var json = """
                {"allocation":"equal",
                 "strata":["unprotected-ssr","challenge","denied","interactive"],
                 "entries":[
                  {"url":"https://ssr.test","stratum":"unprotected-ssr","vendor":"none",
                   "outcome":"served","rendering":"ssr","rank":1,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}},
                  {"url":"https://challenge.test","stratum":"challenge","vendor":"cloudflare",
                   "outcome":"challenge","rendering":"ssr","rank":2,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}},
                  {"url":"https://denied.test","stratum":"denied","vendor":"akamai",
                   "outcome":"denied","rendering":"ssr","rank":3,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}},
                  {"url":"https://interactive.test","stratum":"interactive","vendor":"datadome",
                   "outcome":"interactive","rendering":"ssr","rank":4,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}}]}
                """;
        var f = Files.createTempFile("scrape-gate", ".json");
        Files.writeString(f, json);
        var corpus = ScrapeCorpus.load(f);
        Files.deleteIfExists(f);
        return corpus;
    }

    private static ScrapeHarness.GateCheck check(ScrapeHarness.RungReport rep, String criterion) {
        return rep.gate().checks().stream()
                .filter(c -> c.criterion().equals(criterion)).findFirst().orElse(null);
    }

    @Test
    void aRunThatClearsEveryFloorPasses() throws Exception {
        // The floors are JCLAW-1091's, asserted here so a silent retune shows up as a
        // test change rather than only as a nicer number.
        var rep = ScrapeHarness.run("stub", url -> obs("<html><body>x</body></html>", ARTICLE_TEXT),
                fourStratumCorpus(), 2);

        assertTrue(rep.gate().pass(), "every stratum read: " + rep.gate().checks());
        assertEquals(88.0, check(rep, "overall, prevalence-weighted").floor(), 1e-9);
        assertEquals(60.0, check(rep, "local-only, equal-allocation").floor(), 1e-9);
        assertEquals(36.0, check(rep, "challenge").floor(), 1e-9);
        assertEquals(40.0, check(rep, "denied").floor(), 1e-9);
        assertEquals(16.0, check(rep, "interactive").floor(), 1e-9);
        // A stratum this corpus does not carry is not scored at all — scoring an absent
        // one as zero would fail every partial sweep.
        assertNull(check(rep, "edge-served"));
    }

    @Test
    void oneStratumBelowItsFloorFailsTheWholeGate() throws Exception {
        // The aggregate is what an equal-allocation corpus exists to stop carrying the
        // hard strata: here the weighted figure still clears 88 and the overall rate
        // still clears 60, and the run is a fail anyway. The stratum dropped is the
        // rarest one on the live web, so the aggregate stays clear of its floor however
        // the shipped prevalence file is re-probed.
        var rep = ScrapeHarness.run("stub",
                url -> url.contains("interactive.test")
                        ? obs(CHALLENGE_RAW, CHALLENGE_TEXT)
                        : obs("<html><body>x</body></html>", ARTICLE_TEXT),
                fourStratumCorpus(), 2);

        assertFalse(rep.gate().pass(), "a stratum floor missed is a failed gate");
        assertFalse(check(rep, "interactive").pass());
        assertTrue(check(rep, "overall, prevalence-weighted").pass(),
                "the aggregate must not be what caught it: " + rep.gate().checks());
        assertTrue(check(rep, "local-only, equal-allocation").pass());
        assertEquals(1, rep.gate().checks().stream().filter(c -> !c.pass()).count(),
                "exactly one criterion failed: " + rep.gate().checks());
    }

    // ==================== Which ruler scored the run ====================

    @Test
    void aCorpusThatDriftedOutOfEqualAllocationIsNoLongerScoredAsEqual() throws Exception {
        // build_corpus.py deliberately never moves the "allocation" label when
        // re-classification shifts entries between strata, so a check of the label alone
        // could never fire. The counts the entries realise are what the gate is scored on.
        var skewed = """
                {"allocation":"equal","strata":["a","b"],"entries":[
                 {"url":"https://1.test","stratum":"a","vendor":"none","outcome":"served",
                  "rendering":"ssr","rank":1,"ground_truth":{"min_chars":10,"reject_markers":[]}},
                 {"url":"https://2.test","stratum":"a","vendor":"none","outcome":"served",
                  "rendering":"ssr","rank":2,"ground_truth":{"min_chars":10,"reject_markers":[]}},
                 {"url":"https://3.test","stratum":"a","vendor":"none","outcome":"served",
                  "rendering":"ssr","rank":3,"ground_truth":{"min_chars":10,"reject_markers":[]}},
                 {"url":"https://4.test","stratum":"b","vendor":"none","outcome":"served",
                  "rendering":"ssr","rank":4,"ground_truth":{"min_chars":10,"reject_markers":[]}}]}
                """;
        var f = Files.createTempFile("scrape-skew", ".json");
        Files.writeString(f, skewed);
        var corpus = ScrapeCorpus.load(f);
        Files.deleteIfExists(f);

        assertEquals("equal", corpus.allocation(), "the declared design is untouched");
        assertFalse(corpus.isEqualAllocation(), "3 against 1 is not equal allocation");
        assertEquals(3, corpus.realizedCounts().get("a").intValue());
        assertEquals(1, corpus.realizedCounts().get("b").intValue());

        assertTrue(fourStratumCorpus().isEqualAllocation(),
                "and an evenly realised corpus still qualifies");
    }

    @Test
    void theCorpusFingerprintMovesWithWhatDecidesAScoreAndNotWithProvenance() throws Exception {
        var template = """
                {"allocation":"equal","strata":["a"],"entries":[
                 {"url":"https://1.test","stratum":"a","vendor":"none","outcome":"served",
                  "rendering":"ssr","rank":%d,
                  "ground_truth":{"min_chars":%d,"reject_markers":[]}}]}
                """;
        var base = fingerprintOf(template.formatted(1, 300));
        assertNotNull(base);
        assertEquals(base, fingerprintOf(template.formatted(9_999, 300)),
                "rank is provenance — a re-ranked corpus scores every run identically");
        assertNotEquals(base, fingerprintOf(template.formatted(1, 900)),
                "a moved pass threshold is a different ruler, and must say so");
    }

    private static String fingerprintOf(String json) throws Exception {
        var f = Files.createTempFile("scrape-fp", ".json");
        Files.writeString(f, json);
        var fingerprint = ScrapeCorpus.load(f).identity().fingerprint();
        Files.deleteIfExists(f);
        return fingerprint;
    }
}
