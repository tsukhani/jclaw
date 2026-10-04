import memory.graph.GraphCodec;
import memory.graph.GraphStore;
import memory.graph.GraphStore.GraphRefusedException;
import memory.graph.GraphWithdrawal.LineageDecision;
import memory.graph.GraphWithdrawal.Retirement;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologyRecord.Valence;
import memory.ontology.OntologyValidator.Kind;
import memory.ontology.OntologyValidator.Violation;
import models.MemoryAuthorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

class MemoryGraphStoreTest extends UnitTest {

    private static final long AGENT = 7L;

    @TempDir
    Path tmp;

    private GraphStore store;

    @BeforeEach
    void setup() {
        store = new GraphStore(tmp.resolve("memory-graph"));
    }

    private static Meta meta(String id) {
        return Meta.fresh(id, AGENT, Tier.TENTATIVE);
    }

    /** All five families, every reference resolved. */
    private static List<OntologyRecord> fullSet() {
        return List.of(
                new Evidence(meta("e1"), "memory:1", null),
                new Evidence(meta("e2"), "message:9", "p1"),
                new Term(meta("p1"), "Person", "Ada", List.of("m1"), List.of("e1")),
                new Term(new Meta("o1", AGENT, Tier.FIRM, 3, Instant.parse("2026-09-01T10:15:30Z"), 1),
                        "Organization", "Acme", List.of(), List.of("e1")),
                new Mapping(meta("m1"), "p1", "memory:1", List.of("e1")),
                new Relation(meta("r1"), "works_at", "p1", "o1", 0.5, List.of("e1", "e2")),
                new Constraint(meta("c1"), "p1", "only in work contexts", List.of("e2")));
    }

    private static List<OntologyRecord> shuffled(List<OntologyRecord> records, long seed) {
        var copy = new ArrayList<>(records);
        Collections.shuffle(copy, new Random(seed));
        return copy;
    }

