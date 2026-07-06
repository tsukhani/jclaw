package jobs;

import agents.AgentRunner;
import agents.SkillLoader;
import models.Agent;
import models.Config;
import play.Logger;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;
import services.InternalApiTokenService;
import services.SkillPromotionService;
import services.Tx;
import services.UvProbe;
import services.scanners.ScannerRegistry;
import services.transcription.FfmpegProbe;
import services.transcription.WhisperModel;
import tools.ShellExecTool;
import tools.SubagentSpawnTool;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Seeds default runtime configuration and default agent on first startup.
 * Only writes values that don't already exist.
 *
 * <p>Runs near-FIRST among {@code @OnApplicationStart} jobs ({@code priority =
 * -100}; play1 1.13.27+ runs startup jobs in ascending priority order). Config
 * is the boot foundation: {@code ToolRegistrationJob}, the Ollama/LM-Studio
 * probes, and {@code TelegramStreamingRecoveryJob} all read config at startup,
 * so seeding it before they run makes those previously-implicit ordering
 * dependencies explicit. (The columns those readers touch are created by
 * Hibernate's {@code ddl=update} at SessionFactory init, which completes
 * before any {@code @OnApplicationStart} job runs.)
 */
@OnApplicationStart(priority = -100)
public class DefaultConfigJob extends Job<Void> {

    private static final String EVENT_CATEGORY_AGENT = "agent";
    private static final String CONFIG_VALUE_FALSE = "false";

    @Override
    public void doJob() {
        seedProviders();
        seedToolConfig();
        seedDefaultAgent();
        seedJClawApiTooling();
        seedDispatcherTuning();
        seedTranscription();
        seedVideoGen();
        SkillLoader.syncSkillConfigs();
        HttpFactories.applyDispatcherConfig();
        // JCLAW-163: prime the ffmpeg cache so the Settings UI can render a
        // "ffmpeg missing" banner without paying the probe cost on first
        // page load. Cheap (~ms when ffmpeg is present, ~tens of ms when not).
        FfmpegProbe.probe();
        // JCLAW-226: prime the uv-availability cache so the Settings UI can render
        // a "uv missing" banner for local image generation without paying the probe
        // cost on first page load (cheap; same rationale as FfmpegProbe above).
        UvProbe.probe();
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
        // JCLAW-654: diarization is cloud-only — an audio-capable chat model
        // chosen in Settings; empty means "not configured" and the diarize
        // tool explains what to set up.
        seedIfAbsent("transcription.diarization.provider", "");
        seedIfAbsent("transcription.diarization.model", "");
        seedIfAbsent("transcription.localModel",
                WhisperModel.DEFAULT.id());
        // JCLAW-563: per-turn acoustic emotion labels on diarized
        // transcripts. On by default (best-effort — failures never break a
        // transcript); set false to skip the ~95 MB model download and the
        // extra per-turn inference.
        // JCLAW-565/614: Hugging Face token for the gated community-1
        // weights, passed to the sidecar process as HF_TOKEN. Blank = reuse
        // imagegen.local.hfToken; with neither set diarization fails fast
        // with setup instructions (sidecar-or-error). Masked in
        // Settings (key name contains "token").
        // JCLAW-605: cross-talk turns re-attributed via MossFormer2 source
        // separation in the sidecar + WeSpeaker stem voiceprints. Only
        // activates on the pyannote path when overlap regions exist; every
        // failure degrades to the merge's attribution.
        // JCLAW-612: NeMo MSDD consulted as a second opinion on contested
        // turns (pyannote path only). First use builds a separate uv env.
        // JCLAW-613: transcribe backchannel interjections from minor
        // separation stems so under-speech appears as its own turn.
    }

