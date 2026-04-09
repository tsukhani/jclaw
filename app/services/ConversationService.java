package services;

import llm.LlmTypes.*;
import llm.LlmProvider;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import play.Play;

import java.util.Collections;
import java.util.List;

public class ConversationService {

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

    public static Message appendMessage(Conversation conversation, String role, String content,
                                         String toolCalls, String toolResults) {
        var msg = new Message();
        msg.conversation = conversation;
        msg.role = role;
        msg.content = content;
        msg.toolCalls = toolCalls;
        msg.toolResults = toolResults;
        msg.save();

        conversation.messageCount++;
        if ("user".equals(role) && content != null && conversation.preview == null) {
            conversation.preview = content.substring(0, Math.min(content.length(), 100));
        }

        // Only save conversation for user/final-assistant messages to avoid redundant
        // UPDATEs during tool call rounds. @PreUpdate handles updatedAt automatically.
        if ("user".equals(role) || ("assistant".equals(role) && content != null)) {
            conversation.save();
        }

        return msg;
    }

    public static Message appendUserMessage(Conversation conversation, String content) {
        return appendMessage(conversation, "user", content, null, null);
    }

    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls) {
        return appendMessage(conversation, "assistant", content, toolCalls, null);
    }

    public static Message appendToolResult(Conversation conversation, String toolCallId, String result) {
        return appendMessage(conversation, "tool", result, null, toolCallId);
    }

    /**
     * Load recent messages for context window assembly, returned in chronological order.
     */
    public static List<Message> loadRecentMessages(Conversation conversation) {
        var maxMessages = Integer.parseInt(
                Play.configuration.getProperty("jclaw.agent.max.context.messages", "50"));
        // findRecent returns DESC order, we need ASC for the LLM
        var messages = Message.findRecent(conversation, maxMessages);
        var reversed = new java.util.ArrayList<>(messages);
        Collections.reverse(reversed);
        return reversed;
    }

    public static Conversation findById(Long id) {
        return Conversation.findById(id);
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

                var response = provider.chat(modelId, messages, List.of(), null, null);
                if (response.choices() == null || response.choices().isEmpty()) return;

                var title = ((String) response.choices().getFirst().message().content()).trim();
                if (title.isEmpty() || title.length() > 100) return;

                Tx.run(() -> {
                    Conversation convo = Conversation.findById(conversationId);
                    if (convo != null) {
                        convo.preview = title;
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
