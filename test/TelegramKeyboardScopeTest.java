import channels.TelegramCallbackDispatcher;
import channels.TelegramChannel;
import channels.TelegramChannel.InboundCallback;
import channels.TelegramModelCallback;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConversationService;

/**
 * JCLAW-387 C2: operator scope gate for interactive inline keyboards.
 * {@code telegram.keyboardScope} ∈ {off, dm, group, all} (default {@code all})
 * controls which Telegram chat types may honor a {@code callback_query}.
 *
 * <p>Drives the real {@link TelegramCallbackDispatcher} against the embedded
 * {@link MockTelegramServer} (same harness as {@code TelegramModelSelectorTest})
 * and asserts the observable wire behavior of the gate:
 * <ul>
 *   <li><b>allowed</b> → the handler runs: the BROWSE callback acks AND edits
 *       the message to the providers list.</li>
 *   <li><b>rejected</b> → exactly one {@code answerCallbackQuery} carrying the
 *       "disabled" notice, and zero {@code editMessageText} — the handler never
 *       runs.</li>
 * </ul>
 *
 * <p>Also covers the {@code public} {@link TelegramCallbackDispatcher#keyboardScopeAllows}
 * predicate directly, since the keyboard-SEND sites will consume it later.
 */
class TelegramKeyboardScopeTest extends UnitTest {

    private static final String BOT_TOKEN = "mock-bot-token";
    private static final String CHAT_ID = "77777";
    private static final String SCOPE_KEY = "telegram.keyboardScope";

