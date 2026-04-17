package agents;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import llm.LlmTypes.*;
import models.Agent;
import models.AgentToolConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available tools. Tools are registered at startup and made available
 * to agents. Handles tool execution with error catching.
 * <p>
 * Thread-safe: callers build a list of tools locally and publish atomically
 * via {@link #publish(List)}. No shared mutable buffer is needed.
 */
public class ToolRegistry {

    public interface Tool {
        String name();
        String description();
        Map<String, Object> parameters();
        String execute(String argsJson, Agent agent);

        /** Short one-line summary for the system prompt tool catalog. Defaults to
         *  the full description, but tools with multi-sentence descriptions should
         *  override this to prevent the LLM from misreading action names or internal
         *  details as top-level tool names. */
        default String summary() { return description(); }

        /** System tools are always available, cannot be disabled by users, and are
         *  hidden from the system prompt's tool catalog (the LLM can still invoke
         *  them via the tool schema — they just aren't advertised to users). */
        default boolean isSystem() { return false; }

        /** Taxonomy bucket used to group tools in the system-prompt Tool Catalog.
         *  Must be one of {@code "System"}, {@code "Files"}, {@code "Web"},
         *  {@code "Utilities"} — matching the {@code CANONICAL_CATEGORY_ORDER}
         *  list exposed by {@link ToolCatalog}. Defaults to {@code "Utilities"}
         *  so a tool that forgets to override still renders somewhere sensible. */
        default String category() { return "Utilities"; }

        /** Semantic icon key consumed by the admin UI's SVG dictionary (e.g.
         *  {@code "terminal"}, {@code "folder"}, {@code "globe"}). The backend
         *  does not know what pixels render from this — it only emits the key and
         *  the frontend resolves it to an SVG {@code <path>}. Defaults to
         *  {@code "wrench"}, the generic fallback icon. */
        default String icon() { return "wrench"; }

        /** User-facing blurb rendered in the admin UI tool cards. Richer than
         *  the function-calling {@link #description()} that goes to the LLM —
         *  describes the tool's purpose for a human browsing the /tools page.
         *  Defaults to {@link #summary()}. */
        default String shortDescription() { return summary(); }

        /** Enumerated actions this tool exposes. The admin UI renders these as a
         *  "Show actions" disclosure under each tool card; the names typically
         *  match the {@code action} field in {@link #parameters()}. Empty by
         *  default for single-action tools that would repeat their own name. */
        default java.util.List<ToolAction> actions() { return java.util.List.of(); }

        /** Runtime config key (in {@code ConfigService}) that must be truthy for
         *  this tool to be usable. The admin UI gates the "Enable" toggle on
         *  this. {@code null} means no runtime dependency. */
        default String requiresConfig() { return null; }
    }

    private static volatile Map<String, Tool> tools = Map.of();

    /**
     * Atomically publish a new tool set built by the caller. Uses LinkedHashMap
     * to preserve registration order — iteration stability matters for LLM
     * prompt caching, which hashes the serialized tools array as part of the prefix.
     */
    public static void publish(List<Tool> toolList) {
        var map = new LinkedHashMap<String, Tool>();
        for (var tool : toolList) {
            map.put(tool.name(), tool);
        }
        tools = Collections.unmodifiableMap(map);
    }

    public static List<ToolDef> getToolDefs() {
        return tools.values().stream()
                .map(t -> ToolDef.of(t.name(), t.description(), t.parameters()))
                .toList();
    }

    public static String execute(String toolName, String argsJson, Agent agent) {
        var tool = tools.get(toolName);
        if (tool == null) {
            return "Error: Unknown tool '%s'".formatted(toolName);
        }
        // Defense-in-depth: validate the args JSON is parseable BEFORE invoking the
        // tool. When the LLM hits its output-token budget mid-tool-call, the
        // streaming accumulator emits a truncated arguments string (e.g. for
        // writeDocument: "{\"action\":\"writeDocument\",\"path\":\"foo.docx\"" — no
        // closing brace, no content field). The per-round truncation guards in
        // AgentRunner catch finish_reason="length"/"max_tokens", but some providers
        // (notably OpenRouter's Bedrock route for Anthropic models) can end a
        // stream without a clear finish_reason, letting the malformed call reach
        // the tool. Gson then throws EOFException deep inside the tool and the LLM
        // sees a cryptic "End of input at line 1 column N" error that doesn't
        // teach it to retry with smaller content. Pre-validate here and return a
        // message the LLM can actually act on.
        if (argsJson == null || argsJson.isEmpty()) {
            return "Error: Tool '%s' received empty arguments. The model's response was likely truncated before the tool call completed. Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call."
                    .formatted(toolName);
        }
        try {
            JsonParser.parseString(argsJson);
        } catch (JsonSyntaxException e) {
            return "Error: Tool '%s' received malformed arguments (likely truncated by the model's output token limit). Try breaking the task into smaller steps — for example, write large files in multiple smaller operations instead of one big call. Parse error: %s"
                    .formatted(toolName, e.getMessage());
        }
        try {
            return tool.execute(argsJson, agent);
        } catch (Exception e) {
            return "Error executing tool '%s': %s".formatted(toolName, e.getMessage());
        }
    }

    /** Get tool definitions filtered by agent's tool configuration. */
    public static List<ToolDef> getToolDefsForAgent(Agent agent) {
        return getToolDefsForAgent(loadDisabledTools(agent));
    }

    /** Get tool definitions filtered by a pre-loaded set of disabled tool names. */
    public static List<ToolDef> getToolDefsForAgent(Set<String> disabledTools) {
        if (disabledTools.isEmpty()) return getToolDefs();
        return tools.values().stream()
                .filter(t -> !disabledTools.contains(t.name()))
                .map(t -> ToolDef.of(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * Per-agent cache of disabled-tool names. The write path for AgentToolConfig is
     * a single endpoint ({@link controllers.ApiToolsController}) which calls
     * {@link #invalidateDisabledToolsCache} after every toggle. Every other caller
     * is a read, and each streaming turn reads once, so caching here eliminates a
     * DB round-trip per turn. Keyed by agent ID; values are unmodifiable to guard
     * against callers accidentally mutating the cached set.
     */
    private static final ConcurrentHashMap<Long, Set<String>> DISABLED_TOOLS_CACHE = new ConcurrentHashMap<>();

    /**
     * Load the set of disabled tool names for an agent. Cached per agent; the cache
     * is invalidated whenever an {@link models.AgentToolConfig} row is written via
     * {@link #invalidateDisabledToolsCache}.
     */
    public static Set<String> loadDisabledTools(Agent agent) {
        if (agent == null || agent.id == null) {
            // Unsaved agents have no configs; treat as "nothing disabled."
            return Set.of();
        }
        return DISABLED_TOOLS_CACHE.computeIfAbsent(agent.id, _ -> {
            var configs = AgentToolConfig.findByAgent(agent);
            var disabled = new HashSet<String>();
            for (var c : configs) {
                if (!c.enabled) disabled.add(c.toolName);
            }
            return Collections.unmodifiableSet(disabled);
        });
    }

    /** Invalidate the cached disabled-tools set for a specific agent. */
    public static void invalidateDisabledToolsCache(Agent agent) {
        if (agent != null && agent.id != null) {
            DISABLED_TOOLS_CACHE.remove(agent.id);
        }
    }

    /** Clear the entire disabled-tools cache. Used by tests and admin tooling. */
    public static void clearDisabledToolsCache() {
        DISABLED_TOOLS_CACHE.clear();
    }

    public static List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }
}
