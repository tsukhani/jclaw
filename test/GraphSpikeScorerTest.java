import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.CaseRun;
import services.graphspike.ExtractionPipeline.Decision;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;
import services.graphspike.GraphCases.Relation;
import services.graphspike.GraphSpikeScorer;
import services.graphspike.GraphSpikeScorer.PairingRun;

import java.util.ArrayList;
import java.util.List;

/** JCLAW-1344: the scorer's arithmetic against the spec's I/O matrix. Pure, so no fixtures. */
class GraphSpikeScorerTest extends UnitTest {

    private static final Case WORKS = new Case("works", "Dana Reyes works at Harborlight Analytics.",
            List.of(new Entity("Dana Reyes", "Person"), new Entity("Harborlight Analytics", "Organization")),
            List.of(new Relation("Dana Reyes", "works_at", "Harborlight Analytics")));
    private static final Case FAMILY = new Case("family", "Wren Castillo is the sister of Mateo Castillo.",
            List.of(new Entity("Wren Castillo", "Person"), new Entity("Mateo Castillo", "Person")),
            List.of(new Relation("Wren Castillo", "family_of", "Mateo Castillo")));

    private static Decision term(String mention, String choice, double p) {
        return new Decision(ExtractionPipeline.TERM, mention, null, null, choice, p,
                p >= 0.5 ? ExtractionPipeline.WRITTEN : ExtractionPipeline.ABSTAINED, null);
    }

    private static Decision relation(String from, String choice, String to, double p) {
        return new Decision(ExtractionPipeline.RELATION, from + " -> " + to, from, to, choice, p,
                p >= 0.5 ? ExtractionPipeline.WRITTEN : ExtractionPipeline.ABSTAINED, null);
    }

    private static CaseRun run(Case c, Decision... decisions) {
        return new CaseRun(c.id(), c.entities().stream().map(Entity::mention).toList(), 0, null, 0, List.of(decisions));
    }

    /** Every label written exactly, at probability {@code p}. */
    private static CaseRun oracle(Case c, double p) {
        var decisions = new ArrayList<Decision>();
        c.entities().forEach(e -> decisions.add(term(e.mention(), e.type(), p)));
        c.relations().forEach(r -> decisions.add(relation(r.from(), r.type(), r.to(), p)));
        return run(c, decisions.toArray(Decision[]::new));
    }

    private static GraphSpikeScorer.Score score(List<Case> cases, PairingRun... runs) {
        return GraphSpikeScorer.score(cases, List.of(runs), 0.5);
    }

    @Test
    void anOracleWritesEveryLabelWithNothingWrongAndIsAllowed() {
        var s = score(List.of(WORKS, FAMILY), new PairingRun("p", "m", null,
                List.of(oracle(WORKS, 0.9), oracle(FAMILY, 0.9))));
        var pairing = s.pairings().getFirst();
        assertEquals(6, pairing.written());
        assertEquals(0, pairing.wrong());
        assertEquals(0.0, pairing.wrongShare());
        assertEquals(0, pairing.missedLabels());
        assertEquals(0.0, pairing.abstentionRate());
        assertEquals(List.of("m"), s.allowed());
        assertEquals(0.30, s.models().getFirst().lowestAllowedThreshold());
    }

    @Test
    void anAlwaysUnsureModelWritesNothingAndIsNotAllowed() {
        var s = score(List.of(WORKS), new PairingRun("p", "m", null, List.of(oracle(WORKS, 0.40))));
        var pairing = s.pairings().getFirst();
        assertEquals(0, pairing.written());
        assertNull(pairing.wrongShare());
        assertEquals(1.0, pairing.abstentionRate());
        assertEquals(3, pairing.missedLabels());
        assertEquals(List.of(), s.allowed());
        assertFalse(s.models().getFirst().allowed());
    }

    @Test
    void theGateAllowsExactlyOneInTwentyAndRequiresAWrittenRecord() {
        assertTrue(GraphSpikeScorer.passes(1, 20), "0.05 passes");
        assertFalse(GraphSpikeScorer.passes(2, 39), "0.0513 fails");
        assertFalse(GraphSpikeScorer.passes(0, 0), "nothing written fails");
        assertTrue(GraphSpikeScorer.passes(0, 19));
    }

    @Test
    void theGateHoldsForTheWorstProposer() {
        var wrongType = run(WORKS, term("Dana Reyes", "Organization", 0.9));
        var s = score(List.of(WORKS),
                new PairingRun("good", "m", null, List.of(oracle(WORKS, 0.9))),
                new PairingRun("bad", "m", null, List.of(wrongType)));
        assertEquals(List.of(), s.allowed());
        assertEquals(1.0, s.models().getFirst().worstWrongShare());
    }

