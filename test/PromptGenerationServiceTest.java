import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.PromptGenerationService;

/**
 * JCLAW-813: prompt generation JSON parsing + the message-build/parse core
 * (via a canned {@link PromptGenerationService.Generator} seam, no live model).
 */
class PromptGenerationServiceTest extends UnitTest {

    @Test
    void parseReadsAllFields() {
        var g = PromptGenerationService.parse(
                "{\"title\":\"Code Review\",\"category\":\"CODING\","
                        + "\"content\":\"Review <code>.\",\"tags\":\"review, quality\"}");
        assertEquals("Code Review", g.title());
        assertEquals("CODING", g.category());
        assertEquals("Review <code>.", g.content());
        assertEquals("review, quality", g.tags());
    }

    @Test
    void parseCoercesUnknownCategoryToCustom() {
        var g = PromptGenerationService.parse(
                "{\"title\":\"T\",\"category\":\"NONSENSE\",\"content\":\"c\",\"tags\":\"\"}");
        assertEquals("CUSTOM", g.category());
    }

    @Test
    void parseStripsCodeFences() {
        var g = PromptGenerationService.parse(
                "```json\n{\"title\":\"T\",\"category\":\"WRITING\",\"content\":\"c\",\"tags\":\"a\"}\n```");
        assertEquals("T", g.title());
        assertEquals("WRITING", g.category());
    }

    @Test
    void parseFallsBackOnNonJson() {
        var g = PromptGenerationService.parse("just some plain text, not json");
        assertEquals("just some plain text, not json", g.content()); // raw text kept for editing
        assertEquals("Untitled prompt", g.title());                  // safe default title
        assertEquals("CUSTOM", g.category());
    }

    @Test
    void generateBuildsSystemPlusUserAndParses() throws Exception {
        var g = PromptGenerationService.generate("a checklist for reviewing code", msgs -> {
            // system instructions + user(description)
            assertEquals(2, msgs.size());
            return "{\"title\":\"Review\",\"category\":\"CODING\",\"content\":\"Do it\",\"tags\":\"x, y\"}";
        });
        assertEquals("Review", g.title());
        assertEquals("CODING", g.category());
        assertEquals("Do it", g.content());
        assertEquals("x, y", g.tags());
    }
}
