import memory.TemporalExpressions;
import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.CandidateGenerator.Source;
import services.grapheval.Certifier;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.ExtractionPipeline.Overlap;
import services.grapheval.GraphCases;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.DateLabel;
import services.grapheval.GraphCases.Entity;
import services.grapheval.GraphCases.Relation;
import services.grapheval.StageScorer;
import services.grapheval.StageScorer.Load;
import services.grapheval.StageScorer.Ratio;
import services.grapheval.StageScorer.Scored;
import services.grapheval.StageScorer.StageRun;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;

/** JCLAW-1356, JCLAW-1357, JCLAW-1358: each gold-fed stage scored on its own answers. Pure, so no fixtures. */
class StageScorerTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3);

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

    /** Rule 2a: an unnamed relative named by an owner-possessive kin phrase. */
    private static final Case KIN = new Case("k", List.of("plain"), "Avery Lin's son starts at Harborlight Academy.",
            List.of(Entity.of("operator", "Avery Lin", "Person"), Entity.of("operator-son", "Avery Lin's son", "Person"),
                    Entity.of("academy", "Harborlight Academy", "Organization")),
            List.of(Relation.of("operator", "family_of", "operator-son")), List.of());

    private static Decision term(String span, String choice) {
        return new Decision(ExtractionPipeline.TERM, span, null, null, choice, 0.9, false, null);
    }

    private static Decision relation(String from, String choice, String to, double yes) {
        return new Decision(ExtractionPipeline.RELATION, from + " -> " + to, from, to, choice, yes, false, null);
    }

    private static Overlap overlap(String choice, String... spans) {
        return new Overlap(List.of(spans), new Decision(ExtractionPipeline.OVERLAP, String.join(" | ", spans), null,
                null, choice, 0.9, false, null));
    }

    @Test
    void typingSpansAreTheNonOperatorMentionsThenTheNegatives() {
        assertEquals(List.of("Harborlight Analytics", "Kestrel CI", "data engineer"), StageScorer.typingSpans(A));
        assertEquals("the user", StageScorer.relationTerms(B).getFirst().span(), "the implicit operator's span");
    }

    @Test
    void typingSpansIncludeTheNamedOwnerButNotTheUser() {
        var named = new Case("o", List.of("plain"), "Avery Lin works at Harborlight Analytics.",
                List.of(Entity.of("operator", "Avery Lin", "Person"),
                        Entity.of("harborlight", "Harborlight Analytics", "Organization")),
                List.of(Relation.of("operator", "works_at", "harborlight")), List.of());
        assertEquals(List.of("Avery Lin", "Harborlight Analytics"), StageScorer.typingSpans(named));
        assertFalse(StageScorer.typingSpans(A).contains("The user"));
        var s = StageScorer.score(List.of(named), List.of(new StageRun("o", List.of(),
                List.of(term("Avery Lin", "Person"), term("Harborlight Analytics", "Organization")), List.of())), null, SCHEMA);
        assertEquals(2, s.typing().hit());
        var resolved = StageScorer.resolution(List.of(named, A), "Avery Lin");
        assertEquals(3, resolved.clusters(), "operator x2, harborlight x2, kestrel");
        assertEquals(0, resolved.falseMerges());
        assertEquals(4, StageScorer.resolution(List.of(named, A), null).clusters(), "unnamed, the owner stands apart");
        var all = StageScorer.resolution(List.of(named, A, B), "Avery Lin");
        assertEquals(4, all.clusters(), "the named, \"The user\" and implicit operator are one cluster");
        assertEquals(0, all.falseMerges());
    }

    @Test
    void typingRejectionRelationAndNoRelationAreScoredSeparately() {
        var run = new StageRun("a", List.of(),
                List.of(term("Harborlight Analytics", "Organization"), term("Kestrel CI", "Project"),
                        term("data engineer", ExtractionPipeline.NOT_AN_ENTITY)),
                List.of(relation("The user", "works_at", "Harborlight Analytics", 0.9),
                        relation("Kestrel CI", "uses", "Harborlight Analytics", 0.9),
                        relation("The user", "uses", "Kestrel CI", 0.3)));
        var s = StageScorer.score(List.of(A), List.of(run), null, SCHEMA);
        assertEquals(1, s.typing().hit());
        assertEquals(2, s.typing().total());
        assertEquals(1.0, s.rejection().rate());
        assertEquals(1, s.relation().hit(), "the reversed uses is wrong");
        assertEquals(2, s.relation().total());
        assertEquals(1, s.noRelation().hit(), "an unlabelled pair kept below one half");
        assertEquals(1, s.noRelation().total());
        assertEquals(0, s.failures());
    }

    @Test
    void aLabelledRelationNeedsYesOfAtLeastOneHalfAndASymmetricOneMatchesEitherWay() {
        var c = new Case("f", List.of("plain"), "The user's sister is Wren Castillo, who works at Harborlight.",
                List.of(Entity.of("operator", "The user", "Person"), Entity.of("wren", "Wren Castillo", "Person"),
                        Entity.of("harborlight", "Harborlight", "Organization")),
                List.of(Relation.of("operator", "family_of", "wren"), Relation.of("wren", "works_at", "harborlight")),
                List.of());
        var s = StageScorer.score(List.of(c), List.of(new StageRun("f", List.of(), List.of(),
                List.of(relation("Wren Castillo", "family_of", "The user", 0.5),
                        relation("Wren Castillo", "works_at", "Harborlight", 0.49),
                        relation("The user", "works_at", "Harborlight", 0.5)))), null, SCHEMA);
        assertEquals(1, s.relation().hit());
        assertEquals(2, s.relation().total());
        assertEquals(0, s.noRelation().hit(), "yes at one half is a yes");
        assertEquals(1, s.noRelation().total());
    }

    @Test
    void anOverlapIsRightOnAGoldSpanOrOnNeitherWhenNoSpanIsGold() {
        var run = new StageRun("a", List.of(overlap("Harborlight Analytics", "Harborlight", "Harborlight Analytics"),
                overlap("Kestrel", "Kestrel", "Kestrel CI"),
                overlap(ExtractionPipeline.NEITHER, "data", "data engineer"),
                overlap(ExtractionPipeline.NEITHER, "Kestrel CI", "CI runs")), List.of(), List.of());
        var s = StageScorer.score(List.of(A), List.of(run), null, SCHEMA);
        assertEquals(2, s.overlap().hit());
        assertEquals(4, s.overlap().total());
    }

    @Test
    void aNoiseEntityIsNotGoldInAnOverlap() {
        var c = new Case("n", List.of("plain"), "The user drinks Lapsang tea at Harborlight.",
                List.of(Entity.of("operator", "The user", "Person"),
                        new Entity("tea", "Lapsang tea", "Concept", List.of(), false, true),
                        Entity.of("harborlight", "Harborlight", "Organization")),
                List.of(), List.of());
        var run = new StageRun("n", List.of(overlap(ExtractionPipeline.NEITHER, "Lapsang", "Lapsang tea"),
                overlap("Lapsang tea", "Lapsang tea", "tea at Harborlight"),
                overlap("Lapsang tea", "Lapsang tea", "Harborlight")), List.of(), List.of());
        var s = StageScorer.score(List.of(c), List.of(run), null, SCHEMA);
        assertEquals(1, s.overlap().hit(), "neither is right beside noise, and choosing noise over gold is wrong");
        assertEquals(3, s.overlap().total());
    }

    @Test
    void aFailedQuestionCountsAsAFailureAndAMiss() {
        var failed = new Decision(ExtractionPipeline.TERM, "Kestrel CI", null, null, null, 0, false, "invalid");
        var s = StageScorer.score(List.of(A), List.of(new StageRun("a", List.of(), List.of(failed), List.of())), null, SCHEMA);
        assertEquals(1, s.failures());
        assertEquals(0, s.typing().hit());
        assertEquals(1, s.typing().total());
    }

    @Test
    void candidateRecallCountsEveryNonImplicitGoldEntity() {
        var r = StageScorer.candidateRecall(List.of(A, B), null);
        assertEquals(5, r.total());
        assertEquals(5, r.hit());
    }

    @Test
    void aKinPhraseIsAKinHitThatOnlyTheNewSourcesFind() {
        var c = StageScorer.coverage(List.of(KIN), "Avery Lin");
        assertEquals(Ratio.of(1, 3), c.bySource().get(Source.KIN));
        assertEquals(Ratio.of(2, 3), c.before(), "operator-son is missed before");
        assertEquals(Ratio.of(3, 3), c.after());
        assertEquals(new Load(2.0, 0.0, 2.0), c.loadBefore());
        assertEquals(new Load(3.0, 0.0, 3.0), c.loadAfter(), "the kin phrase overlaps only its own possessor");
    }

    @Test
    void coverageIsPinnedPerSourceBeforeAndAfter() {
        var c = StageScorer.coverage(List.of(A, B, KIN), "Avery Lin");
        var expected = new EnumMap<Source, Ratio>(Source.class);
        for (var s : Source.values()) if (s != Source.OPERATOR) expected.put(s, Ratio.of(0, 8));
        expected.put(Source.KNOWN, Ratio.of(1, 8));
        expected.put(Source.CAPITALIZED, Ratio.of(6, 8));
        expected.put(Source.KIN, Ratio.of(1, 8));
        assertEquals(expected, c.bySource());
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(c.bySource().keySet()), "enum order, no OPERATOR");
        assertEquals(Ratio.of(7, 8), c.before());
        assertEquals(Ratio.of(8, 8), c.after());
        assertEquals(new Load(2.0, 0.0, 2.0), c.loadBefore());
        assertEquals(new Load(7.0 / 3, 0.0, 7.0 / 3), c.loadAfter());
        assertEquals(c.after(), StageScorer.candidateRecall(List.of(A, B, KIN), "Avery Lin"));
        assertEquals(new Load(null, null, null), StageScorer.coverage(List.of(), null).loadAfter());

        var s = StageScorer.score(List.of(A, B, KIN), List.of(), "Avery Lin", SCHEMA);
        assertEquals(c, s.coverage());
    }

    @Test
    void anOverlapGroupIsOneOverlapQuestionAndOneTypingQuestion() {
        var c = new Case("o1", List.of("plain"), "The user prefers Kestrel CI pipelines.",
                List.of(Entity.of("operator", "The user", "Person"), Entity.of("kestrel", "Kestrel CI", "System")),
                List.of(), List.of());
        var coverage = StageScorer.coverage(List.of(c), "Avery Lin");
        assertEquals(new Load(2.0, 1.0, 1.0), coverage.loadBefore());
        assertEquals(new Load(2.0, 1.0, 1.0), coverage.loadAfter());
    }

    @Test
    void resolutionMergesSameSurfacesAndCountsFalseMerges() {
        var other = new Case("c", List.of("plain"), "The user met Harborlight at the fair.",
                List.of(Entity.of("operator", "The user", "Person"),
                        Entity.of("harborlight", "Harborlight", "Organization")),
                List.of(), List.of());
        var r = StageScorer.resolution(List.of(A, B, other), null);
        assertEquals(8, r.mentions());
        // operator x3, harborlight (A), kestrel x2, "Harborlight" (office in B + harborlight in c).
        assertEquals(4, r.clusters());
        assertEquals(1, r.falseMerges(), "the second Harborlight attached to the office's Term");
        assertEquals(4, r.attachments());
        assertEquals(0.25, r.falseMergeRate());
        assertEquals(Certifier.upperBound(1, 4), r.falseMergeBound());
        assertTrue(r.bcubedPrecision() < 1.0);
        assertTrue(r.bcubedRecall() < 1.0);
    }

    @Test
    void scoredOutcomesReportFalseMergesTheirRateAndBoundAndBcubedAndPairwise() {
        var r = StageScorer.resolution(List.of(new Scored("1", "a", "T1", false), new Scored("2", "a", "T1", true),
                new Scored("3", "b", "T1", true), new Scored("4", "c", "T2", false), new Scored("5", "a", "T3", false)));
        assertEquals(5, r.mentions());
        assertEquals(3, r.clusters());
        assertEquals(3, r.goldEntities());
        assertEquals(2, r.attachments());
        assertEquals(1, r.falseMerges(), "b attached to a Term whose first mention is a");
        assertEquals(0.5, r.falseMergeRate());
        assertEquals(Certifier.upperBound(1, 2), r.falseMergeBound());
        assertEquals(11.0 / 15, r.bcubedPrecision(), 1e-12);
        assertEquals(11.0 / 15, r.bcubedRecall(), 1e-12);
        assertEquals(1.0 / 3, r.pairwisePrecision(), 1e-12);
        assertEquals(1.0 / 3, r.pairwiseRecall(), 1e-12);

        var firstWrong = StageScorer.resolution(List.of(new Scored("1", "b", "T", false), new Scored("2", "a", "T", true),
                new Scored("3", "a", "T", true)));
        assertEquals(2, firstWrong.falseMerges(), "a Term's gold is its first mention's");

        var empty = StageScorer.resolution(List.<Scored>of());
        assertEquals(0, empty.attachments());
        assertNull(empty.falseMergeRate());
        assertEquals(1.0, empty.falseMergeBound());
        assertNull(empty.bcubedPrecision());
    }

    private static Decision asked(String stage, String subject, String from, String to, String choice, double p) {
        return new Decision(stage, subject, from, to, choice, p, false, null);
    }

    private static Case works(String status, String text, List<DateLabel> dates, String valid) {
        return new Case("w", List.of("plain"), text,
                List.of(Entity.of("operator", "The user", "Person"),
                        Entity.of("harborlight", "Harborlight Analytics", "Organization")),
                List.of(new Relation("operator", "works_at", "harborlight", status, valid, null, false)), List.of(),
                ANCHOR, dates);
    }

    private static String span(String text) {
        return TemporalExpressions.find(text, ANCHOR).found().getFirst().span();
    }

    @Test
    void deniedUnassertedAndInadmissibleEndedPairsAreNoRelationPairs() {
        for (var status : List.of(GraphCases.DENIED, GraphCases.UNASSERTED)) {
            var c = works(status, "The user does not work at Harborlight Analytics.", List.of(), null);
            var s = StageScorer.score(List.of(c), List.of(new StageRun("w", List.of(), List.of(),
                    List.of(relation("The user", "works_at", "Harborlight Analytics", 0.2)))), null, SCHEMA);
            assertEquals(0, s.relation().total(), status);
            assertEquals(1, s.noRelation().hit(), status);
            assertEquals(1, s.noRelation().total(), status);
        }
        var derived = new Case("d", List.of("plain"), "The Q3 report was derived from the Q2 report.",
                List.of(Entity.of("q3", "The Q3 report", "Artifact"), Entity.of("q2", "the Q2 report", "Artifact")),
                List.of(new Relation("q3", "derived_from", "q2", GraphCases.ENDED, null, null, false)), List.of());
        var s = StageScorer.score(List.of(derived), List.of(new StageRun("d", List.of(), List.of(),
                List.of(relation("The Q3 report", "derived_from", "the Q2 report", 0.9)))), null, SCHEMA);
        assertEquals(0, s.relation().total(), "an ended derived_from is no gold");
        assertEquals(0, s.noRelation().hit());
        assertEquals(1, s.noRelation().total());

        var ended = works(GraphCases.ENDED, "The user used to work at Harborlight Analytics.", List.of(), null);
        var admissible = StageScorer.score(List.of(ended), List.of(new StageRun("w", List.of(), List.of(),
                List.of(relation("The user", "works_at", "Harborlight Analytics", 0.9)))), null, SCHEMA);
        assertEquals(1, admissible.relation().hit(), "an admissible ended is gold");
    }

    @Test
    void aTenseDecisionOnANegativeSpanIsNeverARejection() {
        var tense = asked(ExtractionPipeline.TENSE, "data engineer", null, null, ExtractionPipeline.NOT_AN_ENTITY,
                0.9);
        var s = StageScorer.score(List.of(A), List.of(new StageRun("a", List.of(), List.of(tense), List.of())), null,
                SCHEMA);
        assertEquals(0, s.rejection().total());
        assertEquals(0, s.typing().total());
    }

    @Test
    void tenseIsRightOnTheReadingEqualToTheLabel() {
        var text = "The user moved to Ashgrove in June.";
        var june = span(text);
        var c = new Case("t", List.of("dated"), text,
                List.of(Entity.of("operator", "The user", "Person"), Entity.of("ashgrove", "Ashgrove", "Place")),
                List.of(), List.of(), ANCHOR, List.of(new DateLabel(june, "2026-06")));
        var run = new StageRun("t", List.of(), List.of(), List.of(),
                List.of(asked(ExtractionPipeline.TENSE, june, null, null, ExtractionPipeline.PAST, 0.9),
                        asked(ExtractionPipeline.TENSE, june, null, null, ExtractionPipeline.UPCOMING, 0.9)),
                List.of(), List.of(), List.of(), List.of());
        var s = StageScorer.score(List.of(c), List.of(run), null, SCHEMA);
        assertEquals(StageScorer.Ratio.of(1, 2), s.tense());
        assertEquals(StageScorer.Ratio.of(1, 1), s.dateRecall());
        assertEquals(StageScorer.Ratio.of(1, 1), s.normalizer());
    }

    @Test
    void occursIsRightWhenYesMatchesTheEventsGoldDate() {
        var text = "The user went to the Marrow Bay Retreat this June.";
        var june = span(text);
        var c = new Case("o", List.of("dated"), text,
                List.of(Entity.of("operator", "The user", "Person"),
                        new Entity("retreat", "Marrow Bay Retreat", "Event", List.of(), false, false, "2026-06")),
                List.of(), List.of(), ANCHOR, List.of(new DateLabel(june, "2026-06")));
        var other = new Case("o", List.of("dated"), text, c.entities().stream()
                .map(e -> e.occurs() == null ? e : new Entity(e.id(), e.mention(), e.type(), List.of(), false, false,
                        "2026-07")).toList(), List.of(), List.of(), ANCHOR, c.dates());
        var yes = asked(ExtractionPipeline.OCCURS, "Marrow Bay Retreat @ " + june, "Marrow Bay Retreat", june,
                "yes", 0.9);
        var run = new StageRun("o", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(yes), List.of(),
                List.of());
        assertEquals(StageScorer.Ratio.of(1, 1), StageScorer.score(List.of(c), List.of(run), null, SCHEMA).occurs());
        assertEquals(StageScorer.Ratio.of(0, 1), StageScorer.score(List.of(other), List.of(run), null, SCHEMA)
                .occurs(), "yes on a date that is not the event's");
    }

    private static Decision status(String choice) {
        return asked(ExtractionPipeline.STATUS, "The user -works_at-> Harborlight Analytics", "The user",
                "Harborlight Analytics", choice, 0.9);
    }

    private static StageRun statusRun(Decision... decisions) {
        return new StageRun("w", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(decisions),
                List.of());
    }

    @Test
    void statusIsRightOnTheGoldStatusOrUnstatedAndUnstatedOnAHoldingLabelIsAVeto() {
        var ended = works(GraphCases.ENDED, "The user used to work at Harborlight Analytics.", List.of(), null);
        var s = StageScorer.score(List.of(ended), List.of(statusRun(status(ExtractionPipeline.ENDED),
                status(ExtractionPipeline.HOLDS), status(ExtractionPipeline.UNSTATED))), null, SCHEMA);
        assertEquals(StageScorer.Ratio.of(1, 3), s.status());
        assertEquals(StageScorer.Ratio.of(1, 3), s.vetoRate());

        var denied = works(GraphCases.DENIED, "The user does not work at Harborlight Analytics.", List.of(), null);
        var d = StageScorer.score(List.of(denied), List.of(statusRun(status(ExtractionPipeline.UNSTATED),
                status(ExtractionPipeline.HOLDS))), null, SCHEMA);
        assertEquals(StageScorer.Ratio.of(1, 2), d.status());
        assertEquals(0, d.vetoRate().total(), "a denial is not vetoed by unstated");
    }

    @Test
    void slotIsRightOnTheBoundTheDateIs() {
        var text = "The user has worked at Harborlight Analytics since 2019.";
        var year = span(text);
        var c = works(GraphCases.HOLDS, text, List.of(new DateLabel(year, "2019")), "2019/..");
        var subject = "The user -works_at-> Harborlight Analytics @ " + year;
        var run = new StageRun("w", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(asked(ExtractionPipeline.SLOT, subject, "The user", "Harborlight Analytics",
                                ExtractionPipeline.FROM, 0.9),
                        asked(ExtractionPipeline.SLOT, subject, "The user", "Harborlight Analytics",
                                ExtractionPipeline.TO, 0.9)));
        assertEquals(StageScorer.Ratio.of(1, 2), StageScorer.score(List.of(c), List.of(run), null, SCHEMA).slot());
    }

    @Test
    void negationReportsTheYesRateOnDenialsAndTheNoRateOnPositives() {
        var denied = works(GraphCases.DENIED, "The user does not work at Harborlight Analytics.", List.of(), null);
        var holds = new Case("h", List.of("plain"), denied.text(), denied.entities(),
                List.of(Relation.of("operator", "works_at", "harborlight")), List.of());
        var yes = asked(ExtractionPipeline.NEGATION, "The user -> Harborlight Analytics", "The user",
                "Harborlight Analytics", "works_at", 0.9);
        var no = asked(ExtractionPipeline.NEGATION, "The user -> Harborlight Analytics", "The user",
                "Harborlight Analytics", "works_at", 0.1);
        var s = StageScorer.score(List.of(denied, holds), List.of(
                new StageRun("w", List.of(), List.of(), List.of(), List.of(), List.of(yes, no), List.of(), List.of(),
                        List.of()),
                new StageRun("h", List.of(), List.of(), List.of(), List.of(), List.of(yes, no), List.of(), List.of(),
                        List.of())), null, SCHEMA);
        assertEquals(StageScorer.Ratio.of(1, 2), s.negationYes());
        assertEquals(StageScorer.Ratio.of(1, 2), s.negationNo());
    }
}
