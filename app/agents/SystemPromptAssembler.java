package agents;

import memory.MemoryStore;
import memory.MemoryStoreFactory;
import models.Agent;
import services.AgentService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Assembles the system prompt for an LLM call by reading workspace files,
 * skills, memories, and environment info.
 */
public class SystemPromptAssembler {

    public record AssembledPrompt(String systemPrompt, List<SkillLoader.SkillInfo> skills) {}

    /**
     * Assemble the full system prompt for an agent, given the user's latest message for memory recall.
     */
    public static AssembledPrompt assemble(Agent agent, String userMessage) {
        var sb = new StringBuilder();

        // 1. AGENT.md content
        appendSection(sb, AgentService.readWorkspaceFile(agent.name, "AGENT.md"));

        // 2. IDENTITY.md content
        appendSection(sb, AgentService.readWorkspaceFile(agent.name, "IDENTITY.md"));

        // 3. USER.md content
        appendSection(sb, AgentService.readWorkspaceFile(agent.name, "USER.md"));

        // 4. Skills
        var skills = SkillLoader.loadSkills(agent.name);
        if (!skills.isEmpty()) {
            sb.append("\n");
            sb.append(SkillLoader.skillMatchingInstructions());
            sb.append("\n");
            sb.append(SkillLoader.formatSkillsXml(skills));
            sb.append("\n");

            // Inject the live tool catalog so skills (especially skill-creator) can reference
            // the authoritative set of tool names instead of hardcoding them in SKILL.md files.
            var catalog = ToolCatalog.formatCatalogForPrompt();
            if (!catalog.isEmpty()) {
                sb.append("\n## Tool Catalog\n");
                sb.append("The complete set of tools that exist in JClaw. When a skill declares a `tools:` list, it MUST use names from this table:\n\n");
                sb.append(catalog);
                sb.append("\n");
            }
        }

        // 5. Workspace file delivery convention
        appendFileDeliveryConvention(sb);

        // 6. Environment info — date-only resolution keeps this stable within a day,
        // so the section does not bust the LLM prompt-prefix cache on every request.
        // Agents that need a precise timestamp can call a dedicated tool.
        sb.append("\n## Environment\n");
        sb.append("- Agent name: %s\n".formatted(agent.name));
        sb.append("- Agent ID: %d\n".formatted(agent.id));
        sb.append("- Current date: %s\n".formatted(LocalDate.now(ZoneId.systemDefault())));
        sb.append("- Timezone: %s\n".formatted(ZoneId.systemDefault().getId()));
        sb.append("- Platform: %s\n".formatted(System.getProperty("os.name", "unknown").toLowerCase()));

        // 7. Recalled memories — placed last so they sit past the stable cacheable prefix.
        // This is the only per-turn-variable section of the system prompt.
        appendMemories(sb, agent, userMessage);

        return new AssembledPrompt(sb.toString(), skills);
    }

    /**
     * Teach every agent how to hand a workspace file to the user. The JClaw
     * chat UI rewrites relative markdown links into download chips that point
     * at the workspace file endpoint, so the correct response to "send me X"
     * is a markdown link, not an inline dump of the file contents.
     */
    private static void appendFileDeliveryConvention(StringBuilder sb) {
        sb.append("\n## Workspace File Delivery\n");
        sb.append("""
                When the user asks you to send, share, download, attach, or deliver a file that exists in the agent workspace (including files you just created with writeFile, writeDocument, or any other tool), respond with a markdown link of the form `[filename](relative/path/in/workspace)`. Do NOT paste the file contents inline.

                The JClaw chat UI automatically turns relative markdown links into downloadable chips that point at the workspace file endpoint, so the user can click once to save the file locally. Pasting contents inline defeats this, makes the chat unreadable for large files, and cannot be downloaded in one click.

                Examples:
                - User: "send me the summary.docx" → You: "Here is your summary: [summary.docx](summary.docx)"
                - User: "I'd like to download the nutrition slides" → You: "Ready to download: [practical-nutrition-slides.html](.agent/diagrams/practical-nutrition-slides.html)"

                This applies to every file type in the workspace: documents, generated HTML, images, scripts, data files. Only paste contents inline if the user explicitly asks to see the code/text in chat.
                """);
    }

    private static void appendSection(StringBuilder sb, String content) {
        if (content != null && !content.isBlank()) {
            sb.append(content.strip());
            sb.append("\n\n");
        }
    }

    private static void appendMemories(StringBuilder sb, Agent agent, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return;

        try {
            var store = MemoryStoreFactory.get();
            var memories = store.search(agent.name, userMessage, 10);
            if (!memories.isEmpty()) {
                sb.append("\n## Relevant Memories\n");
                for (var mem : memories) {
                    sb.append("- ");
                    if (mem.category() != null && !mem.category().isEmpty()) {
                        sb.append("[%s] ".formatted(mem.category()));
                    }
                    sb.append(mem.text());
                    sb.append("\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            // Memory recall failure should not block the agent
            services.EventLogger.warn("agent", "Memory recall failed for agent %s: %s"
                    .formatted(agent.name, e.getMessage()));
        }
    }
}
