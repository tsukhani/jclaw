import com.google.gson.JsonObject;
import memory.TemporalExpressions;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.ExtractionPipeline.Inputs;
import services.grapheval.ExtractionPipeline.Predecessor;
import services.grapheval.ExtractionPipeline.Records;
import services.grapheval.ExtractionPipeline.Request;
import services.grapheval.Lineage;
import services.grapheval.Statements;
import services.grapheval.Statements.Claim;
import services.grapheval.Statements.Classes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** JCLAW-1365: what a run would write, derived from its decisions by code. */
class StatementsTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3);
    private static final String OWNER = "Avery Lin";
    private static final Classes AT_80 = Classes.all(0.80);

    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    /** Every typing in {@code types} at 0.99, plus each scripted answer in {@code answers} (choice, confidence). */
    private static Map<String, String> choices(Map<String, String> types, Map<String, String> answers) {
        var out = new HashMap<String, String>(types);
        out.putAll(answers);
        return out;
    }

    private static Map<String, Double> confidences(Map<String, String> types, Map<String, Double> answers) {
        var out = new HashMap<String, Double>();
        types.keySet().forEach(span -> out.put(span, 0.99));
        out.putAll(answers);
        return out;
    }

    /** The span of the only date {@link TemporalExpressions#find} reads in {@code text} at {@link #ANCHOR}. */
    private static String date(String text) {
        var found = TemporalExpressions.find(text, ANCHOR).found();
        assertEquals(1, found.size(), found::toString);
        return found.getFirst().span();
    }

    private CaseRun run(String text, Map<String, String> choices, Map<String, Double> confidence) {
        return run(text, List.of(), ExtractionPipelineTest.scripted(requests, choices, confidence));
    }

    private static CaseRun run(String text, List<Predecessor> predecessors, Decider decider) {
        var inputs = Inputs.of(text, OWNER, MemoryAuthorType.HUMAN_TURN, ANCHOR, predecessors, false);
        return ExtractionPipeline.run(SCHEMA, "c", text, CandidateGenerator.generate(text), "clef-flash", decider,
                inputs);
    }

    private static Claim claim(Statements.Outcome outcome, String from, String type, String to) {
        return outcome.relations().stream().filter(c -> c.from().equals(from) && c.type().equals(type)
                && c.to().equals(to)).findFirst().orElseThrow(() -> new AssertionError(outcome.toString()));
    }

    private static String valid(Claim c) {
        return c.valid() == null ? null : c.valid().toString();
    }

    // --- Examples ---

    private CaseRun moved(Map<String, String> answers, Map<String, Double> confidence) {
        var text = "Avery Lin moved to Ashgrove from Port Calloway in 2019.";
        var types = Map.of("Avery Lin", "Person", "Ashgrove", "Place", "Port Calloway", "Place");
        var base = new HashMap<String, Double>(Map.of(
                "Avery Lin located_in Ashgrove", 0.94, "Avery Lin located_in Port Calloway", 0.91,
                "status:Avery Lin located_in Ashgrove", 0.90, "status:Avery Lin located_in Port Calloway", 0.88,
                "slot:2019:Avery Lin located_in Ashgrove", 0.86, "slot:2019:Avery Lin located_in Port Calloway", 0.83));
        base.putAll(confidence);
        var chosen = new HashMap<String, String>(Map.of(
                "status:Avery Lin located_in Port Calloway", ExtractionPipeline.ENDED,
                "slot:2019:Avery Lin located_in Ashgrove", ExtractionPipeline.FROM,
                "slot:2019:Avery Lin located_in Port Calloway", ExtractionPipeline.TO));
        chosen.putAll(answers);
        assertEquals("2019", date(text));
        return run(text, choices(types, chosen), confidences(types, base));
    }

    @Test
    void aMoveGivesTheNewPlaceAnOpenStartAndTheOldOneAnEnd() {
        var out = Statements.at(moved(Map.of(), Map.of()), 0.80, AT_80);
        assertFalse(out.failed());
        var ashgrove = claim(out, OWNER, "located_in", "Ashgrove");
        assertEquals(OntologyRecord.Status.HOLDS, ashgrove.status());
        assertEquals("2019/..", valid(ashgrove));
        var port = claim(out, OWNER, "located_in", "Port Calloway");
        assertEquals(OntologyRecord.Status.ENDED, port.status());
        assertEquals("/2019", valid(port));
        assertEquals(0, out.conflict());
    }

    private static final String DOES_NOT_USE = "Avery Lin doesn't use Osprey Dashboard; Vela Design does.";
    private static final Map<String, String> OSPREY_TYPES = Map.of("Avery Lin", "Person", "Osprey Dashboard", "System",
            "Vela Design", "Organization");

    private CaseRun doesNotUse(Map<String, String> answers, Map<String, Double> confidence) {
        var base = new HashMap<String, Double>(Map.of("Vela Design uses Osprey Dashboard", 0.85,
                "Avery Lin uses Osprey Dashboard", 0.06, "not Avery Lin uses Osprey Dashboard", 0.90,
                "status:Vela Design uses Osprey Dashboard", 0.88));
        base.putAll(confidence);
        return run(DOES_NOT_USE, choices(OSPREY_TYPES, answers), confidences(OSPREY_TYPES, base));
    }

    @Test
    void aCuedDenialStandsBesideAnotherHoldersPositive() {
        var run = doesNotUse(Map.of(), Map.of());
        assertFalse(run.stage(ExtractionPipeline.NEGATION).isEmpty(), "n't is a cue");
        var out = Statements.at(run, 0.80, AT_80);
        var vela = claim(out, "Vela Design", "uses", "Osprey Dashboard");
        assertEquals(OntologyRecord.Status.HOLDS, vela.status());
        assertNull(vela.valid());
        assertEquals(1, out.relations().size(), out.toString());
        assertEquals(1, out.denials().size(), out.toString());
        var denial = out.denials().getFirst();
        assertEquals(List.of(OWNER, "uses", "Osprey Dashboard"), List.of(denial.from(), denial.type(), denial.to()));
        assertEquals(OntologyRecord.Status.DENIED, denial.status());
        assertNull(denial.valid(), "no perfect never, no never scope");
        assertEquals(0, out.conflict());
    }

    @Test
    void anEndingConsumesItsNegationCue() {
        var text = "Avery Lin hasn't used Osprey Dashboard since 2024.";
        var span = date(text);
        var types = Map.of("Avery Lin", "Person", "Osprey Dashboard", "System");
        var key = "Avery Lin uses Osprey Dashboard";
        var run = run(text, choices(types, Map.of("status:" + key, ExtractionPipeline.ENDED,
                "slot:" + span + ":" + key, ExtractionPipeline.TO)), confidences(types, Map.of(key, 0.9)));
        assertTrue(run.stage(ExtractionPipeline.NEGATION).isEmpty());
        var out = Statements.at(run, 0.80, AT_80);
        var uses = claim(out, OWNER, "uses", "Osprey Dashboard");
        assertEquals(OntologyRecord.Status.ENDED, uses.status());
        assertEquals("/2024", valid(uses));
        assertTrue(out.denials().isEmpty());
    }

    private CaseRun forThreeYears(String status, double slotConfidence) {
        var text = "Avery Lin has worked at Harborlight Analytics for three years.";
        var span = date(text);
        var types = Map.of("Avery Lin", "Person", "Harborlight Analytics", "Organization");
        var key = "Avery Lin works_at Harborlight Analytics";
        return run(text, choices(types, Map.of("status:" + key, status, "slot:" + span + ":" + key,
                ExtractionPipeline.FROM)), confidences(types, Map.of(key, 0.9, "status:" + key, 0.87,
                "slot:" + span + ":" + key, slotConfidence)));
    }

    @Test
    void aDurationCountsBackFromTheAnchor() {
        var out = Statements.at(forThreeYears(ExtractionPipeline.HOLDS, 0.84), 0.80, AT_80);
        var works = claim(out, OWNER, "works_at", "Harborlight Analytics");
        assertEquals(OntologyRecord.Status.HOLDS, works.status());
        assertEquals("2023~/..", valid(works));
    }

    @Test
    void aPlannedRelationIsNotKeptAndSendsNoQualifyRequest() {
        var text = "Avery Lin might join the Atlas Migration next quarter.";
        var types = Map.of("Avery Lin", "Person", "Atlas Migration", "Project");
        var run = run(text, choices(types, Map.of()),
                confidences(types, Map.of("Avery Lin works_on Atlas Migration", 0.07)));
        assertFalse(run.sent().contains(Request.QUALIFY), run.sent().toString());
        assertTrue(Statements.at(run, 0.80, AT_80).relations().isEmpty());
    }

    @Test
    void aSharedObjectKeepsTheFirstVerbsFrame() {
        var text = "Dana Reyes hates Corvid Notes, but Avery Lin loves it.";
        var types = Map.of("Dana Reyes", "Person", "Avery Lin", "Person", "Corvid Notes", "Topic");
        var run = run(text, choices(types, Map.of()), confidences(types, Map.of(
                "Dana Reyes holds_view_on Corvid Notes", 0.9, "Avery Lin holds_view_on Corvid Notes", 0.9)));
        var out = Statements.at(run, 0.80, AT_80);
        assertEquals(OntologyRecord.Valence.UNFAVORABLE, claim(out, "Dana Reyes", "holds_view_on", "Corvid Notes").valence());
        assertNull(claim(out, OWNER, "holds_view_on", "Corvid Notes").valence());
    }

    private CaseRun gala(Map<String, Double> confidence) {
        var text = "The Lanternlight Gala is at the Larkspur Inn on 12 December 2026.";
        var span = date(text);
        var gala = CandidateGenerator.generate(text).stream().map(CandidateGenerator.Candidate::span)
                .filter(s -> s.contains("Gala")).findFirst().orElseThrow();
        var types = Map.of(gala, "Event", "Larkspur Inn", "Place");
        var base = new HashMap<String, Double>(Map.of(gala + " located_in Larkspur Inn", 0.93,
                gala + " @ " + span, 0.91));
        base.putAll(confidence);
        return run(text, choices(types, Map.of()), confidences(types, base));
    }

    @Test
    void anEventOccursOnItsDateAndItsRelationHoldsByRule() {
        var run = gala(Map.of());
        assertFalse(run.sent().contains(Request.QUALIFY), "a dated From takes no status and no valid time");
        var out = Statements.at(run, 0.80, AT_80);
        var event = out.terms().stream().filter(t -> t.type().equals("Event")).findFirst().orElseThrow();
        assertEquals("2026-12-12", String.valueOf(event.occurs()));
        var located = out.relations().getFirst();
        assertEquals("located_in", located.type());
        assertEquals(OntologyRecord.Status.HOLDS, located.status());
        assertNull(located.valid());
    }

    // --- Rules ---

    @Test
    void anUnstatedStatusVetoesTheRelationWhateverItsYes() {
        var run = moved(Map.of("status:Avery Lin located_in Ashgrove", ExtractionPipeline.UNSTATED),
                Map.of("Avery Lin located_in Ashgrove", 0.99, "status:Avery Lin located_in Ashgrove", 0.51));
        var vetoed = Records.vetoed(run);
        assertEquals(1, vetoed.size(), vetoed.toString());
        assertEquals("Ashgrove", vetoed.getFirst().to());
        assertTrue(Records.at(run, 0.80).relations().contains(vetoed.getFirst()), "Records.at is unchanged");
        var out = Statements.at(run, 0.80, AT_80);
        assertTrue(out.relations().stream().noneMatch(c -> c.to().equals("Ashgrove")), out.toString());
    }

    @Test
    void aStatusWritesAtItsClassThresholdAndNotBelow() {
        var at = Statements.at(moved(Map.of(), Map.of("status:Avery Lin located_in Ashgrove", 0.80)), 0.80, AT_80);
        assertEquals(OntologyRecord.Status.HOLDS, claim(at, OWNER, "located_in", "Ashgrove").status());
        var below = Statements.at(moved(Map.of(), Map.of("status:Avery Lin located_in Ashgrove", 0.79)), 0.80, AT_80);
        var ashgrove = claim(below, OWNER, "located_in", "Ashgrove");
        assertNull(ashgrove.status());
        assertNull(ashgrove.valid(), "a null status takes no valid time");
        var raised = Statements.at(moved(Map.of(), Map.of()), 0.50, new Classes(0.95, 0.5, 0.5, 0.5));
        assertNull(claim(raised, OWNER, "located_in", "Ashgrove").status(), "the class threshold is above t");
    }

    @Test
    void aSlotWritesAtItsClassThresholdAndNotBelow() {
        var key = "slot:2019:Avery Lin located_in Ashgrove";
        var at = Statements.at(moved(Map.of(), Map.of(key, 0.80)), 0.80, AT_80);
        assertEquals("2019/..", valid(claim(at, OWNER, "located_in", "Ashgrove")));
        var below = Statements.at(moved(Map.of(), Map.of(key, 0.79)), 0.80, AT_80);
        assertNull(claim(below, OWNER, "located_in", "Ashgrove").valid());
    }

    @Test
    void aDisabledClassWritesNothingOfItsKind() {
        var moved = moved(Map.of(), Map.of());
        var noStatus = Statements.at(moved, 0.80, new Classes(null, 0.8, 0.8, 0.8));
        for (var c : noStatus.relations()) {
            assertNull(c.status(), c.toString());
            assertNull(c.valid(), c.toString());
        }
        var noTime = Statements.at(moved, 0.80, new Classes(0.8, null, 0.8, 0.8));
        assertEquals(OntologyRecord.Status.HOLDS, claim(noTime, OWNER, "located_in", "Ashgrove").status());
        noTime.relations().forEach(c -> assertNull(c.valid(), c.toString()));
        Statements.at(gala(Map.of()), 0.80, new Classes(0.8, null, 0.8, 0.8)).terms()
                .forEach(t -> assertNull(t.occurs(), t.toString()));

        var noNegation = Statements.at(doesNotUse(Map.of(), Map.of()), 0.80, new Classes(0.8, 0.8, null, 0.8));
        assertTrue(noNegation.denials().isEmpty());

        var lineage = Lineage.at(lineageRun(ExtractionPipeline.UPDATE, null), 0.80, new Classes(0.8, 0.8, 0.8, null));
        assertEquals(1, lineage.entries().size());
        assertNull(lineage.entries().getFirst().lineage());
        assertNull(lineage.entries().getFirst().changedBy());
    }

    @Test
    void aPerfectNeverScopesTheDenialUpToTheAnchor() {
        var text = "Avery Lin has never used Osprey Dashboard.";
        var types = Map.of("Avery Lin", "Person", "Osprey Dashboard", "System");
        var run = run(text, choices(types, Map.of()),
                confidences(types, Map.of("not Avery Lin uses Osprey Dashboard", 0.9)));
        var denial = Statements.at(run, 0.80, AT_80).denials().getFirst();
        assertEquals("../2026-10-03", valid(denial));
    }

    @Test
    void aNegationWritesAtItsClassThresholdAndNotBelow() {
        assertEquals(1, Statements.at(doesNotUse(Map.of(), Map.of("not Avery Lin uses Osprey Dashboard", 0.80)),
                0.80, AT_80).denials().size());
        assertTrue(Statements.at(doesNotUse(Map.of(), Map.of("not Avery Lin uses Osprey Dashboard", 0.79)),
                0.80, AT_80).denials().isEmpty());
    }

    // --- Consistency ---

    private Statements.Outcome works(String text, String status, Map<String, String> slots, Map<String, Double> conf) {
        var types = Map.of("Avery Lin", "Person", "Harborlight Analytics", "Organization");
        var key = "Avery Lin works_at Harborlight Analytics";
        var answers = new HashMap<String, String>(Map.of("status:" + key, status));
        var confidence = new HashMap<String, Double>(Map.of(key, 0.9));
        slots.forEach((span, choice) -> answers.put("slot:" + span + ":" + key, choice));
        conf.forEach((span, p) -> confidence.put("slot:" + span + ":" + key, p));
        return Statements.at(run(text, choices(types, answers), confidences(types, confidence)), 0.80, AT_80);
    }

    private static Claim works(Statements.Outcome out) {
        return claim(out, OWNER, "works_at", "Harborlight Analytics");
    }

    @Test
    void aHoldingRelationWhoseEndIsPastLosesItsStatus() {
        var out = works("Avery Lin works at Harborlight Analytics until 2019.", ExtractionPipeline.HOLDS,
                Map.of("2019", ExtractionPipeline.TO), Map.of());
        assertNull(works(out).status());
        assertNull(works(out).valid());
        assertEquals(1, out.conflict());
    }

    @Test
    void anEndedRelationThatBeginsAfterTheAnchorLosesItsStatus() {
        var out = works("Avery Lin worked at Harborlight Analytics in 2028.", ExtractionPipeline.ENDED,
                Map.of("2028", ExtractionPipeline.FROM), Map.of());
        assertNull(works(out).status());
        assertNull(works(out).valid());
        assertEquals(1, out.conflict());
    }

    @Test
    void aDurationOnAnEndedRelationIsDropped() {
        var out = Statements.at(forThreeYears(ExtractionPipeline.ENDED, 0.84), 0.80, AT_80);
        assertEquals(OntologyRecord.Status.ENDED, works(out).status());
        assertNull(works(out).valid());
        assertEquals(1, out.conflict());
    }

    @Test
    void twoDatesForOneBoundKeepTheSurerAndATieKeepsNeither() {
        var text = "Avery Lin joined Harborlight Analytics in 2019 or 2020.";
        var both = Map.of("2019", ExtractionPipeline.FROM, "2020", ExtractionPipeline.FROM);
        var surer = works(text, ExtractionPipeline.HOLDS, both, Map.of("2019", 0.9, "2020", 0.85));
        assertEquals("2019/..", valid(works(surer)));
        assertEquals(1, surer.conflict());
        var tie = works(text, ExtractionPipeline.HOLDS, both, Map.of("2019", 0.9, "2020", 0.9));
        assertNull(works(tie).valid());
        assertEquals(OntologyRecord.Status.HOLDS, works(tie).status());
        assertEquals(1, tie.conflict());
    }

    @Test
    void anInvertedIntervalDropsBothBoundsAndKeepsTheStatus() {
        var out = works("Avery Lin worked at Harborlight Analytics from 2024 to 2020.", ExtractionPipeline.ENDED,
                Map.of("2024", ExtractionPipeline.FROM, "2020", ExtractionPipeline.TO), Map.of());
        assertEquals(OntologyRecord.Status.ENDED, works(out).status());
        assertNull(works(out).valid());
        assertEquals(1, out.conflict());
    }

    @Test
    void aDenialOfAWrittenPositiveWritesNeitherUnlessThePositiveEnded() {
        var both = Map.of("Vela Design uses Osprey Dashboard", 0.06, "Avery Lin uses Osprey Dashboard", 0.9);
        var holds = Statements.at(doesNotUse(Map.of(), both), 0.80, AT_80);
        assertTrue(holds.relations().isEmpty(), holds.toString());
        assertTrue(holds.denials().isEmpty(), holds.toString());
        assertEquals(1, holds.conflict());

        var ended = Statements.at(doesNotUse(Map.of("status:Avery Lin uses Osprey Dashboard", ExtractionPipeline.ENDED),
                both), 0.80, AT_80);
        assertEquals(OntologyRecord.Status.ENDED, claim(ended, OWNER, "uses", "Osprey Dashboard").status());
        assertTrue(ended.denials().isEmpty(), ended.toString());
        assertEquals(1, ended.conflict());
    }

    // --- Lineage ---

    private CaseRun lineageRun(String choice, LocalDate predecessorAnchor) {
        var text = "Avery Lin works at Vela Design now.";
        var earlier = "Avery Lin works at Harborlight Analytics.";
        return run(text, List.of(new Predecessor("m7", earlier, predecessorAnchor)), ExtractionPipelineTest.scripted(
                requests, Map.of("lineage:" + earlier, choice), Map.of()));
    }

    @Test
    void aLineageWritesWithTheSuccessorsAnchor() {
        var out = Lineage.at(lineageRun(ExtractionPipeline.UPDATE, LocalDate.of(2025, 1, 1)), 0.80, AT_80);
        assertFalse(out.failed());
        var entry = out.entries().getFirst();
        assertEquals("m7", entry.predecessorId());
        assertEquals(OntologyRecord.Lineage.UPDATE, entry.lineage());
        assertEquals(ANCHOR, entry.changedBy());
        assertEquals(0, out.conflict());
    }

    @Test
    void anUpdateBeforeItsPredecessorWasWrittenIsDropped() {
        var out = Lineage.at(lineageRun(ExtractionPipeline.UPDATE, ANCHOR.plusDays(1)), 0.80, AT_80);
        assertNull(out.entries().getFirst().lineage());
        assertEquals(1, out.conflict());
        var correction = Lineage.at(lineageRun(ExtractionPipeline.CORRECTION, ANCHOR.plusDays(1)), 0.80, AT_80);
        assertEquals(OntologyRecord.Lineage.CORRECTION, correction.entries().getFirst().lineage());
    }

    // --- Failures ---

    /** {@code scripted}, with every answer to a question whose key starts with {@code prefix} removed. */
    private static Decider failing(Decider scripted, String prefix) {
        return request -> {
            var response = scripted.decide(request);
            for (var key : request.getAsJsonObject("questions").keySet()) {
                if (key.startsWith(prefix)) response.getAsJsonObject("answers").remove(key);
            }
            return response;
        };
    }

    @Test
    void aRunWithAnyFailedDecisionDerivesNothing() {
        var text = "Avery Lin doesn't use Osprey Dashboard; Vela Design has used it since 2019. "
                + "The Lanternlight Gala is at the Larkspur Inn on 12 December 2026.";
        var gala = CandidateGenerator.generate(text).stream().map(CandidateGenerator.Candidate::span)
                .filter(s -> s.contains("Gala")).findFirst().orElseThrow();
        var types = Map.of("Avery Lin", "Person", "Osprey Dashboard", "System", "Vela Design", "Organization",
                gala, "Event", "Larkspur Inn", "Place");
        var decider = ExtractionPipelineTest.scripted(requests, choices(types, Map.of()), confidences(types, Map.of(
                "Vela Design uses Osprey Dashboard", 0.9)));
        var predecessors = List.of(new Predecessor("m1", "Vela Design uses Osprey Dashboard."));
        var asked = new HashSet<String>();
        run(text, predecessors, decider);
        requests.forEach(r -> r.getAsJsonObject("questions").keySet().forEach(k -> asked.add(k.substring(0, 1))));
        assertTrue(asked.containsAll(List.of("m", "r", "n", "e", "s", "d", "l")), asked.toString());
        for (var prefix : asked) {
            var run = run(text, predecessors, failing(decider, prefix));
            assertTrue(run.decisions().stream().anyMatch(ExtractionPipeline.Decision::failed), prefix);
            var statements = Statements.at(run, 0.80, AT_80);
            assertEquals(new Statements.Outcome(true, List.of(), List.of(), List.of(), 0), statements, prefix);
            assertEquals(new Lineage.Outcome(true, List.of(), 0), Lineage.at(run, 0.80, AT_80), prefix);
        }
    }

    @Test
    void aQualifierNeverWritesAboveItsParent() {
        var run = moved(Map.of(), Map.of("Avery Lin located_in Ashgrove", 0.60, "status:Avery Lin located_in Ashgrove",
                0.95, "slot:2019:Avery Lin located_in Ashgrove", 0.99));
        var relation = run.stage(ExtractionPipeline.RELATION).stream().filter(d -> "Ashgrove".equals(d.to()))
                .findFirst().orElseThrow();
        double strength = Math.min(relation.confidence(), relation.floor());
        var status = run.stage(ExtractionPipeline.STATUS).stream().filter(d -> "Ashgrove".equals(d.to()))
                .findFirst().orElseThrow();
        assertEquals(strength, status.floor(), 1e-9);
        var slots = new ArrayList<>(run.stage(ExtractionPipeline.SLOT));
        assertFalse(slots.isEmpty());
        for (var slot : slots) {
            if (!"Ashgrove".equals(slot.to())) continue;
            assertTrue(slot.floor() <= Math.min(strength, status.confidence()) + 1e-9, slot.toString());
            assertFalse(slot.writes(0.61), "a slot never writes above its relation");
        }
        assertNull(valid(claim(Statements.at(run, 0.60, Classes.all(0.70)), OWNER, "located_in", "Ashgrove")),
                "0.60 strength cannot carry a 0.70 slot");
    }
}
