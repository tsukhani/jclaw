import models.Agent;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1344: {@code POST /api/graph/spike}'s gate and validation. Every request here is refused before a model call,
 * so the test never spends one. Mirrors {@code ApiEvalsControllerTest}.
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
        var response = spike(loadtestRequest(null), "{\"agent\":\"" + AGENT + "\",\"proposers\":[\"p/m\"]}");
        assertEquals(403, response.status.intValue());
    }

    @Test
    void anUnknownAgentIs400() {
        var response = spike(authed(), "{\"agent\":\"no-such-graphspike-agent\",\"proposers\":[\"p/m\"]}");
        assertEquals(400, response.status.intValue());
        assertTrue(getContent(response).contains("no-such-graphspike-agent"), getContent(response));
    }

    @Test
    void aProposerWithoutASlashIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"proposers\":[\"glm-5.3-flash\"]}", "must read provider/model");
    }

    @Test
    void anEmptyProposersArrayIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"proposers\":[]}", "at least one provider/model");
    }

    @Test
    void aThresholdOutsideTheUnitIntervalIs400() {
        assertRefused("{\"agent\":\"" + AGENT + "\",\"proposers\":[\"p/m\"],\"threshold\":0}", "threshold must be in");
        assertRefused("{\"agent\":\"" + AGENT + "\",\"proposers\":[\"p/m\"],\"threshold\":1.5}", "threshold must be in");
    }
}
