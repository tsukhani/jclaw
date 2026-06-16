package agents;

import java.util.function.Supplier;

/**
 * Per-tool-execution context (JCLAW-462). Carries the current conversation id
 * to native tools that need it — {@code ccr_retrieve} scopes its lookup to the
 * active conversation. The Tool SPI intentionally passes only the {@code Agent};
 * this ThreadLocal fills the gap without widening every tool's signature.
 *
 * <p>Plain {@link ThreadLocal} (mirrors
 * {@code ConversationService.INLINE_SUBAGENT_RUN_ID}): native tools run on
 * per-call virtual threads, and the dispatcher sets/clears this on that same
 * thread around the tool call, so it can't leak between concurrent tools.
 */
public final class ToolContext {

    private ToolContext() {}

    private static final ThreadLocal<Long> CONVERSATION_ID = new ThreadLocal<>();

    /** Run {@code body} with {@code conversationId} visible via {@link #conversationId()}. */
    public static <T> T withConversation(Long conversationId, Supplier<T> body) {
        var prev = CONVERSATION_ID.get();
        CONVERSATION_ID.set(conversationId);
        try {
            return body.get();
        } finally {
            if (prev == null) CONVERSATION_ID.remove();
            else CONVERSATION_ID.set(prev);
        }
    }

    /** The active conversation id, or {@code null} when called outside a tool dispatch. */
    public static Long conversationId() {
        return CONVERSATION_ID.get();
    }
}
