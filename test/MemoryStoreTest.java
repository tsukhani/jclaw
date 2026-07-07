import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import memory.JpaMemoryStore;
import memory.MemoryCategory;
import memory.MemoryStore;
import memory.MemoryStoreFactory;

class MemoryStoreTest extends UnitTest {

    @BeforeEach
    void setup() {
        // JCLAW-428: JpaMemoryStore.search() exercises the DB-LIKE fallback
        // here, so force the Lucene index closed (and serialize against the
        // search-mode tests via the shared global lock).
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    /**
     * Find-or-create a real agent by name and return its id as the memory
     * partition key. Memories carry a real FK to agent now (JCLAW-537), so the
     * key must be an existing agent's id, not an arbitrary string.
     */
    private String agentId(String name) {
        var a = models.Agent.find("name = ?1", name).<models.Agent>first();
        if (a == null) {
            a = new models.Agent();
            a.name = name;
            a.modelProvider = "openrouter";
            a.modelId = "gpt-4.1";
            a.save();
        }
        return String.valueOf(a.id);
    }

    @Test
    void factoryReturnsJpaByDefault() {
        var store = MemoryStoreFactory.get();
        assertNotNull(store);
        assertInstanceOf(JpaMemoryStore.class, store);
    }

    @Test
    void storeAndList() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "User prefers dark mode", "preference");
        store.store(agentId("agent-1"), "User works at Acme Corp", "fact");

