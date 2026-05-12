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
        // JCLAW-281: this catalog is now native-tools-only. MCP servers
        // render in their own ## MCP Servers section, built by
        // McpServerCatalog. Filter out anything with a non-null group()
        // (i.e., MCP per-action wrappers and server-level handles) so they
        // don't duplicate across both manifests.
        var tools = ToolRegistry.listTools().stream()
                .filter(t -> t.group() == null)
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
     * <p>JCLAW-281: MCP servers used to collapse here behind a {@code list_mcp_tools}
     * discovery row; they now own a separate ## MCP Servers section via
     * {@link McpServerCatalog}, so this catalog is grouped tools only.
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
            for (var t : bucket) {
                var summary = t.summary() != null ? t.summary().replace("\n", " ") : "";
                sb.append("| `").append(t.name()).append("` | ")
                  .append(summary)
                  .append(" |\n");
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
