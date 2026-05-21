import models.Agent;
import models.Conversation;
import models.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import slash.Commands;

import java.time.Instant;

/**
 * JCLAW-26 coverage: slash-command parsing, execution side effects,
 * findByAgentChannelPeer ordering, and contextSince filtering.
 *
 * <p>Not covered here: per-controller SSE / Telegram dispatch wiring —
 * those are thin adapters around {@link Commands#execute}. The behavior
 * that actually matters (new row creation, reset watermark, history
 * filter) lives in this test class.
 */
class SlashCommandsTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = AgentService.create("slash-agent", "openrouter", "gpt-4.1");
        // Clear any provider cache carried over from earlier tests in the
        // suite. The /model and /usage handlers read ProviderRegistry, and
        // a stale cache would leak provider metadata between tests.
        llm.ProviderRegistry.refresh();
    }

    /**
     * Seed a minimal provider config so {@link llm.ProviderRegistry} returns
     * a provider with one model when queried. Used by the {@code /model} and
     * {@code /usage} tests that need {@code resolveModel} to return non-empty.
     * The {@code modelId} parameter is retained for test-call-site clarity
     * (it labels which model the JSON represents) even though the JSON itself
     * already carries the id field.
     */
    @SuppressWarnings("java:S1172") // modelId is a documentation parameter at call sites
    private static void seedProvider(String provider, String modelId, String modelJson) {
        services.ConfigService.set("provider." + provider + ".baseUrl",
                "http://127.0.0.1:9999/v1");
        services.ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        services.ConfigService.set("provider." + provider + ".models",
                "[" + modelJson + "]");
        llm.ProviderRegistry.refresh();
    }

    // ── parse ──────────────────────────────────────────────────────────

    @Test
    void parseRecognizesAllCommands() {
        assertEquals(Commands.Command.NEW, Commands.parse("/new").orElseThrow());
        assertEquals(Commands.Command.RESET, Commands.parse("/reset").orElseThrow());
        assertEquals(Commands.Command.COMPACT, Commands.parse("/compact").orElseThrow());
        assertEquals(Commands.Command.HELP, Commands.parse("/help").orElseThrow());
        assertEquals(Commands.Command.MODEL, Commands.parse("/model").orElseThrow());
        assertEquals(Commands.Command.USAGE, Commands.parse("/usage").orElseThrow());
        assertEquals(Commands.Command.STOP, Commands.parse("/stop").orElseThrow());
    }

    // ── /compact ──────────────────────────────────────────────────────

    @Test
    void compactWithNoConversationReturnsFallback() {
        var result = Commands.execute(Commands.Command.COMPACT, agent, "web", "user1", null);
        assertEquals(Commands.Command.COMPACT, result.command());
        assertNull(result.conversation());
        assertTrue(result.responseText().contains("No active conversation"),
                "got: " + result.responseText());
    }

    @Test
    void compactWithNoProviderReturnsError() {
        // No provider seeded — registry is empty after refresh().
        var convo = ConversationService.create(agent, "web", "user1");
        var result = Commands.execute(Commands.Command.COMPACT, agent, "web", "user1", convo);
        assertEquals(Commands.Command.COMPACT, result.command());
        assertTrue(result.responseText().contains("No LLM provider"),
                "got: " + result.responseText());
    }

    @Test
    void compactOnShortConversationAcksNothingToCompact() {
        // Seed a real provider so we pass the provider gate, then invoke
        // on a conversation with only 2 messages — below even the forced
        // minimums (keepMin=4, minCompactable=2 → requires total >= 6).
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"name\":\"GPT-4.1\",\"contextWindow\":1000000,\"maxTokens\":32768}");
        var convo = ConversationService.create(agent, "web", "user1");
        ConversationService.appendUserMessage(convo, "Hi");
        ConversationService.appendAssistantMessage(convo, "Hello!", null);

        var result = Commands.execute(Commands.Command.COMPACT, agent, "web", "user1", convo);
        assertEquals(Commands.Command.COMPACT, result.command());
        assertTrue(result.responseText().contains("Nothing to compact"),
                "expected 'Nothing to compact' response, got: " + result.responseText());
        // No SessionCompaction row should be written.
        assertEquals(0L, models.SessionCompaction.count());
    }

    @Test
    void compactParsesTrailingHintAsArgs() {
        assertEquals("focus on the sql migration work",
                Commands.extractArgs("/compact focus on the sql migration work"));
        assertNull(Commands.extractArgs("/compact"));
    }

    @Test
    void parseIsCaseInsensitive() {
        assertEquals(Commands.Command.NEW, Commands.parse("/NEW").orElseThrow());
        assertEquals(Commands.Command.RESET, Commands.parse("/Reset").orElseThrow());
    }

    @Test
    void parseTrimsLeadingAndTrailingWhitespace() {
        assertEquals(Commands.Command.HELP, Commands.parse("   /help\n").orElseThrow());
    }

    @Test
    void parseIgnoresArgumentsAfterCommand() {
        // Commands don't take arguments yet — extra tokens are discarded
        // rather than rejecting the whole input.
        assertEquals(Commands.Command.NEW, Commands.parse("/new please start fresh").orElseThrow());
    }

    @Test
    void parseReturnsEmptyForUnknownSlashCommand() {
        assertTrue(Commands.parse("/tarun").isEmpty(),
                "unknown slash commands must pass through so LLM sees them");
        assertTrue(Commands.parse("/start").isEmpty(),
                "/start is Telegram's auto-onboarding — reserved for JCLAW-97");
    }

    @Test
    void parseReturnsEmptyForNonSlashInput() {
        assertTrue(Commands.parse("hello").isEmpty());
        assertTrue(Commands.parse("").isEmpty());
        assertTrue(Commands.parse(null).isEmpty());
        assertTrue(Commands.parse("   ").isEmpty());
        // Must start with a slash — midline slashes don't count.
        assertTrue(Commands.parse("see /help for info").isEmpty());
    }

    // ── /new ───────────────────────────────────────────────────────────

    @Test
    void newCreatesFreshConversationAndIgnoresCurrent() {
        var existing = ConversationService.findOrCreate(agent, "telegram", "user-123");
        ConversationService.appendUserMessage(existing, "earlier message");

        var result = Commands.execute(
                Commands.Command.NEW, agent, "telegram", "user-123", existing);

        assertNotNull(result.conversation(),
                "/new must return a non-null new conversation");
        assertNotEquals(existing.id, result.conversation().id,
                "/new must create a fresh Conversation row, not reuse the current one");
        assertEquals(Commands.NEW_TEXT, result.responseText());

        // The new conversation has exactly the canned assistant message.
        var msgs = ConversationService.loadRecentMessages(result.conversation());
        assertEquals(1, msgs.size());
        assertEquals("assistant", msgs.getFirst().role);
        assertEquals(Commands.NEW_TEXT, msgs.getFirst().content);

        // The existing conversation is untouched.
        var existingMsgs = ConversationService.loadRecentMessages(existing);
        assertEquals(1, existingMsgs.size());
        assertEquals("earlier message", existingMsgs.getFirst().content);
    }

    @Test
    void findByAgentChannelPeerReturnsMostRecentAfterNew() {
        // Critical for the /new handler to "stick" on Telegram: subsequent
        // findOrCreate calls must resolve to the new row, not the old one.
        // Before JCLAW-26 the JPQL had no ORDER BY — first() could return
        // either in insertion order, which would route the next Telegram
        // message back to the pre-/new conversation.
        var first = ConversationService.findOrCreate(agent, "telegram", "user-456");
        Commands.execute(Commands.Command.NEW, agent, "telegram", "user-456", first);

        var resolvedAfterNew = ConversationService.findOrCreate(agent, "telegram", "user-456");
        assertNotEquals(first.id, resolvedAfterNew.id,
                "findOrCreate must return the most recent row after /new creates a new one");
    }

    // ── /reset ─────────────────────────────────────────────────────────

    @Test
    void resetStampsContextSinceOnCurrentConversation() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var before = Instant.now();

        var result = Commands.execute(
                Commands.Command.RESET, agent, "web", "admin", convo);

        assertNotNull(result.conversation());
        assertEquals(convo.id, result.conversation().id,
                "/reset must keep the same conversation row");
        assertNotNull(result.conversation().contextSince,
                "/reset must stamp a contextSince watermark");
        assertFalse(result.conversation().contextSince.isBefore(before),
                "contextSince must be set after the /reset call started");
    }

    @Test
    void resetExcludesPriorMessagesFromLlmContext() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "pre-reset question");
        ConversationService.appendAssistantMessage(convo, "pre-reset answer", null);

        Commands.execute(Commands.Command.RESET, agent, "web", "admin", convo);

        // Simulate a subsequent user turn.
        var reloaded = ConversationService.findById(convo.id);
        ConversationService.appendUserMessage(reloaded, "post-reset question");

        var llmContext = ConversationService.loadRecentMessages(reloaded);
        // /reset doesn't persist any new Message (the ack is transient —
        // delivered via the channel's response mechanism only). So the only
        // message strictly newer than contextSince is the post-reset turn.
        assertEquals(1, llmContext.size(),
                "LLM context must exclude pre-reset messages");
        assertEquals("post-reset question", llmContext.getFirst().content);
    }

    @Test
    void resetDoesNotPersistAck() {
        // /reset is a control signal — the ack text is delivered to the
        // user via the channel's response path (SSE complete frame / sink
        // seal) but must not enter Message history. Persisting it created
        // a clock-alignment bug on Linux where the ack.createdAt and
        // contextSince rounded to the same TIMESTAMP and the ">" filter
        // flaked depending on driver rounding direction. The invariant
        // that prevents it: no new rows from /reset at all.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "first");
        ConversationService.appendAssistantMessage(convo, "second", null);
        var countBefore = Message.count("conversation = ?1", convo);

        Commands.execute(Commands.Command.RESET, agent, "web", "admin", convo);

        var countAfter = Message.count("conversation = ?1", convo);
        assertEquals(countBefore, countAfter,
                "/reset must not persist any new Message row");
    }

    @Test
    void resetWithoutCurrentConversationIsSafe() {
        // Defensive: caller may pass a null conversation (e.g. channel hasn't
        // resolved one yet). Should not throw; returns a fallback response.
        var result = Commands.execute(
                Commands.Command.RESET, agent, "web", "admin", null);
        assertNull(result.conversation());
        assertNotNull(result.responseText());
    }

    // ── /help ──────────────────────────────────────────────────────────

    @Test
    void helpReturnsHelpTextAndDoesNotMutateState() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "prior message");

        var result = Commands.execute(
                Commands.Command.HELP, agent, "web", "admin", convo);

        assertEquals(Commands.HELP_TEXT, result.responseText());
        assertEquals(convo.id, result.conversation().id);
        // Conversation still has contextSince = null — /help is not a reset.
        var reloaded = ConversationService.findById(convo.id);
        assertNull(reloaded.contextSince);
    }

    @Test
    void helpTextListsAllRecognizedCommands() {
        // If a new command is added, HELP_TEXT must be updated — this test
        // fails loudly when any command is missing from the listing (a cheap
        // guardrail against silent drift).
        assertTrue(Commands.HELP_TEXT.contains("/new"));
        assertTrue(Commands.HELP_TEXT.contains("/reset"));
        assertTrue(Commands.HELP_TEXT.contains("/help"));
        assertTrue(Commands.HELP_TEXT.contains("/model"));
        assertTrue(Commands.HELP_TEXT.contains("/usage"));
        assertTrue(Commands.HELP_TEXT.contains("/stop"));
    }

    // ── /model ─────────────────────────────────────────────────────────

    @Test
    void modelWithoutProviderConfigExplainsMismatch() {
        // No provider seeded in ConfigService — resolveModel returns empty,
        // handler degrades to a clear explanation instead of NPE. This is
        // the "model removed from provider config after agent assignment"
        // AC path from JCLAW-107.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var result = Commands.execute(Commands.Command.MODEL, agent, "web", "admin", convo);

        assertEquals(Commands.Command.MODEL, result.command());
        assertTrue(result.responseText().contains("Model not found in provider config"),
                "degradation message should explain the mismatch: " + result.responseText());
        assertTrue(result.responseText().contains("openrouter"));
        assertTrue(result.responseText().contains("gpt-4.1"));
    }

    @Test
    void modelWithProviderConfigRendersCapabilities() {
        // Seed a provider with a model whose metadata covers the full set
        // JCLAW-107 renders. The response must name all seven fields plus
        // pricing so users coming from OpenClaw's /model see parity content.
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"name\":\"GPT 4.1\",\"contextWindow\":128000,"
                        + "\"maxTokens\":8192,\"supportsThinking\":true,"
                        + "\"supportsVision\":true,\"supportsAudio\":false,"
                        + "\"promptPrice\":3.0,\"completionPrice\":15.0,"
                        + "\"cachedReadPrice\":0.30,\"cacheWritePrice\":3.75}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.execute(Commands.Command.MODEL, agent, "web", "admin", convo);
        var text = result.responseText();

        assertTrue(text.contains("openrouter/gpt-4.1"), "model id shown: " + text);
        assertTrue(text.contains("128K"), "context window formatted as K: " + text);
        assertTrue(text.contains("8K"), "max output formatted as K: " + text);
        assertTrue(text.contains("Thinking: supported"), "thinking flag shown: " + text);
        assertTrue(text.contains("Vision: supported"), "vision flag shown: " + text);
        assertTrue(text.contains("Audio: not supported"), "audio flag shown: " + text);
        assertTrue(text.contains("$3.00"), "prompt price shown: " + text);
        assertTrue(text.contains("$15.00"), "completion price shown: " + text);
    }

    @Test
    void modelPersistsResponseAsAssistantMessage() {
        // Parity with /help: the canned response is appended to the
        // conversation so scrollback / reload shows the answer.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var countBefore = Message.count("conversation = ?1", convo);

        Commands.execute(Commands.Command.MODEL, agent, "web", "admin", convo);

        var countAfter = Message.count("conversation = ?1", convo);
        assertEquals(countBefore + 1, countAfter,
                "/model must persist its response as a new assistant message");
        var msgs = ConversationService.loadRecentMessages(convo);
        assertEquals("assistant", msgs.getLast().role);
    }

    // ── /model NAME (write path) ───────────────────────────────────────

    @Test
    void modelSwitchWritesOverrideAndConfirms() {
        // JCLAW-108: /model provider/model-id populates both override columns
        // atomically and returns a confirmation naming the new model.
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":128000}");
        seedProvider("ollama-cloud", "kimi-k2",
                "{\"id\":\"kimi-k2\",\"contextWindow\":200000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.handle("/model ollama-cloud/kimi-k2",
                agent, "web", "admin", convo).orElseThrow();

        // Reload from DB to confirm the override landed.
        var reloaded = ConversationService.findById(convo.id);
        assertEquals("ollama-cloud", reloaded.modelProviderOverride);
        assertEquals("kimi-k2", reloaded.modelIdOverride);
        assertTrue(result.responseText().contains("Switched this conversation to"),
                "response confirms the switch: " + result.responseText());
        assertTrue(result.responseText().contains("ollama-cloud/kimi-k2"));
    }

    @Test
    void modelSwitchRejectsUnknownProvider() {
        // Validation path: unknown provider → no write, clear explanation.
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":128000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.handle("/model nobody/no-model",
                agent, "web", "admin", convo).orElseThrow();

        var reloaded = ConversationService.findById(convo.id);
        assertNull(reloaded.modelProviderOverride, "no override on validation failure");
        assertNull(reloaded.modelIdOverride);
        assertTrue(result.responseText().contains("Provider `nobody` is not configured"),
                "message names the missing provider: " + result.responseText());
    }

    @Test
    void modelSwitchRejectsUnknownModelWithinKnownProvider() {
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":128000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.handle("/model openrouter/some-phantom",
                agent, "web", "admin", convo).orElseThrow();

        var reloaded = ConversationService.findById(convo.id);
        assertNull(reloaded.modelIdOverride);
        assertTrue(result.responseText().contains("no model with id `some-phantom`"),
                "message names the missing model: " + result.responseText());
    }

    @Test
    void modelSwitchRejectsMalformedArgument() {
        // No slash, or empty provider / model id → helpful format error, no write.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.handle("/model just-one-word",
                agent, "web", "admin", convo).orElseThrow();

        assertTrue(result.responseText().contains("Unrecognized model format"),
                "format error shown: " + result.responseText());
    }

    @Test
    void modelSwitchEmitsShrinkageWarningWhenWindowShrinks() {
        // Seed two models: a 200K-window one that ran the last turn, and a
        // 10K-window one the user is switching to. Previous turn's prompt
        // was 50K → exceeds the new window → warning must appear.
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":200000}");
        seedProvider("tiny", "tiny-model",
                "{\"id\":\"tiny-model\",\"contextWindow\":10000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendAssistantMessage(convo, "last answer", null,
                "{\"prompt\":50000,\"completion\":1000,\"total\":51000}");

        var result = Commands.handle("/model tiny/tiny-model",
                agent, "web", "admin", convo).orElseThrow();

        var text = result.responseText();
        assertTrue(text.contains("Warning"), "shrinkage warning present: " + text);
        assertTrue(text.contains("10K"), "new window named: " + text);
        assertTrue(text.contains("50,000"), "current size named: " + text);
        // Override STILL lands even with the warning.
        var reloaded = ConversationService.findById(convo.id);
        assertEquals("tiny", reloaded.modelProviderOverride);
    }

    @Test
    void modelSwitchDoesNotWarnWhenNewWindowIsLarger() {
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":100000}");
        seedProvider("big", "big-model",
                "{\"id\":\"big-model\",\"contextWindow\":1000000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendAssistantMessage(convo, "last answer", null,
                "{\"prompt\":5000,\"completion\":1000,\"total\":6000}");

        var result = Commands.handle("/model big/big-model",
                agent, "web", "admin", convo).orElseThrow();

        assertFalse(result.responseText().contains("Warning"),
                "no warning when switching up: " + result.responseText());
    }

    // ── /model reset ───────────────────────────────────────────────────

    @Test
    void modelResetClearsOverrideAndConfirms() {
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":128000}");
        seedProvider("ollama-cloud", "kimi-k2",
                "{\"id\":\"kimi-k2\",\"contextWindow\":200000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        // First set an override.
        Commands.handle("/model ollama-cloud/kimi-k2", agent, "web", "admin", convo);

        var result = Commands.handle("/model reset",
                agent, "web", "admin", convo).orElseThrow();

        var reloaded = ConversationService.findById(convo.id);
        assertNull(reloaded.modelProviderOverride, "override cleared");
        assertNull(reloaded.modelIdOverride);
        assertTrue(result.responseText().contains("Reverted to agent default"),
                "reset confirms: " + result.responseText());
        assertTrue(result.responseText().contains("openrouter/gpt-4.1"),
                "agent default named: " + result.responseText());
    }

    @Test
    void modelResetWithoutPriorOverrideIsNoOp() {
        // Idempotent: calling /model reset without a prior override still
        // succeeds and reports the current (unchanged) agent default.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.handle("/model reset",
                agent, "web", "admin", convo).orElseThrow();

        assertTrue(result.responseText().contains("no override"),
                "idempotent path reports no-op: " + result.responseText());
    }

    // ── /model (display) with override ─────────────────────────────────

    @Test
    void modelDisplayReflectsActiveOverride() {
        // Once an override is set, /model (no args) shows the override's
        // identity along with a note naming the agent default.
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":128000}");
        seedProvider("ollama-cloud", "kimi-k2",
                "{\"id\":\"kimi-k2\",\"contextWindow\":200000,\"supportsVision\":true}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        Commands.handle("/model ollama-cloud/kimi-k2", agent, "web", "admin", convo);

        var result = Commands.handle("/model", agent, "web", "admin", convo).orElseThrow();
        var text = result.responseText();

        assertTrue(text.contains("Model: ollama-cloud/kimi-k2"),
                "override shown on top line: " + text);
        assertTrue(text.contains("Conversation override active"),
                "scope note present: " + text);
        assertTrue(text.contains("agent default is openrouter/gpt-4.1"),
                "agent default named in scope note: " + text);
        assertTrue(text.contains("200K"), "override's context window shown: " + text);
    }

    // ── /usage ─────────────────────────────────────────────────────────

    @Test
    void usageOnFreshConversationReportsZeros() {
        // AC: a brand-new conversation with zero assistant turns shows
        // Input: 0 / Output: 0 / Total: 0. The percentage line depends on
        // whether contextWindow is known; with no provider seeded it falls
        // back to "unknown (model metadata incomplete)".
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":128000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        var result = Commands.execute(Commands.Command.USAGE, agent, "web", "admin", convo);
        var text = result.responseText();

        assertTrue(text.contains("Input: 0 tokens"), "zero input: " + text);
        assertTrue(text.contains("Output: 0 tokens"), "zero output: " + text);
        assertTrue(text.contains("Total: 0 tokens"), "zero total: " + text);
        assertTrue(text.contains("0% of 128K"), "percentage against known window: " + text);
        assertTrue(text.contains("openrouter/gpt-4.1"), "model id shown: " + text);
    }

    @Test
    void usagePullsLatestAssistantTurnTokens() {
        // After a real assistant turn with usage data, /usage surfaces THAT
        // turn's prompt and completion — not a lifetime accumulation. This
        // is the "current context size" interpretation that makes the
        // percentage meaningful.
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"contextWindow\":100000}");
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "q1");
        ConversationService.appendAssistantMessage(convo, "a1", null,
                "{\"prompt\":1000,\"completion\":200,\"total\":1200}");
        ConversationService.appendUserMessage(convo, "q2");
        ConversationService.appendAssistantMessage(convo, "a2", null,
                "{\"prompt\":25000,\"completion\":500,\"total\":25500}");

        var result = Commands.execute(Commands.Command.USAGE, agent, "web", "admin", convo);
        var text = result.responseText();

        assertTrue(text.contains("Input: 25,000 tokens"), "latest prompt shown with thousands separator: " + text);
        assertTrue(text.contains("Output: 500 tokens"), "latest completion shown: " + text);
        assertTrue(text.contains("Total: 25,500 tokens"), "total equals latest prompt + completion: " + text);
        // 25000 / 100000 = 25%
        assertTrue(text.contains("25% of 100K"), "percentage from latest prompt: " + text);
    }

    @Test
    void usageMarksContextUnknownWhenModelMetadataIsMissing() {
        // Divide-by-zero guard: when contextWindow is absent or zero the
        // percentage math is meaningless. Response must render the tokens
        // but tag the context line as unknown rather than showing "NaN%"
        // or throwing.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendAssistantMessage(convo, "a", null,
                "{\"prompt\":50,\"completion\":10,\"total\":60}");

        var result = Commands.execute(Commands.Command.USAGE, agent, "web", "admin", convo);
        var text = result.responseText();

        assertTrue(text.contains("Context: unknown"),
                "unknown sentinel when provider config missing: " + text);
    }

    @Test
    void usagePersistsResponseAsAssistantMessage() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var countBefore = Message.count("conversation = ?1", convo);

        Commands.execute(Commands.Command.USAGE, agent, "web", "admin", convo);

        var countAfter = Message.count("conversation = ?1", convo);
        assertEquals(countBefore + 1, countAfter,
                "/usage must persist its response as a new assistant message");
    }

    @Test
    void usageWithoutCurrentConversationIsSafe() {
        // Parity with /reset's null-current handling: no conversation to
        // inspect, no token totals to compute. Must not throw.
        var result = Commands.execute(Commands.Command.USAGE, agent, "web", "admin", null);
        assertNull(result.conversation());
        assertNotNull(result.responseText());
    }

    // ── /stop ──────────────────────────────────────────────────────────

    @Test
    void stopWithNoCurrentConversationReturnsFallback() {
        var result = Commands.execute(Commands.Command.STOP, agent, "web", "admin", null);
        assertEquals(Commands.Command.STOP, result.command());
        assertNull(result.conversation());
        assertTrue(result.responseText().contains("No active conversation"),
                "got: " + result.responseText());
    }

    @Test
    void stopOnIdleConversationReportsNothingToStop() {
        // Conversation exists but no in-flight processing — ConversationQueue
        // has never seen this conversation id, so isBusy returns false.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var result = Commands.execute(Commands.Command.STOP, agent, "web", "admin", convo);
        assertEquals(Commands.Command.STOP, result.command());
        assertEquals(convo.id, result.conversation().id);
        assertEquals("Nothing to stop.", result.responseText());
        // Flag must NOT be set when there's nothing to interrupt — a stale
        // true would cancel the next legitimate turn before its first poll.
        assertFalse(services.ConversationQueue.cancellationFlag(convo.id).get(),
                "/stop on an idle conversation must not set the cancellation flag");
    }

    @Test
    void stopOnBusyConversationSetsCancellationFlag() {
        // Drive the queue into "processing" state via tryAcquire so isBusy
        // returns true. Then /stop must flip the cancellation flag — the
        // streaming thread's checkCancelled poll observes it on its next
        // checkpoint and short-circuits the loop.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                "in-flight prompt", "web", "admin", agent);
        var acquired = services.ConversationQueue.tryAcquire(convo.id, queueMsg);
        assertTrue(acquired, "tryAcquire on a fresh conversation should succeed");
        try {
            assertTrue(services.ConversationQueue.isBusy(convo.id),
                    "conversation must register as busy after tryAcquire");

            var result = Commands.execute(Commands.Command.STOP, agent, "web", "admin", convo);

            assertEquals(Commands.Command.STOP, result.command());
            assertEquals("Stopped.", result.responseText());
            assertTrue(services.ConversationQueue.cancellationFlag(convo.id).get(),
                    "/stop on a busy conversation must set the cancellation flag");
        } finally {
            // Release ownership so subsequent tests don't see this conversation
            // as permanently busy. drain() with an empty pending deque flips
            // processing back to false.
            services.ConversationQueue.releaseOwnership(convo.id);
        }
    }

    @Test
    void stopDoesNotPersistAck() {
        // Mirror /reset: /stop is a control signal, not conversation content.
        // Persisting "Stopped." next to a partial assistant message would
        // skew /usage accounting and clutter history.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "first");
        ConversationService.appendAssistantMessage(convo, "second", null);
        var countBefore = Message.count("conversation = ?1", convo);

        Commands.execute(Commands.Command.STOP, agent, "web", "admin", convo);

        var countAfter = Message.count("conversation = ?1", convo);
        assertEquals(countBefore, countAfter,
                "/stop must not persist any new Message row");
    }

    // ── handle() convenience ───────────────────────────────────────────

    @Test
    void handleReturnsEmptyForNonSlashInput() {
        var current = ConversationService.findOrCreate(agent, "web", "admin");
        var result = Commands.handle("regular message", agent, "web", "admin", current);
        assertTrue(result.isEmpty(),
                "handle() must return empty so the caller proceeds to the LLM");
    }

    @Test
    void handleDispatchesRecognizedCommand() {
        var current = ConversationService.findOrCreate(agent, "web", "admin");
        var result = Commands.handle("/help", agent, "web", "admin", current);
        assertTrue(result.isPresent());
        assertEquals(Commands.Command.HELP, result.get().command());
    }

    // --- private static helpers (reflection access) ---

    private String summarizeToolField(String s) throws Exception {
        var m = Commands.class.getDeclaredMethod("summarizeToolField", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private String extractJsonField(String json, String key) throws Exception {
        var m = Commands.class.getDeclaredMethod("extractJsonField", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json, key);
    }

    @Test
    void summarizeToolFieldFormatsShortStringInline() throws Exception {
        var result = summarizeToolField("short input");
        assertEquals("(11 chars) short input", result);
    }

    @Test
    void summarizeToolFieldTruncatesAtCapAndAppendsEllipsis() throws Exception {
        var big = "x".repeat(200);
        var result = summarizeToolField(big);
        assertTrue(result.startsWith("(200 chars) "), "got: " + result);
        assertTrue(result.endsWith("..."), "got: " + result);
    }

    @Test
    void summarizeToolFieldCollapsesInternalWhitespace() throws Exception {
        var result = summarizeToolField("a\n\nb   c\td");
        assertTrue(result.contains("a b c d"), "whitespace collapsed: " + result);
        assertFalse(result.contains("\n"), "newlines removed: " + result);
    }

    @Test
    void extractJsonFieldReturnsNullForNullInput() throws Exception {
        assertNull(extractJsonField(null, "any"));
    }

    @Test
    void extractJsonFieldReturnsNullForBlankInput() throws Exception {
        assertNull(extractJsonField("   ", "any"));
    }

    @Test
    void extractJsonFieldReturnsNullForMissingKey() throws Exception {
        assertNull(extractJsonField("{\"other\":\"value\"}", "missing"));
    }

    @Test
    void extractJsonFieldReturnsNullForNullValue() throws Exception {
        assertNull(extractJsonField("{\"k\":null}", "k"));
    }

    @Test
    void extractJsonFieldReturnsNullForMalformedJson() throws Exception {
        assertNull(extractJsonField("{not json", "k"));
    }

    @Test
    void extractJsonFieldReturnsStringValueForFlatKey() throws Exception {
        assertEquals("v", extractJsonField("{\"k\":\"v\"}", "k"));
    }

    // --- formatPrice + findLatestAssistantUsage helpers ---

    private String formatPrice(double perMillion) throws Exception {
        var m = Commands.class.getDeclaredMethod("formatPrice", double.class);
        m.setAccessible(true);
        return (String) m.invoke(null, perMillion);
    }

    @Test
    void formatPriceReturnsNaForNegative() throws Exception {
        assertEquals("n/a", formatPrice(-1.0));
    }

    @Test
    void formatPriceReturnsFreeForZero() throws Exception {
        assertEquals("free", formatPrice(0.0));
    }

    @Test
    void formatPriceUsesThreeDecimalsBelowOneDollar() throws Exception {
        // sub-dollar prices render with 3 decimals for granularity
        var result = formatPrice(0.125);
        assertEquals("$0.125", result);
    }

    @Test
    void formatPriceUsesTwoDecimalsAtOrAboveOneDollar() throws Exception {
        var result = formatPrice(1.50);
        assertEquals("$1.50", result);
    }

    private int[] findLatestAssistantUsage(java.util.List<models.Message> messages) throws Exception {
        var m = Commands.class.getDeclaredMethod("findLatestAssistantUsage", java.util.List.class);
        m.setAccessible(true);
        return (int[]) m.invoke(null, messages);
    }

    @Test
    void findLatestAssistantUsageReturnsNullForNullList() throws Exception {
        assertNull(findLatestAssistantUsage(null));
    }

    @Test
    void findLatestAssistantUsageReturnsNullForEmptyList() throws Exception {
        assertNull(findLatestAssistantUsage(java.util.List.of()));
    }

    @Test
    void findLatestAssistantUsageSkipsNonAssistantAndBlankUsage() throws Exception {
        // user role + blank-usage assistant must both be skipped; only the
        // last well-formed assistant row contributes.
        var messages = new java.util.ArrayList<models.Message>();
        var user = new models.Message();
        user.role = "user";
        user.content = "hi";
        messages.add(user);
        var blank = new models.Message();
        blank.role = "assistant";
        blank.usageJson = "";
        messages.add(blank);
        var good = new models.Message();
        good.role = "assistant";
        good.usageJson = "{\"prompt\":100,\"completion\":50}";
        messages.add(good);

        var result = findLatestAssistantUsage(messages);
        assertNotNull(result);
        assertEquals(100, result[0]);
        assertEquals(50, result[1]);
    }

    @Test
    void findLatestAssistantUsageSkipsDurationOnlyRows() throws Exception {
        // assistant row whose usageJson lacks "prompt" must be skipped — it's
        // a cancelled-turn artifact. Scanner walks back to the earlier good row.
        var messages = new java.util.ArrayList<models.Message>();
        var good = new models.Message();
        good.role = "assistant";
        good.usageJson = "{\"prompt\":200,\"completion\":30}";
        messages.add(good);
        var partial = new models.Message();
        partial.role = "assistant";
        partial.usageJson = "{\"durationMs\":1500}"; // no "prompt"
        messages.add(partial);

        var result = findLatestAssistantUsage(messages);
        assertNotNull(result);
        assertEquals(200, result[0]);
    }

    // --- /usage end-to-end ---

    @Test
    void usageReturnsFallbackWhenNoConversation() {
        // executeUsage path with current=null. buildUsageResponse short-
        // circuits to the "No active conversation" fallback.
        var result = Commands.handle("/usage", agent, "web", "admin", null);
        assertTrue(result.isPresent());
        assertEquals(Commands.Command.USAGE, result.get().command());
        assertTrue(result.get().responseText().contains("No active conversation"),
                "fallback message present: " + result.get().responseText());
    }

    @Test
    void usageRendersTokenCountsForConversationWithAssistantUsage() {
        // Happy path: create a conversation, append an assistant message
        // with usageJson carrying real token counts, then run /usage. The
        // response must surface the input/output token lines.
        var conv = ConversationService.findOrCreate(agent, "web", "admin");
        services.Tx.run(() -> {
            ConversationService.appendUserMessage(conv, "hi");
            ConversationService.appendAssistantMessage(conv, "hello back",
                    null, "{\"prompt\":1234,\"completion\":56}");
            return null;
        });
        var result = Commands.handle("/usage", agent, "web", "admin", conv);
        assertTrue(result.isPresent());
        var text = result.get().responseText();
        assertTrue(text.contains("Input:"), "input line present: " + text);
        assertTrue(text.contains("Output:"), "output line present: " + text);
        assertTrue(text.contains("Total:"), "total line present: " + text);
        assertTrue(text.contains("Model:"), "model line present: " + text);
    }

    // --- formatTokenCapacity branches ---

    private String formatTokenCapacity(int tokens) throws Exception {
        var m = Commands.class.getDeclaredMethod("formatTokenCapacity", int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, tokens);
    }

    @Test
    void formatTokenCapacityReturnsQuestionMarkForZeroOrNegative() throws Exception {
        assertEquals("?", formatTokenCapacity(0));
        assertEquals("?", formatTokenCapacity(-1));
    }

    @Test
    void formatTokenCapacityFormatsRoundMillions() throws Exception {
        // Even-million path: 2_000_000 → "2M".
        assertEquals("2M", formatTokenCapacity(2_000_000));
    }

    @Test
    void formatTokenCapacityFormatsFractionalMillions() throws Exception {
        // Non-even-million path: 1_500_000 → "1.5M".
        assertEquals("1.5M", formatTokenCapacity(1_500_000));
    }

    @Test
    void formatTokenCapacityFormatsKilo() throws Exception {
        // Sub-million ≥ 1K → integer-divide K rendering.
        assertEquals("128K", formatTokenCapacity(128_000));
    }

    @Test
    void formatTokenCapacityFormatsSmallValuesVerbatim() throws Exception {
        assertEquals("42", formatTokenCapacity(42));
    }

    // --- buildCompactResponseText branches ---

    private String callBuildCompactResponseText(services.SessionCompactor.CompactionResult result, String args)
            throws Exception {
        var m = Commands.class.getDeclaredMethod(
                "buildCompactResponseText",
                services.SessionCompactor.CompactionResult.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, result, args);
    }

    @Test
    void buildCompactResponseTextNoSafeBoundary() throws Exception {
        var result = services.SessionCompactor.CompactionResult.skipped(
                "no safe boundary or below min-turns");
        var text = callBuildCompactResponseText(result, null);
        assertTrue(text.contains("Nothing to compact"), "got: " + text);
    }

    @Test
    void buildCompactResponseTextLlmError() throws Exception {
        var result = services.SessionCompactor.CompactionResult.skipped("llm error");
        var text = callBuildCompactResponseText(result, null);
        assertTrue(text.contains("Compaction failed"), "got: " + text);
        assertTrue(text.contains("LLM call"), "got: " + text);
    }

    @Test
    void buildCompactResponseTextEmptySummary() throws Exception {
        var result = services.SessionCompactor.CompactionResult.skipped("empty summary");
        var text = callBuildCompactResponseText(result, null);
        assertTrue(text.contains("empty response"), "got: " + text);
    }

    @Test
    void buildCompactResponseTextGenericSkipReason() throws Exception {
        var result = services.SessionCompactor.CompactionResult.skipped("some other reason");
        var text = callBuildCompactResponseText(result, null);
        assertTrue(text.contains("Compaction skipped"), "got: " + text);
        assertTrue(text.contains("some other reason"), "got: " + text);
    }

    @Test
    void buildCompactResponseTextNullSkipReason() throws Exception {
        var result = services.SessionCompactor.CompactionResult.skipped(null);
        var text = callBuildCompactResponseText(result, null);
        assertTrue(text.contains("unknown reason"), "got: " + text);
    }

    @Test
    void buildCompactResponseTextSuccessNoHint() throws Exception {
        var result = services.SessionCompactor.CompactionResult.success(3, 800);
        var text = callBuildCompactResponseText(result, null);
        assertTrue(text.contains("Conversation Compacted"), "got: " + text);
        assertTrue(text.contains("Summarized 3 earlier turns"), "got: " + text);
        assertFalse(text.contains("guidance:"), "no hint with null args: " + text);
    }

    @Test
    void buildCompactResponseTextSuccessBlankArgsNoHint() throws Exception {
        var result = services.SessionCompactor.CompactionResult.success(2, 400);
        var text = callBuildCompactResponseText(result, "   ");
        assertFalse(text.contains("guidance:"),
                "blank args must not surface guidance: " + text);
    }

    @Test
    void buildCompactResponseTextSuccessWithHintEchoesArgs() throws Exception {
        var result = services.SessionCompactor.CompactionResult.success(5, 1200);
        var text = callBuildCompactResponseText(result, "focus on code review notes");
        assertTrue(text.contains("(guidance: focus on code review notes)"),
                "guidance must echo args verbatim: " + text);
    }

    @Test
    void findLatestAssistantUsageSkipsMalformedJson() throws Exception {
        // Exception-catch path — malformed usageJson must not crash; the
        // scanner moves on.
        var messages = new java.util.ArrayList<models.Message>();
        var good = new models.Message();
        good.role = "assistant";
        good.usageJson = "{\"prompt\":42,\"completion\":7}";
        messages.add(good);
        var malformed = new models.Message();
        malformed.role = "assistant";
        malformed.usageJson = "{not valid";
        messages.add(malformed);

        var result = findLatestAssistantUsage(messages);
        assertNotNull(result);
        assertEquals(42, result[0]);
    }
}
