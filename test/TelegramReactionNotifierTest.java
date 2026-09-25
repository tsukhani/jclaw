import channels.TelegramReactionNotifier;
import channels.TelegramReactionNotifier.ReactionDelta;
import models.Agent;
import models.EventLog;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;

import java.util.List;

/**
 * JCLAW-1228: the sender-trust decision on the inbound Telegram reaction path, and the label
 * the reactor-derived event text carries. Reaction parsing, the notify policy and the
 * polling/webhook wiring stay in {@code TelegramPollingRunnerTest} and
 * {@code TelegramPollingRunnerDispatchTest}; this class owns only the owner gate.
 */
class TelegramReactionNotifierTest extends UnitTest {

    private static final String OWNER = "4242";
    private static final String STRANGER = "9999";

    private static ReactionDelta delta(String chatType, String reactorId) {
        return new ReactionDelta("100", chatType, 7, reactorId, "@guest",
                List.of("👍"), List.of());
    }

    @Test
    void onlyTheOwnerMayReactInAPrivateChat() {
        assertTrue(TelegramReactionNotifier.reactorAllowed(OWNER, delta("private", OWNER)),
                "the owner's own DM reaction is the case the notifier exists for");
        assertFalse(TelegramReactionNotifier.reactorAllowed(OWNER, delta("private", STRANGER)),
                "a stranger's DM reaction would otherwise run a turn over the owner's history");
        assertFalse(TelegramReactionNotifier.reactorAllowed(OWNER, delta("private", null)),
                "an anonymous / channel actor is not the owner");
    }

    @Test
    void groupReactionsStayWithTheNotifyPolicy() {
        // A group's peer id is the shared chat rather than the owner's DM, so reactor identity
        // is not the gate there — shouldNotifyReaction still decides.
        assertTrue(TelegramReactionNotifier.reactorAllowed(OWNER, delta("group", STRANGER)));
        assertTrue(TelegramReactionNotifier.reactorAllowed(OWNER, delta("supergroup", STRANGER)));
    }

    @Test
    void aStrangersPrivateReactionNeverReachesTheAgent() {
        var agent = new Agent();
        agent.name = "jclaw1228-reaction-" + System.nanoTime();

        EventLogger.flush();
        TelegramReactionNotifier.handleReaction(agent, "1228:tok", OWNER, delta("private", STRANGER));
        EventLogger.flush();

        assertEquals(0L, events(agent.name, "%Reaction notification%"),
                "a stranger's private reaction must not dispatch a turn");
        // The gate runs before the notify policy is read, so this count is 1 whatever the
        // policy is — it proves the zero above is the gate and not a suppressed policy.
        assertEquals(1L, events(agent.name, "%Dropped private-chat reaction%"),
                "the drop is recorded for the operator");
    }

    @Test
    void reactorDerivedTextIsNotLabeledSystem() {
        var text = TelegramReactionNotifier.reactionEventText(delta("private", STRANGER));
        assertFalse(text.startsWith("[system]"),
                "a reactor-chosen display name must not be handed to the model as a system line: " + text);
        assertTrue(text.startsWith("[reaction] @guest reacted"), text);
    }

    private static long events(String agentName, String messagePattern) {
        return EventLog.count("agentId = ?1 AND message LIKE ?2", agentName, messagePattern);
    }
}
