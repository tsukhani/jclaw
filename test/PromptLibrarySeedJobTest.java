import jobs.PromptLibrarySeedJob;
import models.Prompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

/**
 * JCLAW-813: {@link PromptLibrarySeedJob} seeds sample prompts only into an
 * empty library, and no-ops once any prompt exists (so the operator's curation
 * — including deleting every sample — is never overwritten).
 */
class PromptLibrarySeedJobTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
    }

    @Test
    void seedsSamplesIntoEmptyLibrary() {
        assertEquals(0, Prompt.count());
        new PromptLibrarySeedJob().doJob();
        long seeded = Prompt.count();
        assertTrue(seeded >= 10, "expected ~10+ sample prompts, got " + seeded);
        for (Prompt p : Prompt.<Prompt>findAll()) {
            assertNotNull(p.category, "every seeded prompt has a fixed category");
            assertTrue(p.content != null && !p.content.isBlank(), "content must be non-blank");
        }
    }

    @Test
    void noOpWhenLibraryNonEmpty() {
        var p = new Prompt();
        p.title = "Existing";
        p.content = "c";
        p.category = Prompt.Category.CUSTOM;
        p.save();
        new PromptLibrarySeedJob().doJob();
        assertEquals(1, Prompt.count(), "seed job must not add to a non-empty library");
    }
}
