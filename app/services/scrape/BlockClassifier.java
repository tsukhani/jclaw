package services.scrape;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns one fetch outcome into a {@link ScrapeReason}, and a reason into the rung that
 * could address it (JCLAW-1086).
 *
 * <p>One implementation for the runtime tool and the offline harness. Two would drift,
 * and the benchmark would quietly stop describing what agents experience.
 *
 * <p>Classifies from the <em>raw</em> markup rather than the extracted text. Readability
 * strips scripts, so a Cloudflare gate and a client-rendered app both extract to nothing
 * and are indistinguishable after extraction — the markers that separate them are in the
 * markup the extractor discarded.
 */
public final class BlockClassifier {

    private BlockClassifier() {}

    /** Extracted characters below which a response is not a page. Used when the caller
     *  has no better per-URL figure; the harness passes the corpus's derived floor. */
    public static final int DEFAULT_MIN_CHARS = 200;

    /** Case-insensitive: input is lowercased before matching, so an anchored uppercase
     *  "HTTP" would never fire. */
    private static final Pattern HTTP_STATUS =
            Pattern.compile("http (\\d{3})", Pattern.CASE_INSENSITIVE);

    private static final String[] TURNSTILE_MARKERS = {
            "challenges.cloudflare.com/turnstile", "cf-turnstile"
    };
    private static final String[] CHALLENGE_MARKERS = {
            "/cdn-cgi/challenge-platform/", "cf_chl_opt", "_incapsula_resource",
            "verifying you are human", "enable javascript and cookies to continue",
            "needs to review the security of your connection", "captcha-delivery.com"
    };

    /** Ordinary English an article can contain innocently — oxylabs.io scored a policy
     *  block off 7,947 characters of marketing copy that says "scraping is prohibited".
     *  Only consulted when no page came back. */
    private static final String[] POLICY_MARKERS = {
            "ai crawler", "ai training", "automated access is not permitted",
            "bots are not allowed", "scraping is prohibited"
    };

    /**
     * Prerendering services serve rendered HTML to user agents they recognize as
     * crawlers. Measured on abundent.academy: 68 characters of text to a browser UA,
     * 5,169 to Googlebot — a 76x difference from the header alone.
     *
     * <p>Recorded rather than acted on. The count of thin origins carrying these
     * markers is the evidence JCLAW-1091 reports on whether the descoped identity lane
     * (Web Bot Auth) has measurable value — a mechanical argument rather than the
     * speculative policy one that got it descoped.
     */
    private static final String[] PRERENDER_MARKERS = {
            "prerenderready", "prerender.io", "name=\"fragment\"", "name='fragment'",
            "x-prerender"
    };

    public static ScrapeReason classify(ScrapeObservation obs) {
        return classify(obs, DEFAULT_MIN_CHARS);
    }

    public static ScrapeReason classify(ScrapeObservation obs, int minChars) {
        if (obs == null) return ScrapeReason.ERROR;
        if (obs.failed()) return classifyError(obs.error().toLowerCase(Locale.ROOT));

        var raw = obs.rawBody() == null ? "" : obs.rawBody();

        // A gate is an HTML phenomenon. JSON, plain text and extracted PDF prose are
        // content at any length — {"a":1} is seven characters and a complete response,
        // and applying the thin-content floor to it discarded valid results.
        if (!isHtmlLike(obs, raw) && obs.textLength() > 0) {
            return ScrapeReason.OK;
        }

        // Gate markers are decisive at any length: a page carrying a Turnstile widget
        // AND no readable content is a gate, while one that merely embeds the widget
        // has content and falls through to OK below.
        boolean thin = obs.textLength() < minChars;
        if (thin && containsAny(raw, TURNSTILE_MARKERS)) return ScrapeReason.TURNSTILE;
        if (thin && containsAny(raw, CHALLENGE_MARKERS)) return ScrapeReason.JS_CHALLENGE;
        if (thin && containsAny(raw, POLICY_MARKERS)) return ScrapeReason.POLICY_BLOCK;

        if (!thin) return ScrapeReason.OK;

        // Content-free and no gate marker: the origin served us, there is simply nothing
        // server-rendered to read. A rendering gap, not an anti-bot one.
        return ScrapeReason.THIN_CONTENT;
    }

    /** Whether this origin serves rendered HTML to declared crawlers. See
     *  {@link #PRERENDER_MARKERS}. */
    public static boolean hasPrerenderMarkers(ScrapeObservation obs) {
        return obs != null && obs.rawBody() != null
                && containsAny(obs.rawBody(), PRERENDER_MARKERS);
    }

