import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.CaseRun;
import services.graphspike.ExtractionPipeline.Decision;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;
import services.graphspike.GraphCases.Relation;
import services.graphspike.GraphSpikeScorer;
import services.graphspike.GraphSpikeScorer.WrongRecord;

import java.util.List;

/** JCLAW-1356: the strict end-to-end scorer against the spec's I/O matrix. Pure, so no fixtures. */
class GraphSpikeScorerTest extends UnitTest {

    private static final Case KESTREL = new Case("c1", List.of("plain"),
            "The user works at Harborlight Analytics, which runs Kestrel CI, the team's deployment platform.",
            List.of(Entity.of("operator", "The user", "Person"),
                    Entity.of("harborlight", "Harborlight Analytics", "Organization"),
                    Entity.of("kestrel", "Kestrel CI", "System", "the team's deployment platform")),
            List.of(Relation.of("operator", "works_at", "harborlight"), Relation.of("harborlight", "uses", "kestrel")),
            List.of());
    private static final Case MERIDIAN = new Case("c2", List.of("plain"),
            "The user works on the Meridian program with Wren Castillo, their sister.",
            List.of(Entity.of("operator", "The user", "Person"),
                    Entity.of("meridian", "Meridian program", "Project"),
                    Entity.of("wren", "Wren Castillo", "Person")),
            List.of(Relation.of("operator", "works_on", "meridian"), Relation.of("operator", "family_of", "wren")),
            List.of());
    private static final Case NOISY = new Case("c3", List.of("plain"),
            "The user uses Kestrel CI at Harborlight Analytics.",
            List.of(Entity.of("operator", "The user", "Person"),
                    new Entity("kestrel", "Kestrel CI", "System", List.of(), false, true),
                    Entity.of("harborlight", "Harborlight Analytics", "Organization")),
            List.of(new Relation("operator", "uses", "kestrel", true),
                    Relation.of("operator", "works_at", "harborlight")),
            List.of());

    private static Decision operator() {
        return new Decision(ExtractionPipeline.TERM, "The user", null, null, "Person", 1.0, true, null);
    }

    private static Decision term(String span, String type) {
        return term(span, type, 0.9);
    }

    private static Decision term(String span, String type, double p) {
        return new Decision(ExtractionPipeline.TERM, span, null, null, type, p, false, null);
    }

    private static Decision relation(String from, String type, String to) {
        return new Decision(ExtractionPipeline.RELATION, from + " -> " + to, from, to, type, 0.9, false, null);
    }

    private static GraphSpikeScorer.Scored score(Case c, Decision... decisions) {
        return GraphSpikeScorer.score(List.of(c), List.of(new CaseRun(c.id(), List.of(), 0, List.of(decisions))), 0.5);
    }

    @Test
    void everyGoldRecordWrittenIsAllRightAndFullRecall() {
        var s = score(KESTREL, operator(), term("Harborlight Analytics", "Organization"),
                term("Kestrel CI", "System"), relation("The user", "works_at", "Harborlight Analytics"),
                relation("Harborlight Analytics", "uses", "Kestrel CI")).point();
        assertEquals(5, s.written());
        assertEquals(5, s.right());
        assertEquals(0, s.wrong());
        assertEquals(1.0, s.recall());
        assertEquals(0.0, s.wrongShare());
    }

    @Test
    void anAliasSpanIsRight() {
        var s = score(KESTREL, term("the team's deployment platform", "System")).point();
        assertEquals(1, s.right());
        assertEquals(0, s.wrong());
    }

    @Test
    void aPartialSpanIsAWrongMatch() {
        var s = score(MERIDIAN, term("Meridian", "Project"));
        assertEquals(0, s.point().right());
        assertEquals(1, s.point().wrongMatch());
        assertEquals(List.of(new WrongRecord("c2", "term:Meridian:Project", GraphSpikeScorer.MATCH)), s.wrong());
    }

    @Test
    void aWrongTypeIsAWrongType() {
        var s = score(KESTREL, term("Kestrel CI", "Project"));
        assertEquals(1, s.point().wrongType());
        assertEquals("term:Kestrel CI:Project", s.wrong().getFirst().record());
    }

    @Test
    void aSpanAndAnAliasOfOneEntityAreRightThenADuplicate() {
        var s = score(KESTREL, term("Kestrel CI", "System"), term("the team's deployment platform", "System"));
        assertEquals(1, s.point().right());
        assertEquals(1, s.point().wrongDuplicate());
        assertEquals(GraphSpikeScorer.DUPLICATE, s.wrong().getFirst().kind());
    }

    @Test
    void noiseIsInNeitherTheWrongCountNorTheDenominator() {
        var s = score(NOISY, operator(), term("Kestrel CI", "System"), term("Harborlight Analytics", "Organization"),
                relation("The user", "uses", "Kestrel CI"), term("Monday", "Event")).point();
        assertEquals(5, s.written());
        assertEquals(2, s.noise());
        assertEquals(2, s.right());
        assertEquals(1, s.wrong());
        assertEquals(1.0 / 3, s.wrongShare(), 1e-9);
        assertEquals(3, s.gold(), "noise labels are not gold");
    }

    @Test
    void aSymmetricRelationWrittenBothWaysIsOneRecord() {
        var s = score(MERIDIAN, operator(), term("Wren Castillo", "Person"),
                relation("The user", "family_of", "Wren Castillo"),
                relation("Wren Castillo", "family_of", "The user")).point();
        assertEquals(3, s.written());
        assertEquals(3, s.right());
        assertEquals(0, s.wrong());
    }

    @Test
    void anUnlabelledRelationIsWrong() {
        var s = score(MERIDIAN, operator(), term("Wren Castillo", "Person"),
                relation("Wren Castillo", "works_on", "The user"));
        assertEquals(1, s.point().wrongRelation());
        assertEquals("rel:Wren Castillo:works_on:The user", s.wrong().getFirst().record());
    }

    @Test
    void aRelationWithAnUnwrittenEndpointIsNotCounted() {
        var s = score(KESTREL, operator(), relation("The user", "works_at", "Harborlight Analytics")).point();
        assertEquals(1, s.written());
    }

    @Test
    void theGridDropsDecisionsBelowEachThreshold() {
        var run = new CaseRun("c1", List.of(), 0, List.of(term("Kestrel CI", "System", 0.92),
                term("Harborlight Analytics", "Organization", 0.6)));
        var grid = GraphSpikeScorer.grid(List.of(KESTREL), List.of(run));
        assertEquals(GraphSpikeScorer.THRESHOLDS.size(), grid.size());
        assertEquals(0, grid.getFirst().written(), "0.95");
        assertEquals(1, grid.get(1).written(), "0.90");
        assertEquals(2, grid.getLast().written(), "0.50");
    }

    @Test
    void aFailedOrRejectedDecisionWritesNothing() {
        var s = score(KESTREL, new Decision(ExtractionPipeline.TERM, "Kestrel CI", null, null, null, 0, false, "x"),
                term("release", ExtractionPipeline.NOT_AN_ENTITY)).point();
        assertEquals(0, s.written());
        assertEquals(1, s.failures());
        assertNull(s.wrongShare());
    }

    @Test
    void theUnionKeepsOneRecordPerCaseAndKey() {
        var a = new WrongRecord("c1", "term:x:System", GraphSpikeScorer.MATCH);
        var b = new WrongRecord("c1", "term:y:System", GraphSpikeScorer.MATCH);
        assertEquals(List.of(a, b), GraphSpikeScorer.union(List.of(List.of(a), List.of(a, b))));
    }
}
