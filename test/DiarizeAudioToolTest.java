import agents.ToolContext;
import agents.ToolRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.transcription.SpeakerNamer;
import tools.DiarizeAudioTool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-559: {@code diarize_audio} tool — registration, argument validation,
 * and attachment resolution (explicit uuid, newest-in-conversation default,
 * no-scope and no-audio errors). The diarization pipeline itself is covered
 * by the JCLAW-556/558 suites; here everything below the pipeline boundary
 * is exercised without models by asserting on the error strings that come
 * back before any native work would start.
 */
class DiarizeAudioToolTest extends UnitTest {

    private final DiarizeAudioTool tool = new DiarizeAudioTool();
    private Agent agent;
    private Conversation conv;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        agent = AgentService.create("diarize-tool-test", "openrouter", "gpt-4.1");
        conv = ConversationService.create(agent, "web", "u-559");
    }

    @Test
    void toolIsRegistered() {
        new jobs.ToolRegistrationJob().doJob();
        var registered = ToolRegistry.lookupTool(DiarizeAudioTool.TOOL_NAME);
        assertNotNull(registered, "diarize_audio must be registered by ToolRegistrationJob");
        assertFalse(registered.parallelSafe(), "minutes-long native inference must not run in parallel");
        assertEquals(java.util.List.of("diarize", "enroll_speaker", "extract_speaker_clips"),
                registered.actions().stream().map(a -> a.name()).toList(),
                "the Tools UI 'Functions' disclosure must list all three actions");
    }

    @Test
    void invalidArgsJson_returnsError() {
        assertTrue(tool.execute("not json", agent).startsWith("Error: invalid arguments"));
    }

    @Test
    void noConversationScope_returnsHelpfulError() {
        var out = tool.execute("{}", agent);
        assertTrue(out.contains("no conversation in scope"), out);
    }

    @Test
    void explicitUuid_withoutScope_alsoRefuses() {
        // The uuid path must not bypass the scope requirement (IDOR guard).
        var att = seedAttachment(MessageAttachment.KIND_AUDIO, "aud-noscope", "a.ogg");
        var out = tool.execute("{\"attachment_uuid\":\"" + att.uuid + "\"}", agent);
        assertTrue(out.contains("no conversation in scope"), out);
    }

    @Test
    void explicitUuid_fromAnotherConversation_isNotReachable() {
        // Attachment lives in this.conv; the tool runs scoped to a different
        // conversation. A model-controlled uuid must not cross that boundary.
        var att = seedAttachment(MessageAttachment.KIND_AUDIO, "aud-other", "other.ogg");
        var otherConv = ConversationService.create(agent, "web", "u-559-b");
        var out = ToolContext.withConversation(otherConv.id,
                () -> tool.execute("{\"attachment_uuid\":\"" + att.uuid + "\"}", agent));
        assertTrue(out.contains("no attachment with uuid aud-other in this conversation")
                        || out.contains("exists in this conversation"),
                "cross-conversation uuid must resolve to nothing: " + out);
    }

    @Test
    void scopedConversationWithoutAudio_returnsHelpfulError() {
        var out = ToolContext.withConversation(conv.id, () -> tool.execute("{}", agent));
        assertTrue(out.contains("no audio attachments"), out);
    }

    @Test
    void unknownUuid_returnsError() {
        var out = ToolContext.withConversation(conv.id,
                () -> tool.execute("{\"attachment_uuid\":\"nope-123\"}", agent));
        assertTrue(out.contains("no attachment with uuid nope-123"), out);
    }

    @Test
    void nonAudioUuid_returnsError() {
        var att = seedAttachment(MessageAttachment.KIND_IMAGE, "img-559", "pic.png");
        var out = ToolContext.withConversation(conv.id,
                () -> tool.execute("{\"attachment_uuid\":\"" + att.uuid + "\"}", agent));
        assertTrue(out.contains("is IMAGE, not audio"), out);
    }

    @Test
    void audioUuidWithMissingFile_reportsMissingStorage() {
        // The row exists but no bytes were ever written to its storagePath —
        // resolution succeeds, the file check fails, and no native work runs.
        var att = seedAttachment(MessageAttachment.KIND_AUDIO, "aud-559", "meeting.ogg");
        var out = ToolContext.withConversation(conv.id,
                () -> tool.execute("{\"attachment_uuid\":\"" + att.uuid + "\"}", agent));
        assertTrue(out.contains("missing from storage"), out);
    }

    @Test
    void defaultResolution_picksNewestAudioInConversation() {
        seedAttachment(MessageAttachment.KIND_AUDIO, "aud-old", "old.ogg");
        seedAttachment(MessageAttachment.KIND_AUDIO, "aud-new", "new.ogg");
        var out = ToolContext.withConversation(conv.id, () -> tool.execute("{}", agent));
        // Neither file has bytes on disk; the missing-storage error names the
        // resolved attachment, proving the newest one won.
        assertTrue(out.contains("aud-new"), "newest audio attachment must be picked: " + out);
    }

    @Test
    void defaultResolution_skipsGeneratedAudio() {
        seedAttachment(MessageAttachment.KIND_AUDIO, "aud-user", "meeting.ogg");
        var lineup = seedAttachment(MessageAttachment.KIND_AUDIO, "aud-lineup", "lineup.wav");
        lineup.generated = true; // the extract action's own playback file
        lineup.save();
        var out = ToolContext.withConversation(conv.id, () -> tool.execute("{}", agent));
        assertTrue(out.contains("aud-user"),
                "the user's upload must outrank a newer generated lineup: " + out);
    }

    @Test
    void enroll_requiresSpeakerName() {
        var att = seedAttachmentWithBytes("aud-enr-1", "erin.ogg");
        var out = ToolContext.withConversation(conv.id, () -> tool.execute(
                "{\"action\":\"enroll_speaker\",\"attachment_uuid\":\"" + att.uuid + "\"}", agent));
        assertTrue(out.contains("'speaker_name' is required"), out);
    }

    @Test
    void enroll_rejectsPathTraversalNames() {
        var att = seedAttachmentWithBytes("aud-enr-2", "erin.ogg");
        for (var bad : new String[]{"../escape", "a/b", ".hidden", "x\\y"}) {
            var out = ToolContext.withConversation(conv.id, () -> tool.execute(
                    "{\"action\":\"enroll_speaker\",\"speaker_name\":\"" + bad.replace("\\", "\\\\")
                            + "\",\"attachment_uuid\":\"" + att.uuid + "\"}", agent));
            assertTrue(out.contains("not a valid speaker name"), bad + " must be rejected: " + out);
        }
    }

    @Test
    void enroll_copiesClipIntoNamedFolder() throws Exception {
        var voicesRoot = Files.createTempDirectory("enroll-test-");
        SpeakerNamer.setRootForTest(voicesRoot);
        try {
            var att = seedAttachmentWithBytes("aud-enr-3", "erin voice.ogg");
            var out = ToolContext.withConversation(conv.id, () -> tool.execute(
                    "{\"action\":\"enroll_speaker\",\"speaker_name\":\"Erin\"}", agent));

            assertTrue(out.contains("Enrolled voice reference for \"Erin\""), out);
            var clip = voicesRoot.resolve("Erin").resolve(att.uuid + ".ogg");
            assertTrue(Files.isRegularFile(clip), "clip must land at " + clip);
            assertTrue(SpeakerNamer.enrollmentPresent(),
                    "enrollment scanner must see the new clip");
        } finally {
            SpeakerNamer.resetForTest();
            deleteRecursive(voicesRoot);
        }
    }

    @Test
    void unknownAction_returnsError() {
        seedAttachmentWithBytes("aud-enr-4", "x.ogg");
        var out = ToolContext.withConversation(conv.id,
                () -> tool.execute("{\"action\":\"transcode\"}", agent));
        assertTrue(out.contains("unknown action 'transcode'"), out);
    }

    @Test
    void enrollFromStaging_filesClipUnderName() throws Exception {
        var voicesRoot = Files.createTempDirectory("staging-test-");
        SpeakerNamer.setRootForTest(voicesRoot);
        try {
            var staging = voicesRoot.resolve(".staging").resolve(String.valueOf(conv.id));
            Files.createDirectories(staging);
            Files.write(staging.resolve("voice-2.wav"), new byte[]{1, 2, 3, 4});
            Files.write(staging.resolve("voice-2-ref2.wav"), new byte[]{5, 6, 7, 8});

            var out = ToolContext.withConversation(conv.id, () -> tool.execute(
                    "{\"action\":\"enroll_speaker\",\"clip_label\":\"voice-2\",\"speaker_name\":\"Erin\"}",
                    agent));

            assertTrue(out.contains("Enrolled voice reference for \"Erin\""), out);
            assertTrue(Files.isRegularFile(
                            voicesRoot.resolve("Erin").resolve(conv.id + "-voice-2.wav")),
                    "staged clip must be filed under the name");
            assertTrue(Files.isRegularFile(
                            voicesRoot.resolve("Erin").resolve(conv.id + "-voice-2-ref2.wav")),
                    "hidden reference clips staged with the lineup clip must be filed too (JCLAW-606)");
            assertFalse(Files.exists(
                            voicesRoot.resolve("Erin").resolve(conv.id + "-voice-2-ref3.wav")),
                    "absent staged refs are skipped, not errors");
        } finally {
            SpeakerNamer.resetForTest();
            deleteRecursive(voicesRoot);
        }
    }

    @Test
    void enrollFromStaging_rejectsNonLabelStrings() throws Exception {
        var voicesRoot = Files.createTempDirectory("staging-test-");
        SpeakerNamer.setRootForTest(voicesRoot);
        try {
            for (var bad : new String[]{"../../etc/passwd", "voice-", "clip-1", "voice-1x"}) {
                var out = ToolContext.withConversation(conv.id, () -> tool.execute(
                        "{\"action\":\"enroll_speaker\",\"clip_label\":\"" + bad
                                + "\",\"speaker_name\":\"Erin\"}", agent));
                assertTrue(out.contains("not a clip label"), bad + " must be rejected: " + out);
            }
        } finally {
            SpeakerNamer.resetForTest();
            deleteRecursive(voicesRoot);
        }
    }

    @Test
    void enrollFromStaging_missingClip_andMissingScope_returnErrors() throws Exception {
        var voicesRoot = Files.createTempDirectory("staging-test-");
        SpeakerNamer.setRootForTest(voicesRoot);
        try {
            var noScope = tool.execute(
                    "{\"action\":\"enroll_speaker\",\"clip_label\":\"voice-1\",\"speaker_name\":\"Erin\"}",
                    agent);
            assertTrue(noScope.contains("no conversation in scope"), noScope);

            var noClip = ToolContext.withConversation(conv.id, () -> tool.execute(
                    "{\"action\":\"enroll_speaker\",\"clip_label\":\"voice-1\",\"speaker_name\":\"Erin\"}",
                    agent));
            assertTrue(noClip.contains("no staged clip 'voice-1'"), noClip);
        } finally {
            SpeakerNamer.resetForTest();
            deleteRecursive(voicesRoot);
        }
    }

    private MessageAttachment seedAttachmentWithBytes(String uuid, String filename) {
        var att = seedAttachment(MessageAttachment.KIND_AUDIO, uuid, filename);
        try {
            var dir = AgentService.acquireWorkspacePath(agent.name, "attachments/" + conv.id);
            Files.createDirectories(dir);
            Files.write(dir.resolve(uuid), new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return att;
    }

    private static void deleteRecursive(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (java.io.IOException _) {} });
        }
    }

    private MessageAttachment seedAttachment(String kind, String uuid, String filename) {
        var msg = new Message();
        msg.conversation = conv;
        msg.role = MessageRole.USER.value;
        msg.content = "(upload)";
        msg.save();
        var att = new MessageAttachment();
        att.message = msg;
        att.uuid = uuid;
        att.kind = kind;
        att.mimeType = kind.equals(MessageAttachment.KIND_AUDIO) ? "audio/ogg" : "image/png";
        att.originalFilename = filename;
        att.storagePath = agent.name + "/attachments/" + conv.id + "/" + uuid;
        att.sizeBytes = 0;
        att.save();
        return att;
    }
}
