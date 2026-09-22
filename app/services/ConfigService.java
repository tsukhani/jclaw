package services;

import agents.DangerousActionGate;
import channels.TelegramSettings;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jobs.EventLogCleanupJob;
import jobs.ToolRegistrationJob;
import llm.LlmResilience;
import llm.OllamaProvider;
import llm.ProviderLocality;
import llm.ProviderRegistry;
import llm.routing.ModelRouter;
import llm.routing.RouterPolicy;
import memory.JpaMemoryStore;
import memory.MemoryReranker;
import memory.MemoryStoreFactory;
import memory.MemoryVectorSettings;
import models.Agent;
import models.Config;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.JPA;
import services.database.DatabaseService;
import services.telemetry.OtelConfig;
import services.telemetry.OtelRuntime;
import services.tts.TtsEngine;
import services.tts.TtsSidecarManager;
import services.voice.VoiceSettings;
import tools.DocumentsTool;
import tools.SubagentSpawnTool;
import tools.jev.JevSettings;
import tools.scrape.WebScrapeSettings;
import utils.ErrorRendering;
import utils.HttpFactories;
import utils.SsrfGuard;
import utils.StartupErrorTemplates;
import utils.TokenCoalescer;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

public class ConfigService {

    private ConfigService() {}

    /** Namespace every per-provider config key lives under: {@code provider.<name>.<field>}. */
    private static final String PROVIDER_KEY_PREFIX = "provider.";

    /** The per-provider key whose value is an outbound destination. */
    private static final String BASE_URL_SUFFIX = ".baseUrl";

    // Possessive: a repeated group that can backtrack recurses per character and can overflow the stack.
    private static final Pattern TESSERACT_LANGUAGES = Pattern.compile("\\w++(?:\\+\\w++)*+");
    private static final Set<String> PDF_STRATEGIES =
            Set.of("auto", "no_ocr", "ocr_only", "ocr_and_text_extraction");

