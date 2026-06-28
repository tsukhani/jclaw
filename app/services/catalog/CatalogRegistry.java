package services.catalog;

import services.search.LuceneIndexer;

import java.util.List;

/**
 * The configured set of skill {@link Catalog}s the Browse Catalog UI offers.
 * Each catalog is a self-contained "directory" (its own retrieval, cache, and
 * import mechanism); adding a source — another static dump or another dynamic
 * registry — is adding one entry to {@link #CATALOGS}.
 */
public final class CatalogRegistry {

    /** Static dump: the skills.sh / mastra-ai GitHub-scraped snapshot. */
    public static final StaticDumpCatalog MASTRA = new StaticDumpCatalog(
            "mastra", "Mastra (GitHub)",
            "jclaw.skills.catalog.url",
            "https://raw.githubusercontent.com/mastra-ai/skills-api/main/src/registry/scraped-skills.json",
            "scraped-skills.json",
            LuceneIndexer.Scope.SKILLS_CATALOG,
            new MastraDumpParser());

    /** Dynamic registry: the live ClawHub (OpenClaw) API. */
    public static final ClawhubCatalog CLAWHUB = new ClawhubCatalog();

    /** Ordered list shown in the catalog selector; the first is the default. */
    private static final List<Catalog> CATALOGS = List.of(MASTRA, CLAWHUB);

    private CatalogRegistry() {}

    public static List<Catalog> all() {
        return CATALOGS;
    }

    /** Resolve a catalog by id, defaulting to the first (static) catalog when the
     *  id is null/unknown. */
    public static Catalog byId(String id) {
        if (id != null && !id.isBlank()) {
            for (var c : CATALOGS) {
                if (c.id().equals(id)) return c;
            }
        }
        return CATALOGS.getFirst();
    }
}
