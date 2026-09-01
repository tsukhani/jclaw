package tools.scrape;

import crawlercommons.sitemaps.AbstractSiteMap;
import crawlercommons.sitemaps.SiteMap;
import crawlercommons.sitemaps.SiteMapIndex;
import crawlercommons.sitemaps.SiteMapParser;
import okhttp3.OkHttpClient;
import services.ConfigService;
import services.EventLogger;
import utils.RobotsCache;
import utils.WebExtraction;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Seeds a crawl's frontier from the host's sitemaps (JCLAW-1092).
 *
 * <p>This is <em>discovery</em>, not politeness — it changes which pages a crawl returns,
 * including pages nothing on the seed links to. A site whose navigation is
 * JavaScript-built, or whose content sits behind a search form, can be entirely invisible
 * to link harvesting and fully enumerated in its sitemap.
 *
 * <p>Finding the sitemap costs no request: {@code robots.txt} is already fetched and
 * cached for the allow and crawl-delay checks, and the {@code Sitemap:} lines come from
 * that same parse. Reading it does cost requests, so both the number of sitemap documents
 * fetched and the number of URLs taken from them are bounded — a large site's sitemap can
 * carry tens of thousands of URLs against a page budget of 25.
 *
 * <p>Seeded URLs are ordinary frontier entries: they go through the crawl's
 * {@code admit} gate and therefore through {@link utils.SsrfGuard} and the robots check
 * exactly as harvested links do. Nothing here is exempt.
 */
public final class SitemapSeeder {

    private static final String CFG_MAX_URLS = "web_scrape.max-sitemap-urls";
    private static final String CFG_MAX_DOCUMENTS = "web_scrape.max-sitemap-documents";

    /** URLs taken from sitemaps into the frontier. Above the default page budget of 25 so
     *  seeding still has something to offer after the admit gate drops some, but nowhere
     *  near a real sitemap's size. */
    private static final int DEFAULT_MAX_URLS = 50;

    /** Sitemap documents fetched per crawl, counting index expansion. A sitemap index can
     *  point at hundreds of children; without this a "seed the frontier" step could
     *  outspend the crawl it was seeding. */
    private static final int DEFAULT_MAX_DOCUMENTS = 3;

    private SitemapSeeder() {}

    /**
     * Candidate URLs for {@code seed}'s frontier, in sitemap order and bounded.
     *
     * <p>De-duplication and host scoping are deliberately NOT done here. The crawl owns a
     * canonical form, a seen-set and a same-host rule, and re-implementing those here got
     * both wrong: the canonical form drifted, and an exact host match silently dropped
     * every seed on a host that redirects to www (the crawl's own rule accepts subdomains
     * in both directions). This returns candidates; the crawl admits them exactly as it
     * admits a harvested link.
     *
     * <p>Never throws: a malformed, missing or hostile sitemap degrades to "no extra
     * seeds", because seeding is an enhancement to a crawl that already works without it.
     */
    public static List<URI> seedsFor(URI seed, OkHttpClient client,
                                     RobotsCache.Identity identity,
                                     Predicate<URI> acceptable) {
        List<String> sitemaps;
        try {
            sitemaps = RobotsCache.sitemapsFor(seed, client, identity);
        } catch (Exception _) {
            return List.of();
        }
        if (sitemaps.isEmpty()) return List.of();

        var out = new ArrayList<URI>();
        var parser = new SiteMapParser(false);   // no strict URL-in-scope check; we filter ourselves
        int documents = 0;
        int maxUrls = maxUrls();
        int maxDocuments = maxDocuments();

        for (var sitemapUrl : sitemaps) {
            if (documents >= maxDocuments || out.size() >= maxUrls) break;
            documents += collect(parser, sitemapUrl, client, identity,
                    out, maxUrls, maxDocuments - documents, acceptable);
        }
        if (!out.isEmpty()) {
            EventLogger.info("scrape", "%s: seeded %d URL%s from %d sitemap document%s"
                    .formatted(seed.getHost(), out.size(), out.size() == 1 ? "" : "s",
                            documents, documents == 1 ? "" : "s"), null);
        }
        return List.copyOf(out);
    }

    /** Fetch and expand one sitemap, returning how many documents it cost. */
    private static int collect(SiteMapParser parser, String sitemapUrl,
                               OkHttpClient client, RobotsCache.Identity identity,
                               List<URI> out, int maxUrls, int documentsLeft,
                               Predicate<URI> acceptable) {
        if (documentsLeft <= 0) return 0;
        AbstractSiteMap parsed;
        try {
            // Paced like any other fetch too: a sitemap index can name several children,
            // and issuing those back-to-back is the crawl-delay guarantee broken before
            // the crawl has read its first page.
            RobotsCache.awaitSlot(URI.create(sitemapUrl), RobotsCache.DEFAULT_DELAY_MS);
            // Through the guarded client like any other fetch — a sitemap lives on the
            // same untrusted host as the pages and gets no exemption.
            var fetched = WebExtraction.fetch(sitemapUrl, client,
                    Map.of("User-Agent", identity.userAgentHeader(),
                            // Explicit: the shared default asks for markdown first, and a
                            // content-negotiating origin would rank that above the XML.
                            "Accept", "application/xml, text/xml;q=0.9"));
            parsed = parser.parseSiteMap(fetched.body(), URI.create(fetched.finalUrl()).toURL());
        } catch (InterruptedException _) {
            // Restored and propagated rather than folded into the catch below: swallowing
            // it leaves the crawl being torn down still walking sitemap children.
            Thread.currentThread().interrupt();
            return documentsLeft;
        } catch (Exception _) {
            return 1;   // the request was still spent
        }

        if (parsed instanceof SiteMapIndex index) {
            int cost = 1;
            for (var child : index.getSitemaps()) {
                if (cost >= documentsLeft || out.size() >= maxUrls) break;
                cost += collect(parser, child.getUrl().toString(), client, identity,
                        out, maxUrls, documentsLeft - cost, acceptable);
            }
            return cost;
        }
        if (parsed instanceof SiteMap map) {
            for (var entry : map.getSiteMapUrls()) {
                if (out.size() >= maxUrls) break;
                parse(entry.getUrl().toString(), out, acceptable);
            }
        }
        return 1;
    }

    /**
     * Parse, then let the CALLER decide. The predicate carries the crawl's own rules —
     * the seeder still owns none of them — but applying it here means the URL cap counts
     * seeds the crawl can actually use.
     *
     * <p>Filtering afterwards was measurably wrong: docs.openclaw.ai lists 719 Arabic
     * URLs alphabetically ahead of everything else, so a cap of 50 raw entries yielded
     * about six usable ones and a crawl that should have returned 25 pages returned 6.
     */
    private static void parse(String candidate, List<URI> out,
                              Predicate<URI> acceptable) {
        try {
            var uri = URI.create(candidate);
            if (uri.getHost() != null && acceptable.test(uri)) out.add(uri);
        } catch (RuntimeException _) {
            // one malformed <loc> must not lose the rest of the sitemap
        }
    }

    /** Read from the DB-backed runtime config, not application.conf: these are crawl
     *  bounds an operator may want to tune while diagnosing a slow or over-eager crawl,
     *  and the sibling keys web_scrape.concurrency and .respect-robots already live
     *  there. PlayConfig would require an edit and a restart. */
    private static int maxUrls() {
        return ConfigService.getInt(CFG_MAX_URLS, DEFAULT_MAX_URLS);
    }

    private static int maxDocuments() {
        return ConfigService.getInt(CFG_MAX_DOCUMENTS, DEFAULT_MAX_DOCUMENTS);
    }
}
