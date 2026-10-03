import com.google.gson.JsonObject;
import memory.MemoryStoreFactory;
import memory.ontology.OntologySchema;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import services.graphspike.Agreement;
import services.graphspike.Certifier;
import services.graphspike.Certifier.Adjudication;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.Decider;
import services.graphspike.GraphCases;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphSpikeHarness;
import services.graphspike.GraphSpikeHarness.DecisionModel;
import services.graphspike.GraphSpikeScorer;
import services.graphspike.GraphSpikeScorer.WrongRecord;
import services.graphspike.HeldOut;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static utils.GsonHolder.GSON;

/**
 * JCLAW-1356, JCLAW-1357: the harness over the committed set with a decider that answers gold, one that mis-types a term, one
 * that edits a memory, and the
 * held-out set. Concurrency 1 keeps every step on the test thread, inside the test's transaction.
 */
class GraphSpikeHarnessTest extends UnitTest {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final OntologySchema SCHEMA = OntologySchema.seed();

    private String agentId;

    @BeforeEach
    void setUp() {
        LuceneTestSync.closedForTest();
        agentId = String.valueOf(AgentService.create("graphspike-" + UUID.randomUUID().toString().substring(0, 8),
                "test-provider", "test-model").id);
    }

    @AfterEach
    void tearDown() {
        LuceneTestSync.release();
    }

