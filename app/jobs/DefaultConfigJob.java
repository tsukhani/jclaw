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
        seedProviders();
        seedToolConfig();
        seedDefaultAgent();
        agents.SkillLoader.syncSkillConfigs();
        EventLogger.info("system", "Default configuration seeded");
    }

    private void seedProviders() {
        seedIfAbsent("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        seedIfAbsent("provider.ollama-cloud.apiKey", "");
        seedIfAbsent("provider.ollama-cloud.leaderboardUrl", "");
        seedIfAbsent("provider.ollama-cloud.models", """
                [{"id":"qwen3.5","name":"Qwen 3.5","contextWindow":262144,"maxTokens":65535},\
                {"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535},\
                {"id":"gemma4:31b-cloud","name":"Gemma 4 31B","contextWindow":266000,"maxTokens":32768}]""");

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
        // Chat settings — rename-migrate agent.* → chat.* (idempotent) before seeding defaults
        // so any operator-customized values are preserved across the rename.
        renameKeyIfPresent("agent.maxToolRounds", "chat.maxToolRounds");
        seedIfAbsent("chat.maxToolRounds", "10");
        seedIfAbsent("chat.maxContextMessages", "50");

        // Playwright browser tool — migrate legacy jclaw.tools.playwright.* → playwright.*
        // before seeding defaults so any operator-set values are preserved.
        renameKeyIfPresent("jclaw.tools.playwright.enabled", "playwright.enabled");
        renameKeyIfPresent("jclaw.tools.playwright.headless", "playwright.headless");
        seedIfAbsent("playwright.enabled", "true");
        seedIfAbsent("playwright.headless", "true");

        // Shell execution tool — consolidate legacy jclaw.tools.shell.enabled under the
        // existing shell.* namespace so all shell config lives under one prefix.
        renameKeyIfPresent("jclaw.tools.shell.enabled", "shell.enabled");
        seedIfAbsent("shell.enabled", "true");
        seedIfAbsent("shell.allowlist",
                "git,npm,npx,pnpm,node,python,python3,pip,ls,cat,head,tail,grep,find,wc,sort,uniq,diff,mkdir,cp,mv,echo,curl,wget,jq,tar,zip,unzip");
        seedIfAbsent("shell.defaultTimeoutSeconds", "30");
        seedIfAbsent("shell.maxTimeoutSeconds", "300");
        seedIfAbsent("shell.maxOutputBytes", "102400");

        // Web search providers — independent engines, first enabled + keyed one is used.
        // All config lives in the Config DB (editable via Settings UI), not application.conf.
        // See WebSearchTool.SearchProvider for the read paths that consume these values.
        // Rename-migrate flat {exa,brave,tavily}.apiKey → search.{id}.apiKey before seeding
        // so any operator-set keys are preserved across the naming change.
        renameKeyIfPresent("exa.apiKey", "search.exa.apiKey");
        renameKeyIfPresent("brave.apiKey", "search.brave.apiKey");
        renameKeyIfPresent("tavily.apiKey", "search.tavily.apiKey");
        seedIfAbsent("search.exa.enabled", "true");
        seedIfAbsent("search.exa.apiKey", "");
        seedIfAbsent("search.exa.baseUrl", "https://api.exa.ai/search");
        seedIfAbsent("search.brave.enabled", "true");
        seedIfAbsent("search.brave.apiKey", "");
        seedIfAbsent("search.brave.baseUrl", "https://api.search.brave.com/res/v1/web/search");
        seedIfAbsent("search.tavily.enabled", "true");
        seedIfAbsent("search.tavily.apiKey", "");
        seedIfAbsent("search.tavily.baseUrl", "https://api.tavily.com/search");

        // Malware scanners — independent hash-lookup APIs, composed under OR.
        // Keys are seeded empty; each scanner is inert until an operator provides its key.
        // All scanner configuration lives in the Config DB (editable via Settings UI),
        // not in application.conf. See MalwareBazaarScanner/MetaDefenderCloudScanner for
        // the read paths that consume these values.
        // Migrate legacy skills.scanner.* → scanner.* before seeding so operator-set keys survive.
        renameKeyIfPresent("skills.scanner.malwarebazaar.enabled", "scanner.malwarebazaar.enabled");
        renameKeyIfPresent("skills.scanner.malwarebazaar.authKey", "scanner.malwarebazaar.authKey");
        renameKeyIfPresent("skills.scanner.malwarebazaar.url", "scanner.malwarebazaar.url");
        renameKeyIfPresent("skills.scanner.malwarebazaar.timeoutMs", "scanner.malwarebazaar.timeoutMs");
        renameKeyIfPresent("skills.scanner.metadefender.enabled", "scanner.metadefender.enabled");
        renameKeyIfPresent("skills.scanner.metadefender.apiKey", "scanner.metadefender.apiKey");
        renameKeyIfPresent("skills.scanner.metadefender.url", "scanner.metadefender.url");
        renameKeyIfPresent("skills.scanner.metadefender.timeoutMs", "scanner.metadefender.timeoutMs");
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
            AgentService.create("main", "ollama-cloud", "kimi-k2.5", null);
            EventLogger.info("agent", "main", null, "Default agent 'test' created");
        }
        // Always reset the built-in agent's workspace to match tracked files
        AgentService.resetWorkspace("main");
    }

    private void seedIfAbsent(String key, String value) {
        if (ConfigService.get(key) == null) {
            ConfigService.set(key, value);
        }
    }

    /**
     * One-shot rename: if the old key exists and the new one does not, copy the value
     * and delete the old row. Safe to run repeatedly — becomes a no-op once migrated.
     */
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
