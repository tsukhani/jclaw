package services.catalog;

/**
 * One facet row: a topical category, its icon, and how many skills in the current
 * (query-applied, category-unfiltered) result set fall in it. Only static
 * catalogs produce facets — a dynamic catalog can't count a set it doesn't hold.
 */
public record CategoryFacet(String category, String icon, int count) {}
