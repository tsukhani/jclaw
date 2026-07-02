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
import tools.DiarizeAudioTool;

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
