package tools.scrape;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * The {@code web_scrape.*} runtime keys, all edited in Settings &gt; Web Scraping, and the rules
 * {@code ConfigService.setWithSideEffects} applies when one is written.
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

    /** Worker-pool ceiling, applied on read as well, so a value written around the API is still bounded. */
    public static final int MAX_CONCURRENCY = 16;

    // The language is also sent verbatim in an Accept-Language header, so nothing but a code gets through.
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");

    private WebScrapeSettings() {}

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        var v = value == null ? "" : value.trim();
        return switch (key) {
            // max-pages and max-depth are a Math.clamp's upper bound: below the floor the
            // bounds cross and every scrape call throws.
            case MAX_PAGES, TIMEOUT_SECONDS -> wholeNumber(key, v, 1, Integer.MAX_VALUE);
            case MAX_DEPTH, MAX_ESCALATIONS, MAX_SITEMAP_URLS, MAX_SITEMAP_DOCUMENTS ->
                    wholeNumber(key, v, 0, Integer.MAX_VALUE);
            case CONCURRENCY -> wholeNumber(key, v, 1, MAX_CONCURRENCY);
            case RESPECT_ROBOTS, SEED_FROM_SITEMAP ->
                    "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) ? null
                            : key + " must be true or false.";
            case LANGUAGE -> LANGUAGE_CODE.matcher(v).matches() ? null
                    : key + " must be a language code such as en, ja or pt-BR.";
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
