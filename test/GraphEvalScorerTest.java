import memory.TemporalExpressions;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator.Candidate;
import services.grapheval.CandidateGenerator.PreferenceFrame;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GraphCases;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Entity;
import services.grapheval.GraphCases.Relation;
import services.grapheval.GraphEvalScorer;
import services.grapheval.GraphEvalScorer.WrongRecord;
import services.grapheval.Statements;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JCLAW-1356, JCLAW-1357: the strict end-to-end scorer against the spec's I/O matrix. Pure, so no fixtures. */
class GraphEvalScorerTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final LocalDate ANCHOR = LocalDate.of(2026, 2, 15);

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

    private static CaseRun run(Case c, List<Candidate> candidates, Decision... decisions) {
        return new CaseRun(c.id(), candidates, 0, List.of(decisions), c.text(), c.capturedAt(),
                TemporalExpressions.find(c.text(), c.capturedAt()).found(), List.of(), List.of(), Map.of(), Set.of(),
                SCHEMA);
    }

    private static GraphEvalScorer.Scored score(Case c, Decision... decisions) {
        return score(c, Statements.Classes.none(), decisions);
    }

    private static GraphEvalScorer.Scored score(Case c, Statements.Classes classes, Decision... decisions) {
        return GraphEvalScorer.score(List.of(c), List.of(run(c, List.of(), decisions)), SCHEMA.symmetricSet(), 0.5,
                classes);
    }

    @Test
    void everyGoldRecordWrittenIsAllRightAndFullRecall() {
        var s = score(KESTREL, operator(), term("Harborlight Analytics", "Organization"),
                term("Kestrel CI", "System"), relation("The user", "works_at", "Harborlight Analytics"),
                relation("Harborlight Analytics", "uses", "Kestrel CI")).point();
        assertEquals(4, s.written(), "the rule-written operator is left out");
        assertEquals(4, s.right());
        assertEquals(0, s.wrong());
        assertEquals(4, s.gold(), "and is no gold either");
        assertEquals(1, s.ruleWritten());
        assertEquals(1.0, s.recall());
        assertEquals(0.0, s.wrongShare());
    }

    @Test
    void aNamedOwnerIsDecidedWrittenAndGold() {
        var c = new Case("n1", List.of("plain"), "Avery Lin works at Harborlight Analytics.",
                List.of(Entity.of("operator", "Avery Lin", "Person"),
                        Entity.of("harborlight", "Harborlight Analytics", "Organization")),
                List.of(Relation.of("operator", "works_at", "harborlight")), List.of());
        var works = new Decision(ExtractionPipeline.RELATION, "Avery Lin -> Harborlight Analytics", "Avery Lin",
                "Harborlight Analytics", "works_at", 0.9, false, null, 0.8);
        var s = score(c, term("Avery Lin", "Person", 0.8), term("Harborlight Analytics", "Organization"), works)
                .point();
        assertEquals(3, s.written());
        assertEquals(3, s.right());
        assertEquals(3, s.gold(), "the named owner is gold");
        assertEquals(0, s.ruleWritten());
        assertEquals(GraphEvalScorer.TYPE, score(c, term("Avery Lin", "Organization")).wrong().getFirst().kind());
        var untyped = score(c, term("Harborlight Analytics", "Organization")).point();
        assertEquals(1, untyped.right());
        assertEquals(1.0 / 3, untyped.recall(), "an untyped owner is a recall miss");
    }

    @Test
    void aRelationToTheOperatorStillCountsWithAYesExactlyAtT() {
        var works = new Decision(ExtractionPipeline.RELATION, "The user -> Harborlight Analytics", "The user",
                "Harborlight Analytics", "works_at", 0.5, false, null, 0.9);
        var s = score(KESTREL, operator(), term("Harborlight Analytics", "Organization"), works).point();
        assertEquals(2, s.written());
        assertEquals(2, s.right());
        assertEquals(1, s.ruleWritten());

        var below = new Decision(ExtractionPipeline.RELATION, "The user -> Harborlight Analytics", "The user",
                "Harborlight Analytics", "works_at", 0.4999, false, null, 0.9);
        assertEquals(1, score(KESTREL, operator(), term("Harborlight Analytics", "Organization"), below).point()
                .written());
        var floored = new Decision(ExtractionPipeline.RELATION, "The user -> Harborlight Analytics", "The user",
                "Harborlight Analytics", "works_at", 0.9, false, null, 0.4999);
        assertEquals(1, score(KESTREL, operator(), term("Harborlight Analytics", "Organization"), floored).point()
                .written(), "a floor below t holds the relation back");
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
        assertEquals(List.of(new WrongRecord("c2", "term:Meridian:Project", GraphEvalScorer.MATCH)), s.wrong());
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
        assertEquals(GraphEvalScorer.DUPLICATE, s.wrong().getFirst().kind());
    }

    @Test
    void noiseIsInNeitherTheWrongCountNorTheDenominator() {
        var s = score(NOISY, operator(), term("Kestrel CI", "System"), term("Harborlight Analytics", "Organization"),
                relation("The user", "uses", "Kestrel CI"), term("Monday", "Event")).point();
        assertEquals(4, s.written());
        assertEquals(2, s.noise());
        assertEquals(1, s.right());
        assertEquals(1, s.wrong());
        assertEquals(1.0 / 2, s.wrongShare(), 1e-9);
        assertEquals(2, s.gold(), "noise labels and the operator are not gold");
    }

    @Test
    void aSymmetricRelationWrittenBothWaysIsOneRecord() {
        var s = score(MERIDIAN, operator(), term("Wren Castillo", "Person"),
                relation("The user", "family_of", "Wren Castillo"),
                relation("Wren Castillo", "family_of", "The user")).point();
        assertEquals(2, s.written());
        assertEquals(2, s.right());
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
        assertEquals(0, s.written());
        assertEquals(1, s.ruleWritten());
    }

    @Test
    void theGridDropsDecisionsBelowEachThreshold() {
        var run = run(KESTREL, List.of(), term("Kestrel CI", "System", 0.92),
                term("Harborlight Analytics", "Organization", 0.6));
        var grid = GraphEvalScorer.grid(List.of(KESTREL), List.of(run), SCHEMA.symmetricSet(),
                Statements.Classes.none());
        assertEquals(GraphEvalScorer.THRESHOLDS.size(), grid.size());
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
        var a = new WrongRecord("c1", "term:x:System", GraphEvalScorer.MATCH);
        var b = new WrongRecord("c1", "term:y:System", GraphEvalScorer.MATCH);
        assertEquals(List.of(a, b), GraphEvalScorer.union(List.of(List.of(a), List.of(a, b))));
    }

    private static final Statements.Classes ALL = Statements.Classes.all(0.5);

    private static Case works(String status, String valid, String text) {
        return new Case("w", List.of("plain"), text,
                List.of(Entity.of("operator", "The user", "Person"),
                        Entity.of("harborlight", "Harborlight Analytics", "Organization")),
                List.of(new Relation("operator", "works_at", "harborlight", status, valid, null, false)), List.of(),
                ANCHOR, List.of());
    }

    private static Decision status(String from, String to, String choice) {
        return new Decision(ExtractionPipeline.STATUS, from + " -> " + to, from, to, choice, 0.9, false, null);
    }

    private static Decision[] worksAt(Decision... more) {
        var out = new java.util.ArrayList<>(List.of(operator(), term("Harborlight Analytics", "Organization"),
                relation("The user", "works_at", "Harborlight Analytics")));
        out.addAll(List.of(more));
        return out.toArray(Decision[]::new);
    }

    @Test
    void aPositiveWrittenOnADeniedLabelIsPolarityAndATrapViolation() {
        var c = works(GraphCases.DENIED, null, "The user does not work at Harborlight Analytics.");
        var s = score(c, worksAt());
        assertEquals(1, s.point().wrongPolarity());
        assertEquals(List.of(new WrongRecord("w", "rel:The user:works_at:Harborlight Analytics",
                GraphEvalScorer.POLARITY)), s.wrong());
        assertEquals(1, s.point().gold(), "a denial is no base gold");
        assertEquals(1, s.point().trapGold());
        assertEquals(1, s.point().trapViolations());
    }

    @Test
    void aPositiveWrittenOnAnUnassertedLabelIsUnasserted() {
        var c = works(GraphCases.UNASSERTED, null, "The user might apply to Harborlight Analytics.");
        var s = score(c, worksAt());
        assertEquals(1, s.point().wrongUnasserted());
        assertEquals(GraphEvalScorer.UNASSERTED, s.wrong().getFirst().kind());
        assertEquals(1, s.point().trapViolations());
        var unwritten = score(c, operator(), term("Harborlight Analytics", "Organization")).point();
        assertEquals(1, unwritten.trapGold());
        assertEquals(0, unwritten.trapViolations(), "an unasserted triple left unwritten is no violation");
    }

    @Test
    void anEndedOnARelationThatCannotEndScoresAsUnasserted() {
        var c = new Case("d", List.of("plain"), "The Q3 report was derived from the Q2 report.",
                List.of(Entity.of("q3", "The Q3 report", "Artifact"), Entity.of("q2", "the Q2 report", "Artifact")),
                List.of(new Relation("q3", "derived_from", "q2", GraphCases.ENDED, null, null, false)), List.of(),
                ANCHOR, List.of());
        var s = score(c, term("The Q3 report", "Artifact"), term("the Q2 report", "Artifact"),
                relation("The Q3 report", "derived_from", "the Q2 report"));
        assertEquals(1, s.point().wrongUnasserted());
        assertEquals(2, s.point().gold(), "an inadmissible ended is no base gold");
        assertEquals(1, s.point().trapViolations());
    }

    @Test
    void aStatusIsScoredAgainstTheGoldStatus() {
        var c = works(GraphCases.ENDED, null, "The user used to work at Harborlight Analytics.");
        var right = score(c, ALL, worksAt(status("The user", "Harborlight Analytics", ExtractionPipeline.ENDED)));
        assertEquals(new GraphEvalScorer.ClassTally(1, 1, 0, 1, 1.0), right.point().status());
        assertEquals(0, right.point().trapViolations());

        var wrong = score(c, ALL, worksAt(status("The user", "Harborlight Analytics", ExtractionPipeline.HOLDS)));
        assertEquals(new GraphEvalScorer.ClassTally(1, 0, 1, 1, 0.0), wrong.point().status());
        assertEquals(List.of(new WrongRecord("w", "status:The user:works_at:Harborlight Analytics:holds",
                GraphEvalScorer.STATUS)), wrong.wrong());
        assertEquals(2, wrong.point().right(), "the term and the relation stay right at base");
        assertEquals(0, wrong.point().wrong());
        assertEquals(1, wrong.point().gWrong(), "but the item is wrong for G_written");
        assertEquals(1, wrong.point().trapViolations(), "an ended written as holds");
    }

    @Test
    void aNullStatusOnGoldEndedIsIncompleteNotWrong() {
        var c = works(GraphCases.ENDED, null, "The user used to work at Harborlight Analytics.");
        var s = score(c, ALL, worksAt()).point();
        assertEquals(2, s.right(), "the term and the relation");
        assertEquals(0, s.gWrong());
        assertEquals(new GraphEvalScorer.ClassTally(0, 0, 0, 1, 0.0), s.status(), "missed for recall only");
        assertEquals(0, s.trapViolations(), "a null status on gold ended is no violation");
        assertEquals(0, s.ruleStatus());
    }

    @Test
    void aRelationWhoseStatusChoseUnstatedIsUnwritten() {
        var c = works(GraphCases.HOLDS, null, "The user works at Harborlight Analytics.");
        var s = score(c, ALL, worksAt(status("The user", "Harborlight Analytics", ExtractionPipeline.UNSTATED)))
                .point();
        assertEquals(1, s.written(), "only the organisation");
        assertEquals(1, s.vetoed());
        assertEquals(0.5, s.recall());
    }

    @Test
    void aStatusSetByRuleIsCountedApart() {
        var c = new Case("k", List.of("plain"), "Kestrel CI is a kind of Kestrel CI platform.",
                List.of(Entity.of("kestrel", "Kestrel CI", "System"),
                        Entity.of("platform", "Kestrel CI platform", "System")),
                List.of(Relation.of("kestrel", "kind_of", "platform")), List.of());
        var s = score(c, ALL, term("Kestrel CI", "System"), term("Kestrel CI platform", "System"),
                relation("Kestrel CI", "kind_of", "Kestrel CI platform")).point();
        assertEquals(1, s.ruleStatus());
        assertEquals(0, s.status().n());
        assertEquals(3, s.right());
    }

    private static String spanOf(Case c, String part) {
        return TemporalExpressions.find(c.text(), c.capturedAt()).found().stream().map(d -> d.span())
                .filter(sp -> sp.contains(part)).findFirst().orElseThrow();
    }

    private static Decision slot(Case c, String date, String choice) {
        return new Decision(ExtractionPipeline.SLOT, "The user -works_at-> Harborlight Analytics @ " + spanOf(c, date),
                "The user", "Harborlight Analytics", choice, 0.9, false, null);
    }

    @Test
    void aValidTimeIsScoredAgainstTheGoldValue() {
        var text = "The user has worked at Harborlight Analytics since 2019.";
        var held = status("The user", "Harborlight Analytics", ExtractionPipeline.HOLDS);
        var right = score(works(GraphCases.HOLDS, "2019/..", text), ALL,
                worksAt(held, slot(works(GraphCases.HOLDS, null, text), "2019", ExtractionPipeline.FROM)));
        assertEquals(new GraphEvalScorer.ClassTally(1, 1, 0, 1, 1.0), right.point().time());

        var wrong = score(works(GraphCases.HOLDS, "2018/..", text), ALL,
                worksAt(held, slot(works(GraphCases.HOLDS, null, text), "2019", ExtractionPipeline.FROM)));
        assertEquals(1, wrong.point().time().wrong());
        assertEquals(GraphEvalScorer.TIME, wrong.wrong().getFirst().kind());
        assertTrue(wrong.wrong().getFirst().record().startsWith("valid:The user:works_at:Harborlight Analytics:"));

        var unlabelled = score(works(GraphCases.HOLDS, null, text), ALL,
                worksAt(held, slot(works(GraphCases.HOLDS, null, text), "2019", ExtractionPipeline.FROM)));
        assertEquals(new GraphEvalScorer.ClassTally(1, 0, 1, 0, null), unlabelled.point().time(),
                "a value where gold has none is wrong");

        var missing = score(works(GraphCases.HOLDS, "2019/..", text), ALL, worksAt(held)).point();
        assertEquals(new GraphEvalScorer.ClassTally(0, 0, 0, 1, 0.0), missing.time(), "a missing bound is a miss");
        assertEquals(0, missing.gWrong());
    }

    private static Decision negation(String from, String type, String to) {
        return new Decision(ExtractionPipeline.NEGATION, from + " -> " + to, from, to, type, 0.9, false, null);
    }

    @Test
    void aDenialIsRightOnlyOnAGoldDenial() {
        var text = "The user does not work at Harborlight Analytics.";
        var decisions = new Decision[] {operator(), term("Harborlight Analytics", "Organization"),
                negation("The user", "works_at", "Harborlight Analytics")};
        var right = score(works(GraphCases.DENIED, null, text), ALL, decisions).point();
        assertEquals(new GraphEvalScorer.ClassTally(1, 1, 0, 1, 1.0), right.negation());
        assertEquals(2, right.gWritten(), "the organisation and the denial");
        assertEquals(0, right.trapViolations());

        var wrong = score(works(GraphCases.HOLDS, null, text), ALL, decisions);
        assertEquals(new GraphEvalScorer.ClassTally(1, 0, 1, 0, null), wrong.point().negation());
        assertEquals(List.of(new WrongRecord("w", "neg:The user:works_at:Harborlight Analytics",
                GraphEvalScorer.NEGATIVE)), wrong.wrong());

        var disabled = score(works(GraphCases.DENIED, null, text), decisions).point();
        assertEquals(0, disabled.negation().n(), "a disabled negation class writes no denials");
    }

    private static Case view(String valence) {
        return new Case("v", List.of("plain"), "The user loves Rust.",
                List.of(Entity.of("operator", "The user", "Person"), Entity.of("rust", "Rust", "Topic")),
                List.of(new Relation("operator", "holds_view_on", "rust", GraphCases.HOLDS, null, valence, false)),
                List.of(), ANCHOR, List.of());
    }

    private static GraphEvalScorer.Scored viewScored(Case c, OntologyRecord.Valence written) {
        var candidates = written == null ? List.<Candidate>of()
                : List.of(new Candidate("Rust", false, false, 15, 19, new PreferenceFrame(written, "The user")));
        var r = run(c, candidates, operator(), term("Rust", "Topic"), relation("The user", "holds_view_on", "Rust"));
        return GraphEvalScorer.score(List.of(c), List.of(r), SCHEMA.symmetricSet(), 0.5, Statements.Classes.none());
    }

    @Test
    void aValenceIsScoredAgainstTheGoldValence() {
        var right = viewScored(view("favorable"), OntologyRecord.Valence.FAVORABLE).point();
        assertEquals(new GraphEvalScorer.ClassTally(1, 1, 0, 1, 1.0), right.valence());

        var wrong = viewScored(view("unfavorable"), OntologyRecord.Valence.FAVORABLE);
        assertEquals(1, wrong.point().valence().wrong());
        assertEquals(List.of(new WrongRecord("v", "valence:The user:Rust:favorable", GraphEvalScorer.VALENCE)),
                wrong.wrong());

        assertEquals(1, viewScored(view(null), OntologyRecord.Valence.FAVORABLE).point().valence().wrong(),
                "a value where gold has none is wrong");
        var missing = viewScored(view("favorable"), null).point();
        assertEquals(new GraphEvalScorer.ClassTally(0, 0, 0, 1, 0.0), missing.valence());
        assertEquals(0, missing.gWrong());
    }

    @Test
    void aPositiveScoresAgainstThePositiveLabelBesideADenialWhateverTheOrder() {
        var uses = Relation.of("operator", "uses", "kestrel");
        var owns = new Relation("operator", "owns", "kestrel", GraphCases.DENIED, null, null, false);
        for (var order : List.of(List.of(uses, owns), List.of(owns, uses))) {
            var c = new Case("c063", List.of("negated"), "The user uses Kestrel CI but does not own it.",
                    List.of(Entity.of("operator", "The user", "Person"), Entity.of("kestrel", "Kestrel CI", "System")),
                    order, List.of());
            var right = score(c, operator(), term("Kestrel CI", "System"), relation("The user", "uses", "Kestrel CI"))
                    .point();
            assertEquals(2, right.right(), order.toString());
            assertEquals(0, right.wrong(), order.toString());
            var owned = score(c, operator(), term("Kestrel CI", "System"), relation("The user", "owns", "Kestrel CI"));
            assertEquals(1, owned.point().wrongPolarity(), order.toString());
        }
    }
}
