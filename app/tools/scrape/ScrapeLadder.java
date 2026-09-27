package tools.scrape;

import org.jspecify.annotations.Nullable;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import services.scrape.ScrapeSidecarException;
import utils.WebExtraction;

import java.util.Map;

/**
 * Climbs the escalation ladder for one URL (JCLAW-1099).
 *
 * <p>Every rung existed and was measured before this class did, and none of them was
 * reachable: the tools classified a failure, logged the rung that would fix it, and gave
 * up. Wiring them together took the 150-site corpus from 61/150 to 101/150 —
 * 40.7% to 67.3% equal-allocation, 60.9% to 89.1% prevalence-weighted. The figures are
 * JCLAW-1091's.
 *
 * <p><b>Escalation, never substitution.</b> A higher rung is not a superset of a lower
 * one — measured twice. Rung 2 reads pages rung 1 cannot and the reverse; rung 3 reads
 * far more than rung 2 but still loses two corpus entries to it, because presenting as a
 * real browser earns the JavaScript shell where a plain client was served a
 * server-rendered page. So the climb keeps the best attempt seen, not the last one made.
 */
public final class ScrapeLadder {

    /**
     * Rung 2 sends no {@code User-Agent}: curl_cffi supplies the header set matching the
     * profile it forges, and overriding it would pair a Chrome ClientHello with a
     * non-Chrome agent string — a mismatch WAFs test for directly (JCLAW-1087). The one
     * exception is a clearance handed down from a crawl's browser, which carries that
     * browser's User-Agent and is sent only when the profile's Chrome major matches it
     * ({@link ScrapeSessions#rungTwo}).
     */
    public static Map<String, String> impersonatedHeaders(String language) {
        // Same "*;q=0.5" tail rung 1 sends: a bare preference invites a 406 from a site
        // that has no page in that language, and a language must never cost us the page.
        return Map.of("Accept", "text/html,application/xhtml+xml",
                "Accept-Language", language + ", *;q=0.5");
    }

    /** The language a caller that has no preference of its own escalates in. */
    public static final String DEFAULT_LANGUAGE = "en";

    /** One rung's product, and which rung produced it. {@code status} is the HTTP status of a
     *  refused attempt, 0 for any other, and is what a challenge read off a refusal routes on.
     *  {@code challenge} is the stealth sidecar's report of the Cloudflare challenge a render in
     *  the climb met, whichever rung's attempt was kept. */
    public record Attempt(ScrapeRung servedBy, WebExtraction.@Nullable FetchResult fetched,
                          @Nullable String text, ScrapeReason reason, @Nullable String detail,
                          int status, @Nullable String challenge) {

        public Attempt(ScrapeRung servedBy, WebExtraction.@Nullable FetchResult fetched,
                       @Nullable String text, ScrapeReason reason, @Nullable String detail,
                       int status) {
            this(servedBy, fetched, text, reason, detail, status, null);
        }

        public boolean usable() {
            return reason == ScrapeReason.OK;
        }

        int textLength() {
            return text == null ? 0 : text.length();
        }

        /** Valid only when {@link #usable()} — a non-OK attempt extracted no text. */
        public String resolvedText() {
            if (text == null) throw new IllegalStateException("attempt not usable: " + reason);
            return text;
        }

        Attempt withChallenge(@Nullable String met) {
            return met == null || met.equals(challenge) ? this
                    : new Attempt(servedBy, fetched, text, reason, detail, status, met);
        }
    }

    private ScrapeLadder() {}

    /** Whether any rung above {@link ScrapeRung#PLAIN} could be attempted on this install. */
    public static boolean available() {
        return available(null);
    }

    /** As {@link #available()}, for a crawl reaching the sidecars through {@code sessions}. */
    public static boolean available(@Nullable ScrapeSessions sessions) {
        return isInstalled(ScrapeRung.IMPERSONATE, sessions) || isInstalled(ScrapeRung.BROWSER, sessions);
    }

    /**
     * Whether {@link #climb} would issue a request for a rung-1 failure with this reason,
     * refused with {@code status} (0 when it was not refused).
     *
     * <p>A caller holding a budget must ask before claiming a slot: {@code climb} returns
     * without a request when the classifier names an uninstalled rung, so claiming first
     * spends the budget on a page nothing was attempted for and reports an escalation
     * that never happened.
     */
    public static boolean wouldAttempt(ScrapeReason reason, int status) {
        return wouldAttempt(reason, status, null);
    }

    /** As {@link #wouldAttempt(ScrapeReason, int)}, for a crawl reaching the sidecars through {@code sessions}. */
    public static boolean wouldAttempt(ScrapeReason reason, int status, @Nullable ScrapeSessions sessions) {
        return isInstalled(BlockClassifier.nextRung(reason, status, ScrapeRung.PLAIN), sessions);
    }

