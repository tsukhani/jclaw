import com.google.gson.JsonObject;
import memory.ontology.OntologySchema;
import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.Decider;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;
import services.graphspike.GraphCases.Relation;
import services.graphspike.GraphSpikeHarness;
import services.graphspike.GraphSpikeHarness.DecisionModel;
import services.graphspike.GraphSpikeHarness.NamedProposer;
import services.graphspike.MentionProposer;
import services.graphspike.MentionProposer.Proposal;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static utils.GsonHolder.GSON;

/**
 * JCLAW-1344: the harness stores each case as a real memory, proves the run left it unchanged, and deletes it.
 * Concurrency 1 keeps every step on the test thread, inside the test's transaction.
 */
class GraphSpikeHarnessTest extends UnitTest {

    private static final List<Case> CASES = List.of(
            new Case("family", "Wren Castillo is the sister of Mateo Castillo.",
                    List.of(new Entity("Wren Castillo", "Person"), new Entity("Mateo Castillo", "Person")),
                    List.of(new Relation("Wren Castillo", "family_of", "Mateo Castillo"))),
            new Case("tea", "Dana Reyes prefers tea in the morning.",
                    List.of(new Entity("Dana Reyes", "Person")), List.of()),
            new Case("none", "Prefers short answers without bullet points.", List.of(), List.of()));

    private String agentId;

    @BeforeEach
    void setUp() {
        LuceneTestSync.closedForTest();
        agentId = String.valueOf(AgentService.create("graphspike-" + UUID.randomUUID().toString().substring(0, 8),
                "test-provider", "test-model").id);
    }

    @AfterEach
    void tearDown() {
        LuceneTestSync.release();
    }

    /** Proposes every labelled mention, and fails on the case with none. */
    private static final MentionProposer PROPOSER = text -> {
        var c = CASES.stream().filter(x -> x.text().equals(text)).findFirst().orElseThrow();
        return c.entities().isEmpty() ? Proposal.failed("stub: nothing to say")
                : new Proposal(c.entities().stream().map(Entity::mention).toList(), 0, null);
    };

    /** Types every mention a Person and states no relation, all at 0.9. */
    private static JsonObject answer(JsonObject request) {
        var answers = new JsonObject();
        for (var q : request.getAsJsonObject("questions").entrySet()) {
            var ids = q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet();
            var choice = q.getKey().startsWith("m") ? "Person" : ExtractionPipeline.NONE;
            var probabilities = new JsonObject();
            ids.forEach(id -> probabilities.addProperty(id, id.equals(choice) ? 0.9 : 0.1 / (ids.size() - 1)));
            var a = new JsonObject();
            a.addProperty("choice", choice);
            a.addProperty("confidence", 0.9);
            a.add("probabilities", probabilities);
            answers.add(q.getKey(), a);
        }
        var response = new JsonObject();
        response.add("answers", answers);
        return response;
    }

    private GraphSpikeHarness.Report run(Decider decider) {
        return GraphSpikeHarness.run(agentId, CASES, OntologySchema.seed(),
                List.of(new NamedProposer("stub/proposer", PROPOSER)),
                List.of(DecisionModel.of("tev1", decider), DecisionModel.skipped("jev-latest", "no API key")),
                0.5, 1);
    }

    @Test
    void casesAreStoredVerifiedUnchangedAndDeleted() {
        var seen = new AtomicBoolean();
        var report = run(request -> {
            seen.set(Memory.findByAgent(agentId).size() == CASES.size());
            return answer(request);
        });

        assertTrue(seen.get(), "every case is a stored memory while the decisions run");
        assertEquals(List.of(), Tx.run(() -> Memory.findByAgent(agentId)), "every case memory deleted afterwards");

        var integrity = report.memoryIntegrity();
        assertEquals(3, integrity.checked());
        assertEquals(3, integrity.unchanged());
        assertEquals(List.of(), integrity.changed());
        assertEquals(1, integrity.degradedChecked(), "the proposer failure");
        assertEquals(1, integrity.degradedUnchanged());

        assertEquals(List.of("tev1"), report.allowed());
        var pairings = report.pairings();
        assertEquals(List.of("jev-latest", "tev1"), pairings.stream().map(p -> p.model()).toList());
        assertEquals("no API key", pairings.getFirst().skipped());
        var tev1 = pairings.getLast();
        assertEquals(3, tev1.written());
        assertEquals(0, tev1.wrong());
        assertEquals(1, tev1.proposerFailures());
        assertEquals(1, tev1.missedLabels(), "family_of, answered none");
    }

    @Test
    void aDeciderThatEditsAMemoryIsReportedAndAllowsNothing() {
        var report = run(request -> {
            var state = request.getAsJsonObject("state").get("memory").getAsString();
            if (state.startsWith("Dana Reyes")) {
                Tx.run(() -> {
                    for (var m : Memory.findByAgent(agentId)) {
                        if (m.text.equals(state)) {
                            m.text = state + " Edited.";
                            m.save();
                        }
                    }
                });
            }
            return answer(request);
        });

        assertEquals(List.of("tea"), report.memoryIntegrity().changed());
        assertEquals(2, report.memoryIntegrity().unchanged());
        assertEquals(List.of(), report.allowed());
        assertTrue(report.models().stream().noneMatch(m -> m.allowed()), "no model is allowed");
        assertEquals(List.of(), Tx.run(() -> Memory.findByAgent(agentId)));
    }

    @Test
    void twoIdenticalRunsReportIdenticalJson() {
        assertEquals(GSON.toJson(run(GraphSpikeHarnessTest::answer)), GSON.toJson(run(GraphSpikeHarnessTest::answer)));
    }
}
