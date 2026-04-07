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
        if (models.Skill.findByName("skill-creator") != null) return;

        var skill = new models.Skill();
        skill.name = "skill-creator";
        skill.description = "Create new skills for the JClaw skills registry. Guides you through defining skill name, description, and content.";
        skill.isGlobal = false;
        skill.content = """
# Skill Creator

You are helping the user create a new skill for the JClaw skills registry.

## Process

1. **Ask** the user what the skill should do. Get a clear name and description.
2. **Draft** the skill content in markdown format. A good skill includes:
   - A clear title and purpose
   - Step-by-step instructions the agent should follow
   - Which tools to use and how
   - Example inputs and expected outputs
   - Edge cases and error handling
3. **Create** the skill using the skill_manager tool:
   - action: "createSkill"
   - name: a short kebab-case name (e.g., "code-reviewer", "meeting-notes")
   - description: a one-line summary
   - content: the full markdown instructions
   - isGlobal: false (let the admin decide which agents get it)
4. **Confirm** creation and suggest which agents might benefit from the skill.

## Guidelines

- Keep skill names short and descriptive (kebab-case)
- Write clear, actionable instructions that an LLM can follow
- Reference available tools by name (web_fetch, filesystem, task_manager, checklist)
- Skills should be focused on one task — create multiple skills for complex workflows
- Always set isGlobal to false — the admin will assign it to appropriate agents
""";
        skill.save();

        // Assign to the built-in test agent
        var testAgent = models.Agent.findByName("test");
        if (testAgent != null) {
            var assignment = new models.AgentSkill();
            assignment.agent = testAgent;
            assignment.skill = skill;
            assignment.save();
        }

        EventLogger.info("skill", null, null, "Skill-creator skill seeded and assigned to test agent");
    }

    private void seedIfAbsent(String key, String value) {
        if (ConfigService.get(key) == null) {
            ConfigService.set(key, value);
        }
    }
}
