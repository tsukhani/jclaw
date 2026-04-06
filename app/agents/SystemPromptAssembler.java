package agents;

import memory.MemoryStore;
import memory.MemoryStoreFactory;
import models.Agent;
import services.AgentService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        }

        // 5. Recalled memories
        appendMemories(sb, agent, userMessage);

        // 6. Environment info
        sb.append("\n## Environment\n");
        sb.append("- Current time: %s\n".formatted(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                        .format(Instant.now().atZone(ZoneId.systemDefault()))));
        sb.append("- Timezone: %s\n".formatted(ZoneId.systemDefault().getId()));
        sb.append("- Platform: %s\n".formatted(System.getProperty("os.name", "unknown").toLowerCase()));

        return new AssembledPrompt(sb.toString(), skills);
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
