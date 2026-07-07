import agents.AgentRunner;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-370: unit coverage for the two pure helpers that both Telegram
 * dispatch sites ({@link channels.TelegramPollingRunner} and
 * {@link controllers.WebhookTelegramController}) use to compute the
 * conversation peer key and sender attribution for an inbound message:
 *
 * <ul>
 *   <li>{@link AgentRunner#telegramConversationPeerId} — a DM keys off the
 *       binding owner (unchanged); a group/supergroup keys off the chat id,
 *       plus a {@code ":topic:<threadId>"} suffix for forum-topic messages.</li>
 *   <li>{@link AgentRunner#telegramSenderAttributed} — group messages are
 *       prefixed with the sender's display name + id; DMs are left untouched.</li>
 * </ul>
 *
 * <p>These are exercised in isolation because neither dispatch site can be
 * driven end-to-end without leaking real LLM/network activity into the shared
 * test JVM (the polling runner reaches the SDK's {@code api.telegram.org}
 * socket; the webhook controller's accepted-message branch spawns a virtual
 * thread into the live agent path). The peerId-flows-through-unchanged
 * invariant is verified at the service layer in
 * {@code ConversationServiceTest}.
 */
class TelegramConversationKeyingTest extends UnitTest {

    private static final String OWNER = "42";

    // ===== peerId: private chat (DM) → binding owner key, unchanged =====

    @Test
    void privateChatKeysByBindingOwner() {
        // chat.id == user.id in a DM, but we deliberately key off the owner key
        // (binding.telegramUserId) so DM behavior is byte-for-byte unchanged.
        assertEquals(OWNER,
                AgentRunner.telegramConversationPeerId(OWNER, "private", "42", null),
                "a private chat must key off the binding owner, not the chat id");
    }

    @Test
    void privateChatIgnoresThreadIdSuffix() {
        // A DM never carries a forum topic; even if a thread id leaked in, the
        // private branch keys off the owner and never appends a topic suffix.
        assertEquals(OWNER,
                AgentRunner.telegramConversationPeerId(OWNER, "private", "42", 7),
                "private chat must ignore any thread id and stay owner-keyed");
    }

    // ===== peerId: group / supergroup → chat id (shared per chat) =====

    @Test
    void groupChatKeysByChatId() {
        assertEquals("-100",
                AgentRunner.telegramConversationPeerId(OWNER, "group", "-100", null),
                "a group must key off the chat id, not the binding owner");
    }

    @Test
    void supergroupChatKeysByChatId() {
        assertEquals("-100123",
                AgentRunner.telegramConversationPeerId(OWNER, "supergroup", "-100123", null),
                "a supergroup must key off the chat id");
    }

    @Test
    void nullChatTypeTreatedAsGroup() {
        // Mirrors TelegramAccessPolicy: anything that isn't "private" is a group
        // context, so a null chat type keys off the chat id, not the owner.
        assertEquals("-555",
                AgentRunner.telegramConversationPeerId(OWNER, null, "-555", null),
                "a null chat type must fall to the group (chat-id) keying");
    }

    // ===== peerId: forum topic → chat id + ":topic:<threadId>" =====

    @Test
    void forumTopicAppendsTopicSuffix() {
        assertEquals("-100:topic:9",
                AgentRunner.telegramConversationPeerId(OWNER, "supergroup", "-100", 9),
                "a forum-topic message must append \":topic:<threadId>\" to the chat id");
    }

    @Test
    void differentTopicsInSameChatGetDistinctKeys() {
        var topicA = AgentRunner.telegramConversationPeerId(OWNER, "supergroup", "-100", 1);
        var topicB = AgentRunner.telegramConversationPeerId(OWNER, "supergroup", "-100", 2);
        assertNotEquals(topicA, topicB,
                "two topics in the same chat must map to distinct conversation keys");
    }

    @Test
    void sameChatNoTopicVsTopicAreDistinct() {
        var noTopic = AgentRunner.telegramConversationPeerId(OWNER, "supergroup", "-100", null);
        var inTopic = AgentRunner.telegramConversationPeerId(OWNER, "supergroup", "-100", 1);
        assertNotEquals(noTopic, inTopic,
                "the general chat and a forum topic in the same chat are separate conversations");
    }

    // ===== two members in the same group/topic share one key =====

    @Test
    void twoMembersInSameGroupShareOneKey() {
        // The peer key is independent of the sender — both members of the same
        // group resolve to the same chat-id-keyed conversation.
        var memberA = AgentRunner.telegramConversationPeerId("ownerA", "group", "-100", null);
        var memberB = AgentRunner.telegramConversationPeerId("ownerB", "group", "-100", null);
        assertEquals(memberA, memberB,
                "members differing only in owner key must share one group conversation");
    }

    @Test
    void twoMembersInSameTopicShareOneKey() {
        var memberA = AgentRunner.telegramConversationPeerId("ownerA", "supergroup", "-100", 5);
        var memberB = AgentRunner.telegramConversationPeerId("ownerB", "supergroup", "-100", 5);
        assertEquals(memberA, memberB,
                "members in the same forum topic must share one conversation");
    }

    // ===== sender attribution: group annotated, DM untouched =====

    @Test
    void dmTextIsNotAttributed() {
        assertEquals("hello",
                AgentRunner.telegramSenderAttributed("hello", "private", "Ada Lovelace", OWNER),
                "a DM message must be passed through unannotated");
    }

    @Test
    void groupTextIsPrefixedWithDisplayNameAndId() {
        assertEquals("[Ada Lovelace (id 42)]: hello",
                AgentRunner.telegramSenderAttributed("hello", "group", "Ada Lovelace", "42"),
                "a group message must be prefixed with the sender's display name and id");
    }

    @Test
    void groupTextFallsBackToIdWhenNoDisplayName() {
        assertEquals("[42 (id 42)]: hello",
                AgentRunner.telegramSenderAttributed("hello", "group", null, "42"),
                "attribution must fall back to the sender id when no display name is set");
    }

    @Test
    void groupTextFallsBackToIdForBlankDisplayName() {
        assertEquals("[42 (id 42)]: hello",
                AgentRunner.telegramSenderAttributed("hello", "group", "   ", "42"),
                "a blank display name must fall back to the sender id");
    }

    @Test
    void nullChatTypeTextIsAttributedAsGroup() {
        assertEquals("[Ada (id 42)]: hi",
                AgentRunner.telegramSenderAttributed("hi", null, "Ada", "42"),
                "a null chat type is a group context, so the text must be attributed");
    }

    @Test
    void blankGroupTextIsNotAttributed() {
        // Media-only / empty-text group messages have nothing to prefix.
        assertEquals("",
                AgentRunner.telegramSenderAttributed("", "group", "Ada", "42"),
                "empty group text must stay empty (nothing to attribute)");
        assertNull(AgentRunner.telegramSenderAttributed(null, "group", "Ada", "42"),
                "null group text must stay null");
    }
}
