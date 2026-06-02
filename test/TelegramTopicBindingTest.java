import models.Agent;
import models.TelegramBinding;
import models.TelegramTopicBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;

/**
 * JCLAW-372: unit coverage for the per-topic agent override
 * ({@link TelegramTopicBinding}) and its resolver
 * {@link TelegramBinding#resolveAgentForTopic(String, Integer)}.
 *
 * <p>The override is DORMANT (no dispatch site consults it yet), so this is
 * the only place its persistence + resolution semantics are exercised. Mirrors
 * the structure of {@code TelegramBindingTest}: seed inside a {@link Tx}, then
 * assert via a fresh {@code Tx.run} read.
 */
class TelegramTopicBindingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void topicOverridePersistsAndRoundTrips() {
        // An override row must survive a save and reload with its tuple and FK
        // agent intact — the data-model half of the story.
        var defaultAgent = createAgent("ttb-rt-default");
        var overrideAgent = createAgent("ttb-rt-override");
        var binding = Tx.run(() -> seedBinding(defaultAgent, "tok-rt", "10"));
        Tx.run(() -> seedTopic(binding, "-100123", 7, overrideAgent));

        var found = Tx.run(() ->
                TelegramTopicBinding.findByBindingAndTopic(binding, "-100123", 7));
        assertNotNull(found, "persisted topic override must round-trip");
        assertEquals("-100123", found.chatId);
        assertEquals(Integer.valueOf(7), found.threadId);
        assertEquals(overrideAgent.id, found.agent.id);
        assertEquals(binding.id, found.binding.id);
    }

    @Test
    void resolveReturnsOverrideForMappedTopic() {
        // The mapped (chatId, threadId) routes to the override agent, not the
        // binding's default.
        var defaultAgent = createAgent("ttb-mapped-default");
        var overrideAgent = createAgent("ttb-mapped-override");
        var binding = Tx.run(() -> seedBinding(defaultAgent, "tok-mapped", "20"));
        Tx.run(() -> seedTopic(binding, "-100456", 42, overrideAgent));

        var resolved = Tx.run(() -> binding.resolveAgentForTopic("-100456", 42));
        assertNotNull(resolved, "mapped topic must resolve to an agent");
        assertEquals(overrideAgent.id, resolved.id,
                "mapped topic must resolve to the override agent");
    }

    @Test
    void resolveReturnsDefaultForUnmappedTopic() {
        // A topic with no override row falls back to the binding's default
        // agent — the "no row = default" convention.
        var defaultAgent = createAgent("ttb-unmapped-default");
        var overrideAgent = createAgent("ttb-unmapped-override");
        var binding = Tx.run(() -> seedBinding(defaultAgent, "tok-unmapped", "30"));
        Tx.run(() -> seedTopic(binding, "-100789", 1, overrideAgent));

        // Different thread id in the same chat → no matching override row.
        var resolved = Tx.run(() -> binding.resolveAgentForTopic("-100789", 99));
        assertNotNull(resolved, "unmapped topic must still resolve");
        assertEquals(defaultAgent.id, resolved.id,
                "unmapped topic must fall back to the binding's default agent");
    }

    @Test
    void resolveReturnsDefaultForNullThreadId() {
        // A non-topic message (null threadId) has no topic to override, so it
        // always resolves to the default — even when overrides exist on the chat.
        var defaultAgent = createAgent("ttb-null-default");
        var overrideAgent = createAgent("ttb-null-override");
        var binding = Tx.run(() -> seedBinding(defaultAgent, "tok-null", "40"));
        Tx.run(() -> seedTopic(binding, "-100321", 5, overrideAgent));

        var resolved = Tx.run(() -> binding.resolveAgentForTopic("-100321", null));
        assertNotNull(resolved, "non-topic message must still resolve");
        assertEquals(defaultAgent.id, resolved.id,
                "null threadId (non-topic) must resolve to the default agent");
    }

    private Agent createAgent(String name) {
        return Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private static TelegramBinding seedBinding(Agent agent, String token, String tgUserId) {
        var b = new TelegramBinding();
        b.agent = agent;
        b.botToken = token;
        b.telegramUserId = tgUserId;
        b.enabled = true;
        b.save();
        return b;
    }

    private static void seedTopic(TelegramBinding binding, String chatId,
                                  Integer threadId, Agent agent) {
        var t = new TelegramTopicBinding();
        t.binding = binding;
        t.chatId = chatId;
        t.threadId = threadId;
        t.agent = agent;
        t.save();
    }
}
