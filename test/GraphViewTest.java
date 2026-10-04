import memory.graph.GraphView;
import memory.graph.GraphView.Answer;
import memory.graph.GraphView.Contested;
import memory.graph.GraphView.Occurrence;
import memory.graph.GraphView.Reason;
import memory.graph.GraphView.Timing;
import memory.graph.GraphView.Truth;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Lineage;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologyRecord.Valence;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/** JCLAW-1364: the ticket's fixtures and every row of the three status tables, read through {@link GraphView}. */
class GraphViewTest extends UnitTest {

    private static final long AGENT = 7L;
    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final String OWNER = "operator";
    private static final String R = "r";
    /** The read instant passed as {@code now}; the bound clock is 2040, so an answer that read it would differ. */
    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"), ZoneOffset.UTC);

    // ---- builders ----

    private static Meta meta(String id) {
        return Meta.fresh(id, AGENT, Tier.TENTATIVE);
    }

    private static Instant noon(LocalDate day) {
        return day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC);
    }

    private static Instant noon(String day) {
        return noon(LocalDate.parse(day));
    }

    /** A claim from one source; the subject is set by {@link Graph#rel}. */
    private static final class C {
        final String source;
        MemoryAuthorType author;
        Double confidence;
        Instant recordedAt;
        Instant retiredAt;
        String retiredBy;
        Lineage lineage;
        LocalDate changedBy;
        LocalDate anchor;
        Status status;
        EdtfInterval valid;
        EdtfInterval occurs;
        Valence valence;

        C(String source) {
            this.source = source;
        }

        /** Sets the anchor and a recordedAt of the anchor at 12:00 UTC. */
        C anchor(String day) {
            anchor = LocalDate.parse(day);
            recordedAt = noon(anchor);
            return this;
        }

        C noAnchor() {
            anchor = null;
            return this;
        }

        C valid(String text) {
            valid = EdtfInterval.parse(text);
            return this;
        }

        C occurs(String text) {
            occurs = EdtfInterval.parse(text);
            return this;
        }

        C guest() {
            author = MemoryAuthorType.GUEST_TURN;
            return this;
        }

        C author(MemoryAuthorType type) {
            author = type;
            return this;
        }

        C confidence(Double value) {
            confidence = value;
            return this;
        }

        C recordedAt(Instant at) {
            recordedAt = at;
            return this;
        }

        C valence(Valence v) {
            valence = v;
            return this;
        }

        C retired(Instant at, String by, Lineage how, String changed) {
            retiredAt = at;
            retiredBy = by;
            lineage = how;
            changedBy = changed == null ? null : LocalDate.parse(changed);
            return this;
        }

        Evidence build(String subject) {
            return new Evidence(meta(Evidence.claimId(subject, source)), source, subject, author, confidence, null,
                    recordedAt, retiredAt, retiredBy, lineage, changedBy, anchor, status, valid, occurs, valence);
        }
    }

    private static C holds(String source) {
        var c = new C(source);
        c.status = Status.HOLDS;
        return c;
    }

    private static C ended(String source) {
        var c = new C(source);
        c.status = Status.ENDED;
        return c;
    }

    private static C denied(String source) {
        var c = new C(source);
        c.status = Status.DENIED;
        return c;
    }

    private static C bare(String source) {
        return new C(source);
    }

    private static String id(String rel, String source) {
        return Evidence.claimId(rel, source);
    }

    private static final class Graph {
        final List<OntologyRecord> records = new ArrayList<>();

        Graph() {
            for (var p : List.of("operator", "jonah")) term(p, "Person");
            for (var p : List.of("ashgrove", "port-calloway", "berlin", "porto", "larkspur")) term(p, "Place");
            for (var s : List.of("kestrel-ci", "osprey")) term(s, "System");
            for (var o : List.of("harborlight", "vela")) term(o, "Organization");
            for (var t : List.of("topic-a", "topic-b")) term(t, "Topic");
        }

        Graph term(String id, String type, Evidence... evidence) {
            records.add(new Term(meta(id), type, id, List.of(), Arrays.stream(evidence).map(Evidence::id).toList()));
            records.addAll(List.of(evidence));
            return this;
        }

        Graph rel(String id, String type, String from, String to, C... claims) {
            var evidence = Arrays.stream(claims).map(c -> c.build(id)).toList();
            records.add(new Relation(meta(id), type, from, to, evidence.stream().map(Evidence::id).toList()));
            records.addAll(evidence);
            return this;
        }

        Graph relIds(String id, String type, String from, String to, List<String> evidenceIds) {
            records.add(new Relation(meta(id), type, from, to, evidenceIds));
            return this;
        }
    }

    /** One Relation, operator located_in ashgrove, carrying {@code claims}. */
    private static Graph single(C... claims) {
        return new Graph().rel(R, "located_in", OWNER, "ashgrove", claims);
    }

    // ---- reading ----

    /** Runs {@code call}, then checks the records were not touched and that three shuffles give equal answers. */
    private static <T> T read(Graph g, Function<GraphView, T> call) {
        var records = g.records;
        var before = List.copyOf(records);
        T result = call.apply(new GraphView(SCHEMA, records, OWNER));
        assertEquals(before, records, "the record list changed");
        for (int seed = 0; seed < 3; seed++) {
            var shuffled = new ArrayList<>(records);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(result, call.apply(new GraphView(SCHEMA, shuffled, OWNER)), "shuffle seed " + seed);
        }
        return result;
    }

    private static Answer at(Graph g, String rel, String d) {
        return at(g, rel, d, NOW);
    }

    private static Answer at(Graph g, String rel, String d, Instant s) {
        return read(g, v -> v.at(rel, LocalDate.parse(d), s));
    }

    private static Answer row(Graph g, String d) {
        return at(g, R, d);
    }

    private static Answer find(List<Answer> answers, String rel) {
        return answers.stream().filter(a -> a.recordId().equals(rel)).findFirst()
                .orElseThrow(() -> new AssertionError(rel + " is not in " + answers));
    }

    private static GraphView.Current current(Graph g, String today) {
        return current(g, today, NOW);
    }

    private static GraphView.Current current(Graph g, String today, Instant now) {
        return read(g, v -> v.current(LocalDate.parse(today), now));
    }

    private static List<String> ids(List<Answer> answers) {
        return answers.stream().map(Answer::recordId).toList();
    }

    private static void clocked(Runnable body) {
        AppClock.runWith(FIXED, body);
    }

    private static void expect(Answer a, Truth truth, boolean assumed, Reason reason) {
        assertEquals(truth, a.truth(), () -> a.toString());
        assertEquals(assumed, a.assumed(), () -> "assumed: " + a);
        assertEquals(reason, a.reason(), () -> "reason: " + a);
    }

    private static void yes(Answer a) {
        expect(a, Truth.YES, false, null);
    }

    private static void yesAssumed(Answer a) {
        expect(a, Truth.YES, true, null);
    }

    private static void no(Answer a, Reason reason) {
        expect(a, Truth.NO, false, reason);
    }

    private static void noAssumed(Answer a, Reason reason) {
        expect(a, Truth.NO, true, reason);
    }

    private static void unknown(Answer a) {
        expect(a, Truth.UNKNOWN, false, null);
        assertNull(a.deciding(), () -> "deciding: " + a);
    }

    // ---- the holds table ----

    @Test
    void holdsPastAStatedEndIsExpired() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2026-10-03").valid("2020/2026-10"));
            noAssumed(row(g, "2026-11-01"), Reason.EXPIRED);
            unknown(row(g, "2026-10-31"));
            unknown(row(g, "2026-10-03"));
            yes(row(g, "2026-10-01"));
        });
    }

    @Test
    void aSingleDateValidIsBothStartAndEnd() {
        clocked(() -> noAssumed(row(single(holds("memory:1").anchor("2026-10-03").valid("2019")), "2026-10-03"),
                Reason.EXPIRED));
    }

    @Test
    void aSingleDateHoldsBindsItsEndBeforeItsAnchor() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2026-10-03").valid("2019"));
            noAssumed(row(g, "2018-12-31"), Reason.SCHEDULED);
            unknown(row(g, "2019-01-01"));
            unknown(row(g, "2019-06-01"));
            unknown(row(g, "2019-12-31"));
            noAssumed(row(g, "2020-01-01"), Reason.EXPIRED);
            noAssumed(row(g, "2021-01-01"), Reason.EXPIRED);
            noAssumed(row(g, "2026-10-02"), Reason.EXPIRED);
        });
    }

    @Test
    void holdsWithAScheduledStart() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2026-10-03").valid("2027-03/.."));
            noAssumed(row(g, "2026-09-01"), Reason.SCHEDULED);
            no(row(g, "2026-10-03"), Reason.SCHEDULED);
            no(row(g, "2027-02-28"), Reason.SCHEDULED);
            unknown(row(g, "2027-03-01"));
            unknown(row(g, "2027-03-31"));
            yesAssumed(row(g, "2027-04-01"));
        });
    }

    @Test
    void holdsAtAndAfterItsAnchor() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2026-10-03"));
            yes(row(g, "2026-10-03"));
            yesAssumed(row(g, "2026-10-04"));
            unknown(row(g, "2026-10-02"));
        });
    }

    @Test
    void holdsBeforeItsAnchorWithAnUnknownStart() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2026-10-03").valid("/2027"));
            unknown(row(g, "2026-10-02"));
            yes(row(g, "2026-10-03"));
        });
    }

    @Test
    void holdsBeforeItsAnchorAgainstAStatedStart() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2026-10-03").valid("2019/.."));
            noAssumed(row(g, "2018-12-31"), Reason.SCHEDULED);
            unknown(row(g, "2019-01-01"));
            unknown(row(g, "2019-12-31"));
            yes(row(g, "2020-01-01"));
        });
    }

    // ---- the ended table ----

    @Test
    void endedAtOrAfterItsAnchorWhateverTheEndPrecision() {
        clocked(() -> {
            var g = single(ended("memory:1").anchor("2026-10-03").valid("/2026"));
            no(row(g, "2026-10-03"), Reason.STATED);
            noAssumed(row(g, "2026-10-04"), Reason.STATED);
        });
    }

    @Test
    void endedAgainstAStatedEnd() {
        clocked(() -> {
            var g = single(ended("memory:1").anchor("2026-10-03").valid("/2019"));
            no(row(g, "2020-01-01"), Reason.STATED);
            unknown(row(g, "2019-12-31"));
            unknown(row(g, "2019-01-01"));
            unknown(row(g, "2018-12-31"));
        });
    }

    @Test
    void endedAgainstAStatedStartAndSpan() {
        clocked(() -> {
            var g = single(ended("memory:1").anchor("2026-10-03").valid("2015/2019"));
            noAssumed(row(g, "2014-12-31"), Reason.STATED);
            unknown(row(g, "2015-01-01"));
            unknown(row(g, "2015-12-31"));
            yes(row(g, "2016-01-01"));
            yes(row(g, "2018-12-31"));
            unknown(row(g, "2019-01-01"));
        });
    }

    @Test
    void endedWithAStartAndAnUnknownEndIsOtherwiseUnknown() {
        clocked(() -> unknown(row(single(ended("memory:1").anchor("2026-10-03").valid("2015/")), "2016-06-01")));
    }

    // ---- the denied table ----

    @Test
    void deniedAtOrAfterItsAnchor() {
        clocked(() -> {
            var g = single(denied("memory:1").anchor("2026-10-03"));
            no(row(g, "2026-10-03"), Reason.DENIED);
            noAssumed(row(g, "2026-10-04"), Reason.DENIED);
            unknown(row(g, "2026-10-02"));
        });
    }

    @Test
    void aNeverDeniesEveryDateBeforeItsAnchor() {
        clocked(() -> {
            var g = single(denied("memory:1").anchor("2026-10-03").valid("../2026-10-03"));
            no(row(g, "2026-10-02"), Reason.DENIED);
            no(row(g, "2000-01-01"), Reason.DENIED);
        });
    }

    // ---- null status and timeless relations ----

    @Test
    void aNullStatusIsUnknownAtEveryDate() {
        clocked(() -> {
            var g = single(bare("memory:1").anchor("2026-10-03").valence(Valence.FAVORABLE));
            for (var d : List.of("2000-01-01", "2026-10-03", "2030-01-01")) unknown(row(g, d));
            var timeless = new Graph().rel(R, "same_as", "topic-a", "topic-b", bare("memory:1").anchor("2026-10-03"));
            unknown(row(timeless, "2026-10-03"));
        });
    }

    @Test
    void aTimelessHoldsIsYesAtEveryDate() {
        clocked(() -> {
            var g = new Graph().rel(R, "kind_of", "topic-a", "topic-b", holds("memory:1").anchor("2026-10-03"));
            yes(row(g, "1900-01-01"));
            yes(row(g, "2100-01-01"));
            assertEquals(List.of(R), ids(current(g, "2026-10-03").current()));
            var unanchored = new Graph().rel(R, "kind_of", "topic-a", "topic-b", holds("memory:1"));
            yes(row(unanchored, "1900-01-01"));
        });
    }

    @Test
    void aTimelessDenialFollowsTheDeniedTableAndIsNotCurrent() {
        clocked(() -> {
            var g = new Graph().rel(R, "same_as", "topic-a", "topic-b",
                    denied("memory:1").anchor("2026-10-03").valid("../2026-10-03"));
            no(row(g, "2020-01-01"), Reason.DENIED);
            no(row(g, "2026-10-03"), Reason.DENIED);
            var c = current(g, "2026-10-03");
            assertEquals(List.of(), c.current());
            assertEquals(List.of(R), ids(c.denied()));
        });
    }

    // ---- update caps ----

    private static C capped(C claim) {
        return claim.anchor("2025-11-02").retired(noon("2026-10-03"), "memory:506", Lineage.UPDATE, "2026-10-03");
    }

    @Test
    void aCappedHoldsChangesAtItsCap() {
        clocked(() -> {
            var g = single(capped(holds("memory:120")));
            yesAssumed(row(g, "2026-10-02"));
            no(row(g, "2026-10-03"), Reason.CHANGED);
            noAssumed(row(g, "2026-10-04"), Reason.CHANGED);
            yesAssumed(at(g, R, "2026-10-04", Instant.parse("2026-10-01T00:00:00Z")));
        });
    }

    @Test
    void aCappedEndedOrDenialIsUnknownFromItsCap() {
        clocked(() -> {
            var e = single(capped(ended("memory:120")));
            noAssumed(row(e, "2026-01-01"), Reason.STATED);
            unknown(row(e, "2026-10-03"));
            var d = single(capped(denied("memory:120")));
            noAssumed(row(d, "2026-10-02"), Reason.DENIED);
            unknown(row(d, "2026-10-03"));
        });
    }

    @Test
    void anUpdateWithNoChangedByIsNotCapped() {
        clocked(() -> {
            var g = single(holds("memory:120").anchor("2025-11-02")
                    .retired(noon("2026-10-03"), "memory:506", Lineage.UPDATE, null));
            yesAssumed(row(g, "2026-10-04"));
        });
    }

    // ---- step 3: persistence stops ----

    @Test
    void anEndedClaimStopsAPersistedYesFromItsEndPeriod() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2025-01-01"),
                    ended("memory:2").anchor("2026-10-03").valid("/2026-03"));
            yesAssumed(row(g, "2026-02-28"));
            var a = row(g, "2026-03-01");
            unknown(a);
            assertEquals(Contested.NONE, a.contested(), "an ended after a holds is an explained change");
        });
    }

    @Test
    void anEndedClaimWithAnUnknownEndStopsFromItsAnchor() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2025-01-01"),
                    ended("memory:2").anchor("2026-01-01")
                            .retired(noon("2026-02-01"), "memory:3", Lineage.UPDATE, "2026-02-01"));
            yesAssumed(row(g, "2025-12-31"));
            unknown(row(g, "2026-03-01"));
        });
    }

    @Test
    void aDenialStopsAPersistedYesAsAnUnexplainedChange() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2025-01-01"), denied("memory:2").anchor("2026-01-01"));
            var a = row(g, "2026-06-01");
            noAssumed(a, Reason.DENIED);
            assertEquals(Contested.ASSUMED, a.contested());
            var capped = single(holds("memory:1").anchor("2025-01-01"), denied("memory:2").anchor("2026-01-01")
                    .retired(noon("2026-02-01"), "memory:3", Lineage.UPDATE, "2026-02-01"));
            unknown(row(capped, "2026-03-01"));
        });
    }

    @Test
    void aLaterHoldsStopsAPersistedNoByItsAnchor() {
        clocked(() -> {
            var g = single(ended("memory:1").anchor("2025-07-01").valid("/2025-06"),
                    holds("memory:2").anchor("2026-01-01"));
            var a = row(g, "2026-03-01");
            yesAssumed(a);
            assertEquals(Contested.ASSUMED, a.contested(), "a holds with no start is not a rejoin");
            var rejoin = single(ended("memory:1").anchor("2025-07-01").valid("/2025-06"),
                    holds("memory:2").anchor("2026-01-01").valid("2025-09/.."));
            assertEquals(Contested.NONE, row(rejoin, "2026-03-01").contested());
        });
    }

    @Test
    void aLaterHoldsStopsAPersistedNoByItsStatedStart() {
        clocked(() -> {
            var g = single(ended("memory:1").anchor("2025-07-01").valid("/2025-06"),
                    holds("memory:2").anchor("2026-01-01").valid("2025-03/.."));
            var a = row(g, "2025-12-01");
            yes(a);
            assertEquals(Contested.ASSUMED, a.contested());
            var rejoin = single(ended("memory:1").anchor("2025-02-01").valid("/2025-01"),
                    holds("memory:2").anchor("2026-01-01").valid("2025-03/.."));
            var inside = row(rejoin, "2025-03-15");
            noAssumed(inside, Reason.STATED);
            assertEquals(Contested.NONE, inside.contested(), "inside S the ended claim's NO is not yet stopped");
            var atHi = row(rejoin, "2025-04-01");
            yes(atHi);
            assertEquals(id(R, "memory:2"), atHi.deciding());
            assertEquals(Contested.NONE, atHi.contested());
        });
    }

    @Test
    void aSuccessorFromTheRetiringMemoryExplainsTheChange() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2025-01-01")
                            .retired(noon("2026-01-01"), "memory:2", Lineage.RESTATEMENT, null),
                    denied("memory:2").anchor("2026-01-01"));
            var a = row(g, "2026-06-01");
            noAssumed(a, Reason.DENIED);
            assertEquals(Contested.NONE, a.contested());
        });
    }

    // ---- step 4: the decide order ----

    @Test
    void theLaterRecordedAtBreaksAnAnchorTie() {
        clocked(() -> {
            var h = holds("memory:1").anchor("2026-10-03").recordedAt(Instant.parse("2026-10-03T13:00:00Z"));
            var e = ended("memory:2").anchor("2026-10-03").valid("/2026-09");
            var a = row(single(h, e), "2026-10-03");
            yes(a);
            assertEquals(Contested.DEFINITE, a.contested());
            assertEquals(id(R, "memory:1"), a.resolvedDeciding());
            assertEquals(List.of(id(R, "memory:2")), a.others());
            var later = ended("memory:2").anchor("2026-10-03").valid("/2026-09")
                    .recordedAt(Instant.parse("2026-10-03T14:00:00Z"));
            no(row(single(holds("memory:1").anchor("2026-10-03").recordedAt(Instant.parse("2026-10-03T13:00:00Z")),
                    later), "2026-10-03"), Reason.STATED);
        });
    }

    @Test
    void theHigherConfidenceBreaksARecordedAtTieAndNullRanksBelow() {
        clocked(() -> {
            var a = row(single(holds("memory:1").anchor("2026-10-03").confidence(0.9),
                    ended("memory:2").anchor("2026-10-03").valid("/2026-09").confidence(0.5)), "2026-10-03");
            yes(a);
            var b = row(single(holds("memory:1").anchor("2026-10-03"),
                    ended("memory:2").anchor("2026-10-03").valid("/2026-09").confidence(0.1)), "2026-10-03");
            no(b, Reason.STATED);
        });
    }

    @Test
    void theGreaterEvidenceIdBreaksTheLastTie() {
        clocked(() -> {
            no(row(single(holds("memory:1").anchor("2026-10-03"),
                    ended("memory:2").anchor("2026-10-03").valid("/2026-09")), "2026-10-03"), Reason.STATED);
            yes(row(single(holds("memory:2").anchor("2026-10-03"),
                    ended("memory:1").anchor("2026-10-03").valid("/2026-09")), "2026-10-03"));
        });
    }

    @Test
    void aClaimWithNoAnchorIsUnknownAndRanksBelowEveryAnchor() {
        clocked(() -> {
            unknown(row(single(holds("memory:1").noAnchor()), "2026-10-03"));
            var g = new Graph().rel(R, "same_as", "topic-a", "topic-b", holds("memory:1"),
                    denied("memory:2").anchor("2026-10-03"));
            var a = row(g, "2026-10-03");
            no(a, Reason.DENIED);
            assertEquals(Contested.DEFINITE, a.contested());
        });
    }

    // ---- step 5: contested precedence ----

    @Test
    void definiteTakesPrecedenceOverGuest() {
        clocked(() -> {
            var g = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    holds("memory:1").anchor("2026-10-03").recordedAt(Instant.parse("2026-10-03T13:00:00Z")),
                    ended("memory:2").anchor("2026-10-03").valid("/2026-09"),
                    ended("memory:3").anchor("2026-10-03").valid("/2026-09").guest());
            var a = row(g, "2026-10-03");
            yes(a);
            assertEquals(Contested.DEFINITE, a.contested());
        });
    }

    @Test
    void guestTakesPrecedenceOverAssumed() {
        clocked(() -> {
            var g = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    holds("memory:1").anchor("2025-01-01"),
                    denied("memory:2").anchor("2026-01-01"),
                    holds("memory:3").anchor("2026-02-01").guest());
            var a = row(g, "2026-06-01");
            noAssumed(a, Reason.DENIED);
            assertEquals(Contested.GUEST, a.contested());
            assertEquals(id(R, "memory:2"), a.deciding());
        });
    }

    @Test
    void aGuestClaimWithTheOwnerAsTheToEndIsIneligible() {
        clocked(() -> {
            var g = new Graph().rel(R, "family_of", "jonah", OWNER,
                    holds("memory:1").anchor("2025-01-01"),
                    ended("memory:2").anchor("2026-10-03").valid("/2026-09").guest());
            var a = row(g, "2026-10-03");
            yesAssumed(a);
            assertEquals(id(R, "memory:1"), a.deciding());
            assertEquals(Contested.GUEST, a.contested());
        });
    }

    @Test
    void anUnattributedOrNullAuthorReadsAsTheOwners() {
        clocked(() -> {
            var g = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    ended("memory:1").anchor("2026-10-03").author(MemoryAuthorType.UNATTRIBUTED));
            no(row(g, "2026-10-03"), Reason.STATED);
            no(row(new Graph().rel(R, "works_at", OWNER, "harborlight", ended("memory:1").anchor("2026-10-03")),
                    "2026-10-03"), Reason.STATED);
        });
    }

    @Test
    void valencesAreReportedNeverFlagged() {
        clocked(() -> {
            var g = new Graph().rel(R, "holds_view_on", OWNER, "topic-a",
                    holds("memory:1").anchor("2025-01-01").valence(Valence.UNFAVORABLE),
                    holds("memory:2").anchor("2026-10-03").valence(Valence.FAVORABLE));
            var a = row(g, "2026-10-03");
            yes(a);
            assertEquals(Valence.FAVORABLE, a.valence());
            assertEquals(List.of(Valence.FAVORABLE, Valence.UNFAVORABLE), a.valences());
            assertEquals(Contested.NONE, a.contested());
        });
    }

    // ---- the I/O matrix ----

    @Test
    void anUndeclaredTypeOrMissingFromTermReadsAsNotTimeless() {
        clocked(() -> {
            var g = new Graph()
                    .term("gadget", "Gadget")
                    .rel("undeclared", "mentors", "topic-a", "topic-b", holds("memory:1").anchor("2026-10-03"))
                    .rel("ghost", "kind_of", "nobody", "topic-b", holds("memory:1").anchor("2026-10-03"))
                    .rel("odd", "kind_of", "gadget", "topic-b", holds("memory:1").anchor("2026-10-03"));
            for (var rel : List.of("undeclared", "ghost", "odd")) {
                unknown(at(g, rel, "1900-01-01"));
                yes(at(g, rel, "2026-10-03"));
            }
            assertEquals(List.of("ghost", "odd", "undeclared"), ids(current(g, "2026-10-03").current()));
        });
    }

    @Test
    void aDanglingEvidenceIdIsIgnored() {
        clocked(() -> {
            var claim = holds("memory:1").anchor("2026-10-03").build(R);
            var g = new Graph().relIds(R, "located_in", OWNER, "ashgrove", List.of(claim.id(), "ev:missing"));
            g.records.add(claim);
            var a = row(g, "2026-10-03");
            yes(a);
            assertEquals(List.of(), a.others());
            var dangling = new Graph().relIds(R, "located_in", OWNER, "ashgrove", List.of("ev:missing"));
            unknown(row(dangling, "2026-10-03"));
            assertEquals(List.of(R), ids(current(dangling, "2026-10-03").undetermined()));
        });
    }

    @Test
    void atRefusesAnIdThatIsNotARelation() {
        clocked(() -> {
            var view = new GraphView(SCHEMA, new Graph().records, OWNER);
            var e = assertThrows(IllegalArgumentException.class, () -> view.at("ashgrove", LocalDate.EPOCH, NOW));
            assertTrue(e.getMessage().contains("ashgrove"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> view.at("nothing", LocalDate.EPOCH, NOW));
        });
    }

    @Test
    void occurrencesRefusesATermThatIsNotDated() {
        clocked(() -> {
            var view = new GraphView(SCHEMA, new Graph().records, OWNER);
            assertThrows(IllegalArgumentException.class, () -> view.occurrences(OWNER, LocalDate.EPOCH, NOW));
            assertThrows(IllegalArgumentException.class, () -> view.occurrences("nothing", LocalDate.EPOCH, NOW));
        });
    }

    @Test
    void resultCarriersAssertTheirInvariant() {
        clocked(() -> {
            var a = row(single(bare("memory:1")), "2026-10-03");
            assertThrows(IllegalStateException.class, a::resolvedReason);
            assertThrows(IllegalStateException.class, a::resolvedDeciding);
        });
    }

    // ---- fixtures: Ashgrove and Port Calloway ----

    private static Graph ashgrove() {
        return new Graph()
                .rel("r1", "located_in", OWNER, "ashgrove", holds("memory:1").anchor("2026-10-03").valid("2019/.."))
                .rel("r2", "located_in", OWNER, "port-calloway",
                        ended("memory:1").anchor("2026-10-03").valid("/2019"));
    }

    @Test
    void ashgroveAndPortCalloway() {
        clocked(() -> {
            var g = ashgrove();
            yes(at(g, "r1", "2026-10-03"));
            no(at(g, "r2", "2026-10-03"), Reason.STATED);
            noAssumed(at(g, "r1", "2018-06-01"), Reason.SCHEDULED);
            unknown(at(g, "r2", "2018-06-01"));
            unknown(at(g, "r1", "2019-06-01"));
            unknown(at(g, "r2", "2019-06-01"));
            yes(at(g, "r1", "2021-01-01"));
            no(at(g, "r2", "2021-01-01"), Reason.STATED);
            yesAssumed(at(g, "r1", "2028-01-01"));
            noAssumed(at(g, "r2", "2028-01-01"), Reason.STATED);
        });
    }

    // ---- fixtures: Kestrel CI ----

    private static Graph kestrel(Lineage m120Lineage) {
        return new Graph().rel("rk", "uses", OWNER, "kestrel-ci",
                holds("memory:120").anchor("2025-11-02")
                        .retired(noon("2026-10-03"), "memory:506", m120Lineage,
                                m120Lineage == Lineage.UPDATE ? "2026-10-03" : null),
                ended("memory:506").anchor("2026-10-03").valid("/2026-03")
                        .retired(noon("2026-10-20"), "memory:600", Lineage.RESTATEMENT, null),
                ended("memory:600").anchor("2026-10-20").valid("/2026-03"));
    }

    @Test
    void kestrelCi() {
        clocked(() -> {
            var g = kestrel(Lineage.UPDATE);
            var c = current(g, "2026-10-20");
            assertEquals(List.of("rk"), ids(c.ended()));
            var now = c.ended().getFirst();
            no(now, Reason.STATED);
            assertEquals(id("rk", "memory:600"), now.resolvedDeciding());
            assertEquals(List.of(id("rk", "memory:120"), id("rk", "memory:506")), now.others());

            var asOf = at(g, "rk", "2025-11-02");
            yes(asOf);
            assertEquals(id("rk", "memory:120"), asOf.deciding());
            yesAssumed(at(g, "rk", "2026-01-15"));
            unknown(at(g, "rk", "2026-03-15"));
            yesAssumed(at(g, "rk", "2026-01-15", Instant.parse("2026-01-01T00:00:00Z")));

            unknown(at(kestrel(Lineage.CORRECTION), "rk", "2025-11-02"));
        });
    }

    @Test
    void knownAtListsTheClaimsVisibleAtAnInstant() {
        clocked(() -> {
            var g = kestrel(Lineage.UPDATE);
            assertEquals(List.of(id("rk", "memory:120")),
                    read(g, v -> v.knownAt(Instant.parse("2026-01-01T00:00:00Z"))).stream().map(Evidence::id).toList());
            assertEquals(List.of(id("rk", "memory:120"), id("rk", "memory:506"), id("rk", "memory:600")),
                    read(g, v -> v.knownAt(NOW)).stream().map(Evidence::id).toList());
            assertEquals(List.of(id("rk", "memory:506"), id("rk", "memory:600")),
                    read(kestrel(Lineage.CORRECTION), v -> v.knownAt(NOW)).stream().map(Evidence::id).toList());
        });
    }

    @Test
    void asOfReadsEveryRelationIncludingRetiredOnes() {
        clocked(() -> {
            var g = corrected();
            var answers = read(g, v -> v.asOf(LocalDate.parse("2025-11-02"), NOW));
            assertEquals(List.of("r10", "rk"), ids(answers));
            unknown(find(answers, "r10"));
            yes(find(answers, "rk"));
        });
    }

    // ---- fixtures: Harborlight and the anchor phrases ----

    @Test
    void threeYearsAtHarborlight() {
        clocked(() -> {
            var g = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    holds("memory:1").anchor("2026-10-03").valid("2023~/.."));
            noAssumed(row(g, "2021-06-01"), Reason.SCHEDULED);
            noAssumed(row(g, "2021-12-31"), Reason.SCHEDULED);
            unknown(row(g, "2022-01-01"));
            unknown(row(g, "2023-06-01"));
            unknown(row(g, "2024-12-31"));
            yes(row(g, "2025-01-01"));
            yes(row(g, "2025-06-01"));
            yes(row(g, "2026-10-03"));
            yesAssumed(row(g, "2027-06-01"));
        });
    }

    @Test
    void theAnchorPhrasesAtTheirOwnAnchor() {
        clocked(() -> {
            var g = new Graph()
                    .rel("this-year", "works_at", OWNER, "harborlight", holds("memory:1").anchor("2026-10-03").valid("2026/.."))
                    .rel("today", "works_at", OWNER, "vela", holds("memory:1").anchor("2026-10-03").valid("2026-10-03/.."))
                    .rel("one-year", "uses", OWNER, "osprey", holds("memory:1").anchor("2026-10-03").valid("2025~/.."))
                    .rel("three-years", "uses", OWNER, "kestrel-ci", holds("memory:1").anchor("2026-10-03").valid("2023~/.."))
                    .rel("left", "located_in", OWNER, "berlin", ended("memory:1").anchor("2026-10-03").valid("/2026-10"));
            for (var rel : List.of("this-year", "today", "one-year", "three-years")) yes(at(g, rel, "2026-10-03"));
            no(at(g, "left", "2026-10-03"), Reason.STATED);
        });
    }

    // ---- fixtures: Berlin to Porto ----

    @Test
    void berlinToPorto() {
        clocked(() -> {
            var g = new Graph()
                    .rel("r8", "located_in", OWNER, "berlin", capped(holds("memory:120")))
                    .rel("r9", "located_in", OWNER, "porto", holds("memory:506").anchor("2026-10-03").valid("2026-08/.."));
            var c = current(g, "2026-10-03");
            assertEquals(List.of("r8"), ids(c.ended()));
            no(c.ended().getFirst(), Reason.CHANGED);
            assertEquals(List.of("r9"), ids(c.current()));
            yes(c.current().getFirst());

            yesAssumed(at(g, "r8", "2026-01-15"));
            noAssumed(at(g, "r9", "2026-01-15"), Reason.SCHEDULED);
            yesAssumed(at(g, "r8", "2026-09-01"));
            yes(at(g, "r9", "2026-09-01"));
        });
    }

    // ---- fixtures: guests ----

    @Test
    void aGuestNamingTheOwnerIsContestedButDecidesNothing() {
        clocked(() -> {
            var guestClaim = ended("memory:611").anchor("2026-10-03").valid("/2026-21").guest();
            var g = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    holds("memory:120").anchor("2025-11-02"), guestClaim);
            var c = current(g, "2026-10-03");
            assertEquals(List.of(R), ids(c.current()));
            var a = c.current().getFirst();
            yesAssumed(a);
            assertEquals(Contested.GUEST, a.contested());
            assertEquals(id(R, "memory:120"), a.deciding());
            assertEquals(List.of(id(R, "memory:611")), a.others());

            var guestOnly = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    ended("memory:611").anchor("2026-10-03").valid("/2026-21").guest());
            var only = current(guestOnly, "2026-10-03");
            assertEquals(List.of(R), ids(only.undetermined()));
            unknown(only.undetermined().getFirst());
            assertEquals(Contested.GUEST, only.undetermined().getFirst().contested());

            var guestHolds = new Graph().rel(R, "works_at", OWNER, "harborlight",
                    holds("memory:611").anchor("2026-10-03").guest());
            assertEquals(List.of(), current(guestHolds, "2026-10-03").current());
        });
    }

    @Test
    void aGuestNamingTwoOtherPeopleIsEligible() {
        clocked(() -> {
            var g = new Graph().rel(R, "works_at", "jonah", "vela", holds("memory:612").anchor("2026-10-03").guest());
            var c = current(g, "2026-10-03");
            assertEquals(List.of(R), ids(c.current()));
            yes(c.current().getFirst());
            assertEquals(Contested.NONE, c.current().getFirst().contested());
        });
    }

    // ---- fixtures: v2, corrected and retracted Relations ----

    @Test
    void aV2RelationIsUndetermined() {
        clocked(() -> {
            var g = new Graph().rel(R, "uses", OWNER, "osprey", bare("memory:130"));
            for (var d : List.of("2000-01-01", "2026-10-03", "2030-01-01")) {
                var a = row(g, d);
                unknown(a);
                assertEquals(List.of(id(R, "memory:130")), a.others());
            }
            assertEquals(List.of(R), ids(current(g, "2026-10-03").undetermined()));
        });
    }

    private static Graph corrected() {
        return kestrel(Lineage.UPDATE).rel("r10", "works_at", OWNER, "vela",
                holds("memory:120").anchor("2025-11-02")
                        .retired(Instant.parse("2026-10-03T12:00:00Z"), "memory:520", Lineage.CORRECTION, null));
    }

    @Test
    void aCorrectedRelationIsRetired() {
        clocked(() -> {
            var g = corrected();
            var c = current(g, "2026-10-20");
            for (var bucket : List.of(c.current(), c.upcoming(), c.ended(), c.denied(), c.undetermined())) {
                assertFalse(ids(bucket).contains("r10"), c::toString);
            }
            yes(at(g, "r10", "2025-11-02", Instant.parse("2026-01-01T00:00:00Z")));
        });
    }

    @Test
    void aRetractionWithNoLineageIsInvisibleFromItsRetiredAt() {
        clocked(() -> {
            var g = single(holds("memory:1").anchor("2025-11-02")
                    .retired(noon("2026-10-03"), "memory:2", null, null));
            yesAssumed(at(g, R, "2026-10-03", noon("2026-10-03").minusSeconds(1)));
            unknown(at(g, R, "2026-10-03", noon("2026-10-03")));
            var c = current(g, "2026-10-03");
            assertEquals(List.of(), c.current());
            assertEquals(List.of(), c.undetermined());
        });
    }

    @Test
    void currentSortsEachKindOfNoIntoItsBucket() {
        clocked(() -> {
            var g = new Graph()
                    .rel("scheduled", "located_in", OWNER, "porto", holds("memory:1").anchor("2026-10-03").valid("2027-03/.."))
                    .rel("former", "located_in", OWNER, "port-calloway", ended("memory:1").anchor("2026-10-03").valid("/2019"))
                    .rel("expired", "works_at", OWNER, "vela", holds("memory:1").anchor("2026-01-01").valid("2020/2026-06"))
                    .rel("updated", "located_in", OWNER, "berlin", capped(holds("memory:120")))
                    .rel("denied", "works_at", OWNER, "harborlight", denied("memory:1").anchor("2026-10-03"));
            var c = current(g, "2026-10-03");
            assertEquals(List.of("scheduled"), ids(c.upcoming()));
            assertEquals(List.of("expired", "former", "updated"), ids(c.ended()));
            assertEquals(Reason.EXPIRED, find(c.ended(), "expired").reason());
            assertEquals(Reason.STATED, find(c.ended(), "former").reason());
            assertEquals(Reason.CHANGED, find(c.ended(), "updated").reason());
            assertEquals(List.of("denied"), ids(c.denied()));
            assertEquals(List.of(), c.current());
            assertEquals(List.of(), c.undetermined());
        });
    }

    // ---- fixtures: Lanternlight Gala ----

    @Test
    void lanternlightGala() {
        clocked(() -> {
            var g = new Graph()
                    .term("gala", "Event", bare("memory:1").anchor("2026-10-03").occurs("2026-12-12").build("gala"))
                    .rel("r6", "located_in", "gala", "larkspur", holds("memory:1").anchor("2026-10-03"));
            var o = read(g, v -> v.occurrences("gala", LocalDate.parse("2026-10-03"), NOW));
            assertEquals(List.of(new Occurrence(EdtfInterval.parse("2026-12-12"), List.of("r6"), Timing.UPCOMING)),
                    o.values());
            assertEquals(List.of(), o.series());
            assertEquals(List.of(), o.previous());
            var c = current(g, "2026-10-03");
            assertEquals(List.of(), c.current(), "a Relation from a dated Term is read through occurrences");
            assertEquals(List.of(), c.undetermined());
        });
    }

    @Test
    void occurrencesSeparateRescheduledUndatedAndDistinctValues() {
        clocked(() -> {
            var g = new Graph()
                    .term("gala", "Event",
                            bare("memory:1").anchor("2026-10-03").occurs("2026-12-12")
                                    .retired(noon("2026-10-10"), "memory:2", Lineage.UPDATE, "2026-10-10").build("gala"),
                            bare("memory:2").anchor("2026-10-10").occurs("2027-01-09").build("gala"),
                            bare("memory:4").anchor("2026-10-11").occurs("2027-01").build("gala"))
                    .rel("r6", "located_in", "gala", "larkspur", holds("memory:1").anchor("2026-10-03"))
                    .rel("r7", "located_in", "gala", "porto", holds("memory:2").anchor("2026-10-10"))
                    .rel("r8", "involves", "gala", "jonah", holds("memory:3").anchor("2026-10-11"))
                    .rel("r9", "involves", "gala", OWNER, holds("memory:4").anchor("2026-10-11"));
            var o = read(g, v -> v.occurrences("gala", LocalDate.parse("2027-01-09"), NOW));
            assertEquals(List.of(
                    new Occurrence(EdtfInterval.parse("2027-01"), List.of("r9"), Timing.NEITHER),
                    new Occurrence(EdtfInterval.parse("2027-01-09"), List.of("r7"), Timing.NEITHER)), o.values());
            assertEquals(List.of("r8"), o.series());
            assertEquals(List.of(new Occurrence(EdtfInterval.parse("2026-12-12"), List.of("r6"), Timing.PAST)),
                    o.previous());
            var later = read(g, v -> v.occurrences("gala", LocalDate.parse("2027-02-01"), NOW));
            assertEquals(Timing.PAST, later.values().getFirst().timing());
            assertEquals(Timing.PAST, later.values().getLast().timing());

            var beforeReschedule = read(g, v -> v.occurrences("gala", LocalDate.parse("2026-10-05"),
                    noon("2026-10-05")));
            assertEquals(List.of(new Occurrence(EdtfInterval.parse("2026-12-12"), List.of("r6"), Timing.UPCOMING)),
                    beforeReschedule.values());
            assertEquals(List.of(), beforeReschedule.series());
            assertEquals(List.of(), beforeReschedule.previous());
        });
    }

    @Test
    void occurrencesSkipRetractedValuesAndClaimsNotYetRecorded() {
        clocked(() -> {
            var g = new Graph()
                    .term("gala", "Event",
                            bare("memory:1").anchor("2026-10-03").occurs("2026-12-12")
                                    .retired(noon("2026-10-10"), "memory:3", Lineage.CORRECTION, null).build("gala"),
                            bare("memory:2").anchor("2026-10-03").occurs("2027-01-09")
                                    .retired(noon("2026-10-10"), "memory:3", null, null).build("gala"),
                            bare("memory:3").anchor("2026-10-10").occurs("2027-03-03").build("gala"))
                    .rel("r6", "located_in", "gala", "larkspur", holds("memory:3").anchor("2026-10-10"))
                    .rel("r7", "located_in", "gala", "porto",
                            holds("memory:3").anchor("2026-10-10").recordedAt(noon("2027-06-01")))
                    .rel("r8", "involves", "gala", "jonah",
                            holds("memory:4").anchor("2026-10-11").recordedAt(noon("2027-06-01")));
            var o = read(g, v -> v.occurrences("gala", LocalDate.parse("2026-10-20"), NOW));
            assertEquals(List.of(new Occurrence(EdtfInterval.parse("2027-03-03"), List.of("r6"), Timing.UPCOMING)),
                    o.values());
            assertEquals(List.of(), o.series());
            assertEquals(List.of(), o.previous());
        });
    }

    @Test
    void occurrenceTimingBoundaries() {
        clocked(() -> {
            var g = new Graph()
                    .term("gala", "Event", bare("memory:1").anchor("2026-10-03").occurs("2026-12-12").build("gala"))
                    .rel("r6", "located_in", "gala", "larkspur", holds("memory:1").anchor("2026-10-03"));
            for (var c : List.of(new String[] {"2026-12-11", "UPCOMING"}, new String[] {"2026-12-12", "NEITHER"},
                    new String[] {"2026-12-13", "PAST"})) {
                var o = read(g, v -> v.occurrences("gala", LocalDate.parse(c[0]), NOW));
                assertEquals(Timing.valueOf(c[1]), o.values().getFirst().timing(), "today " + c[0]);
            }
        });
    }
}
