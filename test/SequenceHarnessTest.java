import com.google.gson.JsonObject;
import memory.graph.GraphStore;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import models.Memory;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.Tx;
import services.grapheval.Certifier;
import services.grapheval.Configuration;
import services.grapheval.EvalProgress;
import services.grapheval.ExtractionPipeline;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.GraphCases;
import services.grapheval.GraphEvalHarness.DecisionModel;
import services.grapheval.SequenceHarness;
import services.grapheval.SequenceHarness.Variant;
import services.grapheval.SequenceScorer;
import services.grapheval.Sequences;
import utils.GsonHolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** JCLAW-1367: the sequence harness over the committed chains, with canned deciders only — no model call. */
class SequenceHarnessTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final ZoneId ZONE = ZoneId.of("Asia/Singapore");

    private static Sequences set() {
        try {
            return Sequences.load(Play.applicationPath.toPath().resolve(Sequences.DEFAULT_PATH), SCHEMA);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A decider answering every fixture memory's labels: types, holding and denied relations, statuses, lineage. */
    private static Decider gold(Sequences set, List<JsonObject> requests) {
        var choices = new HashMap<String, String>();
        var confidence = new HashMap<String, Double>();
        for (var chain : set.chains()) {
            for (var memory : chain.memories()) {
                var labels = memory.labels();
                for (var e : labels.entities()) {
                    if (e.mention() != null) choices.put(e.mention(), e.type());
                }
                for (var r : labels.relations()) {
                    var from = labels.entity(r.from());
                    var to = labels.entity(r.to());
                    if (from == null || to == null || from.mention() == null || to.mention() == null) continue;
                    var key = from.mention() + " " + r.type() + " " + to.mention();
                    switch (r.status()) {
                        case GraphCases.HOLDS, GraphCases.ENDED -> {
                            confidence.put(key, 0.99);
                            choices.put("status:" + key, r.status());
                        }
                        case GraphCases.DENIED -> confidence.put("not " + key, 0.99);
                        default -> { }
                    }
                }
                memory.supersedes().forEach((predId, lineage) -> choices.put(
                        "lineage:" + chain.memories().get(chain.indexOf(predId)).labels().text(),
                        lineage.name().toLowerCase(Locale.ROOT)));
            }
        }
        return ExtractionPipelineTest.scripted(requests, choices, confidence);
    }

    private static List<List<CaseRun>> askAll(Sequences set, Decider decider) {
        var out = new ArrayList<List<CaseRun>>();
        for (int c = 0; c < set.chains().size(); c++) {
            out.add(SequenceHarness.ask(SCHEMA, set.ownerName(), set.chains().get(c), c, "tev1", decider, ZONE));
        }
        return out;
    }

    private static SequenceHarness.Report run(Sequences set, Decider decider, int runs, Configuration configuration) {
        return SequenceHarness.run(set, SCHEMA, List.of(new DecisionModel("tev1", decider)), runs, 1, configuration,
                false, EvalProgress.none());
    }

    private static boolean isLineage(JsonObject request) {
        return request.getAsJsonObject("state").has("earlier");
    }

    private static List<String> graphListing() {
        var root = GraphStore.get().root();
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.map(Path::toString).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyProbeAnswersItsLabelOnGoldClaimsWithGoldLineage() {
        var set = set();
        var configuration = Configuration.defaultFor(SCHEMA);
        int probes = 0;
        for (int c = 0; c < set.chains().size(); c++) {
            var chain = set.chains().get(c);
            var sets = SequenceHarness.replay(SCHEMA, chain, c, List.of(), Variant.GOLD_CLAIMS_GOLD_LINEAGE,
                    configuration, null, ZONE);
            for (var p : chain.probes()) {
                var a = SequenceHarness.probe(SCHEMA, chain, p, sets.getLast(), ZONE);
                assertEquals(p.truth(), a.truth(), () -> chain.id() + " " + p + " answered " + a);
                assertEquals(p.assumed(), a.assumed(), () -> chain.id() + " " + p + " answered " + a);
                probes++;
            }
        }
        assertTrue(probes > 0, "the committed set carries probes");
    }

    @Test
    void theValidatorIsCleanAfterEveryMemoryInEveryVariant() {
        var set = set();
        var runs = askAll(set, gold(set, new CopyOnWriteArrayList<>()));
        var configuration = Configuration.defaultFor(SCHEMA);
        for (int c = 0; c < set.chains().size(); c++) {
            var chain = set.chains().get(c);
            for (var variant : Variant.values()) {
                var threshold = variant == Variant.GOLD_CLAIMS_GOLD_LINEAGE ? null : 0.5;
                var sets = SequenceHarness.replay(SCHEMA, chain, c, runs.get(c), variant, configuration, threshold,
                        ZONE);
                assertEquals(chain.memories().size(), sets.size());
                boolean asserts = !chain.tags().contains(GraphCases.GUEST_ABOUT_OWNER);
                assertEquals(asserts, sets.getLast().stream().anyMatch(r -> r instanceof OntologyRecord.Relation),
                        () -> chain.id() + " " + variant + ": a relation is written exactly when the chain asserts one");
                for (int i = 0; i < sets.size(); i++) {
                    var records = sets.get(i);
                    var violations = OntologyValidator.validate(SCHEMA, records);
                    int memory = i;
                    assertEquals(List.of(), violations,
                            () -> chain.id() + " " + variant + " after memory " + memory + ": " + records);
                }
            }
        }
    }

    @Test
    void aMemoryIsStampedAtNoonPlusItsIndexAndRetiresItsPredecessorsThere() {
        var set = set();
        var chain = set.chains().getFirst();
        for (int i = 0; i < chain.memories().size(); i++) {
            var expected = chain.memories().get(i).labels().capturedAt().atTime(LocalTime.NOON).atZone(ZONE)
                    .toInstant().plusSeconds(60L * i);
            assertEquals(expected, SequenceHarness.stamp(chain, i, ZONE));
        }
        assertEquals(1000L, SequenceHarness.syntheticId(0, 0));
        assertEquals(2001L, SequenceHarness.syntheticId(1, 1));
        var sets = SequenceHarness.replay(SCHEMA, chain, 0, List.of(), Variant.GOLD_CLAIMS_GOLD_LINEAGE,
                Configuration.defaultFor(SCHEMA), null, ZONE);
        var first = GraphStore.memorySource(SequenceHarness.syntheticId(0, 0));
        var claims = sets.get(1).stream().filter(r -> r instanceof Evidence e && e.source().equals(first))
                .map(Evidence.class::cast).toList();
        assertFalse(claims.isEmpty());
        for (var e : claims) {
            assertEquals(SequenceHarness.stamp(chain, 0, ZONE), e.recordedAt());
            assertEquals(SequenceHarness.stamp(chain, 1, ZONE), e.retiredAt());
            assertEquals(GraphStore.memorySource(SequenceHarness.syntheticId(0, 1)), e.retiredBy());
            assertEquals(OntologyRecord.Lineage.UPDATE, e.lineage());
        }
    }

    @Test
    void aRunWritesNoMemoryRowAndNoGraphFile() {
        var set = set();
        long rows = Tx.run(() -> Memory.count());
        var listing = graphListing();
        run(set, gold(set, new CopyOnWriteArrayList<>()), 1, Configuration.defaultFor(SCHEMA));
        assertEquals(rows, Tx.run(() -> Memory.count()).longValue());
        assertEquals(listing, graphListing());
    }

    @Test
    void eachGoldLinkIsAskedOneLineageQuestion() {
        var set = set();
        var requests = new CopyOnWriteArrayList<JsonObject>();
        askAll(set, gold(set, requests));
        int links = set.chains().stream().mapToInt(Sequences.Chain::links).sum();
        assertTrue(links > 0);
        assertEquals(links, requests.stream().filter(SequenceHarnessTest::isLineage)
                .mapToInt(r -> r.getAsJsonObject("questions").size()).sum());
    }

    @Test
    void theConfigurationNeverChangesWhatIsAsked() {
        var set = set();
        var a = new CopyOnWriteArrayList<JsonObject>();
        var b = new CopyOnWriteArrayList<JsonObject>();
        run(set, gold(set, a), 2, Configuration.defaultFor(SCHEMA));
        var off = Configuration.parse(com.google.gson.JsonParser.parseString("{\"terms\": 0.3}").getAsJsonObject(),
                SCHEMA);
        run(set, gold(set, b), 2, off);
        assertEquals(a.stream().map(JsonObject::toString).sorted().toList(),
                b.stream().map(JsonObject::toString).sorted().toList());
    }

    @Test
    void endToEndReadsAtTheWalksThresholdAndADisabledLineageRetracts() {
        var set = set();
        var configuration = Configuration.defaultFor(SCHEMA);
        var report = run(set, gold(set, new CopyOnWriteArrayList<>()), 2, configuration);
        var r0 = report.models().getFirst().runs().getFirst();
        var runs = askAll(set, gold(set, new CopyOnWriteArrayList<>()));
        var answers = new ArrayList<SequenceScorer.Answered>();
        for (int c = 0; c < set.chains().size(); c++) {
            var chain = set.chains().get(c);
            var last = SequenceHarness.replay(SCHEMA, chain, c, runs.get(c), Variant.END_TO_END, configuration,
                    r0.lineage().threshold(), ZONE).getLast();
            for (var p : chain.probes()) {
                var a = SequenceHarness.probe(SCHEMA, chain, p, last, ZONE);
                answers.add(new SequenceScorer.Answered(p.truth(), p.assumed(), a.truth(), a.assumed()));
            }
        }
        assertEquals(SequenceScorer.timeline(answers), r0.endToEnd());

        // Too few links to evaluate, so the walk is disabled and every superseded claim is a retraction.
        assertEquals(Certifier.DISABLED, r0.lineage().state());
        assertNull(r0.lineage().threshold());
        var chain = set.chains().getFirst();
        var retracted = SequenceHarness.replay(SCHEMA, chain, 0, runs.getFirst(), Variant.END_TO_END,
                configuration, null, ZONE).getLast();
        var first = GraphStore.memorySource(SequenceHarness.syntheticId(0, 0));
        var claims = retracted.stream().filter(r -> r instanceof Evidence e && e.source().equals(first))
                .map(Evidence.class::cast).toList();
        assertFalse(claims.isEmpty());
        claims.forEach(e -> {
            assertNotNull(e.retiredAt());
            assertNull(e.lineage());
        });
        var walked = SequenceHarness.replay(SCHEMA, chain, 0, runs.getFirst(), Variant.END_TO_END, configuration,
                0.5, ZONE).getLast();
        assertTrue(walked.stream().anyMatch(r -> r instanceof Evidence e && e.source().equals(first)
                && e.lineage() == OntologyRecord.Lineage.UPDATE));
    }

    @Test
    void aSecondAnswerThatDiffersLeavesTheLineageUnconfirmed() {
        var set = set();
        var chain = set.chains().getFirst();
        var earlier = chain.memories().getFirst().labels().text();
        var steady = gold(set, new CopyOnWriteArrayList<>());
        var asked = new AtomicInteger();
        Decider flaky = request -> {
            var response = steady.decide(request);
            if (!isLineage(request) || !request.getAsJsonObject("state").get("earlier").getAsString().equals(earlier)
                    || asked.incrementAndGet() == 1) {
                return response;
            }
            var answers = response.getAsJsonObject("answers");
            answers.keySet().forEach(key -> answers.getAsJsonObject(key)
                    .addProperty("choice", ExtractionPipeline.CORRECTION));
            return response;
        };
        var report = run(set, flaky, 1, Configuration.defaultFor(SCHEMA));
        var model = report.models().getFirst();
        assertNotNull(model.spotCheck());
        assertEquals(1, model.spotCheck().differing(), model.spotCheck()::toString);
        assertEquals(SequenceScorer.UNCONFIRMED, model.lineageState());

        var twice = run(set, gold(set, new CopyOnWriteArrayList<>()), 2, Configuration.defaultFor(SCHEMA));
        assertNull(twice.models().getFirst().spotCheck());
        assertEquals(2, twice.models().getFirst().runs().size());
    }

    @Test
    void aFailedLineageDecisionFailsItsMemoryAndDisablesLineage() {
        var set = set();
        var steady = gold(set, new CopyOnWriteArrayList<>());
        Decider broken = request -> {
            if (isLineage(request)) throw new IllegalStateException("decider unavailable");
            return steady.decide(request);
        };
        var report = run(set, broken, 2, Configuration.defaultFor(SCHEMA));
        var model = report.models().getFirst();
        assertEquals(Certifier.DISABLED, model.lineage().state());
        assertTrue(model.runs().getFirst().failedDecisions() > 0);
        assertEquals(SequenceScorer.REPORTED, model.timeline());

        var chain = set.chains().getFirst();
        var runs = SequenceHarness.ask(SCHEMA, set.ownerName(), chain, 0, "tev1", broken, ZONE);
        var sets = SequenceHarness.replay(SCHEMA, chain, 0, runs, Variant.END_TO_END,
                Configuration.defaultFor(SCHEMA), 0.5, ZONE);
        var second = GraphStore.memorySource(SequenceHarness.syntheticId(0, 1));
        assertTrue(sets.get(1).stream().noneMatch(r -> r instanceof Evidence e && e.source().equals(second)),
                "a memory with a failed decision adds no facts");
    }

    @Test
    void theSameAnswersSerializeIdentically() {
        var set = set();
        var a = GsonHolder.GSON.toJson(run(set, gold(set, new CopyOnWriteArrayList<>()), 1,
                Configuration.defaultFor(SCHEMA)));
        var b = GsonHolder.GSON.toJson(run(set, gold(set, new CopyOnWriteArrayList<>()), 1,
                Configuration.defaultFor(SCHEMA)));
        assertEquals(a, b);
        var report = run(set, gold(set, new CopyOnWriteArrayList<>()), 1, Configuration.defaultFor(SCHEMA));
        assertEquals(set.fingerprint(), report.sequences());
        assertEquals(SCHEMA.fingerprint(), report.schema());
        assertEquals(ExtractionPipeline.fingerprint(SCHEMA), report.extraction());
        assertEquals(SequenceHarness.SUPPLIED_SOURCE, report.configurationSource());
        assertEquals(set.chains().size(), report.chains());
    }
}
