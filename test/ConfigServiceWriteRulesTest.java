import channels.TelegramSettings;
import llm.LlmResilience;
import llm.routing.RouterPolicy;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.OperatorAlerts;
import services.PrivilegedConfig;
import services.database.DatabaseService;
import services.decision.DecisionSettings;
import services.telemetry.OtelConfig;
import services.voice.VoiceSettings;
import tools.jev.JevSettings;
import tools.scrape.WebScrapeSettings;
import utils.SsrfGuard;

/**
 * Pins every refusal {@link ConfigService#setWithSideEffects} can answer, the precedence between
 * checks that share a key, and that a refused write stores nothing and applies nothing.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: refused writes leave no row, accepted values are checked
 * through {@link ConfigService#rejectionFor} without one, and the one stored write is to a logger
 * this class owns.
 */
class ConfigServiceWriteRulesTest extends UnitTest {

    private static void assertRefused(String expected, String key, String value) {
        assertEquals(expected, ConfigService.setWithSideEffects(key, value), () -> key + "=" + value);
    }

    /** For a namespace whose rule lives with its owner: the write answers exactly what the owner says. */
    private static void assertRefusedAsOwnerSays(@org.jspecify.annotations.Nullable String owner, String key,
                                                 String value) {
        assertNotNull(owner, () -> "the owner must refuse " + key + "=" + value + ", or this pins nothing");
        assertRefused(owner, key, value);
    }

    /** Validated without a write: these keys are read live, and a floor value stored for a moment
     *  would reach whatever concurrent class reads them (a retention of 1 empties its fixture). */
    private static void assertAccepted(String key, String value) {
        assertNull(ConfigService.rejectionFor(key, value), () -> key + "=" + value);
    }

    // --- cross-cutting guards, in order ---

    @Test
    void aWriteThatWouldLoosenAConfCappedKeyIsRefusedBeforeAnyNamespaceRule() {
        var key = "provider.__conftest__.baseUrl";
        // A metadata address would also fail the base-URL screen; the cap must answer first.
        var value = "http://169.254.169.254";
        assertRefusedAsOwnerSays(PrivilegedConfig.rejectionFor(key, value), key, value);
    }

    @Test
    void aSecretsMaskIsRefusedBeforeAnyNamespaceRule() {
        assertRefused("That is the saved value's mask, not a new value. Enter the whole new value, or leave "
                + "provider.jclaw1402.apiKey unchanged.", "provider.jclaw1402.apiKey", "sk-1****");
        assertRefused("That is the saved value's mask, not a new value. Enter the whole new value, or leave "
                + "provider.router.apiKey unchanged.", "provider.router.apiKey", "sk-1****");
    }

    // --- per-namespace refusals ---

    @Test
    void shellPrivilegesAreRefusedForAnyAgentButMain() {
        var expected = "Shell exec privileges can only be set for the main agent.";
        assertRefused(expected, "agent.jclaw1402-nobody.shell.bypassAllowlist", "true");
        assertRefused(expected, "agent.jclaw1402-nobody.shell.allowGlobalPaths", "true");
    }

    @Test
    void anAlertDestinationThatCannotBeDispatchedIsRefused() {
        assertRefusedAsOwnerSays(OperatorAlerts.rejectionFor("pigeon:42"), OperatorAlerts.KEY, "pigeon:42");
    }

    @Test
    void anUnknownAppTimezoneIsRefused() {
        assertRefused("Invalid IANA timezone id 'Not/A/Zone'. Use a value from GET /api/timezones "
                + "(e.g. 'Asia/Kuala_Lumpur').", "app.timezone", "Not/A/Zone");
    }

    @Test
    void aProviderBooleanMustBeTrueOrFalseAndThatOutranksAReservedName() {
        assertRefused("provider.*.local must be 'true' or 'false'.", "provider.jclaw1402.local", "maybe");
        assertRefused("provider.*.useNativeApi must be 'true' or 'false'.",
                "provider.jclaw1402.useNativeApi", "yes");
        assertRefused("provider.*.local must be 'true' or 'false'.", "provider.router.local", "maybe");
    }

