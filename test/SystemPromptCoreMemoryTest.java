import agents.SystemPromptAssembler;
import models.Agent;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConfigService;

/**
 * JCLAW-40: core-memory auto-load into the cacheable prefix, plus the recall
 * exclusion + importance blend in {@code SystemPromptAssembler}.
 */
class SystemPromptCoreMemoryTest extends UnitTest {

    @BeforeEach
    void setup() {
        // Seeding a memory triggers Memory @PostPersist Lucene indexing; force the
        // index closed (LIKE-fallback recall) and serialize against the other
        // Lucene tests, mirroring MemoryStoreTest.
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        memory.MemoryStoreFactory.reset();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private Agent newAgent(String name) {
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    private void store(String agentName, String text, String category, double importance) {
        memory.MemoryStoreFactory.get().store(agentName, text, category, importance);
    }

    private static int countOccurrences(String haystack, String needle) {
        int c = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    @Test
    void coreMemoriesAppearInCacheablePrefix() {
        var agent = newAgent("spa-core-1");
        store(agent.name, "MARKER_CORE_FACT operator is the sole admin", "core", 0.9);

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        int coreIdx = prompt.indexOf("MARKER_CORE_FACT");
        int marker = prompt.indexOf(SystemPromptAssembler.CACHE_BOUNDARY_MARKER);

        assertTrue(prompt.contains("## Core Memories"), "core memories header present");
        assertTrue(coreIdx >= 0, "core memory text injected");
        assertTrue(coreIdx < marker, "core memories must sit in the cacheable prefix (before the boundary)");
    }

    @Test
    void belowThresholdCoreMemoryIsNotAutoLoaded() {
        var agent = newAgent("spa-core-2");
        store(agent.name, "MARKER_LOW below the default threshold", "core", 0.5);  // < 0.8

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertFalse(prompt.contains("MARKER_LOW"), "below-threshold core memory must not auto-load");
    }

    @Test
    void nonCoreCategoryIsNotAutoLoaded() {
        var agent = newAgent("spa-core-3");
        store(agent.name, "MARKER_FACT a mere high-importance fact", "fact", 0.99);

        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        assertFalse(prompt.contains("## Core Memories"), "a non-core memory must not appear in the core block");
    }

    @Test
    void tokenBudgetCapsTheCoreBlock() {
        var agent = newAgent("spa-core-4");
        for (int i = 0; i < 50; i++) {
            store(agent.name, "COREFILL%02d ".formatted(i) + "x".repeat(80), "core", 0.9);
        }
        var prompt = SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
        int count = countOccurrences(prompt, "COREFILL");
        assertTrue(count > 0, "some core memories should load");
        assertTrue(count < 50, "token budget must cap the core block below the full set");
    }

    @Test
    void coreMemoryIsExcludedFromPerTurnRecall() {
        var agent = newAgent("spa-core-5");
        store(agent.name, "MARKER_DUAL widget preferences are important to track", "core", 0.9);

        // The userMessage is a substring of the memory text so the LIKE-fallback
        // recall returns it; the exclusion must keep it from being duplicated.
        var prompt = SystemPromptAssembler.assemble(agent, "widget preferences", null, "web").systemPrompt();
        assertTrue(prompt.contains("## Core Memories"), "core block present");
        assertEquals(1, countOccurrences(prompt, "MARKER_DUAL"),
                "a core memory must appear once (in the core block), not again in recall");
    }

    // NB: a positive "recall finds a non-core memory" assertion would depend on
    // the JVM-global Lucene search dialect being "none" (the LIKE fallback),
    // which concurrent search-mode tests can flip (JCLAW-428) — that path is
    // already covered reliably by MemoryStoreTest.storeAndSearch. The exclusion
    // test above stays robust because the core memory appears exactly once
    // whether or not recall finds it.
}