    private static List<Case> committed() throws Exception {
        return GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH), SCHEMA);
    }

    /** The {@code noul} question's relation, matched against each sentence over its two quoted spans. */
    private static String relationOf(String rules, String from, String to) {
        for (var relation : ExtractionPipeline.SENTENCES.keySet()) {
            if (rules.contains(ExtractionPipeline.sentence(relation, from, to))) return relation;
        }
        throw new AssertionError("no relation sentence in " + rules);
    }

    /**
     * Answers gold, read back from the case whose text is {@code state.memory}: a term is its entity's type at 0.99
     * when the quoted span is that entity's mention; an overlap is the span naming an entity, else neither; a relation
     * question is yes 0.99 when the case labels that relation in that direction (either, if symmetric), else 0.01.
     */
    private static Decider gold(List<Case> cases) {
        var byText = new HashMap<String, Case>();
        cases.forEach(c -> byText.put(c.text(), c));
        return request -> {
            var c = byText.get(request.getAsJsonObject("state").get("memory").getAsString());
            var answers = new JsonObject();
            for (var q : request.getAsJsonObject("questions").entrySet()) {
                var question = q.getValue().getAsJsonObject();
                var rules = question.getAsJsonObject("instructions").get("rules").getAsString();
                var spans = QUOTED.matcher(rules).results().map(m -> m.group(1)).toList();
                if (q.getKey().startsWith("r")) {
                    var from = c.entityAt(spans.get(0));
                    var to = c.entityAt(spans.get(1));
                    var r = from == null || to == null ? null : c.relation(from.id(), to.id());
                    var yes = r != null && r.type().equals(relationOf(rules, spans.get(0), spans.get(1)));
                    var noul = new JsonObject();
                    noul.addProperty("type", "noul");
                    noul.addProperty("noul", yes ? 0.99 : 0.01);
                    answers.add(q.getKey(), noul);
                    continue;
                }
                var ids = question.getAsJsonObject("criteria").keySet();
                String choice;
                if (q.getKey().startsWith("o")) {
                    choice = ids.stream().filter(id -> {
                        var e = c.entityAt(id);
                        return e != null && id.equals(e.mention());
                    }).findFirst().orElse(ids.stream().filter(id -> c.entityAt(id) != null).findFirst()
                            .orElse(ExtractionPipeline.NEITHER));
                } else if (q.getKey().startsWith("m")) {
                    var e = c.entityAt(spans.getFirst());
                    choice = e != null && spans.getFirst().equals(e.mention()) ? e.type() : ExtractionPipeline.NOT_AN_ENTITY;
                } else {
                    throw new AssertionError("unexpected question " + q.getKey());
                }
                answers.add(q.getKey(), answer(ids, choice, 0.99));
            }
            var response = new JsonObject();
            response.add("answers", answers);
            return response;
        };
    }

    private static JsonObject answer(Set<String> ids, String choice, double p) {
        var probabilities = new JsonObject();
        ids.forEach(id -> probabilities.addProperty(id, id.equals(choice) ? p : (1 - p) / (ids.size() - 1)));
        var a = new JsonObject();
        a.addProperty("choice", choice);
        a.addProperty("confidence", p);
        a.add("probabilities", probabilities);
        return a;
    }

    private GraphSpikeHarness.Report run(List<Case> cases, Decider decider) {
        return GraphSpikeHarness.run(agentId, cases, SCHEMA, List.of(new DecisionModel("tev1", decider)), 2,
                Certifier.DEFAULT_RECALL_FLOOR, 1, List.of(), List.of());
    }

    /** Gold, except c015's "Larchmere House" (a Place on no relation) is typed Organization at 0.92. */
    private static Decider oneMistyped(List<Case> cases) {
        var golden = gold(cases);
        var target = cases.stream().filter(c -> c.id().equals("c015")).findFirst().orElseThrow().text();
        return request -> {
            var response = golden.decide(request);
            if (!request.getAsJsonObject("state").get("memory").getAsString().equals(target)) return response;
            for (var q : request.getAsJsonObject("questions").entrySet()) {
                var rules = q.getValue().getAsJsonObject().getAsJsonObject("instructions").get("rules").getAsString();
                var span = QUOTED.matcher(rules).results().map(m -> m.group(1)).findFirst().orElse("");
                if (q.getKey().startsWith("m") && span.equals("Larchmere House")) {
                    response.getAsJsonObject("answers").add(q.getKey(),
                            answer(q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet(), "Organization", 0.92));
                }
            }
            return response;
        };
    }

    private GraphSpikeHarness.Report runWithLabels(List<Case> cases, Decider decider, List<Adjudication> verdicts) {
        var blind = new HashSet<>(Agreement.blindSelection(cases));
        var second = cases.stream().filter(c -> blind.contains(c.id())).toList();
        return GraphSpikeHarness.run(agentId, cases, SCHEMA, List.of(new DecisionModel("tev1", decider)), 2,
                Certifier.DEFAULT_RECALL_FLOOR, 1, second, verdicts);
    }

    @Test
    void aWrongRecordAtTheCertifiedThresholdAwaitsAdjudicationThenCertifies() throws Exception {
        var cases = committed();
        var expected = new WrongRecord("c015", "term:Larchmere House:Organization", GraphSpikeScorer.TYPE);

        var pending = runWithLabels(cases, oneMistyped(cases), List.of());
        assertTrue(pending.agreement().complete(), pending.agreement().toString());
        var model = pending.models().getFirst();
        assertEquals(0.50, model.wrongRecordsAt());
        assertEquals(List.of(expected), model.wrongRecords());
        assertEquals(Certifier.PENDING_ADJUDICATION, model.certification().status(),
                model.certification().reasons().toString());
        assertEquals(List.of(expected), model.certification().unadjudicated());

        var verdict = new Adjudication("c015", expected.record(), Certifier.WRONG, "typed a house as a company");
        var certified = runWithLabels(cases, oneMistyped(cases), List.of(verdict)).models().getFirst();
        assertEquals(Certifier.CERTIFIED, certified.certification().status(),
                certified.certification().reasons().toString());
        assertEquals(0.50, certified.certification().threshold());
    }

    @Test
    void aGoldDeciderScoresEveryStageFullAndWalksToTheBottom() throws Exception {
        var cases = committed();
        var stored = new AtomicBoolean();
        var golden = gold(cases);
        var report = run(cases, request -> {
            if (!stored.get()) stored.set(Tx.run(() -> Memory.findByAgent(agentId)).size() == cases.size());
            return golden.decide(request);
        });

        assertTrue(stored.get(), "every case is a stored memory while the decisions run");
        assertEquals(List.of(), Tx.run(() -> Memory.findByAgent(agentId)), "every case memory deleted afterwards");
        var integrity = report.memoryIntegrity();
        assertEquals(cases.size(), integrity.checked());
        assertEquals(cases.size(), integrity.unchanged());
        assertEquals(cases.size(), integrity.deleted());
        assertEquals(List.of(), integrity.changed());

        int goldWithoutOperator = 0;
        for (var c : cases) {
            goldWithoutOperator += (int) (c.entities().stream().filter(e -> !e.noise() && !e.operator()).count()
                    + c.relations().stream().filter(r -> !r.noise()).count());
        }
        var model = report.models().getFirst();
        assertEquals(2, model.runs().size());
        for (var run : model.runs()) {
            var s = run.stages();
            assertEquals(1.0, s.typing().rate(), "typing");
            assertEquals(1.0, s.rejection().rate(), "rejection");
            assertEquals(1.0, s.relation().rate(), "relation");
            assertEquals(1.0, s.noRelation().rate(), "no-relation");
            assertTrue(s.overlap().total() == 0 || s.overlap().rate() == 1.0, "overlap " + s.overlap());
            assertEquals(1.0, s.resolution().bcubedPrecision(), "resolution precision");
            assertEquals(1.0, s.resolution().bcubedRecall(), "resolution recall");
            assertEquals(0, s.failures());
            assertEquals(0.50, run.walk().threshold(), "run " + run.run() + ": " + run.walk().failure());
            for (var p : run.grid()) {
                assertEquals(cases.size(), p.ruleWritten(), "one rule-written operator per case");
                assertEquals(goldWithoutOperator, p.gold());
            }
            var bottom = run.grid().getLast();
            assertEquals(0, bottom.wrong(), bottom.toString());
        }
        assertEquals(Certifier.PENDING_AGREEMENT, model.certification().status(), model.certification().reasons().toString());
        assertEquals(0.50, model.certification().threshold());
        assertFalse(report.agreement().complete());
    }

    @Test
    void aDeciderThatEditsAMemoryIsReportedAndCertifiesNothing() throws Exception {
        var cases = committed().subList(0, 6);
        var target = cases.get(2);
        var golden = gold(cases);
        var report = run(cases, request -> {
            var text = request.getAsJsonObject("state").get("memory").getAsString();
            if (text.equals(target.text())) {
                Tx.run(() -> {
                    for (var m : Memory.findByAgent(agentId)) {
                        if (m.text.equals(text)) {
                            m.text = text + " Edited.";
                            m.save();
                        }
                    }
                });
            }
            return golden.decide(request);
        });

        assertEquals(List.of(target.id()), report.memoryIntegrity().changed());
        assertEquals(cases.size() - 1, report.memoryIntegrity().unchanged());
        var certification = report.models().getFirst().certification();
        assertEquals(Certifier.NOT_CERTIFIED, certification.status());
        assertNull(certification.threshold());
        assertEquals(List.of(), Tx.run(() -> Memory.findByAgent(agentId)));
    }

    @Test
    void aHeldOutRunReportsNoIdTextOrSpanAndLeavesEveryRowAsItWas() throws Exception {
        var text = "The user works at Harborlight Analytics, which runs Kestrel CI for every release.";
        var store = MemoryStoreFactory.get();
        var memoryId = Tx.run(() -> store.storeDeferred(agentId, text, "fact", 0.5));
        var dir = Files.createTempDirectory("graph-heldout");
        var file = dir.resolve(HeldOut.FILE);
        try {
            var labelled = Map.of("cases", List.of(
                    Map.of("memoryId", Long.parseLong(memoryId), "labelled", true, "text", text,
                            "entities", List.of(
                                    Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                    Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization"),
                                    Map.of("id", "kestrel", "mention", "Kestrel CI", "type", "System")),
                            "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight"),
                                    Map.of("from", "harborlight", "type", "uses", "to", "kestrel"))),
                    Map.of("memoryId", 1, "labelled", false, "text", "unlabelled", "entities", List.of(),
                            "relations", List.of())));
            Files.writeString(file, GSON.toJson(labelled));
            var loaded = HeldOut.load(file, SCHEMA);
            var report = GraphSpikeHarness.runHeldOut(loaded, SCHEMA,
                    List.of(new DecisionModel("tev1", gold(loaded.cases().stream().map(HeldOut.HeldCase::labels).toList()))),
                    2, Certifier.DEFAULT_RECALL_FLOOR, 1);

            var json = GSON.toJson(report);
            for (var secret : List.of("memoryId", text, "Harborlight", "Kestrel", "The user", "term:", "rel:")) {
                assertFalse(json.contains(secret), "the report names " + secret + ": " + json);
            }
            assertEquals(1, report.cases());
            assertEquals(1, report.unlabelled());
            assertEquals(new GraphSpikeHarness.HeldOutIntegrity(1, 1, 1), report.memoryIntegrity());
            assertNull(report.models().getFirst().walk().threshold(), "five records cannot bound the wrong share");
            assertEquals(text, Tx.run(() -> Memory.<Memory>findById(Long.parseLong(memoryId))).text);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            Tx.run(() -> store.delete(memoryId));
        }
    }
}
