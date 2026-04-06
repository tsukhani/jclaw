package services;

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

        var convo = new Conversation();
        convo.agent = agent;
        convo.channelType = channelType;
        convo.peerId = peerId;
        convo.save();

        EventLogger.info("agent", agent.name, channelType,
                "New conversation created (peer: %s)".formatted(peerId != null ? peerId : "none"));
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

        // Update conversation timestamp
        conversation.updatedAt = java.time.Instant.now();
        conversation.save();

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
}
