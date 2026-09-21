package tools.scrape;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import utils.SsrfGuard;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;

/**
 * What a background scrape job crawls, and within what limits (JCLAW-1272).
 *
 * <p>Parsed once from either door, the {@code web_scrape} tool's arguments or the operator's New
 * scrape form, which is why the argument names are the tool's. Stored on the job as
 * {@link #toJson()}, so a waiting job runs with the values it was accepted with even if Settings
 * change before it starts.
 *
 * @param maxMinutes the job's time limit; a crawl inside a turn is bounded in seconds instead
 */
public record ScrapeJobRequest(URI url, int maxPages, int maxDepth, int maxMinutes, boolean sameHostOnly,
                               boolean respectRobots, boolean seedFromSitemap, String language,
                               ScrapeOutput.Request output) {

    public static final String ARG_URL = "url";
    public static final String ARG_MAX_PAGES = "maxPages";
    public static final String ARG_MAX_DEPTH = "maxDepth";
    public static final String ARG_MAX_MINUTES = "maxMinutes";
    public static final String ARG_SAME_HOST = "sameHostOnly";
    public static final String ARG_RESPECT_ROBOTS = "respectRobots";
    public static final String ARG_SEED_FROM_SITEMAP = "seedFromSitemap";
    public static final String ARG_LANGUAGE = "language";

    /**
     * An agent's request: each limit is clamped into the operator's ceilings, as the inline crawl
     * clamps its own.
     *
     * @throws IllegalArgumentException naming the argument that cannot be used
     */
    public static ScrapeJobRequest forAgent(JsonObject args) {
        return parse(args, true);
    }

    /**
     * The operator's request. The ceilings bound what an agent may ask for, not the person who set
     * them, so a limit here is only checked against its floor.
     *
     * @throws IllegalArgumentException naming the field that cannot be used
     */
    public static ScrapeJobRequest forOperator(JsonObject args) {
        return parse(args, false);
    }

    /** A request stored by {@link #toJson()}, read back without re-applying today's ceilings. */
    public static ScrapeJobRequest fromJson(String json) {
        return parse(JsonParser.parseString(json).getAsJsonObject(), false);
    }

    private static ScrapeJobRequest parse(JsonObject args, boolean agent) {
        var url = url(args);
        int pagesCeiling = WebScrapeSettings.jobMaxPages();
        int depthCeiling = WebScrapeSettings.maxDepth();
        int minutesCeiling = WebScrapeSettings.jobMaxMinutes();
        int maxPages = limit(args, ARG_MAX_PAGES, pagesCeiling, 1, agent);
        int maxDepth = limit(args, ARG_MAX_DEPTH, depthCeiling, 0, agent);
        int maxMinutes = limit(args, ARG_MAX_MINUTES, minutesCeiling, 1, agent);
        var language = WebScrapeSettings.language();
        if (present(args, ARG_LANGUAGE)) {
            language = args.get(ARG_LANGUAGE).getAsString().strip();
            var rejection = WebScrapeSettings.languageRejection(language);
            if (rejection != null) throw new IllegalArgumentException(rejection);
        }
        return new ScrapeJobRequest(url, maxPages, maxDepth, maxMinutes,
                flag(args, ARG_SAME_HOST, true),
                flag(args, ARG_RESPECT_ROBOTS, WebScrapeSettings.respectRobots()),
                flag(args, ARG_SEED_FROM_SITEMAP, WebScrapeSettings.seedFromSitemap()),
                language,
                ScrapeOutput.parse(args, false, ScrapeOutput.Format.MARKDOWN));
    }

    private static URI url(JsonObject args) {
        if (!present(args, ARG_URL) || args.get(ARG_URL).getAsString().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI url;
        try {
            url = URI.create(args.get(ARG_URL).getAsString().strip());
            SsrfGuard.assertSafeScheme(url);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new IllegalArgumentException("url cannot be scraped: " + e.getMessage());
        }
        return url;
    }

    /** The ceiling doubles as the default, as it does for the inline crawl. */
    private static int limit(JsonObject args, String name, int ceiling, int floor, boolean clamp) {
        if (!present(args, name)) return Math.max(ceiling, floor);
        int value;
        try {
            // Through BigDecimal so 2.5 is refused rather than read as 2, and "12" is still 12.
            value = new BigDecimal(args.get(name).getAsString().strip()).intValueExact();
        } catch (RuntimeException _) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
        if (clamp) return Math.clamp(value, floor, Math.max(ceiling, floor));
        if (value < floor) throw new IllegalArgumentException("%s must be at least %d".formatted(name, floor));
        return value;
    }

    private static boolean flag(JsonObject args, String name, boolean fallback) {
        if (!present(args, name)) return fallback;
        var value = args.get(name).getAsString().strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    private static boolean present(JsonObject args, String name) {
        JsonElement value = args.get(name);
        return value != null && !value.isJsonNull() && value.isJsonPrimitive();
    }

    /** Every value spelled out, so reading it back never falls through to a default. */
    public JsonObject toJson() {
        var json = new JsonObject();
        json.addProperty(ARG_URL, url.toString());
        json.addProperty(ARG_MAX_PAGES, maxPages);
        json.addProperty(ARG_MAX_DEPTH, maxDepth);
        json.addProperty(ARG_MAX_MINUTES, maxMinutes);
        json.addProperty(ARG_SAME_HOST, sameHostOnly);
        json.addProperty(ARG_RESPECT_ROBOTS, respectRobots);
        json.addProperty(ARG_SEED_FROM_SITEMAP, seedFromSitemap);
        json.addProperty(ARG_LANGUAGE, language);
        json.addProperty(ScrapeOutput.ARG_FORMAT, output.format().name().toLowerCase(Locale.ROOT));
        if (!output.extract().isEmpty()) {
            var extract = new JsonObject();
            output.extract().forEach((name, field) -> extract.addProperty(name, field.spec()));
            json.add(ScrapeOutput.ARG_EXTRACT, extract);
        }
        json.addProperty(ScrapeOutput.ARG_METADATA, output.metadata());
        return json;
    }
}
