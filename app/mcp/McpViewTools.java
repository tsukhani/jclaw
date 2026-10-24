package mcp;

import agents.DangerousActionGate;
import agents.ToolContext;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import utils.ApiResponses;
import utils.ChannelOriginTrust;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Where the model is offered an MCP tool that links a view (MCP Apps): only on a web-chat turn of a
 * top-level conversation, the one place a view draws, unless its server's operator offers view tools
 * on every channel. A view-first tool elsewhere returns data nothing displays.
 * The turn is read from its own context, as {@link DangerousActionGate} reads it, never from a model's
 * arguments.
 */
public final class McpViewTools {

    private McpViewTools() {}

    /**
     * Whether the turn on this thread is a web-chat turn of a top-level conversation: not a subagent's
     * run, inline or not, nor a task run, its origin {@code web} as {@link DangerousActionGate#effectiveOrigin}
     * reads it, and its conversation without a parent.
     */
    public static boolean webChatTurn(@Nullable Long conversationId) {
        if (conversationId == null || SubagentRegistry.currentRun() != null || ToolContext.taskRunId() != null) {
            return false;
        }
        // One read: the channel, or null when the conversation is gone or a subagent's.
        var channel = Tx.run(() -> Conversation.<Conversation>findById(conversationId) instanceof Conversation c
                && c.parentConversation == null ? c.channelType : null);
        return channel != null && ChannelOriginTrust.WEB.equals(DangerousActionGate.effectiveOrigin(conversationId, () -> channel));
    }

    /** {@link #webChatTurn(Long)} for the tool dispatch running on this thread. */
    public static boolean webChatDispatch() {
        return webChatTurn(ToolContext.conversationId());
    }

    /** {@link #webChatDispatch()}, where a failure to tell counts as no web-chat turn: the call goes on without views. */
    public static boolean webChatDispatchOrNot() {
        try {
            return webChatDispatch();
        } catch (RuntimeException e) {
            EventLogger.warn("MCP_APPS", "Could not tell whether this turn draws MCP Apps views: " + ApiResponses.messageOf(e));
            return false;
        }
    }

    /** {@code turn}, asked at most once. */
    public static BooleanSupplier once(BooleanSupplier turn) {
        var asked = new Boolean[1];
        return () -> {
            if (asked[0] == null) asked[0] = turn.getAsBoolean();
            return asked[0];
        };
    }

    /**
     * The actions of {@code serverName} the model may see on a turn: visible to it, and a view tool only
     * where one is offered. {@code webChatTurn} is asked only when a view tool is in question.
     */
    public static List<McpToolDef> offered(String serverName, List<McpToolDef> defs, BooleanSupplier webChatTurn) {
        var visible = defs.stream().filter(McpToolDef::visibleToModel).toList();
        if (visible.stream().allMatch(d -> d.resourceUri() == null)
                || McpConnectionManager.offersViewToolsEverywhere(serverName) || webChatTurn.getAsBoolean()) {
            return visible;
        }
        return visible.stream().filter(d -> d.resourceUri() == null).toList();
    }
}
