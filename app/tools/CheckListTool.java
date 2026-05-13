package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;

import java.util.List;
import java.util.Map;

public class CheckListTool implements ToolRegistry.Tool {

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;

    public record CheckListItem(String content, String status, String activeForm) {}

    @Override
    public String name() { return "checklist"; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "check"; }

    @Override
    public String shortDescription() {
        return "Create and manage structured checklists to track multi-step work in progress.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("update", "Submit a checklist with items and statuses; at most one item may be in_progress at a time")
        );
    }

    @Override
    public String description() {
        return """
                Create and manage a structured checklist for tracking multi-step work. \
                Submit a list of items; each item requires three fields: \
                `content` (imperative form, e.g. "Run tests"), \
                `status` (one of pending, in_progress, completed), and \
                `activeForm` (present-progressive form, e.g. "Running tests"). \
                At most one item may be in_progress at a time (zero is also valid, e.g. before starting or after finishing).""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "items", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of("type", "string"),
                                                "status", Map.of("type", "string",
                                                        "enum", List.of("pending", "in_progress", "completed")),
                                                "activeForm", Map.of("type", "string")
                                        ),
                                        "required", List.of("content", "status", "activeForm")
                                )
                        )
                ),
                "required", List.of("items")
        );
    }

    /** Pure validation — returns a string, mutates no state. Safe for any
     *  number of parallel invocations. */
    @Override public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        if (!args.has("items") || !args.get("items").isJsonArray()) {
            return "Error: `items` is required and must be an array.";
        }
        var itemsArray = args.getAsJsonArray("items");

        int inProgressCount = 0;
        for (int i = 0; i < itemsArray.size(); i++) {
            var el = itemsArray.get(i);
            if (!el.isJsonObject()) {
                return "Error: Item %d must be an object.".formatted(i);
            }
            var item = el.getAsJsonObject();
            String content = optString(item, "content");
            if (content == null) return "Error: Item %d is missing required field `content`.".formatted(i);
            String status = optString(item, "status");
            if (status == null) return "Error: Item %d is missing required field `status`.".formatted(i);
            String activeForm = optString(item, "activeForm");
            if (activeForm == null) return "Error: Item %d is missing required field `activeForm`.".formatted(i);

            if (content.isBlank()) return "Error: All items must have non-blank content.";
            if (activeForm.isBlank()) return "Error: All items must have non-blank activeForm.";
            if ("in_progress".equals(status)) inProgressCount++;
        }

        if (inProgressCount > 1) {
            return "Error: At most one item may be in_progress. Found %d.".formatted(inProgressCount);
        }

        return "Checklist updated successfully (%d items).".formatted(itemsArray.size());
    }

    private static String optString(com.google.gson.JsonObject item, String key) {
        var el = item.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }
}
