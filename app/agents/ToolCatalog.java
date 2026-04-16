package agents;

import models.Agent;

import java.util.ArrayList;
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
        var sb = new StringBuilder();
        sb.append("| Tool | Purpose |\n");
        sb.append("|---|---|\n");
        for (var t : tools) {
            sb.append("| `").append(t.name()).append("` | ")
              .append(t.description() != null ? t.description().replace("\n", " ") : "")
              .append(" |\n");
        }
        return sb.toString();
    }

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
