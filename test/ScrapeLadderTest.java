import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.FetchSidecarManager;
import services.StealthSidecarManager;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.RenderedFetcher;
import tools.scrape.ScrapeLadder;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The escalation ladder (JCLAW-1099).
 *
 * <p>Whether a given rung defeats a given WAF is a fact about that WAF, measured by the
 * corpus harness against the live web. What is pinned here is the wiring the harness
 * cannot see: that a usable result is never traded away for a higher rung's worse one,
 * that an install with no sidecars still succeeds, and that the ladder stops.
 */
class ScrapeLadderTest extends UnitTest {

    private final ScrapeConfigGuard config = new ScrapeConfigGuard();

    @AfterEach
    void restoreRungs() {
        config.restore();
    }

    private void disableEveryRung() {
        config.set(FetchSidecarManager.CFG_ENABLED, "false");
        config.set(StealthSidecarManager.CFG_ENABLED, "false");
    }

    private static ScrapeLadder.Attempt attempt(ScrapeRung rung, ScrapeReason reason, String text) {
        var fetched = new WebExtraction.FetchResult(
                text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8),
                "text/html", "https://example.test/");
        return new ScrapeLadder.Attempt(rung, fetched, text, reason, null, 0);
    }

    private static ScrapeLadder.Attempt plain(ScrapeReason reason, String text) {
        return attempt(ScrapeRung.PLAIN, reason, text);
    }

    @Test
    void aUsablePlainResultIsNeverEscalated() {
        // Climbing a page that already read is pure cost: seconds of browser time to
        // replace text we have. The guard is here rather than at the call site so every
        // caller gets it.
        var ok = plain(ScrapeReason.OK, "the article body");
        var result = ScrapeLadder.climb("https://example.test/", ok);
        assertSame(ok, result);
        assertEquals(ScrapeRung.PLAIN, result.servedBy());
        // The guard alone is unobservable while OK maps to no rung, so the mapping is
        // pinned beside it: whichever of the two changed, one of these fails.
        for (var attempted : ScrapeRung.values()) {
            assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.OK, attempted),
                    "a page that read must not be escalated after " + attempted);
        }
    }

    @Test
    void withNoRungsInstalledThePlainAttemptIsReturnedUnchanged() {
        // AC: absence degrades, never errors. An install without uv or without the
        // sidecar directories must still scrape, just no further than rung 1.
        disableEveryRung();
        assertFalse(ScrapeLadder.available());

        var blocked = plain(ScrapeReason.TRUST_BLOCK, null);
        var result = ScrapeLadder.climb("https://example.test/", blocked);
        assertSame(blocked, result, "a missing sidecar must not turn a block into an error");
        assertEquals(ScrapeRung.PLAIN, result.servedBy());
    }

    @Test
    void thinContentEscalatesWithoutABlockBeingDetected() {
        // A client-rendered page at zero protection is a rendering failure, not a
        // refusal. If THIN_CONTENT did not escalate, the rung that fixes the largest
        // single population in the corpus would be unreachable for it (JCLAW-1088).
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.THIN_CONTENT, ScrapeRung.PLAIN));
    }

    @Test
    void everyEscalationPathTerminates() {
        // climb() loops until nextRung names a rung nothing installed, so a mapping that
        // ever answered at or below the rung just attempted would spin. That, not the
        // uninstalled-rung exit, is what makes the loop finite — and it is asserted as
        // the property rather than by running a climb, which on a host with no sidecar
        // stops at the first isInstalled check whatever the mapping says.
        for (var reason : ScrapeReason.values()) {
            for (var attempted : ScrapeRung.values()) {
                var next = BlockClassifier.nextRung(reason, attempted);
                assertTrue(next == ScrapeRung.NONE || next.ordinal() > attempted.ordinal(),
                        "%s after %s answers %s, which does not advance the ladder"
                                .formatted(reason, attempted, next));
            }
        }
    }

    @Test
    void aDataDomeRefusalClimbsAsATrustBlockAndAGeoBlockStops() {
        for (var attempted : ScrapeRung.values()) {
            assertEquals(BlockClassifier.nextRung(ScrapeReason.TRUST_BLOCK, 403, attempted),
                    BlockClassifier.nextRung(ScrapeReason.DATADOME, 403, attempted), "DATADOME after " + attempted);
            assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.GEO_BLOCK, 403, attempted),
                    "every rung leaves from the banned country, so none follows " + attempted);
            assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.IP_BLOCK, 403, attempted),
                    "every rung leaves from the banned network or address, so none follows " + attempted);
        }
        assertEquals(ScrapeRung.IMPERSONATE, BlockClassifier.nextRung(ScrapeReason.DATADOME, 403, ScrapeRung.PLAIN));
        assertEquals(ScrapeRung.BROWSER, BlockClassifier.nextRung(ScrapeReason.DATADOME, 403, ScrapeRung.IMPERSONATE));
        assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.DATADOME, 451, ScrapeRung.PLAIN),
                "a stated refusal is never escalated, whoever serves it");
        assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.DATADOME, 404, ScrapeRung.PLAIN));
    }

    @Test
    void aChallengeReadOffARefusalStillClimbsThroughRungTwo() {
        // JCLAW-1304 names the challenge behind a 403; skipping rung 2 for one is a separate,
        // measured decision, so until then it routes exactly as the bare status did.
        for (var challenge : java.util.List.of(ScrapeReason.JS_CHALLENGE, ScrapeReason.TURNSTILE)) {
            for (var status : new int[] {403, 429, 503}) {
                for (var attempted : ScrapeRung.values()) {
                    assertEquals(BlockClassifier.nextRung(ScrapeReason.TRUST_BLOCK, attempted),
                            BlockClassifier.nextRung(challenge, status, attempted),
                            "%s refused with %d after %s".formatted(challenge, status, attempted));
                }
            }
        }
        assertEquals(ScrapeRung.IMPERSONATE,
                BlockClassifier.nextRung(ScrapeReason.TURNSTILE, 403, ScrapeRung.PLAIN));
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.TURNSTILE, 403, ScrapeRung.IMPERSONATE));
        // A challenge served as a page, not a refusal, goes straight to the browser.
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.JS_CHALLENGE, 0, ScrapeRung.PLAIN));
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.TURNSTILE, 0, ScrapeRung.PLAIN));
    }

    @Test
    void aTurnstileGateIsRenderedWhenRungThreeIsInstalled() {
        assertEquals(ScrapeRung.BROWSER, BlockClassifier.nextRung(ScrapeReason.TURNSTILE));
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.TURNSTILE, ScrapeRung.IMPERSONATE));
        assertEquals(ScrapeRung.PROVIDER,
                BlockClassifier.nextRung(ScrapeReason.TURNSTILE, ScrapeRung.BROWSER),
                "past the browser only the descoped rung is left, which nothing installs");
        assertEquals(ScrapeRung.NONE, BlockClassifier.nextRung(ScrapeReason.POLICY_BLOCK),
                "a stated refusal is still never escalated, so never clicked");

        assertEquals(RenderedFetcher.available(),
                ScrapeLadder.wouldAttempt(ScrapeReason.TURNSTILE, 0),
                "a Turnstile gate is attempted exactly when rung 3 is installed");
        config.set(StealthSidecarManager.CFG_ENABLED, "false");
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.TURNSTILE, 0));
    }

    @Test
    void aRungSwitchedOffIsClimbedPastNotStoppedAt() {
        assumeTrue(RenderedFetcher.available(), "rung 3 is not installed here, so nothing above rung 2 can be reached");
        config.set(FetchSidecarManager.CFG_ENABLED, "false");
        assertEquals(ScrapeRung.BROWSER, ScrapeLadder.nextInstalledRung(ScrapeReason.TRUST_BLOCK, 403, ScrapeRung.PLAIN),
                "a refusal the impersonation rung would have taken goes on to the browser");
        assertTrue(ScrapeLadder.wouldAttempt(ScrapeReason.TRUST_BLOCK, 403), "and the budget check agrees");

        config.set(StealthSidecarManager.CFG_ENABLED, "false");
        assertEquals(ScrapeRung.NONE, ScrapeLadder.nextInstalledRung(ScrapeReason.TRUST_BLOCK, 403, ScrapeRung.PLAIN));
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.TRUST_BLOCK, 403));
    }

    @Test
    void theLadderStopsWhenNothingFurtherWouldHelp() {
        // POLICY_BLOCK is a licensing or geo refusal. No transport defeats it, and a
        // ladder that kept climbing would spend a browser render to be refused again.
        // Rungs are left at whatever this host has: NONE is never installed, so the
        // climb must return without a request on any host.
        var refused = plain(ScrapeReason.POLICY_BLOCK, null);
        assertSame(refused, ScrapeLadder.climb("https://example.test/", refused));
    }

    @Test
    void aStructuralErrorEscalatesButATimeoutDoesNot() {
        // ERROR only reaches the ladder after TransientRetryInterceptor has retried the
        // retryable statuses, so what is left is structural — a persistent 400, a
        // redirect loop — which a browser handles natively.
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.ERROR, ScrapeRung.PLAIN));
        // A slow origin will not answer a browser faster, and a render is the most
        // expensive possible way to wait.
        assertEquals(ScrapeRung.NONE,
                BlockClassifier.nextRung(ScrapeReason.TIMEOUT, ScrapeRung.PLAIN));
    }

    @Test
    void aStatedPolicyRefusalIsNeverEscalated() {
        // Load-bearing, and not a metric decision: an origin that says it blocks agents
        // is refusing on identity. Rung 3 demonstrably reads several of these — the
        // ladder declines to. Four corpus entries sit behind this and stay there.
        for (var attempted : java.util.List.of(ScrapeRung.PLAIN, ScrapeRung.IMPERSONATE)) {
            assertEquals(ScrapeRung.NONE,
                    BlockClassifier.nextRung(ScrapeReason.POLICY_BLOCK, attempted),
                    "policy refusals must not be escalated to a stealth rung");
        }
    }

    @Test
    void aDeadLinkIsNeverEscalated() {
        // 404/410 used to classify as ERROR, which escalates to BROWSER. Crawls hit dead
        // links constantly, so a handful of them spent the whole escalation budget on
        // renders that return the same 404 — and suppressed the pages that needed it.
        assertEquals(ScrapeReason.NOT_FOUND, BlockClassifier.classify(
                services.scrape.ScrapeObservation.failed(
                        "https://example.test/gone", "HTTP 404 fetching https://example.test/gone")));
        for (var attempted : java.util.List.of(ScrapeRung.PLAIN, ScrapeRung.IMPERSONATE)) {
            assertEquals(ScrapeRung.NONE,
                    BlockClassifier.nextRung(ScrapeReason.NOT_FOUND, attempted),
                    "a page the origin says is absent is not a transport problem");
        }
    }

    @Test
    void theBestAttemptIsKeptAndATieGoesToTheLowerRung() throws Exception {
        // The whole reason climb() carries a "best" alongside "last": rung 3 reads far
        // more than rung 2 and still loses corpus entries to it, because a browser
        // earns the JavaScript shell where a plain client was served the article. An
        // empty shell must never displace a partial page.
        //
        // Reached by reflection because the ladder's own escalation path needs a live
        // sidecar to run, and the tie-break is the one rule in this class that no
        // sidecar-free test reaches.
        var better = ScrapeLadder.class.getDeclaredMethod(
                "better", ScrapeLadder.Attempt.class, ScrapeLadder.Attempt.class);
        better.setAccessible(true);

        var partial = plain(ScrapeReason.THIN_CONTENT, "some partial content");
        var shell = attempt(ScrapeRung.BROWSER, ScrapeReason.THIN_CONTENT, "some partial conten");
        var equalLength = attempt(ScrapeRung.BROWSER, ScrapeReason.THIN_CONTENT, "some partial content");
        var fuller = attempt(ScrapeRung.BROWSER, ScrapeReason.THIN_CONTENT, "a good deal more content");

        assertSame(partial, better.invoke(null, partial, shell),
                "a browser returning less than rung 1 did must not displace it");
        assertSame(partial, better.invoke(null, partial, equalLength),
                "on a tie the cheaper rung's result is the one kept");
        assertSame(fuller, better.invoke(null, partial, fuller),
                "a higher rung that read more is what the climb is for");

        var usableButShort = attempt(ScrapeRung.BROWSER, ScrapeReason.OK, "read");
        assertSame(usableButShort, better.invoke(null, partial, usableButShort),
                "a usable page beats a longer unusable one");
        var usablePlain = plain(ScrapeReason.OK, "some partial content");
        assertSame(usablePlain, better.invoke(null, usablePlain, fuller),
                "and a usable lower rung is not traded for a longer failure");
    }

    @Test
    void wouldAttemptAnswersBeforeABudgetIsSpent() {
        // The budget is claimed before the climb, so a caller must be able to ask whether
        // the climb would issue any request at all. Reasons no rung addresses used to
        // spend a slot and be counted in the "escalated N pages" line.
        //
        // The assume is load-bearing, not defensive: with no sidecar installed
        // isInstalled() is false for every rung, so all three answers below are false
        // whatever the classifier says, and the assertions measure nothing. This test
        // used to pass on such a host while a POLICY_BLOCK -> BROWSER regression sailed
        // through, so it now skips rather than pretending to have checked.
        assumeTrue(ScrapeLadder.available(),
                "no scrape sidecar installed — wouldAttempt cannot be distinguished here");

        assertTrue(ScrapeLadder.wouldAttempt(ScrapeReason.THIN_CONTENT, 0),
                "a client-rendered page is exactly what the browser rung is for");
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.POLICY_BLOCK, 0),
                "a policy refusal reaches no installed rung");
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.NOT_FOUND, 0),
                "a dead link reaches no installed rung");

        disableEveryRung();
        assertFalse(ScrapeLadder.wouldAttempt(ScrapeReason.THIN_CONTENT, 0),
                "with no sidecar installed nothing is attempted");
    }

    @Test
    void anEscalatedFetchCarriesTheCallersLanguage() {
        // A crawl asked for "ja" and the escalated fetch silently reverted to en-US, so
        // the crawl mixed two languages with only a rung marker to explain why.
        //
        // Asserted as a property rather than a literal: the guarantee is that the
        // caller's language leads and that anything else stays acceptable, not the
        // exact spacing. A bare "ja" invites a 406 from a site with no Japanese, and a
        // language preference must never cost us the page.
        var header = ScrapeLadder.impersonatedHeaders("ja").get("Accept-Language");
        assertTrue(header.startsWith("ja"), header);
        assertTrue(header.contains("*"), header);
    }

    @Test
    void everyRungAboveTheFirstAcceptsALanguage() {
        // The header test above passes even if nothing calls impersonatedHeaders with
        // the crawl's language, and it says nothing at all about rung 3 — which took no
        // headers when that test was written, so a "ja" crawl escalated to the browser
        // silently returned English. These pin the plumbing rather than the value.
        assertNotNull(assertDoesNotThrow(() -> ScrapeLadder.class
                .getDeclaredMethod("attempt", ScrapeRung.class, String.class, String.class, java.util.Optional.class)),
                "the ladder must carry a language into each rung it attempts");
        assertNotNull(assertDoesNotThrow(() -> tools.scrape.RenderedFetcher.class
                .getMethod("fetch", String.class, String.class)),
                "rung 3 must accept a language, not just rung 2");
        assertNotNull(assertDoesNotThrow(() -> ScrapeLadder.class
                .getMethod("climb", String.class, ScrapeLadder.Attempt.class, String.class)),
                "a caller with a language preference must be able to hand it to the climb");
    }

    @Test
    void escalationSurvivesLosingEitherRungButNotBoth() {
        // available() is a disjunction, and comparing it against one of its own operands
        // would hold for an AND too. Observing the difference needs at least one rung
        // genuinely installed, so a bare host skips rather than restating x==x.
        boolean fetch = FetchSidecarManager.available();
        boolean stealth = StealthSidecarManager.available();
        assumeTrue(fetch || stealth,
                "no scrape sidecar installed — the disjunction is unobservable here");

        config.set(FetchSidecarManager.CFG_ENABLED, String.valueOf(fetch));
        config.set(StealthSidecarManager.CFG_ENABLED, "false");
        assertEquals(fetch, ScrapeLadder.available(),
                "rung 2 alone must keep the ladder available");

        config.set(FetchSidecarManager.CFG_ENABLED, "false");
        config.set(StealthSidecarManager.CFG_ENABLED, String.valueOf(stealth));
        assertEquals(stealth, ScrapeLadder.available(),
                "rung 3 alone must keep the ladder available");

        disableEveryRung();
        assertFalse(ScrapeLadder.available());
    }
}
