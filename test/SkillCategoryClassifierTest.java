import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SkillCategoryClassifier;

/**
 * Pure coverage for the derived-category classifier behind Browse Catalog facets.
 */
class SkillCategoryClassifierTest extends UnitTest {

    @Test
    void classifiesByTopicalKeyword() {
        assertEquals("Git & VCS", SkillCategoryClassifier.classify("git-commit-helper", "Git Commit Helper", "tools"));
        assertEquals("Web & Frontend", SkillCategoryClassifier.classify("react-dashboard", "React Dashboard", "web"));
        assertEquals("Media & Creative", SkillCategoryClassifier.classify("ffmpeg", "FFmpeg", "video-toolkit"));
        assertEquals("DevOps & Cloud", SkillCategoryClassifier.classify("docker-deploy", "Docker Deploy", "ops"));
        assertEquals("Documents", SkillCategoryClassifier.classify("pdf-extractor", "PDF Extractor", "docs"));
    }

    @Test
    void firstMatchWinsBySpecificity() {
        // "test" (Testing, listed earlier) beats "react" (Web, listed later).
        assertEquals("Testing & QA", SkillCategoryClassifier.classify("react-test-helper", "React Test Helper", "x"));
    }

    @Test
    void unmatchedFallsToOther() {
        assertEquals(SkillCategoryClassifier.OTHER,
                SkillCategoryClassifier.classify("zxqwop", "Zxqwop Thing", "foo"));
    }

    @Test
    void nullFieldsDoNotThrow() {
        assertEquals(SkillCategoryClassifier.OTHER, SkillCategoryClassifier.classify(null, null, null));
    }

    @Test
    void iconLookup() {
        assertFalse(SkillCategoryClassifier.iconFor("Git & VCS").isBlank());
        assertEquals("📦", SkillCategoryClassifier.iconFor(SkillCategoryClassifier.OTHER));
        assertEquals("📦", SkillCategoryClassifier.iconFor("Nonexistent Category"));
    }

    @Test
    void taxonomyIsFixedAndManageable() {
        assertEquals(14, SkillCategoryClassifier.taxonomy().size());
    }
}
