package agents;

import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.compression.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces large tool results from earlier turns with a one-line stub carrying their
 * {@code ccr_retrieve} handle (JCLAW-1202). Independent of window fill: the context-window
 * trim only truncates when the prompt overflows, which a 1M-window model never does, so an
 * early round's 70k-char file read otherwise rides along in every later call of the
 * conversation. The current turn and the newest messages are never touched, and stored
 * history is untouched — the stub points at the durable row.
 */
public final class ToolResultPruner {
    private ToolResultPruner() {}

    static final String ENABLED_KEY = "chat.pruneToolResults";
    static final String MIN_CHARS_KEY = "chat.pruneToolResultsMinChars";
    static final String PROTECT_RECENT_KEY = "chat.pruneToolResultsProtectRecent";
    static final int DEFAULT_MIN_CHARS = 4_000;
    static final int DEFAULT_PROTECT_RECENT = 12;
    /** Prefix of every stub; also what stops a stub from being stubbed again. */
    public static final String STUB_MARKER = "[tool result elided";
    // Literal rather than tools.CcrRetrieveTool.TOOL_NAME to avoid an agents <-> tools package cycle.
    private static final String CCR_RETRIEVE_TOOL = "ccr_retrieve";

    /** Config-driven entry point; returns the same list instance when nothing changed. */
    public static List<ChatMessage> prune(List<ChatMessage> messages, Agent agent, @Nullable Conversation conversation) {
        if (messages == null || messages.isEmpty() || !ConfigService.getBoolean(ENABLED_KEY, true)) return messages;
        return prune(messages, ConfigService.getInt(MIN_CHARS_KEY, DEFAULT_MIN_CHARS),
                ConfigService.getInt(PROTECT_RECENT_KEY, DEFAULT_PROTECT_RECENT), agent, conversation);
    }

    /**
     * Pure core (test seam). Eligible: TOOL-role messages before the last user message and
     * outside the last {@code protectRecent} messages, with a String body of at least
     * {@code minChars} that is not already a stub and not the retrieve tool's own output.
     */
    public static List<ChatMessage> prune(List<ChatMessage> messages, int minChars, int protectRecent,
                                          @Nullable Agent agent, @Nullable Conversation conversation) {
        int cutoff = Math.min(lastUserIndex(messages), Math.max(0, messages.size() - protectRecent));
        List<ChatMessage> out = null;
        int stubbed = 0;
        long elided = 0;
        for (int i = 0; i < cutoff; i++) {
            var m = messages.get(i);
            if (!MessageRole.TOOL.value.equals(m.role()) || CCR_RETRIEVE_TOOL.equals(m.toolName())) continue;
            if (!(m.content() instanceof String body) || body.length() < minChars || body.startsWith(STUB_MARKER)) continue;
            if (out == null) out = new ArrayList<>(messages);
            out.set(i, new ChatMessage(m.role(), stub(m.toolName(), body), m.toolCalls(), m.toolCallId(), m.toolName()));
            stubbed++;
            elided += body.length();
        }
        if (out == null) return messages;
        if (agent != null) {
            EventLogger.info("llm", agent.name, conversation != null ? conversation.channelType : null,
                    "Stubbed %d earlier tool result(s), %d chars elided; each carries its ccr_retrieve handle"
                            .formatted(stubbed, elided));
        }
        return out;
    }

    private static int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (MessageRole.USER.value.equals(messages.get(i).role())) return i;
        }
        return messages.size();
    }

    static String stub(@Nullable String toolName, String body) {
        return STUB_MARKER + ": " + (toolName != null ? toolName : "a tool") + " returned " + body.length()
                + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(body)
                + "\") if you need the full text again]";
    }
}
