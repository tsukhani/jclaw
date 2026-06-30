import agents.SystemPromptAssembler;
import memory.MemoryStoreFactory;
import models.Agent;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConfigService;

/**
 * JCLAW-531: agent memory is partitioned on the immutable agent id, not the
 * mutable {@code agent.name}. These regression tests pin the two failure modes
 * of the old name-keyed scheme — a rename stranding memories, and a reused name
 * inheriting another agent's memories (a privacy leak) — through the real rename
 * / delete ({@link AgentService}) and recall ({@link SystemPromptAssembler})
 * paths.
 */
class MemoryAgentIdentityTest extends UnitTest {

    @BeforeEach
    void setup() {
        // Storing a memory triggers Memory @PostPersist Lucene indexing; force the
        // index closed (LIKE-fallback recall) and serialize against the other
        // Lucene tests, mirroring SystemPromptCoreMemoryTest.
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private void storeCore(Agent agent, String text) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text, "core", 0.9);
    }

    private String assemble(Agent agent) {
        return SystemPromptAssembler.assemble(agent, null, null, "web").systemPrompt();
    }

    @Test
    void renameDoesNotStrandMemory() {
        var agent = AgentService.create("identity-alpha", "openrouter", "gpt-4.1");
        storeCore(agent, "MARKER_RENAME the operator's home base is Lisbon");
        assertTrue(assemble(agent).contains("MARKER_RENAME"), "core memory present before rename");

        var renamed = AgentService.update(agent, "identity-beta", "openrouter", "gpt-4.1", true);
        assertEquals(agent.id, renamed.id, "a rename must not change the immutable id");

        assertTrue(assemble(renamed).contains("MARKER_RENAME"),
                "memory must survive a rename (it is partitioned on the immutable id)");
    }

    @Test
    void reusedNameDoesNotInheritMemory() {
        var first = AgentService.create("identity-reuse", "openrouter", "gpt-4.1");
        storeCore(first, "MARKER_LEAK a secret only the first agent should know");

        // Free the name by renaming the first agent away, then a brand-new agent
        // takes the freed name — it must start with no memories.
        AgentService.update(first, "identity-reuse-old", "openrouter", "gpt-4.1", true);
        var second = AgentService.create("identity-reuse", "openrouter", "gpt-4.1");

        assertNotEquals(first.id, second.id, "the reused name must map to a new id");
        assertFalse(assemble(second).contains("MARKER_LEAK"),
                "a reused name must not inherit the prior agent's memories");
    }

    @Test
    void deletingAnAgentClearsItsMemoryById() {
        var first = AgentService.create("identity-del", "openrouter", "gpt-4.1");
        storeCore(first, "MARKER_DEL an ephemeral fact from the deleted agent");
        var deletedId = String.valueOf(first.id);

        AgentService.delete(first);
        assertEquals(0, MemoryStoreFactory.get().list(deletedId).size(),
                "deleting an agent must clear its memories, keyed by the immutable id");

        // A new agent reusing the freed name starts clean.
        var second = AgentService.create("identity-del", "openrouter", "gpt-4.1");
        assertFalse(assemble(second).contains("MARKER_DEL"),
                "a name reused after deletion must not inherit the prior agent's memories");
    }

    @Test
    void rawAgentDeleteCascadesToMemoriesViaFk() {
        // JCLAW-537: deleting the agent ROW removes its memories via the FK's
        // ON DELETE CASCADE, independent of AgentService.delete (which also clears
        // them explicitly). Use a bare agent (no tool configs / workspace) so the
        // only FK referencing it is memory.agent_id, and a native DELETE so the
        // DB cascade fires directly rather than Hibernate's session-level logic.
        var agent = new Agent();
        agent.name = "identity-fk";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
        var key = String.valueOf(agent.id);
        MemoryStoreFactory.get().store(key, "MARKER_FK a fact to cascade-delete", "fact", 0.5);
        assertEquals(1, MemoryStoreFactory.get().list(key).size());

        play.db.jpa.JPA.em().createNativeQuery("DELETE FROM agent WHERE id = ?1")
                .setParameter(1, agent.id).executeUpdate();
        play.db.jpa.JPA.em().clear();

        assertEquals(0, MemoryStoreFactory.get().list(key).size(),
                "ON DELETE CASCADE on memory.agent_id must remove the agent's memories");
    }
}
