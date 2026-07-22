import controllers.ApiConversationsController;
import controllers.ApiConversationsController.ConversationView;
import controllers.ApiConversationsController.MessageAttachmentView;
import controllers.ApiConversationsController.MessageView;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JCLAW-827: the {@code @Schema} records documenting the {@code /conversations}
 * and {@code /messages} responses are hand-maintained beside HashMap-based
 * emitters ({@code conversationToMap}, {@code messageToMap},
 * {@code attachmentsToList}), so a new map key can silently drift out of the
 * OpenAPI contract. These tests populate every optional field, run the real
 * emitters via reflection, and assert the emitted keys are a subset of the
 * corresponding record's component names — failing the moment a map key lacks a
 * documented component.
 */
class ApiConversationsControllerSchemaDriftTest extends UnitTest {

    private static Set<String> componentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> missing(Set<String> emitted, Set<String> allowed) {
        var diff = new HashSet<>(emitted);
        diff.removeAll(allowed);
        return diff;
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = ApiConversationsController.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        return (T) m.invoke(null, args);
    }

    @Test
    void messageMapKeysAreSubsetOfMessageView() throws Exception {
        var msg = new Message();
        msg.id = 1L;
        msg.role = "assistant";
        msg.content = "hi";
        msg.toolResults = "call-1";
        msg.toolResultStructured = "{}";
        msg.reasoning = "thinking";
        msg.createdAt = Instant.now();
        msg.usageJson = "{}";
        msg.subagentRunId = 7L;
        msg.messageKind = "subagent_announce";
        msg.metadata = "{}";
        msg.truncated = true;

        Map<String, Object> emitted = invoke("messageToMap",
                new Class<?>[]{Message.class, List.class}, msg, List.of());

        var allowed = componentNames(MessageView.class);
        assertTrue(allowed.containsAll(emitted.keySet()),
                "map keys not documented in MessageView @Schema record: "
                        + missing(emitted.keySet(), allowed));
    }

    @Test
    void attachmentMapKeysAreSubsetOfMessageAttachmentView() throws Exception {
        var att = new MessageAttachment();
        att.uuid = "u";
        att.originalFilename = "f.png";
        att.mimeType = "image/png";
        att.sizeBytes = 10L;
        att.kind = "image";
        att.generated = true;
        att.deleted = true;
        att.generationMetadata = "{}";
        att.generationJobId = 5L;

        List<Map<String, Object>> emitted = invoke("attachmentsToList",
                new Class<?>[]{List.class}, List.of(att));

        var allowed = componentNames(MessageAttachmentView.class);
        for (var el : emitted) {
            assertTrue(allowed.containsAll(el.keySet()),
                    "attachment keys not documented in MessageAttachmentView @Schema record: "
                            + missing(el.keySet(), allowed));
        }
    }

    @Test
    void conversationMapKeysAreSubsetOfConversationView() throws Exception {
        var agent = new Agent();
        agent.id = 3L;
        agent.name = "agent";
        var parent = new Conversation();
        parent.id = 9L;

        var conv = new Conversation();
        conv.id = 1L;
        conv.agent = agent;
        conv.channelType = "web";
        conv.peerId = "peer";
        conv.createdAt = Instant.now();
        conv.updatedAt = Instant.now();
        conv.messageCount = 4;
        conv.preview = "hello";
        conv.modelProviderOverride = "openrouter";
        conv.modelIdOverride = "gpt-5";
        conv.parentConversation = parent;

        Map<String, Object> emitted = invoke("conversationToMap",
                new Class<?>[]{Conversation.class, long.class}, conv, 2L);

        var allowed = componentNames(ConversationView.class);
        assertTrue(allowed.containsAll(emitted.keySet()),
                "map keys not documented in ConversationView @Schema record: "
                        + missing(emitted.keySet(), allowed));
    }
}
