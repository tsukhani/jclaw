package services.scrape;

import org.jspecify.annotations.Nullable;

import java.util.List;
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

    /** The response headers {@link #classify} reads off a refused response. */
    public static final List<String> RESPONSE_HEADERS = List.of("cf-mitigated");

    /** Cloudflare's inline challenge options name the challenge type in every locale;
     *  matched after {@code _cf_chl_opt} so an unrelated {@code cType} cannot count. */
    private static final String CF_CHALLENGE_OPTIONS = "_cf_chl_opt";
    private static final Pattern CF_CHALLENGE_TYPE =
            Pattern.compile("ctype\\s*:\\s*['\"]([a-z-]+)['\"]");
    private static final String TURNSTILE_SCRIPT = "challenges.cloudflare.com/turnstile/";

    /** The host DataDome serves its device check and captcha from, as a script or an iframe. */
    private static final String DATADOME_HOST = "captcha-delivery.com";
    /** Cloudflare's country ban: {@code error code: 1009} as plain text, {@code errorCode: 1009}
     *  in the HTML template's feedback script, {@code error_code: 1009} in the front matter of
     *  the markdown rung 1's Accept asks for. The body is lowercased before matching. */
    private static final Pattern GEO_BLOCK_CODE = Pattern.compile("error[ _]?code:\\s*1009(?!\\d)");
    /** Cloudflare's ASN ban (1005) and IP bans (1006–1008), in the same three forms as 1009. */
    private static final Pattern IP_BLOCK_CODE = Pattern.compile("error[ _]?code:\\s*100[5-8](?!\\d)");

    private static final String[] TURNSTILE_MARKERS = {
            "challenges.cloudflare.com/turnstile", "cf-turnstile"
    };
    private static final String[] CHALLENGE_MARKERS = {
            "/cdn-cgi/challenge-platform/", "cf_chl_opt", "_incapsula_resource",
            "verifying you are human", "enable javascript and cookies to continue",
            "needs to review the security of your connection", DATADOME_HOST
    };

    /** Ordinary English an article can contain innocently — oxylabs.io scored a policy
     *  block off 7,947 characters of marketing copy that says "scraping is prohibited".
     *  Only consulted when no page came back. */
    private static final String[] POLICY_MARKERS = {
            "ai crawler", "ai training", "automated access is not permitted",
            "bots are not allowed", "scraping is prohibited"
    };

    /** Titles of the page a site serves, in place of itself, to a User-Agent it does not recognise. */
    private static final String[] UNSUPPORTED_CLIENT_TITLES = {
            "unsupported browser", "unsupported client", "browser not supported", "browser is not supported",
            "update your browser", "upgrade your browser", "outdated browser"
    };
    /** Above this much text, a page titled that way is about browsers rather than a refusal of this one. */
    private static final int UNSUPPORTED_CLIENT_MAX_CHARS = 2_000;
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>");

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
        if (obs.failed()) return classifyFailure(obs);

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
        // canva.com answers an unrecognised User-Agent with a 200 "update your browser" page, which scored
        // OK on rung 1, so the ladder never reached the browser that reads the site.
        if (unsupportedClient(raw, obs.textLength())) return ScrapeReason.TRUST_BLOCK;

        if (!thin) return ScrapeReason.OK;

        // Content-free and no gate marker: the origin served us, there is simply nothing
        // server-rendered to read. A rendering gap, not an anti-bot one.
        return ScrapeReason.THIN_CONTENT;
    }

    private static ScrapeReason classifyFailure(ScrapeObservation obs) {
        // Not from the message: it names the URL, and "timeout" or "robots.txt" in a path would decide.
        if (obs.status() >= 400) {
            var challenge = cloudflareChallenge(obs);
            if (challenge != null) return challenge;
            // From the body, not DataDome's x-datadome header: rungs 2 and 3 forward none.
            if (obs.rawBody().contains(DATADOME_HOST)) return ScrapeReason.DATADOME;
            if (GEO_BLOCK_CODE.matcher(obs.rawBody()).find()) return ScrapeReason.GEO_BLOCK;
            if (IP_BLOCK_CODE.matcher(obs.rawBody()).find()) return ScrapeReason.IP_BLOCK;
            return statusReason(obs.status());
        }
        return classifyError(obs.resolvedError().toLowerCase(Locale.ROOT));
    }

    private static boolean unsupportedClient(String raw, int textLength) {
        if (textLength > UNSUPPORTED_CLIENT_MAX_CHARS) return false;
        var title = TITLE.matcher(raw);
        return title.find() && containsAny(title.group(1), UNSUPPORTED_CLIENT_TITLES);
    }

    /** Whether this origin serves rendered HTML to declared crawlers. See
     *  {@link #PRERENDER_MARKERS}. A refused response served us nothing to compare. */
    public static boolean hasPrerenderMarkers(ScrapeObservation obs) {
        return obs != null && !obs.failed() && obs.rawBody() != null
                && containsAny(obs.rawBody(), PRERENDER_MARKERS);
    }

    /**
     * A Cloudflare challenge on a refused response, or null when there is no evidence of one.
     *
     * <p>Evidence is {@code cf-mitigated: challenge} or a {@code cType} in the inline challenge
     * options. {@code /cdn-cgi/challenge-platform/} alone is not: Cloudflare injects its
     * detection script there into block pages and ordinary pages alike. Never English page
     * text, which a localized challenge does not carry.
     */
    private static @Nullable ScrapeReason cloudflareChallenge(ScrapeObservation obs) {
        var raw = obs.rawBody();
        int options = raw.indexOf(CF_CHALLENGE_OPTIONS);
        if (options >= 0) {
            var type = CF_CHALLENGE_TYPE.matcher(raw);
            // Only "interactive" always wants a click; managed and non-interactive can clear in a browser.
            if (type.find(options)) {
                return "interactive".equals(type.group(1)) ? ScrapeReason.TURNSTILE : ScrapeReason.JS_CHALLENGE;
            }
        }
        var mitigated = obs.header("cf-mitigated");
        if (mitigated == null || !"challenge".equalsIgnoreCase(mitigated.strip())) return null;
        return raw.contains(TURNSTILE_SCRIPT) ? ScrapeReason.TURNSTILE : ScrapeReason.JS_CHALLENGE;
    }

    /**
     * As {@link #nextRung(ScrapeReason, ScrapeRung)} for an attempt that ended on
     * {@code status} (0 when it was not refused). A challenge read off a refused response
     * routes as that status alone did: whether a detected challenge should skip rung 2 is a
     * separate, measured decision (JCLAW-1304). A DataDome refusal routes the same way, so a
     * 451 naming DataDome still stops as a policy block.
     */
    public static ScrapeRung nextRung(ScrapeReason reason, int status, ScrapeRung attempted) {
        boolean challenge = reason == ScrapeReason.JS_CHALLENGE || reason == ScrapeReason.TURNSTILE
                || reason == ScrapeReason.DATADOME;
        return nextRung(challenge && status >= 400 ? statusReason(status) : reason, attempted);
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
     * <p>{@link ScrapeReason#TURNSTILE} goes to {@link ScrapeRung#BROWSER} with
     * {@link ScrapeReason#JS_CHALLENGE}: the stealth sidecar waits a challenge out, and clicks a
     * gate page's checkbox when {@code web_scrape.stealth.solve-turnstile} is on (JCLAW-1306).
     *
     * <p>{@link ScrapeReason#TIMEOUT} stays at {@link ScrapeRung#NONE}: an origin too
     * slow to answer a plain fetch will not answer a browser faster, and a render is the
     * most expensive way to wait.
     *
     * <p>{@link ScrapeReason#POLICY_BLOCK} maps to {@link ScrapeRung#NONE} rather than
     * to a stealth rung. An origin that states it blocks agents is refusing on identity,
     * and the answer to that is identification, not evasion — which is the lane the epic
     * descoped.
     *
     * <p>{@link ScrapeReason#GEO_BLOCK} and {@link ScrapeReason#IP_BLOCK} map to {@link ScrapeRung#NONE}:
     * every rung leaves from the same egress, so each arrives from the country, network or address the
     * origin bans.
     */
    public static ScrapeRung nextRung(ScrapeReason reason) {
        return switch (reason) {
            case TLS_BLOCKED, TRUST_BLOCK, DATADOME -> ScrapeRung.IMPERSONATE;
            case JS_CHALLENGE, TURNSTILE, THIN_CONTENT -> ScrapeRung.BROWSER;
            // ERROR reaches here only after TransientRetryInterceptor has already
            // retried the retryable statuses, so what is left is structural — a
            // persistent 400, a redirect loop — and a browser handles those natively
            // where a different TLS fingerprint cannot. Skips IMPERSONATE for the same
            // reason THIN_CONTENT does.
            case ERROR -> ScrapeRung.BROWSER;
            case OK, POLICY_BLOCK, GEO_BLOCK, IP_BLOCK, OTHER_WAF, ROBOTS_DISALLOWED, TIMEOUT, NOT_FOUND ->
                    ScrapeRung.NONE;
        };
    }

    private static ScrapeReason classifyError(String lower) {
        if (lower.contains("robots.txt")) return ScrapeReason.ROBOTS_DISALLOWED;
        // Both spellings: WebFetchTool wrote "timed out", while the raw
        // SocketTimeoutException the harness now sees says "timeout".
        if (lower.contains("timed out") || lower.contains("timeout")) return ScrapeReason.TIMEOUT;
        var m = HTTP_STATUS.matcher(lower);
        return m.find() ? statusReason(Integer.parseInt(m.group(1))) : ScrapeReason.ERROR;
    }

    private static ScrapeReason statusReason(int status) {
        return switch (status) {
            case 401, 402, 451 -> ScrapeReason.POLICY_BLOCK;
            case 403, 406, 429, 503 -> ScrapeReason.TRUST_BLOCK;
            // A dead link is not a transport problem: rendering it costs seconds and
            // an escalation slot to arrive at the same 404. Crawls hit these
            // constantly, so leaving them in ERROR spent the whole budget on them.
            case 404, 410 -> ScrapeReason.NOT_FOUND;
            default -> ScrapeReason.ERROR;
        };
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
