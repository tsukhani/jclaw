import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;
import tools.DeliveryResolver;

/**
 * JCLAW-726: pins the shared "most-recently-updated conversation ->
 * channel/peer" inference that {@link tools.DeliveryResolver} now owns as the
 * single source for both {@link tools.TaskTool} (a created task's default
 * output {@code delivery} target) and {@link tools.MessageTool} (a mid-turn
 * send's channel + peer). Previously each tool carried its own copy that had
 * to be "kept agreeing" by hand; these assertions lock the merged behaviour so
 * a regression in either tool surfaces here.
 */
class DeliveryResolverTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = persistAgent("delivery-resolver-agent");
    }

    @Test
    void inferSpecEmptyWhenNoConversation() {
        assertTrue(DeliveryResolver.inferSpec(agent).isEmpty(),
                "no conversation must yield no inferred spec");
        assertTrue(DeliveryResolver.mostRecentConversation(agent).isEmpty(),
                "no conversation must yield an empty Optional");
    }

    @Test
    void inferSpecForWebUsesConversationId() {
        var conv = persistConversation("web", null);
        assertEquals("web:" + conv.id, DeliveryResolver.inferSpec(agent).orElse(null),
                "web channel must address by conversation id (web convs may lack a peerId)");
    }

    @Test
    void inferSpecForExternalChannelUsesPeerId() {
        persistConversation("telegram", "878224171");
        assertEquals("telegram:878224171", DeliveryResolver.inferSpec(agent).orElse(null),
                "external channels must address by peerId");
    }

    @Test
    void inferSpecEmptyWhenUnsupportedChannel() {
        persistConversation("discord", "d-1");
        assertTrue(DeliveryResolver.inferSpec(agent).isEmpty(),
                "a non-deliverable channel must yield no inferred spec");
    }

    @Test
    void inferSpecEmptyWhenExternalChannelHasNoPeer() {
        persistConversation("slack", null);
        assertTrue(DeliveryResolver.inferSpec(agent).isEmpty(),
                "an external channel with no peerId leaves no target");
    }

    @Test
    void mostRecentlyUpdatedConversationWins() {
        // Seed an older slack conversation, then a newer telegram one — both
        // mostRecentConversation and inferSpec must pick the newer row. This is
        // the "most-recently-updated wins" agreement both tools depend on.
        Tx.run(() -> {
            var older = new Conversation();
            older.agent = agent;
            older.channelType = "slack";
            older.peerId = "C-OLD";
            older.save();
            try { Thread.sleep(5); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            var newer = new Conversation();
            newer.agent = agent;
            newer.channelType = "telegram";
            newer.peerId = "999";
            newer.save();
        });
        assertEquals("telegram:999", DeliveryResolver.inferSpec(agent).orElse(null),
                "inference must pick the most-recently-updated conversation");
        var recent = DeliveryResolver.mostRecentConversation(agent).orElseThrow();
        assertEquals("telegram", recent.channelType);
        assertEquals("999", recent.peerId);
    }

    // === Helpers ===

    private Agent persistAgent(String name) {
        return Tx.run(() -> {
            var a = new Agent();
            a.name = name;
            a.modelProvider = "test-provider";
            a.modelId = "test-model";
            a.enabled = true;
            a.save();
            return a;
        });
    }

    private Conversation persistConversation(String channelType, String peerId) {
        return Tx.run(() -> {
            var c = new Conversation();
            c.agent = agent;
            c.channelType = channelType;
            c.peerId = peerId;
            c.save();
            return c;
        });
    }
}
