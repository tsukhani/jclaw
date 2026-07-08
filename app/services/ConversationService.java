package services;

import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import play.db.jpa.JPA;
import services.search.LuceneIndexer;
import services.transcription.PendingTranscripts;
import services.transcription.TranscriptionRouter;
import utils.JpqlFilter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ConversationService {

    private ConversationService() {}

    /**
     * JCLAW-267: per-thread inline-subagent-run marker. When non-null, every
     * Message persisted via {@link #appendMessage} on the current (virtual)
     * thread is stamped with this id so the chat UI can fold the nested-turn
     * trace into a collapsible block. Set by
     * {@link tools.SubagentSpawnTool} around its inline branch's
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
     *
     * @param runId the subagent run id to stamp on persisted messages
     * @param body  work to run with the marker bound
     * @param <T>   {@code body}'s return type
     * @return the value returned by {@code body}
     */
    public static <T> T withSubagentRunIdMarker(Long runId, Supplier<T> body) {
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
        return findOrCreate(agent, channelType, peerId, null);
    }

    /**
     * Chat-type-aware {@link #findOrCreate}. When a new row is created, its
     * {@link Conversation#chatType} is set to {@code chatType} (when non-null);
     * an existing row is returned unchanged — chat type is stamped once at
     * creation, never overwritten on subsequent turns. Only the Telegram ingress
     * paths pass a real value; every other caller delegates with {@code null}
     * via the 3-arg overload, leaving chatType null and behavior unchanged.
     *
     * @param agent       the owning agent
     * @param channelType the channel identifier
     * @param peerId      the channel-specific peer key
     * @param chatType    Telegram {@code chat.type}, or null for non-Telegram /
     *                    unknown
     * @return the existing or newly-created conversation
     */
    public static Conversation findOrCreate(Agent agent, String channelType, String peerId, String chatType) {
        var existing = Conversation.findByAgentChannelPeer(agent, channelType, peerId);
        if (existing != null) return existing;
        return create(agent, channelType, peerId, chatType);
    }

    /**
     * Read-only counterpart to {@link #findOrCreate}: return the existing
     * conversation for this {@code (agent, channelType, peerId)}, or
     * {@code null} when none exists yet. Lets callers detect first contact
     * (no row) without creating one — used by the Telegram {@code /start}
     * welcome (JCLAW-97).
     */
    public static Conversation find(Agent agent, String channelType, String peerId) {
        return Conversation.findByAgentChannelPeer(agent, channelType, peerId);
    }

    /**
     * Set both conversation-scoped override columns atomically (JCLAW-108).
     * Both non-null means "use these for this conversation's turns"; both
     * null clears the override and falls back to the agent's defaults.
     * Caller owns the transaction — this method assumes an active Tx so it
     * can piggy-back on {@code /model NAME}'s handler transaction without
     * a redundant Tx.run nesting.
     *
     * @param conversation the conversation whose override columns to set
     * @param provider     provider override, or null to clear
     * @param modelId      model-id override, or null to clear
     */
    public static void setModelOverride(Conversation conversation, String provider, String modelId) {
        conversation.modelProviderOverride = provider;
        conversation.modelIdOverride = modelId;
        conversation.save();
    }

    /**
     * Clear the conversation-scoped override. See {@link #setModelOverride}.
     *
     * @param conversation the conversation whose override columns to clear
     */
    public static void clearModelOverride(Conversation conversation) {
        conversation.modelProviderOverride = null;
        conversation.modelIdOverride = null;
        conversation.save();
    }

    public static Conversation create(Agent agent, String channelType, String peerId) {
        return create(agent, channelType, peerId, null);
    }

    /**
     * Chat-type-aware {@link #create}. Sets {@link Conversation#chatType} only
     * when {@code chatType} is non-null, so non-Telegram channels and callers
     * without a chat type leave the column null (the pre-existing behavior).
     *
     * @param agent       the owning agent
     * @param channelType the channel identifier
     * @param peerId      the channel-specific peer key
     * @param chatType    Telegram {@code chat.type}, or null
     * @return the newly-created conversation
     */
    public static Conversation create(Agent agent, String channelType, String peerId, String chatType) {
        var convo = new Conversation();
        convo.agent = agent;
        convo.channelType = channelType;
        convo.peerId = peerId;
        if (chatType != null) convo.chatType = chatType;
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
     * Each attachment's staged file gets moved to the conversation-keyed
     * final directory by {@link AttachmentService#finalizeAttachment} and a
     * {@link models.MessageAttachment} row is written against the new
     * message. A {@code VISION_ATTACHMENT_INGEST} event is emitted per image
     * attachment.
     *
     * @param conversation the conversation to append into
     * @param content      the user's message text
     * @param attachments  roundtripped verbatim from the upload response;
     *                     each entry's staged file is finalized against the
     *                     new message row
     * @return the persisted user message
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
        return appendAssistantMessage(conversation, content, toolCalls, usageJson, reasoning, false);
    }

    /**
     * JCLAW-291 overload: persist the assistant turn AND stamp
     * {@link Message#truncated} when the runner detected
     * {@code finish_reason=length / max_tokens} on a non-tool-call reply
     * (the empty-{@code toolCalls} truncation branch). The chat UI reads
     * the column and renders a "Reply was truncated by the model" marker.
     *
     * @param conversation the conversation to append into
     * @param content      assistant body text
     * @param toolCalls    JSON-encoded tool-call list, or {@code null}
     * @param usageJson    JSON-encoded token-usage record, or {@code null}
     * @param reasoning    model-reported reasoning trace, or {@code null}
     * @param truncated    true when the model hit {@code finish_reason=length}
     * @return the persisted assistant message
     */
    public static Message appendAssistantMessage(Conversation conversation, String content,
                                                   String toolCalls, String usageJson, String reasoning,
                                                   boolean truncated) {
        var msg = appendMessage(conversation, MessageRole.ASSISTANT, content, toolCalls, null, usageJson, reasoning);
        if (truncated) {
            msg.truncated = true;
            msg.save();
        }
        return msg;
    }

    public static Message appendToolResult(Conversation conversation, String toolCallId, String result) {
        return appendToolResult(conversation, toolCallId, result, null);
    }

    /**
     * JCLAW-170 overload: persist a tool-result row with an optional
     * structured JSON payload (e.g. web-search result list with favicons)
     * the UI renders as rich widgets.
     *
     * @param conversation   the conversation to append into
     * @param toolCallId     id correlating this result with the assistant
     *                       turn's {@code tool_calls} entry
     * @param result         plain-text result body the LLM sees next turn
     * @param structuredJson optional structured payload; {@code null} for
     *                       tools that don't produce structured output
     * @return the persisted tool-result message
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
        var maxMessages = effectiveHistoryLimit(conversation);
        var floor = latestOf(conversation.contextSince, conversation.compactionSince);
        // findRecent returns DESC order; reversed() returns a read-only ASC view
        // without copying — uses JDK 21 SequencedCollection.
        var recent = Message.findRecent(conversation, maxMessages, floor).reversed();
        // JCLAW-270 + JCLAW-273: drop SYSTEM-role subagent-announce rows from
        // LLM context assembly (UI-only fire-and-forget cards — surfacing them
        // to the model would feed a system prompt the model didn't author and
        // risk re-acknowledging an already-delivered subagent result). USER-
        // role announces — JCLAW-273 yield resumes — are the parent's next
        // user input by construction and MUST stay in context so the LLM
        // sees what it's resuming on. Rows without a messageKind discriminator
        // (the dominant case — regular user/assistant/tool messages) always
        // pass through.
        return recent.stream()
                .filter(m -> m.messageKind == null
                        || MessageRole.USER.value.equals(m.role))
                .toList();
    }

    /** Global history-limit fallback, also the default for the per-type key. */
    static final String KEY_GLOBAL_MAX = "chat.maxContextMessages";
    /** Per-type override for GROUP-chat conversations (JCLAW-387 B4). */
    static final String KEY_GROUP_LIMIT = "groupChat.historyLimit";
    /**
     * Per-type override for DM / private conversations (JCLAW-387 B4). Now
     * reachable: the persisted {@link Conversation#chatType} distinguishes a
     * plain DM from a plain group, which the composite {@code peerId} alone
     * could not.
     */
    static final String KEY_DM_LIMIT = "dmHistoryLimit";

    /** Telegram {@code chat.type} for a one-on-one DM. */
    private static final String CHAT_TYPE_PRIVATE = "private";
    /** Telegram {@code chat.type} values that denote a multi-member group. */
    private static final String CHAT_TYPE_GROUP = "group";
    private static final String CHAT_TYPE_SUPERGROUP = "supergroup";

    /**
     * Resolve the effective history-load cap for {@code conversation} (JCLAW-387 B4).
     *
     * <p>The global {@code chat.maxContextMessages} (default 50) is the baseline.
     * A GROUP-chat conversation may override it via {@code groupChat.historyLimit};
     * a DM / private conversation via {@code dmHistoryLimit}. Each per-type key
     * defaults to the resolved global value when its own key isn't set, so a
     * deployment that sets neither per-type key behaves exactly as before.
     *
     * <p><b>Chat-type resolution.</b> The persisted {@link Conversation} now
     * carries the inbound Telegram {@code chat.type} on {@link Conversation#chatType}
     * (stamped at creation by the Telegram ingress paths), so a plain DM and a
     * plain group are distinguishable on the row:
     * <ul>
     *   <li><b>Non-Telegram channels</b> (web, slack, …) → global. No per-type
     *       notion applies; behavior unchanged.</li>
     *   <li><b>Telegram, stored {@code chatType="private"}</b> → the DM limit.</li>
     *   <li><b>Telegram, stored {@code chatType="group"}/"supergroup"</b> → the
     *       GROUP limit.</li>
     *   <li><b>Telegram forum-topic conversations</b> — whose {@code peerId}
     *       carries the structural {@code ":topic:<threadId>"} suffix that
     *       JClaw's own keying ({@code AgentRunner.telegramConversationPeerId})
     *       writes for group/supergroup topic chats → GROUP limit. Retained as a
     *       secondary signal so legacy rows created before {@code chatType}
     *       existed still resolve a forum topic to the group cap.</li>
     *   <li><b>Telegram, no stored {@code chatType} and no {@code :topic:}
     *       suffix</b> (a legacy plain peerId) → global. A plain DM and a plain
     *       group can't be told apart on such a row, so it falls through to the
     *       global default rather than risk mis-classifying.</li>
     * </ul>
     *
     * @param conversation the conversation whose history cap to resolve
     * @return the effective max number of recent messages to load
     */
    public static int effectiveHistoryLimit(Conversation conversation) {
        var globalMax = ConfigService.getInt(KEY_GLOBAL_MAX, 50);
        var perTypeKey = perTypeHistoryKey(conversation);
        if (perTypeKey == null) return globalMax;
        return ConfigService.getInt(perTypeKey, globalMax);
    }

    /**
     * Pick the per-chat-type config key for {@code conversation}, or {@code null}
     * when chat type isn't cleanly derivable from the persisted row (so the
     * caller falls back to the global cap). See {@link #effectiveHistoryLimit}
     * for the full resolution contract.
     */
    private static String perTypeHistoryKey(Conversation conversation) {
        if (!ChannelType.TELEGRAM.value.equals(conversation.channelType)) {
            return null; // non-Telegram: no per-type notion, use global
        }
        // Primary signal: the chat.type stamped at creation. Distinguishes a
        // plain DM from a plain group, which the peerId alone cannot.
        var stored = conversation.chatType;
        if (CHAT_TYPE_PRIVATE.equals(stored)) {
            return KEY_DM_LIMIT;
        }
        if (CHAT_TYPE_GROUP.equals(stored) || CHAT_TYPE_SUPERGROUP.equals(stored)) {
            return KEY_GROUP_LIMIT;
        }
        // Secondary signal for legacy rows created before chat_type existed: a
        // forum-topic peerId carries the ":topic:" suffix — an unambiguous group
        // marker — so resolve it to the group cap.
        var peerId = conversation.peerId;
        if (peerId != null && peerId.contains(":topic:")) {
            return KEY_GROUP_LIMIT;
        }
        // No stored chatType and no topic suffix: DM vs group not safely
        // distinguishable → global.
        return null;
    }

    private static Instant latestOf(Instant a, Instant b) {
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
     *
     * @param ids conversation ids to delete (children, SubagentRuns, and
     *            attached messages cascade automatically)
     * @return the number of conversations deleted
     */
    public static int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        var em = JPA.em();
        // Subagent-tree cascade: a Conversation can be the parent of one or
        // more child Conversations (subagent runs), and SubagentRun audit
        // rows hold FKs to both ends. Pre-fix, bulk-delete-by-filter
        // happened to include the children in the same SQL statement so
        // the FKs were satisfied at statement-end. Now that the listing
        // filter excludes children (so /conversations doesn't double-show
        // them), explicit cascade-cleanup keeps deleteByIds working when
        // a parent has subagent children.
        // 1. Recursive deleteByIds for child conversations (depth-first —
        //    handles grandchildren / SubagentRuns / Messages / etc).
        List<Long> childIds = em.createQuery(
                "SELECT c.id FROM Conversation c WHERE c.parentConversation.id IN :ids",
                Long.class).setParameter("ids", ids).getResultList();
        if (!childIds.isEmpty()) {
            deleteByIds(childIds);
        }
        // 2. SubagentRun rows referencing any of these conversations on
        //    either side. Done after the child cascade so a recursive call
        //    doesn't try to double-delete a SubagentRun the parent's
        //    cleanup would have removed. JCLAW-673: collect the ids first so
        //    their SUBAGENT_RUN full-text docs can be evicted after the bulk
        //    JPQL DELETE, which never fires SubagentRun.@PostRemove.
        List<Long> subagentRunIds = em.createQuery(
                "SELECT sr.id FROM SubagentRun sr "
                        + "WHERE sr.parentConversation.id IN :ids "
                        + "   OR sr.childConversation.id IN :ids", Long.class)
                .setParameter("ids", ids).getResultList();
        em.createQuery(
                "DELETE FROM SubagentRun sr "
                        + "WHERE sr.parentConversation.id IN :ids "
                        + "   OR sr.childConversation.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        // 3a. JCLAW-209: free the on-disk attachment bytes before dropping the rows that
        // point at them. All of a conversation's attachments live under one directory
        // (workspace/{agent}/attachments/{conversationId}/), so a per-conversation sweep
        // reclaims them; without this the files would be orphaned on disk forever once the
        // rows are cascade-deleted below. Child conversations are handled by the recursive
        // call above. Done while the conversation→agent join is still intact.
        @SuppressWarnings("unchecked")
        List<Object[]> agentDirs = em.createQuery(
                "SELECT c.id, c.agent.name FROM Conversation c WHERE c.id IN :ids")
                .setParameter("ids", ids).getResultList();
        for (var row : agentDirs) {
            AttachmentService.deleteConversationAttachments((String) row[1], (Long) row[0]);
        }
        // 3b. JCLAW-135: collect the message ids so their CONVERSATION_MESSAGE
        // Lucene docs can be evicted after the delete. The DB cascades
        // chat_message_attachment / message / session_compaction off the
        // Conversation delete below (ON DELETE CASCADE, JCLAW-542), so the old
        // hand-ordered JPQL sweep of those three tables is gone — but a bulk /
        // cascade delete never fires Message.@PostRemove, so the full-text docs
        // would orphan without this explicit cleanup. (Child-conversation
        // messages are handled by the recursive call in step 1.)
        List<Long> messageIds = em.createQuery(
                "SELECT m.id FROM Message m WHERE m.conversation.id IN :ids", Long.class)
                .setParameter("ids", ids).getResultList();
        int deleted = em.createQuery("DELETE FROM Conversation c WHERE c.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        for (Long messageId : messageIds) {
            LuceneIndexer.remove(LuceneIndexer.Scope.CONVERSATION_MESSAGE, messageId);
        }
        if (!messageIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.CONVERSATION_MESSAGE);
        }
        // JCLAW-673: evict the SUBAGENT_RUN docs for the rows swept in step 2.
        for (Long subagentRunId : subagentRunIds) {
            LuceneIndexer.remove(LuceneIndexer.Scope.SUBAGENT_RUN, subagentRunId);
        }
        if (!subagentRunIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.SUBAGENT_RUN);
        }
        return deleted;
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
     * @param channel  channel constraint, or null/blank for any channel
     * @param agentId  agent constraint, or null for any agent
     * @param name     case-insensitive substring of the conversation
     *                 preview, or null/blank for any preview
     * @param peer     case-insensitive substring of the peer id, or
     *                 null/blank for any peer
     * @return the number of conversations deleted
     */
    public static int deleteByFilter(String channel, Long agentId, String name, String peer) {
        boolean hasNameFilter = name != null && !name.isBlank();
        var filter = new JpqlFilter()
                .eq("channelType", channel)
                .eq("agent.id", agentId)
                .like("LOWER(preview)", hasNameFilter ? "%" + name.toLowerCase() + "%" : null)
                .like("LOWER(peerId)", peer != null && !peer.isBlank() ? "%" + peer.toLowerCase() + "%" : null);

        // Bulk-delete must mirror the listing endpoint's exclusion of
        // subagent children (parentConversation != null). Without this,
        // the /conversations page's "Delete all" would silently nuke the
        // subagent transcripts too — invisible to the operator and
        // potentially destructive to a still-RUNNING subagent's audit row
        // foreign keys. Per-id deletes (deleteByIds) are still allowed
        // because those are explicit, scoped operator actions.
        var dynamicWhere = filter.toWhereClause();
        var fullWhere = dynamicWhere.isEmpty()
                ? "c.parentConversation IS NULL"
                : "c.parentConversation IS NULL AND " + dynamicWhere;
        String jpql = "SELECT c.id FROM Conversation c WHERE " + fullWhere;
        var q = JPA.em().createQuery(jpql, Long.class);
        var params = filter.paramList();
        for (int i = 0; i < params.size(); i++) {
            q.setParameter(i + 1, params.get(i));
        }
        List<Long> ids = q.getResultList();
        return deleteByIds(ids);
    }

}
