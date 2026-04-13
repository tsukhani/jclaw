package agents;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import llm.LlmTypes.*;
import models.Agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of available tools. Tools are registered at startup and made available
 * to agents. Handles tool execution with error catching.
 * <p>
 * Fully implemented in Group 9 (Tool System). This provides the interface.
 */
public class ToolRegistry {

    public interface Tool {
        String name();
        String description();
        Map<String, Object> parameters();
        String execute(String argsJson, Agent agent);
    }

    private static volatile Map<String, Tool> tools = Map.of();
    private static final Map<String, Tool> registrationBuffer = new LinkedHashMap<>();

    public static void register(Tool tool) {
        registrationBuffer.put(tool.name(), tool);
    }

    public static void clear() {
        registrationBuffer.clear();
    }

    /**
     * Called after all tools are registered to publish an immutable snapshot.
     * Uses LinkedHashMap to preserve registration order — iteration stability matters
     * for LLM prompt caching, which hashes the serialized tools array as part of the prefix.
     */
    public static void publish() {
        tools = Collections.unmodifiableMap(new LinkedHashMap<>(registrationBuffer));
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
    public static List<ToolDef> getToolDefsForAgent(models.Agent agent) {
        return getToolDefsForAgent(loadDisabledTools(agent));
    }

    /** Get tool definitions filtered by a pre-loaded set of disabled tool names. */
    public static List<ToolDef> getToolDefsForAgent(java.util.Set<String> disabledTools) {
        if (disabledTools.isEmpty()) return getToolDefs();
        return tools.values().stream()
                .filter(t -> !disabledTools.contains(t.name()))
                .map(t -> ToolDef.of(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /** Load the set of disabled tool names for an agent (single DB query). */
    public static java.util.Set<String> loadDisabledTools(models.Agent agent) {
        var configs = models.AgentToolConfig.findByAgent(agent);
        var disabled = new java.util.HashSet<String>();
        for (var c : configs) {
            if (!c.enabled) disabled.add(c.toolName);
        }
        return disabled;
    }

    public static List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }
}
