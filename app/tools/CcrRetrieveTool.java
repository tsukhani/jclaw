package tools;

import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Message;
import services.ConversationService;
import services.Tx;
import services.compression.ContentHash;

import java.util.List;
import java.util.Map;

/**
 * CCR retrieve (JCLAW-462). Returns the full, uncompressed original of a
 * content-compressed tool result. When the compression pipeline shrinks a tool
 * output it leaves a marker like
 * {@code [compressed — call ccr_retrieve("ab12cd34ef567890") for the full original]};
 * the LLM passes that handle here when the compressed view (first items +
 * schema + errors) isn't enough.
 *
 * <p>No cache table: the original tool result already lives durably in the
 * {@code Message} row. This tool rescans the active conversation's tool-role
 * messages and matches the handle against the SHA-256 of each content. Scoped
 * to the conversation via {@link ToolContext}.
 */
public class CcrRetrieveTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "ccr_retrieve";

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
        return "Retrieve the full, uncompressed original of a content-compressed tool result by its hash. "
                + "Compressed tool outputs end with a marker like "
                + "[compressed — call ccr_retrieve(\"<hash>\") for the full original]; pass that <hash> here "
                + "when the compressed view (first items, schema and errors) isn't enough.";
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
        if (conversationId == null) {
            return "Error: ccr_retrieve has no active conversation context.";
        }

        return Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) {
                return "Error: conversation not found.";
            }
            // Newest-first: a just-compressed result is usually the one wanted.
            List<Message> toolMsgs = Message.<Message>find(
                    "conversation = ?1 AND role = ?2 ORDER BY id DESC", conv, "tool").fetch();
            for (var m : toolMsgs) {
                if (m.content != null && ContentHash.sha256Hex(m.content).startsWith(handle)) {
                    return m.content;
                }
            }
            return "No original found for hash \"" + handle + "\" in this conversation "
                    + "(it may have been trimmed from history).";
        });
    }
}
