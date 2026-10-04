import com.google.gson.JsonObject;
import memory.MemoryStoreFactory;
import memory.ontology.OntologySchema;
import models.Memory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import services.grapheval.Agreement;
import services.grapheval.CandidateGenerator;
import services.grapheval.Certifier;
import services.grapheval.Certifier.Adjudication;
import services.grapheval.EvalProgress;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.GraphCases;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphEvalHarness;
import services.grapheval.GraphEvalHarness.DecisionModel;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.HeldOut;
import services.grapheval.StageScorer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

import static utils.GsonHolder.GSON;

/**
 * JCLAW-1356, JCLAW-1357: the harness over the committed set with a decider that answers gold, one that mis-types a term, one
 * that edits a memory, and the
 * held-out set. Concurrency 1 keeps every step on the test thread, inside the test's transaction.
 */
class GraphEvalHarnessTest extends UnitTest {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final OntologySchema SCHEMA = OntologySchema.seed();

    private String agentId;

    @BeforeEach
    void setUp() {
        LuceneTestSync.closedForTest();
        agentId = String.valueOf(AgentService.create("grapheval-" + UUID.randomUUID().toString().substring(0, 8),
                "test-provider", "test-model").id);
    }

    @AfterEach
    void tearDown() {
        LuceneTestSync.release();
    }