    // The cache stores Optional<String> rather than String so we can distinguish
    // "key absent in DB" (empty Optional, cached) from "key not yet fetched"
    // (cache miss, triggers a DB lookup): the typed Caches.get(key, loader) returns
    // null for a null loader result instead of caching the absence.
    private static final Cache<String, Optional<String>> cache = Caches.named(
            "config",
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(60))
                    .build());

    public static @Nullable String get(String key) {
        // get(key, loader) provides single-flight semantics — concurrent misses
        // for the same key invoke the loader at most once.
        return cache.get(key, k -> {
            var config = Tx.run(() -> Config.findByKey(k));
            // JCLAW-1022: privileged keys are capped by application.conf, which the config table
            // cannot reach. Applied inside the loader so the cost lands once per key rather than
            // per read, and here rather than at each sink because provider.*.baseUrl alone has
            // nine of them.
            return Optional.ofNullable(
                    PrivilegedConfig.reconcile(k, config != null ? config.value : null));
        }).orElse(null);
    }

    /** The configured value, or {@code defaultValue} when the key is unset. Callers wanting
     *  "unset" to stay distinguishable use the single-argument {@link #get(String)}. */
    public static String get(String key, String defaultValue) {
        var value = get(key);
        return value != null ? value : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException _) {
            reportParseFailure(key, raw, "a whole number", String.valueOf(defaultValue));
            return defaultValue;
        }
    }

    public static long getLong(String key, long defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException _) {
            reportParseFailure(key, raw, "a whole number", String.valueOf(defaultValue));
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    public static double getDouble(String key, double defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        try {
            double parsed = Double.parseDouble(raw.trim());
            // parseDouble accepts "NaN"/"Infinity" without throwing, so the catch below never
            // sees them (JCLAW-1016) — and NaN passes any caller's own `v < lo || v > hi` check.
            if (Double.isFinite(parsed)) return parsed;
        } catch (NumberFormatException _) {
            // Reported below, with the non-finite case, so both reach the operator the same way.
        }
        reportParseFailure(key, raw, "a finite number", String.valueOf(defaultValue));
        return defaultValue;
    }

    // JCLAW-1136: a typo'd numeric value used to be discarded at the three getters above, which
    // left the instance running on a value the operator never chose with nothing anywhere saying
    // so. One entry per key, holding the value last reported, so a key is bounded and a corrected
    // (or newly broken) value shouts again.
    private static final ConcurrentMap<String, String> reportedParseFailures = new ConcurrentHashMap<>();

    /**
     * Report a stored value its reader rejected, once per value — for readers that parse a key
     * themselves rather than through the getters above.
     */
    public static void reportParseFailure(String key, String raw, String expected, String fallback) {
        reportParseFailure(key, raw, expected, fallback, null);
    }

    /** The same, for a key whose fallback is another key's value rather than a built-in default. */
    public static void reportParseFailure(String key, String raw, String expected, String fallback,
                                          @Nullable String fallbackSource) {
        var report = parseFailureReport(key, raw, expected, fallback, fallbackSource);
        if (report != null) EventLogger.error("config", report);
    }

    /**
     * The three-part message for a value a numeric getter rejected, or null when this key has
     * already reported this same value.
     *
     * <p>Config is read on the request path, not just at boot — one key can be read thousands of
     * times a minute — so the first read of a given bad value shouts and the rest stay quiet
     * until the stored value changes. Rendered {@link ErrorRendering#PLAIN}: this goes to the log
     * and the console, neither of which parses markup.
     *
     * <p>Public because test sources are the default package. Calling it marks the value as
     * reported, so a test uses a key name of its own.
     */
    public static @Nullable String parseFailureReport(String key, String raw, String expected,
                                                      String fallback) {
        return parseFailureReport(key, raw, expected, fallback, null);
    }

    /** The same, naming the key {@code fallback} came from when it is not the built-in default. */
    public static @Nullable String parseFailureReport(String key, String raw, String expected,
                                                      String fallback, @Nullable String fallbackSource) {
        // A cleared field means "use the default", which is exactly what happens — shouting about
        // it would train the operator to ignore the message that matters.
        if (raw.isBlank()) return null;
        if (raw.equals(reportedParseFailures.put(key, raw))) return null;
        return ErrorRendering.PLAIN.render(
                StartupErrorTemplates.configParseFailure(key, raw, expected, fallback, fallbackSource));
    }

    public static void set(String key, String value) {
        Tx.run(() -> Config.upsert(key, value));
        // Read-your-writes: seed the cache immediately so a later reader on a
        // different connection sees the value before the surrounding transaction
        // commits — the FunctionalTest suite and real request flows depend on this.
        // Reconciled, not raw: the read path caps privileged keys, and seeding the cache with
        // the stored value would hand the next reader an uncapped one until the TTL (JCLAW-1022).
        cache.put(key, Optional.ofNullable(PrivilegedConfig.reconcile(key, value)));
        // JCLAW-832: when set() joined an ambient (outer) transaction the upsert is
        // not durable yet. If that transaction rolls back, drop the eagerly-cached
        // entry so it can't serve a value the DB never kept for the 60s TTL. On the
        // owned-transaction path Tx.run already committed, so the entry stands.
        if (JPA.isInsideTransaction()) {
            scheduleRollbackEviction(JPA.em().unwrap(Session.class), key);
        }
    }

    /**
     * Register an {@code afterCompletion} synchronization on {@code session}'s
     * transaction that evicts {@code key} from the cache iff the transaction rolls
     * back; on commit the eagerly-cached value stands. One synchronization per
     * {@code set()}-in-a-transaction — config writes are rarely batched, so this is
     * simpler and more robust than deduping through thread-local state (which would
     * be fragile under the concurrent test runner). Visible (public) for
     * {@code ConfigServiceTest} in the default package, which exercises it against a
     * fresh EntityManager without disturbing the UnitTest harness's ambient JPA context.
     */
    public static void scheduleRollbackEviction(Session session, String key) {
        session.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // no-op: the evict/keep decision is made after completion
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_ROLLEDBACK) {
                    cache.invalidate(key);
                }
            }
        });
    }

    /**
     * Atomically create {@code key} with {@code value} iff no row exists yet.
     * Returns {@code true} when this call inserted the row, {@code false} when a
     * row was already present. Unlike {@link #set} (last-writer-wins via upsert),
     * this closes a check-then-write race: a losing concurrent insert trips the
     * {@code config_key} unique constraint and surfaces as {@code false}, not an
     * overwrite. Used by the first-install credential bootstrap (JCLAW-782).
     */
    public static boolean setIfAbsent(String key, String value) {
        boolean inserted = Tx.run(() -> {
            if (Config.findByKey(key) != null) return false;
            try {
                var config = new Config();
                config.key = key;
                config.value = value;
                config.save(); // flushes; the unique index rejects a concurrent insert
                return true;
            } catch (PersistenceException _) {
                // A concurrent setIfAbsent inserted the same key first. The flush
                // marks the transaction rollback-only, so Tx.run/withTransaction
                // rolls it back cleanly — report the key as already present.
                return false;
            }
        });
        if (inserted) {
            // Mirror set(): seed the cache for read-your-writes, evicting the entry
            // if the surrounding (not-yet-committed) transaction rolls back (JCLAW-832).
            // Reconciled, not raw: the read path caps privileged keys, and seeding the cache with
        // the stored value would hand the next reader an uncapped one until the TTL (JCLAW-1022).
        cache.put(key, Optional.ofNullable(PrivilegedConfig.reconcile(key, value)));
            if (JPA.isInsideTransaction()) {
                scheduleRollbackEviction(JPA.em().unwrap(Session.class), key);
            }
        }
        return inserted;
    }

    /**
     * Validate, persist, and trigger side effects for a config key/value.
     * Encapsulates the shell-privilege guard, provider sync, and tool re-registration.
     *
     * @return an error message if the key is rejected, or {@code null} on success
     */
    public static @Nullable String setWithSideEffects(String key, String value) {
        // JCLAW-1022: a row that would loosen a conf-capped key is already inert at the read.
        // Refusing it here is for the operator: a save that cannot take effect would otherwise
        // answer 200 and then read back as something else.
        var capped = PrivilegedConfig.rejectionFor(key, value);
        if (capped != null) {
            return capped;
        }

        // Shell exec privileges are restricted to the main agent
        if (key.matches("agent\\..+\\.shell\\.(bypassAllowlist|allowGlobalPaths)")) {
            var agentName = key.split("\\.")[1];
            var agent = Agent.findByName(agentName);
            if (agent == null || !agent.isMain()) {
                return "Shell exec privileges can only be set for the main agent.";
            }
        }

        // Operator timezone must be a valid IANA zone id. Reject typos here so
        // the system prompt never injects a bad zone — TimezoneResolver.appZone
        // would silently fall back to the server default, hiding the mistake.
        if (key.equals(TimezoneResolver.APP_CONFIG_KEY)) {
            try {
                ZoneId.of(value == null ? "" : value.trim());
            } catch (Exception _) {
                return "Invalid IANA timezone id '" + value
                        + "'. Use a value from GET /api/timezones (e.g. 'Asia/Kuala_Lumpur').";
            }
        }

        // JCLAW-1102: this classification is what lets memory text reach a host, so a typo
        // must not read as "remote". Boolean.parseBoolean maps anything unrecognized to
        // false, which would leave embeddings refusing a provider the operator declared local.
        // JCLAW-1158: the native-transport toggle is read the same way, and a typo would silently
        // keep the provider on the OpenAI-compatible wire.
        var booleanSuffix = providerBooleanSuffix(key);
        if (booleanSuffix != null
                && value != null && !value.isBlank()
                && !"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
            return PROVIDER_KEY_PREFIX + "*" + booleanSuffix + " must be 'true' or 'false'.";
        }

        // JCLAW-939: embedding a memory ships its full text to the provider, so the vector
        // provider is restricted to one on the operator's own machine or network, or one they
        // classified as self-hosted (JCLAW-1102). Enforced here rather than only in the
        // Settings picker: this key is reachable through POST /api/config directly, and a
        // hidden option is not a disabled one.
        //
        // The reranker is held to the same rule for the same reason: it renders the whole
        // candidate shortlist into its prompt, so whatever serves it sees memory text.
        if ((key.equals(MemoryVectorSettings.KEY_PROVIDER) || key.equals(MemoryReranker.KEY_PROVIDER))
                && value != null && !value.isBlank()
                && !ProviderLocality.isLocal(value)) {
            var feature = key.equals(MemoryReranker.KEY_PROVIDER) ? "reranking" : "embeddings";
            return "Provider '" + value + "' is not local. Memory " + feature + " must use a "
                    + "provider classified as self-hosted in Settings > LLM Providers, so "
                    + "memory text only goes where you allow it.";
        }

        // JCLAW-970: both keys are writable through POST /api/config, and a bad value is silent
        // at read — a negative rrfK pins one memory above every other, a non-finite minCosine
        // drops the vector leg entirely. Rejected here for the reason the timezone guard gives.
        if (key.equals(JpaMemoryStore.KEY_RRF_K) && !isIntAtLeast(value, 0)) {
            return "memory.recall.rrfK must be a non-negative integer.";
        }
        if (key.equals(JpaMemoryStore.KEY_RECALL_MIN_COSINE) && !isCosine(value)) {
            return "memory.recall.minCosine must be a finite number between -1.0 and 1.0.";
        }

        if (key.startsWith(WebScrapeSettings.PREFIX)) {
            var rejected = WebScrapeSettings.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }

        // JCLAW-1222: a model list naming what is not registered would route to nothing, and silently.
        if (key.startsWith(RouterPolicy.PREFIX)) {
            var rejected = RouterPolicy.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }
        // Every model picker lists the router under this name, so no real provider may take it.
        if (key.startsWith(PROVIDER_KEY_PREFIX + ModelRouter.PROVIDER + ".")) {
            return "The provider name '" + ModelRouter.PROVIDER + "' is reserved for the model router.";
        }

        // JCLAW-1229: the chat path now dials through the provider-guarded client, so a base URL
        // in the metadata range fails at connect as an opaque DNS error on the operator's next
        // turn. Refused at the write, where the message can say which range and why.
        if (key.startsWith(PROVIDER_KEY_PREFIX) && key.endsWith(BASE_URL_SUFFIX)
                && value != null && !value.isBlank()) {
            try {
                SsrfGuard.assertProviderUrlSafe(value.trim());
            } catch (SecurityException e) {
                return e.getMessage();
            }
        }

        // Each of these readers falls back to its default on a bad value, so without a check
        // here a typo saves cleanly and silently changes nothing.
        if (key.equals(DocumentsTool.KEY_OCR_LANGUAGES)
                && (value == null || !TESSERACT_LANGUAGES.matcher(value.trim()).matches())) {
            return key + " must be Tesseract language codes joined by +, such as eng or eng+fra.";
        }
        if (key.equals(DocumentsTool.KEY_OCR_TIMEOUT) && !isIntAtLeast(value, 1)) {
            return key + " must be a whole number of seconds, at least 1.";
        }
        if (key.equals(DocumentsTool.KEY_OCR_PDF_STRATEGY)
                && (value == null || !PDF_STRATEGIES.contains(value.trim().toLowerCase(Locale.ROOT)))) {
            return key + " must be one of auto, no_ocr, ocr_only or ocr_and_text_extraction.";
        }
        // A retention of 0 or less puts the cutoff at or after now, and the cleanup empties the log.
        if (key.equals(EventLogCleanupJob.CONFIG_KEY) && !isIntAtLeast(value, 1)) {
            return key + " must be a whole number of days, at least 1.";
        }
        if (key.equals(TaskFireDeadline.MAX_DURATION_KEY) && !isIntAtLeast(value, 0)) {
            return key + " must be a whole number of seconds; 0 turns the limit off.";
        }
        if (key.equals(ProviderRegistry.PRIMARY_PROVIDER_KEY) && value != null && !value.isBlank()
                && ProviderRegistry.get(value.trim()) == null) {
            return unconfiguredProvider(key, value.trim());
        }
        if (key.startsWith(VoiceSettings.PREFIX)) {
            var rejected = VoiceSettings.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }
        // A zero timeout expires an approval prompt before anyone can answer it.
        if (key.equals(DangerousActionGate.APPROVAL_TIMEOUT_KEY) && !isIntAtLeast(value, 1)) {
            return key + " must be a whole number of seconds, at least 1.";
        }
        if (key.equals(TokenCoalescer.CONFIG_KEY) && !isIntAtLeast(value, 0)) {
            return key + " must be a whole number of characters; 0 sends every token at once.";
        }
        if (key.startsWith(TelegramSettings.PREFIX)) {
            var rejected = TelegramSettings.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }
        // A window of 0 makes CircuitBreaker.Config throw when the next breaker is minted.
        if (key.startsWith(LlmResilience.BREAKER_KEY_PREFIX)) {
            var rejected = LlmResilience.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }

        // The coding harness is pointed at this provider's endpoint at spawn time; a name with
        // no provider behind it would only surface as a refused spawn much later.
        if (key.equals(SubagentSpawnTool.ACP_MODEL_PROVIDER_KEY) && value != null && !value.isBlank()
                && ProviderRegistry.get(value.trim()) == null) {
            return unconfiguredProvider(key, value.trim());
        }

        // JCLAW-1165: a schedule that never fires or a retention of zero is silent at read.
        if (key.startsWith("db.backup.")) {
            var rejected = DatabaseService.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }

        // JCLAW-34: a collector URL or sampler ratio that cannot take effect is refused
        // here, where the write happens, rather than logged at the next export.
        if (key.startsWith(OtelConfig.KEY_PREFIX)) {
            var rejected = OtelConfig.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }

        // JCLAW-1274: an engine the browser tool does not know would read as Playwright without a word.
        if (key.startsWith(JevSettings.KEY_PREFIX)) {
            var rejected = JevSettings.rejectionFor(key, value);
            if (rejected != null) {
                return rejected;
            }
        }

        set(key, value);

        if (key.startsWith(OtelConfig.KEY_PREFIX)) {
            OtelRuntime.applyConfig();
        }

        // JCLAW-863: switching the sidecar TTS model is the moment the operator
        // declares intent to use it, and the one moment they aren't waiting on a
        // turn — so pay the load now rather than on their first utterance. Gated
        // on the sidecar already running: a Settings change should not spawn a
        // Python process, and a later spawn prewarms on its own.
        if (key.equals("tts." + TtsEngine.SIDECAR.id() + ".model")
                && TtsSidecarManager.isRunning()) {
            TtsSidecarManager.prewarmModelAsync();
        }

        // An agent on router/auto is enabled exactly while the router has models to route to.
        if (key.startsWith(PROVIDER_KEY_PREFIX) || key.startsWith(RouterPolicy.PREFIX)) {
            AgentService.syncEnabledStates();
        }
        // The registry otherwise re-reads the pin only once a minute.
        if (key.equals(ProviderRegistry.PRIMARY_PROVIDER_KEY)) {
            ProviderRegistry.refresh();
        }
        // A breaker reads its tuning only when first minted.
        if (key.startsWith(LlmResilience.BREAKER_KEY_PREFIX)) {
            LlmResilience.applyConfig();
        }
        // JCLAW-930: JpaMemoryStore reads the vector settings once into final fields and
        // MemoryStoreFactory caches the instance, so without this the singleton serves the
        // old provider/model for the life of the process and the settings change looks
        // like it did nothing. reset() only clears the reference — the rebuild happens on
        // next use, keeping pgvector re-provisioning off the settings-save path.
        if (key.startsWith(MemoryVectorSettings.KEY_PREFIX)) {
            MemoryStoreFactory.reset();
        }
        // JCLAW-172: shell.enabled / playwright.enabled are gone — the tools
        // register unconditionally now. Only the loadtest mock provider still
        // toggles a tool registration via this side effect.
        if (key.equals("provider.loadtest-mock.enabled")) {
            ToolRegistrationJob.registerAll();
        }

        // Live-apply LLM dispatcher cap changes from Settings without
        // requiring a restart. HttpFactories.applyDispatcherConfig reads
        // both keys and pushes them into the live OkHttp dispatcher, so
        // the next outbound LLM call uses the new cap.
        if (isDispatcherCapKey(key)) {
            HttpFactories.applyDispatcherConfig();
        }

        // Per-logger level overrides apply live: the next log statement on the
        // affected logger uses the new level. The override is layered on top of
        // the file config, so it wins. See LoggerLevelService.
        if (key.startsWith(LoggerLevelService.PREFIX)) {
            LoggerLevelService.apply(key.substring(LoggerLevelService.PREFIX.length()), value);
        }

        // Convenience linkage: when the operator first sets the Ollama Cloud
        // LLM apiKey, mirror that value into the Ollama search provider's
        // apiKey AND flip search.ollama.enabled to true — but ONLY if the
        // search key is currently empty. Both providers authenticate against
        // the same Ollama account, so the usual case is one key serving
        // both surfaces; this saves the operator a redundant paste.
        //
        // Once the search key has any value (operator-set or previously
        // mirrored), this branch becomes a no-op — subsequent rotations of
        // the LLM key don't drag the search key along, preserving the
        // "set once, owned by you" model that operators expect from
        // independent settings.
        if (key.equals("provider.ollama-cloud.apiKey") && value != null && !value.isBlank()) {
            String existingSearchKey = get("search.ollama.apiKey");
            if (existingSearchKey == null || existingSearchKey.isBlank()) {
                set("search.ollama.apiKey", value);
                set("search.ollama.enabled", "true");
                EventLogger.info("config",
                        "Mirrored ollama-cloud LLM apiKey into search.ollama.apiKey "
                                + "and enabled web search (search key was empty)");
            }
        }

        return null;
    }

    private static String unconfiguredProvider(String key, String name) {
        return "Provider '" + name + "' is not configured. " + key
                + " must name a provider from Settings > LLM Providers.";
    }

    private static boolean isIntAtLeast(String value, int min) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim()) >= min;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /** A cosine similarity: finite and within [-1.0, 1.0]. Rejects the NaN and Infinity
     *  literals {@code Double.parseDouble} accepts without throwing. */
    private static boolean isCosine(String value) {
        try {
            double v = Double.parseDouble(value == null ? "" : value.trim());
            return Double.isFinite(v) && v >= -1.0 && v <= 1.0;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    public static void delete(String key) {
        Tx.run(() -> {
            var config = Config.findByKey(key);
            if (config != null) {
                config.delete();
            }
        });
        // JCLAW-1042 (VULN-100): an invalidate here runs before the commit whenever Tx.run
        // joined an ambient transaction, and a racing login then missed the cache, read the
        // not-yet-deleted row and re-cached the password hash for the full 60s TTL — so the
        // old password kept working. Caching the absence instead makes that read a HIT: it
        // returns null without reaching the DB, so there is nothing stale to re-cache. Mirrors
        // the eager put set() makes for JCLAW-832; a plain deferral would not, because it would
        // leave delete() unable to honor deleteEvictsCacheSoSubsequentGetReturnsNull.
        cache.put(key, Optional.empty());
        if (JPA.isInsideTransaction()) {
            // Same rollback contract as set(): an uncommitted delete must not leave the cache
            // asserting an absence the database never accepted.
            scheduleRollbackEviction(JPA.em().unwrap(Session.class), key);
        }
        // Closes the window between the delete and the put above, where a reader that had
        // already loaded the row could still land its stale write after ours.
        Tx.afterCommit(() -> cache.invalidate(key));
    }

    /** The per-provider boolean key suffix {@code key} ends with, or null when it is not one of them. */
    private static @Nullable String providerBooleanSuffix(String key) {
        if (!key.startsWith(PROVIDER_KEY_PREFIX)) return null;
        if (key.endsWith(ProviderLocality.DECLARED_LOCAL_SUFFIX)) return ProviderLocality.DECLARED_LOCAL_SUFFIX;
        if (key.endsWith(OllamaProvider.USE_NATIVE_API_SUFFIX)) return OllamaProvider.USE_NATIVE_API_SUFFIX;
        return null;
    }

    /**
     * Delete a config key and trigger the same side effects as {@link #setWithSideEffects}.
     * Mirrors save-with-side-effects so controller delete and save stay in sync.
     */
    public static void deleteWithSideEffects(String key) {
        delete(key);
        if (key.startsWith(PROVIDER_KEY_PREFIX) || key.startsWith(RouterPolicy.PREFIX)) {
            AgentService.syncEnabledStates();
        }
        if (key.equals(ProviderRegistry.PRIMARY_PROVIDER_KEY)) {
            ProviderRegistry.refresh();
        }
        if (key.startsWith(LlmResilience.BREAKER_KEY_PREFIX)) {
            LlmResilience.applyConfig();
        }
        // JCLAW-930: see setWithSideEffects — clearing a vector key changes the
        // effective setting just as writing one does, so the store must rebuild too.
        if (key.startsWith(MemoryVectorSettings.KEY_PREFIX)) {
            MemoryStoreFactory.reset();
        }
        // JCLAW-172: see setWithSideEffects for the rationale — only the
        // loadtest mock still triggers a tool re-registration on toggle.
        if (key.equals("provider.loadtest-mock.enabled")) {
            ToolRegistrationJob.registerAll();
        }
        // Deleting a logger override reverts that logger to its inherited level
        // (root → its captured file baseline). See LoggerLevelService.
        if (key.startsWith(LoggerLevelService.PREFIX)) {
            LoggerLevelService.revert(key.substring(LoggerLevelService.PREFIX.length()));
        }
        if (key.startsWith(OtelConfig.KEY_PREFIX)) {
            OtelRuntime.applyConfig();
        }
        // JCLAW-1231: clearing a cap reverts it to the compiled default, which the live
        // dispatcher only picks up here — without this the deleted cap stayed in force
        // until the next restart.
        if (isDispatcherCapKey(key)) {
            HttpFactories.applyDispatcherConfig();
        }
    }

    /** The two keys {@link HttpFactories#applyDispatcherConfig} reads. */
    private static boolean isDispatcherCapKey(String key) {
        return key.equals("dispatcher.llm.maxRequestsPerHost") || key.equals("dispatcher.llm.maxRequests");
    }

    public static List<Config> listAll() {
        // NB: must stay a lambda, not a method reference — Tx.run is overloaded
        // (Function0<T> vs Runnable) and Config::findAll is ambiguous between
        // them, whereas the explicit lambda resolves cleanly (Sonar S1612 FP).
        return Tx.run(() -> Config.<Config>findAll());
    }

    public static void clearCache() {
        cache.invalidateAll();
    }

    private static final Set<String> SENSITIVE_PATTERNS = Set.of(
            "key", "secret", "password", "token"
    );

    public static boolean isSensitive(String key) {
        var lower = key.toLowerCase();
        return SENSITIVE_PATTERNS.stream().anyMatch(lower::contains);
    }

    public static @Nullable String maskValue(String key, String value) {
        if (value == null) return null;
        if (isSensitive(key) && value.length() > 4) {
            return value.substring(0, 4) + "****";
        }
        return value;
    }
}
