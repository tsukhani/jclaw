import memory.JpaMemoryStore;
import memory.graph.GraphLifecycle;
import memory.graph.GraphStore;
import memory.graph.GraphWithdrawal;
import memory.graph.GraphWithdrawal.LineageDecision;
import memory.graph.GraphWithdrawal.Retirement;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    private static void supersede(long memoryId, long by) throws Exception {
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                Memory.<Memory>findById(memoryId).supersede(by);
                return null;
            });
        }
    }

    private static Instant supersededAt(long memoryId) {
        return commitInFreshTx(() -> Memory.<Memory>findById(memoryId).supersededAt);
    }

    private static Retirement rowRetirement(long memoryId) {
        return commitInFreshTx(() -> {
            Memory m = Memory.findById(memoryId);
            return Retirement.of(m.supersededAt, m.supersededById);
        });
    }

    private static Evidence evidence(long agentId, String id) throws Exception {
        return (Evidence) GraphStore.get().read(agentId).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow();
    }

    private static Map<String, String> files(long agentId) throws Exception {
        var out = new TreeMap<String, String>();
        try (var list = Files.list(GraphStore.get().agentDir(agentId))) {
            for (var file : list.toList()) out.put(file.getFileName().toString(), Files.readString(file));
        }
        return out;
    }

    @Test
    void supersedeRetiresOnceCommitted() throws Exception {
        var f = seed();
        supersede(f.first(), f.second());

        assertEquals(Set.of("eA", "eB", "T", "O", "R"), ids(f.agentId()));
        var eA = evidence(f.agentId(), "eA");
        assertNotNull(eA.retiredAt());
        assertEquals(supersededAt(f.first()), eA.retiredAt());
        assertEquals(GraphStore.memorySource(f.second()), eA.retiredBy());
        assertNull(evidence(f.agentId(), "eB").retiredAt());
    }

    @Test
    void supersedeAndDeleteInOneTransactionWithdraws() throws Exception {
        var f = seed();
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                Memory m = Memory.findById(f.first());
                m.supersede(f.second());
                m.deleteWithLineage();
                return null;
            });
        }
        assertFirstWithdrawn(f);
    }

    @Test
    void theHookAndReconcileWriteIdenticalBytes() throws Exception {
        var f = seed();
        supersede(f.first(), f.second());
        var byHook = files(f.agentId());

        GraphStore.get().write(f.agentId(), graph(f));
        assertNotEquals(byHook, files(f.agentId()));
        GraphLifecycle.reconcile();

        assertEquals(byHook, files(f.agentId()));
    }

    @Test
    void deletingTheSuccessorClearsLineageOnReconcile() throws Exception {
        var f = seed();
        supersede(f.first(), f.second());
        var retiredAt = supersededAt(f.first());
        assertTrue(GraphStore.get().recordLineage(f.agentId(), Map.of(f.first(),
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), rowRetirement(f.first())))));
        assertEquals(Lineage.UPDATE, evidence(f.agentId(), "eA").lineage());
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                Memory.<Memory>findById(f.second()).deleteWithLineage();
                return null;
            });
        }
        assertFalse(ids(f.agentId()).contains("eB"));

        GraphLifecycle.reconcile();

        var eA = evidence(f.agentId(), "eA");
        assertEquals(retiredAt, eA.retiredAt());
        assertNull(eA.retiredBy());
        assertNull(eA.lineage());
        assertNull(eA.changedBy());
    }

    @Test
    void aSecondSupersessionKeepsTheFirstLineage() throws Exception {
        var f = seed();
        long third;
        try (var _ = LuceneTestSync.closedLease()) {
            third = commitInFreshTx(() -> memory(Agent.findById(f.agentId()), "Ada works at Initech").id);
        }
        supersede(f.first(), f.second());
        GraphStore.get().recordLineage(f.agentId(), Map.of(f.first(),
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), rowRetirement(f.first()))));
        supersede(f.second(), third);
        GraphStore.get().recordLineage(f.agentId(), Map.of(f.second(),
                new LineageDecision(Lineage.CORRECTION, null, rowRetirement(f.second()))));
        var before = evidence(f.agentId(), "eA");

        GraphLifecycle.reconcile();

        assertEquals(before, evidence(f.agentId(), "eA"));
        assertEquals(GraphStore.memorySource(f.second()), before.retiredBy());
        assertEquals(Lineage.UPDATE, before.lineage());
        assertEquals(LocalDate.parse("2026-10-01"), before.changedBy());
        assertEquals(Lineage.CORRECTION, evidence(f.agentId(), "eB").lineage());
    }

    @Test
    void claimFreeEvidenceTakesAnUpdateLineage() throws Exception {
        var f = seed();
        supersede(f.first(), f.second());

        assertTrue(GraphStore.get().recordLineage(f.agentId(), Map.of(f.first(),
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-01-01"), rowRetirement(f.first())))));

        var eA = evidence(f.agentId(), "eA");
        assertEquals(Lineage.UPDATE, eA.lineage());
        assertEquals(LocalDate.parse("2026-01-01"), eA.changedBy());
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
    void reconcileRetiresAMemorySupersededWithNoHookFiring() throws Exception {
        var f = seed();
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> JPA.em().createQuery("UPDATE Memory m SET m.supersededAt = :at WHERE m.id = :id")
                    .setParameter("at", Instant.parse("2026-10-01T00:00:00Z"))
                    .setParameter("id", f.first()).executeUpdate());
        }
        assertNull(evidence(f.agentId(), "eA").retiredAt(), "no hook ran");

        GraphLifecycle.reconcile();

        assertEquals(Set.of("eA", "eB", "T", "O", "R"), ids(f.agentId()));
        var eA = evidence(f.agentId(), "eA");
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), eA.retiredAt());
        assertNull(eA.retiredBy());
    }

    @Test
    void aRolledBackSupersedeLeavesTheGraphUnchanged() throws Exception {
        var f = seed();
        var before = files(f.agentId());
        try (var _ = LuceneTestSync.closedLease()) {
            assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> {
                Memory.<Memory>findById(f.first()).supersede(f.second());
                throw new IllegalStateException("roll the supersede back");
            }));
        }
        assertEquals(before, files(f.agentId()));
    }

    @Test
    void aRowNoLongerSupersededAtReadBackWritesNothing() throws Exception {
        var f = seed();
        var before = files(f.agentId());
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> {
                Memory m = Memory.findById(f.first());
                m.supersede(f.second());
                m.supersededAt = null;
                m.supersededById = null;
                m.save();
                return null;
            });
        }
        assertEquals(before, files(f.agentId()));
    }

    @Test
    void reconcileClearsTheRetirementOfAnUnsupersededRow() throws Exception {
        var f = seed();
        supersede(f.first(), f.second());
        GraphStore.get().recordLineage(f.agentId(), Map.of(f.first(),
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), rowRetirement(f.first()))));
        try (var _ = LuceneTestSync.closedLease()) {
            commitInFreshTx(() -> JPA.em().createQuery(
                            "UPDATE Memory m SET m.supersededAt = NULL, m.supersededById = NULL WHERE m.id = :id")
                    .setParameter("id", f.first()).executeUpdate());
        }

        var result = GraphLifecycle.reconcile();

        assertTrue(result.evidenceRetired() >= 1);
        assertTrue(result.lineageCleared() >= 1);
        assertEquals(new HashSet<>(graph(f)), new HashSet<>(GraphStore.get().read(f.agentId())));
    }

    @Test
    void aRefusedStampLeavesTheGraphUnchanged() throws Exception {
        var f = seed();
        var a = f.agentId();
        var future = new Evidence(meta(a, "eA"), GraphStore.memorySource(f.first()), null, null, null, null,
                Instant.parse("2999-01-01T00:00:00Z"), null, null, null, null, null, null, null, null, null);
        var records = new ArrayList<>(graph(f));
        records.set(0, future);
        GraphStore.get().write(a, records);
        var before = files(a);

        supersede(f.first(), f.second());
        assertEquals(before, files(a), "the hook's refused stamp is logged, not written");
        assertNotNull(supersededAt(f.first()), "the supersession still committed");

        GraphLifecycle.reconcile();
        assertEquals(before, files(a), "reconcile's refused stamp is logged, not written");
    }

    @Test
    void reconcileWithdrawsADeletedMemoryEvenWhenAStampIsRefused() throws Exception {
        var f = seed();
        var a = f.agentId();
        var future = new Evidence(meta(a, "eA"), GraphStore.memorySource(f.first()), null, null, null, null,
                Instant.parse("2999-01-01T00:00:00Z"), null, null, null, null, null, null, null, null, null);
        var records = new ArrayList<>(graph(f));
        records.set(0, future);
        GraphStore.get().write(a, records);
        try (var _ = LuceneTestSync.closedLease()) {
            // Bulk JPQL, so no hook fires for either change.
            commitInFreshTx(() -> JPA.em().createQuery("UPDATE Memory m SET m.supersededAt = :at WHERE m.id = :id")
                    .setParameter("at", Instant.parse("2026-10-01T00:00:00Z"))
                    .setParameter("id", f.first()).executeUpdate());
            commitInFreshTx(() -> JPA.em().createQuery("DELETE FROM Memory m WHERE m.id = :id")
                    .setParameter("id", f.second()).executeUpdate());
        }

        GraphLifecycle.reconcile();

        assertEquals(Set.of("eA", "T"), ids(a), "eB is withdrawn, with O and R cascading");
        assertEquals(future, evidence(a, "eA"), "the refused stamp left eA unretired");
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