    @Test
    void aSkippedPairingIsNeverAllowed() {
        var s = score(List.of(WORKS), new PairingRun("p", "m", "no API key", List.of()));
        assertEquals("no API key", s.pairings().getFirst().skipped());
        assertEquals(List.of(), s.allowed());
    }

    @Test
    void eachKindOfWrongIsCountedSeparately() {
        var s = score(List.of(WORKS), new PairingRun("p", "m", null, List.of(run(WORKS,
                term("Dana Reyes", "Person", 0.9),
                term("Harborlight Analytics", "Organization", 0.9),
                term("Analytics", "Topic", 0.9),
                relation("Dana Reyes", "works_on", "Harborlight Analytics", 0.9)))));
        var pairing = s.pairings().getFirst();
        assertEquals(4, pairing.written());
        assertEquals(1, pairing.wrongMatch());
        assertEquals(0, pairing.wrongType());
        assertEquals(1, pairing.wrongRelation());
        assertEquals(1, pairing.missedLabels(), "the labelled works_at");

        var typed = score(List.of(WORKS), new PairingRun("p", "m", null,
                List.of(run(WORKS, term("Harborlight Analytics", "Project", 0.9))))).pairings().getFirst();
        assertEquals(1, typed.wrongType());
        assertEquals(0, typed.wrongMatch());
    }

    @Test
    void aSymmetricRelationMatchesReversedAndAnAsymmetricOneDoesNot() {
        var family = score(List.of(FAMILY), new PairingRun("p", "m", null, List.of(run(FAMILY,
                term("Wren Castillo", "Person", 0.9),
                term("Mateo Castillo", "Person", 0.9),
                relation("Mateo Castillo", "family_of", "Wren Castillo", 0.9))))).pairings().getFirst();
        assertEquals(0, family.wrong());
        assertEquals(0, family.missedLabels());

        var works = score(List.of(WORKS), new PairingRun("p", "m", null, List.of(run(WORKS,
                term("Dana Reyes", "Person", 0.9),
                term("Harborlight Analytics", "Organization", 0.9),
                relation("Harborlight Analytics", "works_at", "Dana Reyes", 0.9))))).pairings().getFirst();
        assertEquals(1, works.wrongRelation());
    }

    @Test
    void aLabelledRelationOnAWronglyTypedEndpointIsWrong() {
        var pairing = score(List.of(WORKS), new PairingRun("p", "m", null, List.of(run(WORKS,
                term("Dana Reyes", "Person", 0.9),
                term("Harborlight Analytics", "Project", 0.9),
                relation("Dana Reyes", "works_at", "Harborlight Analytics", 0.9))))).pairings().getFirst();
        assertEquals(1, pairing.wrongType());
        assertEquals(1, pairing.wrongRelation());
        assertEquals(2, pairing.wrong());
    }

    @Test
    void theCurveDropsARelationWhoseEndpointFallsBelowTheThreshold() {
        var s = score(List.of(WORKS), new PairingRun("p", "m", null, List.of(run(WORKS,
                term("Dana Reyes", "Person", 0.9),
                term("Harborlight Analytics", "Organization", 0.6),
                relation("Dana Reyes", "works_at", "Harborlight Analytics", 0.9)))));
        var at060 = s.curve().stream().filter(p -> p.threshold() == 0.60).findFirst().orElseThrow();
        var at065 = s.curve().stream().filter(p -> p.threshold() == 0.65).findFirst().orElseThrow();
        assertEquals(3, at060.written());
        assertEquals(1, at065.written(), "only Dana Reyes: the relation's endpoint abstained");
        assertEquals(0, at065.wrong());
        assertEquals(14, s.curve().size(), "0.30 to 0.95 by 0.05");
        assertEquals(0.90, s.models().getFirst().allowedThresholds().getLast(), "nothing is sure at 0.95");
    }

    @Test
    void aFailedDecisionIsAFailureNotAnAbstention() {
        var failed = new Decision(ExtractionPipeline.TERM, "Dana Reyes", null, null, null, 0,
                ExtractionPipeline.FAILED, "invalid");
        var pairing = score(List.of(WORKS), new PairingRun("p", "m", null, List.of(run(WORKS, failed))))
                .pairings().getFirst();
        assertEquals(1, pairing.decisionFailures());
        assertEquals(0, pairing.abstentions());
        assertEquals(0.0, pairing.abstentionRate());
    }
}
