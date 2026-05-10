package agents;

import models.Agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catalog facade over {@link ToolRegistry}. Provides the live set of registered tool names
 * plus validation helpers that cross-check a skill's declared tool requirements against an
 * agent's enabled tools.
 *
 * <p>There is no hardcoded tool list here — everything derives from what is actually
 * registered at runtime, so adding a new tool in {@code app/tools/} automatically flows
 * through to skill validation and the skill-creator catalog without any manual updates.
 */
public class ToolCatalog {

    /** Every tool name currently registered in {@link ToolRegistry}. */
    public static Set<String> allToolNames() {
        return ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Format the live tool catalog as a markdown table suitable for injection into
     * an agent's system prompt. Filters out any tool that is disabled for this
     * specific agent in {@code AgentToolConfig}, so the LLM only sees — and therefore
     * only declares in skill frontmatter — tools it can actually call. Returns the
     * empty string if no tools are enabled for the agent.
     */
    public static String formatCatalogForPrompt(Agent agent) {
        return formatCatalogForPrompt(ToolRegistry.loadDisabledTools(agent));
    }

    public static String formatCatalogForPrompt(Set<String> disabledForAgent) {
        var tools = ToolRegistry.listTools().stream()
                .filter(t -> !t.isSystem())
                .filter(t -> !disabledForAgent.contains(t.name()))
                .toList();
        if (tools.isEmpty()) return "";
        return renderGroupedCatalog(tools);
    }

    /**
     * Group the supplied tools by {@link ToolRegistry.Tool#category()} and emit a
     * markdown section per category in canonical order (System → Files → Web → Utilities).
     * Categories outside the canonical set are appended at the end in the order the LLM
     * first encountered them, so a future custom category doesn't silently disappear.
     *
     * <p><b>MCP collapse.</b> Tools with a non-null {@link ToolRegistry.Tool#group()}
     * (i.e., MCP-discovered tools sharing a server) are folded into one row per
     * server instead of one row per tool. Without this, an MCP server with 72
     * tools would emit ~10K tokens of per-tool rows that the model has to scan
     * before deciding which to call. The collapsed row mentions the discovery
     * mechanism ({@code list_mcp_tools}) so the model knows how to enumerate
     * the server's individual tools when needed. The actual tool schemas are
     * delivered out-of-band via the lazy-discovery path in
     * {@code AgentRunner}; this catalog is just the operator-readable index.
     */
    private static String renderGroupedCatalog(List<ToolRegistry.Tool> tools) {
        var byCategory = new LinkedHashMap<String, List<ToolRegistry.Tool>>();
        for (var cat : CANONICAL_CATEGORY_ORDER) byCategory.put(cat, new ArrayList<>());
        for (var t : tools) {
            byCategory.computeIfAbsent(t.category(), _ -> new ArrayList<>()).add(t);
        }
        var sb = new StringBuilder();
        var first = true;
        for (var entry : byCategory.entrySet()) {
            var bucket = entry.getValue();
            if (bucket.isEmpty()) continue;
            if (!first) sb.append("\n");
            first = false;
            sb.append("### ").append(entry.getKey()).append("\n");
            sb.append("| Tool | Purpose |\n");
            sb.append("|---|---|\n");
            // Group by `group()` first so MCP servers fold to one row each.
            // Native tools (group=null) keep one row per tool.
            var byGroup = new LinkedHashMap<String, List<ToolRegistry.Tool>>();
            for (var t : bucket) {
                var key = t.group() != null ? t.group() : ("__native__" + t.name());
                byGroup.computeIfAbsent(key, _ -> new ArrayList<>()).add(t);
            }
            for (var groupEntry : byGroup.entrySet()) {
                var members = groupEntry.getValue();
                var first0 = members.get(0);
                if (first0.group() != null) {
                    var server = first0.group();
                    sb.append("| `mcp_").append(server).append("_*` | ")
                      .append(members.size()).append(" tools advertised by the `")
                      .append(server).append("` MCP server. ")
                      .append("Call `list_mcp_tools` with `{\"server\":\"")
                      .append(server).append("\"}` before invoking any of them ")
                      .append("to load their schemas into the conversation. |\n");
                }
                else {
                    var summary = first0.summary() != null ? first0.summary().replace("\n", " ") : "";
                    sb.append("| `").append(first0.name()).append("` | ")
                      .append(summary)
                      .append(" |\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Canonical ordering for the categories defined in
     * {@code frontend/composables/useToolMeta.ts:TOOL_CATEGORIES}. Both surfaces must
     * stay in sync until JCLAW-72 collapses the taxonomy to a single source of truth.
     *
     * <p>{@code "MCP"} (added with JCLAW-33) collects every tool the
     * {@link mcp.McpToolAdapter} contributes — one per MCP-server-advertised
     * tool. It sits last because the prior four categories describe what a
     * tool DOES; "MCP" describes how it's reached, which is a useful filter
     * on the /tools page but a less natural grouping in the system-prompt
     * tool catalog.
     */
    public static final List<String> CANONICAL_CATEGORY_ORDER =
            List.of("System", "Files", "Web", "Utilities", "MCP");

    public record ValidationResult(List<String> unknown, List<String> disabled) {
        public boolean isOk() { return unknown.isEmpty() && disabled.isEmpty(); }
        public List<String> missing() {
            var all = new ArrayList<String>(unknown.size() + disabled.size());
            all.addAll(unknown);
            all.addAll(disabled);
            return all;
        }
    }

    /**
     * Check whether an agent has every tool a skill requires.
     * Returns the lists of unknown (typo/invalid) and disabled-for-this-agent tool names.
     */
    public static ValidationResult validateSkillTools(Agent agent, List<String> requiredTools) {
        return validateSkillTools(ToolRegistry.loadDisabledTools(agent), requiredTools);
    }

    public static ValidationResult validateSkillTools(Set<String> disabledForAgent, List<String> requiredTools) {
        if (requiredTools == null || requiredTools.isEmpty()) {
            return new ValidationResult(List.of(), List.of());
        }

        var knownToolSet = allToolNames();
        var unknown = new ArrayList<String>();
        var knownRequired = new ArrayList<String>();
        for (var t : requiredTools) {
            if (t == null || t.isBlank()) continue;
            var name = t.strip();
            if (!knownToolSet.contains(name)) {
                unknown.add(name);
            } else {
                knownRequired.add(name);
            }
        }

        if (knownRequired.isEmpty()) {
            return new ValidationResult(unknown, List.of());
        }

        var disabled = new ArrayList<String>();
        for (var t : knownRequired) {
            if (disabledForAgent.contains(t)) disabled.add(t);
        }

        return new ValidationResult(unknown, disabled);
    }
}
