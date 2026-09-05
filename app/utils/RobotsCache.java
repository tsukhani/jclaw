package utils;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import okhttp3.OkHttpClient;
import play.Logger;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-host robots.txt rules and request pacing for {@code web_scrape} (JCLAW-1084).
 *
 * <p>Self-interest more than etiquette: the failure mode this prevents is an operator's
 * IP being banned, which costs access to the site permanently rather than politely.
 *
 * <p>Both maps are static, so the rate limit is shared across every concurrent scrape
 * in the JVM. Two agents crawling the same host must not jointly exceed the rate one
 * of them would — a per-crawl limiter would make the guarantee proportional to how
 * many agents happen to be running.
 *
 * <p>Uses the same {@link SimpleRobotRulesParser} Nutch and StormCrawler use, so the
 * Googlebot-compatible matching rules come from a parser that is exercised far more
 * widely than anything written here would be.
 */
public final class RobotsCache {

    private RobotsCache() {}

    private static final SimpleRobotRulesParser PARSER = new SimpleRobotRulesParser();

    /** How long a host's robots.txt is trusted before being re-fetched. Long enough that
     *  a crawl never re-fetches it, short enough that an operator editing robots.txt sees
     *  the change the same day. */
    private static final long CACHE_TTL_NANOS = 3_600L * 1_000_000_000L;

    /** Pacing floor when robots.txt declares no crawl-delay. Not from any standard —
     *  four requests a second to one host is a rate a human browsing could produce. */
    public static final long DEFAULT_DELAY_MS = 250;

    /** Ceiling on a single wait. Some sites declare crawl-delay in the tens of seconds;
     *  honoring that literally would park a virtual thread for the whole crawl budget.
     *  The wait is capped and the crawl's own wall-clock budget ends it instead, so the
     *  outcome is fewer pages rather than a stalled tool call.
     *
     *  <p>Note the parser applies its own, higher threshold first: a Crawl-delay above
     *  {@code SimpleRobotRulesParser.DEFAULT_MAX_CRAWL_DELAY} (300s) is <em>discarded</em>
     *  rather than capped, so it arrives here as unset and falls to the default. */
    private static final long MAX_DELAY_MS = 5_000;

    /**
     * The two identity strings that have to stay consistent, kept together so they
     * cannot drift apart at a call site.
     *
     * @param userAgentHeader what is sent on the wire
     * @param robotName       the token matched against {@code User-agent:} lines in
     *                        robots.txt — a bare product token, never the full header,
     *                        because that is what the directive is written against
     */
    public record Identity(String userAgentHeader, String robotName) {}

    private record CachedRules(BaseRobotRules rules, long fetchedAtNanos) {}

    private static final Map<String, CachedRules> RULES = new ConcurrentHashMap<>();
    private static final Map<String, Long> NEXT_SLOT_NANOS = new ConcurrentHashMap<>();

    /** Clears both caches. For tests — production has no reason to forget. */
    public static void resetForTest() {
        RULES.clear();
        NEXT_SLOT_NANOS.clear();
    }

    /**
     * Whether robots.txt permits fetching this URL for {@code userAgent}.
     *
     * <p>Fails open. A robots.txt that 404s, times out, or comes back unparseable
     * yields allow-all, because this is a politeness control and a broken file on
     * someone else's server should not break the caller's crawl. The security
     * boundary is {@link SsrfGuard} and the outbound allowlist, neither of which
     * fails open.
     */
    public static boolean isAllowed(URI uri, OkHttpClient client, Identity identity) {
        return rulesFor(uri, client, identity).isAllowed(uri.toString());
    }

    /**
     * The {@code Sitemap:} URLs this host's robots.txt advertises (JCLAW-1092).
     *
     * <p>Costs no request of its own: robots.txt is already fetched and cached for the
     * allow and crawl-delay checks, and {@code getSitemaps()} reads a field the same
     * parse populated. Returns empty when the host publishes none, which is the common
     * case and not an error.
     */
    public static List<String> sitemapsFor(URI uri, OkHttpClient client, Identity identity) {
        var sitemaps = rulesFor(uri, client, identity).getSitemaps();
        return sitemaps == null ? List.of() : List.copyOf(sitemaps);
    }

    /** Crawl-delay this host asks for, clamped to a sane band. */
    public static long delayMillis(URI uri, OkHttpClient client, Identity identity) {
        long declared = rulesFor(uri, client, identity).getCrawlDelay();
        if (declared == BaseRobotRules.UNSET_CRAWL_DELAY || declared <= 0) {
            return DEFAULT_DELAY_MS;
        }
        return Math.clamp(declared, DEFAULT_DELAY_MS, MAX_DELAY_MS);
    }

    /**
     * Block until this host's next slot, then claim the one after it.
     *
     * <p>The claim is made with a single {@code compute} so two threads racing on the
     * same host serialize onto consecutive slots rather than both reading the same
     * "next" and firing together.
     */
    public static void awaitSlot(URI uri, long delayMillis) throws InterruptedException {
        var host = host(uri);
        long delayNanos = delayMillis * 1_000_000L;
        long now = System.nanoTime();
        long slot = NEXT_SLOT_NANOS.compute(host, (_, prev) -> {
            long at = (prev == null || prev < now) ? now : prev;
            return at + delayNanos;
        }) - delayNanos;

        long waitNanos = slot - now;
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
        }
    }

    private static BaseRobotRules rulesFor(URI uri, OkHttpClient client, Identity identity) {
        var host = host(uri);
        var cached = RULES.get(host);
        if (cached != null && System.nanoTime() - cached.fetchedAtNanos() < CACHE_TTL_NANOS) {
            return cached.rules();
        }
        var rules = fetchRules(uri, client, identity);
        RULES.put(host, new CachedRules(rules, System.nanoTime()));
        return rules;
    }

    private static BaseRobotRules fetchRules(URI uri, OkHttpClient client, Identity identity) {
        var robotsUrl = "%s://%s%s/robots.txt".formatted(
                uri.getScheme(), uri.getHost(),
                uri.getPort() == -1 ? "" : ":" + uri.getPort());
        try {
            // Through the guarded fetch like any other URL — robots.txt lives on the same
            // untrusted host as the pages, and gets no exemption from SsrfGuard.
            var fetched = WebExtraction.fetch(robotsUrl, client,
                    // Explicit: the shared default prefers markdown, which a
                    // content-negotiating origin would rank above this file's own type.
                    Map.of("User-Agent", identity.userAgentHeader(),
                            "Accept", "text/plain"));
            return PARSER.parseContent(robotsUrl, fetched.body(),
                    fetched.contentType(), List.of(identity.robotName()));
        } catch (Exception e) {
            Logger.debug("robots.txt unavailable for %s (%s) — treating as allow-all",
                    host(uri), e.getMessage());
            return PARSER.failedFetch(404);
        }
    }

    private static String host(URI uri) {
        var h = uri.getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }
}
