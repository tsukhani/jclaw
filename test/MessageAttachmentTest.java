import org.junit.jupiter.api.*;
import play.test.*;
import models.*;

import java.util.UUID;

public class MessageAttachmentTest extends UnitTest {

    private Agent agent;
    private Conversation conversation;
    private Message message;

    @BeforeEach
    public void setUp() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "vision-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "openai/gpt-4o";
        agent.save();

        conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "tester";
        conversation.save();

        message = new Message();
        message.conversation = conversation;
        message.role = MessageRole.USER.value;
        message.content = "what is in this image?";
        message.save();
    }

    @Test
    public void persistsImageAttachment() {
        var uuid = UUID.randomUUID().toString();
        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = "screenshot.png";
        att.storagePath = "vision-agent/attachments/" + conversation.id + "/" + uuid + ".png";
        att.mimeType = "image/png";
        att.sizeBytes = 12345L;
        att.kind = MessageAttachment.KIND_IMAGE;
        att.save();

        assertNotNull(att.id);
        assertNotNull(att.createdAt);

        var found = MessageAttachment.findByUuid(uuid);
        assertNotNull(found);
        assertEquals("screenshot.png", found.originalFilename);
        assertEquals("image/png", found.mimeType);
        assertEquals(MessageAttachment.KIND_IMAGE, found.kind);
        assertEquals(message.id, found.message.id);
    }

    @Test
    public void findByMessageReturnsInsertionOrder() {
        var first = persist("a.png", MessageAttachment.KIND_IMAGE);
        var second = persist("b.pdf", MessageAttachment.KIND_FILE);
        var third = persist("c.jpg", MessageAttachment.KIND_IMAGE);

        var all = MessageAttachment.findByMessage(message);
        assertEquals(3, all.size());
        assertEquals(first.id, all.get(0).id);
        assertEquals(second.id, all.get(1).id);
        assertEquals(third.id, all.get(2).id);
    }

    @Test
    public void uuidIsUnique() {
        var uuid = UUID.randomUUID().toString();
        var first = new MessageAttachment();
        first.message = message;
        first.uuid = uuid;
        first.originalFilename = "a.png";
        first.storagePath = "path/a";
        first.mimeType = "image/png";
        first.sizeBytes = 1L;
        first.kind = MessageAttachment.KIND_IMAGE;
        first.save();

        var clash = new MessageAttachment();
        clash.message = message;
        clash.uuid = uuid;
        clash.originalFilename = "b.png";
        clash.storagePath = "path/b";
        clash.mimeType = "image/png";
        clash.sizeBytes = 2L;
        clash.kind = MessageAttachment.KIND_IMAGE;
        assertThrows(Exception.class, () -> {
            clash.save();
            clash.em().flush();
        });
    }

    private MessageAttachment persist(String filename, String kind) {
        var uuid = UUID.randomUUID().toString();
        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = filename;
        att.storagePath = "vision-agent/attachments/" + conversation.id + "/" + uuid;
        att.mimeType = kind.equals(MessageAttachment.KIND_IMAGE) ? "image/png" : "application/pdf";
        att.sizeBytes = 100L;
        att.kind = kind;
        att.save();
        return att;
    }
}
