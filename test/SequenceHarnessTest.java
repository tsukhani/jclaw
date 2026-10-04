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
import services.TimezoneResolver;
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
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** JCLAW-1367: the sequence harness over the committed chains, with canned deciders only — no model call. */
class SequenceHarnessTest extends UnitTest {

    private static final OntologySchema SCHEMA = OntologySchema.seed();
    private static final ZoneId ZONE = TimezoneResolver.appZone();

    private static Sequences set() {
        try {
            return Sequences.load(Play.applicationPath.toPath().resolve(Sequences.DEFAULT_PATH), SCHEMA);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A decider answering each fixture memory's own labels — types, holding and denied relations, statuses, slots,
     * lineage — keyed by the memory's text, since one relation can hold in one memory and end in the next.
     */
    private static Decider gold(Sequences set, List<JsonObject> requests) {
        var byText = new HashMap<String, Decider>();
        for (var chain : set.chains()) {
            for (var memory : chain.memories()) {
                var labels = memory.labels();
                var choices = new HashMap<String, String>();
                var confidence = new HashMap<String, Double>();
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
                    if (r.valid() == null) continue;
                    var bounds = r.valid().split("/", -1);
                    for (var d : labels.dates()) {
                        if (bounds[0].equals(d.value())) choices.put("slot:" + d.span() + ":" + key, ExtractionPipeline.FROM);
                        if (bounds[1].equals(d.value())) choices.put("slot:" + d.span() + ":" + key, ExtractionPipeline.TO);
                    }
                }
                memory.supersedes().forEach((predId, lineage) -> choices.put(
                        "lineage:" + chain.memories().get(chain.indexOf(predId)).labels().text(),
                        lineage.name().toLowerCase(Locale.ROOT)));
                byText.put(labels.text(), ExtractionPipelineTest.scripted(requests, choices, confidence));
            }
        }
        return request -> Objects.requireNonNull(byText.get(request.getAsJsonObject("state").get("memory")
                .getAsString())).decide(request);
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

    /** Each graph path beside its content; a file another class removes mid-walk reads as empty. */
    private static List<String[]> graphFiles() {
        var root = GraphStore.get().root();
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.map(path -> {
                String content = "";
                try {
                    if (Files.isRegularFile(path)) content = Files.readString(path);
                } catch (IOException e) {
                    // Another class is rewriting its own agent's graph concurrently.
                }
                return new String[] {path.toString(), content};
            }).toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
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
        run(set, gold(set, new CopyOnWriteArrayList<>()), 1, Configuration.defaultFor(SCHEMA));
        var texts = new ArrayList<String>();
        var sources = new ArrayList<String>();
        for (int c = 0; c < set.chains().size(); c++) {
            var memories = set.chains().get(c).memories();
            for (int i = 0; i < memories.size(); i++) {
                texts.add(memories.get(i).labels().text());
                sources.add(GraphStore.memorySource(SequenceHarness.syntheticId(c, i)));
            }
        }
        for (var text : texts) {
            assertEquals(0L, Tx.run(() -> Memory.count("text like ?1", text)).longValue(), text);
        }
        for (var file : graphFiles()) {
            for (var source : sources) assertFalse(file[0].contains(source), file[0]);
            for (var text : texts) assertFalse(file[1].contains(text), () -> file[0] + " holds " + text);
        }
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
        var first = GraphStore.memorySource(SequenceHarness.syntheticId(0, 0));
        var retired = sets.get(1).stream().filter(r -> r instanceof Evidence e && e.source().equals(first))
                .map(Evidence.class::cast).toList();
        assertFalse(retired.isEmpty());
        retired.forEach(e -> assertEquals(SequenceHarness.stamp(chain, 1, ZONE), e.retiredAt(), e::toString));
        var gold = SequenceHarness.replay(SCHEMA, chain, 0, runs, Variant.GOLD_CLAIMS_MODEL_LINEAGE,
                Configuration.defaultFor(SCHEMA), 0.5, ZONE);
        assertTrue(gold.get(1).stream().noneMatch(r -> r instanceof Evidence e && e.source().equals(second)),
                "gold claims are not written for a memory whose run failed");
    }

    @Test
    void endToEndOnGoldAnswersMatchesGoldClaimsWithModelLineage() {
        var set = set();
        var report = run(set, gold(set, new CopyOnWriteArrayList<>()), 2, Configuration.defaultFor(SCHEMA));
        var r0 = report.models().getFirst().runs().getFirst();
        assertEquals(r0.goldClaimsModelLineage(), r0.endToEnd());
        assertEquals(0, r0.endToEnd().definiteWrong(), r0.endToEnd()::toString);
        assertTrue(r0.endToEnd().incomplete() < r0.endToEnd().probes(), r0.endToEnd()::toString);
    }

    @Test
    void aConfigurationWithNoRelationsWritesNoRelation() {
        var set = set();
        var runs = askAll(set, gold(set, new CopyOnWriteArrayList<>()));
        var off = Configuration.parse(com.google.gson.JsonParser.parseString("{\"terms\": 0.3}").getAsJsonObject(),
                SCHEMA);
        for (int c = 0; c < set.chains().size(); c++) {
            var chain = set.chains().get(c);
            var sets = SequenceHarness.replay(SCHEMA, chain, c, runs.get(c), Variant.END_TO_END, off, 0.5, ZONE);
            for (var records : sets) {
                assertTrue(records.stream().noneMatch(r -> r instanceof OntologyRecord.Relation),
                        () -> chain.id() + ": " + records);
            }
        }
    }

    @Test
    void aValidConfigurationParsesIntoItsStatementClasses() {
        var configuration = Configuration.parse(com.google.gson.JsonParser.parseString("""
                {"terms": 0.7, "relations": {"uses": 0.6},
                 "classes": {"status": {"state": "provisional", "threshold": 0.8},
                             "negation": {"state": "disabled"}}}""").getAsJsonObject(), SCHEMA);
        assertEquals(0.7, configuration.terms());
        assertEquals(0.6, configuration.relations().get("uses"));
        var classes = configuration.statementClasses();
        assertEquals(0.8, classes.status());
        assertNull(classes.negation());
        assertNull(classes.time());
        assertNull(classes.lineage());
    }

    @Test
    void theThresholdBoundsZeroAndOneAreAccepted() {
        var configuration = Configuration.parse(com.google.gson.JsonParser.parseString("""
                {"terms": 0, "relations": {"uses": 1},
                 "classes": {"time": {"state": "certified", "threshold": 1}}}""").getAsJsonObject(), SCHEMA);
        assertEquals(0.0, configuration.terms());
        assertEquals(1.0, configuration.relations().get("uses"));
        assertEquals(1.0, configuration.statementClasses().time());
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

        var defaulted = SequenceHarness.run(set, SCHEMA, List.of(new DecisionModel("tev1",
                gold(set, new CopyOnWriteArrayList<>()))), 1, 1, Configuration.defaultFor(SCHEMA), true,
                EvalProgress.none());
        assertEquals(SequenceHarness.DEFAULT_SOURCE, defaulted.configurationSource());
        assertTrue(GsonHolder.GSON.toJson(defaulted).contains("\"configurationSource\":\"default\""));
    }
}