    /**
     * Attempt higher rungs until one succeeds or the ladder is exhausted, and return the
     * best attempt made. {@code plain} is the rung-1 result the caller already has.
     *
     * <p>Returns {@code plain} untouched when it is usable, when no higher rung is
     * installed, or when the classifier says nothing further will help — so a caller can
     * invoke this unconditionally and an install with no sidecars simply keeps rung 1.
     */
    public static Attempt climb(String url, Attempt plain) {
        return climb(url, plain, DEFAULT_LANGUAGE);
    }

    /** As {@link #climb(String, Attempt)}, carrying the caller's language preference so
     *  an escalated page comes back in the language the unescalated one would have. */
    public static Attempt climb(String url, Attempt plain, String language) {
        return climb(url, plain, language, null);
    }

    /**
     * As {@link #climb(String, Attempt, String)} for one page of a crawl, which renders in
     * {@code sessions} and asks it how rung 2 approaches the page's host (JCLAW-1307). Null is
     * a lone fetch, which renders with a browser of its own.
     */
    public static Attempt climb(String url, Attempt plain, String language, @Nullable ScrapeSessions sessions) {
        if (plain.usable()) return plain;

        var best = plain;
        var last = plain;
        var attempted = plain.servedBy();
        String challenge = null;

        while (true) {
            var next = BlockClassifier.nextRung(last.reason(), last.status(), attempted);
            if (!isInstalled(next, sessions)) return best.withChallenge(challenge);

            var rungTwo = next == ScrapeRung.IMPERSONATE && sessions != null
                    ? sessions.rungTwo(url) : ScrapeSessions.RungTwo.AS_USUAL;
            attempted = next;
            if (rungTwo.skip()) continue;
            last = attempt(next, url, language, sessions, rungTwo);
            if (last.usable()) return last;
            if (last.challenge() != null) challenge = last.challenge();
            best = better(best, last);
        }
    }

    /** Run one URL through {@code rung}, classifying the result the way the harness does. */
    private static Attempt attempt(ScrapeRung rung, String url, String language,
                                   @Nullable ScrapeSessions sessions, ScrapeSessions.RungTwo rungTwo) {
        try {
            WebExtraction.FetchResult fetched;
            String challenge = null;
            if (rung == ScrapeRung.BROWSER) {
                var render = sessions == null ? RenderedFetcher.render(url, language) : sessions.render(url, language);
                fetched = render.fetched();
                challenge = render.challenge();
            } else {
                var headers = impersonatedHeaders(language);
                fetched = sessions == null ? ImpersonatedFetcher.fetch(url, headers)
                        : sessions.impersonate(url, headers, rungTwo);
            }
            var text = WebExtraction.toText(fetched);
            var obs = ScrapeObservation.of(fetched, text);
            return new Attempt(rung, fetched, text, BlockClassifier.classify(obs), null, 0, challenge);
        } catch (ScrapeSidecarException e) {
            // Ours, not the origin's — and the distinction this type exists to carry was
            // being thrown away by classifying its message: "sidecar returned HTTP 503"
            // matched the status pattern and reported a local outage as TRUST_BLOCK.
            var detail = e.getMessage();
            return new Attempt(rung, null, null, ScrapeReason.ERROR,
                    detail == null || detail.isBlank() ? e.getClass().getSimpleName() : detail, 0);
        } catch (Exception e) {
            var obs = ScrapeObservation.failed(url, e);
            return new Attempt(rung, null, null, BlockClassifier.classify(obs), obs.error(), obs.status(),
                    obs.challenge());
        }
    }

    /**
     * The attempt worth keeping. A usable one always wins; otherwise the one that
     * extracted more text, with ties going to the lower rung so a browser returning an
     * empty JavaScript shell never displaces a plain fetch's partial content.
     */
    private static Attempt better(Attempt a, Attempt b) {
        if (a.usable()) return a;
        if (b.usable()) return b;
        return b.textLength() > a.textLength() ? b : a;
    }

    /** {@link ScrapeRung#NONE} and any rung whose sidecar is absent are both "stop here". */
    private static boolean isInstalled(ScrapeRung rung, @Nullable ScrapeSessions sessions) {
        return switch (rung) {
            case IMPERSONATE -> sessions == null ? ImpersonatedFetcher.available() : sessions.impersonateAvailable();
            case BROWSER -> sessions == null ? RenderedFetcher.available() : sessions.renderAvailable();
            case PLAIN, PROVIDER, NONE -> false;
        };
    }
}
