package services.catalog;

import okhttp3.Request;
import play.Play;
import services.EventLogger;
import services.SkillCategoryClassifier;
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
 * A {@link CatalogType#STATIC} catalog: a snapshot downloaded from an
 * {@code updateUrl}, parsed by a {@link DumpParser}, and indexed locally into a
 * Lucene scope. Browse/search run against the LOCAL index — offline-capable,
 * with exact topical facets and jump-to-page pagination. {@link #refresh()}
 * re-downloads the dump to update its values.
 *
 * <h2>Lazy on first query (not bundled, not on startup)</h2>
 * Nothing ships in the dist/bundle and no startup job runs. The snapshot is
 * downloaded the first time {@link #query} is called (then disk-cached so a
 * restart re-indexes from cache without re-downloading).
 *
 * <h2>Position-keyed ids</h2>
 * Each row is indexed by its position in the loaded list, so a Lucene hit's id is
 * a direct index back into the in-memory snapshot. Each (re)load clears the scope
 * first, keeping the index and the list congruent.
 *
 * <p>Generic over the dump format via its {@link DumpParser}, so a second static
 * dump is a new registry entry (its own url + parser + Lucene scope).
 */
public final class StaticDumpCatalog implements Catalog {

    /** Category facet name + selection token for "no category filter". */
    public static final String ALL = "All";

    private static final int MAX_PAGE_SIZE = 50;
    /** Relevance window for a text query: facets + pagination span at most this
     *  many top-ranked hits (a blank browse paginates the full snapshot). */
    private static final int LUCENE_WINDOW = 2000;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 120;
    private static final String CATEGORY = "skills";

    private final String id;
    private final String displayName;
    private final String urlProperty;
    private final String defaultUrl;
    private final Path cacheFile;
    private final LuceneIndexer.Scope scope;
    private final DumpParser parser;

    // Published once, atomically, at the end of a successful load. null until
    // the first load completes; read on the query fast-path without locking.
    private volatile List<CatalogSkill> catalog;
    private volatile String scrapedAt;
    private final Object loadLock = new Object();

    /**
     * @param id          stable id (also stamped as each row's {@code provider})
     * @param displayName UI name for the catalog selector
     * @param urlProperty Play config key overriding the dump URL
     * @param defaultUrl  default dump URL when the property is unset
     * @param cacheName   cache filename under {@code data/skills-catalog/}
     * @param scope       Lucene scope this catalog owns
     * @param parser      format-specific dump parser
     */
    public StaticDumpCatalog(String id, String displayName, String urlProperty, String defaultUrl,
                             String cacheName, LuceneIndexer.Scope scope, DumpParser parser) {
        this.id = id;
        this.displayName = displayName;
        this.urlProperty = urlProperty;
        this.defaultUrl = defaultUrl;
        this.cacheFile = Play.applicationPath.toPath().resolve("data/skills-catalog").resolve(cacheName);
        this.scope = scope;
        this.parser = parser;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public CatalogType type() {
        return CatalogType.STATIC;
    }

    @Override
    public CatalogPage query(CatalogQuery q) {
        ensureLoaded();
        var snapshot = catalog;
        int ps = Math.clamp(q.pageSize(), 1, MAX_PAGE_SIZE);
        int pg = Math.max(0, q.page());
        if (snapshot == null) {
            return CatalogPage.notReady(pg, ps);
        }

        // Base set = query applied, category NOT applied (so facet counts span
        // every category). Blank query browses the whole snapshot by installs.
        var query = q.query();
        List<CatalogSkill> base = (query == null || query.isBlank())
                ? browseByInstalls(snapshot)
                : searchLucene(query, snapshot);

        var facets = computeFacets(base);

        var category = q.category();
        var filtered = (category == null || category.isBlank() || ALL.equalsIgnoreCase(category))
                ? base
                : base.stream().filter(s -> category.equals(s.category())).toList();

        int total = filtered.size();
        int from = Math.min(pg * ps, total);
        int to = Math.min(from + ps, total);
        var pageRows = List.copyOf(filtered.subList(from, to));

        return new CatalogPage(true, pageRows, total, pg, ps, null, facets, snapshot.size(), scrapedAt);
    }

    /** Re-download the dump on the next query (drops the disk cache + in-memory
     *  snapshot), so a stale static catalog can be updated from its url. */
    public void refresh() {
        synchronized (loadLock) {
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Catalog '%s' cache delete failed: %s".formatted(id, e.getMessage()));
            }
            catalog = null;
            scrapedAt = null;
        }
    }

    private List<CatalogSkill> browseByInstalls(List<CatalogSkill> snapshot) {
        return snapshot.stream()
                .sorted(Comparator.comparingLong(CatalogSkill::installs).reversed())
                .toList();
    }

    private List<CatalogSkill> searchLucene(String query, List<CatalogSkill> snapshot) {
        try {
            var ids = MessageSearch.searchIds(scope, query, LUCENE_WINDOW);
            var out = new ArrayList<CatalogSkill>(ids.size());
            for (var id : ids) {
                int i = id.intValue();
                if (i >= 0 && i < snapshot.size()) out.add(snapshot.get(i));
            }
            return out;
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Catalog '%s' search failed: %s".formatted(this.id, e.getMessage()));
            return List.of();
        }
    }

    private List<CategoryFacet> computeFacets(List<CatalogSkill> base) {
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

    private void ensureLoaded() {
        if (catalog != null) return;
        synchronized (loadLock) {
            if (catalog != null) return;
            try {
                var parsed = parser.parse(loadJson(), id);
                index(parsed.skills());
                scrapedAt = parsed.scrapedAt();
                catalog = parsed.skills();   // publish last
                EventLogger.info(CATEGORY, "Catalog '%s' loaded: %d skills (scrapedAt=%s)"
                        .formatted(id, parsed.skills().size(), parsed.scrapedAt()));
            } catch (IOException | RuntimeException e) {
                EventLogger.error(CATEGORY, "Catalog '%s' load failed: %s".formatted(id, e.getMessage()));
            }
        }
    }

    private String loadJson() throws IOException {
        if (Files.isReadable(cacheFile)) {
            return Files.readString(cacheFile);
        }
        var body = download(resolveUrl());
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, body);
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Catalog '%s' cache write failed: %s".formatted(id, e.getMessage()));
        }
        return body;
    }

    private String download(String url) throws IOException {
        var call = HttpFactories.general().newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var body = resp.body();
            if (!resp.isSuccessful() || body == null) {
                throw new IOException("dump download HTTP " + resp.code() + " from " + url);
            }
            return body.string();
        }
    }

    private void index(List<CatalogSkill> skills) {
        if (!LuceneIndexer.isOpen()) return;
        LuceneIndexer.clear(scope);
        for (int i = 0; i < skills.size(); i++) {
            LuceneIndexer.upsert(scope, i, content(skills.get(i)));
        }
        LuceneIndexer.commit(scope);
    }

    private static String content(CatalogSkill s) {
        return String.join(" ",
                nz(s.displayName()), nz(s.skillId()), nz(s.owner()), nz(s.repo()), nz(s.source()));
    }

    private String resolveUrl() {
        var configured = Play.configuration.getProperty(urlProperty);
        return (configured != null && !configured.isBlank()) ? configured.trim() : defaultUrl;
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    // --- test seams (mirror LuceneIndexer's *ForTest convention) ---

    /** Test-only: load directly from a dump JSON string, bypassing network/cache,
     *  so tests exercise parse + index + query deterministically and offline.
     *  Requires the Lucene index open (LuceneTestSync.openForTest + MessageSearch.init). */
    public void loadForTest(String json) {
        synchronized (loadLock) {
            var parsed = parser.parse(json, id);
            index(parsed.skills());
            scrapedAt = parsed.scrapedAt();
            catalog = parsed.skills();
        }
    }

    /** Test-only: drop the in-memory snapshot so it doesn't leak into a later test. */
    public void resetForTest() {
        synchronized (loadLock) {
            catalog = null;
            scrapedAt = null;
        }
    }
}
