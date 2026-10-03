import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.Decision;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;
import services.graphspike.GraphCases.Relation;
import services.graphspike.StageScorer;
import services.graphspike.StageScorer.StageRun;

import java.util.List;

/** JCLAW-1356: each gold-fed stage scored on its own answers. Pure, so no fixtures. */
class StageScorerTest extends UnitTest {

    private static final Case A = new Case("a", List.of("role"),
            "The user is a data engineer at Harborlight Analytics, which runs Kestrel CI.",
            List.of(Entity.of("operator", "The user", "Person"),
                    Entity.of("harborlight", "Harborlight Analytics", "Organization"),
                    Entity.of("kestrel", "Kestrel CI", "System")),
            List.of(Relation.of("operator", "works_at", "harborlight"), Relation.of("harborlight", "uses", "kestrel")),
            List.of("data engineer"));
    private static final Case B = new Case("b", List.of("plain"), "Uses Kestrel CI at the Harborlight office.",
            List.of(Entity.implicitOperator(), Entity.of("kestrel", "Kestrel CI", "System"),
                    Entity.of("office", "Harborlight", "Organization")),
            List.of(Relation.of("operator", "uses", "kestrel")), List.of());

    private static Decision term(String span, String choice) {
        return new Decision(ExtractionPipeline.TERM, span, null, null, choice, 0.9, false, null);
    }

    private static Decision relation(String from, String choice, String to) {
        return new Decision(ExtractionPipeline.RELATION, from + " -> " + to, from, to, choice, 0.9, false, null);
    }

    @Test
    void typingSpansAreTheNonOperatorMentionsThenTheNegatives() {
        assertEquals(List.of("Harborlight Analytics", "Kestrel CI", "data engineer"), StageScorer.typingSpans(A));
        assertEquals("the user", StageScorer.relationTerms(B).getFirst().span(), "the implicit operator's span");
    }

    @Test
    void typingRejectionRelationAndNoRelationAreScoredSeparately() {
        var run = new StageRun("a",
                List.of(term("Harborlight Analytics", "Organization"), term("Kestrel CI", "Project"),
                        term("data engineer", ExtractionPipeline.NOT_AN_ENTITY)),
                List.of(relation("The user", "works_at", "Harborlight Analytics"),
                        relation("Harborlight Analytics", ExtractionPipeline.NONE, "Kestrel CI"),
                        relation("The user", ExtractionPipeline.NONE, "Kestrel CI"),
                        relation("Kestrel CI", "uses", "Harborlight Analytics")));
        var s = StageScorer.score(List.of(A), List.of(run));
        assertEquals(1, s.typing().hit());
        assertEquals(2, s.typing().total());
        assertEquals(1.0, s.rejection().rate());
        assertEquals(1, s.relation().hit());
        assertEquals(2, s.relation().total());
        assertEquals(1, s.noRelation().hit());
        assertEquals(2, s.noRelation().total());
        assertEquals(0, s.failures());
    }

    @Test
    void aFailedQuestionCountsAsAFailureAndAMiss() {
        var failed = new Decision(ExtractionPipeline.TERM, "Kestrel CI", null, null, null, 0, false, "invalid");
        var s = StageScorer.score(List.of(A), List.of(new StageRun("a", List.of(failed), List.of())));
        assertEquals(1, s.failures());
        assertEquals(0, s.typing().hit());
        assertEquals(1, s.typing().total());
    }

    @Test
    void candidateRecallCountsEveryNonImplicitGoldEntity() {
        var r = StageScorer.candidateRecall(List.of(A, B));
        assertEquals(5, r.total());
        assertEquals(5, r.hit());
    }

    @Test
    void resolutionMergesSameSurfacesAndCountsFalseMerges() {
        var other = new Case("c", List.of("plain"), "The user met Harborlight at the fair.",
                List.of(Entity.of("operator", "The user", "Person"),
                        Entity.of("harborlight", "Harborlight", "Organization")),
                List.of(), List.of());
        var r = StageScorer.resolution(List.of(A, B, other));
        assertEquals(8, r.mentions());
        // operator x3, harborlight (A), kestrel x2, "Harborlight" (office in B + harborlight in c).
        assertEquals(4, r.clusters());
        assertEquals(1, r.falseMerges());
        assertTrue(r.bcubedPrecision() < 1.0);
        assertTrue(r.bcubedRecall() < 1.0);
    }
}
