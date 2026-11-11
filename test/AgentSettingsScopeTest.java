import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A custom agent reads and changes only its own settings (JF-285), driven end to end with the bearer
 * and agent header {@code jclaw_api} sends.
 *
 * <p>Each case pairs a refusal with a permission on the same route, since a broken header would 403
 * every caller. {@code main}'s half is in {@code RequestPrincipalPolicyTest}: {@code main} is provisioned
 * at boot, and a sibling's {@code Fixtures.deleteDatabase} can remove it mid-suite.
 */
class AgentSettingsScopeTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";

    private record Seed(Long agentId, String name, Long bindingId) {}

    private Seed own;
    private Seed alien;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        own = seedAgent("jf285-own-");
        alien = seedAgent("jf285-alien-");
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    // --- one agent's settings -----------------------------------------------------------

    @Test
    void anAgentReadsItsOwnSettingsAndNotAnotherAgents() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + own.agentId()));
        assertStatus(200, mine);
        assertEquals(own.name(), json(mine).get("name").getAsString());

        assertRefusedAgent(alien.agentId(), asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + alien.agentId())));
        assertStatus(404, asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/999999999")));
    }

    @Test
    void everyPerAgentSettingsReadServesTheOwnerAndRefusesAnotherAgent() {
        for (var suffix : List.of("/prompt-breakdown?channelType=web", "/prompt-text?channelType=web",
                "/shell/effective-allowlist", "/tools", "/skills")) {
            var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + own.agentId() + suffix));
            assertStatus(200, mine);

            assertRefusedAgent(alien.agentId(),
                    asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + alien.agentId() + suffix)));
        }
        var migration = asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + own.agentId() + "/core-migration"));
        assertStatus(200, migration);
        assertRefusedAgent(alien.agentId(), asAgent(() ->
                GET(agentRequest(own.agentId()), "/api/agents/" + alien.agentId() + "/core-migration")));
    }

    @Test
    void anotherAgentsSkillFilesAreRefusedBeforeTheSkillIsLookedUp() {
        // The owner gets past the check to the next one, a skill it does not have.
        for (var suffix : List.of("/skills/no-such-skill/files", "/skills/no-such-skill/files/SKILL.md")) {
            assertStatus(404, asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + own.agentId() + suffix)));
            assertRefusedAgent(alien.agentId(),
                    asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents/" + alien.agentId() + suffix)));
        }
    }

    // --- changing settings --------------------------------------------------------------

    @Test
    void aCustomAgentChangesNoAgentsSettingsNotEvenItsOwn() {
        for (var target : List.of(own.agentId(), alien.agentId())) {
            var resp = asAgent(() -> PUT(agentRequest(own.agentId()), "/api/agents/" + target, "application/json",
                    "{\"description\":\"set by a custom agent\"}"));
            assertStatus(403, resp);
            assertEquals(List.of(AGENT_SCOPE, "Only the operator or the main agent may change an agent's settings."),
                    List.of(json(resp).get("code").getAsString(), json(resp).get("message").getAsString()));
            assertNull(commit(() -> Agent.<Agent>findById(target).description));
        }
    }

    // --- lists --------------------------------------------------------------------------

    @Test
    void theAgentListHoldsOnlyTheCaller() {
        var resp = asAgent(() -> GET(agentRequest(own.agentId()), "/api/agents"));
        assertStatus(200, resp);
        assertEquals(List.of(own.name()), names(JsonParser.parseString(getContent(resp)).getAsJsonArray()));
    }

    @Test
    void skillsByAgentHoldOnlyTheCallersEntry() {
        var resp = asAgent(() -> GET(agentRequest(own.agentId()), "/api/skills/by-agent"));
        assertStatus(200, resp);
        assertEquals(List.of(String.valueOf(own.agentId())), List.copyOf(json(resp).keySet()));
    }

    @Test
    void theWhatsAppBindingsListHoldsOnlyTheCallersBinding() {
        var resp = asAgent(() -> GET(agentRequest(own.agentId()), "/api/channels/whatsapp/bindings"));
        assertStatus(200, resp);
        assertEquals(List.of(own.bindingId()), ids(JsonParser.parseString(getContent(resp)).getAsJsonArray()));
    }

    @Test
    void aWhatsAppPairingStatusIsTheOwnersOnly() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()),
                "/api/channels/whatsapp/bindings/" + own.bindingId() + "/qr"));
        assertStatus(200, mine);
        assertEquals(List.of(own.bindingId(), "CLOUD_API"),
                List.of(json(mine).get("bindingId").getAsLong(), json(mine).get("transport").getAsString()));

        var theirs = asAgent(() -> GET(agentRequest(own.agentId()),
                "/api/channels/whatsapp/bindings/" + alien.bindingId() + "/qr"));
        assertStatus(403, theirs);
        assertEquals(List.of(AGENT_SCOPE, "WhatsApp binding " + alien.bindingId() + " belongs to another agent."),
                List.of(json(theirs).get("code").getAsString(), json(theirs).get("message").getAsString()));
    }

    @Test
    void anUnstampedBearerIsRefusedAnAgentsSettings() {
        assertRefusedAgent(own.agentId(), asAgent(() -> GET(bareBearerRequest(), "/api/agents/" + own.agentId())));
        var list = asAgent(() -> GET(bareBearerRequest(), "/api/agents"));
        assertStatus(403, list);
        assertEquals("This request is agent-originated but names no agent, so it cannot be scoped.",
                json(list).get("message").getAsString());
    }

    // --- helpers ------------------------------------------------------------------------

    private static void assertRefusedAgent(Long target, Http.Response response) {
        assertStatus(403, response);
        var body = json(response);
        assertEquals(List.of(AGENT_SCOPE, "Agent " + target + " is not the calling agent; only main reaches another "
                        + "agent's settings."),
                List.of(body.get("code").getAsString(), body.get("message").getAsString()), getContent(response));
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static List<String> names(JsonArray rows) {
        var out = new ArrayList<String>();
        for (var row : rows) out.add(row.getAsJsonObject().get("name").getAsString());
        return out;
    }

    private static List<Long> ids(JsonArray rows) {
        var out = new ArrayList<Long>();
        for (var row : rows) out.add(row.getAsJsonObject().get("id").getAsLong());
        return out;
    }

    /** An agent with one Cloud API WhatsApp binding. */
    private static Seed seedAgent(String prefix) {
        var name = prefix + UUID.randomUUID().toString().substring(0, 8);
        var ids = commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var binding = new WhatsAppBinding();
            binding.agent = agent;
            binding.transport = WhatsAppTransport.CLOUD_API;
            binding.phoneNumberId = "jf285-" + UUID.randomUUID();
            binding.accessToken = "seed-access-token";
            binding.enabled = false;
            binding.save();
            return new Long[]{agent.id, binding.id};
        });
        return new Seed(ids[0], name, ids[1]);
    }

    private static <T> T commit(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
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

    private static Http.Request agentRequest(Long agentId) {
        var request = bareBearerRequest();
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A bearer with no agent stamp, a token used outside the tool. */
    private static Http.Request bareBearerRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    /** A bearer call answers with a Set-Cookie carrying the agent principal, and FunctionalTest's
     *  saved cookies are JVM-global, so one left behind would 403 a sibling class. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }
}
