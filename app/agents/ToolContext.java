package agents;

import java.util.function.Supplier;

/**
 * Per-tool-execution context (JCLAW-462). Carries the scope ids native tools
 * need but the Tool SPI doesn't pass — {@code ccr_retrieve} resolves a content
 * hash back to its original tool result, and that original lives in a different
 * table depending on the run:
 *
 * <ul>
 *   <li><b>Chat:</b> {@code conversationId} → scan the conversation's
 *       {@code Message} tool rows.</li>
 *   <li><b>Task fire:</b> {@code taskRunId} → scan the task run's
 *       {@code TaskRunMessage} tool rows (task fires run on a stub, unpersisted
 *       Conversation with a null id, so there is no conversation to scope to).</li>
 * </ul>
 *
 * <p>Exactly one of the two is set per run. The dispatcher
 * ({@link ParallelToolExecutor}) sets/clears this on the tool's own virtual
 * thread around the call, so it can't leak between concurrent tools.
 */
public final class ToolContext {

    private ToolContext() {}

    /** The scope ids visible to a tool during its dispatch; exactly one is set. */
    public record Scope(Long conversationId, Long taskRunId) {}

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    /** Run {@code body} with both scope ids visible via {@link #conversationId()} / {@link #taskRunId()}. */
    public static <T> T withScope(Long conversationId, Long taskRunId, Supplier<T> body) {
        var prev = SCOPE.get();
        SCOPE.set(new Scope(conversationId, taskRunId));
        try {
            return body.get();
        } finally {
            if (prev == null) SCOPE.remove();
            else SCOPE.set(prev);
        }
    }

    /** Chat-path convenience: {@link #withScope} with no task-run id. */
    public static <T> T withConversation(Long conversationId, Supplier<T> body) {
        return withScope(conversationId, null, body);
    }

    /** The active conversation id (chat path), or {@code null} outside a chat tool dispatch. */
    public static Long conversationId() {
        var s = SCOPE.get();
        return s == null ? null : s.conversationId();
    }

    /** The active task-run id (task-fire path), or {@code null} outside a task tool dispatch. */
    public static Long taskRunId() {
        var s = SCOPE.get();
        return s == null ? null : s.taskRunId();
    }
}
