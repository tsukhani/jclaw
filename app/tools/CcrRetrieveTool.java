package tools;

import agents.MessageHydrator;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.TaskRunMessage;
import org.jspecify.annotations.Nullable;
import services.CompressionMetrics;
import services.ConversationService;
import services.Tx;
import services.compression.ContentHash;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * CCR retrieve (JCLAW-462). Returns the full, uncompressed original of a
 * content-compressed tool result. When the compression pipeline shrinks a tool
 * output it leaves a marker like
 * {@code [compressed — call ccr_retrieve("ab12cd34ef567890") for the full original]};
 * the LLM passes that handle here when the compressed view (first items +
 * schema + errors) isn't enough.
 *
 * <p>No cache table: the original tool result already lives durably in a
 * message row. The active run is either a chat ({@code Message} rows scoped by
 * conversation) or a task fire ({@code TaskRunMessage} rows scoped by task run —
 * task fires run on a stub Conversation with no id and persist to
 * {@code task_run_message}); the scope is surfaced via {@link ToolContext}. This
 * tool rescans the matching tool-role rows and returns the one whose content
 * SHA-256 matches the handle.
 */
public class CcrRetrieveTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "ccr_retrieve";
    /** Assistant rows a LIKE on one call id may match: providers that number calls per response reuse ids. */
    private static final int CALL_ROW_CANDIDATES = 20;

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("retrieve",
                "Return the full original tool result for a compression hash"));
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    // JF-136: the result is JClaw's own state, never text from outside.
    @Override
    public boolean readsOutsideContent(String argsJson, Agent agent) {
        return false;
    }

    @Override
    public String category() {
        return "System";
    }

    @Override
    public String icon() {
        // "search" maps to a magnifying glass in the frontend tool-icon set
        // (pages/tools.vue, pages/agents.vue) — "archive" isn't mapped and
        // renders blank. The action is a retrieval/look-up by hash.
        return "search";
    }

    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public String description() {
        return "Retrieve the full, uncompressed original of a content-compressed tool result — "
                + "pass the hash from its [compressed — ccr_retrieve(\"<hash>\")] marker.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        "hash", Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "The hash from a [compressed — ccr_retrieve(\"…\")] marker on a tool result.")),
                SchemaKeys.REQUIRED, List.of("hash"));
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        if (!args.has("hash") || args.get("hash").isJsonNull()) {
            return "Error: missing required 'hash' argument.";
        }
        var handle = args.get("hash").getAsString().trim();
        if (handle.isEmpty()) {
            return "Error: 'hash' must not be empty.";
        }

        var conversationId = ToolContext.conversationId();
        var taskRunId = ToolContext.taskRunId();
        if (conversationId == null && taskRunId == null) {
            return "Error: ccr_retrieve has no active conversation or task-run context.";
        }

        var lookup = Tx.run(() -> findOriginal(conversationId, taskRunId, handle));
        // JCLAW-467, JF-155: each lookup is a metric row naming the agent and the tool fetched back; a missing
        // conversation is not a lookup.
        var agentId = agent.id != null ? String.valueOf(agent.id) : null;
        switch (lookup) {
            // The model may pass a shorter prefix; the full handle is what the compression recorded.
            case Found(var text, var toolName) -> CompressionMetrics.recordCcrRetrieval(agentId, toolName,
                    ContentHash.handle(text), true);
            case NotFound _ -> CompressionMetrics.recordCcrRetrieval(agentId, null, null, false);
            case Failed _ -> { }
        }
        return lookup.text();
    }

    private sealed interface Lookup {
        String text();
    }

    /** The original, and the tool that produced it when a call row names it. */
    private record Found(String text, @Nullable String toolName) implements Lookup {}

    private record NotFound(String text) implements Lookup {}

    private record Failed(String text) implements Lookup {}

    /**
     * Locate the original tool-output whose content hash starts with {@code handle},
     * scanning newest-first — chat {@link Message} rows when in a conversation, or
     * {@link TaskRunMessage} rows (JCLAW-462) when in a task fire.
     */
    private static Lookup findOriginal(@Nullable Long conversationId, @Nullable Long taskRunId, String handle) {
        // Newest-first: a just-compressed result is usually the one wanted.
        Found hit;
        if (conversationId != null) {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) {
                return new Failed("Error: conversation not found.");
            }
            List<Message> rows = Message.<Message>find(
                    "conversation = ?1 AND role = ?2 ORDER BY id DESC", conv, "tool").fetch();
            hit = matchByHash(rows, m -> m.content, handle, m -> conversationToolName(conv, m));
        } else {
            // JCLAW-462: task fires persist tool turns to task_run_message,
            // not the chat Message/Conversation tables — scan those instead.
            List<TaskRunMessage> rows = TaskRunMessage.<TaskRunMessage>find(
                    "taskRun.id = ?1 AND role = ?2 ORDER BY id DESC", taskRunId, MessageRole.TOOL).fetch();
            hit = matchByHash(rows, m -> m.content, handle, m -> taskRunToolName(taskRunId, m));
        }
        if (hit != null) return hit;
        return new NotFound("No original found for hash \"" + handle + "\" in this "
                + (conversationId != null ? "conversation" : "task run")
                + " (it may have been trimmed from history).");
    }

    /**
     * The newest row whose content SHA-256 starts with {@code handle}, named for the tool that first produced
     * it: a row {@code ccr_retrieve} itself returned is a copy, so an older match that names another tool wins.
     */
    private static <T> @Nullable Found matchByHash(List<T> rows, Function<T, String> content, String handle,
                                                   Function<T, @Nullable String> toolName) {
        Found copy = null;
        for (var r : rows) {
            var c = content.apply(r);
            if (c == null || !ContentHash.sha256Hex(c).startsWith(handle)) continue;
            var name = toolName.apply(r);
            if (!TOOL_NAME.equals(name)) return new Found(c, name);
            if (copy == null) copy = new Found(c, name);
        }
        return copy;
    }

    private static @Nullable String conversationToolName(Conversation conv, Message toolRow) {
        if (toolRow.toolResults == null) return null;
        List<Message> calls = Message.<Message>find(
                "conversation = ?1 AND role = ?2 AND id < ?3 AND toolCalls LIKE ?4 ORDER BY id DESC",
                conv, MessageRole.ASSISTANT.value, toolRow.id, callIdPattern(toolRow.toolResults))
                .fetch(CALL_ROW_CANDIDATES);
        return nameOfCall(calls, m -> m.toolCalls, toolRow.toolResults);
    }

    private static @Nullable String taskRunToolName(@Nullable Long taskRunId, TaskRunMessage toolRow) {
        if (toolRow.toolResults == null) return null;
        List<TaskRunMessage> calls = TaskRunMessage.<TaskRunMessage>find(
                "taskRun.id = ?1 AND role = ?2 AND id < ?3 AND toolCalls LIKE ?4 ORDER BY id DESC",
                taskRunId, MessageRole.ASSISTANT, toolRow.id, callIdPattern(toolRow.toolResults))
                .fetch(CALL_ROW_CANDIDATES);
        return nameOfCall(calls, m -> m.toolCalls, toolRow.toolResults);
    }

    /** A LIKE prefilter only: {@code _} in an id matches any character, so {@link #nameOfCall} confirms the id. */
    private static String callIdPattern(String callId) {
        return "%\"" + callId + "\"%";
    }

    /**
     * The tool named by the newest of {@code rows} whose call carries {@code callId}, paired the way
     * {@link MessageHydrator} pairs a result with its call; each persisted assistant row holds one call.
     */
    private static <T> @Nullable String nameOfCall(List<T> rows, Function<T, @Nullable String> toolCalls,
                                                   String callId) {
        var wanted = MessageHydrator.sanitizeToolCallId(callId);
        for (var r : rows) {
            var json = toolCalls.apply(r);
            if (json == null) continue;
            for (var call : MessageHydrator.parseToolCalls(json)) {
                if (Objects.equals(wanted, call.id()) && call.function() != null) return call.function().name();
            }
        }
        return null;
    }
}
