package jobs;

import models.Agent;
import models.Config;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import services.scanners.ScannerRegistry;

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
        seedDispatcherTuning();
        seedTranscription();
        agents.SkillLoader.syncSkillConfigs();
        utils.HttpFactories.applyDispatcherConfig();
        // JCLAW-163: prime the ffmpeg cache so the Settings UI can render a
        // "ffmpeg missing" banner without paying the probe cost on first
        // page load. Cheap (~ms when ffmpeg is present, ~tens of ms when not).
        services.transcription.FfmpegProbe.probe();
        EventLogger.info("system", "Default configuration seeded");
    }

    /**
     * JCLAW-163: seed the local Whisper model selection so the Settings UI
     * has a defined default to display, and the writer (JCLAW-165) has a
     * non-null model to ensure-available before the first audio attachment
     * lands.
     */
    private void seedTranscription() {
        // JCLAW-164: provider radio defaults to whisper-local — the only
        // shipped engine today (cloud backends arrive in JCLAW-162). Switch
        // via Settings → Transcription once an OpenRouter / OpenAI API key
        // is configured.
        seedIfAbsent("transcription.provider", "whisper-local");
        seedIfAbsent("transcription.localModel",
                services.transcription.WhisperModel.DEFAULT.id());
    }

    /**
     * Auto-tune the OkHttp LLM dispatcher caps based on host CPU. The static
     * defaults in {@link utils.HttpFactories} (64 per host, 128 total) are
     * a safe floor for any machine; on bigger hosts we want more headroom.
     * Formula is {@code clamp(8 * cores, 64, 256)} per host with total set
     * to twice that — 8 in-flight per core is OkHttp's typical sizing for
     * I/O-bound work, the floor matches the static default so no host
     * loses capacity, and the ceiling caps socket/buffer footprint.
     *
     * <p>Only seeds if the key is absent so an operator override via
     * Settings persists across restarts.
     */
    private void seedDispatcherTuning() {
        renameKeyIfPresent("provider.llm.dispatcher.maxRequestsPerHost", "dispatcher.llm.maxRequestsPerHost");
        renameKeyIfPresent("provider.llm.dispatcher.maxRequests", "dispatcher.llm.maxRequests");
        int cores = Runtime.getRuntime().availableProcessors();
        int defaultPerHost = Math.max(64, Math.min(256, 8 * cores));
        int defaultMax = 2 * defaultPerHost;
        seedIfAbsent("dispatcher.llm.maxRequestsPerHost", String.valueOf(defaultPerHost));
        seedIfAbsent("dispatcher.llm.maxRequests", String.valueOf(defaultMax));
    }

    /**
     * Drop columns left behind by previous model versions, and add columns
     * introduced in this release that Hibernate's {@code jpa.ddl=update}
     * wouldn't patch onto a hot-reloaded dev server (DDL only runs at
     * SessionFactory init, not on class reload). Both paths use the
     * idempotent {@code IF (NOT) EXISTS} form so a server that's already in
     * the right state does nothing.
     *
     * <p>Stale NOT NULL columns also break inserts when the model stops
     * setting them — that's the reason the pre-existing {@code is_default}
     * drop stays here, the reason we drop {@code title_generated} from
     * the v0.9.6 iteration of the title-regeneration gate, and the reason
     * {@code title_generation_count} is dropped now that conversation-title
     * regeneration has been removed entirely.
     */
    private void dropOrphanedColumns() {
        try (var conn = play.db.DB.getDataSource("default").getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE agent DROP COLUMN IF EXISTS is_default");
            stmt.execute("ALTER TABLE conversation DROP COLUMN IF EXISTS title_generated");
            stmt.execute("ALTER TABLE conversation DROP COLUMN IF EXISTS title_generation_count");
            stmt.execute("ALTER TABLE message ADD COLUMN IF NOT EXISTS reasoning TEXT");
            // JCLAW-95: Telegram streaming checkpoint. Non-null rows indicate
            // a placeholder message left dangling by a prior JVM crash — the
            // recovery job edits them on startup.
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS active_stream_message_id INT");
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS active_stream_chat_id VARCHAR(255)");
            // JCLAW-26: /reset watermark. Messages older than this timestamp
            // are excluded from the LLM context window. Null = no reset has
            // occurred, full history is eligible.
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS context_since TIMESTAMP");
            // JCLAW-38: session-compaction watermark. Messages older than
            // this timestamp have been summarized into a session_compaction
            // row; loadRecentMessages skips them on subsequent turns. Null
            // = conversation has never been compacted. Independent of
            // context_since so /reset and compaction compose cleanly.
            stmt.execute("ALTER TABLE conversation ADD COLUMN IF NOT EXISTS compaction_since TIMESTAMP");
            // JCLAW-38: session_compaction history table. The current
            // summary for a conversation is the most recent row by
            // compacted_at. Older rows are retained as an audit trail of
            // how the conversation was progressively summarized.
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS session_compaction (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        conversation_id BIGINT NOT NULL,
                        turn_count INT NOT NULL,
                        summary_tokens INT NOT NULL,
                        model VARCHAR(255) NOT NULL,
                        summary CLOB NOT NULL,
                        compacted_at TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        CONSTRAINT fk_session_compaction_conversation
                            FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
                    )""");
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_session_compaction_conversation
                        ON session_compaction(conversation_id)""");
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_session_compaction_conv_compacted_at
                        ON session_compaction(conversation_id, compacted_at)""");
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

        // JCLAW-178: ollama-local routes to OllamaProvider via the substring
        // match in LlmProvider.forConfig — no new provider class needed. The
        // apiKey is a non-blank sentinel because ProviderRegistry.refreshInner
        // skips rows with a blank apiKey, and local Ollama ignores the
        // Authorization header. models is empty so operators populate it from
        // the Settings UI's discovery flow against their own pulled models.
        seedIfAbsent("provider.ollama-local.baseUrl", "http://localhost:11434/v1");
        seedIfAbsent("provider.ollama-local.apiKey", "ollama-local");
        seedIfAbsent("provider.ollama-local.models", "[]");

        // JCLAW-182: lm-studio falls through to OpenAiProvider via the factory
        // default — LM Studio speaks OpenAI-compatible /v1/chat/completions on
        // localhost:1234 and accepts any non-blank Authorization. Same
        // sentinel-apiKey trick as ollama-local so ProviderRegistry registers
        // the row out of the box.
        seedIfAbsent("provider.lm-studio.baseUrl", "http://localhost:1234/v1");
        seedIfAbsent("provider.lm-studio.apiKey", "lm-studio");
        seedIfAbsent("provider.lm-studio.models", "[]");

        seedIfAbsent("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        seedIfAbsent("provider.openrouter.apiKey", "");
        seedIfAbsent("provider.openrouter.leaderboardUrl", "https://openrouter.ai/rankings");
        seedIfAbsent("provider.openrouter.models", """
                [{"id":"openai/gpt-4.1","name":"GPT-4.1","contextWindow":1047576,"maxTokens":32768},\
                {"id":"anthropic/claude-sonnet-4-6","name":"Claude Sonnet 4.6","contextWindow":200000,"maxTokens":131072},\
                {"id":"google/gemini-3-flash-preview","name":"Gemini 3 Flash","contextWindow":1000000,"maxTokens":65536},\
                {"id":"deepseek/deepseek-v3.2","name":"DeepSeek V3.2","contextWindow":128000,"maxTokens":32768}]""");

        // JCLAW-160: OpenAI as a first-class provider, on equal footing with
        // OpenRouter. apiKey is seeded blank so ProviderRegistry.refreshInner
        // skips the row until an operator pastes a key in Settings; once
        // keyed the row enables direct-OpenAI chat plus the OpenAI Whisper
        // transcription backend (JCLAW-162) without proxying through
        // OpenRouter. No leaderboard or curated model list — the Settings
        // UI's discovery flow against /v1/models populates the catalog.
        seedIfAbsent("provider.openai.baseUrl", "https://api.openai.com/v1");
        seedIfAbsent("provider.openai.apiKey", "");

        // Together AI: OpenAI-shape /v1/chat/completions plus Together's
        // own {reasoning: {enabled: bool}} thinking knob. Routes through
        // TogetherAiProvider via the "together" substring match in
        // LlmProvider.forConfig. apiKey blank → row stays inactive until
        // an operator pastes a key in Settings. No leaderboard URL
        // (Together has no public ranking endpoint); empty model list
        // so operators populate via the Settings UI's /v1/models
        // discovery flow against their own enabled models.
        seedIfAbsent("provider.together.baseUrl", "https://api.together.xyz/v1");
        seedIfAbsent("provider.together.apiKey", "");
        seedIfAbsent("provider.together.leaderboardUrl", "");
        seedIfAbsent("provider.together.models", "[]");
    }

    private void seedToolConfig() {
        // Chat settings — values reference the source constants to stay in sync
        seedIfAbsent("chat.maxToolRounds", String.valueOf(agents.AgentRunner.DEFAULT_MAX_TOOL_ROUNDS));
        seedIfAbsent("chat.maxContextMessages", "50");

        // Ollama: how long the model + KV cache stays resident between requests.
        // Passed through as the top-level keep_alive field on every chat request.
        // Longer values improve prefix-reuse hit rates at the cost of GPU memory.
        seedIfAbsent("ollama.keepAlive", "30m");

        // JCLAW-172: playwright.enabled / playwright.headless / shell.enabled
        // are gone — the browser is always headless and both tools register
        // unconditionally; per-agent enable/disable still happens via the
        // Tools page (AgentToolConfig). The shell allowlist + timeout knobs
        // remain useful operator config and stay seeded.

        // OCR backends. The parse-time tunables (languages, timeout, pdf
        // strategy) stay in conf/application.conf because they're read once
        // per parse and don't need a UI; the user-facing on/off lives here
        // so the Settings page OCR section can flip it. The actual binary's
        // presence is detected at boot by jobs.TesseractProbeJob — when the
        // probe says missing, the Settings UI greys out the toggle even if
        // this row is "true", so the stored value tracks user intent rather
        // than runtime availability.
        seedIfAbsent("ocr.tesseract.enabled", "true");

        // Shell execution tool — operator-tunable knobs only.
        seedIfAbsent("shell.allowlist", tools.ShellExecTool.DEFAULT_ALLOWLIST);
        seedIfAbsent("shell.defaultTimeoutSeconds", "30");
        seedIfAbsent("shell.maxTimeoutSeconds", "300");
        seedIfAbsent("shell.maxOutputBytes", "102400");

        // Web search providers — independent engines, first enabled + keyed one is used.
        // All config lives in the Config DB (editable via Settings UI), not application.conf.
        // See WebSearchTool.SearchProvider for the read paths that consume these values.
        //
        // First-run defaults: every provider is seeded `enabled=false` because each
        // requires an operator-supplied API key (or, for Ollama, an account/key bound
        // to the same endpoint as the chat provider). Ordering by priority is
        // Ollama → Exa → Perplexity → Brave → Tavily → Felo, matching the seed
        // block below; the operator enables the ones they have keys for via the
        // Settings UI, and the first enabled+keyed provider in priority order wins.
        // seedIfAbsent only writes when the key is absent, so this change is a
        // no-op against existing installations — operator-tuned values are preserved.
        seedIfAbsent("search.ollama.enabled", "false");
        seedIfAbsent("search.ollama.apiKey", "");
        seedIfAbsent("search.ollama.baseUrl", "https://ollama.com/api/web_search");
        seedIfAbsent("search.ollama.priority", "0");
        seedIfAbsent("search.exa.enabled", "false");
        seedIfAbsent("search.exa.apiKey", "");
        seedIfAbsent("search.exa.baseUrl", "https://api.exa.ai/search");
        seedIfAbsent("search.exa.priority", "1");
        seedIfAbsent("search.perplexity.enabled", "false");
        seedIfAbsent("search.perplexity.apiKey", "");
        seedIfAbsent("search.perplexity.baseUrl", "https://api.perplexity.ai/search");
        seedIfAbsent("search.perplexity.priority", "2");
        // Server-side recency filter for Perplexity's /search endpoint. One of
        // hour|day|week|month|year, or "none" to disable. Defaults to "month"
        // so "latest X" queries don't return year-old snippets — the LLM will
        // not reliably add year/month keywords on its own.
        seedIfAbsent("search.perplexity.recencyFilter", "month");
        seedIfAbsent("search.brave.enabled", "false");
        seedIfAbsent("search.brave.apiKey", "");
        seedIfAbsent("search.brave.baseUrl", "https://api.search.brave.com/res/v1/web/search");
        seedIfAbsent("search.brave.priority", "3");
        seedIfAbsent("search.tavily.enabled", "false");
        seedIfAbsent("search.tavily.apiKey", "");
        seedIfAbsent("search.tavily.baseUrl", "https://api.tavily.com/search");
        seedIfAbsent("search.tavily.priority", "4");
        seedIfAbsent("search.felo.enabled", "false");
        seedIfAbsent("search.felo.apiKey", "");
        seedIfAbsent("search.felo.baseUrl", "https://openapi.felo.ai/v2/chat");
        seedIfAbsent("search.felo.priority", "5");

        // Malware scanners — independent hash-lookup APIs, composed under OR.
        // Keys are seeded empty; each scanner is inert until an operator provides its key.
        // All scanner defaults live in ScannerRegistry so registering a scanner
        // and seeding its config stay in one place.
        for (var entry : ScannerRegistry.defaultConfig()) {
            seedIfAbsent(entry.key(), entry.value());
        }
    }

    private void seedDefaultAgent() {
        if (Agent.findByName("main") == null) {
            AgentService.create("main", "ollama-cloud", "kimi-k2.5");
            EventLogger.info("agent", "main", null, "Default agent 'main' created");
        }
        // Non-destructive workspace fill-in: creates any missing workspace files
        // from the Java-literal defaults without touching existing content.
        // The repo ships tracked seed files under workspace/main/, so a fresh
        // checkout already has populated markdown; this call handles the case
        // where a file has been deleted from disk post-boot.
        AgentService.createWorkspace("main");

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
