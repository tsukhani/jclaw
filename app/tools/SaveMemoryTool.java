package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import memory.MemoryCategory;
import memory.MemoryStoreFactory;
import models.Agent;
import services.EventLogger;
import services.Tx;

import java.util.List;
import java.util.Map;

/**
 * save_memory (JCLAW-530): lets the agent durably remember a fact when the user
 * explicitly asks ("remember that …"). Writes straight to the calling agent's
 * memory store with source {@code "manual"}, so an explicit request maps to one
 * deterministic action.
 *
 * <p>Motivation (JCLAW-40 UAT): without this tool, an explicit "remember X"
 * request makes the agent thrash — it hunts for a non-existent create endpoint
 * and falls back to editing the USER.md workspace file, while auto-capture
 * (JCLAW-39) separately stores the same fact (duplication). Facts merely
 * mentioned in passing are still handled by auto-capture; this tool is for the
 * explicit "please remember this" case.
 */
public class SaveMemoryTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "save_memory";

    private static final String ARG_TEXT = "text";
    private static final String ARG_CATEGORY = "category";
    private static final String ARG_IMPORTANCE = "importance";

    private static final String EVENT_CATEGORY = "memory";

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "Utilities"; }

    @Override
    public String icon() { return "book"; }

    @Override
    public String summary() {
        return "Save a durable fact to long-term memory when the user explicitly asks you to remember something.";
    }

    @Override
    public String shortDescription() {
        return "Persist a fact the user asked you to remember into the agent's long-term memory store.";
    }

    @Override
    public String description() {
        return """
                Save a durable fact to your long-term memory so you recall it in future sessions. \
                Use this when the user explicitly asks you to remember something (e.g. "remember that my mother is Martha"). \
                Write the fact as a concise, self-contained statement in the third person (e.g. "The user's mother is Martha"). \
                Optionally set category (one of: core, fact, preference, decision, entity, lesson) and importance (0.0-1.0, higher = more important). \
                Do NOT use this for facts merely mentioned in passing — those are captured automatically — and do not edit \
                workspace files to remember facts; use this tool.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_TEXT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "The fact to remember, as a concise self-contained third-person statement."),
                        ARG_CATEGORY, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, MemoryCategory.labels(),
                                SchemaKeys.DESCRIPTION, "Optional category. Defaults to 'fact'."),
                        ARG_IMPORTANCE, Map.of(SchemaKeys.TYPE, SchemaKeys.NUMBER,
                                SchemaKeys.DESCRIPTION,
                                "Optional importance from 0.0 to 1.0 (higher = more important). Defaults to the category's default.")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_TEXT)
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        if (!args.has(ARG_TEXT) || args.get(ARG_TEXT).isJsonNull()) {
            return "Error: 'text' is required — provide the fact to remember.";
        }
        var text = args.get(ARG_TEXT).getAsString().strip();
        if (text.isEmpty()) {
            return "Error: 'text' is empty — provide the fact to remember.";
        }

        var category = args.has(ARG_CATEGORY) && !args.get(ARG_CATEGORY).isJsonNull()
                ? MemoryCategory.normalize(args.get(ARG_CATEGORY).getAsString())
                : MemoryCategory.FACT.label;
        if (category == null) category = MemoryCategory.FACT.label;
        final var finalCategory = category;

        double importance;
        if (args.has(ARG_IMPORTANCE) && !args.get(ARG_IMPORTANCE).isJsonNull()) {
            try {
                importance = clamp01(args.get(ARG_IMPORTANCE).getAsDouble());
            } catch (Exception _) {
                importance = MemoryCategory.defaultImportance(finalCategory);
            }
        } else {
            importance = MemoryCategory.defaultImportance(finalCategory);
        }
        final double finalImportance = importance;

        try {
            var id = Tx.run(() -> MemoryStoreFactory.get()
                    .store(agent.name, text, finalCategory, finalImportance, "manual"));
            EventLogger.info(EVENT_CATEGORY, agent.name, null,
                    "Saved memory via save_memory (category=%s, importance=%.2f)".formatted(finalCategory, finalImportance));
            return "Saved to memory (id %s, category %s, importance %.2f): %s"
                    .formatted(id, finalCategory, finalImportance, text);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY, agent.name, null,
                    "save_memory failed: %s".formatted(e.getMessage()));
            return "Error saving memory: " + e.getMessage();
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }
}
