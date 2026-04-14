package jobs;

import models.Agent;
import models.Config;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

/**
 * Seeds default runtime configuration and default agent on first startup.
 * Only writes values that don't already exist.
 */
@OnApplicationStart
public class DefaultConfigJob extends Job<Void> {

    @Override
    public void doJob() {
        dropOrphanedColumns();
        seedProviders();
        seedToolConfig();
        seedDefaultAgent();
        agents.SkillLoader.syncSkillConfigs();
        EventLogger.info("system", "Default configuration seeded");
    }

    /**
     * Drop columns left behind by previous model versions. Hibernate's
     * {@code jpa.ddl=update} adds columns but never drops them; stale
     * NOT NULL columns break inserts when the model no longer sets them.
     */
    private void dropOrphanedColumns() {
        try (var conn = play.db.DB.getDataSource("default").getConnection()) {
            conn.createStatement().execute(
                    "ALTER TABLE agent DROP COLUMN IF EXISTS is_default");
        } catch (Exception e) {
            play.Logger.warn("Schema migration: %s", e.getMessage());
        }
    }

    private void seedProviders() {
        seedIfAbsent("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        seedIfAbsent("provider.ollama-cloud.apiKey", "");
        seedIfAbsent("provider.ollama-cloud.leaderboardUrl", "");
        // Ollama's /v1 endpoint accepts reasoning_effort: low|medium|high (plus "none",
        // which we model on the client side as null). Kimi and Qwen both expose the
        // full gradient; OpenAI-style providers may add "minimal"/"xhigh" for their
        // effort-based models (GPT-5, Grok) — seed those per-model when applicable.
        seedIfAbsent("provider.ollama-cloud.models", """
                [{"id":"qwen3.5","name":"Qwen 3.5","contextWindow":262144,"maxTokens":65535,"supportsThinking":true,"thinkingLevels":["low","medium","high"]},\
                {"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535,"supportsThinking":true,"thinkingLevels":["low","medium","high"]}]""");

        seedIfAbsent("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        seedIfAbsent("provider.openrouter.apiKey", "");
        seedIfAbsent("provider.openrouter.leaderboardUrl", "https://openrouter.ai/rankings");
        seedIfAbsent("provider.openrouter.models", """
                [{"id":"openai/gpt-4.1","name":"GPT-4.1","contextWindow":1047576,"maxTokens":32768},\
                {"id":"anthropic/claude-sonnet-4-6","name":"Claude Sonnet 4.6","contextWindow":200000,"maxTokens":131072},\
                {"id":"google/gemini-3-flash-preview","name":"Gemini 3 Flash","contextWindow":1000000,"maxTokens":65536},\
                {"id":"deepseek/deepseek-v3.2","name":"DeepSeek V3.2","contextWindow":128000,"maxTokens":32768}]""");
    }

    private void seedToolConfig() {
        // Chat settings — values reference the source constants to stay in sync
        seedIfAbsent("chat.maxToolRounds", String.valueOf(agents.AgentRunner.DEFAULT_MAX_TOOL_ROUNDS));
        seedIfAbsent("chat.maxContextMessages", "50");

        // Ollama: how long the model + KV cache stays resident between requests.
        // Passed through as the top-level keep_alive field on every chat request.
        // Longer values improve prefix-reuse hit rates at the cost of GPU memory.
        seedIfAbsent("ollama.keepAlive", "30m");

        // Playwright browser tool
        seedIfAbsent("playwright.enabled", "true");
        seedIfAbsent("playwright.headless", "true");

        // Shell execution tool
        seedIfAbsent("shell.enabled", "true");
        seedIfAbsent("shell.allowlist", tools.ShellExecTool.DEFAULT_ALLOWLIST);
        seedIfAbsent("shell.defaultTimeoutSeconds", "30");
        seedIfAbsent("shell.maxTimeoutSeconds", "300");
        seedIfAbsent("shell.maxOutputBytes", "102400");

        // Web search providers — independent engines, first enabled + keyed one is used.
        // All config lives in the Config DB (editable via Settings UI), not application.conf.
        // See WebSearchTool.SearchProvider for the read paths that consume these values.
        seedIfAbsent("search.exa.enabled", "true");
        seedIfAbsent("search.exa.apiKey", "");
        seedIfAbsent("search.exa.baseUrl", "https://api.exa.ai/search");
        seedIfAbsent("search.exa.priority", "0");
        seedIfAbsent("search.brave.enabled", "true");
        seedIfAbsent("search.brave.apiKey", "");
        seedIfAbsent("search.brave.baseUrl", "https://api.search.brave.com/res/v1/web/search");
        seedIfAbsent("search.brave.priority", "1");
        seedIfAbsent("search.tavily.enabled", "true");
        seedIfAbsent("search.tavily.apiKey", "");
        seedIfAbsent("search.tavily.baseUrl", "https://api.tavily.com/search");
        seedIfAbsent("search.tavily.priority", "2");
        seedIfAbsent("search.perplexity.enabled", "true");
        seedIfAbsent("search.perplexity.apiKey", "");
        seedIfAbsent("search.perplexity.baseUrl", "https://api.perplexity.ai/search");
        seedIfAbsent("search.perplexity.priority", "3");
        // Server-side recency filter for Perplexity's /search endpoint. One of
        // hour|day|week|month|year, or "none" to disable. Defaults to "month"
        // so "latest X" queries don't return year-old snippets — the LLM will
        // not reliably add year/month keywords on its own.
        seedIfAbsent("search.perplexity.recencyFilter", "month");
        seedIfAbsent("search.ollama.enabled", "true");
        seedIfAbsent("search.ollama.apiKey", "");
        seedIfAbsent("search.ollama.baseUrl", "https://ollama.com/api/web_search");
        seedIfAbsent("search.ollama.priority", "4");
        seedIfAbsent("search.felo.enabled", "true");
        seedIfAbsent("search.felo.apiKey", "");
        seedIfAbsent("search.felo.baseUrl", "https://openapi.felo.ai/v2/chat");
        seedIfAbsent("search.felo.priority", "5");

        // Malware scanners — independent hash-lookup APIs, composed under OR.
        // Keys are seeded empty; each scanner is inert until an operator provides its key.
        // All scanner configuration lives in the Config DB (editable via Settings UI),
        // not in application.conf. See MalwareBazaarScanner/MetaDefenderCloudScanner for
        // the read paths that consume these values.
        // MalwareBazaar Auth-Key: free, from https://auth.abuse.ch/
        seedIfAbsent("scanner.malwarebazaar.enabled", "true");
        seedIfAbsent("scanner.malwarebazaar.authKey", "");
        seedIfAbsent("scanner.malwarebazaar.url", "https://mb-api.abuse.ch/api/v1/");
        seedIfAbsent("scanner.malwarebazaar.timeoutMs", "5000");
        // MetaDefender Cloud API key: free 4,000 req/day, from https://metadefender.opswat.com/
        seedIfAbsent("scanner.metadefender.enabled", "true");
        seedIfAbsent("scanner.metadefender.apiKey", "");
        seedIfAbsent("scanner.metadefender.url", "https://api.metadefender.com/v4/");
        seedIfAbsent("scanner.metadefender.timeoutMs", "5000");
    }

    private void seedDefaultAgent() {
        if (Agent.findByName("main") == null) {
            AgentService.create("main", "ollama-cloud", "kimi-k2.5");
            EventLogger.info("agent", "main", null, "Default agent 'main' created");
        }
        // Always reset the built-in agent's workspace to match tracked files
        AgentService.resetWorkspace("main");

        // Bootstrap the skill-creator capability: the main agent must have
        // skill-creator installed in its workspace on first boot so it can
        // promote other skills into the global registry. Idempotent —
        // copyToAgentWorkspace performs an atomic swap; re-running it is a no-op
        // when the workspace copy is already up to date.
        seedSkillCreatorForMain();
    }

    /**
     * Ensure the main agent has {@code skill-creator} installed in its workspace.
     * Runs on every boot so a clean checkout that ships {@code skills/skill-creator/}
     * in the global registry can still reach a state where {@code main} can promote
     * other skills (per {@code SkillPromotionService.SKILL_CREATOR_NAME}).
     *
     * <p>Skipped silently when the global registry doesn't ship skill-creator
     * (e.g. in tests that strip skills) — main is still usable for everything
     * except promotion, and the capability gate will reject promotion attempts
     * with an actionable error.
     */
    private void seedSkillCreatorForMain() {
        var skillName = services.SkillPromotionService.SKILL_CREATOR_NAME;
        var globalSkillMd = agents.SkillLoader.globalSkillsPath()
                .resolve(skillName).resolve("SKILL.md");
        if (!java.nio.file.Files.exists(globalSkillMd)) {
            play.Logger.info("Skill-creator not present in global registry — skipping bootstrap");
            return;
        }
        var main = Agent.findByName("main");
        if (main == null) return;
        try {
            services.SkillPromotionService.copyToAgentWorkspace(main, skillName);
            EventLogger.info("agent", "main", null,
                    "Skill-creator installed for main agent (promotion capability seeded)");
        } catch (java.io.IOException e) {
            play.Logger.warn("Failed to bootstrap skill-creator for main: %s", e.getMessage());
        }
    }

    private void seedIfAbsent(String key, String value) {
        if (ConfigService.get(key) == null) {
            ConfigService.set(key, value);
        }
    }

    /**
     * One-shot rename: if the old key exists and the new one does not, copy the value
     * and delete the old row. Safe to run repeatedly — becomes a no-op once migrated.
     *
     * <p>Retained even when no call sites exist: this is the standard utility for
     * any future Config DB key rename, and {@code AgentSystemTest} exercises it
     * reflectively so the contract stays covered.
     */
    @SuppressWarnings("unused")
    private void renameKeyIfPresent(String oldKey, String newKey) {
        Tx.run(() -> {
            var oldRow = Config.findByKey(oldKey);
            if (oldRow == null) return;
            var newRow = Config.findByKey(newKey);
            if (newRow == null) {
                Config.upsert(newKey, oldRow.value);
                EventLogger.info("system", "Config key migrated: %s → %s".formatted(oldKey, newKey));
            }
            oldRow.delete();
        });
        ConfigService.clearCache();
    }
}