    @Test
    void aMemoryProviderThatIsNotLocalIsRefused() {
        assertRefused("Provider 'jclaw1402-remote' is not local. Memory embeddings must use a provider "
                        + "classified as self-hosted in Settings > LLM Providers, so memory text only goes "
                        + "where you allow it.",
                memory.MemoryVectorSettings.KEY_PROVIDER, "jclaw1402-remote");
        assertRefused("Provider 'jclaw1402-remote' is not local. Memory reranking must use a provider "
                        + "classified as self-hosted in Settings > LLM Providers, so memory text only goes "
                        + "where you allow it.",
                memory.MemoryReranker.KEY_PROVIDER, "jclaw1402-remote");
    }

    @Test
    void recallKnobsOutsideTheirRangeAreRefused() {
        assertRefused("memory.recall.rrfK must be a non-negative integer.", "memory.recall.rrfK", "-1");
        assertRefused("memory.recall.minCosine must be a finite number between -1.0 and 1.0.",
                "memory.recall.minCosine", "1.01");
        assertRefused("memory.recall.minCosine must be a finite number between -1.0 and 1.0.",
                "memory.recall.minCosine", "NaN");
    }

    @Test
    void recallKnobsAtTheirBoundsAreAccepted() {
        assertAccepted("memory.recall.rrfK", "0");
        assertAccepted("memory.recall.minCosine", "-1.0");
        assertAccepted("memory.recall.minCosine", "1.0");
    }

