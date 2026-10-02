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
import memory.ontology.OntologyValidator.Kind;
import memory.ontology.OntologyValidator.Violation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

class OntologyValidatorTest extends UnitTest {

    private static final long AGENT = 7L;
    private static final OntologySchema SCHEMA = OntologySchema.seed();

    private static Meta meta(String id) {
        return Meta.fresh(id, AGENT, Tier.TENTATIVE);
    }

    private static Evidence evidence(String id) {
        return new Evidence(meta(id), "memory:" + id, null);
    }

    private static Term term(String id, String type) {
        return new Term(meta(id), type, id, List.of(), List.of("e1"));
    }

    private static Relation relation(String id, String type, String from, String to) {
        return new Relation(meta(id), type, from, to, List.of("e1"));
    }

    /** Every family represented, every reference resolved, every type declared. */
    private static List<OntologyRecord> cleanSet() {
        return new ArrayList<>(List.of(
                evidence("e1"),
                new Evidence(meta("e2"), "message:9", "p1"),
                new Term(meta("p1"), "Person", "Ada", List.of("m1"), List.of("e1")),
                new Mapping(meta("m1"), "p1", "message:9", List.of("e1", "e2")),
                term("o1", "Organization"),
                term("pr1", "Project"),
                term("a1", "Artifact"),
                term("t1", "Topic"),
                term("t2", "Topic"),
                term("pl1", "Place"),
                term("pl2", "Place"),
                relation("r1", "works_at", "p1", "o1"),
                new Constraint(meta("c1"), "p1", "only in work contexts", List.of("e1"))));
    }

    private static List<Violation> validate(List<OntologyRecord> records) {
        return OntologyValidator.validate(SCHEMA, records);
    }

    private static List<OntologyRecord> with(OntologyRecord... extra) {
        var records = cleanSet();
        records.addAll(List.of(extra));
        return records;
    }

    @Test
    void freshRecordsStartWithZeroedCountersAndNeutralWeight() {
        var m = meta("x");
        assertEquals(0, m.usefulRecallCount());
        assertNull(m.lastUsefulRecall());
        assertEquals(1, m.graphVersion());
        assertEquals(1.0, relation("r", "uses", "a", "b").weight());
    }

    @Test
    void aCleanSetHasNoViolations() {
        assertEquals(List.of(), validate(cleanSet()));
    }

    @Test
    void undeclaredTypesAreReportedAndSkipTheEndpointCheck() {
        var violations = validate(with(term("v1", "Vehicle"), relation("r9", "likes", "p1", "t1")));
        assertEquals(List.of(
                new Violation("r9", Kind.UNDECLARED_TYPE, "relation r9: type 'likes' is not declared"),
                new Violation("v1", Kind.UNDECLARED_TYPE, "term v1: type 'Vehicle' is not declared")), violations);
    }

    @Test
    void disallowedEndpointsAreReported() {
        var violations = validate(with(
                relation("r2", "works_at", "p1", "pr1"),
                relation("r3", "part_of", "a1", "a1"),
                relation("r4", "kind_of", "t1", "p1")));
        assertEquals(List.of(
                new Violation("r2", Kind.DISALLOWED_ENDPOINT, "relation r2: works_at does not allow Person -> Project"),
                new Violation("r3", Kind.DISALLOWED_ENDPOINT,
                        "relation r3: part_of does not allow Artifact -> Artifact"),
                new Violation("r4", Kind.DISALLOWED_ENDPOINT, "relation r4: kind_of does not allow Topic -> Person")),
                violations);
    }

    @Test
    void theAllowedNeighboursOfEachDisallowedPairPass() {
        assertEquals(List.of(), validate(with(
                relation("r2", "part_of", "a1", "pr1"),
                relation("r3", "kind_of", "t1", "t2"),
                relation("r4", "works_on", "o1", "pr1"),
                relation("r5", "same_as", "pl1", "pl2"))));
    }

    @Test
    void anEndpointTermOfUndeclaredTypeSkipsTheEndpointCheck() {
        var violations = validate(with(term("v1", "Vehicle"), relation("r2", "owns", "p1", "v1")));
        assertEquals(List.of(new Violation("v1", Kind.UNDECLARED_TYPE, "term v1: type 'Vehicle' is not declared")),
                violations);
    }

