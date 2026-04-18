package services;

import llm.LlmTypes.*;
import llm.LlmProvider;
import llm.ProviderRegistry;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.Message;
import models.MessageRole;
import play.db.jpa.JPA;

import java.util.List;

public class ConversationService {

    private static final int TITLE_CONTEXT_MESSAGES = 10;
    private static final int TITLE_TIMEOUT_SECONDS = 15;

    public static Conversation findOrCreate(Agent agent, String channelType, String peerId) {
        var existing = Conversation.findByAgentChannelPeer(agent, channelType, peerId);
        if (existing != null) return existing;
        return create(agent, channelType, peerId);
    }

    public static Conversation create(Agent agent, String channelType, String peerId) {
        var convo = new Conversation();
        convo.agent = agent;
        convo.channelType = channelType;
        convo.peerId = peerId;
        convo.save();

        EventLogger.info("agent", agent.name, channelType,
                "New conversation created (agent: %s, peer: %s)".formatted(agent.name, peerId != null ? peerId : "none"));
        return convo;
    }

    public static Message appendMessage(Conversation conversation, MessageRole role, String content,
                                         String toolCalls, String toolResults, String usageJson) {
        var msg = new Message();
        msg.conversation = conversation;
        msg.role = role.value;
        msg.content = content;
        msg.toolCalls = toolCalls;
        msg.toolResults = toolResults;
        msg.usageJson = usageJson;
        msg.save();

        conversation.messageCount++;
        if (role == MessageRole.USER && content != null && conversation.preview == null) {
            conversation.preview = content.substring(0, Math.min(content.length(), 100));
        }

        // Only save conversation for user/final-assistant messages to avoid redundant
        // UPDATEs during tool call rounds. @PreUpdate handles updatedAt automatically.
        if (role == MessageRole.USER || (role == MessageRole.ASSISTANT && content != null)) {
            conversation.save();
        }

        return msg;
    }

    public static Message appendUserMessage(Conversation conversation, String content) {
        return appendMessage(conversation, MessageRole.USER, content, null, null, null);
    }

    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls) {
        return appendAssistantMessage(conversation, content, toolCalls, null);
    }

    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls, String usageJson) {
        return appendMessage(conversation, MessageRole.ASSISTANT, content, toolCalls, null, usageJson);
    }

    public static Message appendToolResult(Conversation conversation, String toolCallId, String result) {
        return appendMessage(conversation, MessageRole.TOOL, result, null, toolCallId, null);
    }

    /**
     * Load recent messages for context window assembly, returned in chronological order.
     */
    public static List<Message> loadRecentMessages(Conversation conversation) {
        var maxMessages = ConfigService.getInt("chat.maxContextMessages", 50);
        // findRecent returns DESC order; reversed() returns a read-only ASC view
        // without copying — uses JDK 21 SequencedCollection.
        return Message.findRecent(conversation, maxMessages).reversed();
    }

    public static Conversation findById(Long id) {
        return Conversation.findById(id);
    }

    /**
     * Bulk-delete conversations (and their messages) by ID using JPQL.
     * Both single and bulk delete routes use this to ensure consistent behavior.
     * @return the number of conversations deleted
     */
    public static int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        var em = JPA.em();
        em.createQuery("DELETE FROM Message m WHERE m.conversation.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        return em.createQuery("DELETE FROM Conversation c WHERE c.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
    }

    /**
     * Assemble message context from a conversation and kick off async title
     * generation. Returns {@code false} if there are no user messages (i.e. the
     * caller should fall back to the existing preview).
     */
    public static boolean requestTitleGeneration(Conversation conversation) {
        // Idempotency gate: a conversation's title is computed once and then
        // considered final. Without this, the chat page fires this endpoint on
        // every sidebar-switch and the LLM keeps rewriting the preview —
        // burning tokens and flipping the title visibly each time.
        if (conversation.titleGenerated) return false;

        List<Message> msgs = Message.find("conversation = ?1 ORDER BY createdAt ASC", conversation).fetch(TITLE_CONTEXT_MESSAGES);
        var userParts = new StringBuilder();
        var assistantParts = new StringBuilder();
        for (var m : msgs) {
            if (MessageRole.USER.value.equals(m.role) && m.content != null) {
                if (!userParts.isEmpty()) userParts.append("\n");
                userParts.append(m.content);
            } else if (MessageRole.ASSISTANT.value.equals(m.role) && m.content != null) {
                if (!assistantParts.isEmpty()) assistantParts.append("\n");
                assistantParts.append(m.content);
            }
        }

        if (userParts.isEmpty()) return false;

        generateTitleAsync(conversation.id,
                userParts.substring(0, Math.min(userParts.length(), 500)),
                assistantParts.substring(0, Math.min(assistantParts.length(), 500)));
        return true;
    }

    /**
     * Generate a short title for a conversation using an LLM call.
     * Runs asynchronously on a virtual thread — does not block the caller.
     * @param userContext summary of user messages (truncated)
     * @param assistantContext summary of assistant messages (truncated)
     */
    public static void generateTitleAsync(Long conversationId, String userContext, String assistantContext) {
        Thread.ofVirtual().start(() -> {
            try {
                var provider = Tx.run(ProviderRegistry::getPrimary);
                if (provider == null) return;

                var modelId = provider.config().models().isEmpty() ? null : provider.config().models().getFirst().id();
                if (modelId == null) return;

                var messages = List.of(
                        ChatMessage.system("Generate a short title (max 6 words) for this conversation. " +
                                "Return ONLY the title text, nothing else. No quotes, no punctuation at the end."),
                        ChatMessage.user("User messages:\n%s\n\nAssistant messages:\n%s".formatted(
                                userContext, assistantContext))
                );

                var response = provider.chat(modelId, messages, List.of(), null, null, TITLE_TIMEOUT_SECONDS);
                if (response.choices() == null || response.choices().isEmpty()) return;

                var rawContent = response.choices().getFirst().message().content();
                var title = (rawContent instanceof String s ? s : "").strip();
                if (title.isEmpty() || title.length() > 100) return;

                Tx.run(() -> {
                    Conversation convo = Conversation.findById(conversationId);
                    if (convo != null) {
                        convo.preview = title;
                        convo.titleGenerated = true;
                        convo.save();
                    }
                });
            } catch (Exception e) {
                // Title generation is best-effort — don't fail the conversation
                EventLogger.warn("agent", "Title generation failed: %s".formatted(e.getMessage()));
            }
        });
    }
}
