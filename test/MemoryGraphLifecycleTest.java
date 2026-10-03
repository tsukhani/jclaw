import memory.JpaMemoryStore;
import memory.graph.GraphLifecycle;
import memory.graph.GraphStore;
import memory.graph.GraphWithdrawal;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import models.Agent;
import models.Memory;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;

import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The graph follows its memories only once their change commits. Every mutation runs on a
 * fresh thread so its transaction really commits or rolls back, and every assertion reads
 * this class's own agents only.
 */
class MemoryGraphLifecycleTest extends UnitTest {

    private record Fixture(long agentId, long first, long second) {}

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static Memory memory(Agent agent, String text) {
        var m = new Memory();
        m.agent = agent;
        m.text = text;
        m.category = "fact";
        m.importance = 0.5;
        m.save();
        return m;
    }

    private static Meta meta(long agentId, String id) {
        return Meta.fresh(id, agentId, Tier.TENTATIVE);
    }

    /** T rests on both memories; R on the first only. */
    private static List<OntologyRecord> graph(Fixture f) {
        var a = f.agentId();
        return List.of(
                new Evidence(meta(a, "eA"), GraphStore.memorySource(f.first()), null),
                new Evidence(meta(a, "eB"), GraphStore.memorySource(f.second()), null),
                new Term(meta(a, "T"), "Person", "Ada", List.of(), List.of("eA", "eB")),
                new Term(meta(a, "O"), "Organization", "Acme", List.of(), List.of("eB")),
                new Relation(meta(a, "R"), "works_at", "T", "O", 0.5, List.of("eA")));
    }

    private static Fixture seed() throws Exception {
        try (var _ = LuceneTestSync.closedLease()) {
            var f = commitInFreshTx(() -> {
                var agent = AgentService.create("graph-life-" + System.nanoTime(), "openrouter", "gpt-4.1");
                var first = memory(agent, "The user works at Acme");
                var second = memory(agent, "Ada is the user's name");
                return new Fixture(agent.id, first.id, second.id);
            });
            GraphStore.get().write(f.agentId(), graph(f));
            return f;
        }
    }

    private static Set<String> ids(long agentId) throws Exception {
        var out = new HashSet<String>();
        GraphStore.get().read(agentId).forEach(r -> out.add(r.id()));
        return out;
    }

    private static void assertFirstWithdrawn(Fixture f) throws Exception {
        assertEquals(Set.of("eB", "T", "O"), ids(f.agentId()));
        var index = GraphStore.get().index(f.agentId());
        assertNotNull(index);
        assertFalse(index.byId().containsKey("eA"));
        assertFalse(index.byId().containsKey("R"));
        assertNull(index.evidenceIdsBySource().get(GraphStore.memorySource(f.first())));
    }

