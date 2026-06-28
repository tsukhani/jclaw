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

    /**
     * Refresh the catalog's data from its source. For a {@link StaticDumpCatalog}
     * this re-downloads the snapshot from its update URL on the next query;
     * returns {@code true} when a refresh applies. A {@link CatalogType#DYNAMIC}
     * catalog is always live (nothing is snapshotted), so the default is a no-op
     * returning {@code false} (not applicable).
     */
    default boolean refresh() {
        return false;
    }
}
