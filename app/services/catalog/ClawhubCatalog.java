package services.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.Request;
import play.Play;
import services.EventLogger;
import services.SkillCategoryClassifier;
import utils.HttpFactories;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link CatalogType#DYNAMIC} catalog backed by the live ClawHub registry
 * (clawhub.ai) — the OpenClaw public skill registry. Nothing is snapshotted:
 * every browse/search proxies clawhub's HTTP API per-request, so results are
 * always fresh (new skills, install counts, moderation verdicts).
 *
 * <h2>Retrieval model</h2>
 * <ul>
 *   <li>Blank query → browse {@code GET /api/v1/skills?sort=downloads} with
 *       opaque cursor pagination (Next/Prev).</li>
 *   <li>Non-blank query → {@code GET /api/v1/search?q=…}, a single relevance-ranked
 *       page (clawhub's search isn't cursor-paginated).</li>
 * </ul>
 * Each result is classified on the fly through the shared
 * {@link SkillCategoryClassifier} (using clawhub's richer description + topics),
 * so a row carries the same topical category as a static-catalog row.
 *
 * <h2>Why a dynamic catalog returns no facets</h2>
 * {@link #query} always returns an empty {@code facets} list — and this is forced
 * by the source, not a shortcut. Topical facet COUNTS require knowing the whole
 * catalog (count every skill's category); a dynamic catalog deliberately never
 * holds the whole catalog — it proxies a live registry one page at a time.
 *
 * <p>clawhub's public API offers no way to recover those counts. Verified against
 * the live API + the {@code httpApiV1} route source (2026-06): there is no
 * registry-stats endpoint (no totals), no topics/tags/categories listing with
 * per-bucket counts, and browse/search responses carry only result items + a
 * cursor — no aggregations. Per-skill {@code topics} arrays exist, but the
 * {@code topic=}/{@code tag=} filter params have no effect on the list endpoint,
 * so we can't even narrow by topic server-side. The "Topics" taxonomy on the
 * clawhub website is computed by internal Convex queries the site calls directly,
 * never exposed over {@code /api/v1}.
 *
 * <p>So facet counts here would mean paginating the ENTIRE live registry and
 * counting client-side on every browse — which is exactly the "snapshot a live
 * source" move the {@link CatalogType#DYNAMIC} model rejects (a large,
 * rate-limited crawl whose counts are stale the instant they're computed). The
 * UI therefore renders dynamic catalogs with search + cursor Next/Prev and no
 * facet sidebar; the per-row category label (from {@code classifyText}) is the
 * topical signal we CAN surface without holding the whole set.
 */
public final class ClawhubCatalog implements Catalog {

    private static final String BASE_URL_PROPERTY = "jclaw.skills.clawhub.url";
    private static final String DEFAULT_BASE_URL = "https://clawhub.ai";
    private static final int MAX_PAGE_SIZE = 50;
    private static final int TIMEOUT_SECONDS = 25;
    private static final String CATEGORY = "skills";
    private static final String PROVIDER = "clawhub";

    @Override
    public String id() {
        return PROVIDER;
    }

    @Override
    public String displayName() {
        return "ClawHub";
    }

    @Override
    public CatalogType type() {
        return CatalogType.DYNAMIC;
    }

    @Override
    public CatalogPage query(CatalogQuery q) {
        int ps = Math.clamp(q.pageSize(), 1, MAX_PAGE_SIZE);
        var query = q.query();
        try {
            var page = (query == null || query.isBlank()) ? browse(q.cursor(), ps) : search(query, ps);
            return q.sortByName() ? resortByName(page) : page;
        } catch (IOException | RuntimeException e) {
            EventLogger.warn(CATEGORY, "ClawHub query failed: " + e.getMessage());
            return CatalogPage.notReady(0, ps);
        }
    }

    /** Name-sort the CURRENT page only — a dynamic catalog never holds the whole
     *  set, so installs stays the registry's native order and name re-sorts the
     *  page in place. */
    private static CatalogPage resortByName(CatalogPage page) {
        var rows = new ArrayList<>(page.results());
        rows.sort(Comparator.comparing((CatalogSkill s) -> nz(s.displayName()).toLowerCase())
                .thenComparing(s -> nz(s.skillId())));
        return new CatalogPage(page.ready(), List.copyOf(rows), page.total(), page.page(),
                page.pageSize(), page.nextCursor(), page.facets(), page.catalogSize(), page.scrapedAt());
    }

    /** Browse the live registry by downloads, cursor-paginated. */
    private CatalogPage browse(String cursor, int limit) throws IOException {
        var url = base().newBuilder()
                .addPathSegments("api/v1/skills")
                .addQueryParameter("sort", "downloads")
                .addQueryParameter("nonSuspiciousOnly", "true")
                .addQueryParameter("limit", String.valueOf(limit));
        if (cursor != null && !cursor.isBlank()) url.addQueryParameter("cursor", cursor);

        var root = getJson(url.build());
        var items = root.getAsJsonArray("items");
        var rows = new ArrayList<CatalogSkill>(items != null ? items.size() : 0);
        if (items != null) {
            for (var el : items) rows.add(mapBrowseItem(el.getAsJsonObject(), baseUrl()));
        }
        var nextCursor = str(root, "nextCursor");
        return new CatalogPage(true, List.copyOf(rows), -1, 0, limit, nextCursor, List.of(), -1, null);
    }

    /** Relevance search — a single page, no cursor (clawhub search isn't paged). */
    private CatalogPage search(String query, int limit) throws IOException {
        var url = base().newBuilder()
                .addPathSegments("api/v1/search")
                .addQueryParameter("q", query)
                .addQueryParameter("nonSuspiciousOnly", "true")
                .addQueryParameter("limit", String.valueOf(limit))
                .build();

        var root = getJson(url);
        var results = root.getAsJsonArray("results");
        var rows = new ArrayList<CatalogSkill>(results != null ? results.size() : 0);
        if (results != null) {
            for (var el : results) rows.add(mapSearchResult(el.getAsJsonObject(), baseUrl()));
        }
        return new CatalogPage(true, List.copyOf(rows), -1, 0, limit, null, List.of(), -1, null);
    }

    // --- mapping (public for tests) ---

    /** Map a {@code /api/v1/skills} browse item to a normalized row. */
    public static CatalogSkill mapBrowseItem(JsonObject o, String base) {
        var slug = str(o, "slug");
        var displayName = firstNonBlank(str(o, "displayName"), slug);
        var stats = o.has("stats") && o.get("stats").isJsonObject() ? o.getAsJsonObject("stats") : null;
        long installs = stats != null ? asLong(stats, "installsAllTime", asLong(stats, "downloads", 0)) : 0;
        var category = SkillCategoryClassifier.classifyText(
                join(slug, displayName, str(o, "summary"), str(o, "description"), topics(o)));
        // Browse listings omit the owner, and clawhub slugs are owner-scoped (not
        // globally unique), so we can't resolve the exact /{owner}/skills/{slug}
        // page. Link to the slug-filtered search, which lands on the right skill.
        return new CatalogSkill(slug, displayName, "clawhub.ai/" + nz(slug),
                "", "", searchLink(base, slug), installs, category, PROVIDER);
    }

    /** Map a {@code /api/v1/search} result to a normalized row. */
    public static CatalogSkill mapSearchResult(JsonObject o, String base) {
        var slug = str(o, "slug");
        var displayName = firstNonBlank(str(o, "displayName"), slug);
        var owner = nz(str(o, "ownerHandle"));
        long installs = asLong(o, "downloads", 0);
        var category = SkillCategoryClassifier.classifyText(join(slug, displayName, str(o, "summary")));
        return new CatalogSkill(slug, displayName, sourceLabel(owner, slug),
                owner, "", skillPageUrl(base, owner, slug), installs, category, PROVIDER);
    }

    /**
     * The canonical clawhub web page for a skill. Slugs are owner-scoped, so the
     * page lives at {@code /{owner}/skills/{slug}} — the form clawhub declares as
     * its {@code <link rel=canonical>} and returns in disambiguation
     * {@code matches[].url}. When the owner is unknown (browse listings omit it),
     * fall back to the slug-filtered search, which still lands on the right skill.
     */
    private static String skillPageUrl(String base, String owner, String slug) {
        return owner.isBlank() ? searchLink(base, slug) : base + "/" + owner + "/skills/" + nz(slug);
    }

    private static String searchLink(String base, String slug) {
        return base + "/skills?q=" + URLEncoder.encode(nz(slug), StandardCharsets.UTF_8);
    }

    /** Display subtitle: the owner-qualified path when known, else just the slug. */
    private static String sourceLabel(String owner, String slug) {
        return owner.isBlank() ? "clawhub.ai/" + nz(slug) : "clawhub.ai/" + owner + "/" + nz(slug);
    }

    private static String topics(JsonObject o) {
        if (!o.has("topics") || !o.get("topics").isJsonArray()) return "";
        var sb = new StringBuilder();
        JsonArray arr = o.getAsJsonArray("topics");
        for (var el : arr) {
            if (!el.isJsonNull()) sb.append(el.getAsString()).append(' ');
        }
        return sb.toString();
    }

    private JsonObject getJson(HttpUrl url) throws IOException {
        var call = HttpFactories.general().newCall(
                new Request.Builder().url(url).header("Accept", "application/json").get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var body = resp.body();
            if (!resp.isSuccessful() || body == null) {
                throw new IOException("clawhub HTTP " + resp.code() + " for " + url.encodedPath());
            }
            return JsonParser.parseString(body.string()).getAsJsonObject();
        }
    }

    private HttpUrl base() {
        var url = HttpUrl.parse(baseUrl());
        if (url == null) throw new IllegalStateException("invalid clawhub base url: " + baseUrl());
        return url;
    }

    private static String baseUrl() {
        var configured = Play.configuration.getProperty(BASE_URL_PROPERTY);
        return (configured != null && !configured.isBlank()) ? configured.trim() : DEFAULT_BASE_URL;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static long asLong(JsonObject o, String key, long dflt) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : dflt;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : nz(b);
    }

    private static String join(String... parts) {
        var sb = new StringBuilder();
        for (var p : parts) if (p != null) sb.append(p).append(' ');
        return sb.toString();
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