    private Map<String, String> snapshot() throws IOException {
        var out = new TreeMap<String, String>();
        var dir = store.agentDir(AGENT);
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (var f : files.toList()) out.put(f.getFileName().toString(), Files.readString(f));
        }
        return out;
    }

    private List<String> rootNames() throws IOException {
        try (var s = Files.list(store.root())) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aShuffledSetRoundTripsAndRewritesByteIdentically() throws Exception {
        store.write(AGENT, shuffled(fullSet(), 1));
        assertEquals(new HashSet<>(fullSet()), new HashSet<>(store.read(AGENT)));
        var first = snapshot();
        assertEquals(Set.of("term.jsonl", "mapping.jsonl", "relation.jsonl", "constraint.jsonl", "evidence.jsonl"),
                first.keySet());

        store.write(AGENT, shuffled(store.read(AGENT), 2));
        assertEquals(first, snapshot(), "an unchanged graph rewrites to the same bytes");

        for (var doc : first.values()) {
            var ids = doc.lines().map(l -> l.substring(7, l.indexOf('"', 7))).toList();
            assertEquals(ids.stream().sorted().toList(), ids, "lines are sorted by id: " + doc);
            assertTrue(doc.isEmpty() || doc.endsWith("\n"));
        }
    }

    @Test
    void aRecordGivenTwiceWritesTheSameBytesAsOnce() throws Exception {
        store.write(AGENT, fullSet());
        var once = snapshot();
        var twice = new ArrayList<>(fullSet());
        twice.add(fullSet().getFirst());
        store.write(AGENT, twice);
        assertEquals(once, snapshot());
    }

    /** The matrix's withdraw rows on the store: T rests on memory:5 and memory:6, R on T on memory:5 alone. */
    @Test
    void withdrawingTheMatrixSourcesUpdatesFilesAndIndex() throws Exception {
        store.write(AGENT, List.of(
                new Evidence(meta("e5"), "memory:5", null),
                new Evidence(meta("e6"), "memory:6", null),
                new Evidence(meta("e50"), "memory:50", null),
                new Term(meta("T"), "Person", "Ada", List.of("mT"), List.of("e5", "e6")),
                new Term(meta("O"), "Organization", "Acme", List.of(), List.of("e50")),
                new Mapping(meta("mT"), "T", "message:1", List.of("e6")),
                new Constraint(meta("cT"), "T", "only at work", List.of("e6")),
                new Evidence(meta("eS"), "message:2", "T"),
                new Relation(meta("R"), "works_at", "T", "O", List.of("e5"))));

        assertEquals(Set.of("e5", "R"), store.withdraw(AGENT, Set.of(5L)));
        var term = (Term) store.read(AGENT).stream().filter(r -> r.id().equals("T")).findFirst().orElseThrow();
        assertEquals(List.of("e6"), term.evidenceIds());
        var index = store.index(AGENT);
        assertNotNull(index);
        assertFalse(index.byId().containsKey("e5"));
        assertFalse(index.byId().containsKey("R"));
        assertNull(index.evidenceIdsBySource().get("memory:5"));
        assertEquals(Set.of("e50"), index.evidenceIdsBySource().get("memory:50"));

        assertEquals(Set.of("e6", "T", "mT", "cT", "eS"), store.withdraw(AGENT, Set.of(6L)));
        assertEquals(Set.of("e50", "O"), Set.copyOf(store.read(AGENT).stream().map(OntologyRecord::id).toList()));
        index = store.index(AGENT);
        assertNotNull(index);
        assertEquals(Set.of("e50", "O"), index.byId().keySet());
        assertEquals(Map.of("memory:50", Set.of("e50")), index.evidenceIdsBySource());
    }

    @Test
    void theLineFormatPinsKeyOrderAndValueShapes() throws Exception {
        store.write(AGENT, fullSet());
        var dir = store.agentDir(AGENT);
        assertEquals("""
                {"id":"o1","agentId":7,"tier":"firm","usefulRecallCount":3,"lastUsefulRecall":"2026-09-01T10:15:30Z","graphVersion":1,"type":"Organization","name":"Acme","mappingIds":[],"evidenceIds":["e1"]}
                {"id":"p1","agentId":7,"tier":"tentative","usefulRecallCount":0,"lastUsefulRecall":null,"graphVersion":1,"type":"Person","name":"Ada","mappingIds":["m1"],"evidenceIds":["e1"]}
                """, Files.readString(dir.resolve("term.jsonl")));
        assertEquals("""
                {"id":"r1","agentId":7,"tier":"tentative","usefulRecallCount":0,"lastUsefulRecall":null,"graphVersion":1,"type":"works_at","from":"p1","to":"o1","weight":0.5,"evidenceIds":["e1","e2"]}
                """, Files.readString(dir.resolve("relation.jsonl")));
        assertEquals("""
                {"id":"e1","agentId":7,"tier":"tentative","usefulRecallCount":0,"lastUsefulRecall":null,"graphVersion":1,"source":"memory:1","subjectId":null}
                {"id":"e2","agentId":7,"tier":"tentative","usefulRecallCount":0,"lastUsefulRecall":null,"graphVersion":1,"source":"message:9","subjectId":"p1"}
                """, Files.readString(dir.resolve("evidence.jsonl")));
        assertEquals("""
                {"id":"m1","agentId":7,"tier":"tentative","usefulRecallCount":0,"lastUsefulRecall":null,"graphVersion":1,"termId":"p1","source":"memory:1","evidenceIds":["e1"]}
                """, Files.readString(dir.resolve("mapping.jsonl")));
        assertEquals("""
                {"id":"c1","agentId":7,"tier":"tentative","usefulRecallCount":0,"lastUsefulRecall":null,"graphVersion":1,"termId":"p1","rule":"only in work contexts","evidenceIds":["e2"]}
                """, Files.readString(dir.resolve("constraint.jsonl")));
    }

    @Test
    void decodingRefusesAMissingOrUnknownKeyNamingTheLine() {
        var line = GraphCodec.encode(new Evidence(meta("e1"), "memory:1", null));
        var missing = assertThrows(IllegalArgumentException.class, () -> GraphCodec.decode(
                GraphCodec.Family.EVIDENCE, line.replace(",\"subjectId\":null", ""), "7/evidence.jsonl", 3));
        assertTrue(missing.getMessage().startsWith("7/evidence.jsonl:3: missing key 'subjectId'"), missing.getMessage());
        var unknown = assertThrows(IllegalArgumentException.class, () -> GraphCodec.decode(
                GraphCodec.Family.EVIDENCE, line.replace("}", ",\"extra\":1}"), "7/evidence.jsonl", 4));
        assertTrue(unknown.getMessage().startsWith("7/evidence.jsonl:4: unknown key 'extra'"), unknown.getMessage());
        var explicitNull = assertThrows(IllegalArgumentException.class, () -> GraphCodec.decode(
                GraphCodec.Family.EVIDENCE, line.replace("}", ",\"runId\":null}"), "7/evidence.jsonl", 5));
        assertTrue(explicitNull.getMessage().startsWith(
                "7/evidence.jsonl:5: 'runId' is optional and must be omitted rather than null"), explicitNull.getMessage());
        var termLine = GraphCodec.encode(new Term(meta("t1"), "Topic", "Gardening", List.of(), List.of("e1")));
        var explicitEmpty = assertThrows(IllegalArgumentException.class, () -> GraphCodec.decode(
                GraphCodec.Family.TERM, termLine.replace("}", ",\"aliases\":[]}"), "7/term.jsonl", 6));
        assertTrue(explicitEmpty.getMessage().startsWith(
                "7/term.jsonl:6: 'aliases' is optional and must be omitted rather than empty"), explicitEmpty.getMessage());
    }

    private static Evidence fullyQualified() {
        return new Evidence(meta("e9"), "memory:501", "r1", MemoryAuthorType.HUMAN_TURN, 0.875, "run-3",
                Instant.parse("2026-02-15T09:00:00Z"), Instant.parse("2026-03-01T12:30:00Z"), "memory:612",
                Lineage.UPDATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-02-15"), Status.HOLDS,
                EdtfInterval.parse("2019/.."), EdtfInterval.parse("2026-02-14"), Valence.FAVORABLE);
    }

    @Test
    void optionalKeysFollowTheRequiredOnesInAFixedOrder() {
        var evidence = fullyQualified();
        var evidenceLine = "{\"id\":\"e9\",\"agentId\":7,\"tier\":\"tentative\",\"usefulRecallCount\":0,"
                + "\"lastUsefulRecall\":null,\"graphVersion\":1,\"source\":\"memory:501\",\"subjectId\":\"r1\","
                + "\"authorType\":\"human_turn\",\"confidence\":0.875,\"runId\":\"run-3\","
                + "\"recordedAt\":\"2026-02-15T09:00:00Z\",\"retiredAt\":\"2026-03-01T12:30:00Z\","
                + "\"retiredBy\":\"memory:612\",\"lineage\":\"update\",\"changedBy\":\"2026-03-01\","
                + "\"anchor\":\"2026-02-15\",\"status\":\"holds\",\"valid\":\"2019/..\",\"occurs\":\"2026-02-14\","
                + "\"valence\":\"favorable\"}";
        assertEquals(evidenceLine, GraphCodec.encode(evidence));
        assertEquals(evidence, GraphCodec.decode(GraphCodec.Family.EVIDENCE, evidenceLine, "7/evidence.jsonl", 1));

        var term = new Term(meta("p1"), "Person", "Ada", List.of(), List.of("e1"), List.of("Ada L.", "Countess"), "p0");
        var termLine = "{\"id\":\"p1\",\"agentId\":7,\"tier\":\"tentative\",\"usefulRecallCount\":0,"
                + "\"lastUsefulRecall\":null,\"graphVersion\":1,\"type\":\"Person\",\"name\":\"Ada\","
                + "\"mappingIds\":[],\"evidenceIds\":[\"e1\"],\"aliases\":[\"Ada L.\",\"Countess\"],\"mergedInto\":\"p0\"}";
        assertEquals(termLine, GraphCodec.encode(term));
        assertEquals(term, GraphCodec.decode(GraphCodec.Family.TERM, termLine, "7/term.jsonl", 1));

        var mapping = new Mapping(meta("m1"), "p1", "memory:1", List.of("e1"), List.of("Ada", "my aunt"));
        var mappingLine = "{\"id\":\"m1\",\"agentId\":7,\"tier\":\"tentative\",\"usefulRecallCount\":0,"
                + "\"lastUsefulRecall\":null,\"graphVersion\":1,\"termId\":\"p1\",\"source\":\"memory:1\","
                + "\"evidenceIds\":[\"e1\"],\"surfaces\":[\"Ada\",\"my aunt\"]}";
        assertEquals(mappingLine, GraphCodec.encode(mapping));
        assertEquals(mapping, GraphCodec.decode(GraphCodec.Family.MAPPING, mappingLine, "7/mapping.jsonl", 1));
    }

    @Test
    void aLineWithoutOptionalKeysDecodesToUnsetFields() {
        var line = "{\"id\":\"e1\",\"agentId\":7,\"tier\":\"tentative\",\"usefulRecallCount\":0,"
                + "\"lastUsefulRecall\":null,\"graphVersion\":1,\"source\":\"memory:1\",\"subjectId\":null}";
        var evidence = (Evidence) GraphCodec.decode(GraphCodec.Family.EVIDENCE, line, "7/evidence.jsonl", 1);
        assertEquals(new Evidence(meta("e1"), "memory:1", null), evidence);
        assertNull(evidence.authorType());
        assertNull(evidence.valid());
        assertEquals(line, GraphCodec.encode(evidence));
        var term = (Term) GraphCodec.decode(GraphCodec.Family.TERM,
                GraphCodec.encode(new Term(meta("t1"), "Topic", "x", List.of(), List.of("e1"))), "7/term.jsonl", 1);
        assertEquals(List.of(), term.aliases());
        assertNull(term.mergedInto());
    }

    @Test
    void theNeverFormAnApproximateStartAndUnattributedRoundTrip() {
        for (var e : List.of(
                new Evidence(meta("e1"), "memory:1", "r1", null, null, null, null, null, null, null, null, null,
                        Status.DENIED, EdtfInterval.parse("../2026-02-15"), null, null),
                new Evidence(meta("e2"), "memory:2", "r1", null, null, null, null, null, null, null, null, null,
                        Status.HOLDS, EdtfInterval.parse("2023~/.."), null, null),
                new Evidence(meta("e3"), "memory:3", null, MemoryAuthorType.UNATTRIBUTED, null, null, null, null,
                        null, null, null, null, null, null, null, null))) {
            var line = GraphCodec.encode(e);
            assertEquals(e, GraphCodec.decode(GraphCodec.Family.EVIDENCE, line, "7/evidence.jsonl", 1), line);
        }
        assertTrue(GraphCodec.encode(new Evidence(meta("e3"), "memory:3", null, MemoryAuthorType.UNATTRIBUTED, null,
                null, null, null, null, null, null, null, null, null, null, null)).contains("\"authorType\":\"unattributed\""));
    }

    @Test
    void aMalformedOptionalValueIsRefusedNamingTheLine() {
        var line = GraphCodec.encode(new Evidence(meta("e1"), "memory:1", null));
        int lineNo = 10;
        for (var extra : List.of("\"valid\":\"2019-13\"", "\"status\":\"Holds\"", "\"anchor\":\"2026-02-30\"",
                "\"recordedAt\":\"yesterday\"", "\"authorType\":\"HUMAN_TURN\"", "\"confidence\":\"high\"")) {
            var n = lineNo++;
            var e = assertThrows(IllegalArgumentException.class, () -> GraphCodec.decode(
                    GraphCodec.Family.EVIDENCE, line.replace("}", "," + extra + "}"), "7/evidence.jsonl", n), extra);
            assertTrue(e.getMessage().startsWith("7/evidence.jsonl:" + n + ": "), e.getMessage());
        }
    }

    @Test
    void aNonFiniteConfidenceIsRefusedOnEncode() {
        var e = new Evidence(meta("e1"), "memory:1", null, null, Double.NaN, null, null, null, null, null, null,
                null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> GraphCodec.encode(e));
    }

    @Test
    void aDocumentInTodaysFormatRewritesByteIdentically() throws Exception {
        var dir = Files.createDirectories(store.agentDir(AGENT));
        var today = new TreeMap<String, String>();
        for (var family : GraphCodec.Family.values()) {
            var doc = GraphCodec.document(family, fullSet());
            today.put(family.fileName(), doc);
            Files.writeString(dir.resolve(family.fileName()), doc);
        }
        assertFalse(String.join("", today.values()).contains("authorType"));
        store.write(AGENT, store.read(AGENT));
        assertEquals(today, snapshot());
    }

    @Test
    void aMissingFamilyFileReadsAsEmpty() throws Exception {
        var dir = Files.createDirectories(store.agentDir(AGENT));
        var evidence = new Evidence(meta("e1"), "memory:1", null);
        var term = new Term(meta("t1"), "Topic", "Gardening", List.of(), List.of("e1"));
        Files.writeString(dir.resolve("evidence.jsonl"), GraphCodec.encode(evidence) + "\n");
        Files.writeString(dir.resolve("term.jsonl"), GraphCodec.encode(term) + "\n");

        assertEquals(Set.of(evidence, term), new HashSet<>(store.read(AGENT)));
    }

    @Test
    void aRecordWithExactlyOneEvidenceIdIsAccepted() throws Exception {
        var records = List.<OntologyRecord>of(
                new Evidence(meta("e1"), "memory:1", null),
                new Term(meta("t1"), "Topic", "Gardening", List.of(), List.of("e1")));
        store.write(AGENT, records);
        assertEquals(Set.copyOf(records), new HashSet<>(store.read(AGENT)));
    }

    private void assertRefused(List<OntologyRecord> records, Kind kind, String recordId) throws Exception {
        store.write(AGENT, fullSet());
        var before = snapshot();
        var refused = assertThrows(GraphRefusedException.class, () -> store.write(AGENT, records));
        assertTrue(refused.violations().stream().map(Violation::kind).anyMatch(kind::equals),
                () -> "expected " + kind + " in " + refused.violations());
        assertTrue(refused.violations().stream().map(Violation::recordId).anyMatch(recordId::equals));
        assertEquals(before, snapshot(), "a refused write leaves the files unchanged");
    }

    @Test
    void aTermWithNoEvidenceIsRefused() throws Exception {
        var records = new ArrayList<>(fullSet());
        records.add(new Term(meta("t9"), "Topic", "Chess", List.of(), List.of()));
        assertRefused(records, Kind.MISSING_EVIDENCE, "t9");
    }

    @Test
    void anUndeclaredTypeOrDisallowedEndpointIsRefused() throws Exception {
        var pet = new ArrayList<>(fullSet());
        pet.add(new Term(meta("t9"), "Pet", "Rex", List.of(), List.of("e1")));
        assertRefused(pet, Kind.UNDECLARED_TYPE, "t9");

        var place = new ArrayList<>(fullSet());
        place.add(new Term(meta("pl1"), "Place", "Lisbon", List.of(), List.of("e1")));
        place.add(new Relation(meta("r9"), "works_at", "p1", "pl1", List.of("e1")));
        assertRefused(place, Kind.DISALLOWED_ENDPOINT, "r9");
    }

    @Test
    void anAxiomViolationIsRefused() throws Exception {
        var loop = new ArrayList<>(fullSet());
        loop.add(new Relation(meta("r9"), "part_of", "o1", "o1", List.of("e1")));
        assertRefused(loop, Kind.AXIOM, "r9");

        var bothWays = new ArrayList<>(fullSet());
        bothWays.add(new Term(meta("p2"), "Person", "Grace", List.of(), List.of("e1")));
        bothWays.add(new Relation(meta("r8"), "family_of", "p1", "p2", List.of("e1")));
        bothWays.add(new Relation(meta("r9"), "family_of", "p2", "p1", List.of("e1")));
        assertRefused(bothWays, Kind.AXIOM, "r9");
    }

    @Test
    void aRecordOfAnotherAgentIsRefused() throws Exception {
        store.write(AGENT, fullSet());
        var before = snapshot();
        var records = new ArrayList<>(fullSet());
        records.add(new Evidence(Meta.fresh("e9", AGENT + 1, Tier.TENTATIVE), "memory:9", null));

        var refused = assertThrows(GraphRefusedException.class, () -> store.write(AGENT, records));
        assertEquals(List.of("e9"), refused.foreignRecordIds());
        assertEquals(before, snapshot());
    }

    @Test
    void aWriteThatFailsWhileStagingLeavesThePreviousDocuments() throws Exception {
        store.write(AGENT, fullSet());
        var before = snapshot();
        var changed = new ArrayList<>(fullSet());
        changed.add(new Evidence(meta("e3"), "memory:3", null));

        store.failAfterFilesForTest(2);
        try {
            assertThrows(IOException.class, () -> store.write(AGENT, changed));
        } finally {
            store.failAfterFilesForTest(-1);
        }
        assertEquals(before, snapshot());
        assertEquals(List.of("7"), rootNames(), "no staging directory remains");
        assertEquals(new HashSet<>(fullSet()), new HashSet<>(store.read(AGENT)));
    }

    @Test
    void aSwapInterruptedAfterTheBackupMoveIsRecoveredOnRead() throws Exception {
        store.write(AGENT, fullSet());
        var before = snapshot();
        var dir = store.agentDir(AGENT);
        Files.move(dir, dir.resolveSibling(AGENT + ".previous"));
        Files.writeString(Files.createDirectories(dir.resolveSibling(AGENT + ".staging")).resolve("term.jsonl"), "");

        assertEquals(new HashSet<>(fullSet()), new HashSet<>(store.read(AGENT)));
        assertEquals(before, snapshot());
        assertEquals(List.of("7"), rootNames());
    }

    @Test
    void anUnknownFileSurvivesEveryRewriteByteForByte() throws Exception {
        store.write(AGENT, fullSet());
        var commitment = store.agentDir(AGENT).resolve("commitment.jsonl");
        var bytes = "{\"id\":\"k1\",\"future\":true}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(commitment, bytes);

        var changed = new ArrayList<>(fullSet());
        changed.add(new Evidence(meta("e3"), "memory:3", null));
        store.write(AGENT, changed);
        store.write(AGENT, fullSet());

        assertArrayEquals(bytes, Files.readAllBytes(store.agentDir(AGENT).resolve("commitment.jsonl")));
        assertEquals(new HashSet<>(fullSet()), new HashSet<>(store.read(AGENT)));
    }

    @Test
    void concurrentUpdatesBothPersist() throws Exception {
        store.write(AGENT, fullSet());
        var start = new CountDownLatch(1);
        var errors = Collections.synchronizedList(new ArrayList<Throwable>());
        var threads = new ArrayList<Thread>();
        for (var name : List.of("a", "b")) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 15; i++) {
                        var id = "x" + name + i;
                        var source = "memory:" + i;
                        store.update(AGENT, records -> {
                            records.add(new Evidence(meta(id), source, null));
                            return records;
                        });
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        start.countDown();
        for (var t : threads) t.join();

        assertEquals(List.of(), errors);
        var ids = store.read(AGENT).stream().map(OntologyRecord::id).toList();
        assertEquals(fullSet().size() + 30, ids.size(), "every update persisted: " + ids);
    }

    @Test
    void aWithdrawalThatRemovesNothingWritesNothing() throws Exception {
        store.write(AGENT, fullSet());
        var key = Files.readAttributes(store.agentDir(AGENT), BasicFileAttributes.class).fileKey();

        assertEquals(Set.of(), store.withdraw(AGENT, Set.of(50L)));
        assertEquals(key, Files.readAttributes(store.agentDir(AGENT), BasicFileAttributes.class).fileKey(),
                "the directory was not swapped");
    }

    @Test
    void aWithdrawalUpdatesTheFilesAndTheIndex() throws Exception {
        store.write(AGENT, fullSet());
        var index = store.index(AGENT);
        assertNotNull(index);
        assertEquals(Set.of("e1"), index.evidenceIdsBySource().get("memory:1"));

        var removed = store.withdraw(AGENT, Set.of(1L));

        // e1 backs p1, o1, m1; r1 and c1 then lose their endpoints or term; e2 loses its subject.
        assertEquals(Set.of("e1", "e2", "p1", "o1", "m1", "r1", "c1"), removed);
        assertEquals(List.of(), store.read(AGENT));
        index = store.index(AGENT);
        assertNotNull(index);
        assertTrue(index.byId().isEmpty());
        assertTrue(index.evidenceIdsBySource().isEmpty());
    }

    @Test
    void aWithdrawOrDeleteOnAnAgentWithNoGraphCreatesNothing() throws Exception {
        assertEquals(Set.of(), store.withdraw(AGENT, Set.of(1L)));
        assertEquals(Set.of(), store.withdrawAllMemoryEvidence(AGENT));
        store.deleteAgent(AGENT);
        assertFalse(Files.exists(store.root()));
    }

    @Test
    void retireAndRecordLineageTwiceWriteNothingTheSecondTime() throws Exception {
        store.write(AGENT, fullSet());
        var retirement = new Retirement(Instant.parse("2026-10-01T00:00:00Z"), "memory:2");

        var first = store.retire(AGENT, Map.of(1L, retirement));
        assertEquals(1, first.evidenceRetired());
        var retired = snapshot();
        store.failAfterFilesForTest(1);
        try {
            var again = store.retire(AGENT, Map.of(1L, retirement));
            assertEquals(0, again.evidenceRetired());
            assertEquals(0, again.lineageCleared());
        } finally {
            store.failAfterFilesForTest(-1);
        }
        assertEquals(retired, snapshot());

        var decision = new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), retirement);
        assertTrue(store.recordLineage(AGENT, Map.of(1L, decision)));
        var lineaged = snapshot();
        var e1 = (Evidence) store.read(AGENT).stream().filter(r -> r.id().equals("e1")).findFirst().orElseThrow();
        assertEquals(Lineage.UPDATE, e1.lineage());
        store.failAfterFilesForTest(1);
        try {
            assertFalse(store.recordLineage(AGENT, Map.of(1L, decision)));
            assertEquals(0, store.retire(AGENT, Map.of(1L, retirement)).evidenceRetired());
        } finally {
            store.failAfterFilesForTest(-1);
        }
        assertEquals(lineaged, snapshot());
    }

    @Test
    void aRetireOrLineageOnAnAgentWithNoGraphCreatesNothing() throws Exception {
        var retirement = new Retirement(Instant.parse("2026-10-01T00:00:00Z"), "memory:2");
        assertEquals(0, store.retire(AGENT, Map.of(1L, retirement)).evidenceRetired());
        assertFalse(store.recordLineage(AGENT, Map.of(1L, new LineageDecision(Lineage.RESTATEMENT, null, retirement))));
        assertFalse(Files.exists(store.root()));
    }

    @Test
    void deleteAgentRemovesTheDirectoryItsSiblingsAndTheIndex() throws Exception {
        store.write(AGENT, fullSet());
        Files.createDirectories(store.root().resolve(AGENT + ".staging"));
        store.deleteAgent(AGENT);
        assertEquals(List.of(), rootNames());
        assertNull(store.index(AGENT));
        assertEquals(Set.of(), Set.copyOf(store.agentIds()));
    }
}
