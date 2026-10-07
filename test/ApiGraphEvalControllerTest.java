import com.google.gson.JsonParser;
import memory.MemoryStoreFactory;
import memory.ontology.OntologySchema;
import models.Agent;
import models.Memory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.grapheval.Agreement;
import services.grapheval.CompetencyQuestions;
import services.grapheval.GraphCases;
import services.grapheval.HeldOut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static utils.GsonHolder.GSON;

/**
 * JCLAW-1344, JCLAW-1356: the graph eval endpoints' gate, validation and blind sheet. No request here reaches a model
 * call, so the test never spends one. Mirrors {@code ApiEvalsControllerTest}.
 */
class ApiGraphEvalControllerTest extends FunctionalTest {

    private static final String AGENT = "grapheval-ctl-fixture-agent";

    private Http.Request loadtestRequest(String headerValue) {
        var req = newRequest();
        req.remoteAddress = "127.0.0.1";
        if (req.headers == null) req.headers = new HashMap<>();
        if (headerValue != null) req.headers.put("x-loadtest-auth", new Http.Header("x-loadtest-auth", headerValue));
        return req;
    }

    private Http.Request authed() {
        return loadtestRequest(Play.configuration.getProperty("application.secret"));
    }

    /** Commits on its own thread: the request under test cannot see the carrier's open transaction. */
    private void seedAgent() {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    if (Agent.findByName(AGENT) != null) return null;
                    var a = new Agent();
                    a.name = AGENT;
                    a.modelProvider = "openrouter";
                    a.modelId = "gpt-4.1";
                    a.enabled = true;
                    a.save();
                    return null;
                });
            } catch (Throwable e) {
                err.set(e);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
    }

    private Http.Response evaluate(Http.Request request, String body) {
        return POST(request, "/api/graph/eval", "application/json", body);
    }

    private void assertRefused(String body, String expected) {
        seedAgent();
        var response = evaluate(authed(), body);
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains(expected), getContent(response));
    }

    @Test
    void aRequestWithoutTheSharedSecretIsRefused() {
        var response = evaluate(loadtestRequest(null), "{\"agent\":\"" + AGENT + "\"}");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void anUnknownAgentIs400() {
        var response = evaluate(authed(), "{\"agent\":\"no-such-grapheval-agent\",\"decisionModels\":[\"tev1\"]}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("no-such-grapheval-agent"), getContent(response));
    }

    @Test
    void theHostedModelIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"decisionModels\":[\"tev1\",\"jev-latest\"]}",
                "local Ollama models only");
    }

    @Test
    void proposersAreGoneAndAre400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"proposers\":[\"p/m\"]}", "proposers are gone");
    }

    @Test
    void aThresholdIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"threshold\":0.8}", "threshold is gone");
    }

    @Test
    void anUnknownSetIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"set\":\"everything\"}", "set must be");
    }

    /** Refused with {@code expected}, sent with no agent; a refusal that never names the agent shows none was required. */
    private void assertRefusedWithoutAgent(String body, String expected) {
        var response = evaluate(authed(), body);
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains(expected), getContent(response));
        assertFalse(getContent(response).contains("'agent'"), getContent(response));
    }

    @Test
    void theSequenceSetNeedsNoAgentAndRefusesABadConfiguration() {
        var sequences = "{\"set\":\"sequences\",\"decisionModels\":[\"tev1\"],\"configuration\":";
        assertRefusedWithoutAgent(sequences + "{\"terms\":0.8,\"relations\":{\"knows_nothing\":0.8}}}",
                "unknown relation 'knows_nothing'");
        assertRefusedWithoutAgent(sequences + "{\"terms\":0.8,\"classes\":{\"lineage\":{\"state\":\"provisional\","
                + "\"threshold\":0.8}}}}", "unknown class 'lineage'");
        assertRefusedWithoutAgent(sequences + "{\"terms\":0.8,\"classes\":{\"status\":{\"state\":\"maybe\"}}}}",
                "state must be certified, provisional or disabled");
        assertRefusedWithoutAgent(sequences + "[1]}", "configuration must be an object");
        assertRefusedWithoutAgent(sequences + "{\"terms\":1.2}}", "must be in [0, 1]");
        assertRefusedWithoutAgent(sequences + "{\"terms\":0.8,\"classes\":{\"time\":{\"state\":\"certified\"}}}}",
                "needs a threshold");
        assertRefusedWithoutAgent(sequences + "{\"terms\":0.8,\"relations\":{\"uses\":1.5}}}",
                "relation 'uses' must be in [0, 1]");
        assertRefusedWithoutAgent(sequences + "{\"terms\":0.8,\"classes\":{\"status\":{\"state\":\"provisional\"}}}}",
                "a provisional class needs a threshold");
    }

    @Test
    void aConfigurationOutsideTheSequenceSetIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"configuration\":{\"terms\":0.8}}",
                "configuration applies only to the sequences set");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"set\":\"heldout\",\"configuration\":{\"terms\":0.8}}",
                "configuration applies only to the sequences set");
    }

    @Test
    void theSequenceSetRefusesTheCaseSetsKnobs() {
        var sequences = "{\"set\":\"sequences\",\"decisionModels\":[\"tev1\"],";
        assertRefusedWithoutAgent(sequences + "\"recallFloor\":0.5}", "recallFloor does not apply to the sequences set");
        assertRefusedWithoutAgent(sequences + "\"pairFilter\":true}", "pairFilter does not apply to the sequences set");
    }

    @Test
    void theCaseSetStillRequiresAnAgent() {
        var response = evaluate(authed(), "{\"set\":\"cases\",\"decisionModels\":[\"tev1\"]}");
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains("'agent'"), getContent(response));
    }

    @Test
    void runsOutOfRangeAre400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"runs\":0}", "runs must be between");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"runs\":4}", "runs must be between");
    }

    @Test
    void aRecallFloorOutsideTheUnitIntervalIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"recallFloor\":1.5}", "recallFloor must be in");
    }

    @Test
    void aPairFilterThatIsNotABooleanIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"pairFilter\":\"yes\"}", "pairFilter must be a boolean");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"pairFilter\":1}", "pairFilter must be a boolean");
    }

    @Test
    void aSampleCountOutOfRangeIs400() {
        seedAgent();
        var response = POST(authed(), "/api/graph/eval/heldout/sample", "application/json",
                "{\"agent\":\"" + AGENT + "\",\"count\":0}");
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains("count must be between"), getContent(response));
    }

    @Test
    void theBlindSheetHoldsOnlyTheSelectedIdsTextCapturedAtAndOwner() throws Exception {
        var file = HeldOut.root().resolve(HeldOut.DIR).resolve("blind-sheet.json");
        var saved = Files.exists(file) ? Files.readString(file) : null;
        try {
            var response = POST(authed(), "/api/graph/eval/blind-sheet", "application/json", "{}");
            assertEquals(200, response.status.intValue(), getContent(response));
            var cases = GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH),
                    OntologySchema.seed());
            var selected = Agreement.blindSelection(cases);
            var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
            assertEquals(selected.size(), body.get("cases").getAsInt());

            var sheet = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var declared = GraphCases.userMd(Files.readString(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH)));
            assertEquals(declared, sheet.has("userMd") ? sheet.get("userMd").getAsString() : null,
                    "the labeller is told who the owner is");
            assertEquals(GraphCases.capturedAt(Files.readString(Play.applicationPath.toPath()
                    .resolve(GraphCases.DEFAULT_PATH))).toString(), sheet.get("capturedAt").getAsString());
            var ids = new HashSet<String>();
            for (var e : sheet.getAsJsonArray("cases")) {
                var o = e.getAsJsonObject();
                assertEquals(Set.of("id", "text", "capturedAt"), o.keySet());
                ids.add(o.get("id").getAsString());
            }
            assertEquals(new HashSet<>(selected), ids);
        } finally {
            if (saved == null) Files.deleteIfExists(file);
            else Files.writeString(file, saved);
        }
    }

    @Test
    void secondLabelsThatPredateV3AreNo400ButBadAdjudicationsStillAre() throws Exception {
        var second = Play.applicationPath.toPath().resolve(GraphCases.SECOND_LABELS_PATH);
        var e = assertThrows(IllegalArgumentException.class, () -> GraphCases.load(second, OntologySchema.seed()));
        var loaded = GraphCases.loadSecondLabels(second, OntologySchema.seed());
        assertEquals(List.of(), loaded.cases());
        assertEquals(Agreement.Result.PREDATES_V3 + e.getMessage(), loaded.reason());
        var absent = GraphCases.loadSecondLabels(second.resolveSibling("no-such-second-labels.json"),
                OntologySchema.seed());
        assertEquals(new GraphCases.SecondLabels(List.of(), null), absent, "no file is no reason");
        var verdicts = Play.applicationPath.toPath().resolve(GraphCases.ADJUDICATIONS_PATH);
        var saved = Files.exists(verdicts) ? Files.readString(verdicts) : null;
        try {
            Files.writeString(verdicts, "not json");
            assertRefused("{\"agent\":\"" + AGENT + "\",\"decisionModels\":[\"tev1\"]}", "invalid adjudications");
        } finally {
            if (saved == null) Files.deleteIfExists(verdicts);
            else Files.writeString(verdicts, saved);
        }
    }

    // --- JCLAW-1368: certification splits and re-scores -----------------------------------

    private void assertPostRefused(String path, String body, String expected) {
        var response = POST(authed(), path, "application/json", body);
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains(expected), getContent(response));
    }

    @Test
    void theNewRoutesNeedTheSharedSecret() {
        for (var path : List.of("/api/graph/eval/split", "/api/graph/eval/rescore")) {
            var response = POST(loadtestRequest(null), path, "application/json", "{}");
            assertEquals(403, response.status.intValue(), path);
        }
    }

    @Test
    void aSplitRunRefusesAThresholdASetAndAnUnknownSplit() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"split\":\"no-such-split\",\"threshold\":0.8}",
                "threshold is gone");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"split\":\"no-such-split\",\"set\":\"cases\"}",
                "set follows from the split");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"split\":\"no-such-split\"}", "no split named 'no-such-split'");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"split\":\"../x\"}", "no split named");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"agreedShare\":0}", "agreedShare must be in (0, 1]");
    }

    @Test
    void freezingASplitValidatesItsRequestBeforeWritingAnything() {
        var path = "/api/graph/eval/split";
        assertPostRefused(path, "{\"set\":\"cases\",\"share\":0.3}", "'name' is required");
        assertPostRefused(path, "{\"name\":\"x\"}", "'set' is required");
        assertPostRefused(path, "{\"name\":\"x\",\"set\":\"sequences\",\"share\":0.3}",
                "set must be 'cases' or 'heldout'");
        assertPostRefused(path, "{\"name\":\"x\",\"set\":\"cases\",\"share\":0.3,\"ids\":[\"c001\"]}",
                "give share or ids, not both");
        assertPostRefused(path, "{\"name\":\"x\",\"set\":\"cases\",\"share\":1.5}", "share must be in (0, 1]");
        assertPostRefused(path, "{\"name\":\"x\",\"set\":\"cases\",\"share\":0.3,\"seed\":\"abc\"}",
                "seed must be an integer");
    }

    @Test
    void aRescoreNeedsAKnownSplitAndAModelAndTakesNoThreshold() {
        var path = "/api/graph/eval/rescore";
        assertPostRefused(path, "{\"split\":\"no-such-split\",\"decisionModel\":\"tev1\"}",
                "no split named 'no-such-split'");
        assertPostRefused(path, "{\"decisionModel\":\"tev1\"}", "'split' is required");
        assertPostRefused(path, "{\"split\":\"x\",\"decisionModel\":\"tev1\",\"threshold\":0.8}",
                "threshold is gone");
    }

    // --- JCLAW-1374: the model-free coverage report ----------------------------------------

    private static final String COVERAGE = "/api/graph/eval/heldout/coverage";

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    /** Runs {@code body} with {@code content} at {@code path} (absent when null); the path is under the tests' own root. */
    private static void withFile(Path path, @Nullable String content, ThrowingRunnable body) throws Exception {
        try {
            Files.deleteIfExists(path);
            Files.createDirectories(path.getParent());
            if (content != null) Files.writeString(path, content);
            body.run();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void underTestTheWorkingFilesAreNeverTheOperatorsOwn() {
        var app = Play.applicationPath.toPath().normalize();
        for (var path : List.of(HeldOut.defaultPath(), CompetencyQuestions.defaultPath())) {
            assertFalse(path.startsWith(app.resolve(HeldOut.DIR)) || path.startsWith(app.resolve("evals")), path.toString());
            assertTrue(path.startsWith(app.resolve("tmp")), path.toString());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void theCoverageRouteNeedsTheSharedSecret() {
        var response = POST(loadtestRequest(null), COVERAGE, "application/json", "{}");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void theCoverageRouteWithoutAHeldOutFileIs400() throws Exception {
        withFile(HeldOut.defaultPath(), null, () ->
                assertPostRefused(COVERAGE, "{}", "no held-out file; run grapheval heldout-sample first"));
    }

    /** Sent with no decision model in the request, and the test DB configures none: the route asks for none. */
    @Test
    void theCoverageReportCountsLabelsWithoutAModelOrAMemoryRead() throws Exception {
        var text = "The user works at Harborlight Analytics and keeps port 8443 open for it.";
        try (var _ = LuceneTestSync.closedLease()) {
            String memoryId = null;
            try {
                memoryId = commitInFreshTx(() -> {
                    var agent = AgentService.create("grapheval-coverage-" + UUID.randomUUID().toString().substring(0, 8),
                            "test-provider", "test-model");
                    return MemoryStoreFactory.get().storeDeferred(String.valueOf(agent.id), text, "fact", 0.5);
                });
                long id = Long.parseLong(memoryId);
                Supplier<String> row = () -> commitInFreshTx(() -> {
                    Memory m = Memory.findById(id);
                    return m.text + "|" + m.updatedAt;
                });
                var before = row.get();
                var heldout = GSON.toJson(Map.of("cases", List.of(Map.of("memoryId", id, "labelled", true, "text", text,
                        "capturedAt", "2026-10-03", "authorType", "human_turn",
                        "entities", List.of(Map.of("id", "operator", "mention", "The user", "type", "Person"),
                                Map.of("id", "harborlight", "mention", "Harborlight Analytics", "type", "Organization")),
                        "relations", List.of(Map.of("from", "operator", "type", "works_at", "to", "harborlight",
                                "status", "holds")),
                        "notRepresentable", List.of("instruction"), "numbers", List.of("identifier"),
                        "backReference", true))));
                var questions = CompetencyQuestions.defaultPath();
                withFile(HeldOut.defaultPath(), heldout, () -> withFile(questions, null, () -> {
                    var response = POST(authed(), COVERAGE, "application/json", "{}");
                    assertEquals(200, response.status.intValue(), getContent(response));
                    var content = getContent(response);
                    var body = JsonParser.parseString(content).getAsJsonObject();
                    assertEquals(Set.of("questions", "grid", "notRepresentable", "numbers", "backReference", "strata",
                            "labelled"), body.keySet(), "every label section and no confusions");
                    assertTrue(body.get("questions").isJsonNull(), "no question file, no questions");
                    assertTrue(body.getAsJsonObject("grid").getAsJsonArray("cells").asList().stream()
                            .allMatch(c -> c.getAsJsonObject().getAsJsonArray("questions").isEmpty()));
                    assertEquals(1, body.getAsJsonObject("labelled").get("counted").getAsInt());
                    assertEquals(1, body.getAsJsonObject("numbers").getAsJsonObject("byKind").get("identifier").getAsInt());
                    assertEquals(1, body.getAsJsonObject("backReference").get("count").getAsInt());
                    for (var secret : List.of("memoryId", text, "Harborlight", "The user", "8443")) {
                        assertFalse(content.contains(secret), "the report names " + secret);
                    }

                    var asked = new ArrayList<String>();
                    asked.add("{\"id\": \"q01\", \"text\": \"Where does the owner work?\", \"types\": [\"Person\","
                            + " \"Organization\"], \"relation\": \"works_at\", \"facet\": \"status\"}");
                    for (int n = 2; n <= 20; n++) {
                        asked.add("{\"id\": \"q%02d\", \"text\": \"Which places does a project mention?\","
                                .formatted(n) + " \"types\": [\"Place\"], \"relation\": null, \"facet\": null}");
                    }
                    Files.writeString(questions, "{\"questions\": [" + String.join(",", asked) + "]}");
                    var withQuestions = JsonParser.parseString(getContent(
                            POST(authed(), COVERAGE, "application/json", "{}"))).getAsJsonObject();
                    assertEquals(20, withQuestions.getAsJsonArray("questions").size());
                    assertTrue(withQuestions.getAsJsonObject("grid").getAsJsonArray("cells").asList().stream()
                            .map(c -> c.getAsJsonObject())
                            .anyMatch(c -> c.get("from").getAsString().equals("Person")
                                    && c.get("relation").getAsString().equals("works_at")
                                    && c.get("to").getAsString().equals("Organization")
                                    && c.get("memories").getAsInt() == 1
                                    && c.getAsJsonArray("questions").toString().equals("[\"q01\"]")));

                    Files.writeString(questions, "{\"questions\": []}");
                    var broken = POST(authed(), COVERAGE, "application/json", "{}");
                    assertEquals(400, broken.status.intValue(), getContent(broken));
                    assertTrue(getContent(broken).contains("invalid competency questions"), getContent(broken));
                    // The held-out run loads the same file before it streams, so no model is reached.
                    assertPostRefused("/api/graph/eval", "{\"set\":\"heldout\",\"decisionModels\":[\"tev1\"]}",
                            "invalid competency questions");
                }));
                withFile(HeldOut.defaultPath(), "{\"cases\": 3}", () ->
                        assertPostRefused(COVERAGE, "{}", "invalid held-out set"));
                assertEquals(before, row.get(), "the memory row is unchanged");
            } finally {
                var stored = memoryId;
                if (stored != null) {
                    commitInFreshTx(() -> {
                        MemoryStoreFactory.get().delete(stored);
                        return null;
                    });
                }
            }
        }
    }
}
