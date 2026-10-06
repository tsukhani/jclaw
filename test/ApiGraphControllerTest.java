import com.google.gson.JsonParser;
import memory.graph.GraphStore;
import memory.graph.RunLedger.Entry;
import memory.graph.RunLedger.Outcome;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** JCLAW-1371: {@code POST /api/graph/retract}, the operator's retraction by run id, model, digest or certificate. */
class ApiGraphControllerTest extends FunctionalTest {

    private static final String CERT = "cert@4d2c9a7b1e30";
    /** Agent ids no other class writes a graph under. */
    private static final long AGENT = 9_713_710L;
    private static final long OTHER = 9_713_711L;

    @BeforeEach
    void setup() {
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void cleanup() throws Exception {
        clearCookies();
        GraphStore.get().deleteAgent(AGENT);
        GraphStore.get().deleteAgent(OTHER);
    }

    private void login() {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    private Http.Response retract(String body) {
        return POST("/api/graph/retract", "application/json", body);
    }

    /** One run under {@code cert} grounding a Term of its own. */
    private static void seed(long agent, String runId, String cert) throws Exception {
        var entry = new Entry(runId, agent, "memory:1", "sha256:00", Outcome.WRITTEN, "tev1", "sha256:d1", cert,
                "v3@000000000000", "x@000000000000", false, List.of());
        var evidence = new Evidence(Meta.fresh("e-" + runId, agent, Tier.TENTATIVE), "memory:1", null, null, null,
                runId, null, null, null, null, null, null, null, null, null, null);
        GraphStore.get().recordRun(agent, entry, records -> {
            var out = new ArrayList<OntologyRecord>(records);
            out.add(evidence);
            out.add(new Term(Meta.fresh("t-" + runId, agent, Tier.TENTATIVE), "Organization", "Acme", List.of(),
                    List.of(evidence.id())));
            return out;
        });
    }

    @Test
    void aCertificateRetractionReturnsItsCountsAndRemovesTheRecords() throws Exception {
        seed(AGENT, "run@a00000000001", CERT);
        seed(OTHER, "run@a00000000002", CERT);
        seed(OTHER, "run@a00000000003", "cert@000000000001");
        login();

        var response = retract("{\"certificate\": \"" + CERT + "\"}");

        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(Set.of("runs", "evidence", "records"), json.keySet());
        assertEquals(2, json.get("runs").getAsInt());
        assertEquals(2, json.get("evidence").getAsInt());
        assertEquals(2, json.get("records").getAsInt());
        assertEquals(List.of(), GraphStore.get().read(AGENT));
        assertEquals(Set.of("e-run@a00000000003", "t-run@a00000000003"),
                Set.copyOf(GraphStore.get().read(OTHER).stream().map(OntologyRecord::id).toList()));

        var again = JsonParser.parseString(getContent(retract("{\"certificate\": \"" + CERT + "\"}")))
                .getAsJsonObject();
        assertEquals(0, again.get("runs").getAsInt());
    }

    @Test
    void aRunIdRetractionReachesEveryAgent() throws Exception {
        seed(AGENT, "run@b00000000001", CERT + "x");
        seed(OTHER, "run@b00000000002", CERT + "x");
        login();
        var json = JsonParser.parseString(getContent(retract(
                "{\"runIds\": [\"run@b00000000001\", \"run@b00000000002\"]}"))).getAsJsonObject();
        assertEquals(2, json.get("runs").getAsInt());
    }

    @Test
    void anythingButExactlyOneValidKeyIs400() {
        login();
        for (var body : List.of("", "not json", "[]", "{}", "{\"model\": \"tev1\", \"digest\": \"sha256:d1\"}",
                "{\"certificate\": \"" + CERT + "\", \"dryRun\": true}", "{\"agent\": 7}", "{\"model\": \"\"}",
                "{\"digest\": \"   \"}", "{\"model\": 3}", "{\"runIds\": []}", "{\"runIds\": \"run@a00000000001\"}",
                "{\"runIds\": [\"\"]}", "{\"runIds\": [1]}")) {
            var response = retract(body);
            assertStatus(400, response);
            assertTrue(getContent(response).contains(ApiResponses.INVALID_REQUEST), body + " -> " + getContent(response));
        }
    }

    @Test
    void aValidKeyWithNothingToRetractReturnsZeros() {
        login();
        var response = retract("{\"certificate\": \"cert@ffffffffffff\"}");
        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(0, json.get("runs").getAsInt());
        assertEquals(0, json.get("evidence").getAsInt());
        assertEquals(0, json.get("records").getAsInt());
    }

    @Test
    void theAgentPrincipalIsRefused() {
        var request = newRequest();
        request.headers.put("authorization",
                new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        var response = POST(request, "/api/graph/retract", "application/json", "{\"certificate\": \"" + CERT + "\"}");
        assertStatus(403, response);
        assertTrue(getContent(response).contains("operator_only"), getContent(response));
    }

    @Test
    void anUnauthenticatedCallIs401() {
        clearCookies();
        assertStatus(401, retract("{\"certificate\": \"" + CERT + "\"}"));
    }
}
