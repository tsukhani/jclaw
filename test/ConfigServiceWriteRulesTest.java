import agents.ToolResultPruner;
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
import services.SessionCompactor;
import services.database.DatabaseService;
import services.decision.BasetenDecision;
import services.decision.DecisionSettings;
import services.decision.LayaDecision;
import services.telemetry.OtelConfig;
import services.voice.VoiceSettings;
import tools.HarnessSandbox;
import tools.SubagentSpawnTool;
import tools.jev.JevSettings;
import tools.scrape.WebScrapeSettings;
import utils.SsrfGuard;

import java.util.List;

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
        assertRefused("The provider name 'laya' is reserved for the model router's Laya classifier.",
                "provider.laya.baseUrl", "http://127.0.0.1:1");
        assertRefused("The provider name 'baseten-decision' is reserved for the model router's Baseten classifier.",
                "provider.baseten-decision.apiKey", "x");
        // Baseten also serves LLMs, and an operator may name that chat provider baseten.
        assertAccepted("provider.baseten.apiKey", "bt-key");
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
    void thePauseAfterFailedRunsCountMustBeAWholeNumberOfZeroOrMore() {
        var expected = "tasks.pauseAfterFailedOccurrences must be a whole number of failed runs; 0 turns pausing off.";
        assertRefused(expected, "tasks.pauseAfterFailedOccurrences", "-1");
        assertRefused(expected, "tasks.pauseAfterFailedOccurrences", "three");
        assertRefused(expected, "tasks.pauseAfterFailedOccurrences", "");
        assertAccepted("tasks.pauseAfterFailedOccurrences", "0");
        assertAccepted("tasks.pauseAfterFailedOccurrences", "1");
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
    void theSummarizeTokenCapMustBeAWholeNumberOfZeroOrMore() {
        var expected = "chat.compaction.triggerTokens must be a whole number of tokens; 0 turns the token cap off.";
        for (var value : List.of("-1", "150k", "1.5", "")) {
            assertRefused(expected, SessionCompactor.TRIGGER_TOKENS_KEY, value);
        }
        assertAccepted(SessionCompactor.TRIGGER_TOKENS_KEY, "0");
        assertAccepted(SessionCompactor.TRIGGER_TOKENS_KEY, "150000");
    }

    @Test
    void theSummarizeShareOfTheWindowMustBeAWholePercentFromZeroToAHundred() {
        var expected = "chat.compaction.triggerPercent must be a whole number from 0 to 100; 0 turns the window share off.";
        for (var value : List.of("-1", "101", "50.5", "half", "")) {
            assertRefused(expected, SessionCompactor.TRIGGER_PERCENT_KEY, value);
        }
        for (var value : List.of("0", "50", "100")) {
            assertAccepted(SessionCompactor.TRIGGER_PERCENT_KEY, value);
        }
    }

    @Test
    void theSummarizeAtTheRowLimitSwitchMustBeTrueOrFalse() {
        var expected = "chat.compaction.onRowLimit must be 'true' or 'false'.";
        for (var value : List.of("yes", "1", "")) {
            assertRefused(expected, SessionCompactor.ON_ROW_LIMIT_KEY, value);
        }
        for (var value : List.of("true", "false", "TRUE")) {
            assertAccepted(SessionCompactor.ON_ROW_LIMIT_KEY, value);
        }
    }

    @Test
    void theSummaryBudgetMustBeAWholeNumberOfAtLeastAThousandTokens() {
        var key = "chat.compaction.summaryBudgetTokens";
        var expected = key + " must be a whole number of tokens, at least 1000.";
        for (var value : List.of("999", "0", "-1", "20k", "1.5", "")) {
            assertRefused(expected, key, value);
        }
        assertAccepted(key, "1000");
        assertAccepted(key, "20000");
    }

    @Test
    void theToolResultLengthInACatchUpMustBeAWholeNumberOfZeroOrMore() {
        var key = "chat.compaction.toolResultChars";
        var expected = key + " must be a whole number of characters; 0 keeps only the length.";
        for (var value : List.of("-1", "600 chars", "1.5", "")) {
            assertRefused(expected, key, value);
        }
        assertAccepted(key, "0");
        assertAccepted(key, "600");
    }

    @Test
    void theShorteningStepMustBeAWholeNumberOfAtLeastOne() {
        var expected = "chat.pruneToolResultsEveryTurns must be a whole number of turns, at least 1.";
        assertRefused(expected, ToolResultPruner.EVERY_TURNS_KEY, "0");
        assertRefused(expected, ToolResultPruner.EVERY_TURNS_KEY, "five");
        assertRefused(expected, ToolResultPruner.EVERY_TURNS_KEY, "");
        assertAccepted(ToolResultPruner.EVERY_TURNS_KEY, "1");
        assertAccepted(ToolResultPruner.EVERY_TURNS_KEY, "5");
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

    @Test
    void theLayaKeysAreRefusedAtTheWrite() {
        var linkLocal = "http://169.254.169.254:8000";
        String guard = null;
        try {
            SsrfGuard.assertProviderUrlSafe(linkLocal);
        } catch (SecurityException e) {
            guard = e.getMessage();
        }
        assertRefusedAsOwnerSays(guard, LayaDecision.BASE_URL_KEY, linkLocal);
        for (var address : List.of("http://224.0.0.1:8000", "http://0.0.0.0:8000")) {
            assertRefusedAsOwnerSays(DecisionSettings.rejectionFor(LayaDecision.BASE_URL_KEY, address),
                    LayaDecision.BASE_URL_KEY, address);
        }
        assertAccepted(LayaDecision.BASE_URL_KEY, "http://localhost:8000");
        assertAccepted(LayaDecision.BASE_URL_KEY, "http://192.168.1.30:8000");

        assertRefused("decision.laya.apiKey must be printable ASCII with no spaces.", LayaDecision.API_KEY, "laya key");
        assertAccepted(LayaDecision.API_KEY, "laya-key_1.2/3+4=");
    }

    @Test
    void theRetiredModelSelectionsAreNoLongerDecisionSettings() {
        // JF-135: the Model Router offers every decision model, so no card stores a selection.
        for (var key : List.of("decision.ollama.models", "decision.laya.models", "decision.baseten.models")) {
            assertRefused(key + " is not a decision-provider setting; use one of decision.jev.apiKey, "
                    + "decision.ollama.baseUrl, decision.ollama.keepAlive, decision.laya.baseUrl, decision.laya.apiKey, "
                    + "decision.baseten.apiKey.", key, "[\"english\"]");
        }
    }

    @Test
    void theLayaKeyIsMaskedAndItsMaskIsNeverSavedOverIt() {
        assertTrue(ConfigService.isSensitive(LayaDecision.API_KEY));
        assertEquals("laya****", ConfigService.maskValue(LayaDecision.API_KEY, "laya-secret-0123"));
        assertRefused("That is the saved value's mask, not a new value. Enter the whole new value, or leave "
                + "decision.laya.apiKey unchanged.", LayaDecision.API_KEY, "laya****");
    }

    @Test
    void theBasetenKeysAreRefusedAtTheWrite() {
        assertRefused("decision.baseten.apiKey must be printable ASCII with no spaces.", BasetenDecision.API_KEY,
                "baseten key");
        assertAccepted(BasetenDecision.API_KEY, "bt-key_1.2/3+4=");
    }

    @Test
    void theBasetenKeyIsMaskedAndItsMaskIsNeverSavedOverIt() {
        assertTrue(ConfigService.isSensitive(BasetenDecision.API_KEY));
        assertEquals("base****", ConfigService.maskValue(BasetenDecision.API_KEY, "baseten-secret-0123"));
        assertRefused("That is the saved value's mask, not a new value. Enter the whole new value, or leave "
                + "decision.baseten.apiKey unchanged.", BasetenDecision.API_KEY, "base****");
    }

    @Test
    void eitherSandboxKeyAcceptsOnlyWhatTheReaderUnderstands() {
        for (var key : List.of(HarnessSandbox.SHELL_SANDBOX_KEY, HarnessSandbox.ACP_SANDBOX_KEY)) {
            for (var value : List.of("false", "true", "untrusted", "UNTRUSTED", "True", " untrusted ", "")) {
                assertAccepted(key, value);
            }
            for (var value : List.of("untrsuted", "yes", "on", "1", "truee", "untrusted2")) {
                assertRefused(key + " must be 'false', 'untrusted' or 'true' (any case); blank means off.", key, value);
            }
        }
    }

    @Test
    void theShellNetworkKeyAcceptsOnlyAllowOrDeny() {
        var key = HarnessSandbox.SHELL_NETWORK_KEY;
        for (var value : List.of("allow", "deny", "DENY", " Allow ", "")) {
            assertAccepted(key, value);
        }
        for (var value : List.of("denied", "block", "off", "false", "true", "untrusted")) {
            assertRefused(key + " must be 'allow' or 'deny' (any case); blank means allow.", key, value);
        }
    }

    @Test
    void theCodingRunParentIsAnAbsolutePathOrOneUnderHome() {
        for (var value : List.of("", "~", "~/coding-runs", "/tmp/jclaw-coding-runs")) {
            assertAccepted(SubagentSpawnTool.ACP_WORKDIR_KEY, value);
        }
        assertRefused("subagent.acp.workdir must be an absolute path or start with ~/; blank means each agent's "
                + "own workspace.", SubagentSpawnTool.ACP_WORKDIR_KEY, "coding-runs");
    }

    @Test
    void anAcpModeOutsideBatchJsonRpcIsRefused() {
        for (var value : List.of("batch", "json", "rpc", "RPC", " json ", "")) {
            assertAccepted(SubagentSpawnTool.ACP_MODE_KEY, value);
        }
        for (var value : List.of("stream", "batched", "jsonrpc")) {
            assertRefused("subagent.acp.mode must be 'batch', 'json' or 'rpc'; blank means batch.",
                    SubagentSpawnTool.ACP_MODE_KEY, value);
        }
        // The neighbouring rules still answer as before.
        assertAccepted("shell.allowlist", "ls,cat,grep");
        assertRefused("Provider 'jclaw1406-none' is not configured. subagent.acp.modelProvider must name a "
                + "provider from Settings > LLM Providers.", SubagentSpawnTool.ACP_MODEL_PROVIDER_KEY, "jclaw1406-none");
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

        var mode = SubagentSpawnTool.ACP_MODE_KEY;
        var modeBefore = ConfigService.get(mode);
        assertNotNull(ConfigService.setWithSideEffects(mode, "stream"));
        assertEquals(modeBefore, ConfigService.get(mode));
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
