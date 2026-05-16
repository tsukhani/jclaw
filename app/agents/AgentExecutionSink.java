package agents;

import services.AttachmentService;

import java.util.List;

/**
 * Write-target abstraction for {@link AgentRunner}. Decouples message
 * persistence (where the user/assistant turns end up) from the rest of the
 * runner's logic so the same agent loop can serve two storage backends:
 *
 * <ul>
 *   <li>{@link ConversationSink} — writes Message rows linked to a
 *       {@link models.Conversation}. Powers all existing chat flows
 *       (web, Telegram, slash commands, channel adapters).</li>
 *   <li>{@code TaskRunSink} (subsequent JCLAW-21 commit) — writes
 *       {@code task_run_message} rows linked to a {@code TaskRun}, so
 *       task fires record their per-turn transcript without
 *       manufacturing a Conversation.</li>
 * </ul>
 *
 * <p>This interface intentionally covers ONLY the write surface. Read-side
 * concerns (channel metadata, peer id, conversation history loading) stay
 * on the caller's side; the few AgentRunner code paths that need
 * conversation-specific metadata downcast via
 * {@link ConversationSink#conversation()} until they're incrementally
 * refactored in later commits.
 *
 * <p>Lifecycle methods ({@link #onStart}, {@link #onComplete},
 * {@link #onFailure}) are no-ops by default. {@link ConversationSink}
 * leaves them empty because the Conversation already exists before the
 * run; {@code TaskRunSink} uses them to open and close the
 * {@code TaskRun} row that bookends the agent loop.
 *
 * <p>Naming note: "sink" in this codebase has historically meant a
 * channel-side streaming adapter (e.g. {@code TelegramStreamingSink}
 * handles live token edits to Telegram messages). Those are about UX
 * during a turn. {@code AgentExecutionSink} is about persistence of the
 * turn's history. Same word, different layers; the qualifier in the
 * type name keeps them apart.
 *
 * <p>Part of JCLAW-21's Tasks foundation.
 */
public interface AgentExecutionSink {

    /**
     * Called once before any {@code append...} method, after the runner
     * has finished its preliminaries (config load, queue acquire) but
     * before the user input is persisted. Default no-op for sinks whose
     * storage context (e.g. a Conversation) already exists.
     */
    default void onStart() {}

    /**
     * Persist the user turn. {@code attachments} may be {@code null} or
     * empty for a text-only message.
     */
    void appendUserMessage(String content, List<AttachmentService.Input> attachments);

    /**
     * Persist an assistant turn with all optional metadata. Other
     * overloads forward to this method with null or false defaults for
     * omitted fields.
     *
     * @param content the assistant text (may be empty when the turn was
     *                a pure tool-call dispatch with no surface response)
     * @param toolCalls JSON-encoded tool-call list, or {@code null}
     * @param usageJson JSON-encoded token-usage record, or {@code null}
     * @param reasoning model-reported reasoning trace, or {@code null}
     * @param truncated true if the model hit {@code finish_reason=length}
     */
    void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                String reasoning, boolean truncated);

    /** Convenience overload: assistant turn without usage, reasoning, or truncated. */
    default void appendAssistantMessage(String content, String toolCalls) {
        appendAssistantMessage(content, toolCalls, null, null, false);
    }

    /**
     * Persist the result of a tool invocation. {@code structuredJson} may
     * be {@code null} for tools that don't produce structured output;
     * the rich-widget renderer (web_search favicons, etc.) reads it when
     * present.
     */
    void appendToolResult(String toolCallId, String result, String structuredJson);

    /** Convenience overload: tool result with no structured payload. */
    default void appendToolResult(String toolCallId, String result) {
        appendToolResult(toolCallId, result, null);
    }

    /**
     * Called once after the run completes successfully. {@code outputSummary}
     * is the agent's final assistant text (post-tool-loop).
     * {@link ConversationSink} ignores this; {@code TaskRunSink} stores it
     * on the TaskRun row so the monitoring UI can show a one-line summary
     * in the Timeline.
     */
    default void onComplete(String outputSummary) {}

    /**
     * Called once if the run fails. {@link ConversationSink} ignores this;
     * {@code TaskRunSink} records the failure reason on the TaskRun row
     * and closes it.
     */
    default void onFailure(String error) {}

    /**
     * Short identifier suitable for log lines. Defaults to {@code "unknown"};
     * implementations override to expose conversation id, task run id, etc.
     */
    default String executionLabel() { return "unknown"; }
}