    @Test
    void unresolvedReferencesNameTheFieldAndWhatTheyFound() {
        var violations = validate(with(
                relation("r2", "works_at", "p1", "nobody"),
                relation("r3", "works_at", "e1", "o1"),
                new Mapping(meta("m2"), "e1", "message:1", List.of("e1")),
                new Term(meta("p2"), "Person", "Bob", List.of("m1"), List.of("e1")),
                new Evidence(meta("e3"), "memory:3", "ghost"),
                new Evidence(meta("e4"), "memory:4", "e4"),
                new Constraint(meta("c2"), "p1", "rule", List.of("t1"))));
        assertEquals(List.of(
                new Violation("c2", Kind.UNRESOLVED_REFERENCE, "constraint c2: evidenceIds 't1' is a Term, not an Evidence"),
                new Violation("e3", Kind.UNRESOLVED_REFERENCE, "evidence e3: subjectId 'ghost' is unresolved"),
                new Violation("e4", Kind.UNRESOLVED_REFERENCE, "evidence e4: subjectId 'e4' names the evidence itself"),
                new Violation("m2", Kind.UNRESOLVED_REFERENCE, "mapping m2: termId 'e1' is an Evidence, not a Term"),
                new Violation("p2", Kind.UNRESOLVED_REFERENCE, "term p2: mappingIds 'm1' grounds 'p1', not this term"),
                new Violation("r2", Kind.UNRESOLVED_REFERENCE, "relation r2: to 'nobody' is unresolved"),
                new Violation("r3", Kind.UNRESOLVED_REFERENCE, "relation r3: from 'e1' is an Evidence, not a Term")),
                violations);
    }

    @Test
    void evidenceWithoutASubjectAndASubjectOfAnyFamilyResolve() {
        assertEquals(List.of(), validate(with(
                new Evidence(meta("e3"), "memory:3", null),
                new Evidence(meta("e4"), "memory:4", "r1"),
                new Evidence(meta("e5"), "memory:5", "e1"))));
    }

    @Test
    void missingEvidenceIsReportedForEveryFamilyThatNeedsIt() {
        var violations = validate(with(
                new Term(meta("t9"), "Topic", "x", List.of(), List.of()),
                new Mapping(meta("m9"), "t1", "file:/a", List.of()),
                new Relation(meta("r9"), "kind_of", "t1", "t2", List.of()),
                new Constraint(meta("c9"), "t1", "rule", List.of())));
        assertEquals(List.of(
                new Violation("c9", Kind.MISSING_EVIDENCE, "constraint c9: evidenceIds is empty"),
                new Violation("m9", Kind.MISSING_EVIDENCE, "mapping m9: evidenceIds is empty"),
                new Violation("r9", Kind.MISSING_EVIDENCE, "relation r9: evidenceIds is empty"),
                new Violation("t9", Kind.MISSING_EVIDENCE, "term t9: evidenceIds is empty")), violations);
    }

    @Test
    void aBlankSourceIsReported() {
        var violations = validate(with(
                new Evidence(meta("e9"), " ", null),
                new Mapping(meta("m9"), "t1", "", List.of("e1"))));
        assertEquals(List.of(
                new Violation("e9", Kind.MISSING_SOURCE, "evidence e9: source is blank"),
                new Violation("m9", Kind.MISSING_SOURCE, "mapping m9: source is blank")), violations);
    }

    @Test
    void aDuplicateIdIsReportedOnceAndResolvesWhateverTheOrder() {
        var records = with(evidence("t1"), relation("r2", "kind_of", "t1", "t2"));
        var expected = List.of(new Violation("t1", Kind.DUPLICATE_ID, "id 't1' is shared by 2 records"));
        assertEquals(expected, validate(records));
        Collections.reverse(records);
        assertEquals(expected, validate(records));
    }

    @Test
    void anIdenticalRecordListedTwiceIsNotADuplicate() {
        var records = cleanSet();
        records.addAll(cleanSet());
        assertEquals(List.of(), validate(records));
    }

    @Test
    void oneRecordWithSeveralFaultsReportsThemAll() {
        var violations = validate(with(new Relation(meta("r9"), "likes", "ghost", "e1", List.of())));
        assertEquals(List.of(
                new Violation("r9", Kind.UNDECLARED_TYPE, "relation r9: type 'likes' is not declared"),
                new Violation("r9", Kind.UNRESOLVED_REFERENCE, "relation r9: from 'ghost' is unresolved"),
                new Violation("r9", Kind.UNRESOLVED_REFERENCE, "relation r9: to 'e1' is an Evidence, not a Term"),
                new Violation("r9", Kind.MISSING_EVIDENCE, "relation r9: evidenceIds is empty")), violations);
    }

    @Test
    void theOutputIsIdenticalOnRepeatRunsAndInAnyInputOrder() {
        var faulty = with(
                term("v1", "Vehicle"),
                relation("r9", "likes", "p1", "t1"),
                relation("r2", "works_at", "p1", "pr1"),
                relation("r3", "works_at", "p1", "nobody"),
                new Mapping(meta("m2"), "e1", "", List.of()),
                new Evidence(meta("e4"), " ", "e4"),
                evidence("t2"));
        var first = validate(faulty);
        assertEquals(EnumSet.allOf(Kind.class),
                first.stream().map(Violation::kind).collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class))),
                () -> "expected every kind: " + first);
        assertEquals(first, validate(faulty));
        assertEquals(first, validate(faulty));

        var reversed = new ArrayList<>(faulty);
        Collections.reverse(reversed);
        assertEquals(first, validate(reversed));

        var shuffled = new ArrayList<>(faulty);
        Collections.shuffle(shuffled, new Random(1343));
        assertEquals(first, validate(shuffled));
    }
}
