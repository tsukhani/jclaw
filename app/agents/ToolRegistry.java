package agents;

import llm.LlmTypes.*;
import models.Agent;

import java.util.ArrayList;
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

    private static final Map<String, Tool> tools = new LinkedHashMap<>();

    public static void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public static void clear() {
        tools.clear();
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
        try {
            return tool.execute(argsJson, agent);
        } catch (Exception e) {
            return "Error executing tool '%s': %s".formatted(toolName, e.getMessage());
        }
    }

    public static List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }
}