    /**
     * JCLAW-230: seed the video-generation job timeout so the Settings UI (JCLAW-236) has a defined
     * default and {@code jobs.VideoGenerationJobRunner} times jobs out at a known bound. Provider
     * selection ({@code videogen.provider}) and model stay unset until the operator opts in.
     */
    private void seedVideoGen() {
        seedIfAbsent("videogen.maxJobMinutes", "30");
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
        int cores = Runtime.getRuntime().availableProcessors();
        int defaultPerHost = Math.clamp(8L * cores, 64, 256);
        int defaultMax = 2 * defaultPerHost;
        seedIfAbsent("dispatcher.llm.maxRequestsPerHost", String.valueOf(defaultPerHost));
        seedIfAbsent("dispatcher.llm.maxRequests", String.valueOf(defaultMax));
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

        // vLLM (self-hosted): OpenAI-compatible /v1/chat/completions + /v1/models, the same factory
        // default (OpenAiProvider) and discovery path (OpenAI-compat /v1/models) as lm-studio. Default
        // serving port is 8000. Same non-blank sentinel apiKey so ProviderRegistry registers the row
        // (vLLM ignores Authorization unless --api-key was set, in which case the operator overrides
        // this). Backs the Settings → Video Interpretation "vLLM" backend.
        seedIfAbsent("provider.vllm.baseUrl", "http://localhost:8000/v1");
        seedIfAbsent("provider.vllm.apiKey", "vllm");
        seedIfAbsent("provider.vllm.models", "[]");

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

        // Black Forest Labs (Flux) — IMAGE GENERATION only (JCLAW-225), NOT a chat provider:
        // ProviderRegistry.IMAGE_ONLY_PROVIDERS skips it for /chat/completions; the
        // services.imagegen.BflImageGenerationClient reads these keys directly (async submit+poll
        // with x-key auth). apiKey blank → the Image Generation Settings BFL radio stays disabled
        // until an operator pastes a key. imagegen.provider stays unseeded (absent = off, opt-in
        // via Settings), mirroring caption.provider.
        seedIfAbsent("provider.bfl.baseUrl", "https://api.bfl.ai/v1");
        seedIfAbsent("provider.bfl.apiKey", "");
        // Replicate — also IMAGE GENERATION only (hosted models behind an async predictions API,
        // Bearer auth). Same ProviderRegistry exclusion; ReplicateImageGenerationClient reads these.
        seedIfAbsent("provider.replicate.baseUrl", "https://api.replicate.com/v1");
        seedIfAbsent("provider.replicate.apiKey", "");
        seedIfAbsent("imagegen.imageSize", "1024x1024");
        seedIfAbsent("imagegen.timeoutSeconds", "60");

        // JCLAW-226: local Flux 2 Klein engine via a Python HTTP sidecar (the shape
        // chosen in the JCLAW-509 spike). Selection stays on imagegen.provider="flux-local"
        // (unseeded — absent = off, opt-in via Settings, like the cloud image backends);
        // these keys configure the sidecar that LocalImageSidecarManager launches on demand.
        // Default model is klein 4B (Apache-2.0, ~13 GB fp16) — the smallest variant and the
        // only one whose throughput stays tolerable on Apple Silicon (MPS). idleTimeoutMinutes
        // lets the daemon self-evict and release the GPU when unused.
        seedIfAbsent("imagegen.local.model", "black-forest-labs/FLUX.2-klein-4B");
        seedIfAbsent("imagegen.local.port", "9527");
        seedIfAbsent("imagegen.local.idleTimeoutMinutes", "15");
        // Optional Hugging Face token, passed to the sidecar as HF_TOKEN. Blank by default —
        // klein 4B is Apache-2.0 and downloads anonymously; a token only lifts rate limits,
        // speeds downloads, and unlocks gated models. Masked (key name contains "token").
        seedIfAbsent("imagegen.local.hfToken", "");

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
        seedIfAbsent("chat.maxToolRounds", String.valueOf(AgentRunner.DEFAULT_MAX_TOOL_ROUNDS));
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
        seedIfAbsent("shell.allowlist", ShellExecTool.DEFAULT_ALLOWLIST);
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
        seedIfAbsent("search.ollama.enabled", CONFIG_VALUE_FALSE);
        seedIfAbsent("search.ollama.apiKey", "");
        seedIfAbsent("search.ollama.baseUrl", "https://ollama.com/api/web_search");
        seedIfAbsent("search.ollama.priority", "0");
        seedIfAbsent("search.exa.enabled", CONFIG_VALUE_FALSE);
        seedIfAbsent("search.exa.apiKey", "");
        seedIfAbsent("search.exa.baseUrl", "https://api.exa.ai/search");
        seedIfAbsent("search.exa.priority", "1");
        seedIfAbsent("search.perplexity.enabled", CONFIG_VALUE_FALSE);
        seedIfAbsent("search.perplexity.apiKey", "");
        seedIfAbsent("search.perplexity.baseUrl", "https://api.perplexity.ai/search");
        seedIfAbsent("search.perplexity.priority", "2");
        // Server-side recency filter for Perplexity's /search endpoint. One of
        // hour|day|week|month|year, or "none" to disable. Defaults to "month"
        // so "latest X" queries don't return year-old snippets — the LLM will
        // not reliably add year/month keywords on its own.
        seedIfAbsent("search.perplexity.recencyFilter", "month");
        seedIfAbsent("search.brave.enabled", CONFIG_VALUE_FALSE);
        seedIfAbsent("search.brave.apiKey", "");
        seedIfAbsent("search.brave.baseUrl", "https://api.search.brave.com/res/v1/web/search");
        seedIfAbsent("search.brave.priority", "3");
        seedIfAbsent("search.tavily.enabled", CONFIG_VALUE_FALSE);
        seedIfAbsent("search.tavily.apiKey", "");
        seedIfAbsent("search.tavily.baseUrl", "https://api.tavily.com/search");
        seedIfAbsent("search.tavily.priority", "4");
        seedIfAbsent("search.felo.enabled", CONFIG_VALUE_FALSE);
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

        // JCLAW-266: subagent recursion caps. Personal Edition defaults —
        // single level of delegation (top-level agents may spawn one tier of
        // subagents; grandchildren are refused) and a small fan-out so one
        // parent can't saturate the executor with concurrent children. Both
        // keys are editable from the Settings page's Subagents section
        // (DB-backed so changes take effect without a restart). Read path
        // lives in tools.SubagentSpawnTool#enforceRecursionLimits.
        seedIfAbsent(SubagentSpawnTool.DEPTH_LIMIT_KEY,
                String.valueOf(SubagentSpawnTool.DEFAULT_DEPTH_LIMIT));
        seedIfAbsent(SubagentSpawnTool.BREADTH_LIMIT_KEY,
                String.valueOf(SubagentSpawnTool.DEFAULT_BREADTH_LIMIT));
        // JCLAW-424: absolute wall-clock ceiling on a single subagent run — the
        // runaway guard the idle budget (an active child never trips it) cannot
        // provide. Operator-only; 0 disables. Read path lives in
        // tools.SubagentSpawnTool#awaitFuture.
        seedIfAbsent(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                String.valueOf(SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS));
    }

