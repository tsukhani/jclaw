import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;
import services.Tx;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** A turn sent through {@code POST /api/chat/send} saves its usage record, as a streamed turn does (JF-149). */
class ChatSendUsageTest extends FunctionalTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"Sent and counted."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""";
    private static final String MODEL = "send-model";

    private HttpServer llmServer;
    private String provider;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        provider = "send-usage-" + UUID.randomUUID().toString().substring(0, 8);
        // A real port, because the request runs on Play's thread, where an HttpFactories binding does not reach.
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commitInFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Send\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void tearDown() {
        llmServer.stop(0);
        commitInFreshTx(() -> {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
    }

    @Test
    void aSentTurnSavesItsUsageAndAppearsInCost() {
        login();
        var agentId = commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "send-usage-agent";
            agent.modelProvider = provider;
            agent.modelId = MODEL;
            agent.enabled = true;
            agent.save();
            return agent.id;
        });

        var sent = POST("/api/chat/send", "application/json",
                "{\"agentId\": %d, \"message\": \"Count this turn.\"}".formatted(agentId));
        assertIsOk(sent);
        var body = JsonParser.parseString(getContent(sent)).getAsJsonObject();
        assertEquals(Set.of("conversationId", "response", "agentId", "agentName"), body.keySet());
        assertEquals("Sent and counted.", body.get("response").getAsString());

        var messages = JsonParser.parseString(
                getContent(GET("/api/conversations/" + body.get("conversationId").getAsLong() + "/messages")))
                .getAsJsonArray();
        var reply = messages.get(messages.size() - 1).getAsJsonObject();
        assertEquals("assistant", reply.get("role").getAsString());
        var usage = reply.getAsJsonObject("usage");
        assertNotNull(usage, reply::toString);
        assertEquals(10, usage.get("prompt").getAsInt());
        assertEquals(5, usage.get("completion").getAsInt());
        assertEquals(15, usage.get("total").getAsInt());
        assertEquals("provider", usage.get("usageSource").getAsString());
        assertEquals(provider, usage.get("modelProvider").getAsString());
        assertEquals(MODEL, usage.get("modelId").getAsString());

        var rows = JsonParser.parseString(getContent(GET("/api/metrics/cost?agentId=" + agentId)))
                .getAsJsonObject().getAsJsonArray("rows");
        assertEquals(1, rows.size(), rows::toString);
        assertEquals(usage, JsonParser.parseString(rows.get(0).getAsJsonObject().get("usageJson").getAsString()));
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
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
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
