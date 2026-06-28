package services.catalog;

/**
 * A browse/search request against a {@link Catalog}. The same envelope serves
 * both catalog types; each implementation reads the fields its retrieval model
 * uses — a static catalog uses {@code page}/{@code pageSize}/{@code category},
 * a dynamic catalog uses {@code cursor} (and ignores {@code page}/facets).
 *
 * @param query    free-text query; blank means "browse" (install/popularity order)
 * @param category topical category filter, or null/blank/"All" for no filter (static only)
 * @param page     0-indexed page (static pagination)
 * @param pageSize results per page
 * @param cursor   opaque continuation token from a prior page's {@code nextCursor} (dynamic only)
 */
public record CatalogQuery(String query, String category, int page, int pageSize, String cursor) {}
