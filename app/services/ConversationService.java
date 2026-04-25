package services;

import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.Message;
import models.MessageRole;
import play.db.jpa.JPA;

import java.util.List;

public class ConversationService {

    public static Conversation findOrCreate(Agent agent, String channelType, String peerId) {
        var existing = Conversation.findByAgentChannelPeer(agent, channelType, peerId);
        if (existing != null) return existing;
        return create(agent, channelType, peerId);
    }

    /**
     * Set both conversation-scoped override columns atomically (JCLAW-108).
     * Both non-null means "use these for this conversation's turns"; both
     * null clears the override and falls back to the agent's defaults.
     * Caller owns the transaction — this method assumes an active Tx so it
     * can piggy-back on {@code /model NAME}'s handler transaction without
     * a redundant Tx.run nesting.
     */
    public static void setModelOverride(Conversation conversation, String provider, String modelId) {
        conversation.modelProviderOverride = provider;
        conversation.modelIdOverride = modelId;
        conversation.save();
    }

    /** Clear the conversation-scoped override. See {@link #setModelOverride}. */
    public static void clearModelOverride(Conversation conversation) {
        conversation.modelProviderOverride = null;
        conversation.modelIdOverride = null;
        conversation.save();
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
        return appendMessage(conversation, role, content, toolCalls, toolResults, usageJson, null);
    }

    public static Message appendMessage(Conversation conversation, MessageRole role, String content,
                                         String toolCalls, String toolResults, String usageJson,
                                         String reasoning) {
        var msg = new Message();
        msg.conversation = conversation;
        msg.role = role.value;
        msg.content = content;
        msg.toolCalls = toolCalls;
        msg.toolResults = toolResults;
        msg.usageJson = usageJson;
        msg.reasoning = reasoning;
        msg.save();

        conversation.messageCount++;
        if (role == MessageRole.USER && content != null && conversation.preview == null) {
            // Budget is the @Column(length=100) cap on Conversation.preview, not
            // the input length — so truncate to 97 and reserve 3 chars for the
            // ellipsis marker so the UI can show "real prompt was longer."
            conversation.preview = content.length() <= 100
                    ? content
                    : content.substring(0, 97) + "...";
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

    /**
     * Persist a user message together with its attached files (JCLAW-25).
     * {@code attachments} is roundtripped verbatim from the upload response;
     * each entry's staged file gets moved to the conversation-keyed final
     * directory by {@link AttachmentService#finalizeAttachment} and a
     * {@link models.MessageAttachment} row is written against the new
     * message. A {@code VISION_ATTACHMENT_INGEST} event is emitted per image
     * attachment.
     */
    public static Message appendUserMessage(Conversation conversation, String content,
                                             List<AttachmentService.Input> attachments) {
        var msg = appendMessage(conversation, MessageRole.USER, content, null, null, null);
        if (attachments != null && !attachments.isEmpty()) {
            for (var input : attachments) {
                var att = AttachmentService.finalizeAttachment(conversation.agent, msg, input);
                if (att.isImage()) {
                    EventLogger.info("VISION_ATTACHMENT_INGEST",
                            conversation.agent.name, conversation.channelType,
                            "image=%s (%s, %d bytes)"
                                    .formatted(att.originalFilename, att.mimeType, att.sizeBytes));
                } else if (att.isAudio()) {
                    EventLogger.info("AUDIO_ATTACHMENT_INGEST",
                            conversation.agent.name, conversation.channelType,
                            "audio=%s (%s, %d bytes)"
                                    .formatted(att.originalFilename, att.mimeType, att.sizeBytes));
                }
            }
        }
        return msg;
    }

    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls) {
        return appendAssistantMessage(conversation, content, toolCalls, null, null);
    }

    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls, String usageJson) {
        return appendAssistantMessage(conversation, content, toolCalls, usageJson, null);
    }

    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls, String usageJson, String reasoning) {
        return appendMessage(conversation, MessageRole.ASSISTANT, content, toolCalls, null, usageJson, reasoning);
    }

    public static Message appendToolResult(Conversation conversation, String toolCallId, String result) {
        return appendToolResult(conversation, toolCallId, result, null);
    }

    /**
     * JCLAW-170 overload: persist a tool-result row with an optional
     * structured JSON payload (e.g. web-search result list with favicons)
     * the UI renders as rich widgets. {@code structuredJson} is null for
     * tools that don't produce structured output.
     */
    public static Message appendToolResult(Conversation conversation, String toolCallId,
                                            String result, String structuredJson) {
        var msg = appendMessage(conversation, MessageRole.TOOL, result, null, toolCallId, null);
        if (structuredJson != null) {
            msg.toolResultStructured = structuredJson;
            msg.save();
        }
        return msg;
    }

    /**
     * Load recent messages for context window assembly, returned in chronological order.
     *
     * <p>Honors two independent watermarks, whichever is tighter wins:
     * <ul>
     *   <li>{@link Conversation#contextSince} — {@code /reset} (JCLAW-26).
     *       User-driven: when invoked, the LLM sees an empty slate on the
     *       next turn while history stays in the DB.</li>
     *   <li>{@link Conversation#compactionSince} — session compaction
     *       (JCLAW-38). Automatic: older turns have been summarized into a
     *       {@link models.SessionCompaction} row and the summary is
     *       injected into the system prompt in place of the raw messages.</li>
     * </ul>
     * The two are orthogonal — a user can {@code /reset} a compacted
     * conversation, or compaction can fire on a conversation that's
     * already had a reset — so the effective floor is {@code max(..)}.
     */
    public static List<Message> loadRecentMessages(Conversation conversation) {
        var maxMessages = ConfigService.getInt("chat.maxContextMessages", 50);
        var floor = latestOf(conversation.contextSince, conversation.compactionSince);
        // findRecent returns DESC order; reversed() returns a read-only ASC view
        // without copying — uses JDK 21 SequencedCollection.
        return Message.findRecent(conversation, maxMessages, floor).reversed();
    }

    private static java.time.Instant latestOf(java.time.Instant a, java.time.Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
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
        // MessageAttachment first — FK from chat_message_attachment.message_id to
        // message.id has no ON DELETE CASCADE, so the bulk Message delete below
        // would otherwise fail with a referential-integrity violation.
        em.createQuery("DELETE FROM MessageAttachment a WHERE a.message.conversation.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        em.createQuery("DELETE FROM Message m WHERE m.conversation.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        em.createQuery("DELETE FROM SessionCompaction sc WHERE sc.conversation.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        return em.createQuery("DELETE FROM Conversation c WHERE c.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
    }

}
