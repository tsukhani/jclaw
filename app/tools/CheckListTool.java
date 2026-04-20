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
                Submit a list of items with status (pending, in_progress, completed) and activeForm text. \
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
        var itemsArray = args.getAsJsonArray("items");

        int inProgressCount = 0;
        for (var el : itemsArray) {
            var item = el.getAsJsonObject();
            var content = item.get("content").getAsString();
            var status = item.get("status").getAsString();
            var activeForm = item.get("activeForm").getAsString();

            if (content.isBlank()) return "Error: All items must have non-blank content.";
            if (activeForm.isBlank()) return "Error: All items must have non-blank activeForm.";
            if ("in_progress".equals(status)) inProgressCount++;
        }

        if (inProgressCount > 1) {
            return "Error: At most one item may be in_progress. Found %d.".formatted(inProgressCount);
        }

        return "Checklist updated successfully (%d items).".formatted(itemsArray.size());
    }
}
