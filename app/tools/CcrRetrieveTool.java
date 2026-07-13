package tools;

import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Message;
import models.MessageRole;
import models.TaskRunMessage;
import services.CompressionMetrics;
import services.ConversationService;
import services.Tx;
import services.compression.ContentHash;

import java.util.List;
import java.util.Map;
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

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("retrieve",
                "Return the full original tool result for a compression hash"));
    }

    @Override
    public String name() {
        return TOOL_NAME;
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

        var result = Tx.run(() -> findOriginal(conversationId, taskRunId, handle));
        // JCLAW-467: record the hit/miss for the CCR cache-hit-rate metric. System
        // errors (no context / conversation not found) aren't a retrieval outcome.
        if (!result.startsWith("Error:")) {
            CompressionMetrics.recordCcrRetrieval(!result.startsWith("No original found"));
        }
        return result;
    }

    /**
     * Locate the original tool-output whose content hash starts with {@code handle},
     * scanning newest-first — chat {@link Message} rows when in a conversation, or
     * {@link TaskRunMessage} rows (JCLAW-462) when in a task fire. Returns the content,
     * or an explanatory "Error:"/"No original found" string.
     */
    private static String findOriginal(Long conversationId, Long taskRunId, String handle) {
        // Newest-first: a just-compressed result is usually the one wanted.
        if (conversationId != null) {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) {
                return "Error: conversation not found.";
            }
            List<Message> rows = Message.<Message>find(
                    "conversation = ?1 AND role = ?2 ORDER BY id DESC", conv, "tool").fetch();
            var hit = matchByHash(rows, m -> m.content, handle);
            if (hit != null) return hit;
        } else {
            // JCLAW-462: task fires persist tool turns to task_run_message,
            // not the chat Message/Conversation tables — scan those instead.
            List<TaskRunMessage> rows = TaskRunMessage.<TaskRunMessage>find(
                    "taskRun.id = ?1 AND role = ?2 ORDER BY id DESC", taskRunId, MessageRole.TOOL).fetch();
            var hit = matchByHash(rows, m -> m.content, handle);
            if (hit != null) return hit;
        }
        return "No original found for hash \"" + handle + "\" in this "
                + (conversationId != null ? "conversation" : "task run")
                + " (it may have been trimmed from history).";
    }

    /** First row (in iteration order) whose content SHA-256 starts with {@code handle}, or null. */
    private static <T> String matchByHash(List<T> rows, Function<T, String> content, String handle) {
        for (var r : rows) {
            var c = content.apply(r);
            if (c != null && ContentHash.sha256Hex(c).startsWith(handle)) {
                return c;
            }
        }
        return null;
    }
}
