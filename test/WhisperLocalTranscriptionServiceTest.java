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
import services.transcription.FfmpegProbe;
import services.transcription.TranscriptionException;
import services.transcription.WhisperTranscriber;
import services.transcription.WhisperLocalTranscriptionService;

import java.util.UUID;

/**
 * JCLAW-315: cover the local-transcription adapter that bridges
 * {@link services.transcription.TranscriptionService} to the static
 * {@link WhisperTranscriber} engine.
 *
 * <p>The happy path (whisper.cpp inference) needs the native lib and a
 * downloaded model; that's already covered by the integration test in
 * {@code WhisperTranscriberTest}. Here we only verify the adapter
 * layer: null guard, attachment-path resolution wiring, and the
 * fail-fast envelope when ffmpeg is missing.
 */
class WhisperLocalTranscriptionServiceTest extends UnitTest {

    private Agent agent;
    private Conversation conversation;
    private Message message;

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "whisper-local-test-" + UUID.randomUUID().toString().substring(0, 6);
        agent.modelProvider = "openrouter";
        agent.modelId = "anthropic/claude-sonnet-4-5";
        agent.save();

        conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "tester";
        conversation.save();

        message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "voice note";
        message.save();
    }

    @AfterEach
    void tearDown() {
        FfmpegProbe.setForTest(null);
    }

    @Test
    void transcribeThrowsOnNullAttachment() {
        var svc = new WhisperLocalTranscriptionService();
        var ex = assertThrows(TranscriptionException.class, () -> svc.transcribe(null));
        assertTrue(ex.getMessage().toLowerCase().contains("null"),
                "error must explicitly mention null: " + ex.getMessage());
    }

    @Test
    void transcribeSurfacesMissingUvAsTranscriptionException() {
        // JCLAW-650: ffmpeg is no longer an ASR precondition (the sidecar
        // reads the audio file directly; ffmpeg matters only for the
        // diarization pipeline's PCM decode). The plain path's single
        // prerequisite is uv — force it off and pin the actionable error.
        services.UvProbe.setForTest(new services.UvProbe.ProbeResult(false, "forced-for-test"));
        try {
            var att = persistAudio();
            var svc = new WhisperLocalTranscriptionService();
            var ex = assertThrows(TranscriptionException.class, () -> svc.transcribe(att));
            assertTrue(ex.getMessage().contains("uv"),
                    "the error must name the missing prerequisite: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("setup"),
                    "the error must point at the fix: " + ex.getMessage());
        } finally {
            services.UvProbe.setForTest(null);
        }
    }

    /**
     * Persist a minimal audio attachment row and write a placeholder byte
     * on disk so the storage-path resolution side of the service finds
     * something. The contents don't matter — ffmpeg-missing is asserted
     * before any read happens.
     */
    private MessageAttachment persistAudio() {
        var uuid = UUID.randomUUID().toString();
        var relPath = "attachments/" + conversation.id + "/" + uuid + ".ogg";
        var full = AgentService.acquireWorkspacePath(agent.name, relPath);
        try {
            java.nio.file.Files.createDirectories(full.getParent());
            java.nio.file.Files.write(full, new byte[]{1, 2, 3});
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = "voice.ogg";
        att.storagePath = agent.name + "/" + relPath;
        att.mimeType = "audio/ogg";
        att.sizeBytes = 3L;
        att.kind = MessageAttachment.KIND_AUDIO;
        att.save();
        return att;
    }
}
