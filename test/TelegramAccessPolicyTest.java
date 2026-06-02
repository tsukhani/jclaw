import channels.TelegramAccessPolicy;
import channels.TelegramChannel;
import org.junit.jupiter.api.*;
import play.test.*;

/**
 * Unit coverage for the JCLAW-371 access matrix in {@link TelegramAccessPolicy}.
 * The four canonical cases the story calls out:
 *
 * <ul>
 *   <li>DM from owner → allowed</li>
 *   <li>DM from non-owner → rejected</li>
 *   <li>group message WITH mention → allowed (any member)</li>
 *   <li>group message WITHOUT mention → ignored</li>
 * </ul>
 *
 * <p>Exercising the pure predicate keeps these network-free; the full HTTP
 * wiring through the webhook controller is covered (for the rejection branches)
 * by {@code WebhookTelegramControllerTest}.
 */
class TelegramAccessPolicyTest extends UnitTest {

    @Test
    void dmFromOwnerIsAllowed() {
        assertTrue(TelegramAccessPolicy.isAllowed(true, "private", false),
                "owner DM is always served, mention irrelevant");
    }

    @Test
    void dmFromNonOwnerIsRejected() {
        assertFalse(TelegramAccessPolicy.isAllowed(false, "private", true),
                "a non-owner DM is rejected even if it mentions the bot");
    }

    @Test
    void groupMessageWithMentionIsAllowed() {
        // ownerMatches=false: any group member may address the bot.
        assertTrue(TelegramAccessPolicy.isAllowed(false, "group", true),
                "a mention-addressed group message is served regardless of sender");
        assertTrue(TelegramAccessPolicy.isAllowed(false, "supergroup", true),
                "supergroups behave like groups");
    }

    @Test
    void groupMessageWithoutMentionIsIgnored() {
        assertFalse(TelegramAccessPolicy.isAllowed(true, "group", false),
                "unaddressed group chatter is ignored even from the owner");
        assertFalse(TelegramAccessPolicy.isAllowed(false, "supergroup", false),
                "unaddressed supergroup chatter is ignored");
    }

    @Test
    void unknownChatTypeDefaultsToMentionGated() {
        // Anything that isn't "private" — including null or "channel" — is
        // treated as a group: served only when the bot is addressed.
        assertFalse(TelegramAccessPolicy.isAllowed(true, null, false),
                "null chat type defaults to the restrictive mention-gated branch");
        assertTrue(TelegramAccessPolicy.isAllowed(false, "channel", true),
                "an addressed message in a non-private context is served");
    }

    // ─── JCLAW-387 (B3): wake-word match feeds the access decision ───────

    @AfterEach
    void clearWakeWordConfig() {
        play.Play.configuration.remove("telegram.mentionPatterns");
    }

    @Test
    void wakeWordMatchAdmitsGroupMessageViaIsAllowed() {
        // A configured wake-word match surfaces as botMentioned=true, which the
        // existing group branch of isAllowed admits — no policy change needed.
        play.Play.configuration.setProperty("telegram.mentionPatterns", "(?i)\\bjarvis\\b");
        boolean addressed = TelegramChannel.matchesWakeWord("Jarvis, what's up");
        assertTrue(addressed, "the configured wake-word must match");
        assertTrue(TelegramAccessPolicy.isAllowed(false, "group", addressed),
                "a wake-word-addressed group message is served");
    }

    @Test
    void nonMatchingGroupMessageStillIgnored() {
        play.Play.configuration.setProperty("telegram.mentionPatterns", "(?i)\\bjarvis\\b");
        boolean addressed = TelegramChannel.matchesWakeWord("just chatting");
        assertFalse(addressed, "an unrelated message must not match the wake-word");
        assertFalse(TelegramAccessPolicy.isAllowed(false, "group", addressed),
                "an unaddressed group message stays ignored");
    }
}
