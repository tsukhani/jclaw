import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeCorpus;
import services.scrape.ScrapeHarness;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.RenderedFetcher;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

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
    void anUnsupportedBrowserPageIsARefusalThatEscalates() {
        // canva.com's answer to rung 1's User-Agent, served as a 200: it scored OK and the ladder stopped there.
        var raw = "<html><head><title>unsupported client – canva</title></head><body><h1>please update your browser</h1>"
                + "<p>it seems you are using an old or unsupported browser.</p></body></html>";
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(obs(raw, "u".repeat(920))));
        assertEquals(ScrapeRung.IMPERSONATE, BlockClassifier.nextRung(ScrapeReason.TRUST_BLOCK, ScrapeRung.PLAIN));
    }

    @Test
    void aLongPageAboutUpdatingBrowsersIsContent() {
        var raw = "<html><head><title>how to update your browser</title></head><body><article>guide</article></body></html>";
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(obs(raw, ARTICLE_TEXT.repeat(2))));
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
        assertEquals(ScrapeRung.BROWSER, BlockClassifier.nextRung(ScrapeReason.TURNSTILE));
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

    // ==================== Refused responses (JCLAW-1304) ====================

    private static final String REFUSED_URL = "https://x.test/";
    private static final Map<String, String> CF_CHALLENGE = Map.of("cf-mitigated", "challenge");

    /** A Cloudflare challenge as served with a 403: the type is named in the inline options,
     *  and the visible text is in whatever language the visitor's locale picked. */
    private static String challenge(String type, String title, String noscript) {
        return """
                <!DOCTYPE html><html><head><title>%s</title>
                <meta name="robots" content="noindex,nofollow"></head><body>
                <noscript><span id="challenge-error-text">%s</span></noscript>
                <script>(function(){window._cf_chl_opt={cvId: '3',cZone: "x.test",cType: '%s',
                cRay: '8c4f0d2e9a1b2c3d',cH: 'abc'};var cpo=document.createElement('script');
                cpo.src='/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1?ray=8c4f0d2e9a1b2c3d';
                document.getElementsByTagName('head')[0].appendChild(cpo);}());</script>
                </body></html>""".formatted(title, noscript, type);
    }

    private static final String MANAGED_EN = challenge("managed",
            "Just a moment...", "Enable JavaScript and cookies to continue");
    private static final String MANAGED_FR = challenge("managed",
            "Un instant\u2026", "Activez JavaScript et les cookies pour continuer");

    /** Cloudflare's firewall block page: no challenge, but its detection script loads from
     *  the same /cdn-cgi/challenge-platform/ path a challenge does. */
    private static final String CF_BLOCK_PAGE = """
            <!DOCTYPE html><html><head><title>Attention Required! | Cloudflare</title></head>
            <body><h1>Sorry, you have been blocked</h1><p>Cloudflare Ray ID: 8c4f0d2e9a1b2c3d</p>
            <script>(function(){var s=document.createElement('script');
            s.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js';document.head.appendChild(s);})();
            </script></body></html>""";

    private static final String ARTICLE_HTML = "<html><head><title>Widgets</title></head><body><article><p>"
            + "Widgets combine several parts into one, and this page explains how. ".repeat(20)
            + "</p></article></body></html>";

    private static ScrapeObservation refused(int status, String body, Map<String, String> headers) {
        return ScrapeObservation.failed(REFUSED_URL, new WebExtraction.HttpStatusException(status,
                REFUSED_URL, body.getBytes(StandardCharsets.UTF_8), "text/html; charset=UTF-8", headers));
    }

    @Test
    void aRefusalCarryingCfMitigatedAndAManagedChallengeIsAJsChallenge() {
        assertEquals(ScrapeReason.JS_CHALLENGE,
                BlockClassifier.classify(refused(403, MANAGED_EN, CF_CHALLENGE)));
    }

    @Test
    void aLocalizedChallengeClassifiesAsTheEnglishOneDoes() {
        // Scrapling's solver matched English strings and left every other locale unsolved (upstream c032626).
        var lower = MANAGED_FR.toLowerCase(Locale.ROOT);
        for (var english : new String[] {"just a moment", "enable javascript", "verifying you are human"}) {
            assertFalse(lower.contains(english), "the fixture must carry no English: " + english);
        }
        assertEquals(ScrapeReason.JS_CHALLENGE,
                BlockClassifier.classify(refused(403, MANAGED_FR, CF_CHALLENGE)));
        assertEquals(BlockClassifier.classify(refused(403, MANAGED_EN, Map.of())),
                BlockClassifier.classify(refused(403, MANAGED_FR, Map.of())),
                "without the header the inline options still decide, in any language");
    }

    @Test
    void aRefusalWithNoChallengeEvidenceStaysATrustBlock() {
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(
                refused(403, "<html><body><h1>403 Forbidden</h1></body></html>", Map.of())));
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(refused(403, CF_BLOCK_PAGE, Map.of())),
                "a challenge-platform script alone is Cloudflare's detection, not a challenge");
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(
                refused(403, "<script>var config = {cType: 'managed'};</script>", Map.of())),
                "a cType counts only inside Cloudflare's challenge options");
    }

    @Test
    void aRefusalIsClassifiedByItsStatusNotByWordsInItsUrl() {
        for (var url : new String[] {"https://x.test/blog/request-timeouts", "https://x.test/robots.txt-guide"}) {
            var refusal = new WebExtraction.HttpStatusException(403, url, new byte[0], "text/html", Map.of());
            assertEquals(ScrapeReason.TRUST_BLOCK,
                    BlockClassifier.classify(ScrapeObservation.failed(url, refusal)), url);
            var dead = new WebExtraction.HttpStatusException(404, url, new byte[0], "text/html", Map.of());
            assertEquals(ScrapeReason.NOT_FOUND,
                    BlockClassifier.classify(ScrapeObservation.failed(url, dead)), url);
        }
    }

    @Test
    void theChallengeTypeDecidesBetweenAJsChallengeAndTurnstile() {
        assertEquals(ScrapeReason.TURNSTILE, BlockClassifier.classify(refused(403,
                challenge("interactive", "Un instant\u2026", ""), CF_CHALLENGE)));
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(refused(403,
                challenge("non-interactive", "Just a moment...", ""), CF_CHALLENGE)));
        assertEquals(ScrapeReason.TURNSTILE, BlockClassifier.classify(refused(403,
                "<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script>",
                CF_CHALLENGE)), "an embedded widget with no inline options is Turnstile");
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(refused(403, "", CF_CHALLENGE)),
                "the header alone names a challenge even when the body says nothing");
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(refused(503, MANAGED_EN, Map.of())),
                "rungs 2 and 3 see no origin header, so the inline options suffice");
    }

    // ==================== Named refusals (JCLAW-1320) ====================

    /** Rung 3's render of g2.com on 2026-09-28: DataDome's device check behind Cloudflare's CDN,
     *  which still injects its detection script. {@code cid}, {@code hsh}, {@code e} and {@code cookie} redacted. */
    private static final String DATADOME_INTERSTITIAL = """
            <html lang="en"><head><title>g2.com</title></head><body style="margin:0">
            <script data-cfasync="false">var dd={'rt':'i','cid':'REDACTED','hsh':'REDACTED','b':1639491,'s':48621,
            'e':'REDACTED','qp':'','host':'geo.captcha-delivery.com','cookie':'REDACTED'}</script>
            <script data-cfasync="false" src="https://ct.captcha-delivery.com/i.js"></script>
            <iframe src="https://geo.captcha-delivery.com/interstitial/?initialCid=REDACTED&amp;hash=REDACTED&amp;cid=REDACTED&amp;referer=https%3A%2F%2Fwww.g2.com%2F&amp;s=48621&amp;e=REDACTED&amp;b=1639491&amp;dm=cd"
             title="DataDome Device Check" width="100%" height="100%"></iframe>
            <script>(function(){var s=document.createElement('script');
            s.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js';document.head.appendChild(s);})();</script>
            </body></html>""";

    /** Rung 1's answer from klook.com the same day: the captcha, redacted as above. */
    private static final String DATADOME_CAPTCHA = """
            <html lang="en"><head><title>klook.com</title></head><body style="margin:0">
            <p id="cmsg">Please enable JS and disable any ad blocker</p>
            <script data-cfasync="false">var dd={'rt':'c','cid':'REDACTED','hsh':'REDACTED','t':'bv','rr':'','qp':'',
            's':37675,'e':'REDACTED','host':'geo.captcha-delivery.com','cookie':'REDACTED'}</script>
            <script data-cfasync="false" src="https://ct.captcha-delivery.com/c.js"></script></body></html>""";

    /** Rung 3's render of hostgator.com.br: Cloudflare's Error 1009 template, its code in the feedback script. */
    private static final String GEO_BLOCK_PAGE = """
            <!DOCTYPE html><html class="no-js" lang="en-US"><head>
            <title>Access denied | www.hostgator.com.br used Cloudflare to restrict access | www.hostgator.com.br | Cloudflare</title>
            <link rel="stylesheet" id="cf_styles-css" href="/cdn-cgi/styles/main.css"><script>
            (function(){var e=function(a){var b=new XMLHttpRequest;a={event:"feedback clicked",
            properties:{errorCode: 1009 },helpful:a,version: 1 };b.open("POST","https://sparrow.cloudflare.com/api/v1/event");
            b.send(JSON.stringify(a))};})();
            </script></head><body><div id="cf-wrapper"><div id="cf-error-details">
            <h1><span data-translate="error">Error</span><span>1009</span></h1><h2>Access denied</h2>
            </div></div></body></html>""";

    /** Rung 1's answer from hostgator.com.br: Cloudflare's 1009 served as markdown, ray id redacted. */
    private static final String GEO_BLOCK_MARKDOWN = """
            ---
            error_code: 1009
            error_name: country_banned
            error_category: access_denied
            status: 403
            ray_id: REDACTED
            zone: hostgator.com.br
            cloudflare_error: true
            retryable: false
            owner_action_required: true
            ---

            # Error 1009: Access denied

            ## What Happened

            The site owner has blocked the country or region associated with your IP address.
            """;

    private static final String DATADOME_SCRIPT =
            "<script data-cfasync=\"false\" src=\"https://ct.captcha-delivery.com/i.js\"></script>";

    @Test
    void aDataDomeRefusalIsNamedFromItsBody() {
        assertEquals(ScrapeReason.DATADOME, BlockClassifier.classify(refused(403, DATADOME_INTERSTITIAL, Map.of())),
                "the interstitial, whose Cloudflare detection script is not a challenge");
        assertEquals(ScrapeReason.DATADOME, BlockClassifier.classify(refused(403, DATADOME_CAPTCHA, Map.of())));
    }

    @Test
    void aCloudflare1009IsAGeoBlockInEveryForm() {
        var plainText = new WebExtraction.HttpStatusException(403, REFUSED_URL,
                "error code: 1009\n".getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8", Map.of());
        assertEquals(ScrapeReason.GEO_BLOCK, BlockClassifier.classify(ScrapeObservation.failed(REFUSED_URL, plainText)));
        assertEquals(ScrapeReason.GEO_BLOCK, BlockClassifier.classify(refused(403, GEO_BLOCK_PAGE, Map.of())));
        var markdown = new WebExtraction.HttpStatusException(403, REFUSED_URL,
                GEO_BLOCK_MARKDOWN.getBytes(StandardCharsets.UTF_8), "text/markdown; charset=utf-8", Map.of());
        assertEquals(ScrapeReason.GEO_BLOCK, BlockClassifier.classify(ScrapeObservation.failed(REFUSED_URL, markdown)),
                "what Cloudflare answers rung 1's markdown-first Accept with");
    }

    @Test
    void anyOtherCloudflareRefusalStaysATrustBlock() {
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(refused(403, CF_BLOCK_PAGE, Map.of())));
        for (var code : new String[] {"1004", "1010", "1020", "10090"}) {
            assertEquals(ScrapeReason.TRUST_BLOCK,
                    BlockClassifier.classify(refused(403, "error code: " + code, Map.of())), code);
        }
    }

    @Test
    void aCloudflareAsnOrIpBanIsAnIpBlockInEveryForm() {
        // No 1005-1008 capture yet: Cloudflare serves every 1xxx error from one template, so these
        // are the 1009 captures above with only the code changed.
        for (var code : new String[] {"1005", "1006", "1007", "1008"}) {
            var plainText = new WebExtraction.HttpStatusException(403, REFUSED_URL,
                    ("error code: " + code + "\n").getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8",
                    Map.of());
            assertEquals(ScrapeReason.IP_BLOCK,
                    BlockClassifier.classify(ScrapeObservation.failed(REFUSED_URL, plainText)), code);
            assertEquals(ScrapeReason.IP_BLOCK,
                    BlockClassifier.classify(refused(403, GEO_BLOCK_PAGE.replace("1009", code), Map.of())), code);
            assertEquals(ScrapeReason.IP_BLOCK,
                    BlockClassifier.classify(refused(403, GEO_BLOCK_MARKDOWN.replace("1009", code), Map.of())), code);
        }
    }

    @Test
    void aCloudflareChallengeIsReadBeforeADataDomeMarker() {
        var both = MANAGED_EN.replace("</body>", DATADOME_SCRIPT + "</body>");
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(refused(403, both, Map.of())));
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(refused(403, both, CF_CHALLENGE)));
    }

    @Test
    void aPageServedWith200IsNeverANamedRefusal() {
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(obs(DATADOME_CAPTCHA, "")),
                "a thin DataDome page stays a challenge");
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(obs(
                "<html><body><p>Cloudflare answers error code: 1009 when a country is banned.</p></body></html>",
                ARTICLE_TEXT)));
    }

    /** A stealth-sidecar answer: always 200, with the origin's outcome in its headers. */
    private static Response render(String body, String... headers) {
        var b = new Response.Builder()
                .request(new Request.Builder().url("http://127.0.0.1:9532/render").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create(body, MediaType.parse("text/html; charset=utf-8")));
        for (int i = 0; i < headers.length; i += 2) b.addHeader(headers[i], headers[i + 1]);
        return b.build();
    }

    private static ScrapeObservation rung3(Response response) {
        var url = "https://8.8.8.8/";
        try {
            var render = RenderedFetcher.rendered(response, url);
            return ScrapeObservation.of(render.fetched(), WebExtraction.toText(render.fetched()))
                    .withChallenge(render.challenge());
        } catch (Exception e) {
            return ScrapeObservation.failed(url, e);
        }
    }

    @Test
    void aRenderThatSettledOn200IsScoredFromItsSettledBody() {
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(rung3(render(ARTICLE_HTML,
                "X-Upstream-Status", "403", "X-Settled-Status", "200"))));
        var unsettled = rung3(render(ARTICLE_HTML, "X-Upstream-Status", "403"));
        assertEquals(403, unsettled.status(), "without the settled header the first navigation decides");
        assertEquals(ScrapeReason.TRUST_BLOCK, BlockClassifier.classify(unsettled));
        assertEquals(403, rung3(render(ARTICLE_HTML,
                "X-Upstream-Status", "403", "X-Settled-Status", "0")).status(),
                "a settled 0 reports no navigation, so it cannot overrule the first one");
        assertEquals(ScrapeReason.JS_CHALLENGE, BlockClassifier.classify(rung3(render(MANAGED_FR,
                "X-Upstream-Status", "403", "X-Settled-Status", "403"))),
                "a challenge still standing when the window closed is classified from its body");
        assertEquals(ScrapeReason.TURNSTILE, BlockClassifier.classify(rung3(render(
                "<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script>",
                "X-Settled-Status", "403", "X-Upstream-cf-mitigated", "challenge"))),
                "a sidecar forwards an origin header the classifier reads as X-Upstream-<name>");
    }

    @Test
    void aRenderCarriesTheChallengeItMetWhetherOrNotItCleared() {
        var cleared = rung3(render(ARTICLE_HTML, "X-Upstream-Status", "403", "X-Settled-Status", "200",
                "X-Challenge", "managed; cleared"));
        assertEquals(ScrapeReason.OK, BlockClassifier.classify(cleared));
        assertEquals("managed; cleared", cleared.challenge());
        var unsolved = rung3(render(MANAGED_FR, "X-Upstream-Status", "403", "X-Settled-Status", "403",
                "X-Challenge", "interactive; unsolved; clicks=3"));
        assertEquals(403, unsolved.status());
        assertEquals("interactive; unsolved; clicks=3", unsolved.challenge(),
                "a refused render keeps the report beside its body");
        assertNull(rung3(render(ARTICLE_HTML, "X-Upstream-Status", "200")).challenge());
    }

    @Test
    void theHarnessCountsSolvedAndUnsolvedChallengesPerUrl() throws Exception {
        var json = """
                {"allocation":"equal","strata":["challenge"],"entries":[
                  {"url":"https://solved.test","stratum":"challenge","vendor":"cloudflare",
                   "outcome":"challenge","rendering":"ssr","rank":1,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}},
                  {"url":"https://unsolved.test","stratum":"challenge","vendor":"cloudflare",
                   "outcome":"challenge","rendering":"ssr","rank":2,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}},
                  {"url":"https://open.test","stratum":"challenge","vendor":"cloudflare",
                   "outcome":"challenge","rendering":"ssr","rank":3,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}}]}
                """;
        var f = Files.createTempFile("scrape-challenge", ".json");
        Files.writeString(f, json);
        var corpus = ScrapeCorpus.load(f);
        Files.deleteIfExists(f);

        var rep = ScrapeHarness.run("stub", url -> {
            if (url.contains("unsolved.test")) {
                return refused(403, MANAGED_EN, CF_CHALLENGE).withChallenge("managed; unsolved; clicks=3");
            }
            var page = obs("<html><body>x</body></html>", ARTICLE_TEXT);
            return url.contains("solved.test") ? page.withChallenge("managed; cleared; clicks=1") : page;
        }, corpus, 2);

        assertEquals(Map.of("managed; cleared; clicks=1", 1, "managed; unsolved; clicks=3", 1), rep.byChallenge());
        assertEquals("managed; cleared; clicks=1", rep.results().get(0).challenge());
        assertEquals("managed; unsolved; clicks=3", rep.results().get(1).challenge());
        assertNull(rep.results().get(2).challenge(), "a page that met no challenge records none");
    }

    @Test
    void theUnresolvedHistogramTellsAChallengeFromATrustBlockAndRoutesBothAlike() throws Exception {
        var json = """
                {"allocation":"equal","strata":["challenge"],"entries":[
                  {"url":"https://gated.test","stratum":"challenge","vendor":"cloudflare",
                   "outcome":"challenge","rendering":"ssr","rank":1,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}},
                  {"url":"https://refused.test","stratum":"challenge","vendor":"cloudflare",
                   "outcome":"challenge","rendering":"ssr","rank":2,
                   "ground_truth":{"min_chars":300,"reject_markers":[]}}]}
                """;
        var f = Files.createTempFile("scrape-refused", ".json");
        Files.writeString(f, json);
        var corpus = ScrapeCorpus.load(f);
        Files.deleteIfExists(f);

        var rep = ScrapeHarness.run("stub", url -> url.contains("gated.test")
                ? refused(403, MANAGED_EN, CF_CHALLENGE)
                : refused(403, CF_BLOCK_PAGE, Map.of()), corpus, 2);

        assertEquals(1, rep.byReason().get(ScrapeReason.JS_CHALLENGE.name()));
        assertEquals(1, rep.byReason().get(ScrapeReason.TRUST_BLOCK.name()));
        // Whether a detected challenge should skip rung 2 is its own measured decision.
        assertEquals(Map.of(ScrapeRung.IMPERSONATE.name(), 2), rep.byNextRung());
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
