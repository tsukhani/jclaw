import memory.graph.GraphStore;
import memory.graph.GraphStore.GraphRefusedException;
import memory.graph.GraphStore.Retraction;
import memory.graph.GraphStore.RunRetractedException;
import memory.graph.RunLedger;
import memory.graph.RunLedger.Entry;
import memory.graph.RunLedger.Outcome;
import memory.graph.RunLedger.Selector;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;

/** JCLAW-1371: retracting what a run wrote, by run id, model, digest or certificate, and refusing it again. */
class MemoryGraphRetractionTest extends UnitTest {

    private static final long A = 7L;
    private static final long B = 8L;
    private static final String RUN_A = "run@aaaaaaaaaaaa";
    private static final String RUN_B = "run@bbbbbbbbbbbb";
    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir
    Path tmp;

    private GraphStore store;

    @BeforeEach
    void setup() {
        store = new GraphStore(tmp.resolve("memory-graph"));
    }

    // ---- fixtures ----

    private static Meta meta(long agent, String id) {
        return Meta.fresh(id, agent, Tier.TENTATIVE);
    }

    private static Evidence ev(long agent, String id, String source, String runId) {
        return new Evidence(meta(agent, id), source, null, null, null, runId, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static Entry entry(long agent, String runId, String source, String model, String digest, String cert) {
        return new Entry(runId, agent, source, "sha256:" + runId.substring(4), Outcome.WRITTEN, model, digest, cert,
                "v3@000000000000", "x@000000000000", false, List.of());
    }

    private static Entry entry(long agent, String runId, String source) {
        return entry(agent, runId, source, "tev1", "sha256:d1", "cert@c1");
    }

    /** {@code records} with {@code added} put in place by id. */
    private static UnaryOperator<List<OntologyRecord>> put(OntologyRecord... added) {
        return records -> {
            var byId = new TreeMap<String, OntologyRecord>();
            records.forEach(r -> byId.put(r.id(), r));
            for (var r : added) byId.put(r.id(), r);
            return new ArrayList<>(byId.values());
        };
    }

    /** Run A grounds Term T in memory:1, run B in memory:2. */
    private void twoRunsOnOneTerm(long agent) throws IOException {
        store.recordRun(agent, entry(agent, RUN_A, "memory:1"), put(ev(agent, "eA", "memory:1", RUN_A),
                new Term(meta(agent, "T"), "Organization", "Acme", List.of(), List.of("eA"))));
        store.recordRun(agent, entry(agent, RUN_B, "memory:2"), put(ev(agent, "eB", "memory:2", RUN_B),
                new Term(meta(agent, "T"), "Organization", "Acme", List.of(), List.of("eA", "eB"))));
    }

    private OntologyRecord byId(long agent, String id) throws IOException {
        return store.read(agent).stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }

    private Map<String, String> files(long agent) throws IOException {
        var out = new TreeMap<String, String>();
        var dir = store.agentDir(agent);
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (var f : s.toList()) out.put(f.getFileName().toString(), Files.readString(f));
        }
        return out;
    }

    private Object dirKey(long agent) throws IOException {
        return Files.readAttributes(store.agentDir(agent), BasicFileAttributes.class).fileKey();
    }

    private void assertValid(long agent) throws IOException {
        assertEquals(List.of(), OntologyValidator.validate(OntologySchema.seed(), store.read(agent)));
    }

    private Entry ledgerEntry(long agent, String runId) throws IOException {
        return store.ledger(agent).stream().filter(e -> e.runId().equals(runId)).findFirst().orElseThrow();
    }

    // ---- retraction by run id ----

    @Test
    void retractingOneOfTwoRunsKeepsTheTermOnTheOtherRunsEvidence() throws Exception {
        twoRunsOnOneTerm(A);

        assertEquals(new Retraction(1, 1, 0), store.retract(A, Set.of(RUN_A)));

        assertEquals(List.of("eB"), ((Term) byId(A, "T")).evidenceIds());
        assertNull(byId(A, "eA"));
        assertTrue(ledgerEntry(A, RUN_A).retracted(), "a retracted run stays in the ledger");
        assertFalse(ledgerEntry(A, RUN_B).retracted());
        assertValid(A);
    }

    @Test
    void retractingARecordsLastSupportCascadesThroughItsMappingsRelationsAndConstraints() throws Exception {
        twoRunsOnOneTerm(A);
        var run = "run@cccccccccccc";
        store.recordRun(A, entry(A, run, "memory:3"), put(ev(A, "eC", "memory:3", run),
                new Term(meta(A, "P"), "Person", "Ada", List.of("mP"), List.of("eC")),
                new Mapping(meta(A, "mP"), "P", "memory:3", List.of("eC")),
                new Relation(meta(A, "R"), "works_at", "P", "T", List.of("eC")),
                new Constraint(meta(A, "cP"), "P", "only at work", List.of("eC"))));
        assertValid(A);

        assertEquals(new Retraction(1, 1, 4), store.retract(A, Set.of(run)));

        for (var id : List.of("eC", "P", "mP", "R", "cP")) assertNull(byId(A, id), id);
        assertEquals(List.of("eA", "eB"), ((Term) byId(A, "T")).evidenceIds());
        assertValid(A);
    }

    @Test
    void retractingASuccessorClearsTheLineageItStampedUnlessAnotherRunOfItsSourceStands() throws Exception {
        var predecessor = "run@111111111111";
        var successor = "run@222222222222";
        var stamped = new Evidence(meta(A, "e120"), "memory:120", null, null, null, predecessor, null, AT,
                "memory:506", Lineage.UPDATE, LocalDate.parse("2026-10-01"), null, null, null, null, null);
        store.recordRun(A, entry(A, predecessor, "memory:120"), put(stamped,
                new Term(meta(A, "T"), "Organization", "Acme", List.of(), List.of("e120"))));
        store.recordRun(A, entry(A, successor, "memory:506"), put(ev(A, "e506", "memory:506", successor),
                new Term(meta(A, "T"), "Organization", "Acme", List.of(), List.of("e120", "e506"))));
        var rerun = "run@333333333333";
        store.recordRun(A, entry(A, rerun, "memory:506"), records -> records);

        store.retract(A, Set.of(successor));
        var kept = (Evidence) byId(A, "e120");
        assertEquals(Lineage.UPDATE, kept.lineage(), "the standing rerun of memory:506 still vouches for it");

        assertEquals(new Retraction(1, 0, 0), store.retract(A, Set.of(rerun)));
        var cleared = (Evidence) byId(A, "e120");
        assertNull(cleared.lineage());
        assertNull(cleared.changedBy());
        assertEquals(AT, cleared.retiredAt());
        assertEquals("memory:506", cleared.retiredBy());
        assertValid(A);
    }

    @Test
    void retractingAgainReturnsZerosAndWritesNothing() throws Exception {
        twoRunsOnOneTerm(A);
        store.retract(A, Set.of(RUN_A));
        var files = files(A);
        var key = dirKey(A);

        store.failAfterFilesForTest(1);
        try {
            assertEquals(Retraction.NONE, store.retract(A, Set.of(RUN_A)));
            assertEquals(Retraction.NONE, store.retract(A, Set.of("run@000000000000")), "a run the ledger lacks");
        } finally {
            store.failAfterFilesForTest(-1);
        }
        assertEquals(files, files(A));
        assertEquals(key, dirKey(A), "the directory was not swapped");
    }

    @Test
    void aWrittenRunTheValidatorRefusesChangesNothing() throws Exception {
        twoRunsOnOneTerm(A);
        var files = files(A);
        var run = "run@eeeeeeeeeeee";

        var refused = assertThrows(GraphRefusedException.class, () -> store.recordRun(A, entry(A, run, "memory:5"),
                put(ev(A, "eE", "memory:5", run),
                        new Term(meta(A, "Q"), "Organization", "Dangling", List.of(), List.of("e-missing")))));

        assertFalse(refused instanceof RunRetractedException, refused.getMessage());
        assertFalse(refused.violations().isEmpty());
        assertEquals(files, files(A), "records and ledger alike");
    }

    // ---- the write guard ----

    @Test
    void aRetractedRunCannotBeWrittenAgainButItsNeighboursCan() throws Exception {
        twoRunsOnOneTerm(A);
        store.retract(A, Set.of(RUN_A));
        var files = files(A);

        var again = assertThrows(RunRetractedException.class, () -> store.recordRun(A, entry(A, RUN_A, "memory:1"),
                put(ev(A, "eA", "memory:1", RUN_A))));
        assertTrue(again.getMessage().contains("run retracted") && again.getMessage().contains(RUN_A),
                again.getMessage());
        assertEquals(RUN_A, again.runId());
        var empty = new Entry(RUN_A, A, "memory:1", "sha256:x", Outcome.EMPTY, "tev1", "sha256:d1", "cert@c1",
                "v3@0", "x@0", false, List.of());
        assertThrows(RunRetractedException.class, () -> store.recordRun(A, empty, records -> records));
        assertEquals(files, files(A));

        var stray = ev(A, "eStray", "memory:1", RUN_A);
        assertThrows(RunRetractedException.class, () -> store.update(A, put(stray)));
        var all = new ArrayList<>(store.read(A));
        all.add(stray);
        assertThrows(RunRetractedException.class, () -> store.write(A, all));
        assertEquals(files, files(A));

        var sameSource = "run@aaaaaaaaaaa9";
        store.recordRun(A, entry(A, sameSource, "memory:1"), put(ev(A, "eA2", "memory:1", sameSource),
                new Term(meta(A, "T"), "Organization", "Acme", List.of(), List.of("eB", "eA2"))));
        var prefixed = RUN_A + "0";
        store.update(A, put(ev(A, "eP", "memory:9", prefixed),
                new Term(meta(A, "T"), "Organization", "Acme", List.of(), List.of("eB", "eA2", "eP"))));
        assertEquals(List.of("eB", "eA2", "eP"), ((Term) byId(A, "T")).evidenceIds());
        assertValid(A);
    }

    // ---- retraction by model, digest or certificate ----

    /** Agent A: m1 (tev1, d1, c1), m2 (tev1, d2, c2); agent B: m3 (tev1, d1, c1), m4 (tev2, d3, c3). */
    private void fourRuns() throws IOException {
        for (var run : List.of(
                entry(A, "run@m10000000000", "memory:1", "tev1", "sha256:d1", "cert@c1"),
                entry(A, "run@m20000000000", "memory:2", "tev1", "sha256:d2", "cert@c2"),
                entry(B, "run@m30000000000", "memory:3", "tev1", "sha256:d1", "cert@c1"),
                entry(B, "run@m40000000000", "memory:4", "tev2", "sha256:d3", "cert@c3"))) {
            var agent = run.agentId();
            var e = ev(agent, "e" + run.runId().charAt(5), run.source(), run.runId());
            store.recordRun(agent, run, records -> {
                var term = records.stream().filter(r -> r.id().equals("T")).map(r -> (Term) r).findFirst();
                var evidence = new ArrayList<>(term.map(Term::evidenceIds).orElse(List.of()));
                evidence.add(e.id());
                return put(e, new Term(meta(agent, "T"), "Organization", "Acme", List.of(), evidence)).apply(records);
            });
        }
    }

    private Set<String> retracted() throws IOException {
        var out = new TreeSet<String>();
        for (var agent : List.of(A, B)) {
            store.ledger(agent).stream().filter(Entry::retracted).forEach(e -> out.add(e.runId()));
        }
        return out;
    }

    @Test
    void aModelTakesEveryDigestOfItsNameAcrossAgents() throws Exception {
        fourRuns();
        assertEquals(new TreeSet<>(Set.of("run@m10000000000", "run@m20000000000")),
                store.runs(A, new Selector.Model("tev1")));

        assertEquals(new Retraction(3, 3, 1), store.retract(new Selector.Model("tev1")));

        assertEquals(Set.of("run@m10000000000", "run@m20000000000", "run@m30000000000"), retracted());
        assertNull(byId(A, "T"), "both of A's runs are gone");
        assertEquals(List.of("e4"), ((Term) byId(B, "T")).evidenceIds());
        assertValid(A);
        assertValid(B);

        var files = Map.of(A, files(A), B, files(B));
        assertEquals(Retraction.NONE, store.retract(new Selector.Model("tev1")));
        assertEquals(Retraction.NONE, store.retract(new Selector.Digest("sha256:d1")), "already retracted");
        assertEquals(files, Map.of(A, files(A), B, files(B)));
    }

    @Test
    void aDigestTakesOnlyItsOwnRuns() throws Exception {
        fourRuns();
        assertEquals(new Retraction(2, 2, 0), store.retract(new Selector.Digest("sha256:d1")));
        assertEquals(Set.of("run@m10000000000", "run@m30000000000"), retracted());
        assertEquals(List.of("e2"), ((Term) byId(A, "T")).evidenceIds());
        assertEquals(List.of("e4"), ((Term) byId(B, "T")).evidenceIds());
        assertValid(A);
        assertValid(B);
    }

    @Test
    void aCertificateTakesOnlyItsOwnRuns() throws Exception {
        fourRuns();
        assertEquals(new Retraction(1, 1, 0), store.retract(new Selector.Certificate("cert@c3")));
        assertEquals(Set.of("run@m40000000000"), retracted());
        assertEquals(List.of("e3"), ((Term) byId(B, "T")).evidenceIds());
        assertEquals(List.of("e1", "e2"), ((Term) byId(A, "T")).evidenceIds());
        assertValid(A);
        assertValid(B);
        assertEquals(Retraction.NONE, store.retract(new Selector.Certificate("cert@none")));
    }

    // ---- the ledger in the swap ----

    @Test
    void anInterruptedRecordRunLeavesThePreviousLedgerBytes() throws Exception {
        twoRunsOnOneTerm(A);
        var before = files(A);
        store.failAfterFilesForTest(1);
        try {
            assertThrows(IOException.class, () -> store.recordRun(A, entry(A, "run@dddddddddddd", "memory:4"),
                    records -> records));
        } finally {
            store.failAfterFilesForTest(-1);
        }
        assertEquals(before, files(A));
        assertEquals(2, store.ledger(A).size());
    }

    @Test
    void twoThreadsRecordingRunsOnOneAgentBothLand() throws Exception {
        store.recordRun(A, entry(A, RUN_A, "memory:1"), put(ev(A, "eA", "memory:1", RUN_A),
                new Term(meta(A, "T"), "Organization", "Acme", List.of(), List.of("eA"))));
        var start = new CountDownLatch(1);
        var errors = new ArrayList<Throwable>();
        var threads = new ArrayList<Thread>();
        for (var n : List.of("1", "2")) {
            var runId = "run@00000000000" + n;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    store.recordRun(A, entry(A, runId, "memory:1" + n), records -> {
                        var e = ev(A, "e" + n, "memory:1" + n, runId);
                        var t = (Term) records.stream().filter(r -> r.id().equals("T")).findFirst().orElseThrow();
                        var ids = new ArrayList<>(t.evidenceIds());
                        ids.add(e.id());
                        return put(e, new Term(t.meta(), t.type(), t.name(), List.of(), ids)).apply(records);
                    });
                } catch (Exception | AssertionError e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            }));
        }
        start.countDown();
        for (var t : threads) t.join();

        assertEquals(List.of(), errors);
        assertEquals(Set.of(RUN_A, "run@000000000001", "run@000000000002"),
                Set.copyOf(store.ledger(A).stream().map(Entry::runId).toList()));
        assertEquals(Set.of("eA", "e1", "e2"), Set.copyOf(((Term) byId(A, "T")).evidenceIds()));
        assertValid(A);
    }

    @Test
    void writesWithNoRunCarryTheLedgerOverByteIdentically() throws Exception {
        twoRunsOnOneTerm(A);
        var ledger = Files.readString(store.agentDir(A).resolve(RunLedger.FILE_NAME));

        store.update(A, put(new Evidence(meta(A, "eX"), "memory:50", null),
                new Term(meta(A, "O"), "Organization", "Other", List.of(), List.of("eX"))));
        assertEquals(ledger, Files.readString(store.agentDir(A).resolve(RunLedger.FILE_NAME)));
        store.write(A, store.read(A));
        assertEquals(ledger, Files.readString(store.agentDir(A).resolve(RunLedger.FILE_NAME)));
        assertEquals(Set.of("eX", "O"), store.withdraw(A, Set.of(50L)));
        assertEquals(ledger, Files.readString(store.agentDir(A).resolve(RunLedger.FILE_NAME)));
    }

    @Test
    void deletingTheAgentRemovesItsLedger() throws Exception {
        twoRunsOnOneTerm(A);
        assertTrue(Files.exists(store.agentDir(A).resolve(RunLedger.FILE_NAME)));
        store.deleteAgent(A);
        assertFalse(Files.exists(store.agentDir(A)));
        assertEquals(List.of(), store.ledger(A));
    }
}
