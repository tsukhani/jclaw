import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The two eval endpoints, exercised through their rejection paths and the empty run.
 *
 * <p>Deliberately never reaches a successful capture or a non-empty ingest: both spend a
 * model call per turn or per pair, so a test that got that far would bill the operator's
 * provider and depend on it being reachable. Everything up to that point — the loopback
 * gate, required fields, agent and suite resolution, the local-dataset switch, the limit
 * clamp — is decided before any provider is touched, and that is what is asserted here.
 */
class ApiEvalsControllerTest extends FunctionalTest {

    private static final String AGENT = "evalctl-fixture-agent";

    /**
     * A request that satisfies {@code LoadtestAuthCheck}: {@code newRequest()} leaves
     * {@code remoteAddress} null, which the guard correctly rejects, so the origin is set
     * explicitly. Mirrors {@code ApiMetricsControllerTest.loadtestRequest}.
     */
    private Http.Request loadtestRequest(String remoteAddress, String headerValue) {
        var req = newRequest();
        req.remoteAddress = remoteAddress;
        if (req.headers == null) {
            req.headers = new HashMap<>();
        }
        if (headerValue != null) {
            req.headers.put("x-loadtest-auth", new Http.Header("x-loadtest-auth", headerValue));
        }
        return req;
    }

    private Http.Request authed() {
        return loadtestRequest("127.0.0.1", Play.configuration.getProperty("application.secret"));
    }

    /**
     * Seed an agent on its own virtual thread so the inner transaction commits — the
     * FunctionalTest carrier already holds an open one, so an inline save would not be
     * visible to the request under test. Mirrors {@code ApiAgentsControllerTest}.
     */
    private void seedAgent() {
        var id = new AtomicLong();
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
                    id.set(a.id);
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

    // ─────── loopback + shared-secret gate ────────────────────────────────────

    @Test
    void captureRefusesARequestWithoutTheSharedSecret() {
        var response = POST(loadtestRequest("127.0.0.1", null), "/api/evals/capture",
                "application/json", "{}");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void memoryIngestRefusesARequestFromOffTheLoopback() {
        var response = POST(loadtestRequest("10.1.2.3",
                        Play.configuration.getProperty("application.secret")),
                "/api/evals/memory-ingest", "application/json", "{}");
        assertEquals(403, response.status.intValue());
    }

    // ─────── capture: everything decided before a model call ──────────────────

    @Test
    void captureRequiresASuite() {
        var response = POST(authed(), "/api/evals/capture", "application/json",
                "{\"agent\":\"" + AGENT + "\"}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void captureRequiresAnAgentRatherThanDefaultingToOne() {
        // Defaulting would let a sweep run against whichever agent happened to be first,
        // which is the accident the endpoint is documented as designing out.
        var response = POST(authed(), "/api/evals/capture", "application/json",
                "{\"suite\":\"tool-selection\"}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void captureReportsAnUnknownAgentByName() {
        var response = POST(authed(), "/api/evals/capture", "application/json",
                "{\"suite\":\"tool-selection\",\"agent\":\"no-such-agent-here\"}");
        assertEquals(404, response.status.intValue());
        assertTrue(getContent(response).contains("no-such-agent-here"),
                "the 404 should name the agent that was not found");
    }

    @Test
    void captureReportsAnUnknownSuiteAndSaysWhereItLooked() {
        seedAgent();
        var response = POST(authed(), "/api/evals/capture", "application/json",
                "{\"suite\":\"no-such-suite\",\"agent\":\"" + AGENT + "\"}");
        assertEquals(404, response.status.intValue());
        var body = getContent(response);
        assertTrue(body.contains("no-such-suite"), "the 404 should name the suite: " + body);
        assertTrue(body.contains("evals/suites"),
                "the 404 should name the directory searched: " + body);
    }

    /**
     * {@code local} selects the untracked dataset directory instead of the tracked one
     * (JCLAW-942). Whether that directory exists on a given machine decides which error
     * comes back, so the assertion is on the directory named — that is what proves the
     * switch was honored.
     */
    @Test
    void captureLooksInTheLocalDatasetWhenAskedTo() {
        seedAgent();
        var response = POST(authed(), "/api/evals/capture", "application/json",
                "{\"suite\":\"no-such-suite\",\"agent\":\"" + AGENT + "\",\"local\":true}");
        var body = getContent(response);
        assertTrue(response.status == 400 || response.status == 404,
                "expected a client error, got " + response.status + ": " + body);
        assertTrue(body.contains("local"),
                "the error should name the local dataset directory, not the tracked one: " + body);
    }

    // ─────── memory ingest ────────────────────────────────────────────────────

    @Test
    void memoryIngestRequiresAnAgent() {
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"pairs\":[]}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    void memoryIngestReportsAnUnknownAgentByName() {
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"agent\":\"no-such-agent-here\",\"pairs\":[]}");
        assertEquals(404, response.status.intValue());
        assertTrue(getContent(response).contains("no-such-agent-here"),
                "the 404 should name the agent that was not found");
    }

    @Test
    void memoryIngestRequiresPairsToBePresent() {
        seedAgent();
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"agent\":\"" + AGENT + "\"}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("pairs"), "the 400 should name the missing field");
    }

    @Test
    void memoryIngestRequiresPairsToBeAnArray() {
        seedAgent();
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"agent\":\"" + AGENT + "\",\"pairs\":\"not-an-array\"}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("pairs"), "the 400 should name the offending field");
    }

    @Test
    void memoryIngestOnAnEmptyCorpusReportsAnEmptyRun() {
        seedAgent();
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"agent\":\"" + AGENT + "\",\"pairs\":[]}");
        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(AGENT, json.get("agent").getAsString());
        assertEquals(0, json.get("pairs").getAsInt());
        assertEquals(0, json.get("captured").getAsInt());
        assertEquals(0, json.get("storedMemories").getAsInt(), "nothing was ingested, so nothing stored");
        assertEquals(0, json.get("perPairMs").getAsLong(),
                "per-pair cost is reported as zero rather than dividing by zero pairs");
    }

    /**
     * The limit caps how much of the supplied corpus is ingested. Zero is the useful case
     * to assert: it proves the cap is applied to the request rather than to the response,
     * and it is the only way to exercise the path with pairs present but no model call.
     */
    @Test
    void memoryIngestIngestsNothingWhenTheLimitIsZero() {
        seedAgent();
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"agent\":\"" + AGENT + "\",\"limit\":0,"
                        + "\"pairs\":[{\"user\":\"hello\",\"assistant\":\"hi\"}]}");
        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(0, json.get("pairs").getAsInt(),
                "a zero limit must ingest none of the supplied pairs");
        assertEquals(0, json.get("captured").getAsInt());
    }

    @Test
    void memoryIngestRejectsALimitThatIsNotANumber() {
        seedAgent();
        var response = POST(authed(), "/api/evals/memory-ingest", "application/json",
                "{\"agent\":\"" + AGENT + "\",\"limit\":\"lots\",\"pairs\":[]}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("limit"), "the 400 should name the bad field");
    }
}