    private MockTelegramServer server;
    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        services.ConfigService.clearCache();
        // Start from the default — each test sets the scope it wants explicitly.
        play.Play.configuration.remove(SCOPE_KEY);
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
        seedProvider("openrouter",
                "{\"id\":\"gpt-4.1\",\"name\":\"GPT 4.1\",\"contextWindow\":128000,\"maxTokens\":8192}");
        seedProvider("ollama-cloud",
                "{\"id\":\"kimi-k2\",\"contextWindow\":200000,\"supportsVision\":true}");
        agent = AgentService.create("scope-agent", "openrouter", "gpt-4.1");
        services.Tx.run(() -> {
            var binding = new models.TelegramBinding();
            binding.agent = agent;
            binding.botToken = BOT_TOKEN;
            binding.telegramUserId = "tg-user-1";
            binding.enabled = true;
            binding.save();
        });
        conversation = services.Tx.run(() ->
                ConversationService.findOrCreate(agent, "telegram", CHAT_ID));
    }

    @AfterEach
    void teardown() {
        play.Play.configuration.remove(SCOPE_KEY);
        if (server != null) server.close();
        TelegramChannel.clearForTest(BOT_TOKEN);
    }

    // ── scope = all (default): honored in every chat type ─────────────────

    @Test
    void allScopeHandlesCallbackInPrivateChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "all");
        dispatchBrowse("private");
        assertHandlerRan();
    }

    @Test
    void allScopeHandlesCallbackInGroupChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "all");
        dispatchBrowse("group");
        assertHandlerRan();
    }

    @Test
    void missingConfigDefaultsToAllAndHandlesEverywhere() {
        // No telegram.keyboardScope set — must behave exactly like "all".
        play.Play.configuration.remove(SCOPE_KEY);
        dispatchBrowse("supergroup");
        assertHandlerRan();
    }

    // ── scope = off: rejected everywhere ──────────────────────────────────

    @Test
    void offScopeRejectsInPrivateChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "off");
        dispatchBrowse("private");
        assertRejectedWithNotice();
    }

    @Test
    void offScopeRejectsInGroupChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "off");
        dispatchBrowse("group");
        assertRejectedWithNotice();
    }

    // ── scope = dm: private honored, group rejected ───────────────────────

    @Test
    void dmScopeHandlesPrivateChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "dm");
        dispatchBrowse("private");
        assertHandlerRan();
    }

    @Test
    void dmScopeRejectsGroupChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "dm");
        dispatchBrowse("group");
        assertRejectedWithNotice();
    }

    // ── scope = group: group/supergroup honored, private rejected ─────────

    @Test
    void groupScopeHandlesGroupChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "group");
        dispatchBrowse("group");
        assertHandlerRan();
    }

    @Test
    void groupScopeHandlesSupergroupChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "group");
        dispatchBrowse("supergroup");
        assertHandlerRan();
    }

    @Test
    void groupScopeRejectsPrivateChat() {
        play.Play.configuration.setProperty(SCOPE_KEY, "group");
        dispatchBrowse("private");
        assertRejectedWithNotice();
    }

    // ── Public predicate (consumed by keyboard-SEND sites later) ──────────

    @Test
    void predicateAllPermitsEveryChatType() {
        play.Play.configuration.setProperty(SCOPE_KEY, "all");
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("private"));
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("group"));
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("supergroup"));
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("channel"));
        // Even a null/unknown chat type fails open under "all".
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows(null));
    }

    @Test
    void predicateOffPermitsNothing() {
        play.Play.configuration.setProperty(SCOPE_KEY, "off");
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("private"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("group"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("supergroup"));
    }

    @Test
    void predicateDmPermitsOnlyPrivate() {
        play.Play.configuration.setProperty(SCOPE_KEY, "dm");
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("private"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("group"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("supergroup"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows(null));
    }

    @Test
    void predicateGroupPermitsOnlyGroupAndSupergroup() {
        play.Play.configuration.setProperty(SCOPE_KEY, "group");
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("group"));
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("supergroup"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("private"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows(null));
    }

    @Test
    void predicateUnknownScopeFailsOpenToAll() {
        // A typo in the operator config must not silently disable all keyboards.
        play.Play.configuration.setProperty(SCOPE_KEY, "wat");
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("private"));
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("group"));
    }

    @Test
    void predicateIsCaseAndWhitespaceInsensitive() {
        play.Play.configuration.setProperty(SCOPE_KEY, "  DM  ");
        assertTrue(TelegramCallbackDispatcher.keyboardScopeAllows("private"));
        assertFalse(TelegramCallbackDispatcher.keyboardScopeAllows("group"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Drive a BROWSE callback for {@code chatType} through the real dispatcher. */
    private void dispatchBrowse(String chatType) {
        var payload = TelegramModelCallback.encodeBrowse(conversation.id);
        var cb = new InboundCallback("cbid-scope", CHAT_ID, chatType, "tg-user-1", 100, payload);
        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);
    }

    /** Allowed path: the BROWSE handler acks and edits the message in place. */
    private void assertHandlerRan() {
        assertEquals(1, server.countRequests("answerCallbackQuery"),
                "allowed callback must be acked");
        assertEquals(1, server.countRequests("editMessageText"),
                "allowed BROWSE callback must edit the message to the providers list");
        var ackBody = firstRequestBody("answerCallbackQuery");
        assertFalse(ackBody.contains("disabled in this chat"),
                "an allowed callback must not carry the disabled notice: " + ackBody);
    }

    /** Rejected path: ack-only with the disabled notice; no handler edit. */
    private void assertRejectedWithNotice() {
        assertEquals(1, server.countRequests("answerCallbackQuery"),
                "a rejected callback is still acked (3s SLA)");
        assertEquals(0, server.countRequests("editMessageText"),
                "the handler must NOT run for a rejected callback");
        var ackBody = firstRequestBody("answerCallbackQuery");
        assertTrue(ackBody.contains("disabled in this chat"),
                "ack must carry the buttons-disabled notice: " + ackBody);
        assertTrue(ackBody.contains("show_alert"),
                "notice should surface as an alert the user can read: " + ackBody);
    }

    private String firstRequestBody(String methodName) {
        return server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase(methodName))
                .findFirst().orElseThrow().body();
    }

    private static void seedProvider(String provider, String modelJson) {
        services.ConfigService.set("provider." + provider + ".baseUrl",
                "http://127.0.0.1:9999/v1");
        services.ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        services.ConfigService.set("provider." + provider + ".models",
                "[" + modelJson + "]");
        llm.ProviderRegistry.refresh();
    }
}