        var all = store.list(agentId("agent-1"));
        assertEquals(2, all.size());
    }

    @Test
    void storeAndSearch() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "User prefers concise responses", "preference");
        store.store(agentId("agent-1"), "User lives in Berlin", "fact");
        store.store(agentId("agent-1"), "User works on machine learning projects", "fact");

        var results = store.search(agentId("agent-1"), "concise", 10);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().text().contains("concise"));
    }

    @Test
    void searchIsCaseInsensitive() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "User prefers DARK MODE", "preference");

        var results = store.search(agentId("agent-1"), "dark mode", 10);
        assertEquals(1, results.size());
    }

    @Test
    void searchFiltersByAgent() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "Shared fact", "fact");
        store.store(agentId("agent-2"), "Other agent fact", "fact");

        var results1 = store.search(agentId("agent-1"), "fact", 10);
        assertEquals(1, results1.size());

        var results2 = store.search(agentId("agent-2"), "fact", 10);
        assertEquals(1, results2.size());
    }

    @Test
    void deleteRemovesMemory() {
        var store = MemoryStoreFactory.get();
        var id = store.store(agentId("agent-1"), "Temporary memory", "fact");

        var before = store.list(agentId("agent-1"));
        assertEquals(1, before.size());

        store.delete(id);

        var after = store.list(agentId("agent-1"));
        assertEquals(0, after.size());
    }

    @Test
    void searchRespectsLimit() {
        var store = MemoryStoreFactory.get();
        for (int i = 0; i < 10; i++) {
            store.store(agentId("agent-1"), "Memory item %d about testing".formatted(i), "fact");
        }

        var results = store.search(agentId("agent-1"), "testing", 3);
        assertEquals(3, results.size());
    }

    @Test
    void memoryEntryRecordFields() {
        var store = MemoryStoreFactory.get();
        var id = store.store(agentId("agent-1"), "Important fact", "core");

        var all = store.list(agentId("agent-1"));
        var entry = all.getFirst();
        assertEquals(id, entry.id());
        assertEquals(agentId("agent-1"), entry.agentId());
        assertEquals("Important fact", entry.text());
        assertEquals("core", entry.category());
        assertNotNull(entry.createdAt());
    }

    @Test
    void threeArgStoreAppliesCategoryDefaultImportance() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "User prefers dark mode", "preference");

        var entry = store.list(agentId("agent-1")).getFirst();
        assertEquals(MemoryCategory.PREFERENCE.defaultImportance, entry.importance(), 1e-9);
    }

    @Test
    void fourArgStorePersistsImportanceAndCategory() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "Operator is the sole admin", "core", 0.95);

        var entry = store.list(agentId("agent-1")).getFirst();
        assertEquals(0.95, entry.importance(), 1e-9);
        assertEquals("core", entry.category());
    }

    @Test
    void findCoreFiltersCategoryAndRanksByImportance() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "Low core note", "core", 0.82);
        store.store(agentId("agent-1"), "High core note", "core", 0.95);
        store.store(agentId("agent-1"), "Below threshold core", "core", 0.50);
        store.store(agentId("agent-1"), "A mere fact", "fact", 0.99);

        var core = models.Memory.findCore(agentId("agent-1"), 0.8, 10);
        assertEquals(2, core.size());
        // Ranked by importance DESC — high before low; the fact is excluded by category.
        assertEquals("High core note", core.get(0).text);
        assertEquals("Low core note", core.get(1).text);
    }

    @Test
    void listReturnsEmptyForUnknownAgent() {
        var store = MemoryStoreFactory.get();
        var results = store.list("nonexistent-agent");
        assertTrue(results.isEmpty());
    }

    @Test
    void searchReturnsEmptyForNoMatch() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "Something about cats", "fact");

        var results = store.search(agentId("agent-1"), "zzzzz_no_match", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void deleteAllRemovesOnlyTargetAgent() {
        // Guards the primitive that AgentService.delete() calls during agent cascade.
        // Must wipe every memory for the target agent while leaving other agents untouched.
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-1"), "keep-1", "fact");
        store.store(agentId("agent-1"), "keep-2", "fact");
        store.store(agentId("agent-2"), "drop-1", "fact");

        var removed = store.deleteAll(agentId("agent-2"));
        assertEquals(1, removed);
        assertEquals(2, store.list(agentId("agent-1")).size());
        assertEquals(0, store.list(agentId("agent-2")).size());
    }

    @Test
    void paginatedListRespectsLimitAndOffset() {
        // Covers the (agentId, limit, offset) overload — ordered by updatedAt DESC.
        var store = MemoryStoreFactory.get();
        for (int i = 0; i < 5; i++) {
            store.store(agentId("agent-page"), "row %d".formatted(i), "fact");
        }

        var firstTwo = store.list(agentId("agent-page"), 2, 0);
        assertEquals(2, firstTwo.size());

        var nextTwo = store.list(agentId("agent-page"), 2, 2);
        assertEquals(2, nextTwo.size());

        // No overlap between the two slices.
        var firstIds = firstTwo.stream().map(MemoryStore.MemoryEntry::id).toList();
        var nextIds = nextTwo.stream().map(MemoryStore.MemoryEntry::id).toList();
        for (var id : firstIds) {
            assertFalse(nextIds.contains(id),
                    "paginated slices must be disjoint, got overlap on id " + id);
        }
    }

    @Test
    void paginatedListReturnsEmptyForOffsetPastEnd() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-end"), "only row", "fact");
        var page = store.list(agentId("agent-end"), 10, 100);
        assertTrue(page.isEmpty(), "offset beyond row count must return empty");
    }

    @Test
    void deleteWithUnknownIdIsNoOp() {
        // Covers the Memory.findById == null guard in delete(String).
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-noop"), "keep me", "fact");
        store.delete("999999999"); // id doesn't exist
        assertEquals(1, store.list(agentId("agent-noop")).size(),
                "delete on unknown id must not affect existing rows");
    }

    @Test
    void deleteAllReturnsZeroForUnknownAgent() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-keep"), "stays", "fact");
        var removed = store.deleteAll("agent-none");
        assertEquals(0, removed);
        assertEquals(1, store.list(agentId("agent-keep")).size());
    }

    @Test
    void storeThrowsForUnknownNumericAgentId() {
        // JCLAW-537: memories carry a real FK — a write against a missing agent
        // is a programming error and must fail loudly, never persist an orphan.
        var store = MemoryStoreFactory.get();
        var ex = assertThrows(IllegalArgumentException.class,
                () -> store.store("424242", "orphan memory", "fact"));
        assertTrue(ex.getMessage().contains("424242"),
                "the failing agent id must be named in the error");
    }

    @Test
    void storeThrowsForNonNumericAgentId() {
        var store = MemoryStoreFactory.get();
        assertThrows(IllegalArgumentException.class,
                () -> store.store("not-a-pk", "orphan memory", "fact"));
    }

    @Test
    void storeThrowsForNullAgentId() {
        var store = MemoryStoreFactory.get();
        assertThrows(IllegalArgumentException.class,
                () -> store.store(null, "orphan memory", "fact"));
    }

    @Test
    void blankQuerySearchReturnsEmpty() {
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-blank"), "Something about cats", "fact");
        assertTrue(store.search(agentId("agent-blank"), "   ", 10).isEmpty(),
                "blank query must short-circuit to empty, not match everything");
    }

    @Test
    void paginatedListReturnsEmptyForNonNumericAgentId() {
        // Covers the pkOrNull guard in list(agentId, limit, offset).
        var store = MemoryStoreFactory.get();
        store.store(agentId("agent-guard"), "real row", "fact");
        assertTrue(store.list("not-a-pk", 10, 0).isEmpty());
    }
}
