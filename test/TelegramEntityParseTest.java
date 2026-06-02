import channels.TelegramChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.test.UnitTest;

/**
 * JCLAW-367: MessageEntity / @mention parsing + sender-identity capture in
 * {@link TelegramChannel#parseUpdate(Update, String, Long)}.
 *
 * <p>Builds SDK {@link Update} objects from raw Bot API JSON (the same shape
 * Telegram POSTs) so the entity offsets, sender fields, and reply context are
 * exactly what the deserializer produces in production. The bot under test is
 * {@code @jclaw_bot} with numeric user id {@code 555}.
 */
class TelegramEntityParseTest extends UnitTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();
    private static final String BOT_USERNAME = "jclaw_bot";
    private static final Long BOT_USER_ID = 555L;

    private static Update update(String json) throws Exception {
        return JACKSON.readValue(json, Update.class);
    }

    // ── AC1: bot_command with @botname suffix ───────────────────────────

    @Test
    void botCommandWithBotnameSuffix_flagsBotMentionedAndCapturesSender() throws Exception {
        // "/help@jclaw_bot" — the bot_command entity covers offset 0..14.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada","last_name":"Lovelace","username":"ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"/help@jclaw_bot",
                  "entities":[{"type":"bot_command","offset":0,"length":15}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "/help@jclaw_bot addresses this bot → botMentioned must be true");
        assertEquals("42", msg.fromId());
        assertEquals("ada", msg.fromUsername());
        assertEquals("Ada Lovelace", msg.fromDisplayName(),
                "display name is first + last");
    }

    @Test
    void botCommandWithOtherBotSuffix_doesNotFlagBotMentioned() throws Exception {
        // "/help@other_bot" — bot_command suffix matches a different bot.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"/help@other_bot",
                  "entities":[{"type":"bot_command","offset":0,"length":15}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "a command addressed to @other_bot must not flag our bot");
    }

    @Test
    void bareBotCommandWithoutSuffix_doesNotFlagBotMentioned() throws Exception {
        // "/help" with no @suffix — in a group this is not a direct address.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"/help",
                  "entities":[{"type":"bot_command","offset":0,"length":5}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "a suffix-less /help is not a direct bot address");
    }

    // ── AC1: mention entity ─────────────────────────────────────────────

    @Test
    void mentionEntityMatchingBot_flagsBotMentioned() throws Exception {
        // "hey @jclaw_bot what's up" — mention entity at offset 4, length 10.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"hey @jclaw_bot what's up",
                  "entities":[{"type":"mention","offset":4,"length":10}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "@jclaw_bot mention must flag botMentioned");
    }

    @Test
    void mentionEntityForOtherUser_doesNotFlagBotMentioned() throws Exception {
        // "@someone_else hi" — mention of a different handle.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"@someone_else hi",
                  "entities":[{"type":"mention","offset":0,"length":13}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "a mention of a different user must not flag our bot");
    }

    @Test
    void textMentionEntityMatchingBotUserId_flagsBotMentioned() throws Exception {
        // text_mention carries the resolved User object (used for users with
        // no @-handle, but Telegram also uses it when resolving by id). The
        // embedded user id 555 == our bot id → addressed.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"JClaw please help",
                  "entities":[{"type":"text_mention","offset":0,"length":5,
                    "user":{"id":555,"is_bot":true,"first_name":"JClaw"}}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "text_mention resolving to the bot's user id must flag botMentioned");
    }

    // ── AC1: non-mention message ────────────────────────────────────────

    @Test
    void plainTextWithoutEntities_doesNotFlagBotMentioned() throws Exception {
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada","username":"ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"just chatting in the group"}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "a plain group message with no entities is not a bot address");
        assertEquals("Ada", msg.fromDisplayName(),
                "first-name-only display name when no last name");
    }

    // ── AC1: offset-correctness — no false positive inside a URL ────────

    @Test
    void handleInsideUrl_doesNotFalsePositive() throws Exception {
        // The literal "@jclaw_bot" appears inside a URL, but the only entity
        // is a `url` entity — there is NO mention entity. Substring search
        // would wrongly fire; offset-based scanning must not.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"see https://x.com/@jclaw_bot/posts for details",
                  "entities":[{"type":"url","offset":4,"length":27}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "@jclaw_bot inside a URL (url entity, not mention) must NOT false-positive");
    }

    @Test
    void slashCommandInsideCodeSpan_doesNotFalsePositive() throws Exception {
        // "/help@jclaw_bot" appears inside a `code` entity, not a bot_command
        // entity — Telegram does not emit bot_command for text inside code.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"run /help@jclaw_bot to see commands",
                  "entities":[{"type":"code","offset":4,"length":15}]}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "/help@jclaw_bot inside a code span must NOT flag botMentioned");
    }

    // ── AC3: reply-to-bot signal ────────────────────────────────────────

    @Test
    void replyToBotMessage_flagsBotMentioned() throws Exception {
        // No mention entity, but the message replies to one authored by the
        // bot (from.id 555). That is a direct address.
        var u = update("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"yes please",
                  "reply_to_message":{"message_id":1,
                    "from":{"id":555,"is_bot":true,"first_name":"JClaw"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "text":"want me to continue?"}}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "replying to the bot's own message must flag botMentioned");
    }

    @Test
    void replyToOtherUser_doesNotFlagBotMentioned() throws Exception {
        var u = update("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"agreed",
                  "reply_to_message":{"message_id":1,
                    "from":{"id":77,"is_bot":false,"first_name":"Bob"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "text":"I think we should ship"}}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "replying to a human (not the bot) must not flag botMentioned");
    }

    // ── Best-effort degradation when bot identity is unknown ────────────

    @Test
    void singleArgParse_degradesGracefullyWithoutBotIdentity() throws Exception {
        // Single-arg parse has no username/id: an @mention can't be resolved,
        // so botMentioned stays false, but a reply to *any* bot still fires
        // (best-effort fallback documented on the overload).
        var mention = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"hey @jclaw_bot",
                  "entities":[{"type":"mention","offset":4,"length":10}]}}
                """);
        var m1 = TelegramChannel.parseUpdate(mention);
        assertNotNull(m1);
        assertFalse(m1.botMentioned(),
                "without a known bot username, an @mention can't be resolved");

        var replyToBot = update("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"ok",
                  "reply_to_message":{"message_id":1,
                    "from":{"id":555,"is_bot":true,"first_name":"JClaw"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "text":"continue?"}}}
                """);
        var m2 = TelegramChannel.parseUpdate(replyToBot);
        assertNotNull(m2);
        assertTrue(m2.botMentioned(),
                "best-effort: a reply to any bot fires when our id is unknown");
    }

    // ── JCLAW-368: message_id + message_thread_id capture ────────────────

    @Test
    void capturesMessageId() throws Exception {
        var u = update("""
                {"update_id":1,"message":{"message_id":4242,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"hello"}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertEquals(Integer.valueOf(4242), msg.messageId(),
                "message_id must be captured verbatim from the Update");
    }

    @Test
    void capturesMessageThreadIdForForumTopicMessage() throws Exception {
        // A message posted in a forum topic: is_topic_message true and
        // message_thread_id set to the topic's root message id.
        var u = update("""
                {"update_id":1,"message":{"message_id":7,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "is_topic_message":true,"message_thread_id":99,
                  "text":"posting in a topic"}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertEquals(Integer.valueOf(7), msg.messageId());
        assertEquals(Integer.valueOf(99), msg.messageThreadId(),
                "message_thread_id must be captured for a forum-topic message");
    }

    @Test
    void messageThreadIdNullForNonTopicMessage() throws Exception {
        // Plain group message — no is_topic_message flag, no thread scope.
        var u = update("""
                {"update_id":1,"message":{"message_id":8,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"plain message, no topic"}}
                """);
        var msg = TelegramChannel.parseUpdate(u, BOT_USERNAME, BOT_USER_ID);
        assertNotNull(msg);
        assertEquals(Integer.valueOf(8), msg.messageId());
        assertNull(msg.messageThreadId(),
                "message_thread_id must be null for a plain non-topic message");
    }
}
