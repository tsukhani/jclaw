package services;

import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import play.db.jpa.JPA;
import services.transcription.PendingTranscripts;
import services.transcription.TranscriptionRouter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ConversationService {

    private ConversationService() {}

    /**
     * JCLAW-267: per-thread inline-subagent-run marker. When non-null, every
     * Message persisted via {@link #appendMessage} on the current (virtual)
     * thread is stamped with this id so the chat UI can fold the nested-turn
     * trace into a collapsible block. Set by
     * {@link tools.SpawnSubagentTool} around its inline branch's
     * {@code AgentRunner.run} call; cleared in a finally so unrelated traffic
     * on the same carrier thread never inherits a stale marker.
     *
     * <p>Plain {@link ThreadLocal} (not {@link ScopedValue}) so virtual threads
     * spawned by the inline run inherit nothing — the inline marker only
     * applies to messages the parent's spawn thread persists, and AgentRunner
     * runs everything on the calling thread anyway (its internal VTs are for
     * outbound LLM HTTP, not message persistence).
     */
    private static final ThreadLocal<Long> INLINE_SUBAGENT_RUN_ID = new ThreadLocal<>();

    /**
     * Run {@code body} with {@link #INLINE_SUBAGENT_RUN_ID} bound to
     * {@code runId} so every {@link #appendMessage} call made on the current
     * thread stamps that id on the persisted Message. Always clears in a
     * finally — never leak the marker back to the caller.
     */
    public static <T> T withSubagentRunIdMarker(Long runId, java.util.function.Supplier<T> body) {
        var prev = INLINE_SUBAGENT_RUN_ID.get();
        INLINE_SUBAGENT_RUN_ID.set(runId);
        try {
            return body.get();
        } finally {
            if (prev == null) INLINE_SUBAGENT_RUN_ID.remove();
            else INLINE_SUBAGENT_RUN_ID.set(prev);
        }
    }

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
        // JCLAW-267: stamp the inline-subagent-run marker on every message
        // persisted during an inline-mode child run so the chat UI can fold
        // the nested-turn trace into a collapsible block. ThreadLocal is null
        // (cleared) for every other call path — top-level turns and
        // session-mode children alike.
        msg.subagentRunId = INLINE_SUBAGENT_RUN_ID.get();
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
                    // JCLAW-165: dispatch transcription on a virtual thread the
                    // moment the audio row is persisted. The future is registered
                    // in PendingTranscripts so AgentRunner's text-only branch
                    // and the format-rejection retry path can await it on demand
                    // without blocking the prepared-data transaction.
                    dispatchTranscription(att);
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
    /**
     * JCLAW-165: kick off transcription for a freshly-finalized audio
     * attachment on a virtual thread. Registers a {@link CompletableFuture}
     * in {@link PendingTranscripts} so downstream branches in AgentRunner
     * can await on demand.
     *
     * <p>Failure handling is silent per AC: any throwable resolves the
     * future with the empty string {@code ""} and leaves
     * {@code MessageAttachment.transcript} NULL. Callers awaiting the
     * future treat empty-string as "could not transcribe" and substitute
     * the standard fallback note.
     *
     * <p>Restart resilience: in-flight VTs do not survive a JVM restart.
     * Per AC, no backfill — the row's transcript stays NULL and any
     * future read by a non-supportsAudio model on a later turn falls
     * through to the fallback note.
     */
    static void dispatchTranscription(MessageAttachment attachment) {
        var serviceOpt = TranscriptionRouter.configuredService();
        if (serviceOpt.isEmpty()) return;
        var service = serviceOpt.get();
        var attId = attachment.id;
        if (attId == null) return; // attachment not yet persisted — shouldn't happen
        var future = new CompletableFuture<String>();
        PendingTranscripts.register(attId, future);
        Thread.ofVirtual().name("transcription-" + attId).start(() -> {
            String transcript;
            try {
                var result = service.transcribe(attachment);
                transcript = result == null ? "" : result;
            } catch (@SuppressWarnings("java:S1181") Throwable t) {
                // Top-level guard for background transcription VT — must never propagate (JNI/native errors included)
                EventLogger.warn("transcription",
                        "Transcription failed for attachment %d: %s"
                                .formatted(attId, t.getMessage()));
                transcript = "";
            }
            // Per AC: empty result means failure → leave transcript NULL.
            // Non-empty result is persisted, even if any branch never
            // awaited the future (history search + replay invariants).
            if (!transcript.isEmpty()) {
                final String finalTranscript = transcript;
                try {
                    Tx.run(() -> {
                        var fresh = (MessageAttachment) MessageAttachment.findById(attId);
                        if (fresh != null) {
                            fresh.transcript = finalTranscript;
                            fresh.save();
                        }
                    });
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // Background VT — DB persistence failure must not propagate
                    EventLogger.warn("transcription",
                            "Failed to persist transcript for attachment %d: %s"
                                    .formatted(attId, t.getMessage()));
                }
            }
            future.complete(transcript);
        });
    }

    public static List<Message> loadRecentMessages(Conversation conversation) {
        var maxMessages = ConfigService.getInt("chat.maxContextMessages", 50);
        var floor = latestOf(conversation.contextSince, conversation.compactionSince);
        // findRecent returns DESC order; reversed() returns a read-only ASC view
        // without copying — uses JDK 21 SequencedCollection.
        var recent = Message.findRecent(conversation, maxMessages, floor).reversed();
        // JCLAW-270: drop subagent-announce rows from LLM context assembly.
        // They're UI-only structured cards; surfacing them to the model would
        // both feed a system prompt the model didn't author and risk it
        // re-acknowledging an already-delivered subagent result on the next
        // turn. The Message row stays visible in the chat scrollback and
        // sidebar — only the LLM view filters it out.
        return recent.stream()
                .filter(m -> m.messageKind == null)
                .toList();
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

    /**
     * Resolve every conversation id matching the given filter (same predicates
     * the listing endpoint accepts), then delegate to {@link #deleteByIds} so
     * the cascade ordering and FK handling stay in one place. Any of
     * {@code channel}, {@code agentId}, {@code name}, {@code peer} may be
     * {@code null}/blank to mean "no constraint on this field"; passing all
     * four as null/blank deletes every conversation in the table.
     *
     * <p>Two-step (resolve ids, then delete) rather than a single
     * filtered-DELETE because the cascade has to delete attachments and
     * messages first, and JPQL DELETE statements don't support sub-selects
     * across the conversation/message join in every dialect we run on.
     * Building the id list once and reusing {@link #deleteByIds} keeps the
     * exact ordering this service has shipped with since launch.
     *
     * @return the number of conversations deleted
     */
    public static int deleteByFilter(String channel, Long agentId, String name, String peer) {
        boolean hasNameFilter = name != null && !name.isBlank();
        var filter = new utils.JpqlFilter()
                .eq("channelType", channel)
                .eq("agent.id", agentId)
                .like("LOWER(preview)", hasNameFilter ? "%" + name.toLowerCase() + "%" : null)
                .like("LOWER(peerId)", peer != null && !peer.isBlank() ? "%" + peer.toLowerCase() + "%" : null);

        var where = filter.toWhereClause();
        String jpql = where.isEmpty()
                ? "SELECT c.id FROM Conversation c"
                : "SELECT c.id FROM Conversation c WHERE " + where;
        var q = JPA.em().createQuery(jpql, Long.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
        List<Long> ids = q.getResultList();
        return deleteByIds(ids);
    }

}