    private static List<Case> committed() throws Exception {
        return GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH), SCHEMA);
    }

    private static @Nullable String owner() {
        try {
            return GraphCases.ownerName(Files.readString(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code noul} question's relation, matched against each schema gloss over its two quoted spans. */
    private static String relationOf(String rules, String from, String to) {
        for (var relation : SCHEMA.relations().keySet()) {
            if (rules.contains(ExtractionPipeline.gloss(SCHEMA, relation, from, to))) return relation;
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
                if (q.getKey().startsWith("n") || q.getKey().startsWith("e")) {
                    var noul = new JsonObject();
                    noul.addProperty("type", "noul");
                    noul.addProperty("noul", 0.01);
                    answers.add(q.getKey(), noul);
                    continue;
                }
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
                } else if (q.getKey().startsWith("t")) {
                    choice = ExtractionPipeline.PAST;
                } else if (q.getKey().startsWith("s")) {
                    choice = ExtractionPipeline.HOLDS;
                } else if (q.getKey().startsWith("d")) {
                    choice = ExtractionPipeline.NEITHER;
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

    private GraphEvalHarness.Report run(List<Case> cases, Decider decider) {
        return GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", decider)), 2,
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

    private GraphEvalHarness.Report runWithLabels(List<Case> cases, Decider decider, List<Adjudication> verdicts) {
        var blind = new HashSet<>(Agreement.blindSelection(cases));
        var second = cases.stream().filter(c -> blind.contains(c.id())).toList();
        return GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", decider)), 2,
                Certifier.DEFAULT_RECALL_FLOOR, 1, second, verdicts);
    }

    @Test
    void aWrongRecordAtTheCertifiedThresholdAwaitsAdjudicationThenCertifies() throws Exception {
        var cases = committed();
        var expected = new WrongRecord("c015", "term:Larchmere House:Organization", GraphEvalScorer.TYPE);

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
    void progressPrintsOnePassLinePerModelAndRunInCompletionOrderAndLeavesTheReportAlone() throws Exception {
        var cases = committed().subList(0, 6);
        var events = new ArrayList<JsonObject>();
        var progress = new EvalProgress(events::add);
        var beat = new AtomicBoolean();
        var golden = gold(cases);
        Decider beating = request -> {
            if (beat.compareAndSet(false, true)) progress.heartbeat();
            return golden.decide(request);
        };
        Decider broken = _ -> {
            throw new IllegalStateException("cold load");
        };
        Function<EvalProgress, GraphEvalHarness.Report> run = p -> GraphEvalHarness.run(agentId, cases, owner(), SCHEMA,
                List.of(new DecisionModel("tev1", beating), new DecisionModel("tev2", broken)), 2,
                Certifier.DEFAULT_RECALL_FLOOR, 1, List.of(), List.of(), p);

        var streamed = run.apply(progress);
        var quiet = run.apply(EvalProgress.none());

        assertEquals(GSON.toJson(quiet), GSON.toJson(streamed));
        var beats = events.stream().filter(e -> e.get("event").getAsString().equals(EvalProgress.HEARTBEAT)).toList();
        assertEquals(1, beats.size(), events.toString());
        var heartbeat = beats.getFirst();
        assertEquals("tev1", heartbeat.get("model").getAsString());
        assertEquals(1, heartbeat.get("run").getAsInt());
        assertEquals(0, heartbeat.get("done").getAsInt());
        var passes = events.stream().filter(e -> e.get("event").getAsString().equals(EvalProgress.PASS)).toList();
        assertEquals(List.of("tev1 1/2", "tev2 1/2", "tev1 2/2", "tev2 2/2"), passes.stream()
                .map(e -> e.get("model").getAsString() + " " + e.get("run").getAsInt() + "/" + e.get("runs").getAsInt())
                .toList());
        for (var pass : passes) {
            assertEquals(cases.size(), pass.get("done").getAsInt(), pass.toString());
            assertEquals(cases.size(), pass.get("cases").getAsInt(), pass.toString());
            assertTrue(pass.get("seconds").getAsDouble() >= 0, pass.toString());
        }
        assertEquals(0, passes.getFirst().get("failed").getAsInt());
        int stageFailures = streamed.models().get(1).runs().getFirst().stages().failures();
        assertTrue(stageFailures > 0 && passes.get(1).get("failed").getAsInt() >= stageFailures, passes.toString());
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
        assertEquals(SCHEMA.fingerprint(), report.schema(), "the report names the schema it certified under");
        assertEquals(ExtractionPipeline.fingerprint(SCHEMA), report.extraction(), "and the extraction it asked with");
        assertFalse(report.pairFilter());
        var integrity = report.memoryIntegrity();
        assertEquals(cases.size(), integrity.checked());
        assertEquals(cases.size(), integrity.unchanged());
        assertEquals(cases.size(), integrity.deleted());
        assertEquals(List.of(), integrity.changed());

        int goldWithoutOperator = 0;
        int ruleWritten = 0;
        for (var c : cases) {
            goldWithoutOperator += (int) (c.entities().stream().filter(e -> !e.noise() && !e.ruleWritten()).count()
                    + c.relations().stream().filter(r -> !r.noise()).count());
            if (c.text().startsWith("The user") || CandidateGenerator.subjectless(c.text())) ruleWritten++;
        }
        var model = report.models().getFirst();
        assertEquals(2, model.runs().size());
        for (var run : model.runs()) {
            var s = run.stages();
            assertEquals(1.0, s.typing().rate(), "typing");
            assertEquals(1.0, s.rejection().rate(), "rejection");
            assertEquals(1.0, s.relation().rate(), "relation");
            assertEquals(1.0, s.noRelation().rate(), "no-relation");
            assertEquals(1.0, s.resolution().bcubedPrecision(), "resolution precision");
            assertEquals(1.0, s.resolution().bcubedRecall(), "resolution recall");
            assertEquals(0, s.failures());
            assertEquals(0.50, run.walk().threshold(), "run " + run.run() + ": " + run.walk().failure());
            for (var p : run.grid()) {
                assertEquals(ruleWritten, p.ruleWritten(), "one per \"The user\" or subjectless case");
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
    void aGoldDeciderSettlesEveryOverlap() {
        // The committed cases raise no overlap, so this one is built to: a preference object around a name.
        var c = new Case("o1", List.of("plain"), "The user prefers Kestrel CI pipelines.",
                List.of(GraphCases.Entity.of("operator", "The user", "Person"),
                        GraphCases.Entity.of("kestrel", "Kestrel CI", "System")),
                List.of(), List.of());
        var report = run(List.of(c), gold(List.of(c)));
        for (var run : report.models().getFirst().runs()) {
            var overlap = run.stages().overlap();
            assertTrue(overlap.total() > 0, "the case raises an overlap");
            assertEquals(1.0, overlap.rate(), "overlap " + overlap);
        }
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

    private GraphEvalHarness.Report runOnce(List<Case> cases, Decider decider) {
        var blind = new HashSet<>(Agreement.blindSelection(cases));
        var second = cases.stream().filter(c -> blind.contains(c.id())).toList();
        return GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", decider)), 1,
                Certifier.DEFAULT_RECALL_FLOOR, 1, second, List.of());
    }

    private int relationQuestions(List<Case> cases, boolean pairFilter, List<GraphEvalHarness.Report> reports) {
        var golden = gold(cases);
        var asked = new AtomicInteger();
        Decider counting = request -> {
            request.getAsJsonObject("questions").keySet().forEach(k -> {
                if (k.startsWith("r")) asked.incrementAndGet();
            });
            return golden.decide(request);
        };
        reports.add(GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", counting)),
                1, Certifier.DEFAULT_RECALL_FLOOR, 1, List.of(), List.of(), EvalProgress.none(), pairFilter));
        return asked.get();
    }

    @Test
    void thePairFilterAsksFewerEndToEndRelationQuestionsAndIsStamped() throws Exception {
        var clause = Pattern.compile(ExtractionPipeline.CLAUSE_BOUNDARY);
        var spanning = committed().stream().filter(c -> clause.matcher(c.text()).results()
                .anyMatch(m -> m.end() < c.text().strip().length() - 1)).limit(3).toList();
        assertFalse(spanning.isEmpty(), "the committed set has a memory of two clauses");
        var reports = new ArrayList<GraphEvalHarness.Report>();
        int unfiltered = relationQuestions(spanning, false, reports);
        int filtered = relationQuestions(spanning, true, reports);
        // The gold-fed relate stage is unfiltered by design, so the whole difference is the end-to-end run's.
        assertTrue(filtered < unfiltered, filtered + " vs " + unfiltered);
        var report = reports.getLast();
        assertTrue(report.pairFilter());
        assertFalse(reports.getFirst().pairFilter());
        assertEquals(ExtractionPipeline.fingerprint(SCHEMA), report.extraction());
    }

    @Test
    void aSingleRunIsSpotCheckedAndCertifiesWhenEveryAnswerRepeats() throws Exception {
        var cases = committed();
        var model = runOnce(cases, gold(cases)).models().getFirst();
        assertEquals(1, model.runs().size());
        var spot = model.spotCheck();
        assertNotNull(spot, "a single run carries its spot-check");
        int stride = GraphEvalHarness.SPOT_CHECK_STRIDE;
        assertEquals((cases.size() + stride - 1) / stride, spot.cases());
        assertTrue(spot.decisions() > 0);
        assertEquals(0, spot.differing());
        assertEquals(Certifier.CERTIFIED, model.certification().status(), model.certification().reasons().toString());
    }

    @Test
    void aSpotCheckThatHearsADifferentAnswerRefusesTheModel() throws Exception {
        var cases = committed();
        var golden = gold(cases);
        var first = cases.getFirst().text();
        var calls = new AtomicInteger();
        // The first case is always in the sample; every asking of it answers with a slightly different probability.
        Decider drifting = request -> {
            var response = golden.decide(request);
            if (!request.getAsJsonObject("state").get("memory").getAsString().equals(first)) return response;
            double p = 0.99 - 1e-6 * calls.incrementAndGet();
            for (var e : response.getAsJsonObject("answers").entrySet()) {
                var a = e.getValue().getAsJsonObject();
                a.addProperty("confidence", p);
                a.getAsJsonObject("probabilities").addProperty(a.get("choice").getAsString(), p);
            }
            return response;
        };
        var model = runOnce(cases, drifting).models().getFirst();
        var spot = model.spotCheck();
        assertNotNull(spot);
        assertTrue(spot.differing() > 0, spot.toString());
        assertEquals(Certifier.NOT_CERTIFIED, model.certification().status());
        assertTrue(model.certification().reasons().stream().anyMatch(r -> r.contains("two full runs")),
                model.certification().reasons().toString());
    }

    @Test
    void aHeldOutOwnerNamedInTheTextJoinsTheOperatorOnlyWhenTheOwnerIsKnown() throws Exception {
        var named = "Ada Pell works at Harborlight Analytics, which runs Kestrel CI for every release.";
        var legacy = "The user works at Harborlight Analytics and reviews every release there.";
        var store = MemoryStoreFactory.get();
        var namedId = Tx.run(() -> store.storeDeferred(agentId, named, "fact", 0.5));
        var legacyId = Tx.run(() -> store.storeDeferred(agentId, legacy, "fact", 0.5));
        var dir = Files.createTempDirectory("graph-heldout-owner");
        var file = dir.resolve(HeldOut.FILE);
        try {
            Files.writeString(file, GSON.toJson(Map.of("cases", List.of(
                    Map.of("memoryId", Long.parseLong(namedId), "labelled", true, "text", named,
                            "entities", List.of(Map.of("id", "operator", "mention", "Ada Pell", "type", "Person"),
                                    Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization")),
                            "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight"))),
                    Map.of("memoryId", Long.parseLong(legacyId), "labelled", true, "text", legacy,
                            "entities", List.of(Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                    Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization")),
                            "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight")))))));
            var loaded = HeldOut.load(file, SCHEMA);
            var decider = gold(loaded.cases().stream().map(HeldOut.HeldCase::labels).toList());
            Function<@Nullable String, StageScorer.Resolution> resolution = owner -> GraphEvalHarness.runHeldOut(loaded,
                            owner, SCHEMA, List.of(new DecisionModel("tev1", decider)), 1, Certifier.DEFAULT_RECALL_FLOOR, 1)
                    .models().getFirst().runs().getFirst().stages().resolution();

            var known = resolution.apply("Ada Pell");
            assertEquals(known.goldEntities(), known.clusters(), "the owner's name and \"The user\" are one operator");
            assertEquals(1.0, known.pairwiseRecall());
            var unknown = resolution.apply(null);
            assertEquals(unknown.goldEntities() + 1, unknown.clusters(), "without the owner the operator splits in two");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            Tx.run(() -> store.delete(namedId));
            Tx.run(() -> store.delete(legacyId));
        }
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
            var events = new ArrayList<JsonObject>();
            var progress = new EvalProgress(events::add);
            var golden = gold(loaded.cases().stream().map(HeldOut.HeldCase::labels).toList());
            Decider beating = request -> {
                progress.heartbeat();
                return golden.decide(request);
            };
            var report = GraphEvalHarness.runHeldOut(loaded, null, SCHEMA, List.of(new DecisionModel("tev1", beating)),
                    2, Certifier.DEFAULT_RECALL_FLOOR, 1, progress);

            assertTrue(events.stream().anyMatch(e -> e.get("event").getAsString().equals(EvalProgress.HEARTBEAT)),
                    events.toString());
            assertEquals(2, events.stream().filter(e -> e.get("event").getAsString().equals(EvalProgress.PASS)).count(),
                    events.toString());
            var json = GSON.toJson(report) + events;
            for (var secret : List.of("memoryId", text, "Harborlight", "Kestrel", "The user", "term:", "rel:")) {
                assertFalse(json.contains(secret), "the report names " + secret + ": " + json);
            }
            assertEquals(1, report.cases());
            assertEquals(1, report.unlabelled());
            assertEquals(new GraphEvalHarness.HeldOutIntegrity(1, 1, 1), report.memoryIntegrity());
            assertNull(report.models().getFirst().walk().threshold(), "five records cannot bound the wrong share");
            assertEquals(text, Tx.run(() -> Memory.<Memory>findById(Long.parseLong(memoryId))).text);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            Tx.run(() -> store.delete(memoryId));
        }
    }
}
