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
import java.util.List;
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

    /** Hard cap on results per query, independent of the requested limit. */
    private static final int MAX_RESULTS = 100;
    /** Generous deadline for the one-off ~10 MB snapshot download. */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 120;

    private static final String CATEGORY = "skills";

    /** One importable catalog entry. Mirrors the upstream row shape, trimmed
     *  to the fields the Skills UI and the import path need. */
    public record CatalogSkill(String skillId, String displayName, String source,
                               String owner, String repo, String githubUrl, long installs) {}

    /** Search/browse response. {@code ready=false} means the snapshot couldn't
     *  be loaded (download/parse failure) — the UI shows an error rather than
     *  an empty result that looks like "no matches". */
    public record CatalogSearchResult(boolean ready, int catalogSize, String scrapedAt,
                                      List<CatalogSkill> results) {}

    // Published once, atomically, at the end of a successful load. null until
    // the first load completes; read on the search fast-path without locking.
    private static volatile List<CatalogSkill> catalog;
    private static volatile String scrapedAt;
    // Serializes concurrent first-searches so the download + index runs once.
    private static final Object LOAD_LOCK = new Object();

    private SkillCatalogService() {}

    /**
     * Search the catalog, loading it lazily on first call. A blank query
     * "browses" the most-installed skills. Never throws — a load or search
     * failure degrades to {@code ready=false} / empty results.
     */
    public static CatalogSearchResult search(String query, int limit) {
        ensureLoaded();
        var snapshot = catalog;
        if (snapshot == null) {
            return new CatalogSearchResult(false, 0, null, List.of());
        }
        int cap = Math.clamp(limit, 1, MAX_RESULTS);
        List<CatalogSkill> hits = (query == null || query.isBlank())
                ? topByInstalls(snapshot, cap)
                : searchLucene(query, cap, snapshot);
        return new CatalogSearchResult(true, snapshot.size(), scrapedAt, hits);
    }

    /** Browse default for an empty query: the most-installed skills first. */
    private static List<CatalogSkill> topByInstalls(List<CatalogSkill> snapshot, int cap) {
        return snapshot.stream()
                .sorted(Comparator.comparingLong(CatalogSkill::installs).reversed())
                .limit(cap)
                .toList();
    }

    /** Lucene relevance search, hydrating hit ids back to catalog rows. */
    private static List<CatalogSkill> searchLucene(String query, int cap, List<CatalogSkill> snapshot) {
        try {
            var ids = MessageSearch.searchIds(LuceneIndexer.Scope.SKILLS_CATALOG, query, cap);
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
                list.add(new CatalogSkill(
                        str(o, "skillId"), str(o, "displayName"), str(o, "source"),
                        str(o, "owner"), str(o, "repo"), str(o, "githubUrl"),
                        asLong(o, "installs")));
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
