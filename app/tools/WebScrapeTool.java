package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.OkHttpClient;
import services.EventLogger;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.ScrapeLadder;
import tools.scrape.SitemapSeeder;
import utils.PlayConfig;
import utils.RobotsCache;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read a page and the pages it links to, as one block of Markdown.
 *
 * <p>The gap between this and {@code web_fetch} is a frontier, not an engine: both
 * run the same {@link WebExtraction} fetch-and-extract chain, and this one adds a
 * queue, a budget, and a scope rule (JCLAW-1083). Every URL — seed and harvested
 * alike — enters through {@link WebExtraction#fetch}, so {@link SsrfGuard} sees each
 * one and each redirect hop.
 *
 * <p>Breadth-first on purpose. A crawl that runs out of budget mid-way should have
 * spent it on the seed's immediate neighbors rather than one deep chain, because
 * the pages nearest the seed are the ones the caller asked about.
 */
public class WebScrapeTool implements ToolRegistry.Tool {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int TIMEOUT_SECONDS = 30;

    /** Mirrors {@code WebFetchTool.CLIENT}: package-private and non-final so tests can
     *  substitute a socket-free client, since SsrfGuard's DNS blocks the loopback a
     *  local test server would bind. Production must not mutate it. */
    static OkHttpClient CLIENT = SsrfGuard.buildGuardedClient(
            CONNECT_TIMEOUT_SECONDS, TIMEOUT_SECONDS);

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; JClaw/1.0)";
    /**
     * Request headers for a crawl in {@code language}.
     *
     * <p>{@code Accept-Language} is the half of language preference that works on sites
     * doing server-side negotiation — they hand us the right translation directly,
     * before any of the hreflang machinery is involved. It complements rather than
     * replaces that: hreflang suppression handles path-segmented sites, this handles
     * negotiated ones, and neither covers a site that does neither.
     *
     * <p>The {@code *;q=0.5} tail is load-bearing. A bare {@code Accept-Language: en}
     * invites a 406 or an empty body from a site that has no English at all, and a
     * language preference must never cost us the page — same reasoning as keeping
     * {@code text/html} acceptable while preferring markdown.
     */
    private static Map<String, String> headersFor(String language) {
        return Map.of("User-Agent", USER_AGENT,
                "Accept-Language", language + ", *;q=0.5");
    }

    /** {@code jclaw} is the token a site would write in a {@code User-agent:} line;
     *  the header above is what goes on the wire. */
    private static final RobotsCache.Identity IDENTITY =
            new RobotsCache.Identity(USER_AGENT, "jclaw");

    private static final String ARG_URL = "url";
    private static final String ARG_MAX_PAGES = "maxPages";
    private static final String ARG_MAX_DEPTH = "maxDepth";
    private static final String ARG_SAME_HOST = "sameHostOnly";
    private static final String ARG_RESPECT_ROBOTS = "respectRobots";

    private static final String CFG_MAX_PAGES = "web_scrape.max-pages";
    private static final String CFG_MAX_DEPTH = "web_scrape.max-depth";
    private static final String CFG_TIMEOUT_SECONDS = "web_scrape.timeout-seconds";
    private static final String CFG_RESPECT_ROBOTS = "web_scrape.respect-robots";
    private static final String CFG_CONCURRENCY = "web_scrape.concurrency";
    private static final String CFG_MAX_ESCALATIONS = "web_scrape.max-escalations";
    private static final String CFG_SEED_FROM_SITEMAP = "web_scrape.seed-from-sitemap";
    private static final String CFG_LANGUAGE = "web_scrape.language";
    private static final String ARG_LANGUAGE = "language";
    private static final String EVENT_CATEGORY = "scrape";

    /** Preferred language for pages that declare translations. English by default; the
     *  per-call {@code language} argument overrides it, following the respectRobots
     *  precedent from JCLAW-1095 rather than being config-only. */
    private static final String DEFAULT_LANGUAGE = "en";

    /** Escalated pages allowed per crawl. Deliberately well below max-pages: a rung-3
     *  render costs seconds where a plain fetch costs milliseconds, so a crawl that
     *  escalated every page would be unusable. Five buys the pages most likely to
     *  matter — a blocked entry page, a client-rendered section — without turning a
     *  25-page crawl into a multi-minute one. */
    private static final int DEFAULT_MAX_ESCALATIONS = 5;

    private static final String INTERRUPTED = "interrupted";
    private static final String ROBOTS_REFUSAL = "disallowed by robots.txt";

    /** Ceilings an operator can lower but the model cannot raise. This is a tool call
     *  inside a conversation, not a background crawler: the caller is waiting, and the
     *  result has to fit a context window. */
    private static final int DEFAULT_MAX_PAGES = 25;
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Outbound fan-out. Deliberately operator config and never a tool argument:
     *  fan-out is a resource knob, not a task-shaping one, so it belongs beside
     *  {@code dispatcher.llm.maxRequestsPerHost} rather than in a model-supplied
     *  argument. Raising it cannot make a crawl ruder — per-host pacing
     *  ({@link RobotsCache#awaitSlot}) claims slots atomically, so concurrent
     *  workers on one host queue onto consecutive slots instead of firing together.
     *  What it buys is overlapping round-trip time, turning a latency-bound crawl
     *  into a pacing-bound one. */
    private static final int DEFAULT_CONCURRENCY = 4;
    private static final int MAX_CONCURRENCY = 16;

    /** Total budget across every page, matching what one web_fetch may return. */
    private static final int MAX_TOTAL_CHARS = WebExtraction.MAX_TEXT_LENGTH;

    @Override public String name() { return "web_scrape"; }
    @Override public String category() { return "Web"; }
    @Override public String icon() { return "globe"; }

    @Override
    public String shortDescription() {
        return "Read a page and the pages it links to, as one block of Markdown.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("scrape",
                "Crawl from a URL, following links within a page and depth budget"));
    }

    @Override
    public String description() {
        return """
                Read a starting URL and the pages it links to, returning all of them as one \
                Markdown document with each page's source URL as a heading. \
                Use this for a documentation site, a multi-page article, or any question that \
                needs more than one page. \
                For a single page use web_fetch instead — it is faster and cheaper.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_URL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "The URL to start from"),
                        ARG_MAX_PAGES, Map.of(SchemaKeys.TYPE, "integer",
                                SchemaKeys.DESCRIPTION,
                                "Maximum pages to read (default %d)".formatted(DEFAULT_MAX_PAGES)),
                        ARG_MAX_DEPTH, Map.of(SchemaKeys.TYPE, "integer",
                                SchemaKeys.DESCRIPTION,
                                "How many links deep to follow; 0 reads only the starting URL (default %d)"
                                        .formatted(DEFAULT_MAX_DEPTH)),
                        ARG_SAME_HOST, Map.of(SchemaKeys.TYPE, "boolean",
                                SchemaKeys.DESCRIPTION,
                                "Stay on the starting URL's host (default true)"),
                        ARG_RESPECT_ROBOTS, Map.of(SchemaKeys.TYPE, "boolean",
                                SchemaKeys.DESCRIPTION,
                                "Honour the site's robots.txt (default true). Set false ONLY when "
                                + "the user explicitly asks to ignore robots.txt for this request; "
                                + "never choose it yourself to work around a refusal."),
                        ARG_LANGUAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "Preferred language for sites that publish translations, as an "
                                + "hreflang code such as 'en', 'ja' or 'pt-BR' (default '%s'). "
                                + "Other translations of a page are skipped so the page budget "
                                + "is spent on distinct content."
                                        .formatted(DEFAULT_LANGUAGE))
                ),
                SchemaKeys.REQUIRED, List.of(ARG_URL)
        );
    }

    /** Holds no handles between calls and writes nothing to disk. */
    @Override public boolean parallelSafe() { return true; }

    /** {@code servedBy} is the rung that produced this text. PLAIN for the ordinary
     *  case; anything higher means the ladder was climbed for this page. */
    private record Page(String url, String text, ScrapeRung servedBy) {}

    /** A frontier URL the guard declined, kept apart from {@link Page} so a refusal
     *  never spends a slot in the page budget. */
    private record Refusal(String url, String why) {}

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        URI seed;
        try {
            seed = URI.create(args.get(ARG_URL).getAsString().strip());
        } catch (RuntimeException e) {
            return "Error: could not parse url: %s".formatted(e.getMessage());
        }

        int maxPages = Math.clamp(
                args.has(ARG_MAX_PAGES) ? args.get(ARG_MAX_PAGES).getAsInt() : configMaxPages(),
                1, configMaxPages());
        int maxDepth = Math.clamp(
                args.has(ARG_MAX_DEPTH) ? args.get(ARG_MAX_DEPTH).getAsInt() : configMaxDepth(),
                0, configMaxDepth());
        boolean sameHostOnly = !args.has(ARG_SAME_HOST) || args.get(ARG_SAME_HOST).getAsBoolean();
        // Config supplies the default; the argument overrides it per call. An operator
        // who wants robots ignored everywhere sets the config once, and one who leaves
        // it on can still say "ignore robots for this" in a single request.
        boolean respectRobots = args.has(ARG_RESPECT_ROBOTS)
                ? args.get(ARG_RESPECT_ROBOTS).getAsBoolean()
                : respectRobotsDefault();

        try {
            SsrfGuard.assertSafeScheme(seed);
        } catch (SecurityException e) {
            return "Error: URL rejected by SSRF guard: %s".formatted(e.getMessage());
        }
        var language = args.has(ARG_LANGUAGE) && !args.get(ARG_LANGUAGE).isJsonNull()
                ? args.get(ARG_LANGUAGE).getAsString().strip()
                : languageDefault();
        return crawl(seed, maxPages, maxDepth, sameHostOnly, respectRobots, language);
    }

    /**
     * Fetch one URL exactly as the crawl would: SSRF admission, robots, per-host pacing,
     * then the shared fetch and extraction (JCLAW-1094).
     *
     * <p>Public so {@code ScrapeHarness} can measure this tool as a rung rather than a
     * replica of it. Returning the observation rather than rendered text keeps the rung
     * comparable with rung 1 — both hand the same classifier the same raw body, so a
     * difference in their access rates is a difference in admission, not in how the two
     * were scored.
     */
    public ScrapeObservation fetchSingle(String url) {
        URI uri;
        try {
            uri = URI.create(url);
            SsrfGuard.assertSafeScheme(uri);
        } catch (RuntimeException e) {
            return ScrapeObservation.failed(url, "rejected: " + e.getMessage());
        }
        boolean respect = respectRobotsDefault();
        if (respect && !RobotsCache.isAllowed(uri, CLIENT, IDENTITY)) {
            return ScrapeObservation.failed(url, ROBOTS_REFUSAL);
        }
        try {
            RobotsCache.awaitSlot(uri, respect
                    ? RobotsCache.delayMillis(uri, CLIENT, IDENTITY)
                    : RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return ScrapeObservation.failed(url, INTERRUPTED);
        }
        try {
            var fetched = WebExtraction.fetch(url, CLIENT, headersFor(languageDefault()));
            return ScrapeObservation.of(fetched, WebExtraction.toText(fetched));
        } catch (Exception e) {
            return ScrapeObservation.failed(url, reason(e));
        }
    }

    /** Accumulators for one crawl. A small mutable carrier so the passes below can be
     *  separate methods without threading six out-parameters through each of them. */
    private static final class CrawlState {
        final List<Page> pages = new ArrayList<>();
        final List<Refusal> refused = new ArrayList<>();
        final LinkedHashSet<String> seen = new LinkedHashSet<>();
        int totalChars;
        /** Locale variants of pages already queued, dropped before they spend budget. */
        int localeVariantsDropped;
        /** Path prefixes the site's own hreflang markup identified as non-preferred
         *  locale roots, e.g. "/ar/". Learned, never guessed — see suppressLocaleVariants. */
        final LinkedHashSet<String> suppressedLocalePrefixes = new LinkedHashSet<>();
        int unvisited;
        String stoppedBecause;
        /** {@code System.nanoTime()} at which the crawl's declared timeout expires. */
        long deadline;
        /** Remaining escalation budget, and what refusing it cost — reported rather
         *  than silently dropped, so a thin result is never mistaken for a blocked one. */
        int escalationsLeft = maxEscalations();
        int escalationsUsed;
        int escalationsSuppressed;
        /** Kept apart from {@link #escalationsSuppressed}: raising max-escalations fixes
         *  one and does nothing for the other. */
        int escalationsOutOfTime;

        /** Claimed from the crawl pool, so two threads escalating at once cannot
         *  overdraw the budget. */
        synchronized boolean claimEscalation() {
            if (escalationsLeft <= 0) {
                escalationsSuppressed++;
                return false;
            }
            escalationsLeft--;
            escalationsUsed++;
            return true;
        }

        synchronized void noteOutOfTime() {
            escalationsOutOfTime++;
        }
    }

    private String crawl(URI seed, int maxPages, int maxDepth, boolean sameHostOnly,
                         boolean respectRobots, String language) {
        var state = new CrawlState();
        state.deadline = System.nanoTime()
                + Duration.ofSeconds(configTimeoutSeconds()).toNanos();
        state.seen.add(canonical(seed));
        var level = List.of(seed);
        int depth = 0;

        try (var pool = Executors.newFixedThreadPool(configConcurrency())) {
            while (!level.isEmpty()) {
                var admitted = withinBudget(admit(level, respectRobots, state), maxPages, state);
                if (admitted.isEmpty()) {
                    break;
                }
                var fetched = fetchLevel(pool, admitted, respectRobots, language, state);
                if (state.stoppedBecause != null || exhausted(state)
                        || depth >= maxDepth) {
                    break;
                }
                if (depth == 0) {
                    // Before harvesting or seeding, so both are filtered by what the
                    // site's markup says about its own translations.
                    learnLocalesFromHtml(seed, language, state, fetched);
                }
                level = nextLevel(fetched, seed, sameHostOnly, language, state);
                if (depth == 0) {
                    level = withSitemapSeeds(level, seed, sameHostOnly, respectRobots, state);
                }
                depth++;
            }
        }
        return render(seed, state, maxDepth, sameHostOnly);
    }

    /**
     * Drop the URLs this crawl will not visit, recording why.
     *
     * <p>Runs single-threaded before any work is submitted. Both checks are cheap, and
     * rejecting here keeps {@code seen}, the refusal list and the page budget free of
     * synchronization.
     */
    private static List<URI> admit(List<URI> level, boolean respectRobots, CrawlState state) {
        var admitted = new ArrayList<URI>();
        for (var uri : level) {
            try {
                SsrfGuard.assertSafeScheme(uri);
            } catch (SecurityException e) {
                state.refused.add(new Refusal(uri.toString(), e.getMessage()));
                continue;
            }
            if (respectRobots && !RobotsCache.isAllowed(uri, CLIENT, IDENTITY)) {
                state.refused.add(new Refusal(uri.toString(), ROBOTS_REFUSAL));
                continue;
            }
            admitted.add(uri);
        }
        return admitted;
    }

    /** Slice a level to the remaining page budget before anything is submitted, so the
     *  page count stays exact without workers racing a shared counter. */
    private static List<URI> withinBudget(List<URI> admitted, int maxPages, CrawlState state) {
        int room = maxPages - state.pages.size();
        if (room <= 0) {
            state.stoppedBecause = budgetReached(maxPages);
            state.unvisited += admitted.size();
            return List.of();
        }
        if (admitted.size() > room) {
            // Truncating a level to the remaining budget IS the stop reason. Leaving it
            // unset reported "Stopped: null" beside a non-zero unread count.
            state.unvisited += admitted.size() - room;
            state.stoppedBecause = budgetReached(maxPages);
            return List.copyOf(admitted.subList(0, room));
        }
        return admitted;
    }

    private static String budgetReached(int maxPages) {
        return "page budget (%d) reached".formatted(maxPages);
    }

    /**
     * Fetch a whole level concurrently and record each outcome.
     *
     * <p>Results are collected in submission order, never completion order: the
     * JCLAW-1091 harness compares runs, and a result whose page order varies per run is
     * not comparable.
     */
    private List<WebExtraction.FetchResult> fetchLevel(ExecutorService pool, List<URI> admitted,
                                                       boolean respectRobots, String language,
                                                       CrawlState state) {
        var futures = admitted.stream()
                .map(uri -> pool.submit(() -> fetchOne(uri, respectRobots, language, state)))
                .toList();
        var fetched = new ArrayList<WebExtraction.FetchResult>();
        for (int i = 0; i < futures.size(); i++) {
            var uri = admitted.get(i);
            try {
                recordOutcome(futures.get(i).get(), uri, state, fetched);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                state.stoppedBecause = INTERRUPTED;
                return fetched;
            } catch (ExecutionException e) {
                state.pages.add(new Page(uri.toString(),
                        "[Not retrieved \u2014 %s]".formatted(reason(e)), ScrapeRung.PLAIN));
            }
        }
        return fetched;
    }

    /** Name the reason and the rung that would address it rather than reporting a bare
     *  failure. An agent reading "TURNSTILE" knows not to retry; "[Could not fetch]"
     *  invites a retry loop. */
    private static void recordOutcome(Outcome outcome, URI uri, CrawlState state,
                                      List<WebExtraction.FetchResult> fetched) {
        if (!outcome.usable()) {
            state.pages.add(new Page(uri.toString(), "[Not retrieved \u2014 %s%s%s]"
                    .formatted(outcome.reason(),
                            outcome.detail() == null ? "" : ": " + outcome.detail(),
                            outcome.nextRung() == ScrapeRung.NONE ? ""
                                    : "; needs " + outcome.nextRung()), outcome.servedBy()));
            return;
        }
        state.pages.add(new Page(outcome.fetched().finalUrl(), outcome.text(),
                outcome.servedBy()));
        state.totalChars += outcome.text().length();
        fetched.add(outcome.fetched());
    }

    /** True when the time or content budget is spent; records which one. */
    private boolean exhausted(CrawlState state) {
        if (System.nanoTime() > state.deadline) {
            state.stoppedBecause = "time budget (%ds) reached".formatted(configTimeoutSeconds());
            return true;
        }
        if (state.totalChars >= MAX_TOTAL_CHARS) {
            state.stoppedBecause =
                    "content budget (%d characters) reached".formatted(MAX_TOTAL_CHARS);
            return true;
        }
        return false;
    }

    private static List<URI> nextLevel(List<WebExtraction.FetchResult> fetched, URI seed,
                                       boolean sameHostOnly, String language, CrawlState state) {
        var next = new ArrayList<URI>();
        for (var f : fetched) {
            // Before harvesting: a page that declares translations of itself gets all but
            // one of them retired, so they never compete for the page budget. Without
            // this, docs.openclaw.ai spent all 25 slots on one page in fifteen languages
            // and reported "80 discovered pages not read" (JCLAW-1100).
            suppressLocaleVariants(f, language, state);
            collectLinks(f, seed, sameHostOnly, state, next);
        }
        return next;
    }

    /**
     * Retire every declared translation of {@code fetched} except the preferred one.
     *
     * <p>Marks them seen rather than filtering later, so the suppression also covers a
     * link to the same variant found on some other page.
     *
     * <p><b>Never suppresses everything.</b> A site with no variant in the preferred
     * language keeps one — its own URL, or failing that the first declared — because a
     * language filter that empties the frontier is worse than no filter at all.
     */
    private static void suppressLocaleVariants(WebExtraction.FetchResult fetched,
                                               String language, CrawlState state) {
        var alternates = WebExtraction.alternates(fetched);
        if (alternates.size() < 2) return;

        var keeper = pickVariant(alternates, language, fetched.finalUrl());
        for (var entry : alternates.entrySet()) {
            var uri = entry.getValue();
            if (uri.equals(keeper)) continue;
            if (state.seen.add(canonical(uri))) {
                state.localeVariantsDropped++;
            }
            localeRoot(uri, keeper).ifPresent(state.suppressedLocalePrefixes::add);
        }
    }

    /**
     * The path prefix a non-preferred variant lives under, when the site's own markup
     * says so — {@code https://host/ar} against a keeper of {@code https://host/} yields
     * {@code /ar/}.
     *
     * <p>Suppressing declared alternates alone is not enough: it is reactive, and only
     * teaches the crawl about a page it has already fetched. Against docs.openclaw.ai the
     * home page's translations vanished but {@code /ar/agent-runtime-architecture} still
     * spent budget, because it was queued before its English twin was read.
     *
     * <p>This is the path heuristic the ticket permits as a <em>supporting</em> signal:
     * the prefix is only ever taken from a URL the site itself declared as an alternate,
     * so {@code /design} is never mistaken for German. Nothing is inferred from a path
     * the markup did not name.
     */
    private static Optional<String> localeRoot(URI variant, URI keeper) {
        var path = variant.getPath() == null ? "" : variant.getPath();
        var keeperPath = keeper.getPath() == null ? "" : keeper.getPath();
        // Only a root-level variant defines a prefix: /ar over /, not /a/b over /a.
        if (!keeperPath.isEmpty() && !"/".equals(keeperPath)) return Optional.empty();
        var trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty() || trimmed.contains("/")) return Optional.empty();
        return Optional.of("/" + trimmed + "/");
    }

    /** Exact hreflang match, then primary subtag ({@code en} matches {@code en-GB}), then
     *  the page we are already holding, then whatever came first. */
    private static URI pickVariant(Map<String, URI> alternates, String language, String finalUrl) {
        var want = language.toLowerCase(Locale.ROOT);
        var exact = alternates.get(want);
        if (exact != null) return exact;
        for (var e : alternates.entrySet()) {
            var primary = e.getKey().split("-", 2)[0];
            if (primary.equals(want.split("-", 2)[0])) return e.getValue();
        }
        for (var e : alternates.entrySet()) {
            if (e.getValue().toString().equals(finalUrl)) return e.getValue();
        }
        return alternates.values().iterator().next();
    }

    /**
     * Read the seed's translations from its HTML when the crawl never saw any (JCLAW-1100).
     *
     * <p>Costs one extra request, and only on sites that serve markdown. It exists because
     * the three discovery features interact badly without it: preferring markdown
     * (JCLAW-1101) means the seed arrives with no {@code <link rel="alternate">} to learn
     * from, sitemap seeding (JCLAW-1092) then supplies the frontier, and a sitemap carries
     * no locale annotation — docs.openclaw.ai lists 719 Arabic URLs in plain
     * alphabetical order, so {@code /ar/} filled the page budget with nothing to say it
     * was a translation. Locale de-duplication silently did not work on exactly the sites
     * markdown-first was built for.
     *
     * <p>Deliberately conditional: an HTML seed already carries its alternates, so the
     * extra request happens only when the seed came back as markdown. One request buys
     * the locale map for the whole crawl.
     */
    private static void learnLocalesFromHtml(URI seed, String language, CrawlState state,
                                             List<WebExtraction.FetchResult> fetched) {
        if (!state.suppressedLocalePrefixes.isEmpty()) return;

        // An HTML seed already carries its own alternates — read them and spend nothing.
        for (var f : fetched) {
            suppressLocaleVariants(f, language, state);
        }
        if (!state.suppressedLocalePrefixes.isEmpty()) return;
        // Only a markdown seed is worth a second request; anything else has either told
        // us its translations or has none to tell.
        if (fetched.stream().noneMatch(f -> WebExtraction.isMarkdown(f.contentType()))) return;

        try {
            var html = WebExtraction.fetch(seed.toString(), CLIENT,
                    Map.of("User-Agent", IDENTITY.userAgentHeader(),
                            "Accept", "text/html",
                            "Accept-Language", language + ", *;q=0.5"));
            suppressLocaleVariants(html, language, state);
        } catch (Exception _) {
            // Best effort. A site that will not serve HTML simply keeps its translations
            // in the frontier, which is where they were before this existed.
        }
    }

    /**
     * Merge the host's sitemap URLs into the frontier (JCLAW-1092).
     *
     * <p><b>Seeded URLs are depth 1, not depth 0.</b> The ticket asked for this to be
     * decided and recorded. Depth 0 means "the page you asked for" — a caller passing
     * {@code maxDepth=0} gets one page, and letting a sitemap add twenty-five more would
     * silently redefine that contract for every existing caller. Seeding is discovery,
     * {@code maxDepth} is what bounds discovery, so a depth-0 crawl correctly does no
     * seeding at all. Seeded URLs then compete with harvested links on equal terms, which
     * is right: both are one step of discovery from the seed.
     *
     * <p><b>Skipped when {@code respectRobots} is off.</b> The epic separates discovery
     * from politeness, so seeding regardless looked defensible — a {@code Sitemap:} line
     * is a publishing hint, not a restriction. {@code RobotsCacheTest} decided otherwise:
     * {@code turningOffRespectRobotsIgnoresTheRulesButStillPaces} asserts robots.txt is
     * not fetched at all when its rules are ignored. Mining that file for hints while
     * declaring we ignore it would break a tested contract to save a caller nothing, and
     * an operator who turned robots off wants fewer requests, not an extra one.
     */
    private List<URI> withSitemapSeeds(List<URI> harvested, URI seed, boolean sameHostOnly,
                                       boolean respectRobots, CrawlState state) {
        if (!respectRobots || !seedFromSitemapDefault()) return harvested;
        var seeds = SitemapSeeder.seedsFor(seed, CLIENT, IDENTITY,
                uri -> (!sameHostOnly || sameHost(uri, seed))
                        && !underSuppressedLocale(uri, state)
                        && !state.seen.contains(canonical(uri)));
        if (seeds.isEmpty()) return harvested;

        // Harvested links first: a page the site links to from its entry point is a
        // better guess at what a caller wants than an arbitrary sitemap row, and the
        // page budget cuts from the end.
        var merged = new ArrayList<>(harvested);
        // The predicate above already applied the crawl's rules while the seeder was
        // still reading, so the URL cap counted usable seeds rather than raw rows.
        for (var uri : seeds) {
            if (state.seen.add(canonical(uri))) merged.add(uri);
        }
        return merged;
    }

    /** One page's work, as it runs on the pool. Pacing happens here so the wait for a
     *  host's next slot overlaps other hosts' fetches instead of blocking the crawl. */
    private record Outcome(WebExtraction.FetchResult fetched, String text,
                           ScrapeReason reason, ScrapeRung nextRung, String detail,
                           ScrapeRung servedBy) {
        boolean usable() {
            return reason == ScrapeReason.OK;
        }
    }

    private Outcome fetchOne(URI uri, boolean respectRobots, String language, CrawlState state) {
        try {
            RobotsCache.awaitSlot(uri, respectRobots
                    ? RobotsCache.delayMillis(uri, CLIENT, IDENTITY)
                    : RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return classified(uri, null, ScrapeObservation.failed(uri.toString(), INTERRUPTED));
        }
        Outcome plain;
        try {
            var fetched = WebExtraction.fetch(uri.toString(), CLIENT, headersFor(language));
            var text = WebExtraction.toText(fetched);
            plain = classified(uri, fetched, ScrapeObservation.of(fetched, text));
        } catch (WebExtraction.HostNotAllowedException e) {
            // Our own refusal, not the origin's. The browser rung reaches the network
            // without consulting the allowlist, so escalating this would walk around the
            // guard rather than fall back to it — the same reason web_fetch returns here.
            return classified(uri, null, ScrapeObservation.failed(uri.toString(), reason(e)));
        } catch (Exception e) {
            // One unreachable page must not end the crawl — the caller asked for a
            // site, and a broken link on it is the site's problem, not the run's.
            plain = classified(uri, null, ScrapeObservation.failed(uri.toString(), reason(e)));
        }
        return escalate(uri, plain, state, language);
    }

    /**
     * Climb the ladder for a page rung 1 could not read, if the crawl's escalation
     * budget allows.
     *
     * <p>The budget is claimed before the attempt and never refunded on failure: a rung
     * that failed still spent the seconds, and refunding would let one pathological host
     * consume the whole crawl one retry at a time.
     */
    private Outcome escalate(URI uri, Outcome plain, CrawlState state, String language) {
        if (plain.usable() || !ScrapeLadder.available()) return plain;
        // Ask before claiming: a reason no installed rung addresses would spend the slot
        // without issuing a request, and be counted in the "escalated N pages" line.
        if (!ScrapeLadder.wouldAttempt(plain.reason())) return plain;
        // Checked between levels alone this bounds nothing: rung 2 waits up to 90s and
        // rung 3 up to 120s, so a 60s crawl could block an agent turn for minutes. A
        // climb already in flight keeps running — the rungs have no cancellation seam.
        if (System.nanoTime() > state.deadline) {
            state.noteOutOfTime();
            return plain;
        }
        if (!state.claimEscalation()) return plain;
        // An escalation is another request to a host that just refused us, so it waits
        // its turn like any other. Without this a blocked page fired rung 1, rung 2 and
        // rung 3 back-to-back, which is the pacing guarantee inverted at exactly the
        // moment the origin was least willing.
        try {
            RobotsCache.awaitSlot(uri, RobotsCache.DEFAULT_DELAY_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return plain;
        }
        var best = ScrapeLadder.climb(uri.toString(),
                new ScrapeLadder.Attempt(ScrapeRung.PLAIN, plain.fetched(), plain.text(),
                        plain.reason(), plain.detail()),
                language);
        if (best.servedBy() == ScrapeRung.PLAIN) return plain;
        EventLogger.info(EVENT_CATEGORY, "%s: served by %s after %s at PLAIN"
                .formatted(uri, best.servedBy(), plain.reason()), null);
        return new Outcome(best.fetched(), best.text(), best.reason(),
                BlockClassifier.nextRung(best.reason(), best.servedBy()),
                best.detail(), best.servedBy());
    }

    /** Runs the shared classifier and records the outcome, so a live install produces
     *  the same telemetry the offline harness does. */
    private static Outcome classified(URI uri, WebExtraction.FetchResult fetched,
                                      ScrapeObservation obs) {
        var reason = BlockClassifier.classify(obs);
        var next = BlockClassifier.nextRung(reason);
        if (reason != ScrapeReason.OK) {
            EventLogger.info(EVENT_CATEGORY,
                    "%s: %s (would need %s)".formatted(uri, reason, next),
                    obs.failed() ? obs.error() : "extracted %d chars".formatted(obs.textLength()));
        }
        return new Outcome(fetched, obs.extractedText(), reason, next, obs.error(),
                ScrapeRung.PLAIN);
    }

    /** Runs after a level completes, single-threaded, so {@code seen} needs no
     *  synchronization and the next level's order is deterministic. */
    private static void collectLinks(WebExtraction.FetchResult fetched, URI seed,
                                     boolean sameHostOnly, CrawlState state,
                                     List<URI> next) {
        var seen = state.seen;
        for (var link : WebExtraction.links(fetched)) {
            if (sameHostOnly && !sameHost(link, seed)) {
                continue;
            }
            if (underSuppressedLocale(link, state)) {
                state.localeVariantsDropped++;
                continue;
            }
            // Dedup on the canonical form, so ?a=1#frag and ?a=1 are one page.
            if (seen.add(canonical(link))) {
                next.add(link);
            }
        }
    }

    /** True when this URL sits under a locale prefix the site's markup already declared
     *  as a non-preferred translation root. */
    private static boolean underSuppressedLocale(URI link, CrawlState state) {
        if (state.suppressedLocalePrefixes.isEmpty()) return false;
        var path = link.getPath() == null ? "" : link.getPath();
        for (var prefix : state.suppressedLocalePrefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Same registrable host, or a subdomain of the seed's — {@code docs.x.com} counts
     *  as part of a crawl seeded at {@code x.com}, which is what a caller means by
     *  "this site". */
    private static boolean sameHost(URI candidate, URI seed) {
        var a = host(candidate);
        var b = host(seed);
        return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
    }

    private static String host(URI uri) {
        var h = uri.getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /** Dedup key: scheme and host lowercased, fragment dropped. Path and query are kept
     *  verbatim — a trailing slash or a query parameter can select a different page, and
     *  normalizing those away merges pages that are not the same. */
    private static String canonical(URI uri) {
        var path = uri.getPath() == null ? "" : uri.getPath();
        var query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
        return "%s://%s%s%s".formatted(
                uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT),
                host(uri), path, query);
    }

    private static int configConcurrency() {
        return Math.clamp(
                services.ConfigService.getInt(CFG_CONCURRENCY, DEFAULT_CONCURRENCY),
                1, MAX_CONCURRENCY);
    }

    private static String reason(Exception e) {
        var m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static String render(URI seed, CrawlState state, int maxDepth,
                                 boolean sameHostOnly) {
        var pages = state.pages;
        var refused = state.refused;
        var stoppedBecause = state.stoppedBecause;
        int unvisited = state.unvisited;
        var sb = new StringBuilder();
        sb.append("Scraped %d page%s from %s (depth \u2264 %d, %s)\n"
                .formatted(pages.size(), pages.size() == 1 ? "" : "s", seed,
                        maxDepth, sameHostOnly ? "same host only" : "any host"));
        if (!refused.isEmpty()) {
            // Named, not merely counted: an operator debugging an allowlist needs to
            // know which host was declined and why.
            sb.append("Refused %d link%s:\n".formatted(
                    refused.size(), refused.size() == 1 ? "" : "s"));
            for (var r : refused) {
                sb.append("  - %s \u2014 %s\n".formatted(r.url(), r.why()));
            }
        }
        if (state.localeVariantsDropped > 0) {
            sb.append("Dropped %d locale variant%s of pages already queued.\n".formatted(
                    state.localeVariantsDropped, state.localeVariantsDropped == 1 ? "" : "s"));
        }
        if (state.escalationsUsed > 0 || state.escalationsSuppressed > 0
                || state.escalationsOutOfTime > 0) {
            sb.append("Escalated %d page%s beyond the plain fetch".formatted(
                    state.escalationsUsed, state.escalationsUsed == 1 ? "" : "s"));
            if (state.escalationsSuppressed > 0) {
                // Never a silent truncation: a caller reading thin text needs to know
                // whether the page resisted or whether we simply stopped trying.
                sb.append("; %d more could have been but the escalation budget (%d) was spent"
                        .formatted(state.escalationsSuppressed, maxEscalations()));
            }
            if (state.escalationsOutOfTime > 0) {
                sb.append("; %d more were skipped because the time budget (%ds) was spent"
                        .formatted(state.escalationsOutOfTime, configTimeoutSeconds()));
            }
            sb.append(".\n");
        }
        if (unvisited > 0) {
            // Say what was left behind rather than let the result read as complete.
            sb.append("Stopped: %s \u2014 %d discovered page%s not read.\n"
                    .formatted(stoppedBecause, unvisited, unvisited == 1 ? "" : "s"));
        }
        for (var p : pages) {
            sb.append("\n\n---\n\n## ").append(p.url());
            if (p.servedBy() != ScrapeRung.PLAIN) {
                sb.append(" _(via ").append(p.servedBy()).append(")_");
            }
            sb.append("\n\n").append(p.text());
        }
        if (sb.length() > MAX_TOTAL_CHARS) {
            return sb.substring(0, MAX_TOTAL_CHARS)
                    + "\n\n[Truncated: scraped content exceeds %d characters]"
                            .formatted(MAX_TOTAL_CHARS);
        }
        return sb.toString();
    }

    private static int configMaxPages() {
        return (int) PlayConfig.longOr(CFG_MAX_PAGES, DEFAULT_MAX_PAGES);
    }

    private static int configMaxDepth() {
        return (int) PlayConfig.longOr(CFG_MAX_DEPTH, DEFAULT_MAX_DEPTH);
    }

    /** Runtime config, matching web_scrape.concurrency and .respect-robots. JCLAW-1099
     *  described this as operator-tunable but read it from application.conf, which needs
     *  a restart to change — not tunable in the sense the ticket meant. */
    private static int maxEscalations() {
        return services.ConfigService.getInt(CFG_MAX_ESCALATIONS, DEFAULT_MAX_ESCALATIONS);
    }

    private static int configTimeoutSeconds() {
        return (int) PlayConfig.longOr(CFG_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    private static String languageDefault() {
        var configured = services.ConfigService.get(CFG_LANGUAGE, DEFAULT_LANGUAGE).strip();
        return configured.isEmpty() ? DEFAULT_LANGUAGE : configured;
    }

    private static boolean seedFromSitemapDefault() {
        return !"false".equalsIgnoreCase(
                services.ConfigService.get(CFG_SEED_FROM_SITEMAP, "true").strip());
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
    private static boolean respectRobotsDefault() {
        return !"false".equalsIgnoreCase(
                services.ConfigService.get(CFG_RESPECT_ROBOTS, "true").strip());
    }
}
