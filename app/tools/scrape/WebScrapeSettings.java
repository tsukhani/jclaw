package tools.scrape;

import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.FetchSidecarManager;
import services.StealthSidecarManager;

import java.util.regex.Pattern;

/**
 * The {@code web_scrape.*} runtime keys, their defaults, and the rules
 * {@code ConfigService.setWithSideEffects} applies when one is written. Settings &gt; Web Scraping
 * edits them, except the proxy's keys, which Settings &gt; Proxy Providers edits.
 */
public final class WebScrapeSettings {

    public static final String PREFIX = "web_scrape.";

    public static final String MAX_PAGES = "web_scrape.max-pages";
    public static final String MAX_DEPTH = "web_scrape.max-depth";
    public static final String TIMEOUT_SECONDS = "web_scrape.timeout-seconds";
    public static final String CONCURRENCY = "web_scrape.concurrency";
    public static final String MAX_ESCALATIONS = "web_scrape.max-escalations";
    public static final String LANGUAGE = "web_scrape.language";
    public static final String RESPECT_ROBOTS = "web_scrape.respect-robots";
    public static final String SEED_FROM_SITEMAP = "web_scrape.seed-from-sitemap";
    public static final String MAX_SITEMAP_URLS = "web_scrape.max-sitemap-urls";
    public static final String MAX_SITEMAP_DOCUMENTS = "web_scrape.max-sitemap-documents";
    public static final String PROXY_URL = "web_scrape.proxy.url";
    public static final String PROXY_USERNAME = "web_scrape.proxy.username";
    public static final String PROXY_PASSWORD = "web_scrape.proxy.password";
    public static final String PROXY_ENABLED = "web_scrape.proxy.enabled";
    /** JCLAW-1334: the DataImpulse plan whose credentials the proxy uses, and the parameters that follow its login. */
    public static final String PROXY_DATAIMPULSE_PLAN = "web_scrape.proxy.dataimpulse.plan";
    public static final String PROXY_DATAIMPULSE_TARGETING = "web_scrape.proxy.dataimpulse.targeting";
    /** Background jobs (JCLAW-1272): the most an agent's job may ask for, and how many run at once. */
    public static final String JOB_MAX_PAGES = "web_scrape.job.max-pages";
    public static final String JOB_MAX_MINUTES = "web_scrape.job.max-minutes";
    public static final String JOB_MAX_CONCURRENT = "web_scrape.job.max-concurrent";

    /** Worker-pool ceiling, applied on read as well, so a value written around the API is still bounded. */
    public static final int MAX_CONCURRENCY = 16;

    /** Each running job has its own pool of {@link #CONCURRENCY} workers, so this multiplies it. */
    public static final int MAX_JOB_CONCURRENCY = 8;

    // The language is also sent verbatim in an Accept-Language header, so nothing but a code gets through.
    // The possessive repeat keeps the regex engine from recursing per subtag on a very long value.
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*+");

    // The shape of a curl_cffi target (chrome, chrome146, safari18_0_ios); whether this build knows it is the sidecar's call.
    private static final Pattern PROFILE_NAME = Pattern.compile("[a-z][a-z0-9_]*+");

    /** Preferred language for pages that declare translations. English by default; the
     *  per-call {@code language} argument overrides it, following the respectRobots
     *  precedent from JCLAW-1095 rather than being config-only. */
    public static final String DEFAULT_LANGUAGE = "en";
    public static final int DEFAULT_MAX_DEPTH = 2;
    public static final int DEFAULT_JOB_MAX_PAGES = 500;
    public static final int DEFAULT_JOB_MAX_MINUTES = 60;
    public static final int DEFAULT_JOB_MAX_CONCURRENT = 2;

    private WebScrapeSettings() {}

    public static int maxDepth() {
        return ConfigService.getInt(MAX_DEPTH, DEFAULT_MAX_DEPTH);
    }

    public static String language() {
        var configured = ConfigService.get(LANGUAGE, DEFAULT_LANGUAGE).strip();
        return configured.isEmpty() ? DEFAULT_LANGUAGE : configured;
    }

