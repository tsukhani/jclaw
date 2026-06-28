package services.catalog;

/**
 * How a skill catalog is retrieved — the distinction that decides where queries
 * run and what UI a catalog can offer.
 *
 * <ul>
 *   <li>{@link #STATIC} — a snapshot we own: download a dump from an update URL,
 *       index it locally (Lucene), search offline, refresh by re-downloading.
 *       Exact topical facets + jump-to-page pagination.</li>
 *   <li>{@link #DYNAMIC} — a live registry we proxy: the source owns the data,
 *       index, ranking, and freshness; we query it per-request and never hold the
 *       whole thing. Always fresh, but cursor (Next/Prev) navigation and no global
 *       facet counts (we don't have the full set to count).</li>
 * </ul>
 */
public enum CatalogType {
    STATIC,
    DYNAMIC
}