    private void seedDefaultAgent() {
        if (Agent.findByName("main") == null) {
            AgentService.create("main", "ollama-cloud", "kimi-k2.5");
            EventLogger.info(EVENT_CATEGORY_AGENT, "main", null, "Default agent 'main' created");
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
     * JCLAW-282: bring up the in-process JClaw API tool. Two steps:
     * <ol>
     *   <li>Mint (or recover) the internal bearer token so {@code jclaw_api}
     *       can authenticate against the same auth filter that gates
     *       external clients.</li>
     *   <li>Install the {@code jclaw-api} skill into {@code main}'s
     *       workspace so it surfaces in main's {@code <available_skills>}
     *       block. Other agents don't get the skill — its installation
     *       location <em>is</em> the gating mechanism for which agents
     *       can use it. {@link services.SkillPromotionService#copyToAgentWorkspace}
     *       is idempotent.</li>
     * </ol>
     */
    private void seedJClawApiTooling() {
        // Step 1: ensure the internal bearer token exists. The first
        // call mints it; subsequent boots reuse the stored value.
        InternalApiTokenService.token();

        // Step 2: install the skill into main's workspace.
        seedJClawApiSkillForMain();
    }

    /** Mirror of {@link #seedSkillCreatorForMain} for the jclaw-api skill.
     *  Skipped silently if the global registry doesn't ship the skill —
     *  the tool stays callable for any agent that has it allowlisted,
     *  but main won't see the curated SKILL.md until an operator drops
     *  it back in. */
    private void seedJClawApiSkillForMain() {
        var skillName = "jclaw-api";
        var globalSkillMd = SkillLoader.globalSkillsPath()
                .resolve(skillName).resolve("SKILL.md");
        if (!Files.exists(globalSkillMd)) {
            Logger.info("jclaw-api skill not present in global registry — skipping bootstrap");
            return;
        }
        var main = Agent.findByName("main");
        if (main == null) return;
        try {
            SkillPromotionService.copyToAgentWorkspace(main, skillName);
            EventLogger.info(EVENT_CATEGORY_AGENT, "main", null,
                    "jclaw-api skill installed for main agent (in-process API access seeded)");
        } catch (IOException e) {
            Logger.warn("Failed to bootstrap jclaw-api skill for main: %s", e.getMessage());
        }
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
        var skillName = SkillPromotionService.SKILL_CREATOR_NAME;
        var globalSkillMd = SkillLoader.globalSkillsPath()
                .resolve(skillName).resolve("SKILL.md");
        if (!Files.exists(globalSkillMd)) {
            Logger.info("Skill-creator not present in global registry — skipping bootstrap");
            return;
        }
        var main = Agent.findByName("main");
        if (main == null) return;
        try {
            SkillPromotionService.copyToAgentWorkspace(main, skillName);
            EventLogger.info(EVENT_CATEGORY_AGENT, "main", null,
                    "Skill-creator installed for main agent (promotion capability seeded)");
        } catch (IOException e) {
            Logger.warn("Failed to bootstrap skill-creator for main: %s", e.getMessage());
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