    public static boolean seedFromSitemap() {
        return !"false".equalsIgnoreCase(ConfigService.get(SEED_FROM_SITEMAP, "true").strip());
    }

    /**
     * Default for the {@code respectRobots} argument when a call omits it.
     *
     * <p>Default-on. Ignoring a site's robots.txt is a deliberate choice about someone
     * else's server, so it is opt-out per call rather than something that happens by
     * omission — and an operator who wants it off everywhere sets this key once.
     *
     * <p>Note what the override does <em>not</em> change: per-host pacing stays on
     * either way. "Ignore this site's directives" and "hammer this site" are different
     * requests, and only the first is available.
     */
    public static boolean respectRobots() {
        return !"false".equalsIgnoreCase(ConfigService.get(RESPECT_ROBOTS, "true").strip());
    }

    public static int jobMaxPages() {
        return ConfigService.getInt(JOB_MAX_PAGES, DEFAULT_JOB_MAX_PAGES);
    }

    public static int jobMaxMinutes() {
        return ConfigService.getInt(JOB_MAX_MINUTES, DEFAULT_JOB_MAX_MINUTES);
    }

    public static int jobMaxConcurrent() {
        return Math.clamp(ConfigService.getInt(JOB_MAX_CONCURRENT, DEFAULT_JOB_MAX_CONCURRENT),
                1, MAX_JOB_CONCURRENCY);
    }

    /** A message naming what a language code must look like, or null when {@code value} is one. */
    public static @Nullable String languageRejection(String value) {
        return LANGUAGE_CODE.matcher(value).matches() ? null
                : "language must be a language code such as en, ja or pt-BR";
    }

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        // Untrimmed: a trailing control character would otherwise reach the proxy's credentials.
        if (key.startsWith(DataImpulsePlans.PREFIX)) return DataImpulsePlans.rejectionFor(key, value == null ? "" : value);
        var v = value == null ? "" : value.trim();
        return switch (key) {
            // max-pages and max-depth are a Math.clamp's upper bound: below the floor the
            // bounds cross and every scrape call throws.
            case MAX_PAGES, TIMEOUT_SECONDS, JOB_MAX_PAGES, JOB_MAX_MINUTES -> wholeNumber(key, v, 1, Integer.MAX_VALUE);
            case MAX_DEPTH, MAX_ESCALATIONS, MAX_SITEMAP_URLS, MAX_SITEMAP_DOCUMENTS ->
                    wholeNumber(key, v, 0, Integer.MAX_VALUE);
            case CONCURRENCY -> wholeNumber(key, v, 1, MAX_CONCURRENCY);
            case JOB_MAX_CONCURRENT -> wholeNumber(key, v, 1, MAX_JOB_CONCURRENCY);
            case RESPECT_ROBOTS, SEED_FROM_SITEMAP, PROXY_ENABLED, StealthSidecarManager.CFG_ENABLED,
                    StealthSidecarManager.CFG_SOLVE_TURNSTILE, FetchSidecarManager.CFG_ENABLED ->
                    "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) ? null
                            : key + " must be true or false.";
            case FetchSidecarManager.CFG_PROFILE -> PROFILE_NAME.matcher(v).matches() ? null
                    : key + " must be an impersonation profile such as chrome or safari18_0.";
            case LANGUAGE -> LANGUAGE_CODE.matcher(v).matches() ? null
                    : key + " must be a language code such as en, ja or pt-BR.";
            case PROXY_URL -> ScrapeProxy.urlRejection(v);
            case PROXY_USERNAME, PROXY_PASSWORD -> ScrapeProxy.credentialRejection(v);
            default -> null;
        };
    }

    private static @Nullable String wholeNumber(String key, String v, int min, int max) {
        boolean inRange;
        try {
            int n = Integer.parseInt(v);
            inRange = n >= min && n <= max;
        } catch (NumberFormatException _) {
            inRange = false;
        }
        if (inRange) {
            return null;
        }
        return max == Integer.MAX_VALUE
                ? "%s must be a whole number of at least %d.".formatted(key, min)
                : "%s must be a whole number from %d to %d.".formatted(key, min, max);
    }
}