    /**
     * The rung to try after {@code reason} was observed <em>on {@code attempted}</em>.
     *
     * <p>The single-argument form maps a reason to the rung that addresses it, which is
     * only correct for a rung-1 observation. On a rung-2 report it answered
     * {@code IMPERSONATE} for every {@code TRUST_BLOCK} — recommending the rung that had
     * just failed, and making 39 of one run's failures read as "needs impersonation"
     * when impersonation is exactly what produced them. The suggestion never points at
     * or below the rung already attempted; when the ladder is exhausted it says
     * {@link ScrapeRung#NONE} rather than inventing a rung.
     */
    public static ScrapeRung nextRung(ScrapeReason reason, ScrapeRung attempted) {
        var suggested = nextRung(reason);
        if (suggested == ScrapeRung.NONE) return ScrapeRung.NONE;
        if (suggested.ordinal() > attempted.ordinal()) return suggested;
        int next = attempted.ordinal() + 1;
        return next < ScrapeRung.NONE.ordinal() ? ScrapeRung.values()[next] : ScrapeRung.NONE;
    }

    /**
     * The cheapest rung that could plausibly address this failure.
     *
     * <p>{@link ScrapeReason#THIN_CONTENT} skips {@link ScrapeRung#IMPERSONATE}
     * deliberately: a different TLS fingerprint cannot execute JavaScript, so
     * escalating a client-rendered page to the impersonation rung spends a request to
     * arrive at the same empty page.
     *
     * <p>{@link ScrapeReason#TIMEOUT} stays at {@link ScrapeRung#NONE}: an origin too
     * slow to answer a plain fetch will not answer a browser faster, and a render is the
     * most expensive way to wait.
     *
     * <p>{@link ScrapeReason#POLICY_BLOCK} maps to {@link ScrapeRung#NONE} rather than
     * to a stealth rung. An origin that states it blocks agents is refusing on identity,
     * and the answer to that is identification, not evasion — which is the lane the epic
     * descoped.
     */
    public static ScrapeRung nextRung(ScrapeReason reason) {
        return switch (reason) {
            case TLS_BLOCKED, TRUST_BLOCK -> ScrapeRung.IMPERSONATE;
            case JS_CHALLENGE, THIN_CONTENT -> ScrapeRung.BROWSER;
            case TURNSTILE -> ScrapeRung.PROVIDER;
            // ERROR reaches here only after TransientRetryInterceptor has already
            // retried the retryable statuses, so what is left is structural — a
            // persistent 400, a redirect loop — and a browser handles those natively
            // where a different TLS fingerprint cannot. Skips IMPERSONATE for the same
            // reason THIN_CONTENT does.
            case ERROR -> ScrapeRung.BROWSER;
            case OK, POLICY_BLOCK, OTHER_WAF, ROBOTS_DISALLOWED, TIMEOUT, NOT_FOUND ->
                    ScrapeRung.NONE;
        };
    }

    private static ScrapeReason classifyError(String lower) {
        if (lower.contains("robots.txt")) return ScrapeReason.ROBOTS_DISALLOWED;
        // Both spellings: WebFetchTool wrote "timed out", while the raw
        // SocketTimeoutException the harness now sees says "timeout".
        if (lower.contains("timed out") || lower.contains("timeout")) return ScrapeReason.TIMEOUT;
        var m = HTTP_STATUS.matcher(lower);
        if (m.find()) {
            return switch (Integer.parseInt(m.group(1))) {
                case 401, 402, 451 -> ScrapeReason.POLICY_BLOCK;
                case 403, 406, 429, 503 -> ScrapeReason.TRUST_BLOCK;
                // A dead link is not a transport problem: rendering it costs seconds and
                // an escalation slot to arrive at the same 404. Crawls hit these
                // constantly, so leaving them in ERROR spent the whole budget on them.
                case 404, 410 -> ScrapeReason.NOT_FOUND;
                default -> ScrapeReason.ERROR;
            };
        }
        return ScrapeReason.ERROR;
    }

    /** Mirrors {@code WebExtraction}'s routing: an explicit html content type, or — when
     *  the type is absent — a body that opens with a tag. */
    private static boolean isHtmlLike(ScrapeObservation obs, String raw) {
        var ct = obs.contentType() == null ? "" : obs.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("html")) return true;
        if (!ct.isBlank()) return false;
        return raw.stripLeading().startsWith("<");
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (var n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
