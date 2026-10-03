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
import memory.ontology.OntologyRecord.Valence;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import memory.ontology.OntologyValidator.Kind;
import memory.ontology.OntologyValidator.Violation;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
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
                term("a2", "Artifact"),
                relation("r3", "part_of", "a1", "a2"),
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
    void aPlaceMayBePartOfAPlaceButNotOfItsNeighbours() {
        assertEquals(List.of(), validate(with(relation("r2", "part_of", "pl1", "pl2"))));
        var violations = validate(with(
                term("ev1", "Event"),
                relation("r3", "part_of", "pl1", "o1"),
                relation("r4", "part_of", "ev1", "pl1"),
                relation("r5", "part_of", "o1", "pl1")));
        assertEquals(List.of(
                new Violation("r3", Kind.DISALLOWED_ENDPOINT, "relation r3: part_of does not allow Place -> Organization"),
                new Violation("r4", Kind.DISALLOWED_ENDPOINT, "relation r4: part_of does not allow Event -> Place"),
                new Violation("r5", Kind.DISALLOWED_ENDPOINT,
                        "relation r5: part_of does not allow Organization -> Place")),
                violations);
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
                evidence("t2"),
                relation("r4", "part_of", "o1", "o1"),
                claim("k1", SOURCE, null, Status.HOLDS, null, null, null, null),
                term("ev1", "Event"),
                listed("r5", "involves", "ev1", "p1", "k2"),
                claim("k2", "r5", Status.ENDED, null),
                listed("r6", "works_at", "p1", "o1", "k3", "k4"),
                claim("k3", "r6", Status.HOLDS, "2019/"),
                claim("k4", "r6", Status.HOLDS, null),
                new Evidence(meta("k5"), "memory:5", null, null, null, null, null, null, "memory:6", null, null, null,
                        null, null, null, null));
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

    @Test
    void aDuplicatedTermIdResolvesThroughWhicheverTypeTheEndpointAllows() {
        var records = with(term("x", "Person"), term("x", "Topic"), relation("r2", "holds_view_on", "p1", "x"));
        var expected = List.of(new Violation("x", Kind.DUPLICATE_ID, "id 'x' is shared by 2 records"));
        assertEquals(expected, validate(records));
        Collections.reverse(records);
        assertEquals(expected, validate(records));
    }

    @Test
    void anIrreflexiveSelfLoopIsAnAxiomViolation() {
        record Loop(String type, String termId, String termType) {}
        for (var loop : List.of(
                new Loop("part_of", "o1", "Organization"),
                new Loop("family_of", "p1", "Person"),
                new Loop("kind_of", "t1", "Topic"),
                new Loop("same_as", "pl1", "Place"),
                new Loop("derived_from", "a1", "Artifact"))) {
            assertTrue(SCHEMA.irreflexive(loop.type()), loop.type());
            assertTrue(SCHEMA.allows(loop.type(), loop.termType(), loop.termType()), loop.type());
            assertEquals(List.of(new Violation("r9", Kind.AXIOM, "relation r9: " + loop.type()
                            + " is irreflexive, but from and to are both '" + loop.termId() + "'")),
                    validate(with(relation("r9", loop.type(), loop.termId(), loop.termId()))), loop.type());
        }
    }

    @Test
    void aSymmetricRelationStoredBothWaysIsReportedOnEachNamingTheOther() {
        var records = with(term("p2", "Person"),
                relation("r8", "family_of", "p1", "p2"),
                relation("r9", "family_of", "p2", "p1"));
        var expected = List.of(
                new Violation("r8", Kind.AXIOM, "relation r8: family_of is symmetric, and r9 states it the other way round"),
                new Violation("r9", Kind.AXIOM, "relation r9: family_of is symmetric, and r8 states it the other way round"));
        assertEquals(expected, validate(records));
        Collections.reverse(records);
        assertEquals(expected, validate(records));

        assertEquals(List.of(), validate(with(term("p2", "Person"), relation("r8", "family_of", "p1", "p2"))),
                "one direction alone is fine");
        assertEquals(List.of(), validate(with(relation("r8", "part_of", "pl1", "pl2"), relation("r9", "part_of", "pl2", "pl1"))),
                "part_of is not symmetric");
    }

    @Test
    void aSymmetricSelfLoopIsReportedOnceAsIrreflexive() {
        assertEquals(List.of(new Violation("r9", Kind.AXIOM,
                        "relation r9: family_of is irreflexive, but from and to are both 'p1'")),
                validate(with(relation("r9", "family_of", "p1", "p1"))));
    }

    private static final String SOURCE = "memory:501";
    private static final LocalDate ANCHOR = LocalDate.parse("2026-02-15");
    private static final Instant RECORDED = Instant.parse("2026-02-15T09:00:00Z");

    /** A fully provenanced claim from {@link #SOURCE}, anchored on {@link #ANCHOR}. */
    private static Evidence claim(String id, @Nullable String subject, @Nullable Status status, @Nullable String valid) {
        return claim(id, SOURCE, subject, status, valid, null, null, ANCHOR);
    }

    private static Evidence claim(String id, String source, @Nullable String subject, @Nullable Status status,
            @Nullable String valid, @Nullable String occurs, @Nullable Valence valence, @Nullable LocalDate anchor) {
        return new Evidence(meta(id), source, subject, MemoryAuthorType.HUMAN_TURN, null, null, RECORDED, null, null,
                null, null, anchor, status, valid == null ? null : EdtfInterval.parse(valid),
                occurs == null ? null : EdtfInterval.parse(occurs), valence);
    }

    /** A relation resting on e1 and listing the given claims. */
    private static Relation listed(String id, String type, String from, String to, String... claimIds) {
        var evidenceIds = new ArrayList<>(List.of("e1"));
        evidenceIds.addAll(List.of(claimIds));
        return new Relation(meta(id), type, from, to, evidenceIds);
    }

    private static Term listedTerm(String id, String type, String... claimIds) {
        var evidenceIds = new ArrayList<>(List.of("e1"));
        evidenceIds.addAll(List.of(claimIds));
        return new Term(meta(id), type, id, List.of(), evidenceIds);
    }

    private static Evidence systemTime(String id, @Nullable Instant recordedAt, @Nullable Instant retiredAt,
            @Nullable String retiredBy, @Nullable Lineage lineage, @Nullable LocalDate changedBy,
            @Nullable LocalDate anchor) {
        return new Evidence(meta(id), "memory:1", null, null, null, null, recordedAt, retiredAt, retiredBy,
                lineage, changedBy, anchor, null, null, null, null);
    }

    /** One claim on a works_at relation r7 that lists it and nothing else does. */
    private static List<Violation> validateClaim(@Nullable Status status, @Nullable String valid, @Nullable LocalDate anchor) {
        return validate(with(listed("r7", "works_at", "p1", "o1", "k"),
                claim("k", SOURCE, "r7", status, valid, null, null, anchor)));
    }

    private static List<String> kindsOn(List<Violation> violations) {
        return violations.stream().map(v -> v.recordId() + " " + v.kind()).toList();
    }

    @Test
    void wellLinkedClaimsOfEveryKindPass() {
        assertEquals(List.of(), validate(with(
                listed("r7", "works_at", "p1", "o1", "k1"),
                claim("k1", "r7", Status.HOLDS, "2019/.."),
                listedTerm("ev1", "Event", "k2"),
                claim("k2", SOURCE, "ev1", null, null, "2026-02-14", null, ANCHOR),
                listed("r8", "holds_view_on", "p1", "t1", "k3"),
                claim("k3", SOURCE, "r8", Status.HOLDS, null, null, Valence.FAVORABLE, ANCHOR))));
    }

    @Test
    void aClaimOnAnUndeclaredRelationOrFromTypeIsSkippedNotThrown() {
        var violations = validate(with(
                listed("r9", "likes", "p1", "t1", "k1"),
                claim("k1", SOURCE, "r9", Status.HOLDS, "2019/..", null, Valence.FAVORABLE, ANCHOR),
                term("v1", "Vehicle"),
                listed("r8", "works_at", "v1", "o1", "k2", "k3"),
                claim("k2", "r8", Status.ENDED, null),
                claim("k3", "file:/v", "r8", null, "2019/2020", null, null, ANCHOR)));
        assertEquals(List.of(
                new Violation("r9", Kind.UNDECLARED_TYPE, "relation r9: type 'likes' is not declared"),
                new Violation("v1", Kind.UNDECLARED_TYPE, "term v1: type 'Vehicle' is not declared")), violations);
    }

    @Test
    void aClaimWithANullUnresolvedOrSelfSubjectIsAClaimLink() {
        assertEquals(List.of(
                new Violation("k1", Kind.CLAIM_LINK, "evidence k1: a claim needs a subjectId"),
                new Violation("k2", Kind.CLAIM_LINK, "evidence k2: subjectId 'ghost' is unresolved"),
                new Violation("k3", Kind.CLAIM_LINK, "evidence k3: subjectId 'k3' names the evidence itself")),
                validate(with(claim("k1", null, Status.HOLDS, null), claim("k2", "ghost", Status.HOLDS, null),
                        claim("k3", "k3", Status.HOLDS, null))));
    }

    @Test
    void aClaimItsSubjectDoesNotListOrAnotherRecordListsIsAClaimLink() {
        assertEquals(List.of(new Violation("k", Kind.CLAIM_LINK, "evidence k: subject 'r1' does not list it in evidenceIds")),
                validate(with(claim("k", "r1", Status.HOLDS, null))));
        assertEquals(List.of(new Violation("k", Kind.CLAIM_LINK,
                        "evidence k: a claim about 'r7' is also listed by t9")),
                validate(with(listed("r7", "works_at", "p1", "o1", "k"), listedTerm("t9", "Topic", "k"),
                        claim("k", "r7", Status.HOLDS, null))));
        assertEquals(List.of(
                new Violation("k", Kind.CLAIM_LINK, "evidence k: subject 'e1' does not list it in evidenceIds"),
                new Violation("k", Kind.CLAIM_LINK, "evidence k: valence needs a relation of a type that takes one")),
                validate(with(new Evidence(meta("k"), "file:/a", "e1", null, null, null, null, null, null, null, null,
                        null, null, null, null, Valence.FAVORABLE))), "an evidence subject lists nothing");
    }

    @Test
    void aClaimOnTheWrongKindOfSubjectIsAClaimLink() {
        var violations = validate(with(
                listedTerm("t8", "Topic", "k1", "k2"),
                claim("k1", "t8", Status.HOLDS, null),
                claim("k2", "file:/b", "t8", null, null, "2026-02-14", null, ANCHOR),
                listed("r7", "works_at", "p1", "o1", "k3"),
                claim("k3", SOURCE, "r7", Status.HOLDS, null, null, Valence.UNFAVORABLE, ANCHOR),
                listedTerm("v1", "Vehicle", "k4"),
                claim("k4", "file:/c", "v1", null, null, "2026-02-14", null, ANCHOR)));
        assertEquals(List.of(
                new Violation("k1", Kind.CLAIM_LINK, "evidence k1: status and valid need a relation subject"),
                new Violation("k2", Kind.CLAIM_LINK, "evidence k2: occurs needs a term of a dated type"),
                new Violation("k3", Kind.CLAIM_LINK, "evidence k3: valence needs a relation of a type that takes one"),
                new Violation("v1", Kind.UNDECLARED_TYPE, "term v1: type 'Vehicle' is not declared")), violations);
    }

    @Test
    void aStatusOrValidTheRelationDoesNotTakeIsNotAllowed() {
        var violations = validate(with(
                term("ev1", "Event"),
                listed("r5", "involves", "ev1", "p1", "k1", "k2"),
                claim("k1", "r5", Status.ENDED, null),
                claim("k2", "file:/v", "r5", Status.HOLDS, "2020/..", null, null, ANCHOR),
                listed("r6", "located_in", "ev1", "pl1", "k3"),
                claim("k3", "r6", Status.ENDED, null)));
        assertEquals(List.of(
                new Violation("k1", Kind.CLAIM_NOT_ALLOWED, "evidence k1: status ended is not allowed on involves from Event"),
                new Violation("k2", Kind.CLAIM_NOT_ALLOWED, "evidence k2: valid is not allowed on involves from Event"),
                new Violation("k3", Kind.CLAIM_NOT_ALLOWED,
                        "evidence k3: status ended is not allowed on located_in from Event")), violations);
        assertEquals(List.of(), validate(with(term("ev1", "Event"), listed("r5", "involves", "ev1", "p1", "k1"),
                claim("k1", "r5", Status.DENIED, null))));
    }

    @Test
    void aDenialsValidIsTheNeverFormEndingOnItsAnchor() {
        assertEquals(List.of(), validateClaim(Status.DENIED, "../2026-02-15", ANCHOR));
        assertEquals(List.of(), validateClaim(Status.DENIED, null, ANCHOR), "a denial needs no valid");
        assertEquals(List.of("k CLAIM_NOT_ALLOWED"), kindsOn(validateClaim(Status.DENIED, "../2026-02-14", ANCHOR)));
        assertEquals(List.of("k CLAIM_NOT_ALLOWED"), kindsOn(validateClaim(Status.DENIED, "2026-02-15", ANCHOR)));

        Function<String, List<Violation>> unanchored = valid -> validate(with(
                listed("r7", "works_at", "p1", "o1", "k"),
                claim("k", "file:/notes", "r7", Status.DENIED, valid, null, null, null)));
        assertEquals(List.of(), unanchored.apply("../2026-01-01"));
        assertEquals(List.of(new Violation("k", Kind.CLAIM_NOT_ALLOWED,
                "evidence k: a denial's valid 2020/.. is not an open start ../YYYY-MM-DD")), unanchored.apply("2020/.."));
    }

    @Test
    void aHoldsIntervalMustStillHoldOnItsAnchor() {
        assertEquals(List.of(), validateClaim(Status.HOLDS, "2019/2026-02-15", ANCHOR));
        assertEquals(List.of("k INVALID_INTERVAL"), kindsOn(validateClaim(Status.HOLDS, "2019/2026-02-14", ANCHOR)));
        assertEquals(List.of(), validateClaim(Status.HOLDS, "2019/..", ANCHOR));
        assertEquals(List.of(), validateClaim(Status.HOLDS, "2019", ANCHOR), "the single-date form has no end");
        assertEquals(List.of("k INVALID_INTERVAL"), kindsOn(validateClaim(Status.HOLDS, "2019/", ANCHOR)));
        assertEquals(List.of("k INVALID_INTERVAL", "k PROVENANCE_MISSING"),
                kindsOn(validateClaim(Status.HOLDS, "2019/", null)), "an unknown end is refused without an anchor too");
        assertEquals(List.of("k PROVENANCE_MISSING"), kindsOn(validateClaim(Status.HOLDS, "2019/2020", null)));
    }

    @Test
    void anEndedIntervalMustHaveEndedByItsAnchor() {
        assertEquals(List.of(), validateClaim(Status.ENDED, "2019/2026-02-15", ANCHOR));
        assertEquals(List.of("k INVALID_INTERVAL"), kindsOn(validateClaim(Status.ENDED, "2019/2026-02-16", ANCHOR)));
        assertEquals(List.of(), validateClaim(Status.ENDED, "2026-02-15/", ANCHOR));
        assertEquals(List.of("k INVALID_INTERVAL"), kindsOn(validateClaim(Status.ENDED, "2026-02-16/", ANCHOR)));
        assertEquals(List.of("k INVALID_INTERVAL"), kindsOn(validateClaim(Status.ENDED, "2019/..", ANCHOR)));
        assertEquals(List.of(), validateClaim(Status.ENDED, "2019/", ANCHOR), "an unknown end is accepted");
    }

    @Test
    void anOpenStartIsRefusedOutsideADenial() {
        assertEquals(List.of(new Violation("k", Kind.INVALID_INTERVAL, "evidence k: valid ../2026-02-15 has an open start")),
                validateClaim(null, "../2026-02-15", ANCHOR));
    }

    @Test
    void anOccurrenceIsADateOrAClosedInterval() {
        Function<String, List<Violation>> check = occurs -> validate(with(
                listedTerm("ev1", "Event", "k"), claim("k", SOURCE, "ev1", null, null, occurs, null, ANCHOR)));
        assertEquals(List.of(), check.apply("2026-02-14"));
        assertEquals(List.of(), check.apply("2026-02-14/2026-02-16"));
        assertEquals(List.of(new Violation("k", Kind.INVALID_INTERVAL,
                "evidence k: occurs 2026/.. is neither a date nor a closed interval")), check.apply("2026/.."));
        assertEquals(List.of("k INVALID_INTERVAL"), kindsOn(check.apply("2026-02/")));
    }

    @Test
    void systemTimeIsCheckedOnEveryEvidence() {
        var at = Instant.parse("2026-03-01T12:00:00Z");
        assertEquals(List.of(), validate(with(
                systemTime("s1", at, at, "memory:12", Lineage.UPDATE, ANCHOR, ANCHOR),
                systemTime("s2", null, at, "memory:13", Lineage.CORRECTION, null, ANCHOR))));
        var violations = validate(with(
                systemTime("s1", at, at.minusSeconds(1), null, null, null, null),
                systemTime("s2", null, null, "memory:12", null, null, null),
                systemTime("s3", null, at, "message:12", null, null, null),
                systemTime("s4", null, at, "memory:", null, null, null),
                systemTime("s5", null, null, null, Lineage.UPDATE, null, null),
                systemTime("s6", null, at, "memory:12", Lineage.RESTATEMENT, ANCHOR, null),
                systemTime("s7", null, at, "memory:12", Lineage.UPDATE, ANCHOR.minusDays(1), ANCHOR)));
        assertEquals(List.of(
                new Violation("s1", Kind.SYSTEM_TIME,
                        "evidence s1: retiredAt 2026-03-01T11:59:59Z is before recordedAt 2026-03-01T12:00:00Z"),
                new Violation("s2", Kind.SYSTEM_TIME, "evidence s2: retiredBy is set without retiredAt"),
                new Violation("s3", Kind.SYSTEM_TIME, "evidence s3: retiredBy 'message:12' is not memory:<id>"),
                new Violation("s4", Kind.SYSTEM_TIME, "evidence s4: retiredBy 'memory:' is not memory:<id>"),
                new Violation("s5", Kind.SYSTEM_TIME, "evidence s5: lineage is set without retiredBy"),
                new Violation("s6", Kind.SYSTEM_TIME, "evidence s6: changedBy is set but lineage is not update"),
                new Violation("s7", Kind.SYSTEM_TIME, "evidence s7: changedBy 2026-02-14 is before anchor 2026-02-15")),
                violations);
    }

    @Test
    void aClaimFromAMemoryOrMessageNeedsItsProvenance() {
        Function<String, Evidence> bare = source -> new Evidence(meta("k"), source, "r7", null,
                null, null, null, null, null, null, null, null, Status.HOLDS, null, null, null);
        assertEquals(List.of(
                new Violation("k", Kind.PROVENANCE_MISSING, "evidence k: claim from message:9 has no anchor"),
                new Violation("k", Kind.PROVENANCE_MISSING, "evidence k: claim from message:9 has no authorType"),
                new Violation("k", Kind.PROVENANCE_MISSING, "evidence k: claim from message:9 has no recordedAt")),
                validate(with(listed("r7", "works_at", "p1", "o1", "k"), bare.apply("message:9"))));
        assertEquals(List.of(), validate(with(listed("r7", "works_at", "p1", "o1", "k"), bare.apply("file:/notes"))));
        assertEquals(List.of(), validateClaim(Status.HOLDS, null, ANCHOR));
    }

    @Test
    void twoClaimsFromOneSourceAboutOneRecordAreDuplicates() {
        assertEquals(List.of(
                new Violation("k1", Kind.DUPLICATE_CLAIM, "evidence k1: memory:501 already claims about 'r7' through k2"),
                new Violation("k2", Kind.DUPLICATE_CLAIM, "evidence k2: memory:501 already claims about 'r7' through k1")),
                validate(with(listed("r7", "works_at", "p1", "o1", "k1", "k2"),
                        claim("k1", "r7", Status.HOLDS, null), claim("k2", "r7", Status.ENDED, null))));
        assertEquals(List.of(), validate(with(listed("r7", "works_at", "p1", "o1", "k1", "k2"),
                claim("k1", "r7", Status.HOLDS, null),
                claim("k2", "memory:502", "r7", Status.ENDED, null, null, null, ANCHOR))));
    }

    @Test
    void aNullAnchorSkipsEveryAnchorComparison() {
        var at = Instant.parse("2026-03-01T12:00:00Z");
        assertEquals(List.of(), validate(with(
                systemTime("s1", null, at, "memory:12", Lineage.UPDATE, ANCHOR, null))));
        assertEquals(List.of(new Violation("k", Kind.PROVENANCE_MISSING, "evidence k: claim from memory:501 has no anchor")),
                validateClaim(null, "2019/2020", null));
    }

    @Test
    void theConcurrentUpdateShapeIsClean() {
        var records = cleanSet();
        for (int i = 1; i <= 3; i++) {
            records.add(new Evidence(meta("xa" + i), "memory:" + i, null));
            records.add(new Evidence(meta("xb" + i), "memory:" + i, null));
        }
        assertEquals(List.of(), validate(records));
    }

    @Test
    void aClaimBearingSetGivesTheSameOutputInAnyOrder() {
        var records = with(
                term("ev1", "Event"),
                listed("r5", "involves", "ev1", "p1", "k1"),
                claim("k1", "r5", Status.ENDED, "2019/"),
                listed("r6", "works_at", "p1", "o1", "k2", "k3"),
                claim("k2", "r6", Status.HOLDS, "2019/2020"),
                claim("k3", "r6", Status.DENIED, "../2026-02-14"),
                claim("k4", null, null, "../2026-02-15"));
        var first = validate(records);
        assertFalse(first.isEmpty());
        var a = new ArrayList<>(records);
        Collections.shuffle(a, new Random(1362));
        var b = new ArrayList<>(records);
        Collections.shuffle(b, new Random(2026));
        assertEquals(first, validate(a));
        assertEquals(first, validate(b));
    }

    @Test
    void claimIdsCarryTheRecordLengthAndTheFullSource() {
        assertEquals("ev:2:r1:memory:501", Evidence.claimId("r1", "memory:501"));
        assertEquals("ev:2:r1:message:501", Evidence.claimId("r1", "message:501"));
        assertNotEquals(Evidence.claimId("r1", "memory:501"), Evidence.claimId("r1", "message:501"));
        assertNotEquals(Evidence.claimId("a:b", "c"), Evidence.claimId("a", "b:c"));
        assertEquals(Evidence.claimId("r1", "memory:501"), Evidence.claimId("r1", "memory:501"));
    }
}
