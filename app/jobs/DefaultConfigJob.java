package jobs;

import models.Agent;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;

/**
 * Seeds default runtime configuration and default agent on first startup.
 * Only writes values that don't already exist.
 */
@OnApplicationStart
public class DefaultConfigJob extends Job<Void> {

    @Override
    public void doJob() {
        seedProviders();
        seedDefaultAgent();
        seedSkillCreator();
        EventLogger.info("system", "Default configuration seeded");
    }

    private void seedProviders() {
        seedIfAbsent("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        seedIfAbsent("provider.ollama-cloud.apiKey", "");
        seedIfAbsent("provider.ollama-cloud.models", """
                [{"id":"qwen3.5","name":"Qwen 3.5","contextWindow":262144,"maxTokens":65535},\
                {"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535},\
                {"id":"gemma4:31b-cloud","name":"Gemma 4 31B","contextWindow":266000,"maxTokens":32768}]""");

        seedIfAbsent("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        seedIfAbsent("provider.openrouter.apiKey", "");
        seedIfAbsent("provider.openrouter.models", """
                [{"id":"openai/gpt-4.1","name":"GPT-4.1","contextWindow":1047576,"maxTokens":32768},\
                {"id":"anthropic/claude-sonnet-4-6","name":"Claude Sonnet 4.6","contextWindow":200000,"maxTokens":131072},\
                {"id":"google/gemini-3-flash-preview","name":"Gemini 3 Flash","contextWindow":1000000,"maxTokens":65536},\
                {"id":"deepseek/deepseek-v3.2","name":"DeepSeek V3.2","contextWindow":128000,"maxTokens":32768}]""");
    }

    private void seedDefaultAgent() {
        if (Agent.findByName("test") == null) {
            AgentService.create("test", "ollama-cloud", "kimi-k2.5", true);
            EventLogger.info("agent", "test", null, "Default agent 'test' created");
        }
        // Always reset the built-in agent's workspace to match tracked files
        AgentService.resetWorkspace("test");
    }

    private void seedSkillCreator() {
        if (play.Play.runningInTestMode()) return;
        var skillDir = agents.SkillLoader.globalSkillsPath().resolve("skill-creator");
        var skillFile = skillDir.resolve("SKILL.md");
        try {
            java.nio.file.Files.createDirectories(skillDir);
            // Always write (reset on startup, same as workspace/test/)
            java.nio.file.Files.writeString(skillFile, """
---
name: skill-creator
description: Create new skills for the agent's workspace. Guides you through defining skill name, description, and content.
---

# Skill Creator

You are helping the user create a new skill in your workspace.

## Process

1. **Ask** the user what the skill should do. Get a clear name and description.
2. **Draft** the skill content in markdown format. A good skill includes:
   - YAML frontmatter with `name` and `description` fields
   - A clear title and purpose
   - Step-by-step instructions the agent should follow
   - Which tools to use and how
   - Example inputs and expected outputs
   - Edge cases and error handling
3. **Create** the skill using the filesystem tool:
   - Create a directory: `skills/{skill-name}/`
   - Write the SKILL.md file: `skills/{skill-name}/SKILL.md`
   - The file must start with YAML frontmatter between `---` markers
4. **Confirm** creation and explain the skill is now available in your workspace.

## Example SKILL.md format

```markdown
---
name: code-reviewer
description: Review code for bugs, security issues, and best practices
---

# Code Reviewer

When asked to review code:

1. Read the file using the filesystem tool
2. Analyze for:
   - Bugs and logic errors
   - Security vulnerabilities
   - Performance issues
   - Code style and readability
3. Provide a structured review with severity levels
```

## Guidelines

- Keep skill names short and descriptive (kebab-case)
- Write clear, actionable instructions that an LLM can follow
- Reference available tools by name (web_fetch, filesystem, task_manager, checklist)
- Skills should be focused on one task — create multiple skills for complex workflows
- Skills you create go into your workspace and are available only to you
- An admin can promote a skill to global by copying it to the shared skills directory
""");
            EventLogger.info("skill", null, null, "Skill-creator skill seeded at %s".formatted(skillFile));
        } catch (java.io.IOException e) {
            EventLogger.error("skill", "Failed to seed skill-creator: %s".formatted(e.getMessage()));
        }
    }

    private void seedIfAbsent(String key, String value) {
        if (ConfigService.get(key) == null) {
            ConfigService.set(key, value);
        }
    }
}
