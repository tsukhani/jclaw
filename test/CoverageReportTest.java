import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.CompetencyQuestions;
import services.grapheval.CompetencyQuestions.Question;
import services.grapheval.CoverageReport;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Entity;
import services.grapheval.GraphCases.Relation;
import services.grapheval.HeldOut;
import services.grapheval.StageScorer.StageRun;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JCLAW-1374: every section of the coverage report over fixture labels, and the typing confusion matrix. */
class CoverageReportTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();

    private static Entity entity(String id, String mention, String type, boolean noise) {
        return new Entity(id, mention, type, List.of(), false, noise);
    }

    private static Relation relation(String from, String type, String to, String status) {
        return new Relation(from, type, to, status, null, null, false);
    }

    private static HeldOut.HeldCase held(long id, List<String> tags, List<Entity> entities, List<Relation> relations,
                                         MemoryAuthorType author, HeldOut.@Nullable Coverage coverage) {
        return new HeldOut.HeldCase(id, new Case("h" + id, tags, "fixture memory " + id, entities, relations,
                List.of()), author, coverage);
    }

    /** Two named, a timed works_at and an Organization using a System; a quantity, an instruction, a back reference. */
    private static final HeldOut.HeldCase C1 = held(1, List.of(),
            List.of(entity("operator", "The user", "Person", false),
                    entity("harborlight", "Harborlight Analytics", "Organization", false),
                    entity("kestrel", "Kestrel CI", "System", false)),
            List.of(new Relation("operator", "works_at", "harborlight", "holds", "2019/..", null, false),
                    relation("harborlight", "uses", "kestrel", "holds")),
            MemoryAuthorType.HUMAN_TURN,
            new HeldOut.Coverage(List.of("quantity", "instruction"), List.of("identifier", "quantity"), true));

    /** A guest's: an implicit operator's favorable view, and a Project using a System only planned; three named. */
    private static final HeldOut.HeldCase C2 = held(2, List.of("unasserted"),
            List.of(Entity.implicitOperator(), entity("oat-milk", "oat milk", "Topic", false),
                    entity("atlas", "Atlas", "Project", false), entity("fenwick", "Fenwick", "System", false)),
            List.of(new Relation("operator", "holds_view_on", "oat-milk", "holds", null, "favorable", false),
                    relation("atlas", "uses", "fenwick", "unasserted")),
            MemoryAuthorType.GUEST_TURN,
            new HeldOut.Coverage(List.of("plan"), List.of(), false));

    /** A dated Event involving the owner, a denied works_at, a noise home; five named, two of them noise. */
    private static final HeldOut.HeldCase C3 = held(3, List.of("ended"),
            List.of(entity("operator", "Avery Lin", "Person", false),
                    new Entity("quillon", "Quillon Summit", "Event", List.of(), false, false, "2026-05"),
                    entity("ashgrove", "Ashgrove", "Place", true),
                    entity("ostrander", "Ostrander Freight", "Organization", false),
                    entity("jonah", "Jonah Pell", "Person", true),
                    entity("plan", "plan.pdf", "Artifact", false)),
            List.of(relation("quillon", "involves", "operator", "holds"),
                    new Relation("operator", "located_in", "ashgrove", "ended", null, null, true),
                    relation("operator", "works_at", "ostrander", "denied")),
            MemoryAuthorType.HUMAN_TURN,
            new HeldOut.Coverage(List.of(), List.of("date"), false));

    /** Only the implicit owner: zero named. */
    private static final HeldOut.HeldCase C5 = held(5, List.of(), List.of(Entity.implicitOperator()), List.of(), null,
            new HeldOut.Coverage(List.of("routine"), List.of(), false));

    /** No coverage fields: it would move every count it touched, so it must touch none. */
    private static final HeldOut.HeldCase C4 = held(4, List.of("negated"),
            List.of(Entity.implicitOperator(), entity("zephyr", "Zephyr", "Project", false),
                    entity("gull", "Gull", "System", false)),
            List.of(relation("zephyr", "uses", "gull", "holds")),
            MemoryAuthorType.GUEST_TURN, null);

    private static final List<HeldOut.HeldCase> CASES = List.of(C1, C2, C3, C4, C5);

    private static Question q(String id, @Nullable String relation, @Nullable String facet, String... types) {
        return new Question(id, "a generic question " + id, List.of(types), relation, facet);
    }

    private static final List<Question> QUESTIONS = List.of(
            q("q01", "works_at", "status", "Person", "Organization"),
            q("q02", "uses", null, "Project", "System"),
            q("q03", "holds_view_on", "valence", "Person", "Topic"),
            q("q04", null, "plan", "Person"),
            q("q05", "uses", null, "Organization", "System"),
            q("q06", "works_at", "valid", "Person", "Organization"),
            q("q07", null, "occurs", "Event"),
            q("q08", "involves", "occurs", "Event", "Person"),
            q("q09", null, null, "Person"),
            q("q10", "located_in", null, "Person", "Place"),
            q("q11", "works_at", "relation", "Person", "Organization"));

    @Test
    void eachQuestionCountsByItsFacet() {
        var counts = CoverageReport.sections(CASES, QUESTIONS, SCHEMA).questions();
        assertNotNull(counts);
        var expected = List.of(
                new CoverageReport.QuestionCount("q01", "status", 2, null),
                new CoverageReport.QuestionCount("q02", null, 0, null),
                new CoverageReport.QuestionCount("q03", "valence", 1, null),
                new CoverageReport.QuestionCount("q04", "plan", null, 1),
                new CoverageReport.QuestionCount("q05", null, 1, null),
                new CoverageReport.QuestionCount("q06", "valid", 1, null),
                new CoverageReport.QuestionCount("q07", "occurs", 1, null),
                new CoverageReport.QuestionCount("q08", "occurs", 1, null),
                new CoverageReport.QuestionCount("q09", null, 0, null),
                new CoverageReport.QuestionCount("q10", null, 0, null),
                new CoverageReport.QuestionCount("q11", "relation", null, 0));
        assertEquals(expected, counts, "q01 counts the denial; q02 skips the unasserted and the uncounted; "
                + "q09 never counts the operator or a noise Person; q10 skips a noise relation");
    }

    @Test
    void anImplicitOperatorIsAPersonEndpointButNoNamedEntity() {
        var view = List.of(q("q01", "holds_view_on", null, "Person", "Topic"), q("q02", null, null, "Person"));
        var counts = CoverageReport.sections(List.of(C2, C5), view, SCHEMA).questions();
        assertNotNull(counts);
        assertEquals(1, counts.get(0).represented());
        assertEquals(0, counts.get(1).represented());
    }

    @Test
    void theGridIsEveryAllowedCellWithItsMemoriesAndQuestions() {
        var grid = CoverageReport.sections(CASES, QUESTIONS, SCHEMA).grid();
        assertEquals(43, grid.allowed(), "schema v3 allows 43 cells");
        assertEquals(grid.allowed(), grid.cells().size());
        Map<String, CoverageReport.Cell> byKey = new LinkedHashMap<>();
        grid.cells().forEach(c -> byKey.put(c.from() + " " + c.relation() + " " + c.to(), c));
        assertEquals(new CoverageReport.Cell("Person", "works_at", "Organization", 2, List.of("q01", "q06", "q11")),
                byKey.get("Person works_at Organization"));
        assertEquals(new CoverageReport.Cell("Organization", "uses", "System", 1, List.of("q05")),
                byKey.get("Organization uses System"));
        assertEquals(new CoverageReport.Cell("Project", "uses", "System", 0, List.of("q02")),
                byKey.get("Project uses System"));
        assertEquals(new CoverageReport.Cell("Person", "holds_view_on", "Topic", 1, List.of("q03")),
                byKey.get("Person holds_view_on Topic"));
        assertEquals(new CoverageReport.Cell("Event", "involves", "Person", 1, List.of("q08")),
                byKey.get("Event involves Person"));
        assertEquals(new CoverageReport.Cell("Person", "located_in", "Place", 0, List.of("q10")),
                byKey.get("Person located_in Place"));
        assertEquals(4, grid.covered());
        assertFalse(byKey.containsKey("Organization works_at Person"));
    }

    @Test
    void notRepresentableNumbersAndBackReferenceCountMemories() {
        var s = CoverageReport.sections(CASES, null, SCHEMA);
        var notRepresentable = s.notRepresentable();
        assertEquals(CompetencyQuestions.NOT_REPRESENTABLE, List.copyOf(notRepresentable.byKind().keySet()));
        assertEquals(1, notRepresentable.byKind().get("quantity"));
        assertEquals(1, notRepresentable.byKind().get("instruction"));
        assertEquals(1, notRepresentable.byKind().get("plan"));
        assertEquals(1, notRepresentable.byKind().get("routine"));
        assertEquals(0, notRepresentable.byKind().get("health"));
        assertEquals(4, notRepresentable.byKind().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(3, notRepresentable.any());
        assertEquals(0.75, notRepresentable.share());

        var numbers = s.numbers();
        assertEquals(CompetencyQuestions.NUMBER_KINDS, List.copyOf(numbers.byKind().keySet()));
        assertEquals(Map.of("date", 1, "duration", 0, "clock-time", 0, "identifier", 1, "quantity", 1, "other", 0),
                numbers.byKind());
        assertEquals(2, numbers.any());
        assertEquals(0.5, numbers.share());

        assertEquals(new CoverageReport.Share(1, 0.25), s.backReference());
        assertEquals(new CoverageReport.Labelled(4, 1), s.labelled());
    }

    @Test
    void everyStratumCountsItsMemoriesAndShare() {
        var strata = CoverageReport.sections(CASES, null, SCHEMA).strata();
        assertEquals(CoverageReport.STRATA, List.copyOf(strata.keySet()));
        var expected = new LinkedHashMap<String, Integer>();
        expected.put("zero-named", 1);
        expected.put("one-named", 0);
        expected.put("two-named", 1);
        expected.put("three-or-more-named", 2);
        expected.put("owner-not-named", 2);
        expected.put("negation", 1);
        expected.put("plans-or-uncertainty", 1);
        expected.put("numbers", 2);
        expected.put("quantity", 1);
        expected.put("agent-instruction", 1);
        expected.put("ended", 1);
        expected.put("guest", 1);
        expected.forEach((name, count) -> assertEquals(new CoverageReport.Share(count, count / 4.0), strata.get(name),
                name));
    }

    @Test
    void aTagAlonePlacesAMemoryInItsStratum() {
        var owner = List.of(entity("operator", "The user", "Person", false),
                entity("harborlight", "Harborlight Analytics", "Organization", false));
        var holds = List.of(relation("operator", "works_at", "harborlight", "holds"));
        var coverage = new HeldOut.Coverage(List.of(), List.of(), false);
        var cases = List.of(
                held(11, List.of("negated"), owner, holds, MemoryAuthorType.HUMAN_TURN, coverage),
                held(12, List.of("unasserted"), owner, holds, MemoryAuthorType.HUMAN_TURN, coverage),
                held(13, List.of("ended"), owner, holds, MemoryAuthorType.HUMAN_TURN, coverage));
        var strata = CoverageReport.sections(cases, null, SCHEMA).strata();
        for (var name : List.of("negation", "plans-or-uncertainty", "ended")) {
            assertEquals(new CoverageReport.Share(1, 1 / 3.0), strata.get(name), name);
        }
    }

    @Test
    void noCountedCaseCountsZeroAndSharesNothing() {
        var s = CoverageReport.sections(List.of(C4), null, SCHEMA);
        assertNull(s.questions());
        assertEquals(new CoverageReport.Labelled(0, 1), s.labelled());
        assertEquals(0, s.grid().covered());
        assertTrue(s.grid().cells().stream().allMatch(c -> c.memories() == 0 && c.questions().isEmpty()));
        assertEquals(0, s.notRepresentable().any());
        assertNull(s.notRepresentable().share());
        assertNull(s.numbers().share());
        assertEquals(new CoverageReport.Share(0, null), s.backReference());
        s.strata().forEach((name, share) -> assertEquals(new CoverageReport.Share(0, null), share, name));
    }

    private static Decision typed(String subject, @Nullable String choice) {
        return new Decision(ExtractionPipeline.TERM, subject, null, null, choice, choice == null ? 0 : 0.9, false,
                choice == null ? "timeout" : null);
    }

    private static StageRun run(String caseId, Decision... typing) {
        return new StageRun(caseId, List.of(), List.of(typing), List.of());
    }

    private static Map<String, Integer> row(int project, int system, int artifact, int other, int notAnEntity,
                                            int failed) {
        var row = new LinkedHashMap<String, Integer>();
        row.put("Project", project);
        row.put("System", system);
        row.put("Artifact", artifact);
        row.put(CoverageReport.OTHER, other);
        row.put(CoverageReport.NOT_AN_ENTITY, notAnEntity);
        row.put(CoverageReport.FAILED, failed);
        return row;
    }

    @Test
    void theConfusionMatrixCountsEveryRunsDecisionOnACountedGoldProjectSystemOrArtifact() {
        var byModel = new LinkedHashMap<String, List<StageRun>>();
        byModel.put("tev1", List.of(
                run("1", typed("Kestrel CI", "System"), typed("Harborlight Analytics", "System")),
                run("2", typed("Atlas", "Artifact"), typed("Fenwick", "Topic"), typed("oat milk", "Project")),
                run("3", typed("plan.pdf", null)),
                run("4", typed("Zephyr", "System"), typed("Gull", "Project")),
                run("1", typed("Kestrel CI", ExtractionPipeline.NOT_AN_ENTITY)),
                run("2", typed("Atlas", "Project"),
                        new Decision("overlap", "Fenwick", null, null, "Fenwick", 0.9, false, null))));
        byModel.put("nimble", List.of());

        var confusions = CoverageReport.confusions(CASES, byModel);
        assertEquals(List.of("tev1", "nimble"), confusions.stream().map(CoverageReport.Confusion::model).toList());
        var rows = confusions.getFirst().rows();
        assertEquals(CoverageReport.CONFUSED, List.copyOf(rows.keySet()));
        assertEquals(row(1, 0, 1, 0, 0, 0), rows.get("Project"), "Zephyr's case lacks the fields");
        assertEquals(row(0, 1, 0, 1, 1, 0), rows.get("System"), "Harborlight is an Organization: no row");
        assertEquals(row(0, 0, 0, 0, 0, 1), rows.get("Artifact"));
        var empty = confusions.get(1).rows();
        CoverageReport.CONFUSED.forEach(gold -> assertEquals(row(0, 0, 0, 0, 0, 0), empty.get(gold), gold));
    }
}
