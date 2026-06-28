import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.catalog.CatalogQuery;
import services.catalog.CatalogRegistry;
import services.catalog.CatalogSkill;
import services.catalog.CategoryFacet;
import services.search.MessageSearch;

import java.util.List;

/**
 * Coverage for the static-dump catalog (mastra): upstream schema → DTO mapping
 * (incl. derived category), install-ranked browse, Lucene term search, topical
 * facets, the category filter, and pagination — driven through
 * {@link CatalogRegistry#MASTRA}.
 *
 * <p>Loads from an inline fixture via {@code MASTRA.loadForTest} (no network /
 * disk cache). Lucene access is serialized through {@link LuceneTestSync};
 * {@link MessageSearch#init()} is called explicitly because
 * {@code FullTextSearchInitJob} skips test mode.
 */
class StaticDumpCatalogTest extends UnitTest {

    // 5 skills with predictable categories: Git&VCS(1), Web&Frontend(2),
    // DevOps&Cloud(1), Documents(1); installs descending 100..60.
    private static final String FIXTURE = """
            {
              "scrapedAt": "2026-01-30T04:51:07.907Z",
              "skills": [
                {"skillId":"git-commit-helper","displayName":"Git Commit Helper","source":"a/tools","installs":100,"owner":"a","repo":"tools","githubUrl":"https://github.com/a/tools"},
                {"skillId":"react-dashboard","displayName":"React Dashboard","source":"b/web","installs":90,"owner":"b","repo":"web","githubUrl":"https://github.com/b/web"},
                {"skillId":"docker-deploy","displayName":"Docker Deploy","source":"c/ops","installs":80,"owner":"c","repo":"ops","githubUrl":"https://github.com/c/ops"},
                {"skillId":"react-router-guide","displayName":"React Router Guide","source":"b/web","installs":70,"owner":"b","repo":"web","githubUrl":"https://github.com/b/web"},
                {"skillId":"pdf-extractor","displayName":"PDF Extractor","source":"d/docs","installs":60,"owner":"d","repo":"docs","githubUrl":"https://github.com/d/docs"}
              ]
            }
            """;

    private static CatalogQuery q(String query, String category, int page, int pageSize) {
        return new CatalogQuery(query, category, page, pageSize, null, "installs");
    }

    private static CatalogQuery qSort(String query, int page, int pageSize, String sort) {
        return new CatalogQuery(query, null, page, pageSize, null, sort);
    }

    @BeforeEach
    void setUp() throws java.io.IOException {
        LuceneTestSync.openForTest();
        MessageSearch.init();
        CatalogRegistry.MASTRA.loadForTest(FIXTURE);
    }

    @AfterEach
    void tearDown() {
        CatalogRegistry.MASTRA.resetForTest();
        LuceneTestSync.release();
    }

    @Test
    void parsesSchemaFieldsAndDerivesCategory() {
        var r = CatalogRegistry.MASTRA.query(q("", null, 0, 1));

        assertTrue(r.ready());
        assertEquals(5, r.catalogSize());
        assertEquals("2026-01-30T04:51:07.907Z", r.scrapedAt());

        var top = r.results().get(0);   // most-installed
        assertEquals("git-commit-helper", top.skillId());
        assertEquals("https://github.com/a/tools", top.url());
        assertEquals(100L, top.installs());
        assertEquals("Git & VCS", top.category());
        assertEquals("mastra", top.provider());
    }

    @Test
    void blankQueryBrowsesMostInstalledFirst() {
        var ids = CatalogRegistry.MASTRA.query(q("", null, 0, 50)).results().stream()
                .map(CatalogSkill::skillId).toList();

        assertEquals(List.of("git-commit-helper", "react-dashboard", "docker-deploy",
                "react-router-guide", "pdf-extractor"), ids);
    }

    @Test
    void sortByNameOrdersAlphabetically() {
        var ids = CatalogRegistry.MASTRA.query(qSort("", 0, 50, "name")).results().stream()
                .map(CatalogSkill::skillId).toList();
        // By displayName: Docker Deploy, Git Commit Helper, PDF Extractor, React Dashboard, React Router Guide
        assertEquals(List.of("docker-deploy", "git-commit-helper", "pdf-extractor",
                "react-dashboard", "react-router-guide"), ids);
    }

    @Test
    void paginationSlicesByInstalls() {
        var page0 = CatalogRegistry.MASTRA.query(q("", null, 0, 2));
        assertEquals(5, page0.total());
        assertEquals(2, page0.results().size());
        assertEquals("git-commit-helper", page0.results().get(0).skillId());

        var page1 = CatalogRegistry.MASTRA.query(q("", null, 1, 2));
        assertEquals(List.of("docker-deploy", "react-router-guide"),
                page1.results().stream().map(CatalogSkill::skillId).toList());
    }

    @Test
    void facetsCountOverBaseWithAllFirst() {
        var facets = CatalogRegistry.MASTRA.query(q("", null, 0, 50)).facets();

        assertEquals("All", facets.get(0).category());
        assertEquals(5, facets.get(0).count());
        assertEquals("Web & Frontend", facets.get(1).category());
        assertEquals(2, facets.get(1).count());
        var rest = facets.subList(2, facets.size()).stream()
                .map(CategoryFacet::category).toList();
        assertTrue(rest.containsAll(List.of("Git & VCS", "DevOps & Cloud", "Documents")));
        assertFalse(rest.contains("Other"));
    }

    @Test
    void categoryFilterNarrowsResultsButNotFacets() {
        var r = CatalogRegistry.MASTRA.query(q("", "Web & Frontend", 0, 50));

        assertEquals(2, r.total());
        assertEquals(List.of("react-dashboard", "react-router-guide"),
                r.results().stream().map(CatalogSkill::skillId).toList());
        assertEquals(5, r.facets().get(0).count());
    }

    @Test
    void termQueryMatchesViaLuceneScope() {
        var r = CatalogRegistry.MASTRA.query(q("react", null, 0, 50));

        assertTrue(r.ready());
        assertEquals(2, r.results().size());
        assertEquals(List.of("react-dashboard", "react-router-guide"),
                r.results().stream().map(CatalogSkill::skillId).sorted().toList());
    }
}
