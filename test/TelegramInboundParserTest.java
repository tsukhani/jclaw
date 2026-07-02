import channels.InboundMessage;
import channels.TelegramInboundParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.test.UnitTest;

import java.util.stream.Stream;

/**
 * Branch-edge coverage for {@link TelegramInboundParser} beyond the happy
 * paths already exercised by {@code TelegramEntityParseTest} / {@code ChannelTest}:
 * null/absent-field update shapes, sender-identity fallbacks, caption vs text,
 * every media-attachment collector, sticker/venue note degradations, reply
 * context fallbacks, wake-word gating, and callback-query parsing.
 *
 * <p>Fixtures are built by deserializing raw Bot API JSON (the exact shape
 * Telegram POSTs) so optional-field absence behaves exactly as in production.
 * The bot under test is {@code @jclaw_bot} with numeric user id {@code 555}.
 */
class TelegramInboundParserTest extends UnitTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();
    private static final String BOT_USERNAME = "jclaw_bot";
    private static final Long BOT_USER_ID = 555L;

    private static final String WAKE_CFG_KEY = "telegram.mentionPatterns";

    private String previousWakePatterns;

    @BeforeEach
    void snapshotWakeWordConfig() {
        previousWakePatterns = play.Play.configuration.getProperty(WAKE_CFG_KEY);
        // Deterministic baseline: wake-words off unless a test opts in.
        play.Play.configuration.remove(WAKE_CFG_KEY);
    }

    @AfterEach
    void restoreWakeWordConfig() {
        if (previousWakePatterns == null) {
            play.Play.configuration.remove(WAKE_CFG_KEY);
        } else {
            play.Play.configuration.setProperty(WAKE_CFG_KEY, previousWakePatterns);
        }
    }

    private static Update update(String json) throws Exception {
        return JACKSON.readValue(json, Update.class);
    }

    /** Identity-aware parse against the test bot (@jclaw_bot / 555). */
    private static InboundMessage parse(String json) throws Exception {
        return TelegramInboundParser.parseUpdate(update(json), BOT_USERNAME, BOT_USER_ID);
    }

    // ── Null / drop gates ────────────────────────────────────────────────

    @Test
    void nullUpdateAndMessagelessUpdateReturnNull() {
        assertNull(TelegramInboundParser.parseUpdate((Update) null, BOT_USERNAME, BOT_USER_ID),
                "a null update must parse to null, not throw");
        assertNull(TelegramInboundParser.parseUpdate(new Update(), BOT_USERNAME, BOT_USER_ID),
                "an update carrying no message must parse to null");
    }

    @Test
    void editedMessageUpdateReturnsNull() throws Exception {
        // edited_message is a different Update field — getMessage() is null,
        // so the parser must bail rather than mis-handle the edit as new.
        var u = update("""
                {"update_id":1,"edited_message":{"message_id":1,
                  "chat":{"id":9,"type":"private"},"date":2,"edit_date":3,
                  "text":"edited body"}}
                """);
        assertNull(TelegramInboundParser.parseUpdate(u, BOT_USERNAME, BOT_USER_ID),
                "an edited_message update has no .message and must parse to null");
    }

    @Test
    void unrecognizedContentOnlyMessageIsDropped() throws Exception {
        // A contact share carries no text, no caption, no downloadable
        // attachment, and no sticker/location/venue note → nothing actionable.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "contact":{"phone_number":"+15550100","first_name":"Bob"}}}
                """);
        assertNull(msg, "a contact-only message has nothing to act on and must be dropped");
    }

    @Test
    void photoWithoutCaptionKeepsTurnAliveWithEmptyText() throws Exception {
        // No caption and no text, but an attachment is present → the turn
        // survives the empty-drop gate with text "". The single PhotoSize
        // also has no file_size → the parser defaults the byte count to 0.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "photo":[{"file_id":"P1","file_unique_id":"up1","width":90,"height":90}]}}
                """);
        assertNotNull(msg, "an attachment-only message must NOT be dropped");
        assertEquals("", msg.text(), "no caption and no text → empty text, not null");
        assertEquals(1, msg.attachments().size());
        assertEquals("P1", msg.attachments().get(0).telegramFileId());
        assertEquals(0L, msg.attachments().get(0).sizeBytes(),
                "absent file_size must default to 0 bytes");
    }

    // ── Sender identity fallbacks ────────────────────────────────────────

    @Test
    void missingFromYieldsNullSenderFields() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"sender-less message"}}
                """);
        assertNotNull(msg);
        assertEquals("-100", msg.chatId());
        assertEquals("supergroup", msg.chatType());
        assertNull(msg.fromId(), "no from → fromId null");
        assertNull(msg.fromUsername(), "no from → fromUsername null");
        assertNull(msg.fromDisplayName(), "no from → fromDisplayName null");
    }

    @Test
    void blankFirstNameFallsBackToLastNameWithoutLeadingSpace() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"  ","last_name":"Lovelace"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "text":"hi"}}
                """);
        assertNotNull(msg);
        assertEquals("Lovelace", msg.fromDisplayName(),
                "blank first name is skipped — last name alone, no leading space");
    }

    @Test
    void blankNamesFallBackToUsernameHandle() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":" ","username":"ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "text":"hi"}}
                """);
        assertNotNull(msg);
        assertEquals("ada", msg.fromDisplayName(),
                "blank names → display name falls back to the @-handle");
        assertEquals("ada", msg.fromUsername());
    }

    @Test
    void blankNamesAndNoUsernameYieldNullDisplayName() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":" "},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "text":"hi"}}
                """);
        assertNotNull(msg);
        assertNull(msg.fromDisplayName(),
                "blank name and no handle → display name is null, not blank");
        assertEquals("42", msg.fromId(), "fromId is still captured");
    }

    // ── Caption vs text + context-note folding ──────────────────────────

    @Test
    void captionBecomesTextAndBestPhotoSizeWins() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "caption":"holiday pics","media_group_id":"mg-7",
                  "photo":[
                    {"file_id":"P_small","file_unique_id":"ps","width":90,"height":90,"file_size":100},
                    {"file_id":"P_big","file_unique_id":"pb","width":800,"height":800,"file_size":54321}]}}
                """);
        assertNotNull(msg);
        assertEquals("holiday pics", msg.text(), "caption populates text for media messages");
        assertEquals("mg-7", msg.mediaGroupId(), "media_group_id is carried through");
        assertEquals(1, msg.attachments().size(), "only ONE photo attachment per photo message");
        var a = msg.attachments().get(0);
        assertEquals("P_big", a.telegramFileId(),
                "the LAST PhotoSize (highest resolution) must be picked");
        assertEquals(MessageAttachment.KIND_IMAGE, a.kind());
        assertEquals("image/jpeg", a.mimeType());
        assertEquals(54321L, a.sizeBytes());
        assertNull(a.suggestedFilename(), "photos carry no filename");
    }

    @Test
    void textAndContextNoteAreJoinedWithNewline() throws Exception {
        // The parser documents graceful degradation for combined shapes:
        // when a message carries BOTH prose and a note-producing payload,
        // the note is appended on a new line rather than replacing the text.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "text":"meet here",
                  "location":{"latitude":1.5,"longitude":2.5}}}
                """);
        assertNotNull(msg);
        assertEquals("meet here\n[location: 1.5, 2.5]", msg.text(),
                "prose + context note must combine as text \\n note");
    }

    // ── botMentioned: entity edge branches ───────────────────────────────

    @Test
    void captionEntityMentionFlagsBot() throws Exception {
        // The @mention lives in the CAPTION entity list (photo message), not text.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "photo":[{"file_id":"P1","file_unique_id":"up1","width":90,"height":90}],
                  "caption":"@jclaw_bot look",
                  "caption_entities":[{"type":"mention","offset":0,"length":10}]}}
                """);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "a mention entity in caption_entities must flag botMentioned");
        assertEquals("@jclaw_bot look", msg.text());
    }

    @Test
    void textMentionWithMissingOrForeignUserDoesNotFlag() throws Exception {
        // Two defective text_mention entities: one with no embedded user at
        // all, one resolving to a different user id — neither addresses us.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"Alice and Bob please help",
                  "entities":[
                    {"type":"text_mention","offset":0,"length":5},
                    {"type":"text_mention","offset":10,"length":3,
                      "user":{"id":777,"is_bot":false,"first_name":"Bob"}}]}}
                """);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "text_mention without a user, or with a foreign user id, must not flag our bot");
    }

    @Test
    void textMentionWithoutKnownBotIdDoesNotFlag() throws Exception {
        // Single-arg parse: botUserId unknown → a text_mention can never be
        // resolved to us, even when the embedded id happens to be the bot's.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"JClaw please help",
                  "entities":[{"type":"text_mention","offset":0,"length":5,
                    "user":{"id":555,"is_bot":true,"first_name":"JClaw"}}]}}
                """);
        var msg = TelegramInboundParser.parseUpdate(u);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "without a known bot user id, text_mention must stay dormant");
    }

    @Test
    void mentionMatchesCaseInsensitively() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"@JCLAW_Bot ping",
                  "entities":[{"type":"mention","offset":0,"length":10}]}}
                """);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "Telegram handles are case-insensitive — @JCLAW_Bot must match jclaw_bot");
    }

    @Test
    void mentionSliceWithoutLeadingAtStillMatches() throws Exception {
        // Defensive tolerance: a mention entity whose slice lacks the leading
        // @ (malformed offsets from a buggy client) still compares the bare
        // handle — stripLeadingAt is strip-if-present, not require-@.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"jclaw_bot ping",
                  "entities":[{"type":"mention","offset":0,"length":9}]}}
                """);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "a mention slice without the leading @ still matches the bare handle");
    }

    @Test
    void malformedEntityOffsetsBeyondBodyAreClampedSafely() throws Exception {
        // Offset past the end of the body → the SDK's own getEntities() throws
        // from its eager computeText() substring; the parser's safeEntities
        // wrapper treats the malformed payload as having no entities, so the
        // parse completes without an exception and without a false positive.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"short",
                  "entities":[{"type":"mention","offset":100,"length":10}]}}
                """);
        assertNotNull(msg, "a malformed entity offset must not abort the parse");
        assertFalse(msg.botMentioned(),
                "an out-of-bounds entity slice is empty and must not match the bot");
        assertEquals("short", msg.text());
    }

    @Test
    void commandSuffixWithUnknownBotUsernameDoesNotFlag() throws Exception {
        // Single-arg parse: botUsername unknown → /cmd@jclaw_bot can't be
        // verified as ours and must NOT fire.
        var u = update("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"/help@jclaw_bot",
                  "entities":[{"type":"bot_command","offset":0,"length":15}]}}
                """);
        var msg = TelegramInboundParser.parseUpdate(u);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "a command suffix can't be matched when the bot username is unknown");
    }

    @Test
    void replyToMessageWithoutAuthorDoesNotFlagButKeepsReplyContext() throws Exception {
        // The replied-to message has no from (e.g. a channel-forwarded shape):
        // reply-to-bot detection must stay false, but the reply text still
        // rides along as context.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"interesting",
                  "reply_to_message":{"message_id":1,
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "text":"authorless announcement"}}}
                """);
        assertNotNull(msg);
        assertFalse(msg.botMentioned(),
                "a reply target without an author can't be a reply to the bot");
        assertEquals("in reply to: authorless announcement", msg.replyContext());
    }

    // ── botMentioned: wake-word gating (JCLAW-387 B3) ─────────────────────

    @Test
    void wakeWordInTextFlagsBot_skippingEmptyAndInvalidPatterns() throws Exception {
        // Config mixes a blank token, a valid pattern, and an invalid regex —
        // the blank is skipped, the invalid is dropped at compile time, and
        // the valid pattern still matches.
        play.Play.configuration.setProperty(WAKE_CFG_KEY, "  ,(?i)\\bjarvis\\b,[oops");
        var hit = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"ok Jarvis, run the report"}}
                """);
        assertNotNull(hit);
        assertTrue(hit.botMentioned(),
                "a configured wake-word match in text must flag botMentioned");

        var miss = parse("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "text":"talking about tuesday plans"}}
                """);
        assertNotNull(miss);
        assertFalse(miss.botMentioned(),
                "a non-matching body must not flag even with wake-words configured");
    }

    @Test
    void wakeWordInCaptionFlagsBot() throws Exception {
        play.Play.configuration.setProperty(WAKE_CFG_KEY, "(?i)\\bjarvis\\b");
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":1,
                  "photo":[{"file_id":"P1","file_unique_id":"up1","width":90,"height":90}],
                  "caption":"jarvis look at this"}}
                """);
        assertNotNull(msg);
        assertTrue(msg.botMentioned(),
                "the wake-word gate must also scan the CAPTION of a media message");
    }

    // ── Attachment collectors ─────────────────────────────────────────────

    @Test
    void emptyPhotoArrayStagesNothingAndMessageIsDropped() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "photo":[]}}
                """);
        assertNull(msg,
                "an empty photo array stages no attachment; with no text the turn is dropped");
    }

    @Test
    void voiceNoteMapsToAudioWithZeroBytesWhenSizeAbsent() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "voice":{"file_id":"V1","file_unique_id":"vu1","duration":3,
                    "mime_type":"audio/ogg"}}}
                """);
        assertNotNull(msg);
        assertEquals(1, msg.attachments().size());
        var a = msg.attachments().get(0);
        assertEquals("V1", a.telegramFileId());
        assertEquals(MessageAttachment.KIND_AUDIO, a.kind(), "voice → KIND_AUDIO");
        assertEquals("audio/ogg", a.mimeType());
        assertNull(a.suggestedFilename(), "voice notes carry no filename");
        assertEquals(0L, a.sizeBytes(), "absent file_size defaults to 0");
    }

    @Test
    void audioFileMapsToAudioWithFilename() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "audio":{"file_id":"A1","file_unique_id":"au1","duration":180,
                    "file_name":"song.mp3","mime_type":"audio/mpeg","file_size":777}}}
                """);
        assertNotNull(msg);
        assertEquals(1, msg.attachments().size());
        var a = msg.attachments().get(0);
        assertEquals("A1", a.telegramFileId());
        assertEquals(MessageAttachment.KIND_AUDIO, a.kind(), "audio file → KIND_AUDIO");
        assertEquals("song.mp3", a.suggestedFilename());
        assertEquals("audio/mpeg", a.mimeType());
        assertEquals(777L, a.sizeBytes());
    }

    @Test
    void pdfDocumentMapsToFile() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "document":{"file_id":"D1","file_unique_id":"du1",
                    "file_name":"report.pdf","mime_type":"application/pdf","file_size":1234}}}
                """);
        assertNotNull(msg);
        var a = msg.attachments().get(0);
        assertEquals(MessageAttachment.KIND_FILE, a.kind(), "non-image document → KIND_FILE");
        assertEquals("report.pdf", a.suggestedFilename());
        assertEquals("application/pdf", a.mimeType());
        assertEquals(1234L, a.sizeBytes());
    }

    @Test
    void imageDocumentIsClassifiedAsImage() throws Exception {
        // Uploading a picture via "File" (uncompressed) arrives as a document
        // with an image/* MIME — it must classify as IMAGE, not FILE, so the
        // vision path receives an image part.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "document":{"file_id":"D2","file_unique_id":"du2",
                    "file_name":"scan.png","mime_type":"image/png","file_size":2048}}}
                """);
        assertNotNull(msg);
        assertEquals(MessageAttachment.KIND_IMAGE, msg.attachments().get(0).kind(),
                "an image/* document must stay KIND_IMAGE");
    }

    @Test
    void documentWithoutMimeOrSizeMapsToFileWithZeroBytes() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "document":{"file_id":"D3","file_unique_id":"du3"}}}
                """);
        assertNotNull(msg);
        var a = msg.attachments().get(0);
        assertEquals(MessageAttachment.KIND_FILE, a.kind(),
                "a null MIME can't match image/* → KIND_FILE");
        assertNull(a.mimeType());
        assertNull(a.suggestedFilename());
        assertEquals(0L, a.sizeBytes());
    }

    @Test
    void videoMapsToFile() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "video":{"file_id":"VID1","file_unique_id":"vd1","width":640,"height":480,
                    "duration":12,"file_name":"clip.mp4","mime_type":"video/mp4","file_size":999}}}
                """);
        assertNotNull(msg);
        var a = msg.attachments().get(0);
        assertEquals(MessageAttachment.KIND_FILE, a.kind(), "video → KIND_FILE");
        assertEquals("VID1", a.telegramFileId());
        assertEquals("clip.mp4", a.suggestedFilename());
        assertEquals("video/mp4", a.mimeType());
        assertEquals(999L, a.sizeBytes());
    }

    @Test
    void videoNoteMapsToFileWithNullNameAndMime() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "video_note":{"file_id":"VN1","file_unique_id":"vn1","length":240,"duration":10}}}
                """);
        assertNotNull(msg);
        var a = msg.attachments().get(0);
        assertEquals(MessageAttachment.KIND_FILE, a.kind(), "video note → KIND_FILE");
        assertEquals("VN1", a.telegramFileId());
        assertNull(a.suggestedFilename(), "video notes have no filename");
        assertNull(a.mimeType(), "video notes report no MIME");
        assertEquals(0L, a.sizeBytes());
    }

    // ── Sticker note degradations ────────────────────────────────────────

    @Test
    void stickerWithSetNameButNoEmojiYieldsSetOnlyNoteAndZeroByteAttachment() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "sticker":{"file_id":"STK9","file_unique_id":"u9","type":"regular",
                    "width":512,"height":512,"is_animated":false,"is_video":false,
                    "set_name":"AnimalsPack"}}}
                """);
        assertNotNull(msg);
        assertEquals("[sticker: (set AnimalsPack)]", msg.text(),
                "no emoji → the set name rides directly after the colon");
        assertEquals(1, msg.attachments().size(), "a static sticker still stages its WEBP");
        var a = msg.attachments().get(0);
        assertEquals("STK9", a.telegramFileId());
        assertEquals("image/webp", a.mimeType());
        assertEquals(MessageAttachment.KIND_IMAGE, a.kind());
        assertEquals(0L, a.sizeBytes(), "absent sticker file_size defaults to 0");
    }

    @Test
    void stickerWithNeitherEmojiNorSetDegradesToBareNote() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "sticker":{"file_id":"STK10","file_unique_id":"u10","type":"regular",
                    "width":512,"height":512,"is_animated":true,"is_video":false}}}
                """);
        assertNotNull(msg);
        assertEquals("[sticker]", msg.text(),
                "no emoji and no set name → a bare [sticker] placeholder");
        assertTrue(msg.attachments().isEmpty(),
                "an animated sticker stages no downloadable attachment");
    }

    // ── Venue note degradations ──────────────────────────────────────────

    @Test
    void venueWithoutAddressOmitsTheAddressSegment() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "venue":{"title":"Ferry Building",
                    "location":{"latitude":37.7955,"longitude":-122.3937}}}}
                """);
        assertNotNull(msg);
        assertEquals("[venue: Ferry Building (37.7955, -122.3937)]", msg.text(),
                "no address → title and coordinates only, no dash segment");
    }

    @Test
    void venueWithoutTitleOrLocationKeepsAddressOnly() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "venue":{"address":"1 Ferry Building, SF"}}}
                """);
        assertNotNull(msg);
        assertEquals("[venue — 1 Ferry Building, SF]", msg.text(),
                "missing title and location degrade to the address segment alone");
    }

    @Test
    void venueWithSiblingLocationFieldProducesOnlyTheVenueNote() throws Exception {
        // Real venue messages carry BOTH venue and location fields — the
        // richer venue note must win and the bare location note be skipped.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":1,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":42,"type":"private"},"date":1,
                  "location":{"latitude":37.7955,"longitude":-122.3937},
                  "venue":{"title":"Ferry Building","address":"1 Ferry Building, SF",
                    "location":{"latitude":37.7955,"longitude":-122.3937}}}}
                """);
        assertNotNull(msg);
        assertTrue(msg.text().startsWith("[venue:"),
                "the venue note must be produced, got: " + msg.text());
        assertFalse(msg.text().contains("[location:"),
                "the sibling location must NOT add a second note when a venue is present");
    }

    // ── Reply-context fallbacks ──────────────────────────────────────────

    @Test
    void blankQuoteFallsBackToRepliedToText() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"agreed",
                  "quote":{"text":"   ","position":0},
                  "reply_to_message":{"message_id":1,
                    "from":{"id":77,"is_bot":false,"first_name":"Bob"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "text":"the plan"}}}
                """);
        assertNotNull(msg);
        assertEquals("in reply to: the plan", msg.replyContext(),
                "a blank quote must fall back to the full replied-to text");
    }

    @Test
    void replyToPhotoWithCaptionUsesTheCaption() throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"beautiful",
                  "reply_to_message":{"message_id":1,
                    "from":{"id":77,"is_bot":false,"first_name":"Bob"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "photo":[{"file_id":"P1","file_unique_id":"up1","width":90,"height":90}],
                    "caption":"sunset pic"}}}
                """);
        assertNotNull(msg);
        assertEquals("in reply to: sunset pic", msg.replyContext(),
                "a replied-to media message with a caption must surface the caption, not [photo]");
    }

    @Test
    void replyToUnrecognizedShapeYieldsNullReplyContext() throws Exception {
        // The replied-to message is a dice roll — no text, no caption, and not
        // one of the recognized media shapes → replyContext must be null.
        var msg = parse("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"lucky roll",
                  "reply_to_message":{"message_id":1,
                    "from":{"id":77,"is_bot":false,"first_name":"Bob"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                    "dice":{"emoji":"🎲","value":3}}}}
                """);
        assertNotNull(msg);
        assertNull(msg.replyContext(),
                "an unrecognized replied-to shape carries no usable context");
        assertEquals("lucky roll", msg.text(), "the outer message itself is unaffected");
    }

    static Stream<Arguments> replyToMediaTypeCases() {
        return Stream.of(
            Arguments.of("\"sticker\":{\"file_id\":\"S1\",\"file_unique_id\":\"su1\","
                    + "\"type\":\"regular\",\"width\":1,\"height\":1,"
                    + "\"is_animated\":true,\"is_video\":false}", "sticker"),
            Arguments.of("\"voice\":{\"file_id\":\"V1\",\"file_unique_id\":\"vu1\",\"duration\":2}",
                    "voice"),
            Arguments.of("\"audio\":{\"file_id\":\"A1\",\"file_unique_id\":\"au1\",\"duration\":2}",
                    "audio"),
            Arguments.of("\"video\":{\"file_id\":\"VD1\",\"file_unique_id\":\"vd1\","
                    + "\"width\":1,\"height\":1,\"duration\":2}", "video"),
            Arguments.of("\"video_note\":{\"file_id\":\"VN1\",\"file_unique_id\":\"vn1\","
                    + "\"length\":240,\"duration\":2}", "video note"),
            Arguments.of("\"document\":{\"file_id\":\"D1\",\"file_unique_id\":\"du1\"}",
                    "document"),
            Arguments.of("\"venue\":{\"title\":\"T\",\"address\":\"A\","
                    + "\"location\":{\"latitude\":1.0,\"longitude\":2.0}}", "venue"),
            Arguments.of("\"location\":{\"latitude\":1.0,\"longitude\":2.0}", "location")
        );
    }

    @ParameterizedTest(name = "[{index}] reply to {1}")
    @MethodSource("replyToMediaTypeCases")
    void replyToCaptionlessMediaNotesItsType(String mediaFragment, String expectedLabel)
            throws Exception {
        var msg = parse("""
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"look above",
                  "reply_to_message":{"message_id":1,
                    "from":{"id":77,"is_bot":false,"first_name":"Bob"},
                    "chat":{"id":-100,"type":"supergroup"},"date":1,
                """ + mediaFragment + "}}}");
        assertNotNull(msg);
        assertEquals("in reply to: [" + expectedLabel + "]", msg.replyContext(),
                "a caption-less replied-to " + expectedLabel + " must be noted by type");
    }

    // ── parseCallback ─────────────────────────────────────────────────────

    @Test
    void callbackParseReturnsNullForNullOrNonCallbackUpdates() {
        assertNull(TelegramInboundParser.parseCallback((Update) null),
                "null update → null callback");
        assertNull(TelegramInboundParser.parseCallback(new Update()),
                "an update without callback_query → null callback");
    }

    @Test
    void callbackWithoutDataOrWithBlankDataReturnsNull() throws Exception {
        var noData = update("""
                {"update_id":1,"callback_query":{"id":"cb1","chat_instance":"ci",
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"}}}
                """);
        assertNull(TelegramInboundParser.parseCallback(noData),
                "a callback with no data field is unusable → null");

        var blankData = update("""
                {"update_id":1,"callback_query":{"id":"cb2","chat_instance":"ci",
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "data":"   "}}
                """);
        assertNull(TelegramInboundParser.parseCallback(blankData),
                "a callback with blank data is unusable → null");
    }

    @Test
    void callbackWithoutFromReturnsNull() throws Exception {
        // No tapper identity → authorization is impossible → drop.
        var u = update("""
                {"update_id":1,"callback_query":{"id":"cb3","chat_instance":"ci",
                  "data":"model:pick:3"}}
                """);
        assertNull(TelegramInboundParser.parseCallback(u),
                "a callback without a from user must be dropped");
    }

    @Test
    void callbackWithAccessibleOriginCapturesChatAndMessageId() throws Exception {
        var u = update("""
                {"update_id":1,"callback_query":{"id":"cb4","chat_instance":"ci",
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "data":"model:pick:3",
                  "message":{"message_id":77,
                    "chat":{"id":-100,"type":"supergroup"},"date":5,
                    "text":"pick a model"}}}
                """);
        var cb = TelegramInboundParser.parseCallback(u);
        assertNotNull(cb);
        assertEquals("cb4", cb.callbackId());
        assertEquals("42", cb.fromId());
        assertEquals("-100", cb.chatId(), "chat id comes from the origin message");
        assertEquals("supergroup", cb.chatType());
        assertEquals(Integer.valueOf(77), cb.messageId());
        assertEquals("model:pick:3", cb.data());
    }

    @Test
    void callbackWithInaccessibleOriginKeepsMessageIdButNoChat() throws Exception {
        // date: 0 is Telegram's marker for an InaccessibleMessage — the SDK
        // deserializes a non-Message subtype, so the chat fields can't be
        // read but the message id still is.
        var u = update("""
                {"update_id":1,"callback_query":{"id":"cb5","chat_instance":"ci",
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "data":"noop",
                  "message":{"message_id":88,
                    "chat":{"id":-100,"type":"supergroup"},"date":0}}}
                """);
        var cb = TelegramInboundParser.parseCallback(u);
        assertNotNull(cb);
        assertEquals(Integer.valueOf(88), cb.messageId(),
                "the message id survives even for an inaccessible origin");
        assertNull(cb.chatId(), "an inaccessible origin exposes no chat id");
        assertNull(cb.chatType(), "an inaccessible origin exposes no chat type");
        assertEquals("42", cb.fromId());
    }

    @Test
    void callbackWithoutOriginMessageYieldsNullChatAndMessageId() throws Exception {
        var u = update("""
                {"update_id":1,"callback_query":{"id":"cb6","chat_instance":"ci",
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "data":"noop"}}
                """);
        var cb = TelegramInboundParser.parseCallback(u);
        assertNotNull(cb, "a callback without an origin message is still actionable");
        assertNull(cb.chatId());
        assertNull(cb.chatType());
        assertNull(cb.messageId());
        assertEquals("noop", cb.data());
    }

    // ── prepareInboundAttachments: attachment-free short-circuit ─────────

    @Test
    void prepareInboundAttachmentsWithNoAttachmentsReturnsEmptyListWithoutDownloading() {
        // Empty attachments must short-circuit to an empty list BEFORE any
        // network/agent access — the token, chat id, and agent are never
        // dereferenced on this path (a bogus token proves no download fires).
        var agent = new models.Agent();
        agent.name = "parser-test-agent";
        var textOnly = new InboundMessage("123", "private", "just text", "42", "ada");

        var inputs = TelegramInboundParser.prepareInboundAttachments(
                "0:bogus-token-never-used", "123", agent, textOnly);

        assertNotNull(inputs, "no attachments is NOT a rejection — must not return null");
        assertTrue(inputs.isEmpty(),
                "a text-only message yields an empty input list (continue with text only)");
    }
}
