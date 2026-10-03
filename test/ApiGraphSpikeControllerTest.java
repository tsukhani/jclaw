import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.graphspike.Agreement;
import services.graphspike.GraphCases;
import services.graphspike.HeldOut;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1344, JCLAW-1356: the graph spike endpoints' gate, validation and blind sheet. No request here reaches a model
 * call, so the test never spends one. Mirrors {@code ApiEvalsControllerTest}.
 */
class ApiGraphSpikeControllerTest extends FunctionalTest {

    private static final String AGENT = "graphspike-ctl-fixture-agent";

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

    private Http.Response spike(Http.Request request, String body) {
        return POST(request, "/api/graph/spike", "application/json", body);
    }

    private void assertRefused(String body, String expected) {
        seedAgent();
        var response = spike(authed(), body);
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains(expected), getContent(response));
    }

    @Test
    void aRequestWithoutTheSharedSecretIsRefused() {
        var response = spike(loadtestRequest(null), "{\"agent\":\"" + AGENT + "\"}");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void anUnknownAgentIs400() {
        var response = spike(authed(), "{\"agent\":\"no-such-graphspike-agent\",\"decisionModels\":[\"tev1\"]}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("no-such-graphspike-agent"), getContent(response));
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
    void aSampleCountOutOfRangeIs400() {
        seedAgent();
        var response = POST(authed(), "/api/graph/spike/heldout/sample", "application/json",
                "{\"agent\":\"" + AGENT + "\",\"count\":0}");
        assertEquals(400, response.status.intValue(), getContent(response));
        assertTrue(getContent(response).contains("count must be between"), getContent(response));
    }

    @Test
    void theBlindSheetHoldsOnlyTheSelectedIdsAndText() throws Exception {
        var file = Play.applicationPath.toPath().resolve(HeldOut.DIR).resolve("blind-sheet.json");
        var saved = Files.exists(file) ? Files.readString(file) : null;
        try {
            var response = POST(authed(), "/api/graph/spike/blind-sheet", "application/json", "{}");
            assertEquals(200, response.status.intValue(), getContent(response));
            var cases = GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH),
                    OntologySchema.seed());
            var selected = Agreement.blindSelection(cases);
            var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
            assertEquals(selected.size(), body.get("cases").getAsInt());

            var ids = new HashSet<String>();
            for (var e : JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("cases")) {
                var o = e.getAsJsonObject();
                assertEquals(Set.of("id", "text"), o.keySet());
                ids.add(o.get("id").getAsString());
            }
            assertEquals(new HashSet<>(selected), ids);
        } finally {
            if (saved == null) Files.deleteIfExists(file);
            else Files.writeString(file, saved);
        }
    }
}
