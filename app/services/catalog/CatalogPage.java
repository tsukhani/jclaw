package services.catalog;

import java.util.List;

/**
 * A page of catalog results — the unified response envelope for both catalog
 * types. Which fields are populated depends on the type, and the UI renders
 * navigation accordingly:
 *
 * <ul>
 *   <li><b>Static</b>: {@code total} (filtered count), {@code page}, {@code facets},
 *       {@code catalogSize}, {@code scrapedAt} are set; {@code nextCursor} is null
 *       → jump-to-page nav + topical facets.</li>
 *   <li><b>Dynamic</b>: {@code results} + {@code nextCursor} are set; {@code total}
 *       is {@code -1} (unknown — live), {@code facets} is empty, {@code scrapedAt}
 *       is null → Next/Prev cursor nav, no global facets.</li>
 * </ul>
 *
 * {@code ready=false} signals a load/fetch failure (the UI shows an error rather
 * than an empty "no matches").
 */
public record CatalogPage(boolean ready, List<CatalogSkill> results, int total, int page,
                          int pageSize, String nextCursor, List<CategoryFacet> facets,
                          int catalogSize, String scrapedAt) {

    /** Empty failure page, preserving the requested paging echo. */
    public static CatalogPage notReady(int page, int pageSize) {
        return new CatalogPage(false, List.of(), 0, page, pageSize, null, List.of(), -1, null);
    }
}
