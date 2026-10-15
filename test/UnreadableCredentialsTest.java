import channels.ChannelTransport;
import models.Agent;
import models.Config;
import models.McpServer;
import models.SlackBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import services.UnreadableCredentials;
import services.UnreadableCredentials.Item;
import utils.CredentialCipher;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1455: what Settings lists for re-entry when a stored credential does not decrypt under this installation's
 * secret. The rows are this class's own, since other classes share the tables, and each step commits on its own.
 */
class UnreadableCredentialsTest extends UnitTest {

    private static final CredentialCipher ELSEWHERE = CredentialCipher.of("fake-secret-of-another-installation-9", null);
    private static final String UNREADABLE_KEY = "jclaw1455.unreadable.apiKey";
    private static final String READABLE_KEY = "jclaw1455.readable.apiKey";
    private static final String SERVER = "jclaw1455-unreadable-mcp";
    private static final String AGENT = "jclaw1455-unreadable-agent";

    @AfterEach
    void deleteOwnRows() {
        commitInFreshTx(() -> {
            ConfigService.delete(UNREADABLE_KEY);
            ConfigService.delete(READABLE_KEY);
            McpServer.delete("name = ?1", SERVER);
            Agent agent = Agent.find("name = ?1", AGENT).first();
            if (agent != null) AgentService.delete(agent);
            return null;
        });
        ConfigService.clearCache();
    }

    @Test
    void eachCredentialThatDoesNotDecryptIsNamedWithThePageThatTakesItAgain() {
        commitInFreshTx(() -> {
            Config.upsert(UNREADABLE_KEY, ELSEWHERE.encrypt("config." + UNREADABLE_KEY, "fake-key-from-elsewhere"));
            ConfigService.set(READABLE_KEY, "fake-key-from-here");

            var server = new McpServer();
            server.name = SERVER;
            server.transport = McpServer.Transport.HTTP;
            server.configJson = "{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\""
                    + ELSEWHERE.encrypt("mcp_server.config_json.headers.Authorization", "Bearer fake-elsewhere")
                    + "\",\"X-Api-Key\":\"fake-header-from-here\"}}";
            server.save();

            var binding = new SlackBinding();
            binding.agent = AgentService.create(AGENT, "openrouter", "gpt-4.1", null, null, false);
            binding.botToken = "xoxb-fake-from-here-1455";
            binding.signingSecret = "fake-signing-from-here";
            binding.transport = ChannelTransport.HTTP;
            binding.save();
            JPA.em().flush();
            JPA.em().createNativeQuery("UPDATE slack_binding SET signing_secret = ?1 WHERE id = ?2")
                    .setParameter(1, ELSEWHERE.encrypt("slack_binding.signing_secret", "fake-signing-from-elsewhere"))
                    .setParameter(2, binding.id)
                    .executeUpdate();
            return null;
        });
        ConfigService.clearCache();

        List<Item> own = commitInFreshTx(() -> UnreadableCredentials.list().stream()
                .filter(i -> i.owner().startsWith("jclaw1455")).toList());

        assertEquals(List.of(
                new Item("config", UNREADABLE_KEY, UNREADABLE_KEY, "/settings"),
                new Item("slack", AGENT, "signing secret", "/channels/slack"),
                new Item("mcp", SERVER, "headers.Authorization", "/mcp-servers")), own,
                "every value that does not decrypt, and none that does");
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
        if (err.get() != null) throw new AssertionError(err.get());
        return ref.get();
    }
}
