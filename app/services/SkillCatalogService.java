package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;
import play.Play;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lazy, in-process search index over the external <em>importable-skills</em>
 * catalog — the GitHub-scraped snapshot published by skills.sh / mastra-ai.
 * Backs the Skills page's "browse &amp; import" search so an operator can find
 * a community skill by name and pull it into JClaw.
 *
 * <h2>First-class Java, no sidecar</h2>
 * The upstream {@code skills-api} is a Node service that scrapes GitHub and
 * serves a pre-built index. We deliberately do NOT run it. Its only
 * irreplaceable output is the catalog data, which it also checks into its repo
 * as a single ~10&nbsp;MB JSON snapshot. JClaw fetches that snapshot directly
 * and indexes it into its own Lucene 10 backend — the same engine that powers
 * message/task/memory search — under a dedicated
 * {@link LuceneIndexer.Scope#SKILLS_CATALOG} scope.
 *
 * <h2>Lazy on first search (not bundled, not on startup)</h2>
 * Nothing is shipped in the dist/bundle and no {@code @Every} job runs at boot.
 * The snapshot is downloaded the first time a search hits {@link #search} (then
 * cached on disk so restarts re-index from the cache without re-downloading).
 * The first search therefore pays the download + index cost (~a few seconds for
 * 34k rows); subsequent searches are pure Lucene lookups.
 *
 * <h2>Position-keyed ids</h2>
 * Catalog rows have string ids ({@code source/skillId}); Lucene keys on a
 * {@code long}. We key each doc by its <em>position</em> in the loaded snapshot
 * list, so a Lucene hit's id is a direct index back into {@link #catalog} for
 * O(1) metadata hydration. Each (re)load {@link LuceneIndexer#clear}s the scope
 * before re-upserting, keeping the index and the in-memory list congruent.
 */
public final class SkillCatalogService {

    /** Operator override for the upstream snapshot URL. */
    private static final String CATALOG_URL_PROPERTY = "jclaw.skills.catalog.url";
    /** Default snapshot: the catalog the mastra-ai {@code skills-api} checks
     *  into its repo (the same data the Node service serves). */
    private static final String DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/mastra-ai/skills-api/main/src/registry/scraped-skills.json";

    /** On-disk cache so a restart re-indexes without re-downloading. Relative
     *  to the Play app root, alongside the Lucene index under {@code data/}. */
    private static final String CACHE_DIR = "data/skills-catalog";
    private static final String CACHE_FILE = "scraped-skills.json";

    /** Hard cap on page size, independent of the requested pageSize. */
    private static final int MAX_PAGE_SIZE = 50;
    /** Relevance window for a text query: facet counts + pagination are computed
     *  over (at most) this many top-ranked Lucene hits. A blank "browse" query
     *  paginates the full catalog instead, so this only bounds text searches. */
    private static final int LUCENE_WINDOW = 2000;
    /** Facet name + selection token for "no category filter". */
    public static final String ALL = "All";
    /** Generous deadline for the one-off ~10 MB snapshot download. */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 120;

    private static final String CATEGORY = "skills";

    /** One importable catalog entry. Mirrors the upstream row shape, trimmed to
     *  the fields the Skills UI + import path need, plus a derived topical
     *  {@link #category} (see {@link SkillCategoryClassifier}). */
    public record CatalogSkill(String skillId, String displayName, String source,
                               String owner, String repo, String githubUrl, long installs,
                               String category) {}

    /** One facet row: a category, its icon, and how many skills in the current
     *  (query-applied, category-unfiltered) result set fall in it. */
    public record CategoryFacet(String category, String icon, int count) {}

    /** Search/browse response. {@code ready=false} means the snapshot couldn't
     *  be loaded (download/parse failure) — the UI shows an error rather than
     *  an empty result that looks like "no matches". {@code facets} are computed
     *  over the query result BEFORE the category filter, so every category's
     *  count is visible; {@code total} is the filtered count that drives paging. */
    public record CatalogSearchResult(boolean ready, int catalogSize, String scrapedAt,
                                      List<CatalogSkill> results, int total, int page, int pageSize,
                                      List<CategoryFacet> facets) {}

    // Published once, atomically, at the end of a successful load. null until
    // the first load completes; read on the search fast-path without locking.
    private static volatile List<CatalogSkill> catalog;
    private static volatile String scrapedAt;
    // Serializes concurrent first-searches so the download + index runs once.
    private static final Object LOAD_LOCK = new Object();

    private SkillCatalogService() {}

    /**
     * Search/browse the catalog with topical facets and pagination, loading it
     * lazily on first call. A blank query browses by install count; a non-blank
     * query rides Lucene relevance. {@code category} (null/blank/{@link #ALL}
     * means no filter) narrows the result list, while facet counts are always
     * computed over the unfiltered query result so the user can see and switch
     * between categories. Never throws — a load/search failure degrades to
     * {@code ready=false} / empty results.
     */
    public static CatalogSearchResult search(String query, String category, int page, int pageSize) {
        ensureLoaded();
        var snapshot = catalog;
        int ps = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        int pg = Math.max(0, page);
        if (snapshot == null) {
            return new CatalogSearchResult(false, 0, null, List.of(), 0, pg, ps, List.of());
        }

        // Base set = query applied, category NOT applied (so facet counts span
        // every category). Blank query browses the whole catalog by installs.
        List<CatalogSkill> base = (query == null || query.isBlank())
                ? browseByInstalls(snapshot)
                : searchLucene(query, snapshot);

        var facets = computeFacets(base);

        var filtered = (category == null || category.isBlank() || ALL.equalsIgnoreCase(category))
                ? base
                : base.stream().filter(s -> category.equals(s.category())).toList();

        int total = filtered.size();
        int from = Math.min(pg * ps, total);
        int to = Math.min(from + ps, total);
        var pageRows = List.copyOf(filtered.subList(from, to));

        return new CatalogSearchResult(true, snapshot.size(), scrapedAt, pageRows, total, pg, ps, facets);
    }

    /** Browse order for a blank query: most-installed first, whole catalog. */
    private static List<CatalogSkill> browseByInstalls(List<CatalogSkill> snapshot) {
        return snapshot.stream()
                .sorted(Comparator.comparingLong(CatalogSkill::installs).reversed())
                .toList();
    }

    /** Lucene relevance hits (capped at {@link #LUCENE_WINDOW}), hydrated to rows. */
    private static List<CatalogSkill> searchLucene(String query, List<CatalogSkill> snapshot) {
        try {
            var ids = MessageSearch.searchIds(LuceneIndexer.Scope.SKILLS_CATALOG, query, LUCENE_WINDOW);
            var out = new ArrayList<CatalogSkill>(ids.size());
            for (var id : ids) {
                int i = id.intValue();
                if (i >= 0 && i < snapshot.size()) out.add(snapshot.get(i));
            }
            return out;
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Skill catalog search failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Facet counts over the base set: "All" first, then categories with count &gt;
     * 0 sorted by count descending, with {@link SkillCategoryClassifier#OTHER}
     * forced last (it's a residual, not a topic).
     */
    private static List<CategoryFacet> computeFacets(List<CatalogSkill> base) {
        var counts = new HashMap<String, Integer>();
        for (var s : base) counts.merge(s.category(), 1, Integer::sum);

        var facets = new ArrayList<CategoryFacet>();
        facets.add(new CategoryFacet(ALL, "", base.size()));
        counts.entrySet().stream()
                .filter(e -> e.getValue() > 0 && !SkillCategoryClassifier.OTHER.equals(e.getKey()))
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .forEach(e -> facets.add(new CategoryFacet(
                        e.getKey(), SkillCategoryClassifier.iconFor(e.getKey()), e.getValue())));
        var other = counts.getOrDefault(SkillCategoryClassifier.OTHER, 0);
        if (other > 0) {
            facets.add(new CategoryFacet(SkillCategoryClassifier.OTHER,
                    SkillCategoryClassifier.iconFor(SkillCategoryClassifier.OTHER), other));
        }
        return facets;
    }

    private static void ensureLoaded() {
        if (catalog != null) return;
        synchronized (LOAD_LOCK) {
            if (catalog != null) return;
            try {
                var parsed = parse(loadJson());
                index(parsed);
                scrapedAt = parsed.scrapedAt();
                catalog = parsed.skills();   // publish last
                EventLogger.info(CATEGORY,
                        "Skill catalog loaded: %d skills (scrapedAt=%s)"
                                .formatted(parsed.skills().size(), parsed.scrapedAt()));
            } catch (IOException | RuntimeException e) {
                // Leave catalog null so the next search retries the load.
                EventLogger.error(CATEGORY, "Skill catalog load failed: " + e.getMessage());
            }
        }
    }

    /** Read the cached snapshot if present, else download and cache it. */
    private static String loadJson() throws IOException {
        var cache = cacheFile();
        if (Files.isReadable(cache)) {
            return Files.readString(cache);
        }
        var body = download(catalogUrl());
        try {
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, body);
        } catch (IOException e) {
            // Cache write is best-effort; the in-memory load still succeeds.
            EventLogger.warn(CATEGORY, "Skill catalog cache write failed: " + e.getMessage());
        }
        return body;
    }

    private static String download(String url) throws IOException {
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var body = resp.body();
            if (!resp.isSuccessful() || body == null) {
                throw new IOException("catalog download HTTP " + resp.code() + " from " + url);
            }
            return body.string();
        }
    }

    private record Parsed(List<CatalogSkill> skills, String scrapedAt) {}

    private static Parsed parse(String raw) {
        var root = JsonParser.parseString(raw).getAsJsonObject();
        var when = str(root, "scrapedAt");
        var arr = root.getAsJsonArray("skills");
        var list = new ArrayList<CatalogSkill>(arr != null ? arr.size() : 0);
        if (arr != null) {
            for (var el : arr) {
                var o = el.getAsJsonObject();
                var skillId = str(o, "skillId");
                var displayName = str(o, "displayName");
                var repo = str(o, "repo");
                list.add(new CatalogSkill(
                        skillId, displayName, str(o, "source"),
                        str(o, "owner"), repo, str(o, "githubUrl"),
                        asLong(o, "installs"),
                        SkillCategoryClassifier.classify(skillId, displayName, repo)));
            }
        }
        return new Parsed(List.copyOf(list), when);
    }

    /** Wipe and rebuild the Lucene scope from the parsed snapshot, keyed by
     *  list position. No-op (search degrades to install-ranked browse) if the
     *  shared index isn't open. */
    private static void index(Parsed parsed) {
        if (!LuceneIndexer.isOpen()) return;
        LuceneIndexer.clear(LuceneIndexer.Scope.SKILLS_CATALOG);
        var list = parsed.skills();
        for (int i = 0; i < list.size(); i++) {
            LuceneIndexer.upsert(LuceneIndexer.Scope.SKILLS_CATALOG, i, content(list.get(i)));
        }
        LuceneIndexer.commit(LuceneIndexer.Scope.SKILLS_CATALOG);
    }

    /** Indexed free-text for one row. No upstream description exists, so match
     *  on the name/owner/repo signals the snapshot does carry. */
    private static String content(CatalogSkill s) {
        return String.join(" ",
                nz(s.displayName()), nz(s.skillId()), nz(s.owner()), nz(s.repo()), nz(s.source()));
    }

    private static Path cacheFile() {
        return Play.applicationPath.toPath().resolve(CACHE_DIR).resolve(CACHE_FILE);
    }

    private static String catalogUrl() {
        var configured = Play.configuration.getProperty(CATALOG_URL_PROPERTY);
        return (configured != null && !configured.isBlank()) ? configured.trim() : DEFAULT_CATALOG_URL;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static long asLong(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    // --- test seams (mirror LuceneIndexer's *ForTest convention) ---

    /**
     * Test-only: load the catalog directly from a JSON string, bypassing the
     * network fetch and disk cache, so tests can exercise parse + index + search
     * deterministically and offline. Requires the shared Lucene index to be
     * open (via {@code LuceneTestSync.openForTest()} + {@code MessageSearch.init()}).
     */
    public static void loadForTest(String json) {
        synchronized (LOAD_LOCK) {
            var parsed = parse(json);
            index(parsed);
            scrapedAt = parsed.scrapedAt();
            catalog = parsed.skills();
        }
    }

    /** Test-only: drop the in-memory snapshot so the JVM-global static doesn't
     *  leak into a later test. */
    public static void resetForTest() {
        synchronized (LOAD_LOCK) {
            catalog = null;
            scrapedAt = null;
        }
    }
}
