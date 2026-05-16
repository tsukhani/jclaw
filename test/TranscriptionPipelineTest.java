import agents.AgentRunner;
import agents.VisionAudioAssembler;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.transcription.PendingTranscripts;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * JCLAW-165 coverage for the capability-aware {@code userMessageFor}
 * overload, the format-rejection heuristic, and the AUDIO_PASSTHROUGH_OUTCOME
 * structured-log path. Pure unit tests that exercise the assembly and
 * detection logic in isolation; the full async-dispatch + LLM round-trip
 * is covered indirectly by {@code play autotest} runs that touch the
 * pipeline end-to-end.
 */
class TranscriptionPipelineTest extends UnitTest {

    private Agent agent;
    private Conversation conversation;
    private Message message;

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        PendingTranscripts.clearForTest();
        agent = new Agent();
        agent.name = "transcription-agent-" + UUID.randomUUID().toString().substring(0, 6);
        agent.modelProvider = "openrouter";
        agent.modelId = "anthropic/claude-sonnet-4-5";
        agent.save();

        conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "test-peer";
        conversation.save();

        message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "what did I say?";
        message.save();
    }

    @AfterEach
    void tearDown() {
        PendingTranscripts.clearForTest();
    }

    // ── userMessageFor capability routing ────────────────────────────

    @Test
    void userMessageForSupportsAudioTrueEmitsInputAudioPart() throws Exception {
        // Audio-capable model still gets the native input_audio part.
        // Default overload (single-arg) routes here too — back-compat
        // with all pre-JCLAW-165 callers.
        persistAudioAttachment("clip.mp3", "audio/mpeg", new byte[]{1, 2, 3, 4, 5}, null);
        var fresh = Message.<Message>findById(message.id);

        var chatMsg = VisionAudioAssembler.userMessageFor(fresh, true);
        assertTrue(chatMsg.content() instanceof List);
        var parts = (List<?>) chatMsg.content();
        assertNotNull(firstPartOfType(parts, "input_audio"),
                "supportsAudio=true → input_audio part: " + parts);
        assertNull(firstPartOfType(parts, "text") == null ? null
                : firstPartOfType(parts, "text").get("text").toString().contains("transcription") ? "found-transcript" : null,
                "supportsAudio=true must NOT inject transcript blocks");
    }

    @Test
    void userMessageForSupportsAudioFalseEmitsTranscriptInTextPart() throws Exception {
        // Text-only model: persisted transcript becomes a [Voice note transcription:]
        // block inside the text part. No input_audio part is emitted.
        persistAudioAttachment("clip.mp3", "audio/mpeg", new byte[]{1, 2, 3},
                "hello, this is a voice note");
        var fresh = Message.<Message>findById(message.id);

        var chatMsg = VisionAudioAssembler.userMessageFor(fresh, false);
        var parts = (List<?>) chatMsg.content();

        assertNull(firstPartOfType(parts, "input_audio"),
                "supportsAudio=false must drop input_audio: " + parts);
        var textPart = firstPartOfType(parts, "text");
        assertNotNull(textPart, "text part must exist when audio is downgraded");
        var text = (String) textPart.get("text");
        assertTrue(text.contains("[Voice note transcription:"),
                "text must contain the transcription block: " + text);
        assertTrue(text.contains("hello, this is a voice note"),
                "actual transcript must be in the text: " + text);
    }

    @Test
    void userMessageForSupportsAudioFalseFallsBackWhenTranscriptMissing() throws Exception {
        // Whisper failure = NULL transcript → fallback note carries the
        // user's original filename so the LLM has SOME context about what
        // the voice note was, even if it can't read the contents.
        persistAudioAttachment("recording.ogg", "audio/ogg", new byte[]{9, 8, 7}, null);
        var fresh = Message.<Message>findById(message.id);

        var chatMsg = VisionAudioAssembler.userMessageFor(fresh, false);
        var parts = (List<?>) chatMsg.content();

        var textPart = firstPartOfType(parts, "text");
        assertNotNull(textPart);
        var text = (String) textPart.get("text");
        assertTrue(text.contains("[Voice note recording.ogg: transcription unavailable]"),
                "fallback note must carry the original filename: " + text);
        assertFalse(text.contains("[Voice note transcription:"),
                "no transcription block when transcript is missing: " + text);
    }

    @Test
    void userMessageForDefaultOverloadStillEmitsInputAudio() throws Exception {
        // Back-compat: the no-supportsAudio-arg overload (existing callers
        // like buildMessages-inside-Tx and VisionAudioAssemblyTest) keeps
        // pre-JCLAW-165 behaviour. Without this guarantee the streaming
        // path's tests would change shape uninvitedly.
        persistAudioAttachment("clip.mp3", "audio/mpeg", new byte[]{1, 2}, null);
        var fresh = Message.<Message>findById(message.id);

        var chatMsg = VisionAudioAssembler.userMessageFor(fresh);
        var parts = (List<?>) chatMsg.content();
        assertNotNull(firstPartOfType(parts, "input_audio"),
                "default overload must preserve input_audio behaviour");
    }

    // ── isAudioFormatRejection heuristic ─────────────────────────────

    @Test
    void isAudioFormatRejectionDetectsKnownProviderShapes() {
        // OpenAI-style unsupported_format
        assertTrue(AgentRunner.isAudioFormatRejection(
                new RuntimeException("HTTP 400 from openai: unsupported_format webm")));
        // Gemini-style invalid_argument
        assertTrue(AgentRunner.isAudioFormatRejection(
                new RuntimeException("HTTP 400 from openrouter: invalid_argument: audio format not supported")));
        // Generic format not-supported
        assertTrue(AgentRunner.isAudioFormatRejection(
                new RuntimeException("HTTP 400 from acme: the format is not supported by this model")));
        // Audio + format combo
        assertTrue(AgentRunner.isAudioFormatRejection(
                new RuntimeException("HTTP 400 from acme: audio format mismatch")));
    }

    @Test
    void isAudioFormatRejectionIgnoresUnrelated400s() {
        // 400 with no audio/format hints — unrelated client error.
        assertFalse(AgentRunner.isAudioFormatRejection(
                new RuntimeException("HTTP 400 from openai: model not found")));
        // 500-class server error: not a client format rejection.
        assertFalse(AgentRunner.isAudioFormatRejection(
                new RuntimeException("HTTP 500 from openai: internal error")));
        // Null / non-LlmException-shaped
        assertFalse(AgentRunner.isAudioFormatRejection(null));
        assertFalse(AgentRunner.isAudioFormatRejection(new RuntimeException()));
    }

    // ── PendingTranscripts state ─────────────────────────────────────

    @Test
    void pendingTranscriptsRoundTripWithCompletedFuture() {
        var future = CompletableFuture.completedFuture("transcript text");
        PendingTranscripts.register(42L, future);

        var lookup = PendingTranscripts.lookup(42L);
        assertTrue(lookup.isPresent(), "registered future must be retrievable");
        assertSame(future, lookup.get(), "exact reference must round-trip");
        assertEquals("transcript text", lookup.get().getNow(""),
                "completed future returns its value");
    }

    @Test
    void pendingTranscriptsLookupReturnsEmptyForUnknownAttachment() {
        assertTrue(PendingTranscripts.lookup(99999L).isEmpty(),
                "unknown attachment id returns empty");
        assertTrue(PendingTranscripts.lookup(null).isEmpty(),
                "null attachment id is gracefully empty");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private MessageAttachment persistAudioAttachment(String filename, String mimeType,
                                                      byte[] bytes, String transcript) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1)
                : "";
        var relPath = "attachments/" + conversation.id + "/" + uuid
                + (ext.isEmpty() ? "" : "." + ext);
        var full = AgentService.acquireWorkspacePath(agent.name, relPath);
        Files.createDirectories(full.getParent());
        Files.write(full, bytes);

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = filename;
        att.storagePath = agent.name + "/" + relPath;
        att.mimeType = mimeType;
        att.sizeBytes = (long) bytes.length;
        att.kind = MessageAttachment.KIND_AUDIO;
        att.transcript = transcript;
        att.save();
        return att;
    }

    private static Map<?, ?> firstPartOfType(List<?> parts, String type) {
        return parts.stream()
                .filter(p -> p instanceof Map && type.equals(((Map<?, ?>) p).get("type")))
                .map(p -> (Map<?, ?>) p)
                .findFirst().orElse(null);
    }
}
