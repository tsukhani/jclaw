package agents;

import models.Conversation;
import services.AttachmentService;
import services.ConversationService;

import java.util.List;

/**
 * {@link AgentExecutionSink} backed by a {@link Conversation}. All
 * {@code append...} methods forward to the static helpers on
 * {@link ConversationService}, preserving the existing message
 * persistence semantics for chat flows.
 *
 * <p>Lifecycle methods are no-ops. The Conversation already exists
 * before the runner is invoked, and the chat UI treats "completion" as
 * the natural end of the streaming response — there's nothing to mark
 * on a row that already represents the conversation as a whole.
 * {@code TaskRunSink} (a subsequent JCLAW-21 commit) overrides
 * {@link #onStart}, {@link #onComplete}, and {@link #onFailure} because
 * the TaskRun row's lifecycle bookends the agent loop one-to-one with
 * a single fire.
 *
 * <p>Exposes the underlying {@link #conversation()} via accessor so the
 * subset of AgentRunner code that still needs conversation-specific
 * metadata (channelType, peerId, id) can reach it without going through
 * the sink interface. Migrating those calls to interface methods is
 * incremental refactoring left to subsequent JCLAW-21 commits.
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
        ConversationService.appendUserMessage(conversation, content, attachments);
    }

    @Override
    public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                       String reasoning, boolean truncated) {
        ConversationService.appendAssistantMessage(conversation, content, toolCalls,
                usageJson, reasoning, truncated);
    }

    @Override
    public void appendToolResult(String toolCallId, String result, String structuredJson) {
        ConversationService.appendToolResult(conversation, toolCallId, result, structuredJson);
    }

    @Override
    public String executionLabel() {
        return "conversation:" + conversation.id;
    }
}
