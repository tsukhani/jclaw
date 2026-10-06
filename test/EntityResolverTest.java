import com.google.gson.JsonObject;
import memory.graph.GraphStore;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import models.MemoryAuthorType;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.grapheval.EntityResolver;
import services.grapheval.EntityResolver.Mention;
import services.grapheval.EntityResolver.Resolved;
import services.grapheval.EntityResolver.Settings;
import services.grapheval.EntityResolver.Shortlist;
import services.grapheval.EntityResolver.SignalLookup;
import services.grapheval.EntityResolver.Step;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.StatementRecords;
import services.grapheval.TermIds;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/** JCLAW-1370: entity resolution steps, the distinctive-name gate, stable Term ids, merge and undo. */
class EntityResolverTest extends UnitTest {

    private static final long AGENT = 7L;
    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final Settings PLAIN = new Settings(SCHEMA, null, null, null, null);
    private static final Instant AT = Instant.parse("2026-10-03T12:00:00Z");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3);

    @TempDir
    Path tmp;

    private List<OntologyRecord> graph = List.of();

    private static Mention m(String surface, String type, long memory) {
        return new Mention(surface, type, false, memory, MemoryAuthorType.HUMAN_TURN, AT, ANCHOR, surface + " came up.");
    }

    private static Mention guest(String surface, String type, long memory) {
        return new Mention(surface, type, false, memory, MemoryAuthorType.GUEST_TURN, AT, ANCHOR, surface + " came up.");
    }

    private static Mention operator(long memory) {
        return new Mention("The user", "Person", true, memory, MemoryAuthorType.HUMAN_TURN, AT, ANCHOR, "The user ran.");
    }

    /** Resolves into {@link #graph}, checking the seed validator and every record's tier and agent. */
    private Resolved run(Mention mention, Settings settings) {
        var r = EntityResolver.resolve(AGENT, graph, mention, settings);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, r.records()), mention::toString);
        for (var record : r.records()) {
            assertEquals(AGENT, record.meta().agentId(), record::id);
            assertEquals(Tier.TENTATIVE, record.meta().tier(), record::id);
        }
        graph = r.records();
        return r;
    }

    private Resolved run(Mention mention) {
        return run(mention, PLAIN);
    }

    private <T extends OntologyRecord> List<T> all(Class<T> family) {
        return graph.stream().filter(family::isInstance).map(family::cast).toList();
    }

    private Term term(String id) {
        return all(Term.class).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    private void replaceTerm(Term t) {
        graph = graph.stream().map(r -> r.id().equals(t.id()) ? t : r).toList();
    }

    // ---- canonical and identifier keys ----

    @Test
    void topicPluralsAndCaseResolveToOneTermWithTheirSurfacesAsAliases() {
        var first = run(m("oat milk", "Topic", 1));
        assertEquals(Step.NEW, first.step());
        assertFalse(first.attached());
        var second = run(m("Oat milk", "Topic", 2));
        var third = run(m("oat milks", "Topic", 3));
        assertEquals(Step.CANONICAL, third.step());
        assertTrue(second.attached() && third.attached());
        assertEquals(first.termId(), third.termId());
        assertEquals(1, all(Term.class).size());
        assertEquals(3, all(Mapping.class).size());
        var t = term(first.termId());
        assertEquals("oat milk", t.name());
        assertEquals(List.of("Oat milk", "oat milks"), t.aliases());
        assertEquals(List.of(List.of("oat milk"), List.of("Oat milk"), List.of("oat milks")),
                all(Mapping.class).stream().map(Mapping::surfaces).toList(), "the verbatim surface per Mapping");
    }

    @Test
    void aPlaceWithAndWithoutTheAndAPossessiveIsOneTerm() {
        var a = run(m("The Larkspur Inn", "Place", 1));
        var b = run(m("Larkspur Inn's", "Place", 2));
        assertEquals(a.termId(), b.termId());
        assertEquals(1, all(Term.class).size());
    }

    @Test
    void anIdentifierAttachesByItsKeyToOneArtifactWhateverItsTypeAndWritesNoRelation() {
        var a = run(m("https://Example.com/a", "Artifact", 1));
        var b = run(m("https://example.com/a", "System", 2));
        assertEquals(Step.IDENTIFIER, b.step());
        assertTrue(b.attached());
        assertEquals(TermIds.identifier(AGENT, "https://example.com/a"), a.termId());
        assertEquals(a.termId(), b.termId());
        assertEquals("Artifact", term(a.termId()).type());
        var path = run(m("https://example.com/A", "Artifact", 3));
        assertNotEquals(a.termId(), path.termId(), "a URL's path keeps its case");
        assertEquals(run(m("JCLAW-1370", "Artifact", 4)).termId(), run(m("JCLAW-1370", "Project", 5)).termId());
        assertEquals(run(m("~/notes/plan.md", "Artifact", 6)).termId(), run(m("~/notes/plan.md", "Artifact", 7)).termId());
        assertEquals(run(m("Dana@Example.com", "Artifact", 8)).termId(),
                run(m("dana@example.com", "Person", 9)).termId(), "an email without case");
        assertEquals(List.of(), all(Relation.class));
    }

    // ---- the gate ----

    @Test
    void shortNamesNeverAttachAndLinkByACandidateSameAs() {
        var one = run(m("Dana", "Person", 1));
        var two = run(m("Dana", "Person", 2));
        assertEquals(Step.SHORT_NAME, two.step());
        assertFalse(two.attached());
        assertEquals(TermIds.shortName(AGENT, "Person", "dana", 1), one.termId());
        assertEquals(TermIds.shortName(AGENT, "Person", "dana", 2), two.termId());
        assertEquals(List.of(one.termId()), two.candidates());
        assertEquals(2, all(Term.class).size());
        var relations = all(Relation.class);
        assertEquals(1, relations.size());
        var sameAs = relations.getFirst();
        assertEquals("same_as", sameAs.type());
        assertEquals(Set.of(one.termId(), two.termId()), Set.of(sameAs.from(), sameAs.to()));
        assertTrue(sameAs.from().compareTo(sameAs.to()) < 0, "the symmetric ends in order");
        var evidence = all(Evidence.class).stream().filter(e -> e.id().equals(sameAs.evidenceIds().getFirst()))
                .findFirst().orElseThrow();
        assertEquals("memory:2", evidence.source());
        assertNull(evidence.status());
        assertNull(evidence.valid());
        assertNull(evidence.occurs());
        assertNull(evidence.valence());

        var repass = run(m("Dana", "Person", 1));
        assertEquals(one.termId(), repass.termId(), "a re-pass makes no duplicate");
        assertEquals(2, all(Term.class).size());
    }

    @Test
    void aDistinctiveNameRepeatedIsOneTermWithTwoEvidence() {
        var a = run(m("Dana Reyes", "Person", 1));
        var b = run(m("Dana Reyes", "Person", 2));
        assertEquals(a.termId(), b.termId());
        assertEquals(TermIds.distinctive(AGENT, "Person", "dana reyes"), a.termId());
        assertEquals(2, term(a.termId()).evidenceIds().size());
    }

    @Test
    void theGateNeedsSixCharactersOrTwoTokensAndOneAndAHalfBits() {
        assertFalse(EntityResolver.distinctive("dakar"), "5 characters, one token");
        assertTrue(EntityResolver.distinctive("dakota"), "6 characters");
        assertTrue(EntityResolver.distinctive("dana reyes"), "two tokens");
        assertFalse(EntityResolver.distinctive("dana"), "a bare first name");
        assertEquals(1.5, EntityResolver.entropy("aaaabbcc"));
        assertTrue(EntityResolver.distinctive("aaaabbcc"), "1.5 bits");
        var below = "aaaaaaaaaaabbbbbbccccc";
        assertTrue(EntityResolver.entropy(below) < 1.5 && EntityResolver.entropy(below) > 1.49);
        assertFalse(EntityResolver.distinctive(below), "just below 1.5 bits");
        assertFalse(EntityResolver.distinctive("aa aa"), "two tokens but one character");
    }

    @Test
    void anAmbiguousCanonicalStepAttachesNothingAndLinksEachQualifier() {
        var first = run(m("Harborlight Analytics", "Organization", 1));
        var other = run(m("Harborlight Labs", "Organization", 2));
        var labs = term(other.termId());
        replaceTerm(new Term(labs.meta(), labs.type(), labs.name(), labs.mappingIds(), labs.evidenceIds(),
                List.of("Harborlight Analytics"), null));
        var r = run(m("Harborlight Analytics", "Organization", 3));
        assertEquals(Step.CANONICAL, r.step());
        assertFalse(r.attached());
        assertEquals(TermIds.shortName(AGENT, "Organization", "harborlight analytics", 3), r.termId(),
                "the distinctive id is taken");
        assertEquals(Set.of(first.termId(), other.termId()), Set.copyOf(r.candidates()));
        assertEquals(2, all(Relation.class).size());
        assertEquals(3, all(Term.class).size());
    }

    // ---- owner ----

    @Test
    void theOwnerKeepsItsIdAcrossARenameAndTheOldNameBecomesAnAlias() {
        var lin = new Settings(SCHEMA, "Avery Lin", null, null, null);
        var a = run(m("Avery Lin", "Person", 1), lin);
        assertEquals(Step.OWNER, a.step());
        assertEquals(TermIds.owner(AGENT), a.termId());
        assertEquals(a.termId(), run(operator(2), lin).termId(), "a leftover The user is the owner");
        var b = run(m("Avery Reyes", "Person", 3), new Settings(SCHEMA, "Avery Reyes", null, null, null));
        assertEquals(a.termId(), b.termId());
        var owner = term(a.termId());
        assertEquals("Avery Reyes", owner.name());
        assertTrue(owner.aliases().contains("Avery Lin"), owner.aliases()::toString);
    }

    @Test
    void anOwnerTermWithNoNameIsTheUser() {
        var r = run(operator(1));
        assertEquals("The user", term(r.termId()).name());
    }

    @Test
    void aGuestNamingTheOwnerIsANewTermThatNeverMergesIntoTheOwner() {
        var lin = new Settings(SCHEMA, "Avery Lin", null, null, null);
        var owner = run(m("Avery Lin", "Person", 1), lin).termId();
        var g = run(guest("Avery Lin", "Person", 2), lin);
        assertNotEquals(owner, g.termId());
        assertNotEquals(Step.OWNER, g.step());
        assertFalse(g.attached());
        assertNotEquals(owner, run(new Mention("The user", "Person", true, 3, MemoryAuthorType.GUEST_TURN, AT, ANCHOR,
                "x"), lin).termId(), "a guest's operator is not the owner");
        var before = graph;
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, before, g.termId(), owner));
    }

    // ---- fuzzy ----

    @Test
    void theFuzzyStepIsOffUntilAThresholdIsSetAndNeverSeesAShortName() {
        assertNull(EntityResolver.FUZZY_THRESHOLD);
        assertEquals(15, EntityResolver.FUZZY_GRID.size());
        assertEquals(0.99, EntityResolver.FUZZY_GRID.getFirst());
        assertEquals(0.85, EntityResolver.FUZZY_GRID.getLast());
        var known = run(m("Harborlight Analytics", "Organization", 1)).termId();
        var start = graph;
        var off = run(m("Harborlight Analytic", "Organization", 2), new Settings(SCHEMA, null,
                EntityResolver.FUZZY_THRESHOLD, null, null));
        assertEquals(Step.NEW, off.step());
        assertNotEquals(known, off.termId());

        graph = start;
        var on = run(m("Harborlight Analytic", "Organization", 2), new Settings(SCHEMA, null, 0.9, null, null));
        assertEquals(Step.FUZZY, on.step());
        assertEquals(known, on.termId());

        run(m("Danae", "Person", 3));
        var shortName = run(m("Dana", "Person", 4), new Settings(SCHEMA, null, 0.5, null, null));
        assertEquals(Step.SHORT_NAME, shortName.step());
    }

    @Test
    void twoFuzzyQualifiersAttachNothing() {
        var a = run(m("Harborlight Analytics", "Organization", 1)).termId();
        var b = run(m("Harborlight Analytica", "Organization", 2)).termId();
        var r = run(m("Harborlight Analytic", "Organization", 3), new Settings(SCHEMA, null, 0.9, null, null));
        assertEquals(Step.FUZZY, r.step());
        assertFalse(r.attached());
        assertEquals(TermIds.distinctive(AGENT, "Organization", "harborlight analytic"), r.termId());
        assertEquals(Set.of(a, b), Set.copyOf(r.candidates()));
    }

    // ---- shortlist ----

    /** Answers every question with {@code choice} at {@code p}; counts its calls. */
    private static Decider choosing(String choice, double p, List<JsonObject> requests) {
        return request -> {
            requests.add(request.deepCopy());
            var answers = new JsonObject();
            for (var q : request.getAsJsonObject("questions").entrySet()) {
                var ids = q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet();
                var probabilities = new JsonObject();
                ids.forEach(id -> probabilities.addProperty(id, id.equals(choice) ? p : (1 - p) / (ids.size() - 1)));
                var answer = new JsonObject();
                answer.addProperty("choice", choice);
                answer.addProperty("confidence", p);
                answer.add("probabilities", probabilities);
                answers.add(q.getKey(), answer);
            }
            var response = new JsonObject();
            response.add("answers", answers);
            return response;
        };
    }

    private static final String[] ORGS = {"Alder Works", "Birch Holdings", "Cedar Partners", "Dogwood Labs",
        "Elm Collective", "Fir Systems", "Ginkgo Group", "Hazel Studio", "Ironwood Trust", "Juniper Bank",
        "Kapok Foundry", "Larch Media"};

    private static SignalLookup signals(ToDoubleFunction<Term> vector) {
        return new SignalLookup() {
            @Override
            public Double vectorSimilarity(Term term) {
                return vector.applyAsDouble(term);
            }

            @Override
            public int entityOverlap(Term term) {
                return term.name().length() % 3;
            }

            @Override
            public double keywordOverlap(Term term) {
                return term.name().length() / 10.0;
            }

            @Override
            public Long daysApart(Term term) {
                return (long) term.name().charAt(0);
            }
        };
    }

    private void seedOrgs() {
        for (int i = 0; i < ORGS.length; i++) run(m(ORGS[i], "Organization", i + 1));
    }

    @Test
    void theShortlistOffersAtMostTenOptionsAndAttachesOnlyAtTheThreshold() {
        seedOrgs();
        var start = graph;
        var lookup = signals(t -> Arrays.asList(ORGS).indexOf(t.name()) / 12.0);
        var ranked = EntityResolver.rank(all(Term.class), lookup);
        var requests = new ArrayList<JsonObject>();

        var at = run(m("Northwind Traders", "Organization", 50),
                new Settings(SCHEMA, null, null, 0.8, new Shortlist("tev1", choosing("t3", 0.8, requests), lookup)));
        assertEquals(Step.SHORTLIST, at.step());
        assertTrue(at.attached());
        assertEquals(ranked.get(2), at.termId());
        assertEquals(1, requests.size());
        var question = requests.getFirst().getAsJsonObject("questions").entrySet().iterator().next().getValue()
                .getAsJsonObject();
        var criteria = question.getAsJsonObject("criteria");
        assertEquals(11, criteria.size(), "ten options plus new");
        assertTrue(criteria.has("new"));
        assertEquals(term(ranked.getFirst()).name() + " (Organization)", criteria.get("t1").getAsString());
        assertEquals("Northwind Traders came up.",
                requests.getFirst().getAsJsonObject("state").get("memory").getAsString());
        assertTrue(question.getAsJsonObject("instructions").get("rules").getAsString().contains("\"Northwind Traders\""));

        graph = start;
        var below = run(m("Northwind Traders", "Organization", 50),
                new Settings(SCHEMA, null, null, 0.8, new Shortlist("tev1", choosing("t3", 0.79, requests), lookup)));
        assertEquals(Step.SHORTLIST, below.step());
        assertFalse(below.attached());
        assertEquals(TermIds.distinctive(AGENT, "Organization", "northwind traders"), below.termId());

        graph = start;
        var calls = new AtomicInteger();
        Decider counting = request -> {
            calls.incrementAndGet();
            return choosing("t1", 0.99, new ArrayList<>()).decide(request);
        };
        var none = run(m("Northwind Traders", "Organization", 50),
                new Settings(SCHEMA, null, null, null, new Shortlist("tev1", counting, lookup)));
        assertEquals(Step.NEW, none.step());
        assertEquals(0, calls.get(), "no threshold, no model call");

        graph = start;
        var chosenNew = run(m("Northwind Traders", "Organization", 50),
                new Settings(SCHEMA, null, null, 0.8, new Shortlist("tev1", choosing("new", 0.95, requests), lookup)));
        assertFalse(chosenNew.attached());
    }

    @Test
    void aFailingOrInvalidDeciderMakesANewTermAndNothingEscapes() {
        seedOrgs();
        var start = graph;
        var lookup = signals(_ -> 0.5);
        Decider throwing = _ -> {
            throw new IllegalStateException("down");
        };
        Decider invalid = _ -> new JsonObject();
        for (var decider : List.of(throwing, invalid, choosing("t99", 0.9, new ArrayList<>()))) {
            graph = start;
            var r = run(m("Northwind Traders", "Organization", 50),
                    new Settings(SCHEMA, null, null, 0.5, new Shortlist("tev1", decider, lookup)));
            assertFalse(r.attached());
            assertEquals(TermIds.distinctive(AGENT, "Organization", "northwind traders"), r.termId());
        }
    }

    @Test
    void aMissingVectorRanksLastOnThatSignalAndResolutionCompletes() {
        assertEquals(List.of(1L, 2L, 0L), EntityResolver.signalOrder(Arrays.asList(null, 0.9, 0.8)));
        assertEquals(List.of(0L, 1L, 2L), EntityResolver.signalOrder(Arrays.asList(0.5, 0.5, null)), "ties by position");
        seedOrgs();
        SignalLookup missing = new SignalLookup() {
            @Override
            public Double vectorSimilarity(Term term) {
                return term.name().equals("Alder Works") ? null : 0.5;
            }

            @Override
            public int entityOverlap(Term term) {
                return 0;
            }

            @Override
            public double keywordOverlap(Term term) {
                return 0;
            }

            @Override
            public Long daysApart(Term term) {
                return null;
            }
        };
        var ranked = EntityResolver.rank(all(Term.class), missing);
        assertEquals(12, ranked.size());
        var r = run(m("Northwind Traders", "Organization", 50), new Settings(SCHEMA, null, null, 0.8,
                new Shortlist("tev1", choosing("t1", 0.9, new ArrayList<>()), missing)));
        assertTrue(r.attached());
        assertEquals(ranked.getFirst(), r.termId());
    }

    // ---- stable ids ----

    @Test
    void aTermWithdrawnWithItsSourceAndRecreatedKeepsItsId() throws Exception {
        var store = new GraphStore(tmp.resolve("graph"));
        var id = run(m("Harborlight Analytics", "Organization", 1)).termId();
        store.write(AGENT, graph);
        store.withdraw(AGENT, Set.of(1L));
        graph = store.read(AGENT);
        assertEquals(List.of(), all(Term.class), "the Term went with its only source");
        assertEquals(id, run(m("Harborlight Analytics", "Organization", 2)).termId());
        store.write(AGENT, graph);

        graph = List.of();
        run(m("Harborlight Analytics", "Organization", 1));
        assertEquals(id, run(m("Harborlight Analytics", "Organization", 2)).termId());
        store.write(AGENT, graph);
        store.withdraw(AGENT, Set.of(1L));
        graph = store.read(AGENT);
        assertEquals(List.of(id), all(Term.class).stream().map(Term::id).toList());
        assertEquals(TermIds.distinctive(AGENT, "Organization", "harborlight analytics"), id);
    }

    @Test
    void theSameStreamFromEmptyGraphsGivesEqualRecordsAndIds() {
        var stream = List.of(m("Avery Lin", "Person", 1), operator(2), m("Dana", "Person", 3), m("Dana", "Person", 4),
                m("oat milks", "Topic", 5), m("Oat milk", "Topic", 6), m("https://example.com/a", "Artifact", 7),
                m("Harborlight Analytics", "Organization", 8), m("Harborlight Analytic", "Organization", 9));
        var settings = new Settings(SCHEMA, "Avery Lin", 0.9, null, null);
        var runs = new ArrayList<List<OntologyRecord>>();
        for (int i = 0; i < 2; i++) {
            graph = List.of();
            stream.forEach(mention -> run(mention, settings));
            runs.add(graph);
        }
        assertEquals(runs.get(0), runs.get(1));
    }

    // ---- merge and undo ----

    @Test
    void mergeCopiesMappingsAndEvidenceAndUndoRemovesExactlyThose() {
        var into = run(m("Harborlight Analytics", "Organization", 1)).termId();
        var from = run(m("Harborlight", "Organization", 2)).termId();
        run(m("Harborlight", "Organization", 3));
        var before = graph;
        var merged = EntityResolver.merge(AGENT, before, from, into);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, merged));
        graph = merged;
        var fromTerm = term(from);
        var intoTerm = term(into);
        assertEquals(into, fromTerm.mergedInto());
        assertEquals(1 + fromTerm.mappingIds().size(), intoTerm.mappingIds().size());
        assertEquals(1 + fromTerm.evidenceIds().size(), intoTerm.evidenceIds().size());
        for (var id : fromTerm.evidenceIds()) {
            var copy = TermIds.derived("ev", into, id);
            assertTrue(intoTerm.evidenceIds().contains(copy));
            var e = all(Evidence.class).stream().filter(x -> x.id().equals(copy)).findFirst().orElseThrow();
            assertEquals(into, e.subjectId());
        }
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, merged, from, into),
                "already merged");
        var undone = EntityResolver.undo(AGENT, merged, from);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, undone));
        assertEquals(before, undone);
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.undo(AGENT, undone, from), "not merged");
    }

    @Test
    void aMergedTermResolvesToTheEndOfItsChain() {
        var into = run(m("Harborlight Analytics", "Organization", 1)).termId();
        var from = run(m("Harborlight Labs", "Organization", 2)).termId();
        graph = EntityResolver.merge(AGENT, graph, from, into);
        var r = run(m("Harborlight Labs", "Organization", 3));
        assertEquals(into, r.termId());
        assertTrue(r.attached());
    }

    @Test
    void aMergeAcrossTypesOrOfAnUnknownIdIsRefusedAndChangesNothing() {
        var org = run(m("Harborlight Analytics", "Organization", 1)).termId();
        var place = run(m("Larkspur Inn", "Place", 2)).termId();
        var before = List.copyOf(graph);
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, graph, org, place));
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, graph, org, "term:nothing"));
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, graph, "term:nothing", org));
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, graph, org, org));
        assertEquals(before, graph);
    }

    // ---- edge cases ----

    @Test
    void theGateCountsCodePointsNotUtf16Units() {
        var five = "\uD835\uDC00\uD835\uDC01\uD835\uDC02\uD835\uDC03\uD835\uDC04";
        assertEquals(10, five.length());
        assertFalse(EntityResolver.distinctive(five), "five code points in ten UTF-16 units");
        assertTrue(EntityResolver.distinctive(five + "\uD835\uDC05"), "six code points");
    }

    @Test
    void anOwnerRenameDropsTheNewNameFromTheAliases() {
        var id = run(m("Avery Lin", "Person", 1), new Settings(SCHEMA, "Avery Lin", null, null, null)).termId();
        var owner = term(id);
        replaceTerm(new Term(owner.meta(), owner.type(), owner.name(), owner.mappingIds(), owner.evidenceIds(),
                List.of("Avery Reyes"), null));
        run(m("Avery Reyes", "Person", 2), new Settings(SCHEMA, "Avery Reyes", null, null, null));
        assertEquals("Avery Reyes", term(id).name());
        assertEquals(List.of("Avery Lin"), term(id).aliases());
    }

    @Test
    void aGuestMentionNeverReachesATermMergedIntoTheOwner() {
        var lin = new Settings(SCHEMA, "Avery Lin", null, null, null);
        var owner = run(m("Avery Lin", "Person", 1), lin).termId();
        var other = run(m("Avery Lindqvist", "Person", 2), lin).termId();
        graph = EntityResolver.merge(AGENT, graph, other, owner);
        var g = run(guest("Avery Lindqvist", "Person", 3), lin);
        assertFalse(g.attached());
        assertNotEquals(owner, g.termId());
        assertNotEquals(other, g.termId());
        assertFalse(term(owner).evidenceIds().contains(Evidence.claimId(owner, "memory:3")));
    }

    @Test
    void theFuzzyStepRefusesATermWhoseKeyFailsTheGate() {
        assertTrue(new JaroWinklerSimilarity().apply("dakar", "dakary") >= 0.9);
        var shortName = run(m("Dakar", "Place", 1));
        assertEquals(Step.SHORT_NAME, shortName.step());
        var r = run(m("Dakary", "Place", 2), new Settings(SCHEMA, null, 0.9, null, null));
        assertEquals(Step.NEW, r.step());
        assertFalse(r.attached());
        assertEquals(List.of(), r.candidates());
    }

    private static Term t(String id) {
        return new Term(Meta.fresh(id, AGENT, Tier.TENTATIVE), "Organization", id, List.of(), List.of());
    }

    @Test
    void rankFusesTheFourSignalsWithDaysApartAscending() {
        var terms = List.of(t("term:c"), t("term:a"), t("term:b"));
        var vector = Map.of("term:a", 0.9, "term:c", 0.5);
        var entity = Map.of("term:a", 0, "term:b", 2, "term:c", 1);
        var keyword = Map.of("term:a", 0.1, "term:b", 0.3, "term:c", 0.2);
        Function<Map<String, Long>, SignalLookup> lookup = days -> new SignalLookup() {
            @Override
            public Double vectorSimilarity(Term term) {
                return vector.get(term.id());
            }

            @Override
            public int entityOverlap(Term term) {
                return entity.get(term.id());
            }

            @Override
            public double keywordOverlap(Term term) {
                return keyword.get(term.id());
            }

            @Override
            public Long daysApart(Term term) {
                return days.get(term.id());
            }
        };
        assertEquals(List.of("term:b", "term:a", "term:c"),
                EntityResolver.rank(terms, lookup.apply(Map.of("term:a", 1L, "term:b", 2L, "term:c", 3L))));
        assertEquals(List.of("term:b", "term:c", "term:a"),
                EntityResolver.rank(terms, lookup.apply(Map.of("term:a", 3L, "term:b", 2L, "term:c", 1L))),
                "fewer days apart ranks higher");
    }

    private void claimFreeRelation(String type, String a, String b, long memory) {
        var id = StatementRecords.relationId(SCHEMA, type, a, b);
        var from = type.equals("same_as") && a.compareTo(b) > 0 ? b : a;
        var to = from.equals(a) ? b : a;
        var evidenceId = Evidence.claimId(id, "memory:" + memory);
        var records = new ArrayList<>(graph);
        records.add(new Evidence(Meta.fresh(evidenceId, AGENT, Tier.TENTATIVE), "memory:" + memory, id,
                MemoryAuthorType.HUMAN_TURN, null, null, AT, null, null, null, null, ANCHOR, null, null, null, null));
        records.add(new Relation(Meta.fresh(id, AGENT, Tier.TENTATIVE), type, from, to, List.of(evidenceId)));
        graph = records;
    }

    private JsonObject askedCriteria(Mention mention, Settings base) {
        var requests = new ArrayList<JsonObject>();
        var settings = new Settings(SCHEMA, base.ownerName(), null, 0.5,
                new Shortlist("tev1", choosing("new", 0.9, requests), signals(_ -> 0.5)));
        var start = graph;
        var r = run(mention, settings);
        graph = start;
        assertFalse(r.attached());
        assertEquals(1, requests.size());
        return requests.getFirst().getAsJsonObject("questions").entrySet().iterator().next().getValue()
                .getAsJsonObject().getAsJsonObject("criteria");
    }

    @Test
    void theShortlistPoolMirrorsTheFuzzyGateAndTheGlossSkipsSameAs() {
        var lin = new Settings(SCHEMA, "Avery Lin", null, null, null);
        run(m("Avery Lin", "Person", 1), lin);
        run(m("Dana", "Person", 2), lin);
        var morgan = run(m("Morgan Blake", "Person", 3), lin).termId();
        var riley = run(m("Riley Quinn", "Person", 4), lin).termId();
        graph = EntityResolver.merge(AGENT, graph, riley, morgan);
        var harbor = run(m("Harborlight Analytics", "Organization", 5), lin).termId();
        claimFreeRelation("same_as", morgan, riley, 6);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, graph));

        var criteria = askedCriteria(m("Jordan Ellis", "Person", 30), lin);
        assertEquals(Set.of("t1", "new"), criteria.keySet(), criteria::toString);
        assertEquals("Morgan Blake (Person)", criteria.get("t1").getAsString(), "same_as is no gloss");

        claimFreeRelation("works_at", morgan, harbor, 7);
        assertEquals("Morgan Blake (Person, works_at Harborlight Analytics)",
                askedCriteria(m("Jordan Ellis", "Person", 30), lin).get("t1").getAsString());

        var harborOnly = askedCriteria(m("Northwind Traders", "Organization", 32), lin);
        assertEquals("Harborlight Analytics (Organization)", harborOnly.get("t1").getAsString(),
                "only the to end of works_at: no tail");

        run(m("https://example.com/a", "Artifact", 8));
        run(m("Quarterly Planning Report", "Artifact", 9));
        var artifacts = askedCriteria(m("Annual Budget Plan", "Artifact", 31), PLAIN);
        assertEquals(Set.of("t1", "new"), artifacts.keySet(), artifacts::toString);
        assertEquals("Quarterly Planning Report (Artifact)", artifacts.get("t1").getAsString());
    }

    private void claim(String termId, long memory, String occurs) {
        var source = "memory:" + memory;
        var id = Evidence.claimId(termId, source);
        var records = new ArrayList<OntologyRecord>();
        for (var r : graph) {
            if (r instanceof Term t && t.id().equals(termId)) {
                var ids = new ArrayList<>(t.evidenceIds());
                ids.add(id);
                records.add(new Term(t.meta(), t.type(), t.name(), t.mappingIds(), ids, t.aliases(), t.mergedInto()));
            } else {
                records.add(r);
            }
        }
        records.add(new Evidence(Meta.fresh(id, AGENT, Tier.TENTATIVE), source, termId, MemoryAuthorType.HUMAN_TURN,
                null, null, AT, null, null, null, null, ANCHOR, null, null, EdtfInterval.parse(occurs), null));
        graph = records;
    }

    @Test
    void mergeSkipsAClaimFromASourceTheTargetAlreadyClaimsAndUndoStillRestores() {
        var into = run(m("Lisbon Offsite Trip", "Event", 1)).termId();
        var from = run(m("Lisbon Offsite", "Event", 2)).termId();
        claim(into, 5, "2026-11");
        claim(from, 5, "2026-12");
        claim(from, 6, "2026-12");
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, graph));
        var before = graph;
        var merged = EntityResolver.merge(AGENT, before, from, into);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, merged));
        graph = merged;
        var ids = term(into).evidenceIds();
        assertFalse(ids.contains(TermIds.derived("ev", into, Evidence.claimId(from, "memory:5"))));
        assertTrue(ids.contains(TermIds.derived("ev", into, Evidence.claimId(from, "memory:6"))));
        assertTrue(ids.contains(TermIds.derived("ev", into, Evidence.claimId(from, "memory:2"))));
        var undone = EntityResolver.undo(AGENT, merged, from);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, undone));
        assertEquals(before, undone);
    }

    @Test
    void mergeIntoTheOwnerCopiesOnlyAMixedTermsHumanEvidence() {
        var lin = new Settings(SCHEMA, "Avery Lin", null, null, null);
        var owner = run(m("Avery Lin", "Person", 1), lin).termId();
        var morgan = run(m("Morgan Blake", "Person", 2), lin).termId();
        assertEquals(morgan, run(guest("Morgan Blake", "Person", 3), lin).termId());
        var before = graph;
        var merged = EntityResolver.merge(AGENT, before, morgan, owner);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, merged));
        graph = merged;
        var o = term(owner);
        assertTrue(o.evidenceIds().contains(TermIds.derived("ev", owner, Evidence.claimId(morgan, "memory:2"))));
        assertFalse(o.evidenceIds().contains(TermIds.derived("ev", owner, Evidence.claimId(morgan, "memory:3"))));
        assertEquals(2, o.mappingIds().size(), "its own Mapping and the human one");
        assertTrue(all(Evidence.class).stream().filter(e -> o.evidenceIds().contains(e.id()))
                .noneMatch(e -> e.authorType() == MemoryAuthorType.GUEST_TURN));
        var undone = EntityResolver.undo(AGENT, merged, morgan);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, undone));
        assertEquals(before, undone);
    }

    @Test
    void mergeAndUndoRefuseChainsCyclesAndTheOwnerAsTheMergedTerm() {
        var lin = new Settings(SCHEMA, "Avery Lin", null, null, null);
        var owner = run(m("Avery Lin", "Person", 1), lin).termId();
        var a = run(m("Morgan Blake", "Person", 2), lin).termId();
        var b = run(m("Riley Quinn", "Person", 3), lin).termId();
        var c = run(m("Jordan Ellis", "Person", 4), lin).termId();
        var start = graph;
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, start, owner, a),
                "the owner never merges away");

        var ab = EntityResolver.merge(AGENT, start, a, b);
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, ab, b, a), "a cycle");
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.merge(AGENT, ab, c, a),
                "into a merged Term");
        var abc = EntityResolver.merge(AGENT, ab, b, c);
        assertEquals(List.of(), OntologyValidator.validate(SCHEMA, abc));
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.undo(AGENT, abc, a),
                "its target merged onward");
        assertThrows(IllegalArgumentException.class, () -> EntityResolver.undo(AGENT, abc, "term:nothing"));
        var restored = EntityResolver.undo(AGENT, EntityResolver.undo(AGENT, abc, b), a);
        assertEquals(start, restored);
    }
}