    @Test
    void webScrapeKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(WebScrapeSettings.rejectionFor(WebScrapeSettings.MAX_PAGES, "0"),
                WebScrapeSettings.MAX_PAGES, "0");
    }

    @Test
    void routerKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(RouterPolicy.rejectionFor("router.jclaw1402", "x"), "router.jclaw1402", "x");
    }

    @Test
    void theProviderNamesTheRouterOwnsAreReserved() {
        assertRefused("The provider name 'router' is reserved for the model router.",
                "provider.router.baseUrl", "http://127.0.0.1:1/v1");
        assertRefused("The provider name 'jev' is reserved for the model router's JEV classifier.",
                "provider.jev.apiKey", "x");
        assertRefused("The provider name 'ollama-decision' is reserved for the model router's Ollama "
                + "classifier.", "provider.ollama-decision.baseUrl", "http://127.0.0.1:1");
    }

    @Test
    void aProviderBaseUrlInTheMetadataRangeIsRefusedWithTheGuardsMessage() {
        var value = "http://169.254.169.254";
        String expected = null;
        try {
            SsrfGuard.assertProviderUrlSafe(value);
        } catch (SecurityException e) {
            expected = e.getMessage();
        }
        assertRefusedAsOwnerSays(expected, "provider.jclaw1402.baseUrl", value);
    }

    @Test
    void ocrTuningTesseractCannotUseIsRefused() {
        assertRefused("ocr.tesseract.languages must be Tesseract language codes joined by +, such as eng or "
                + "eng+fra.", "ocr.tesseract.languages", "eng fra");
        assertRefused("ocr.tesseract.timeout must be a whole number of seconds, at least 1.",
                "ocr.tesseract.timeout", "0");
        assertRefused("ocr.pdf.strategy must be one of auto, no_ocr, ocr_only or ocr_and_text_extraction.",
                "ocr.pdf.strategy", "sometimes");
        assertAccepted("ocr.tesseract.timeout", "1");
    }

    @Test
    void retentionAndDeadlineBoundsAreRefusedBelowTheirFloor() {
        assertRefused("logs.retentionDays must be a whole number of days, at least 1.", "logs.retentionDays", "0");
        assertRefused("tasks.fireMaxDurationSeconds must be a whole number of seconds; 0 turns the limit off.",
                "tasks.fireMaxDurationSeconds", "-1");
        assertAccepted("logs.retentionDays", "1");
        assertAccepted("tasks.fireMaxDurationSeconds", "0");
    }

    @Test
    void aPrimaryOrAcpProviderMustBeConfigured() {
        assertRefused("Provider 'jclaw1402-none' is not configured. llm.primaryProvider must name a provider "
                + "from Settings > LLM Providers.", "llm.primaryProvider", "jclaw1402-none");
        assertRefused("Provider 'jclaw1402-none' is not configured. subagent.acp.modelProvider must name a "
                + "provider from Settings > LLM Providers.", "subagent.acp.modelProvider", " jclaw1402-none ");
    }

    @Test
    void voiceKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(VoiceSettings.rejectionFor(VoiceSettings.MAX_RUN_ON_CHARS, "0"),
                VoiceSettings.MAX_RUN_ON_CHARS, "0");
    }

    @Test
    void theApprovalTimeoutRuleOutranksTheTelegramNamespace() {
        assertRefused("telegram.approval.timeout-seconds must be a whole number of seconds, at least 1.",
                agents.DangerousActionGate.APPROVAL_TIMEOUT_KEY, "0");
        assertAccepted(agents.DangerousActionGate.APPROVAL_TIMEOUT_KEY, "1");
    }

    @Test
    void aNegativeTokenCoalesceSizeIsRefused() {
        assertRefused("chat.stream.token_coalesce_chars must be a whole number of characters; 0 sends every "
                + "token at once.", utils.TokenCoalescer.CONFIG_KEY, "-1");
    }

    @Test
    void telegramKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(TelegramSettings.rejectionFor(TelegramSettings.KEYBOARD_SCOPE, "everywhere"),
                TelegramSettings.KEYBOARD_SCOPE, "everywhere");
    }

    @Test
    void breakerKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(LlmResilience.rejectionFor("llm.breaker.window", "0"), "llm.breaker.window", "0");
    }

    @Test
    void databaseBackupKeysAnswerTheirOwnersRule() {
        assertRefused("db.backup.retention must be at least 1.", DatabaseService.KEY_RETENTION, "0");
        assertRefusedAsOwnerSays(DatabaseService.rejectionFor(DatabaseService.KEY_SCHEDULE, "noon"),
                DatabaseService.KEY_SCHEDULE, "noon");
    }

    @Test
    void telemetryKeysAnswerTheirOwnersRule() {
        assertRefused("otel.exporter.protocol must be 'http/protobuf' or 'grpc'.", OtelConfig.KEY_PROTOCOL, "smoke");
    }

    @Test
    void browserKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(JevSettings.rejectionFor(JevSettings.ENGINE, "foo"), JevSettings.ENGINE, "foo");
    }

    @Test
    void decisionKeysAnswerTheirOwnersRule() {
        assertRefusedAsOwnerSays(DecisionSettings.rejectionFor("decision.jclaw1402", "3"), "decision.jclaw1402",
                "3");
    }

    // --- a refused write stores nothing and applies nothing ---

    @Test
    void aRefusedWriteLeavesTheStoredValueAsItWas() {
        var breaker = "llm.breaker.window";
        var before = ConfigService.get(breaker);
        assertNotNull(ConfigService.setWithSideEffects(breaker, "0"));
        assertEquals(before, ConfigService.get(breaker));

        var protocol = OtelConfig.KEY_PROTOCOL;
        var protocolBefore = ConfigService.get(protocol);
        assertNotNull(ConfigService.setWithSideEffects(protocol, "smoke"));
        assertEquals(protocolBefore, ConfigService.get(protocol));
    }

    @Test
    void aRefusedWriteToAKeyWithASideEffectStoresNothing() {
        // No value is both a mask and a valid level, so the logger cannot show the effect not running;
        // the absent row shows set() was never reached, and every side effect runs after it.
        var key = "logging.level.jclaw1402.probe.apiKey";
        assertNotNull(ConfigService.setWithSideEffects(key, "DEBU****"));
        assertNull(ConfigService.get(key));
    }

    @Test
    void rejectionForAnswersWhatTheWriteWouldWithoutStoringIt() {
        var key = "logging.level.jclaw1402.probe.validate";
        assertNull(ConfigService.rejectionFor(key, "TRACE"));
        assertNull(ConfigService.get(key));
        assertEquals(ConfigService.rejectionFor("logs.retentionDays", "0"),
                ConfigService.setWithSideEffects("logs.retentionDays", "0"));
    }

    // --- side effects of an accepted write ---

    @Test
    void anAcceptedLoggerLevelAppliesLive() {
        var logger = "jclaw1402.probe.level";
        var key = "logging.level." + logger;
        try {
            assertNull(ConfigService.setWithSideEffects(key, "TRACE"));
            assertEquals(Level.TRACE, LogManager.getLogger(logger).getLevel());
        } finally {
            ConfigService.deleteWithSideEffects(key);
        }
    }
}
