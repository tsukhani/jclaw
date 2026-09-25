import channels.SlackChannel;
import channels.SlackInbound;
import models.DeliveredMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.Tx;

import java.util.List;

/** JCLAW-1298: a reply in the thread under a post JClaw delivered. */
class SlackThreadReplyTest extends UnitTest {

    private static final String OWNER = "UOWNER";

    private static SlackChannel.InboundMessage reply(String channel, String threadTs, String user, String text) {
        return new SlackChannel.InboundMessage(channel, user, text, threadTs, List.of(), "channel", false);
    }

    /** A delivered post in a fresh channel; returns that channel's id, the post's ts is "1700.000100". */
    private static String deliveredPost(String text) {
        var channel = "CTHREAD" + System.nanoTime();
        Tx.run(() -> {
            DeliveredMessage.record("slack", channel, List.of("1700.000100"), "the result of task 'brief'", text);
            return null;
        });
        return channel;
    }

    @Test
    void theOwnersPlainReplyUnderADeliveredPostIsHeard() {
        var msg = reply(deliveredPost("the briefing"), "1700.000100", OWNER, "why?");
        var parent = SlackInbound.deliveredParent(msg);

        assertNotNull(parent);
        assertTrue(SlackInbound.admitted(OWNER, msg, parent, false),
                "a reply under the bot's own post answers the bot, like a mention");
    }

    @Test
    void aPlainReplyUnderAnyOtherThreadStillNeedsTheMention() {
        var msg = reply(deliveredPost("the briefing"), "1700.999999", OWNER, "why?");

        assertNull(SlackInbound.deliveredParent(msg));
        assertFalse(SlackInbound.admitted(OWNER, msg, null, false));
    }

    @Test
    void anotherMemberUnderADeliveredPostIsNotServedWhenAnOwnerIsSet() {
        var msg = reply(deliveredPost("the briefing"), "1700.000100", "UOTHER", "why?");

        assertFalse(SlackInbound.admitted(OWNER, msg, SlackInbound.deliveredParent(msg), false));
    }

    @Test
    void onlyTheThreadsFirstReplyCarriesThePost() {
        var parent = SlackInbound.deliveredParent(reply(deliveredPost("the briefing"), "1700.000100", OWNER, "x"));

        assertEquals("[Replying to the result of task 'brief']\n> the briefing\n\nwhy?",
                SlackInbound.turnText("why?", parent));
        assertEquals("and then?", SlackInbound.turnText("and then?", parent),
                "later replies find the post in the conversation already");
    }

    @Test
    void aCommandInTheThreadLeavesTheQuoteForTheNextReply() {
        var parent = SlackInbound.deliveredParent(reply(deliveredPost("the briefing"), "1700.000100", OWNER, "x"));

        assertEquals("/model", SlackInbound.turnText("/model", parent));
        assertTrue(SlackInbound.turnText("why?", parent).startsWith("[Replying to the result of task 'brief']"));
    }

    @Test
    void aMessageOutsideAThreadIsTheTextAlone() {
        assertEquals("hello", SlackInbound.turnText("hello", null));
    }
}
