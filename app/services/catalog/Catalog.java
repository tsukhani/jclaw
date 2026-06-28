package services.catalog;

/**
 * A browsable/searchable source of importable skills. Implementations differ by
 * {@link CatalogType}: a {@link StaticDumpCatalog} downloads + locally indexes a
 * dump, a {@link ClawhubCatalog} proxies a live registry API. The
 * {@link services.catalog.CatalogRegistry} holds the configured set; adding a
 * source is registering one more {@code Catalog}.
 */
public interface Catalog {

    /** Stable identifier (also the {@code provider} stamped on each result). */
    String id();

    /** Human-facing name for the catalog selector (e.g. "Mastra (GitHub)"). */
    String displayName();

    /** Retrieval model — drives which UI/nav the frontend renders. */
    CatalogType type();

    /** Browse/search this catalog. Never throws — a failure returns
     *  {@link CatalogPage#notReady}. */
    CatalogPage query(CatalogQuery q);
}