    @Test
    void deleteWithLineageWithdrawsOnceCommitted() throws Exception {
        var f = seed();
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                Memory.<Memory>findById(f.first()).deleteWithLineage();
                return null;
            });
        }
        assertFirstWithdrawn(f);
    }

    @Test
    void supersedeWithdrawsOnceCommitted() throws Exception {
        var f = seed();
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                Memory.<Memory>findById(f.first()).supersede(f.second());
                return null;
            });
        }
        assertFirstWithdrawn(f);
    }

    @Test
    void deleteAllWithdrawsEveryMemorySourceOnceCommitted() throws Exception {
        var f = seed();
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> new JpaMemoryStore(false, false).deleteAll(String.valueOf(f.agentId())));
        }
        assertEquals(Set.of(), ids(f.agentId()));
        var index = GraphStore.get().index(f.agentId());
        assertNotNull(index);
        assertTrue(index.byId().isEmpty());
    }

    @Test
    void aRolledBackDeleteLeavesTheGraphUnchanged() throws Exception {
        var f = seed();
        var before = Files.readString(GraphStore.get().agentDir(f.agentId()).resolve("evidence.jsonl"));
        try (var _ = LuceneTestSync.closedLease()) {
            assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> {
                Memory.<Memory>findById(f.first()).deleteWithLineage();
                throw new IllegalStateException("roll the delete back");
            }));
        }
        assertEquals(before, Files.readString(GraphStore.get().agentDir(f.agentId()).resolve("evidence.jsonl")));
        assertEquals(ids(f.agentId()), Set.of("eA", "eB", "T", "O", "R"));
    }

    @Test
    void deletingTheAgentRemovesItsGraphDirectory() throws Exception {
        var f = seed();
        var root = GraphStore.get().root();
        Files.createDirectories(root.resolve(f.agentId() + ".staging"));
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                AgentService.delete(Agent.findById(f.agentId()));
                return null;
            });
        }
        assertFalse(Files.exists(GraphStore.get().agentDir(f.agentId())));
        assertFalse(Files.exists(root.resolve(f.agentId() + ".staging")));
        assertFalse(Files.exists(root.resolve(f.agentId() + ".previous")));
        assertNull(GraphStore.get().index(f.agentId()));
    }

    @Test
    void reconcileWithdrawsAMemoryDeletedWithNoHookFiring() throws Exception {
        var f = seed();
        var expected = new HashSet<>(GraphWithdrawal.withdraw(graph(f),
                Set.of(GraphStore.memorySource(f.first()))).survivors());
        try (var _ = LuceneTestSync.closedLease()) {
            // Bulk JPQL skips deleteWithLineage, as a crash between commit and hook would.
            commitInFreshTx(() -> JPA.em().createQuery("DELETE FROM Memory m WHERE m.id = :id")
                    .setParameter("id", f.first()).executeUpdate());
        }
        assertEquals(ids(f.agentId()), Set.of("eA", "eB", "T", "O", "R"), "no hook ran");

        GraphLifecycle.reconcile();

        assertEquals(expected, new HashSet<>(GraphStore.get().read(f.agentId())));
        assertFirstWithdrawn(f);
    }

    @Test
    void reconcileWithdrawsAMemorySupersededWithNoHookFiring() throws Exception {
        var f = seed();
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> JPA.em().createQuery("UPDATE Memory m SET m.supersededAt = :at WHERE m.id = :id")
                    .setParameter("at", Instant.parse("2026-10-01T00:00:00Z"))
                    .setParameter("id", f.first()).executeUpdate());
        }

        GraphLifecycle.reconcile();

        assertFirstWithdrawn(f);
    }

    @Test
    void reconcileWithdrawsASourceNamingAnotherAgentsMemory() throws Exception {
        var f = seed();
        var other = seed();
        var foreign = new Fixture(f.agentId(), other.first(), f.second());
        GraphStore.get().write(f.agentId(), graph(foreign));

        GraphLifecycle.reconcile();

        assertFirstWithdrawn(foreign);
        assertEquals(Set.of("eA", "eB", "T", "O", "R"), ids(other.agentId()), "the owner's graph is untouched");
    }

    @Test
    void anUnreadableGraphDoesNotStopTheNextAgentsRepair() throws Exception {
        var broken = seed();
        var f = seed();
        Files.writeString(GraphStore.get().agentDir(broken.agentId()).resolve("evidence.jsonl"), "not json\n");
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> JPA.em().createQuery("DELETE FROM Memory m WHERE m.id = :id")
                    .setParameter("id", f.first()).executeUpdate());
        }

        GraphLifecycle.reconcile();

        assertFirstWithdrawn(f);
    }

    @Test
    void reconcileDeletesTheGraphOfAnAgentThatNoLongerExists() throws Exception {
        long missing = Long.MAX_VALUE - 11;
        var store = GraphStore.get();
        store.write(missing, List.of(
                new Evidence(meta(missing, "e1"), "message:1", null),
                new Term(meta(missing, "t1"), "Topic", "Chess", List.of(), List.of("e1"))));
        assertTrue(Files.isDirectory(store.agentDir(missing)));

        var result = GraphLifecycle.reconcile();

        assertTrue(result.orphansDeleted() >= 1);
        assertFalse(Files.exists(store.agentDir(missing)));
        assertNull(store.index(missing));
    }
}
