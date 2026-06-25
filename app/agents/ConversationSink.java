package agents;

import models.Conversation;
import models.MessageAttachment;
import services.AttachmentService;
import services.ConversationService;
import services.EventLogger;

import java.util.List;

/**
 * {@link AgentExecutionSink} backed by a {@link Conversation}. All
 * {@code append...} methods forward to the static helpers on
 * {@link ConversationService}, preserving the existing message
 * persistence semantics for chat flows.
 *
 * <h2>Detached-entity safety</h2>
 * Callers on virtual threads (db-scheduler fires via TaskExecutionHandler,
 * webhooks, the streaming
 * runStreaming entry point) pass a {@link Conversation} loaded in an
 * already-committed {@code Tx.run()} block — that entity is detached
 * when AgentRunner sees it, and {@code conversation.save()} inside
 * {@link ConversationService#appendMessage} would throw
 * {@code PersistentObjectException}. Each write method therefore
 * re-fetches the managed entity by id from the current persistence
 * context before delegating, matching the pattern the chat code path
 * used before this sink was introduced. The re-fetch is cheap inside
 * an open Tx because JPA's L1 cache returns the same instance.
 *
 * <p>Lifecycle methods are no-ops. The Conversation already exists
 * before the runner is invoked, and the chat UI treats "completion" as
 * the natural end of the streaming response — there's nothing to mark
 * on a row that already represents the conversation as a whole.
 * {@code TaskRunSink} (sibling implementation in the JCLAW-21 series)
 * overrides {@link #onStart}, {@link #onComplete}, and
 * {@link #onFailure} because the TaskRun row's lifecycle bookends the
 * agent loop one-to-one with a single fire.
 *
 * <p>Exposes the underlying {@link #conversation()} via accessor so the
 * subset of AgentRunner code that still needs conversation-specific
 * metadata (channelType, peerId, id) can reach it without going through
 * the sink interface. The read-side fields used on AgentRunner's paths
 * (channelType, id, agent.name) are eager primitives or strings that
 * stay readable on the detached entity, so no re-fetch is needed for
 * metadata access.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public class ConversationSink implements AgentExecutionSink {

    private final Conversation conversation;

    public ConversationSink(Conversation conversation) {
        if (conversation == null) {
            throw new IllegalArgumentException("conversation must not be null");
        }
        this.conversation = conversation;
    }

    /**
     * The underlying Conversation. Exposed so AgentRunner code that
     * still reads conversation metadata directly has an escape hatch
     * during the incremental migration to a metadata-aware sink API.
     */
    public Conversation conversation() {
        return conversation;
    }

    @Override
    public void appendUserMessage(String content, List<AttachmentService.Input> attachments) {
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped("user");
            return;
        }
        ConversationService.appendUserMessage(managed, content, attachments);
    }

    @Override
    public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                       String reasoning, boolean truncated) {
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped("assistant");
            return;
        }
        ConversationService.appendAssistantMessage(managed, content, toolCalls,
                usageJson, reasoning, truncated);
    }

    @Override
    public MessageAttachment appendAssistantMessage(String content, String toolCalls, GeneratedAttachment image) {
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped("assistant");
            return null;
        }
        var msg = ConversationService.appendAssistantMessage(managed, content, toolCalls);
        if (image != null) {
            // JCLAW-228: inline the generate_image output on the assistant turn that called the tool,
            // and return the row so the runner can push it onto the live SSE tool_call frame.
            return AttachmentService.persistGeneratedImage(
                    managed.agent, msg, image.bytes(), image.mimeType(), image.metadata());
        }
        return null;
    }

    @Override
    public MessageAttachment appendVideoPlaceholder(String content, String toolCalls, ToolRegistry.VideoJobRef videoJob) {
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped("assistant");
            return null;
        }
        var msg = ConversationService.appendAssistantMessage(managed, content, toolCalls);
        if (videoJob == null || videoJob.jobId() == null) {
            return null;
        }
        // JCLAW-234/235: a zero-byte placeholder linked to the job; the runner fills it on completion
        // and the chat swaps the generating card for the inline player. Returned so the runner ships it
        // on the live SSE tool_call frame (which starts the frontend's progress poll).
        return AttachmentService.createGeneratedVideoPlaceholder(
                managed.agent, msg, videoJob.jobId(), videoJob.generationMetadata());
    }

    @Override
    public void appendToolResult(String toolCallId, String result, String structuredJson) {
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped("tool-result");
            return;
        }
        ConversationService.appendToolResult(managed, toolCallId, result, structuredJson);
    }

    /**
     * Mirror the pre-sink AgentRunner pattern: when the conversation was
     * deleted between the LLM call and the write (loadtest cleanup,
     * manual UI delete, etc.), log + skip rather than insert a Message
     * with a null FK. The chat UI never displays these — they're
     * operator-only diagnostics.
     */
    private void warnSkipped(String kind) {
        var channel = conversation.channelType;
        EventLogger.warn("agent", null, channel,
                "Persist skipped (%s): conversation %d was deleted before persist completed"
                        .formatted(kind, conversation.id));
    }

    @Override
    public String executionLabel() {
        return "conversation:" + conversation.id;
    }
}
