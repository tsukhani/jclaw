package agents;

import models.Conversation;
import models.MessageAttachment;
import models.VideoGenerationJob;
import org.jspecify.annotations.Nullable;
import services.AttachmentService;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final String ASSISTANT = "assistant";

    private final Conversation conversation;
    private final boolean fenced;
    private final long generation;
    private final @Nullable AtomicBoolean turnCancel;
    /** Sticky: once a commit is refused, every later write of this turn is counted, not made. */
    private volatile boolean discarding;
    private final AtomicInteger droppedRows = new AtomicInteger();

    public ConversationSink(Conversation conversation) {
        this(conversation, false, 0, null);
    }

    /**
     * A sink whose {@link #commit} writes only while the turn still owns {@code conversation} at
     * {@code generation} and {@code turnCancel} (when non-null) is unset.
     */
    public ConversationSink(Conversation conversation, long generation, @Nullable AtomicBoolean turnCancel) {
        this(conversation, true, generation, turnCancel);
    }

    private ConversationSink(Conversation conversation, boolean fenced, long generation,
                             @Nullable AtomicBoolean turnCancel) {
        if (conversation == null) {
            throw new IllegalArgumentException("conversation must not be null");
        }
        this.conversation = conversation;
        this.fenced = fenced;
        this.generation = generation;
        this.turnCancel = turnCancel;
    }

    /**
     * Commit {@code writes} under {@link ConversationQueue#commitIfOwner}. A refused commit still
     * runs {@code writes}, so the caller's in-memory state stays consistent, but with every append
     * turned into a count.
     */
    @Override
    public boolean commit(Runnable writes) {
        if (!fenced) {
            Tx.run(writes);
            return true;
        }
        if (!discarding && ConversationQueue.commitIfOwner(conversation.id, generation, turnCancel,
                () -> Tx.run(writes))) {
            return true;
        }
        discarding = true;
        writes.run();
        return false;
    }

    /** Rows this turn would have written after it lost the conversation. */
    public int droppedRows() {
        return droppedRows.get();
    }

    /** Count the row instead of writing it once the turn has lost the conversation. */
    private boolean dropping() {
        if (!discarding) return false;
        droppedRows.incrementAndGet();
        return true;
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
    public void appendUserMessage(String content,
                                  @Nullable List<AttachmentService.Input> attachments) {
        if (dropping()) return;
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped("user");
            return;
        }
        ConversationService.appendUserMessage(managed, content, attachments);
    }

    @Override
    public void appendAssistantMessage(@Nullable String content, @Nullable String toolCalls,
                                       @Nullable String usageJson, @Nullable String reasoning,
                                       boolean truncated) {
        if (dropping()) return;
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped(ASSISTANT);
            return;
        }
        ConversationService.appendAssistantMessage(managed, content, toolCalls,
                usageJson, reasoning, truncated);
    }

    @Override
    public List<MessageAttachment> appendAssistantMessage(
            @Nullable String content, @Nullable String toolCalls,
            List<GeneratedAttachment> attachments) {
        if (dropping()) return List.of();
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped(ASSISTANT);
            return List.of();
        }
        var msg = ConversationService.appendAssistantMessage(managed, content, toolCalls);
        // JCLAW-228/562: inline every tool-produced attachment on the ONE assistant turn that
        // carried the call, and return the rows so the runner can push them onto the live SSE
        // tool_call frame.
        var persisted = new ArrayList<MessageAttachment>(attachments.size());
        for (var a : attachments) {
            persisted.add(AttachmentService.persistGeneratedAttachment(
                    managed.agent, msg, a.bytes(), a.mimeType(), a.metadata(), a.filename()));
        }
        return persisted;
    }

    @Override
    public @Nullable MessageAttachment appendVideoPlaceholder(@Nullable String content,
            @Nullable String toolCalls, ToolRegistry.VideoJobRef videoJob) {
        if (dropping()) return null;
        var managed = ConversationService.findById(conversation.id);
        if (managed == null) {
            warnSkipped(ASSISTANT);
            return null;
        }
        var msg = ConversationService.appendAssistantMessage(managed, content, toolCalls);
        if (videoJob == null || videoJob.jobId() == null) {
            return null;
        }
        // JCLAW-236: backfill the job's conversation (the tool submits without one) so the Settings jobs
        // panel can link each job back into its chat.
        VideoGenerationJob job = VideoGenerationJob.findById(videoJob.jobId());
        if (job != null && job.conversation == null) {
            job.conversation = managed;
            job.save();
        }
        // JCLAW-234/235: a zero-byte placeholder linked to the job; the runner fills it on completion
        // and the chat swaps the generating card for the inline player. Returned so the runner ships it
        // on the live SSE tool_call frame (which starts the frontend's progress poll).
        return AttachmentService.createGeneratedVideoPlaceholder(
                managed.agent, msg, videoJob.jobId(), videoJob.generationMetadata());
    }

    @Override
    public void appendToolResult(@Nullable String toolCallId, String result,
                                 @Nullable String structuredJson) {
        if (dropping()) return;
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
