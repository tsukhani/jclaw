import memory.graph.GraphStore;
import memory.graph.RunLedger;
import memory.graph.RunLedger.DecisionEntry;
import memory.graph.RunLedger.Entry;
import memory.graph.RunLedger.Outcome;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator;
import services.grapheval.EntityResolver;
import services.grapheval.EntityResolver.Mention;
import services.grapheval.EntityResolver.Settings;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.RunEntries;
import services.grapheval.StatementRecords;
import services.grapheval.Statements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** JCLAW-1371: the run ledger's canonical format, run ids, and what a recorded run leaves in the graph. */
class RunLedgerTest extends UnitTest {

    /** StatementRecords mints its Evidence under agent 0, so the composed graph is agent 0's. */
    private static final long AGENT = 0L;
    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final String TEXT = "The user works at Harborlight Analytics.";
    private static final Instant AT = Instant.parse("2026-10-03T12:00:00Z");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3);
    private static final String DIGEST = "sha256:4f1c";
    private static final String CERT = "cert@4d2c9a7b1e30";

    @TempDir
    Path tmp;

    private GraphStore store;

    @BeforeEach
    void setup() {
        store = new GraphStore(tmp.resolve("memory-graph"));
    }

    // ---- the canonical line ----

    static Entry example() {
        var term = new LinkedHashMap<String, Double>();
        term.put("Person", 0.0);
        term.put("Organization", 0.01);
        term.put("Project", 0.01);
        term.put("System", 0.93);
        term.put("Artifact", 0.03);
        term.put("Place", 0.0);
        term.put("Event", 0.0);
        term.put("Topic", 0.01);
        term.put("not_an_entity", 0.01);
        var lineage = new LinkedHashMap<String, Double>();
        lineage.put("update", 0.91);
        lineage.put("restatement", 0.06);
        lineage.put("correction", 0.03);
        return new Entry("run@7e3f0a1b2c4d", 7, "memory:506", "sha256:...", Outcome.WRITTEN, "tev1", "sha256:...",
                CERT, "v3@...", "x@...", false, List.of(
                        new DecisionEntry("term", "Kestrel CI", null, term, null, null, false),
                        new DecisionEntry("relation", "Avery Lin -> Kestrel CI", "uses", Map.of(), 0.91, null, false),
                        new DecisionEntry("lineage", "506 <- 120", null, lineage, null, null, false),
                        new DecisionEntry("status", "Avery Lin -uses-> Kestrel CI", null, Map.of(), null,
                                "exceeds context", false)));
    }

    @Test
    void theTicketsExampleEncodesToItsPinnedBytesAndRoundTrips() {
        var pinned = "{\"runId\":\"run@7e3f0a1b2c4d\",\"agentId\":7,\"source\":\"memory:506\",\"text\":\"sha256:...\","
                + "\"outcome\":\"written\",\"model\":\"tev1\",\"digest\":\"sha256:...\","
                + "\"certificate\":\"cert@4d2c9a7b1e30\",\"schema\":\"v3@...\",\"extraction\":\"x@...\","
                + "\"retracted\":false,\"decisions\":["
                + "{\"stage\":\"term\",\"subject\":\"Kestrel CI\",\"probabilities\":{\"Artifact\":0.03,\"Event\":0.0,"
                + "\"Organization\":0.01,\"Person\":0.0,\"Place\":0.0,\"Project\":0.01,\"System\":0.93,\"Topic\":0.01,"
                + "\"not_an_entity\":0.01}},"
                + "{\"stage\":\"relation\",\"subject\":\"Avery Lin -> Kestrel CI\",\"relation\":\"uses\",\"yes\":0.91},"
                + "{\"stage\":\"lineage\",\"subject\":\"506 <- 120\","
                + "\"probabilities\":{\"correction\":0.03,\"restatement\":0.06,\"update\":0.91}},"
                + "{\"stage\":\"status\",\"subject\":\"Avery Lin -uses-> Kestrel CI\",\"failure\":\"exceeds context\"}"
                + "]}\n";
        var document = RunLedger.document(List.of(example()));
        assertEquals(pinned, document);
        var parsed = RunLedger.parse(document, "ledger");
        assertEquals(List.of(example()), parsed);
        assertEquals(document, RunLedger.document(parsed));
    }

    @Test
    void entriesAreSortedByRunIdAndAnOperatorDecisionIsWrittenAsTrue() {
        var operator = new Entry("run@000000000001", 7, "memory:1", "sha256:00", Outcome.EMPTY, "tev1", DIGEST, CERT,
                "v3@0", "x@0", true, List.of(new DecisionEntry("term", "The user", null, Map.of(), null, null, true)));
        var document = RunLedger.document(List.of(example(), operator));
        assertTrue(document.startsWith("{\"runId\":\"run@000000000001\""), document);
        assertTrue(document.contains("{\"stage\":\"term\",\"subject\":\"The user\",\"operator\":true}"), document);
        assertTrue(document.contains("\"outcome\":\"empty\""), document);
        assertEquals(List.of(operator, example()), RunLedger.parse(document, "ledger"));
        assertEquals("", RunLedger.document(List.of()));
        assertEquals(List.of(), RunLedger.parse("", "ledger"));
    }

    @Test
    void aMalformedLineIsRefusedNamingItsFileAndLine() {
        var line = RunLedger.encode(example());
        for (var bad : List.of(
                line.replace("\"model\":\"tev1\",", ""),
                line.replace("\"model\":\"tev1\",", "\"model\":\"tev1\",\"extra\":1,"),
                line.replace("\"yes\":0.91", "\"yes\":0.91,\"failure\":\"x\""),
                line.replace("\"failure\":\"exceeds context\"", "\"operator\":false"),
                line.replace("\"outcome\":\"written\"", "\"outcome\":\"Written\""),
                line + " trailing")) {
            var e = assertThrows(IllegalArgumentException.class, () -> RunLedger.parse("\n" + bad + "\n", "7/ledger"));
            assertTrue(e.getMessage().startsWith("7/ledger:2: "), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionEntry("term", "x", null, Map.of(), null, null, false));
    }

    // ---- run ids and recorded runs ----

    private static CaseRun extract(String text) {
        var decider = ExtractionPipelineTest.scripted(new CopyOnWriteArrayList<>(),
                Map.of("Harborlight Analytics", "Organization"),
                Map.of("The user works_at Harborlight Analytics", 0.97));
        return ExtractionPipeline.run(SCHEMA, "c", text, CandidateGenerator.generate(text), "tev1", decider);
    }

    /** The run's terms resolved and grounded, then its statements recorded, every Evidence under {@code runId}. */
    private static List<OntologyRecord> compose(List<OntologyRecord> graph, CaseRun run, long memoryId,
            String runId) {
        var outcome = Statements.at(run, 0.5, Statements.Classes.all(0.5));
        var operators = new HashMap<String, Boolean>();
        run.candidates().forEach(c -> operators.merge(c.span(), c.operator(), Boolean::logicalOr));
        var settings = new Settings(SCHEMA, null, null, null, null);
        var records = graph;
        var termIds = new TreeMap<String, String>();
        for (var t : outcome.terms()) {
            var mention = new Mention(t.span(), t.type(), operators.getOrDefault(t.span(), false), memoryId,
                    MemoryAuthorType.HUMAN_TURN, AT, ANCHOR, run.text(), runId);
            var resolved = EntityResolver.resolve(AGENT, records, mention, settings);
            records = resolved.records();
            termIds.put(t.span(), resolved.termId());
        }
        var facts = StatementRecords.fromStatements(outcome, outcome.relations(), outcome.denials(),
                span -> Objects.requireNonNull(termIds.get(span)));
        return StatementRecords.add(SCHEMA, records,
                new StatementRecords.Source(memoryId, AT, ANCHOR, MemoryAuthorType.HUMAN_TURN, runId),
                facts.terms(), facts.claims());
    }

    private Entry record(long memoryId, String text) throws IOException {
        var run = extract(text);
        var entry = RunEntries.entry(AGENT, memoryId, text, run, Outcome.WRITTEN, "tev1", DIGEST, CERT);
        store.recordRun(AGENT, entry, records -> compose(records, run, memoryId, entry.runId()));
        return entry;
    }

    private Map<String, String> files() throws IOException {
        var out = new TreeMap<String, String>();
        try (var s = Files.list(store.agentDir(AGENT))) {
            for (var f : s.toList()) out.put(f.getFileName().toString(), Files.readString(f));
        }
        return out;
    }

    @Test
    void anUnchangedMemoryExtractedTwiceIsOneRunAndEveryFileIsByteIdentical() throws Exception {
        var first = record(506, TEXT);
        var once = files();
        var second = record(506, TEXT);

        assertEquals(first.runId(), second.runId());
        assertEquals(first, second);
        assertEquals(once, files());
        assertTrue(once.containsKey(RunLedger.FILE_NAME), once.keySet().toString());
        assertEquals(1, store.ledger(AGENT).size());
        assertTrue(first.runId().matches("run@[0-9a-f]{12}"), first.runId());
        assertEquals(RunEntries.textHash(TEXT), first.text());
        assertTrue(first.text().matches("sha256:[0-9a-f]{64}"), first.text());
        assertEquals(SCHEMA.fingerprint(), first.schema());
        assertEquals(ExtractionPipeline.fingerprint(SCHEMA), first.extraction());
        assertEquals(extract(TEXT).decisions().size(), first.decisions().size(), "one entry per decision, in order");
        var operator = first.decisions().getFirst();
        assertTrue(operator.operator(), operator.toString());
        var relation = first.decisions().stream().filter(d -> d.stage().equals(ExtractionPipeline.RELATION))
                .findFirst().orElseThrow();
        assertEquals("works_at", relation.relation());
        assertEquals(0.97, relation.yes(), 1e-9);
    }

    @Test
    void aChangedTextOrAnyStampMintsANewRunId() {
        var base = RunEntries.runId(7, "memory:506", TEXT, DIGEST, CERT, "v3@a", "x@a");
        assertEquals(base, RunEntries.runId(7, "memory:506", TEXT, DIGEST, CERT, "v3@a", "x@a"));
        for (var other : List.of(
                RunEntries.runId(7, "memory:506", TEXT + " ", DIGEST, CERT, "v3@a", "x@a"),
                RunEntries.runId(8, "memory:506", TEXT, DIGEST, CERT, "v3@a", "x@a"),
                RunEntries.runId(7, "memory:507", TEXT, DIGEST, CERT, "v3@a", "x@a"),
                RunEntries.runId(7, "memory:506", TEXT, "sha256:4f1d", CERT, "v3@a", "x@a"),
                RunEntries.runId(7, "memory:506", TEXT, DIGEST, "cert@000000000000", "v3@a", "x@a"),
                RunEntries.runId(7, "memory:506", TEXT, DIGEST, CERT, "v3@b", "x@a"),
                RunEntries.runId(7, "memory:506", TEXT, DIGEST, CERT, "v3@a", "x@b"))) {
            assertNotEquals(base, other);
        }
        var changed = "The user works at Harborlight Analytics now.";
        assertNotEquals(RunEntries.entry(AGENT, 506, TEXT, extract(TEXT), Outcome.WRITTEN, "tev1", DIGEST, CERT).runId(),
                RunEntries.entry(AGENT, 506, changed, extract(changed), Outcome.WRITTEN, "tev1", DIGEST, CERT).runId());
    }

    @Test
    void eachOutcomeWritesExactlyOneEntryAndOnlyAWrittenRunChangesTheRecords() throws Exception {
        record(500, TEXT);
        var families = files();
        families.remove(RunLedger.FILE_NAME);
        long memory = 501;
        for (var outcome : List.of(Outcome.EMPTY, Outcome.ABSTAINED, Outcome.FAILED)) {
            var text = "Memory " + memory + " says nothing.";
            var entry = RunEntries.entry(AGENT, memory, text, extract(text), outcome, "tev1", DIGEST, CERT);
            store.recordRun(AGENT, entry, records -> records);
            var now = files();
            now.remove(RunLedger.FILE_NAME);
            assertEquals(families, now, outcome + " leaves the family files byte-identical");
            assertEquals(1, store.ledger(AGENT).stream().filter(e -> e.runId().equals(entry.runId())).count());
            memory++;
        }
        assertEquals(4, store.ledger(AGENT).size());
        assertEquals(EnumSet.allOf(Outcome.class),
                EnumSet.copyOf(store.ledger(AGENT).stream().map(Entry::outcome).toList()));

        var text = "Memory 600 says nothing.";
        var empty = RunEntries.entry(AGENT, 600, text, extract(text), Outcome.EMPTY, "tev1", DIGEST, CERT);
        var before = files();
        assertThrows(IllegalArgumentException.class, () -> store.recordRun(AGENT, empty,
                records -> compose(records, extract(TEXT), 600, empty.runId())));
        assertEquals(before, files(), "a non-written run that changes records writes nothing");
    }

    @Test
    void everyEvidenceAWrittenRunAddsCarriesItsRunIdGroundingIncluded() throws Exception {
        var entry = record(506, TEXT);
        var records = store.read(AGENT);
        var evidence = records.stream().filter(r -> r instanceof Evidence).map(r -> (Evidence) r).toList();
        assertFalse(evidence.isEmpty());
        assertTrue(records.stream().anyMatch(r -> r instanceof Mapping), "the terms were grounded");
        assertTrue(evidence.stream().anyMatch(e -> e.subjectId() != null && e.subjectId().startsWith("rel:")),
                "the relation's claim is there");
        for (var e : evidence) assertEquals(entry.runId(), e.runId(), e.id());
    }

    @Test
    void aWrittenRunAddingEvidenceWithoutItsRunIdIsRefusedAndWritesNothing() throws Exception {
        record(506, TEXT);
        var before = files();
        var text = "Avery Lin uses Kestrel CI.";
        var entry = RunEntries.entry(AGENT, 507, text, extract(text), Outcome.WRITTEN, "tev1", DIGEST, CERT);
        for (var stray : new String[] {null, "run@000000000000"}) {
            var e = assertThrows(IllegalArgumentException.class, () -> store.recordRun(AGENT, entry, records -> {
                var out = new ArrayList<>(records);
                out.add(new Evidence(Meta.fresh("e-stray", AGENT, Tier.TENTATIVE), "memory:507", null, null, null,
                        stray, null, null, null, null, null, null, null, null, null, null));
                return out;
            }));
            assertTrue(e.getMessage().contains("e-stray"), e.getMessage());
            assertEquals(before, files());
        }
    }
}
