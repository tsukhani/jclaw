import memory.graph.GraphWithdrawal;
import memory.graph.GraphWithdrawal.LineageDecision;
import memory.graph.GraphWithdrawal.Retirement;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import models.MemoryAuthorType;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class MemoryGraphWithdrawalTest extends UnitTest {

    private static Meta meta(String id) {
        return Meta.fresh(id, 7L, Tier.TENTATIVE);
    }

    private static Evidence evidence(String id, String source) {
        return new Evidence(meta(id), source, null);
    }

    private static OntologyRecord byId(List<OntologyRecord> records, String id) {
        return records.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private static Set<String> ids(List<OntologyRecord> records) {
        var out = new HashSet<String>();
        records.forEach(r -> out.add(r.id()));
        return out;
    }

    private static void assertValid(List<OntologyRecord> records) {
        assertEquals(List.of(), OntologyValidator.validate(OntologySchema.seed(), records));
    }

    /**
     * T rests on e5 and e6; R on T rests on e5 alone; O rests on the near-miss memory:50.
     * Every arm of the cascade hangs off T so withdrawing its last evidence reaches them all.
     */
    private static List<OntologyRecord> graph() {
        return List.of(
                evidence("e5", "memory:5"),
                evidence("e6", "memory:6"),
                evidence("e50", "memory:50"),
                new Evidence(meta("eS"), "message:2", "T"),
                new Term(meta("T"), "Person", "Ada", List.of("mT"), List.of("e5", "e6")),
                new Term(meta("O"), "Organization", "Acme", List.of("mO"), List.of("e50")),
                new Term(meta("P"), "Person", "Bob", List.of(), List.of("e50")),
                new Relation(meta("R"), "works_at", "T", "O", List.of("e5")),
                new Relation(meta("R2"), "works_at", "T", "O", List.of("e50")),
                new Relation(meta("R3"), "family_of", "P", "T", List.of("e50")),
                new Mapping(meta("mT"), "T", "message:1", List.of("e50")),
                new Mapping(meta("mO"), "O", "memory:7", List.of("e50")),
                new Constraint(meta("cT"), "T", "only at work", List.of("e50")));
    }

    @Test
    void withdrawingOneOfTwoSourcesKeepsTheTermAndDropsWhatRestedOnlyOnIt() {
        var graph = graph();
        assertValid(graph);

        var result = GraphWithdrawal.withdraw(graph, Set.of("memory:5"));

        assertEquals(Set.of("e5", "R"), result.removedIds());
        assertEquals(List.of("e6"), ((Term) byId(result.survivors(), "T")).evidenceIds());
        assertTrue(ids(result.survivors()).contains("e50"), "memory:50 is not memory:5");
        assertValid(result.survivors());
    }

    @Test
    void aPruneKeepsATermsAliasesAndMergeAndAMappingsSurfaces() {
        var graph = List.<OntologyRecord>of(
                evidence("e5", "memory:5"),
                evidence("e6", "memory:6"),
                new Term(meta("T"), "Person", "Ada", List.of("mT"), List.of("e5", "e6"), List.of("Ada L."), "T0"),
                new Mapping(meta("mT"), "T", "message:1", List.of("e5", "e6"), List.of("Ada", "my aunt")));

        var survivors = GraphWithdrawal.withdraw(graph, Set.of("memory:5")).survivors();

        assertEquals(new Term(meta("T"), "Person", "Ada", List.of("mT"), List.of("e6"), List.of("Ada L."), "T0"),
                byId(survivors, "T"));
        assertEquals(new Mapping(meta("mT"), "T", "message:1", List.of("e6"), List.of("Ada", "my aunt")),
                byId(survivors, "mT"));
    }

    @Test
    void withdrawingTheLastSourceCascadesThroughEveryArm() {
        var afterFive = GraphWithdrawal.withdraw(graph(), Set.of("memory:5")).survivors();

        var result = GraphWithdrawal.withdraw(afterFive, Set.of("memory:6"));

        // T loses its last evidence; mT and cT go by termId, R2 by from, R3 by to, eS by subjectId.
        assertEquals(Set.of("e6", "T", "mT", "cT", "R2", "R3", "eS"), result.removedIds());
        assertEquals(Set.of("e50", "O", "P", "mO"), ids(result.survivors()));
        assertValid(result.survivors());
    }

    @Test
    void aMappingGoesByItsOwnMemorySourceAndItsTermForgetsIt() {
        var result = GraphWithdrawal.withdraw(graph(), Set.of("memory:7"));

        assertEquals(Set.of("mO"), result.removedIds());
        assertEquals(List.of(), ((Term) byId(result.survivors(), "O")).mappingIds());
        assertValid(result.survivors());
    }

    @Test
    void aSourceNothingNamesRemovesNothing() {
        var graph = graph();
        var result = GraphWithdrawal.withdraw(graph, Set.of("memory:99", "memory:"));
        assertEquals(Set.of(), result.removedIds());
        assertEquals(graph, result.survivors());
    }

    // ---- retirement and lineage ----

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-02T00:00:00Z");

    private static Evidence ev(List<OntologyRecord> records, String id) {
        return (Evidence) byId(records, id);
    }

    private static Evidence stamped(String id, String source, Instant at, String by, Lineage lineage,
            LocalDate changedBy) {
        return new Evidence(meta(id), source, null, null, null, null, null, at, by, lineage, changedBy, null, null,
                null, null, null);
    }

    /** A claim on R anchored at 2026-03-01. */
    private static Evidence claim(String id, String source) {
        return new Evidence(meta(id), source, "R", MemoryAuthorType.HUMAN_TURN, null, null,
                Instant.parse("2026-03-01T09:00:00Z"), null, null, null, null, LocalDate.parse("2026-03-01"),
                Status.HOLDS, EdtfInterval.parse("2026-03-01/.."), null, null);
    }

    @Test
    void retireSetsChangesAndClearsTheStampOnEveryEvidenceOfTheSourceOnly() {
        var graph = graph();

        var set = GraphWithdrawal.retire(graph, Map.of("memory:5", new Retirement(AT, "memory:9")));
        assertEquals(1, set.evidenceRetired());
        assertEquals(0, set.lineageCleared());
        assertEquals(AT, ev(set.records(), "e5").retiredAt());
        assertEquals("memory:9", ev(set.records(), "e5").retiredBy());
        assertNull(ev(set.records(), "e50").retiredAt(), "memory:50 is not memory:5");
        assertEquals(ids(graph), ids(set.records()), "nothing is removed");
        for (var r : set.records()) {
            if (!r.id().equals("e5")) assertEquals(byId(graph, r.id()), r, r.id());
        }
        assertEquals(graph.stream().map(OntologyRecord::id).toList(),
                set.records().stream().map(OntologyRecord::id).toList(), "record order is kept");
        assertValid(set.records());

        var changed = GraphWithdrawal.retire(set.records(), Map.of("memory:5", new Retirement(LATER, "memory:10")));
        assertEquals(1, changed.evidenceRetired());
        assertEquals(LATER, ev(changed.records(), "e5").retiredAt());
        assertEquals("memory:10", ev(changed.records(), "e5").retiredBy());
        assertValid(changed.records());

        var cleared = GraphWithdrawal.retire(changed.records(), Map.of("memory:5", Retirement.CLEARED));
        assertEquals(1, cleared.evidenceRetired());
        assertEquals(graph, cleared.records());
    }

    @Test
    void anIdenticalStampReturnsEqualRecordsAndZeroCounts() {
        var once = GraphWithdrawal.retire(graph(), Map.of("memory:5", new Retirement(AT, "memory:9"))).records();

        var again = GraphWithdrawal.retire(once, Map.of("memory:5", new Retirement(AT, "memory:9")));

        assertEquals(once, again.records());
        assertEquals(0, again.evidenceRetired());
        assertEquals(0, again.lineageCleared());
        var lineaged = GraphWithdrawal.recordLineage(once, Map.of("memory:5",
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), new Retirement(AT, "memory:9"))));
        assertTrue(lineaged.changed());
        assertFalse(GraphWithdrawal.recordLineage(lineaged.records(), Map.of("memory:5",
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), new Retirement(AT, "memory:9"))))
                .changed());
    }

    @Test
    void lineageIsClearedWhenRetiredByChangesOrClearsAndKeptWhenOnlyRetiredAtChanges() {
        var e = stamped("e5", "memory:5", AT, "memory:9", Lineage.UPDATE, LocalDate.parse("2026-10-01"));
        var graph = List.<OntologyRecord>of(e);

        var atOnly = GraphWithdrawal.retire(graph, Map.of("memory:5", new Retirement(LATER, "memory:9")));
        assertEquals(1, atOnly.evidenceRetired());
        assertEquals(0, atOnly.lineageCleared());
        assertEquals(Lineage.UPDATE, ev(atOnly.records(), "e5").lineage());
        assertEquals(LocalDate.parse("2026-10-01"), ev(atOnly.records(), "e5").changedBy());

        var byChanged = GraphWithdrawal.retire(graph, Map.of("memory:5", new Retirement(AT, "memory:10")));
        assertEquals(1, byChanged.lineageCleared());
        assertNull(ev(byChanged.records(), "e5").lineage());
        assertNull(ev(byChanged.records(), "e5").changedBy());

        var byGone = GraphWithdrawal.retire(graph, Map.of("memory:5", new Retirement(AT, null)));
        assertEquals(1, byGone.evidenceRetired());
        assertEquals(1, byGone.lineageCleared());
        assertEquals(AT, ev(byGone.records(), "e5").retiredAt());
        assertNull(ev(byGone.records(), "e5").retiredBy());
        assertNull(ev(byGone.records(), "e5").lineage());
        assertNull(ev(byGone.records(), "e5").changedBy());
        assertValid(byGone.records());

        var unsuperseded = GraphWithdrawal.retire(graph, Map.of("memory:5", Retirement.CLEARED));
        assertEquals(List.of(new Evidence(meta("e5"), "memory:5", null)), unsuperseded.records());
        assertEquals(1, unsuperseded.lineageCleared());
    }

    @Test
    void anUpdateBeforeAClaimsAnchorWritesNoLineageOnThatClaim() {
        var graph = List.<OntologyRecord>of(
                new Term(meta("T"), "Person", "Ada", List.of(), List.of("e5")),
                new Term(meta("O"), "Organization", "Acme", List.of(), List.of("e5")),
                new Relation(meta("R"), "works_at", "T", "O", List.of("e5", "c5")),
                evidence("e5", "memory:5"),
                claim("c5", "memory:5"));
        var retirement = new Retirement(AT, "memory:9");

        var before = GraphWithdrawal.recordLineage(graph, Map.of("memory:5",
                new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-02-28"), retirement))).records();
        assertEquals(AT, ev(before, "c5").retiredAt(), "the claim is still retired");
        assertNull(ev(before, "c5").lineage());
        assertNull(ev(before, "c5").changedBy());
        assertEquals(Lineage.UPDATE, ev(before, "e5").lineage(), "claim-free Evidence takes the lineage");
        assertEquals(LocalDate.parse("2026-02-28"), ev(before, "e5").changedBy());
        assertValid(before);

        for (var day : List.of("2026-03-01", "2026-03-02")) {
            var on = GraphWithdrawal.recordLineage(graph, Map.of("memory:5",
                    new LineageDecision(Lineage.UPDATE, LocalDate.parse(day), retirement))).records();
            assertEquals(Lineage.UPDATE, ev(on, "c5").lineage(), day);
            assertEquals(LocalDate.parse(day), ev(on, "c5").changedBy(), day);
            assertValid(on);
        }
    }

    @Test
    void aDecisionNamingNoSuccessorWritesNoLineage() {
        for (var retirement : List.of(new Retirement(AT, null), Retirement.CLEARED)) {
            var out = GraphWithdrawal.recordLineage(graph(), Map.of("memory:5",
                    new LineageDecision(Lineage.UPDATE, LocalDate.parse("2026-10-01"), retirement)));
            assertEquals(retirement.at(), ev(out.records(), "e5").retiredAt());
            assertNull(ev(out.records(), "e5").lineage(), String.valueOf(retirement));
            assertNull(ev(out.records(), "e5").changedBy(), String.valueOf(retirement));
            assertValid(out.records());
        }
    }

    @Test
    void restatementAndCorrectionWriteNoChangedBy() {
        for (var lineage : List.of(Lineage.RESTATEMENT, Lineage.CORRECTION)) {
            var out = GraphWithdrawal.recordLineage(graph(), Map.of("memory:5",
                    new LineageDecision(lineage, LocalDate.parse("2026-10-01"), new Retirement(AT, "memory:9"))));
            assertTrue(out.changed());
            assertEquals(lineage, ev(out.records(), "e5").lineage());
            assertNull(ev(out.records(), "e5").changedBy());
            assertEquals(byId(graph(), "T"), byId(out.records(), "T"), "other families are untouched");
            assertEquals(byId(graph(), "mO"), byId(out.records(), "mO"));
            assertValid(out.records());
        }
    }

    // ---- retraction by Evidence id (JCLAW-1371) ----

    @Test
    void withdrawingEvidenceByIdCascadesAsASourceWithdrawalDoes() {
        var graph = graph();
        var byId = GraphWithdrawal.withdrawEvidence(graph, Set.of("e5"));
        assertEquals(GraphWithdrawal.withdraw(graph, Set.of("memory:5")), byId);
        assertEquals(Set.of("e5", "R"), byId.removedIds());

        var last = GraphWithdrawal.withdrawEvidence(graph, Set.of("e5", "e6"));
        assertFalse(ids(last.survivors()).contains("T"), "T lost its last Evidence");
        assertTrue(last.removedIds().containsAll(Set.of("T", "R", "R2", "mT", "cT", "eS")), last.removedIds().toString());
        assertValid(last.survivors());
    }

    @Test
    void anEvidenceIdThatNamesAnotherFamilyOrNothingRemovesNothing() {
        var graph = graph();
        var result = GraphWithdrawal.withdrawEvidence(graph, Set.of("T", "R", "e-none"));
        assertEquals(Set.of(), result.removedIds());
        assertEquals(graph, result.survivors());
    }

    @Test
    void clearLineageNullsOnlyTheNamedSuccessorsStampsAndKeepsTheRetirement() {
        var graph = List.<OntologyRecord>of(
                stamped("e5", "memory:5", AT, "memory:9", Lineage.UPDATE, LocalDate.parse("2026-10-01")),
                stamped("e6", "memory:6", AT, "memory:8", Lineage.CORRECTION, null),
                new Evidence(meta("e7"), "memory:7", null, null, null, null, null, AT, "memory:9", null, null, null,
                        null, null, null, null),
                evidence("e1", "memory:9"));

        var cleared = GraphWithdrawal.clearLineage(graph, Set.of("memory:9"));

        assertEquals(1, cleared.lineageCleared(), "e7 carried no lineage to clear");
        var e5 = ev(cleared.records(), "e5");
        assertNull(e5.lineage());
        assertNull(e5.changedBy());
        assertEquals(AT, e5.retiredAt());
        assertEquals("memory:9", e5.retiredBy());
        assertEquals(Lineage.CORRECTION, ev(cleared.records(), "e6").lineage(), "memory:8 still stands");
        assertEquals(graph.subList(1, 4), cleared.records().subList(1, 4));
        assertEquals(0, GraphWithdrawal.clearLineage(graph, Set.of()).lineageCleared());
    }
}
