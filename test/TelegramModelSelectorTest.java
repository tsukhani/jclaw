import channels.TelegramCallbackDispatcher;
import channels.TelegramChannel;
import channels.TelegramChannel.InboundCallback;
import channels.TelegramModelCallback;
import channels.TelegramModelKeyboard;
import channels.TelegramModelSelector;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;
import services.ConversationService;

/**
 * JCLAW-109: wire-level integration coverage for the Telegram
 * inline-keyboard model selector. Drives the real {@link TelegramCallbackDispatcher}
 * and {@link TelegramModelSelector} entry points against the embedded
 * {@link MockTelegramServer}, asserting that the right Bot API calls land
 * on the wire for each step of the drill-down flow (summary → browse →
 * provider → select).
 */
public class TelegramModelSelectorTest extends UnitTest {

    private static final String BOT_TOKEN = "mock-bot-token";
    private static final String CHAT_ID = "77777";

    private MockTelegramServer server;
    private Agent agent;
    private Conversation conversation;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        // ConfigService has an in-memory cache (TTL-based) that outlives
        // deleteDatabase. Clear it between tests so provider.*.enabled
        // values written by one test don't leak into the next.
        services.ConfigService.clearCache();
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
        seedProvider("openrouter", "gpt-4.1",
                "{\"id\":\"gpt-4.1\",\"name\":\"GPT 4.1\",\"contextWindow\":128000,\"maxTokens\":8192}");
        seedProvider("ollama-cloud", "kimi-k2",
                "{\"id\":\"kimi-k2\",\"contextWindow\":200000,\"supportsVision\":true}");
        agent = AgentService.create("selector-agent", "openrouter", "gpt-4.1");
        // A Telegram binding is needed so TelegramModelSelector can resolve
        // the bot token for the /model summary send path.
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
        if (server != null) server.close();
        TelegramChannel.clearForTest(BOT_TOKEN);
    }

    // ── /model summary send path ──────────────────────────────────────

    @Test
    public void sendSummaryEmitsMessageWithInlineKeyboard() {
        boolean ok = TelegramModelSelector.sendSummary(agent, conversation);
        assertTrue(ok, "summary send should succeed against the mock");
        assertEquals(1, server.countRequests("sendMessage"),
                "summary is one sendMessage request");
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .findFirst().orElseThrow().body();
        assertTrue(body.contains("reply_markup"), "keyboard present: " + body);
        assertTrue(body.contains("Browse providers"), "browse button: " + body);
        // Show details is a text command now (/model status), not a keyboard button.
        assertFalse(body.contains("Show details"), "no show-details button: " + body);
        assertTrue(body.contains("Current:"), "summary text present: " + body);
    }

    // ── Browse flow ───────────────────────────────────────────────────

    @Test
    public void browseCallbackEditsToProvidersList() {
        var payload = TelegramModelCallback.encodeBrowse(conversation.id);
        var cb = new InboundCallback("cbid-1", CHAT_ID, "private", "tg-user-1", 100, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        assertEquals(1, server.countRequests("answerCallbackQuery"),
                "must ack the callback");
        assertEquals(1, server.countRequests("editMessageText"),
                "must edit the summary message to the providers list");
        var editBody = firstRequestBody("editMessageText");
        assertTrue(editBody.contains("Select a provider"), "header: " + editBody);
        assertTrue(editBody.contains("openrouter"), "provider 0 named: " + editBody);
        assertTrue(editBody.contains("ollama-cloud"), "provider 1 named: " + editBody);
    }

    @Test
    public void providerPageCallbackEditsToModelsList() {
        int openrouterIdx = providerIndex("openrouter");
        var payload = TelegramModelCallback.encodeProviderPage(conversation.id, openrouterIdx, 0);
        var cb = new InboundCallback("cbid-2", CHAT_ID, "private", "tg-user-1", 101, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        assertEquals(1, server.countRequests("editMessageText"));
        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("openrouter"), "provider header: " + body);
        assertTrue(body.contains("GPT 4.1"), "model label: " + body);
    }

    // ── Select + write override ───────────────────────────────────────

    @Test
    public void selectCallbackWritesOverrideAndConfirms() {
        // ConfigService insertion order is not guaranteed across providers, so
        // look up the current index for the target provider. Model index 0 =
        // kimi-k2 (the only model seeded for ollama-cloud).
        int ollamaIdx = providerIndex("ollama-cloud");
        var payload = TelegramModelCallback.encodeSelect(conversation.id, ollamaIdx, 0);
        var cb = new InboundCallback("cbid-3", CHAT_ID, "private", "tg-user-1", 102, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        // Override persisted.
        var reloaded = services.Tx.run(() -> (Conversation) Conversation.findById(conversation.id));
        assertEquals("ollama-cloud", reloaded.modelProviderOverride);
        assertEquals("kimi-k2", reloaded.modelIdOverride);

        // Confirmation edit landed.
        assertEquals(1, server.countRequests("editMessageText"));
        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("Switched this conversation"),
                "confirms the switch: " + body);
        assertTrue(body.contains("ollama-cloud/kimi-k2"),
                "names the new model: " + body);
    }

    // ── Validation and stale-index alerts ─────────────────────────────

    @Test
    public void selectWithStaleProviderIndexShowsAlertAndSkipsWrite() {
        // Provider index 99 doesn't exist — the registry has only two.
        var payload = TelegramModelCallback.encodeSelect(conversation.id, 99, 0);
        var cb = new InboundCallback("cbid-4", CHAT_ID, "private", "tg-user-1", 103, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var ackBody = firstRequestBody("answerCallbackQuery");
        assertTrue(ackBody.contains("show_alert"), "alert flag set: " + ackBody);
        assertTrue(ackBody.contains("no longer in the provider config"),
                "user-facing error text: " + ackBody);
        // No edit fired — message stays as-is so the user can re-run /model.
        assertEquals(0, server.countRequests("editMessageText"));

        var reloaded = services.Tx.run(() -> (Conversation) Conversation.findById(conversation.id));
        assertNull(reloaded.modelProviderOverride, "no write on stale index");
    }

    @Test
    public void callbackForDeletedConversationShowsAlert() {
        // Encode a callback for a conversation id that doesn't exist.
        var payload = TelegramModelCallback.encodeSelect(99_999L, 0, 0);
        var cb = new InboundCallback("cbid-5", CHAT_ID, "private", "tg-user-1", 104, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var ackBody = firstRequestBody("answerCallbackQuery");
        assertTrue(ackBody.contains("no longer exists"),
                "deleted-conversation message: " + ackBody);
        assertTrue(ackBody.contains("show_alert"), "alert flag: " + ackBody);
        assertEquals(0, server.countRequests("editMessageText"));
    }

    @Test
    public void malformedCallbackDataShowsGenericAlert() {
        var cb = new InboundCallback("cbid-6", CHAT_ID, "private", "tg-user-1", 105, "m:garbage");

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var ackBody = firstRequestBody("answerCallbackQuery");
        assertTrue(ackBody.contains("no longer available"),
                "generic alert for malformed: " + ackBody);
    }

    // ── Details + Back loop ───────────────────────────────────────────

    @Test
    public void detailsCallbackEditsToFullMetadata() {
        var payload = TelegramModelCallback.encodeDetails(conversation.id);
        var cb = new InboundCallback("cbid-7", CHAT_ID, "private", "tg-user-1", 106, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("Context window"), "full-details text: " + body);
        assertTrue(body.contains("128K"), "formatted context window present: " + body);
        // Back button attached.
        assertTrue(body.contains("Back"), "back button in keyboard: " + body);
    }

    @Test
    public void backCallbackReturnsToSummary() {
        var payload = TelegramModelCallback.encodeBack(conversation.id);
        var cb = new InboundCallback("cbid-8", CHAT_ID, "private", "tg-user-1", 107, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("Current:"), "summary text restored: " + body);
        assertTrue(body.contains("Browse providers"), "summary keyboard restored: " + body);
    }

    // ── Keyboard layout sanity ────────────────────────────────────────

    @Test
    public void providersKeyboardHidesDisabledProviders() {
        // The load-test harness ships loadtest-mock with its enabled flag
        // flipped off when idle; it only turns on for the duration of a
        // run. The user-visible filter keys off that flag, so a disabled
        // provider is hidden regardless of whether its config is present.
        seedProvider("loadtest-mock", "mock-model",
                "{\"id\":\"mock-model\",\"contextWindow\":1000}");
        services.ConfigService.set("provider.loadtest-mock.enabled", "false");

        var keyboard = TelegramModelKeyboard.providersKeyboard(conversation.id);
        var rows = keyboard.getKeyboard();
        for (var row : rows) {
            for (var btn : row) {
                assertNotEquals("loadtest-mock", btn.getText(),
                        "disabled loadtest-mock provider must not appear in the keyboard");
            }
        }
        // Two seeded providers (openrouter + ollama-cloud) plus the Back row.
        assertEquals(3, rows.size(), "2 enabled providers + 1 back row: " + rows.size());
    }

    @Test
    public void providersKeyboardHidesNormalProviderWhenOperatorDisablesIt() {
        // JCLAW-110: the enable/disable toggle extends the existing filter
        // to user-configured providers. When an operator toggles a normal
        // provider off from Settings, it disappears from /model just like
        // loadtest-mock does when idle.
        services.ConfigService.set("provider.openrouter.enabled", "false");
        llm.ProviderRegistry.refresh();

        var keyboard = TelegramModelKeyboard.providersKeyboard(conversation.id);
        for (var row : keyboard.getKeyboard()) {
            for (var btn : row) {
                assertNotEquals("openrouter", btn.getText(),
                        "operator-disabled openrouter must not appear in the keyboard");
            }
        }
    }

    @Test
    public void providersKeyboardShowsExplicitlyEnabledProvider() {
        // Inverse: if loadtest-mock is flipped on (e.g. during an active
        // run), it IS visible. The filter is driven by the enabled flag,
        // not a hardcoded name block-list, so operators can deliberately
        // surface internal providers when appropriate.
        seedProvider("loadtest-mock", "mock-model",
                "{\"id\":\"mock-model\",\"contextWindow\":1000}");
        services.ConfigService.set("provider.loadtest-mock.enabled", "true");

        var keyboard = TelegramModelKeyboard.providersKeyboard(conversation.id);
        boolean loadtestSeen = false;
        for (var row : keyboard.getKeyboard()) {
            for (var btn : row) {
                if ("loadtest-mock".equals(btn.getText())) loadtestSeen = true;
            }
        }
        assertTrue(loadtestSeen, "explicitly-enabled loadtest-mock must appear");
    }

    @Test
    public void modelsKeyboardPaginatesWhenMoreThanTenModels() {
        // Make a provider with 15 models — the first page should show 10
        // models plus a "Next" button.
        var bigProvider = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(i -> "{\"id\":\"m" + i + "\",\"contextWindow\":1000}")
                .collect(java.util.stream.Collectors.joining(","));
        services.ConfigService.set("provider.bigco.baseUrl", "http://127.0.0.1:9999/v1");
        services.ConfigService.set("provider.bigco.apiKey", "sk-test");
        services.ConfigService.set("provider.bigco.models", "[" + bigProvider + "]");
        llm.ProviderRegistry.refresh();

        // The keyboard uses userVisibleProviders — look up bigco's index
        // from the same filtered source so our test's expected index
        // matches what the keyboard renders.
        var providers = channels.TelegramModelSelector.userVisibleProviders();
        int bigcoIdx = -1;
        for (int i = 0; i < providers.size(); i++) {
            if ("bigco".equals(providers.get(i).config().name())) { bigcoIdx = i; break; }
        }
        assertTrue(bigcoIdx >= 0, "bigco should be in the user-visible list");

        var keyboard = TelegramModelKeyboard.modelsKeyboard(conversation.id, bigcoIdx, 0);
        // 10 model rows + 1 nav row = 11 total
        assertEquals(11, keyboard.getKeyboard().size(),
                "first page has 10 models + nav: " + keyboard.getKeyboard().size());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String firstRequestBody(String methodName) {
        return server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase(methodName))
                .findFirst().orElseThrow().body();
    }

    /** Find the current provider index for a given provider name. ProviderRegistry
     *  ordering isn't seed-order — ConfigService.listAll determines it — so every
     *  test that needs an index should resolve it live. */
    private static int providerIndex(String name) {
        var providers = llm.ProviderRegistry.listAll();
        for (int i = 0; i < providers.size(); i++) {
            if (name.equals(providers.get(i).config().name())) return i;
        }
        throw new IllegalStateException("provider '" + name + "' not in registry");
    }

    private static void seedProvider(String provider, String modelId, String modelJson) {
        services.ConfigService.set("provider." + provider + ".baseUrl",
                "http://127.0.0.1:9999/v1");
        services.ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        services.ConfigService.set("provider." + provider + ".models",
                "[" + modelJson + "]");
        llm.ProviderRegistry.refresh();
    }
}
