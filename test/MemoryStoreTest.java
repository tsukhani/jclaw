import org.junit.jupiter.api.*;
import play.test.*;
import memory.*;

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

    @Test
    void factoryReturnsJpaByDefault() {
        var store = MemoryStoreFactory.get();
        assertNotNull(store);
        assertInstanceOf(JpaMemoryStore.class, store);
    }

    @Test
    void storeAndList() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "User prefers dark mode", "preference");
        store.store("agent-1", "User works at Acme Corp", "fact");

        var all = store.list("agent-1");
        assertEquals(2, all.size());
    }

    @Test
    void storeAndSearch() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "User prefers concise responses", "preference");
        store.store("agent-1", "User lives in Berlin", "fact");
        store.store("agent-1", "User works on machine learning projects", "fact");

        var results = store.search("agent-1", "concise", 10);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().text().contains("concise"));
    }

    @Test
    void searchIsCaseInsensitive() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "User prefers DARK MODE", "preference");

        var results = store.search("agent-1", "dark mode", 10);
        assertEquals(1, results.size());
    }

    @Test
    void searchFiltersByAgent() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "Shared fact", "fact");
        store.store("agent-2", "Other agent fact", "fact");

        var results1 = store.search("agent-1", "fact", 10);
        assertEquals(1, results1.size());

        var results2 = store.search("agent-2", "fact", 10);
        assertEquals(1, results2.size());
    }

    @Test
    void deleteRemovesMemory() {
        var store = MemoryStoreFactory.get();
        var id = store.store("agent-1", "Temporary memory", "fact");

        var before = store.list("agent-1");
        assertEquals(1, before.size());

        store.delete(id);

        var after = store.list("agent-1");
        assertEquals(0, after.size());
    }

    @Test
    void searchRespectsLimit() {
        var store = MemoryStoreFactory.get();
        for (int i = 0; i < 10; i++) {
            store.store("agent-1", "Memory item %d about testing".formatted(i), "fact");
        }

        var results = store.search("agent-1", "testing", 3);
        assertEquals(3, results.size());
    }

    @Test
    void memoryEntryRecordFields() {
        var store = MemoryStoreFactory.get();
        var id = store.store("agent-1", "Important fact", "core");

        var all = store.list("agent-1");
        var entry = all.getFirst();
        assertEquals(id, entry.id());
        assertEquals("agent-1", entry.agentId());
        assertEquals("Important fact", entry.text());
        assertEquals("core", entry.category());
        assertNotNull(entry.createdAt());
    }

    @Test
    void threeArgStoreAppliesCategoryDefaultImportance() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "User prefers dark mode", "preference");

        var entry = store.list("agent-1").getFirst();
        assertEquals(MemoryCategory.PREFERENCE.defaultImportance, entry.importance(), 1e-9);
    }

    @Test
    void fourArgStorePersistsImportanceAndCategory() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "Operator is the sole admin", "core", 0.95);

        var entry = store.list("agent-1").getFirst();
        assertEquals(0.95, entry.importance(), 1e-9);
        assertEquals("core", entry.category());
    }

    @Test
    void findCoreFiltersCategoryAndRanksByImportance() {
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "Low core note", "core", 0.82);
        store.store("agent-1", "High core note", "core", 0.95);
        store.store("agent-1", "Below threshold core", "core", 0.50);
        store.store("agent-1", "A mere fact", "fact", 0.99);

        var core = models.Memory.findCore("agent-1", 0.8, 10);
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
        store.store("agent-1", "Something about cats", "fact");

        var results = store.search("agent-1", "zzzzz_no_match", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void deleteAllRemovesOnlyTargetAgent() {
        // Guards the primitive that AgentService.delete() calls during agent cascade.
        // Must wipe every memory for the target agent while leaving other agents untouched.
        var store = MemoryStoreFactory.get();
        store.store("agent-1", "keep-1", "fact");
        store.store("agent-1", "keep-2", "fact");
        store.store("agent-2", "drop-1", "fact");

        var removed = store.deleteAll("agent-2");
        assertEquals(1, removed);
        assertEquals(2, store.list("agent-1").size());
        assertEquals(0, store.list("agent-2").size());
    }

    @Test
    void paginatedListRespectsLimitAndOffset() {
        // Covers the (agentId, limit, offset) overload — ordered by updatedAt DESC.
        var store = MemoryStoreFactory.get();
        for (int i = 0; i < 5; i++) {
            store.store("agent-page", "row %d".formatted(i), "fact");
        }

        var firstTwo = store.list("agent-page", 2, 0);
        assertEquals(2, firstTwo.size());

        var nextTwo = store.list("agent-page", 2, 2);
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
        store.store("agent-end", "only row", "fact");
        var page = store.list("agent-end", 10, 100);
        assertTrue(page.isEmpty(), "offset beyond row count must return empty");
    }

    @Test
    void deleteWithUnknownIdIsNoOp() {
        // Covers the Memory.findById == null guard in delete(String).
        var store = MemoryStoreFactory.get();
        store.store("agent-noop", "keep me", "fact");
        store.delete("999999999"); // id doesn't exist
        assertEquals(1, store.list("agent-noop").size(),
                "delete on unknown id must not affect existing rows");
    }

    @Test
    void deleteAllReturnsZeroForUnknownAgent() {
        var store = MemoryStoreFactory.get();
        store.store("agent-keep", "stays", "fact");
        var removed = store.deleteAll("agent-none");
        assertEquals(0, removed);
        assertEquals(1, store.list("agent-keep").size());
    }

    @Test
    void factoryFallsBackToJpaForUnknownBackend() {
        // Set memory.backend to a name no switch arm handles → default
        // branch logs an error and yields JpaMemoryStore.
        var prior = play.Play.configuration.getProperty("memory.backend");
        play.Play.configuration.setProperty("memory.backend", "definitely-not-a-real-backend");
        try {
            MemoryStoreFactory.reset();
            var store = MemoryStoreFactory.get();
            assertInstanceOf(JpaMemoryStore.class, store);
        } finally {
            if (prior == null) {
                play.Play.configuration.remove("memory.backend");
            } else {
                play.Play.configuration.setProperty("memory.backend", prior);
            }
            MemoryStoreFactory.reset();
        }
    }

    @Test
    void factoryFallsBackToJpaWhenNeo4jDriverMissing() {
        // memory.backend=neo4j: factory tries Class.forName on the Neo4j
        // driver. In the test JVM the driver is not on the classpath, so
        // the ClassNotFoundException catch fires and falls back to JPA.
        var prior = play.Play.configuration.getProperty("memory.backend");
        play.Play.configuration.setProperty("memory.backend", "neo4j");
        try {
            MemoryStoreFactory.reset();
            var store = MemoryStoreFactory.get();
            assertInstanceOf(JpaMemoryStore.class, store,
                    "missing Neo4j driver must yield a JpaMemoryStore fallback");
        } finally {
            if (prior == null) {
                play.Play.configuration.remove("memory.backend");
            } else {
                play.Play.configuration.setProperty("memory.backend", prior);
            }
            MemoryStoreFactory.reset();
        }
    }
}
