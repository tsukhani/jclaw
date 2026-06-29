import org.junit.jupiter.api.*;
import play.test.*;
import memory.MemoryCategory;
import memory.MemoryStoreFactory;
import models.Agent;
import tools.SaveMemoryTool;

/**
 * JCLAW-530: the agent-callable save_memory tool — explicit "remember X" path.
 */
class SaveMemoryToolTest extends UnitTest {

    private SaveMemoryTool tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        // store() triggers Memory @PostPersist Lucene indexing; force the index
        // closed and serialize against the other Lucene tests.
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        tool = new SaveMemoryTool();
        agent = new Agent();           // transient is fine — execute() only reads agent.name
        agent.name = "save-mem-agent";
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    @Test
    void nameSchemaAndDescription() {
        assertEquals("save_memory", tool.name());
        assertTrue(tool.parameters().containsKey("properties"));
        assertFalse(tool.description().isBlank());
    }

    @Test
    void savesWithManualSourceAndCategoryDefault() {
        var result = tool.execute("{\"text\":\"The user's mother is Martha.\"}", agent);
        assertTrue(result.startsWith("Saved to memory"), result);

        var stored = MemoryStoreFactory.get().list("save-mem-agent");
        assertEquals(1, stored.size());
        var m = stored.getFirst();
        assertEquals("The user's mother is Martha.", m.text());
        assertEquals("manual", m.source());
        assertEquals("fact", m.category());
        assertEquals(MemoryCategory.FACT.defaultImportance, m.importance(), 1e-9);
    }

    @Test
    void honorsCategoryAndImportance() {
        var result = tool.execute(
                "{\"text\":\"Operator is the sole admin\",\"category\":\"core\",\"importance\":0.95}", agent);
        assertTrue(result.contains("core"), result);
        var m = MemoryStoreFactory.get().list("save-mem-agent").getFirst();
        assertEquals("core", m.category());
        assertEquals(0.95, m.importance(), 1e-9);
        assertEquals("manual", m.source());
    }

    @Test
    void clampsOutOfRangeImportance() {
        tool.execute("{\"text\":\"x\",\"importance\":5}", agent);
        var m = MemoryStoreFactory.get().list("save-mem-agent").getFirst();
        assertEquals(1.0, m.importance(), 1e-9);
    }

    @Test
    void missingTextReturnsErrorAndStoresNothing() {
        var result = tool.execute("{}", agent);
        assertTrue(result.startsWith("Error"), result);
        assertEquals(0, MemoryStoreFactory.get().list("save-mem-agent").size());
    }
}
