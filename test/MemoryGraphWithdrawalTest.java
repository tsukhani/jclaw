import memory.graph.GraphWithdrawal;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Constraint;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.HashSet;
import java.util.List;
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
}
