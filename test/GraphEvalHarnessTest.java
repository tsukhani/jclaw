import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.MemoryStoreFactory;
import memory.TemporalExpressions;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologySchema;
import models.Memory;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import services.grapheval.Adjudications;
import services.grapheval.Agreement;
import services.grapheval.CandidateGenerator;
import services.grapheval.CertificateDocument;
import services.grapheval.CertificationSplit;
import services.grapheval.Certifier;
import services.grapheval.Configuration;
import services.grapheval.EvalProgress;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.GateBounds;
import services.grapheval.GraphCases;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphEvalHarness;
import services.grapheval.GraphEvalHarness.DecisionModel;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.HeldOut;
import services.grapheval.Sequences;
import services.grapheval.SplitUses;
import services.grapheval.StageScorer;
import services.grapheval.StoredRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * The committed set made certifiable: each {@code holds_view_on} labelled with the valence its preference frame
     * gives, and one {@code unasserted} relation added on an unlabelled pair per case, so the trap set is not empty.
     */
    private static List<Case> certifiable() throws Exception {
        var known = owner() == null ? List.<String>of() : List.of(owner());
        var out = new ArrayList<Case>();
        for (var c : committed()) {
            var frames = new HashMap<String, String>();
            for (var candidate : CandidateGenerator.generate(c.text(), known)) {
                var frame = candidate.frame();
                if (frame != null) frames.put(candidate.span(), frame.valence().name().toLowerCase(Locale.ROOT));
            }
            var relations = new ArrayList<GraphCases.Relation>();
            for (var r : c.relations()) {
                var to = c.entity(r.to());
                var valence = r.type().equals("holds_view_on") && to != null ? frames.get(to.span()) : r.valence();
                relations.add(new GraphCases.Relation(r.from(), r.type(), r.to(), r.status(), r.valid(), valence,
                        r.noise()));
            }
            var trap = unassertedPair(c);
            if (trap != null) relations.add(trap);
            out.add(new Case(c.id(), c.tags(), c.text(), c.entities(), relations, c.negatives(), c.capturedAt(),
                    c.dates()));
        }
        return out;
    }

    private static GraphCases.@Nullable Relation unassertedPair(Case c) {
        for (var from : c.entities()) {
            for (var to : c.entities()) {
                if (from == to || c.relations().stream().anyMatch(r -> r.reaches(from.id()) && r.reaches(to.id()))) {
                    continue;
                }
                for (var type : SCHEMA.relations().keySet()) {
                    if (SCHEMA.allows(type, from.type(), to.type())) {
                        return new GraphCases.Relation(from.id(), type, to.id(), GraphCases.UNASSERTED, null, null,
                                false);
                    }
                }
            }
        }
        return null;
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
     * is yes 0.99 on a holds or admissible ended label of that type (either way round, if symmetric), a negation yes on
     * a denial of it; status, slot, tense and occurs answer what the label says.
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
                var key = q.getKey();
                if (key.startsWith("r") || key.startsWith("n") || key.startsWith("e")) {
                    boolean yes;
                    if (key.startsWith("e")) {
                        var event = c.entityAt(spans.get(0));
                        var label = dateLabel(c, spans.get(1));
                        yes = event != null && event.occurs() != null && label != null
                                && label.equals(EdtfInterval.parse(event.occurs()).toString());
                    } else {
                        var r = labelled(c, rules, spans.get(0), spans.get(1), key.startsWith("n"));
                        yes = r != null && (r.denied() || c.holdsOrEnded(r, SCHEMA));
                    }
                    var noul = new JsonObject();
                    noul.addProperty("type", "noul");
                    noul.addProperty("noul", yes ? 0.99 : 0.01);
                    answers.add(key, noul);
                    continue;
                }
                var ids = question.getAsJsonObject("criteria").keySet();
                String choice;
                if (key.startsWith("o")) {
                    choice = ids.stream().filter(id -> {
                        var e = c.entityAt(id);
                        return e != null && id.equals(e.mention());
                    }).findFirst().orElse(ids.stream().filter(id -> c.entityAt(id) != null).findFirst()
                            .orElse(ExtractionPipeline.NEITHER));
                } else if (key.startsWith("m")) {
                    var e = c.entityAt(spans.getFirst());
                    choice = e != null && spans.getFirst().equals(e.mention()) ? e.type() : ExtractionPipeline.NOT_AN_ENTITY;
                } else if (key.startsWith("t")) {
                    choice = tense(c, spans.getFirst());
                } else if (key.startsWith("s")) {
                    var r = labelled(c, rules, spans.get(0), spans.get(1), false);
                    var status = r == null ? ExtractionPipeline.UNSTATED : c.scoredStatus(r, SCHEMA);
                    choice = status.equals(ExtractionPipeline.HOLDS) || status.equals(ExtractionPipeline.ENDED) ? status
                            : ExtractionPipeline.UNSTATED;
                } else if (key.startsWith("d")) {
                    var r = labelled(c, rules, spans.get(1), spans.get(2), false);
                    var label = dateLabel(c, spans.get(0));
                    choice = r == null || r.valid() == null || label == null ? ExtractionPipeline.NEITHER
                            : slot(EdtfInterval.parse(label), EdtfInterval.parse(r.valid()));
                } else {
                    throw new AssertionError("unexpected question " + key);
                }
                answers.add(key, answer(ids, choice, 0.99));
            }
            var response = new JsonObject();
            response.add("answers", answers);
            return response;
        };
    }

    /** The positive (or, when {@code denied}, the denied) label of the question's relation between two spans. */
    private static GraphCases.@Nullable Relation labelled(Case c, String rules, String fromSpan, String toSpan,
                                                         boolean denied) {
        var from = c.entityAt(fromSpan);
        var to = c.entityAt(toSpan);
        if (from == null || to == null) return null;
        var r = denied ? c.denied(from.id(), to.id(), SCHEMA.symmetricSet())
                : c.positive(from.id(), to.id(), SCHEMA.symmetricSet());
        return r != null && r.type().equals(relationOf(rules, fromSpan, toSpan)) ? r : null;
    }

    private static @Nullable String dateLabel(Case c, String span) {
        return c.dates().stream().filter(d -> d.span().equals(span) && d.value() != null)
                .map(d -> EdtfInterval.parse(d.value()).toString()).findFirst().orElse(null);
    }

    /** The reading of {@code span} equal to its label: upcoming when that is the second reading, else past. */
    private static String tense(Case c, String span) {
        var label = dateLabel(c, span);
        for (var d : TemporalExpressions.find(c.text(), c.capturedAt()).found()) {
            if (d.span().equals(span) && d.readings().size() == 2 && d.readings().get(1).toString().equals(label)) {
                return ExtractionPipeline.UPCOMING;
            }
        }
        return ExtractionPipeline.PAST;
    }

    private static String slot(EdtfInterval date, EdtfInterval valid) {
        if (!date.single()) {
            return date.start().equals(valid.start()) && date.end().equals(valid.end()) ? ExtractionPipeline.DURING
                    : ExtractionPipeline.NEITHER;
        }
        if (date.start().equals(valid.start())) return ExtractionPipeline.FROM;
        return date.start().equals(valid.end()) ? ExtractionPipeline.TO : ExtractionPipeline.NEITHER;
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

    private GraphEvalHarness.Report runWithLabels(List<Case> cases, Decider decider, List<Adjudications.Verdict> verdicts) {
        var blind = new HashSet<>(Agreement.blindSelection(cases));
        var second = cases.stream().filter(c -> blind.contains(c.id())).toList();
        return GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", decider)), 2,
                Certifier.DEFAULT_RECALL_FLOOR, 1, second, verdicts);
    }

    @Test
    void aWrongRecordAtTheCertifiedThresholdAwaitsAdjudicationThenCertifies() throws Exception {
        var cases = certifiable();
        var expected = new WrongRecord("c015", "term:Larchmere House:Organization", GraphEvalScorer.TYPE);

        var pending = runWithLabels(cases, oneMistyped(cases), List.of());
        assertTrue(pending.agreement().complete(), pending.agreement().toString());
        var model = pending.models().getFirst();
        assertEquals(0.50, model.wrongRecordsAt());
        assertEquals(List.of(expected), model.wrongRecords());
        assertEquals(Certifier.PENDING_ADJUDICATION, model.certification().status(),
                model.certification().reasons().toString());
        assertEquals(List.of(expected), model.certification().unadjudicated());

        var verdict = new Adjudications.Verdict("c015", expected.record(), Adjudications.UNMATCHED, Certifier.WRONG,
                "guide@000000000000", Adjudications.OPERATOR, null, null, "typed a house as a company");
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
        var cases = certifiable();
        var stored = new AtomicBoolean();
        var golden = gold(cases);
        var report = run(cases, request -> {
            if (!stored.get()) {
                stored.set(Tx.run(() -> Memory.findByAgent(agentId, cases.size() + 1)).size() == cases.size());
            }
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
                    + c.relations().stream().filter(r -> !r.noise() && c.holdsOrEnded(r, SCHEMA)).count());
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
            assertTrue(bottom.status().n() > 0, "the grid writes status values: " + bottom.status());
            assertEquals(1.0, s.status().rate(), "status " + s.status());
            assertEquals(0.0, s.vetoRate().rate(), "veto " + s.vetoRate());
            var status = run.classWalks().getFirst();
            assertEquals(GraphEvalScorer.STATUS, status.name());
            assertNotEquals(Certifier.DISABLED, status.state(), status.toString());
            assertNotNull(status.threshold(), status.toString());
            assertEquals(0, status.k(), status.toString());
        }
        var certificate = model.certificate();
        assertEquals(SCHEMA.fingerprint(), certificate.schema());
        assertEquals(ExtractionPipeline.fingerprint(SCHEMA), certificate.extraction());
        assertNotNull(certificate.classes().getFirst().threshold(), certificate.toString());
        assertEquals(Certifier.PENDING_AGREEMENT, model.certification().status(), model.certification().reasons().toString());
        assertEquals(0.50, model.certification().threshold());
        assertFalse(report.agreement().complete());
    }

    @Test
    void theCommittedTrapSetNoLongerFailsG_trapOnItsSize() throws Exception {
        var cases = committed();
        var model = run(cases, gold(cases)).models().getFirst();
        for (var run : model.runs()) {
            assertFalse(String.valueOf(run.walk().failure()).contains("trap upper bound"), run.walk().failure());
            var first = run.walk().steps().getFirst();
            // 76 ended, 102 denied and 62 unasserted labels (JCLAW-1376): zero violations now bound below 0.05.
            assertEquals(240, first.trapGold());
            assertEquals(0, first.trapViolations());
        }
    }

    @Test
    void twoRunsWithTheSameAnswersReportTheSame() throws Exception {
        var cases = certifiable().subList(0, 20);
        assertEquals(GSON.toJson(run(cases, gold(cases))), GSON.toJson(run(cases, gold(cases))));
    }

    @Test
    void secondLabelsThatPredateV3LeaveAGoldModelPendingAgreementWithTheReason() throws Exception {
        var cases = certifiable();
        var report = GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", gold(cases))),
                2, Certifier.DEFAULT_RECALL_FLOOR, 1, List.of(), List.of(), EvalProgress.none(), false,
                Agreement.Result.PREDATES_V3 + "case s1: relation has no 'status'");
        assertFalse(report.agreement().complete());
        assertEquals(Agreement.Result.PREDATES_V3 + "case s1: relation has no 'status'", report.agreement().reason());
        var certification = report.models().getFirst().certification();
        assertEquals(Certifier.PENDING_AGREEMENT, certification.status(), certification.reasons().toString());
    }

    @Test
    void aGoldDeciderAnswersTheGoldFedQualifierStagesRight() {
        var text = "Avery Lin has worked at Harborlight Analytics since 2019, does not use Kestrel CI, "
                + "and goes to the Fernhill Summit in March.";
        var c = new Case("q1", List.of("plain", "dated", "negated"), text,
                List.of(GraphCases.Entity.of("operator", "Avery Lin", "Person"),
                        GraphCases.Entity.of("harborlight", "Harborlight Analytics", "Organization"),
                        GraphCases.Entity.of("kestrel", "Kestrel CI", "System"),
                        new GraphCases.Entity("summit", "Fernhill Summit", "Event", List.of(), false, false, "2026-03")),
                List.of(new GraphCases.Relation("operator", "works_at", "harborlight", GraphCases.HOLDS, "2019/..",
                                null, false),
                        new GraphCases.Relation("operator", "uses", "kestrel", GraphCases.DENIED, null, null, false)),
                List.of(), LocalDate.of(2026, 2, 15),
                List.of(new GraphCases.DateLabel("2019", "2019"), new GraphCases.DateLabel("March", "2026-03")));
        for (var run : run(List.of(c), gold(List.of(c))).models().getFirst().runs()) {
            var s = run.stages();
            for (var ratio : Map.of("tense", s.tense(), "occurs", s.occurs(), "slot", s.slot(),
                    "negationYes", s.negationYes()).entrySet()) {
                assertTrue(ratio.getValue().total() > 0, ratio.getKey() + " is scored");
                assertEquals(1.0, ratio.getValue().rate(), ratio.getKey() + " " + ratio.getValue());
            }
        }
    }

    @Test
    void theCaseVoiceIsTheGuestsOnAGuestCaseAndAHeldCaseKeepsItsOwn() {
        var owner = new Case("v1", List.of("plain"), "Avery Lin uses Kestrel CI.", List.of(), List.of(), List.of());
        var guest = new Case("v2", List.of("guest"), "A guest uses Kestrel CI.", List.of(), List.of(), List.of());
        assertEquals(MemoryAuthorType.HUMAN_TURN, GraphEvalHarness.inputs(owner, null, false, null).authorType());
        assertEquals(MemoryAuthorType.GUEST_TURN, GraphEvalHarness.inputs(guest, null, false, null).authorType());
        for (var author : Arrays.asList(MemoryAuthorType.GUEST_TURN, null)) {
            var held = new HeldOut.HeldCase(42L, owner, author);
            assertEquals(author, GraphEvalHarness.inputs(owner, null, false, held).authorType());
        }
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
    void theOwnerReachesTheHarnessGeneratorAsAKinPossessor() {
        assertEquals("Avery Lin", owner());
        var c = new Case("k1", List.of("plain"), "Avery Lin's son starts at Harborlight Academy.",
                List.of(GraphCases.Entity.of("operator", "Avery Lin", "Person"),
                        GraphCases.Entity.of("operator-son", "Avery Lin's son", "Person"),
                        GraphCases.Entity.of("academy", "Harborlight Academy", "Organization")),
                List.of(GraphCases.Relation.of("operator", "family_of", "operator-son")), List.of());
        var golden = gold(List.of(c));
        // The gold-fed typing stage asks every gold span; only the generated candidates' runs ask the kin phrase too.
        var typed = new ConcurrentHashMap<String, Integer>();
        run(List.of(c), request -> {
            for (var q : request.getAsJsonObject("questions").entrySet()) {
                if (!q.getKey().startsWith("m")) continue;
                var rules = q.getValue().getAsJsonObject().getAsJsonObject("instructions").get("rules").getAsString();
                QUOTED.matcher(rules).results().forEach(m -> typed.merge(m.group(1), 1, Integer::sum));
            }
            return golden.decide(request);
        });
        assertTrue(typed.getOrDefault("Harborlight Academy", 0) > 0, typed.toString());
        assertEquals(typed.get("Harborlight Academy"), typed.get("Avery Lin's son"), typed.toString());
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
        var cases = certifiable();
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
                    Map.of("memoryId", Long.parseLong(namedId), "labelled", true, "text", named, "capturedAt", "2026-10-03",
                            "authorType", "human_turn",
                            "entities", List.of(Map.of("id", "operator", "mention", "Ada Pell", "type", "Person"),
                                    Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization")),
                            "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight", "status", "holds"))),
                    Map.of("memoryId", Long.parseLong(legacyId), "labelled", true, "text", legacy, "capturedAt", "2026-10-03",
                            "authorType", "human_turn",
                            "entities", List.of(Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                    Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization")),
                            "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight", "status", "holds")))))));
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
                    Map.of("memoryId", Long.parseLong(memoryId), "labelled", true, "text", text, "capturedAt", "2026-10-03",
                            "authorType", "human_turn",
                            "entities", List.of(
                                    Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                    Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization"),
                                    Map.of("id", "kestrel", "mention", "Kestrel CI", "type", "System")),
                            "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight", "status", "holds"),
                                    Map.of("from", "harborlight", "type", "uses", "to", "kestrel", "status", "holds"))),
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
            assertEquals(ExtractionPipeline.fingerprint(SCHEMA), report.extraction());
            assertEquals(1, report.unlabelled());
            assertEquals(new GraphEvalHarness.HeldOutIntegrity(1, 1, 1), report.memoryIntegrity());
            assertNull(report.models().getFirst().walk().threshold(), "five records cannot bound the wrong share");
            assertEquals(text, Tx.run(() -> Memory.<Memory>findById(Long.parseLong(memoryId))).text);
            var held = loaded.cases().getFirst();
            var c = held.labels();
            var caseRun = ExtractionPipeline.run(SCHEMA, c.id(), c.text(), CandidateGenerator.generate(c.text()), "tev1",
                    golden, GraphEvalHarness.inputs(c, null, false, held));
            assertEquals(Long.parseLong(memoryId), caseRun.memoryId(), "the held-out run passes the memory id");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            Tx.run(() -> store.delete(memoryId));
        }
    }

    // ---- Protocol v2 (JCLAW-1368): one certification invocation and its re-score ----

    private static final String DIGEST = "sha256:7e57";

    private static Sequences fixtureSequences() throws IOException {
        return Sequences.load(Play.applicationPath.toPath().resolve("test/sequence-fixture.json"), SCHEMA);
    }

    /** Gold for the cases and for the sequence chains, routed by the memory's text. */
    private static Decider both(List<Case> cases, Sequences sequences, List<String> order) {
        var caseGold = gold(cases);
        var chainGold = SequenceHarnessTest.gold(sequences, new ArrayList<>());
        var texts = new HashSet<String>();
        cases.forEach(c -> texts.add(c.text()));
        return request -> {
            var text = request.getAsJsonObject("state").get("memory").getAsString();
            boolean isCase = texts.contains(text);
            order.add(isCase ? "case" : "chain");
            return isCase ? caseGold.decide(request) : chainGold.decide(request);
        };
    }

    /** {@code decider}, except c015's "Larchmere House" is typed Organization at 0.99, so it writes at every threshold. */
    private static Decider mistyped(Decider decider, List<Case> cases) {
        var target = cases.stream().filter(c -> c.id().equals("c015")).findFirst().orElseThrow().text();
        return request -> {
            var response = decider.decide(request);
            if (!request.getAsJsonObject("state").get("memory").getAsString().equals(target)) return response;
            for (var q : request.getAsJsonObject("questions").entrySet()) {
                var rules = q.getValue().getAsJsonObject().getAsJsonObject("instructions").get("rules").getAsString();
                var span = QUOTED.matcher(rules).results().map(m -> m.group(1)).findFirst().orElse("");
                if (q.getKey().startsWith("m") && span.equals("Larchmere House")) {
                    response.getAsJsonObject("answers").add(q.getKey(), answer(
                            q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet(), "Organization", 0.99));
                }
            }
            return response;
        };
    }

    private static CertificationSplit.Source source(List<Case> cases, Sequences sequences) throws IOException {
        var json = Files.readString(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH));
        return CertificationSplit.Source.cases(json, cases, sequences.fingerprint(),
                "the guide".getBytes(StandardCharsets.UTF_8), SCHEMA);
    }

    private GraphEvalHarness.CertifyRequest request(Path root, CertificationSplit split,
                                                    CertificationSplit.Source source, Sequences sequences,
                                                    Decider decider, int runs, List<Case> second,
                                                    List<Adjudications.Verdict> verdicts) {
        return request(root, split, source, sequences, decider, runs, second, verdicts, Certifier.DEFAULT_RECALL_FLOOR);
    }

    private GraphEvalHarness.CertifyRequest request(Path root, CertificationSplit split,
                                                    CertificationSplit.Source source, Sequences sequences,
                                                    Decider decider, int runs, List<Case> second,
                                                    List<Adjudications.Verdict> verdicts, double recallFloor) {
        return new GraphEvalHarness.CertifyRequest(root, split, source, SCHEMA, agentId, owner(), Map.of(), sequences,
                List.of(new DecisionModel("tev1", decider)), Map.of("tev1", DIGEST), runs, 1356, 0.2, 0.2,
                recallFloor, 1, second, null, verdicts);
    }

    private static void delete(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void oneInvocationCertifiesOnceItsSheetIsJudgedAndARescoreEqualsTheFullRun() throws Exception {
        var root = Files.createTempDirectory("grapheval-cert");
        try {
            var cases = certifiable();
            var sequences = fixtureSequences();
            var source = source(cases, sequences);
            var ids = new ArrayList<String>();
            for (int i = 0; i < cases.size(); i++) {
                var id = cases.get(i).id();
                if (i % 2 == 0 || id.equals("c015")) ids.add(id);
            }
            var split = CertificationSplit.freeze(root, "cert-t", source, 1356, null, ids, 0.95, null, SCHEMA);
            var splitCases = split.casesFrom(source);
            var order = new ArrayList<String>();
            var golden = mistyped(both(cases, sequences, order), cases);
            var calls = new AtomicInteger();
            Decider counted = request -> {
                calls.incrementAndGet();
                return golden.decide(request);
            };
            var report = GraphEvalHarness.certify(request(root, split, source, sequences, counted, 1, splitCases,
                    List.of()), EvalProgress.none());
            int asked = calls.get();

            assertEquals(order.indexOf("chain"), order.lastIndexOf("case") + 1,
                    "the certifying set runs first, then the sequence harness");
            assertEquals("certification", report.kind());
            assertEquals(splitCases.size(), report.memories());
            var model = report.models().getFirst();
            var sequencing = model.sequencing();
            assertNotNull(sequencing);
            assertEquals(Certifier.ON, sequencing.terms().state(), sequencing.terms().toString());
            assertTrue(sequencing.passed(), sequencing.reasons().toString());
            assertNotNull(model.sequences(), "the sequences were scored at the configuration reached");
            assertEquals(0, model.failedDecisions());
            assertEquals(0, model.spotCheck().differing());
            assertEquals(0, model.sequenceSpotCheck().differing());
            assertEquals(Certifier.PENDING_ADJUDICATION, model.verdict().status(), model.verdict().reasons().toString());
            assertTrue(model.unjudgedByGate().values().stream().mapToInt(Integer::intValue).sum() > 0);
            assertEquals(1356, model.agreedSample().seed());
            assertEquals(0.2, model.agreedSample().share());
            assertTrue(model.agreedSample().drawn() > 0);
            assertNull(model.certificate());
            assertEquals(SplitUses.USED, SplitUses.latest(root, "cert-t", "tev1", DIGEST).state());
            assertTrue(Files.exists(StoredRun.path(root, "cert-t", "tev1")));

            var sheetFile = GraphEvalHarness.sheetPath(root, "cert-t", "tev1");
            var sheet = JsonParser.parseString(Files.readString(sheetFile)).getAsJsonObject();
            assertFalse(sheet.toString().contains("tev1"), "the sheet never names the model");
            var verdicts = new ArrayList<Adjudications.Verdict>();
            var listed = new ArrayList<String>();
            for (var e : sheet.getAsJsonArray("records")) {
                listed.add(e.getAsJsonObject().get("caseId").getAsString() + " " + e.getAsJsonObject().get("record"));
                var o = e.getAsJsonObject();
                assertEquals(Set.of("caseId", "record", "text"), o.keySet(), "nothing on the sheet gives a side away");
                var c = splitCases.stream().filter(x -> x.id().equals(o.get("caseId").getAsString())).findFirst()
                        .orElseThrow();
                assertEquals(c.text(), o.get("text").getAsString());
                // Judged blind, as an adjudicator reading the memory would: only the decider's mistyping is wrong.
                var record = o.get("record").getAsString();
                boolean wrong = c.id().equals("c015") && record.equals("term:Larchmere House:Organization");
                verdicts.add(new Adjudications.Verdict(c.id(), record, null,
                        wrong ? Adjudications.WRONG : Adjudications.RIGHT, source.guide(), Adjudications.OPERATOR,
                        null, null, ""));
            }

            var reuse = assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.admit(request(root, split,
                    source, sequences, both(cases, sequences, new ArrayList<>()), 1, splitCases, List.of())));
            assertTrue(reuse.getMessage().contains(SplitUses.FILE), reuse.getMessage());

            assertTrue(listed.contains("c015 \"term:Larchmere House:Organization\""), listed.toString());
            var same = GraphEvalHarness.rescore(root, split, source, SCHEMA, "tev1", sequences, 0.2, splitCases, null,
                    List.of());
            assertEquals(GSON.toJson(report), GSON.toJson(same), "a re-score gives the full run's report");

            var judged = GraphEvalHarness.rescore(root, split, source, SCHEMA, "tev1", sequences, 0.2, splitCases,
                    null, verdicts).models().getFirst();
            assertEquals(Certifier.CERTIFIED, judged.verdict().status(), judged.verdict().reasons().toString());
            assertEquals(asked, calls.get(), "a re-score asks no model");
            var read = CertificateDocument.read(root, "tev1", SCHEMA.fingerprint(), ExtractionPipeline.fingerprint(SCHEMA),
                    DIGEST);
            assertNull(read.reason());
            var cert = read.certificate();
            assertEquals(judged.certificate(), cert.id());
            assertTrue(cert.json().getAsJsonObject("classes").has("lineage"), cert.json().toString());
            assertNotNull(judged.lineagePower(), "lineage reports its power like every other class");
            assertEquals(judged.sequences().timeline(),
                    cert.json().getAsJsonObject("timeline").get("result").getAsString());
            assertEquals(sequencing.terms().threshold(), cert.toConfiguration().terms());
            assertEquals("digest sha256:7e57 differs from running sha256:0000", CertificateDocument.read(root, "tev1",
                    SCHEMA.fingerprint(), ExtractionPipeline.fingerprint(SCHEMA), "sha256:0000").reason());

            var resheet = JsonParser.parseString(Files.readString(sheetFile)).getAsJsonObject();
            var relisted = new ArrayList<String>();
            resheet.getAsJsonArray("records").forEach(e -> relisted.add(e.getAsJsonObject().get("caseId").getAsString()
                    + " " + e.getAsJsonObject().get("record")));
            assertEquals(listed, relisted, "a judged record stays on the sheet");

            var newGuide = "guide@999999999999";
            var regraded = new CertificationSplit.Source("cases", source.items(), source.cases(), source.sequences(),
                    newGuide, source.schema());
            var restamped = verdicts.stream().map(v -> new Adjudications.Verdict(v.caseId(), v.record(), v.side(),
                    v.verdict(), newGuide, v.adjudicator(), v.inclusion(), v.check(), v.note())).toList();
            assertEquals(Certifier.CERTIFIED, GraphEvalHarness.rescore(root, split, regraded, SCHEMA, "tev1", sequences,
                    0.2, splitCases, null, restamped).models().getFirst().verdict().status());
            var stamped = CertificateDocument.parse(Files.readString(CertificateDocument.path(root, "tev1")));
            assertEquals(newGuide, stamped.json().get("guide").getAsString(), "the guide the verdicts were judged under");
            assertNotEquals(split.guide(), newGuide);

            var pending = GraphEvalHarness.rescore(root, split, source, SCHEMA, "tev1", sequences, 0.2, splitCases,
                    null, List.of()).models().getFirst();
            assertEquals(Certifier.PENDING_ADJUDICATION, pending.verdict().status());
            assertEquals("no certificate for tev1", CertificateDocument.read(root, "tev1", SCHEMA.fingerprint(),
                    ExtractionPipeline.fingerprint(SCHEMA), DIGEST).reason(), "this split's stale certificate is gone");
            var other = cert.json().deepCopy();
            other.addProperty("split", "cert-other");
            Files.writeString(CertificateDocument.path(root, "tev1"), other.toString());
            GraphEvalHarness.rescore(root, split, source, SCHEMA, "tev1", sequences, 0.2, splitCases, null, List.of());
            assertTrue(Files.exists(CertificateDocument.path(root, "tev1")), "another split's certificate is left alone");
        } finally {
            delete(root);
        }
    }

    @Test
    void aVoidRunReportsFailuresButNoGateAndAFreshRunIsAccepted() throws Exception {
        var root = Files.createTempDirectory("grapheval-void");
        try {
            var cases = committed();
            var sequences = fixtureSequences();
            var source = source(cases, sequences);
            var split = CertificationSplit.freeze(root, "cert-v", source, 1, null, List.of("c001", "c002", "c003"), 0.95,
                    null, SCHEMA);
            var target = split.casesFrom(source).get(1).text();
            var golden = both(cases, sequences, new ArrayList<>());
            Decider failing = request -> {
                if (request.getAsJsonObject("state").get("memory").getAsString().equals(target)) {
                    throw new IllegalStateException("timeout");
                }
                return golden.decide(request);
            };
            var request = request(root, split, source, sequences, failing, 1, List.of(), List.of());
            var model = GraphEvalHarness.certify(request, EvalProgress.none()).models().getFirst();
            assertTrue(model.failedDecisions() > 0);
            assertNull(model.sequencing(), "no gate result");
            assertNull(model.sequences());
            assertEquals(Certifier.NOT_CERTIFIED, model.verdict().status());
            assertEquals(Certifier.VOID_RUN, model.verdict().reasons().getFirst());
            var tallies = model.tallies();
            assertEquals(3, tallies.memories());
            assertEquals(1, tallies.failedMemories());
            assertNotNull(tallies.questionsPerMemory());
            assertEquals(3, tallies.byQuestions().stream().mapToInt(GraphEvalHarness.FailureShare::memories).sum());
            assertEquals(SplitUses.VOID, SplitUses.latest(root, "cert-v", "tev1", DIGEST).state());
            GraphEvalHarness.admit(request);

            var stored = StoredRun.path(root, "cert-v", "tev1");
            var json = Files.readString(stored);
            Files.writeString(stored, json.replace(SCHEMA.fingerprint(), "v0@000000000000"));
            var schema = assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.rescore(root, split, source,
                    SCHEMA, "tev1", sequences, 0.2, List.of(), null, List.of()));
            assertTrue(schema.getMessage().contains("schema"), schema.getMessage());
            Files.writeString(stored, json.replace(ExtractionPipeline.fingerprint(SCHEMA), "x@000000000000"));
            var extraction = assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.rescore(root, split,
                    source, SCHEMA, "tev1", sequences, 0.2, List.of(), null, List.of()));
            assertTrue(extraction.getMessage().contains("extraction"), extraction.getMessage());
            Files.writeString(stored, json);

            var items = new java.util.LinkedHashMap<>(source.items());
            var edited = items.get("c002").deepCopy();
            edited.addProperty("text", "Edited.");
            items.put("c002", edited);
            var drifted = new CertificationSplit.Source("cases", items, source.cases(), source.sequences(),
                    source.guide(), source.schema());
            var drift = assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.rescore(root, split,
                    drifted, SCHEMA, "tev1", sequences, 0.2, List.of(), null, List.of()));
            assertEquals("case c002 changed since split 'cert-v' was frozen", drift.getMessage());
            var refused = assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.admit(
                    request(root, split, drifted, sequences, golden, 1, List.of(), List.of())));
            assertEquals(drift.getMessage(), refused.getMessage());
            var reordered = new CertificationSplit.Source("cases", source.items(), source.cases(),
                    "sequences@000000000000", source.guide(), source.schema());
            var seq = assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.admit(
                    request(root, split, reordered, sequences, golden, 1, List.of(), List.of())));
            assertTrue(seq.getMessage().startsWith("sequences changed"), seq.getMessage());
        } finally {
            delete(root);
        }
    }

    @Test
    void aDifferingSpotCheckAdmitsOneRunOfTwoAsTheSameUse() throws Exception {
        var root = Files.createTempDirectory("grapheval-second");
        try {
            var cases = committed();
            var sequences = fixtureSequences();
            var source = source(cases, sequences);
            var split = CertificationSplit.freeze(root, "cert-s", source, 1, null, List.of("c001", "c002"), 0.95, null,
                    SCHEMA);
            var first = split.casesFrom(source).getFirst().text();
            var golden = both(cases, sequences, new ArrayList<>());
            var calls = new AtomicInteger();
            Decider drifting = request -> {
                var response = golden.decide(request);
                if (!request.getAsJsonObject("state").get("memory").getAsString().equals(first)) return response;
                double p = 0.99 - 1e-6 * calls.incrementAndGet();
                for (var e : response.getAsJsonObject("answers").entrySet()) {
                    var a = e.getValue().getAsJsonObject();
                    if (!a.has("choice")) continue;
                    a.addProperty("confidence", p);
                    a.getAsJsonObject("probabilities").addProperty(a.get("choice").getAsString(), p);
                }
                return response;
            };
            var once = GraphEvalHarness.certify(request(root, split, source, sequences, drifting, 1, List.of(),
                    List.of()), EvalProgress.none()).models().getFirst();
            assertTrue(once.spotCheck().differing() > 0, once.spotCheck().toString());
            assertEquals(Certifier.NEEDS_SECOND_RUN, once.verdict().reasons().getFirst());
            assertEquals(SplitUses.NEEDS_SECOND_RUN, SplitUses.latest(root, "cert-s", "tev1", DIGEST).state());
            assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.admit(request(root, split, source,
                    sequences, golden, 1, List.of(), List.of())));

            var twice = GraphEvalHarness.certify(request(root, split, source, sequences, golden, 2, List.of(),
                    List.of()), EvalProgress.none()).models().getFirst();
            assertEquals(2, twice.runs());
            assertNull(twice.spotCheck());
            assertEquals(SplitUses.USED, SplitUses.latest(root, "cert-s", "tev1", DIGEST).state());
            assertThrows(IllegalArgumentException.class, () -> GraphEvalHarness.admit(request(root, split, source,
                    sequences, golden, 2, List.of(), List.of())));
        } finally {
            delete(root);
        }
    }

    @Test
    void anOffRelationWritesNothingIntoTheClassesOrThePooledGates() throws Exception {
        var cases = committed().stream().filter(c -> c.relations().stream().anyMatch(r -> r.type().equals("works_at")))
                .limit(12).toList();
        var golden = gold(cases);
        var runs = cases.stream().map(c -> ExtractionPipeline.run(SCHEMA, c.id(), c.text(),
                CandidateGenerator.generate(c.text(), owner() == null ? List.of() : List.of(owner())), "tev1", golden,
                GraphEvalHarness.inputs(c, owner(), false, null))).toList();
        var classes = new java.util.TreeMap<String, Configuration.ClassSetting>();
        for (var name : Certifier.V2_CLASSES) classes.put(name, new Configuration.ClassSetting(Certifier.PROVISIONAL, 0.5));
        var all = new java.util.TreeMap<String, Double>();
        SCHEMA.relations().keySet().forEach(r -> all.put(r, 0.5));
        var without = new java.util.TreeMap<>(all);
        without.remove("works_at");
        var on = GraphEvalScorer.score(cases, runs, SCHEMA.symmetricSet(), new Configuration(0.5, all, classes));
        var off = GraphEvalScorer.score(cases, runs, SCHEMA.symmetricSet(), new Configuration(0.5, without, classes));
        int worksAt = on.memories().stream().mapToInt(m -> m.gates().containsKey("works_at")
                ? m.gates().get("works_at").written() : 0).sum();
        assertTrue(worksAt > 0, "the cases write works_at when it is on");
        assertTrue(off.records().stream().noneMatch(r -> r.gate().equals("works_at")));
        assertTrue(off.memories().stream().allMatch(m -> !m.gates().containsKey("works_at")
                || m.gates().get("works_at").written() == 0));
        int writtenOn = on.memories().stream().mapToInt(GraphEvalScorer.MemoryScore::gWritten).sum();
        int writtenOff = off.memories().stream().mapToInt(GraphEvalScorer.MemoryScore::gWritten).sum();
        assertTrue(writtenOn - writtenOff >= worksAt, writtenOn + " vs " + writtenOff);
        int statusOn = on.memories().stream().mapToInt(m -> m.classes().get("status").n()).sum();
        int statusOff = off.memories().stream().mapToInt(m -> m.classes().get("status").n()).sum();
        assertTrue(statusOff < statusOn, "works_at's statuses leave the status class: " + statusOn + " vs " + statusOff);
        assertEquals(on.memories().stream().mapToInt(m -> m.gates().get(GraphEvalScorer.TERMS).written()).sum(),
                off.memories().stream().mapToInt(m -> m.gates().get(GraphEvalScorer.TERMS).written()).sum(),
                "Terms are unchanged");
    }

    @Test
    void aDevelopmentRunCarriesTheV2SequencingAndTheSplitIdsLeftOut() throws Exception {
        var cases = committed().subList(0, 6);
        var report = GraphEvalHarness.run(agentId, cases, owner(), SCHEMA, List.of(new DecisionModel("tev1", gold(cases))),
                1, Certifier.DEFAULT_RECALL_FLOOR, 1, List.of(), List.of(), EvalProgress.none(), false, null,
                new GraphEvalHarness.DevOptions("guide@000000000000", 1356, 0.2, 0.2, 5));
        assertEquals(5, report.excludedSplitIds());
        var v2 = report.models().getFirst().v2();
        assertEquals(0.95, v2.startingThreshold());
        assertNotNull(v2.sequencing());
        assertEquals(6, v2.tallies().memories());
        assertEquals(0, v2.tallies().failedMemories());
        assertEquals(1356, v2.agreedSample().seed());
        assertEquals(0.2, v2.checks().checkShare());
        assertFalse(v2.tallies().falsePositiveByTag().isEmpty());
    }

    @Test
    void twoRunsAreReadAtTheWorseRunSoASecondPassThatErrsTurnsTheTermGateOff() throws Exception {
        var root = Files.createTempDirectory("grapheval-worst");
        try {
            var cases = certifiable();
            var sequences = fixtureSequences();
            var source = source(cases, sequences);
            var ids = new ArrayList<String>();
            for (int i = 0; i < cases.size(); i += 2) ids.add(cases.get(i).id());
            var split = CertificationSplit.freeze(root, "cert-w", source, 1, null, ids, 0.95, null, SCHEMA);
            var splitCases = split.casesFrom(source);
            var first = splitCases.getFirst().text();
            var last = splitCases.getLast().text();
            var golden = both(cases, sequences, new ArrayList<>());
            var sawLast = new AtomicBoolean();
            var secondPass = new AtomicBoolean();
            // Concurrency 1 asks pass 1's cases in split order, then pass 2's: pass 2 mistypes every term it is asked.
            Decider erring = request -> {
                var text = request.getAsJsonObject("state").get("memory").getAsString();
                if (text.equals(last)) sawLast.set(true);
                else if (text.equals(first) && sawLast.get()) secondPass.set(true);
                var response = golden.decide(request);
                if (!secondPass.get()) return response;
                for (var q : request.getAsJsonObject("questions").entrySet()) {
                    if (!q.getKey().startsWith("m")) continue;
                    var ids2 = q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet();
                    var given = response.getAsJsonObject("answers").getAsJsonObject(q.getKey()).get("choice")
                            .getAsString();
                    if (given.equals(ExtractionPipeline.NOT_AN_ENTITY)) continue;
                    var wrong = ids2.stream().filter(id -> !id.equals(given)
                            && !id.equals(ExtractionPipeline.NOT_AN_ENTITY)).findFirst().orElse(given);
                    response.getAsJsonObject("answers").add(q.getKey(), answer(ids2, wrong, 0.99));
                }
                return response;
            };
            var model = GraphEvalHarness.certify(request(root, split, source, sequences, erring, 2, splitCases,
                    List.of(), 0.6), EvalProgress.none()).models().getFirst();
            assertTrue(secondPass.get(), "the second pass was asked");
            assertEquals(2, model.runs());
            var sequencing = model.sequencing();
            assertNotNull(sequencing);
            assertEquals(Certifier.OFF, sequencing.terms().state(), sequencing.terms().toString());
            assertFalse(sequencing.passed());
            assertTrue(sequencing.terms().k() > 0, "the gate reads the second pass's wrong terms");
            assertEquals(Certifier.NOT_CERTIFIED, model.verdict().status());

            var rescored = GraphEvalHarness.rescore(root, split, source, SCHEMA, "tev1", sequences, 0.2, splitCases,
                    null, List.of());
            assertEquals(0.6, rescored.recallFloor(), "a re-score keeps the run's own recall floor");
            assertEquals(0.6, StoredRun.read(root, "cert-w", "tev1", SCHEMA).recallFloor());
        } finally {
            delete(root);
        }
    }

    /** Agrees every bound, so a two-memory held-out split gets past its gates to the agreement step. */
    private static final GateBounds LENIENT = new GateBounds() {
        @Override
        public boolean passes(List<GateBounds.MemoryCounts> writing, double limit, double tail) {
            return true;
        }

        @Override
        public double upper(List<GateBounds.MemoryCounts> writing, double tail) {
            return 0;
        }

        @Override
        public double recallLower(List<GateBounds.MemoryCounts> labelled, double tail) {
            return 1;
        }

        @Override
        public double power(List<GateBounds.MemoryCounts> writing, double trueWrongShare,
                            double limit, double tail) {
            return 1;
        }

        @Override
        public int recordsNeeded(int wrong, double limit, double tail) {
            return 1;
        }
    };

    @Test
    void aHeldOutSplitCertifiesOverTheMemoriesInPlaceAndStopsAtAgreement() throws Exception {
        var store = MemoryStoreFactory.get();
        var texts = List.of("The user works at Harborlight Analytics, which runs Kestrel CI for every release.",
                "The user uses Kestrel CI at Harborlight Analytics every week.");
        var memoryIds = new ArrayList<String>();
        for (var text : texts) memoryIds.add(Tx.run(() -> store.storeDeferred(agentId, text, "fact", 0.5)));
        var root = Files.createTempDirectory("grapheval-held-cert");
        try {
            var entries = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < texts.size(); i++) {
                entries.add(Map.of("memoryId", Long.parseLong(memoryIds.get(i)), "labelled", true, "text", texts.get(i),
                        "capturedAt", "2026-10-03", "authorType", "human_turn",
                        "entities", List.of(Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization"),
                                Map.of("id", "kestrel", "mention", "Kestrel CI", "type", "System")),
                        "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight",
                                "status", "holds"))));
            }
            var file = root.resolve(HeldOut.FILE);
            Files.writeString(file, GSON.toJson(Map.of("cases", entries)));
            var loaded = HeldOut.load(file, SCHEMA);
            var sequences = fixtureSequences();
            var source = CertificationSplit.Source.heldout(Files.readString(file), loaded, sequences.fingerprint(),
                    "the guide".getBytes(StandardCharsets.UTF_8), SCHEMA);
            var split = CertificationSplit.freeze(root, "held-t", source, 1, null, null, 0.95, null, SCHEMA);
            assertEquals(memoryIds, split.ids());
            var held = new HashMap<String, HeldOut.HeldCase>();
            loaded.cases().forEach(h -> held.put(String.valueOf(h.memoryId()), h));
            var labels = loaded.cases().stream().map(HeldOut.HeldCase::labels).toList();
            var request = new GraphEvalHarness.CertifyRequest(root, split, source, SCHEMA, null, null, held, sequences,
                    List.of(new DecisionModel("tev1", both(labels, sequences, new ArrayList<>()))),
                    Map.of("tev1", DIGEST), 1, 1, 0.2, 0.2, Certifier.DEFAULT_RECALL_FLOOR, 1, List.of(), null,
                    List.of());
            var report = GraphEvalHarness.certify(request, EvalProgress.none(), LENIENT);
            var model = report.models().getFirst();
            assertEquals("heldout", report.set());
            assertEquals(0, model.memoriesChanged());
            assertEquals(0, model.spotCheck().differing());
            assertTrue(model.spotCheck().decisions() > 0, "the spot-check asked the held-out memory again");
            assertEquals(0, model.failedDecisions());
            assertEquals(Certifier.PENDING_AGREEMENT, model.verdict().status(), model.verdict().reasons().toString());
            for (var text : texts) {
                assertFalse(GSON.toJson(report).contains(text), "the report carries no memory text");
            }
            for (int i = 0; i < texts.size(); i++) {
                var id = Long.parseLong(memoryIds.get(i));
                assertEquals(texts.get(i), Tx.run(() -> Memory.<Memory>findById(id)).text);
            }
        } finally {
            delete(root);
            for (var id : memoryIds) Tx.run(() -> store.delete(id));
        }
    }
}
