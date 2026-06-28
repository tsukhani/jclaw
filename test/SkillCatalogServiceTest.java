import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SkillCatalogService;
import services.search.MessageSearch;

import java.util.List;

/**
 * Spike coverage for the importable-skills catalog search: proves the upstream
 * snapshot schema maps onto {@link SkillCatalogService.CatalogSkill}, that a
 * blank query browses by install count, and that a term query rides the new
 * {@link services.search.LuceneIndexer.Scope#SKILLS_CATALOG} Lucene scope.
 *
 * <p>Loads from an inline fixture via {@link SkillCatalogService#loadForTest}
 * (no network / disk cache). Lucene access is serialized through
 * {@link LuceneTestSync}; {@link MessageSearch#init()} is called explicitly
 * because {@code FullTextSearchInitJob} skips test mode.
 */
class SkillCatalogServiceTest extends UnitTest {

    // Verbatim upstream shape (scraped-skills.json): only these 8 fields exist.
    private static final String FIXTURE = """
            {
              "scrapedAt": "2026-01-30T04:51:07.907Z",
              "totalSkills": 3,
              "skills": [
                {"source":"vercel-labs/agent-skills","skillId":"vercel-react-best-practices","name":"vercel-react-best-practices","installs":69954,"owner":"vercel-labs","repo":"agent-skills","githubUrl":"https://github.com/vercel-labs/agent-skills","displayName":"Vercel React Best Practices"},
                {"source":"remotion-dev/skills","skillId":"remotion-best-practices","name":"remotion-best-practices","installs":50464,"owner":"remotion-dev","repo":"skills","githubUrl":"https://github.com/remotion-dev/skills","displayName":"Remotion Best Practices"},
                {"source":"anthropics/skills","skillId":"frontend-design","name":"frontend-design","installs":1234,"owner":"anthropics","repo":"skills","githubUrl":"https://github.com/anthropics/skills","displayName":"Frontend Design"}
              ]
            }
            """;

    @BeforeEach
    void setUp() throws java.io.IOException {
        LuceneTestSync.openForTest();
        MessageSearch.init();
        SkillCatalogService.loadForTest(FIXTURE);
    }

    @AfterEach
    void tearDown() {
        SkillCatalogService.resetForTest();
        LuceneTestSync.release();
    }

    @Test
    void parsesEveryUpstreamSchemaField() {
        var r = SkillCatalogService.search("", 10);

        assertTrue(r.ready(), "catalog should be loaded");
        assertEquals(3, r.catalogSize());
        assertEquals("2026-01-30T04:51:07.907Z", r.scrapedAt());

        var top = r.results().get(0);
        assertEquals("vercel-react-best-practices", top.skillId());
        assertEquals("Vercel React Best Practices", top.displayName());
        assertEquals("vercel-labs/agent-skills", top.source());
        assertEquals("vercel-labs", top.owner());
        assertEquals("agent-skills", top.repo());
        assertEquals("https://github.com/vercel-labs/agent-skills", top.githubUrl());
        assertEquals(69954L, top.installs());
    }

    @Test
    void blankQueryBrowsesMostInstalledFirst() {
        var ids = SkillCatalogService.search("", 10).results().stream()
                .map(SkillCatalogService.CatalogSkill::skillId).toList();

        assertEquals(List.of(
                "vercel-react-best-practices",   // 69954
                "remotion-best-practices",       // 50464
                "frontend-design"), ids);        // 1234
    }

    @Test
    void termQueryMatchesViaLuceneScope() {
        var r = SkillCatalogService.search("remotion", 10);

        assertTrue(r.ready());
        assertEquals(1, r.results().size(), "only the Remotion skill matches 'remotion'");
        assertEquals("remotion-best-practices", r.results().get(0).skillId());
    }

    @Test
    void limitIsRespected() {
        assertEquals(1, SkillCatalogService.search("", 1).results().size());
    }
}
