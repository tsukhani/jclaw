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
class TelegramModelSelectorTest extends UnitTest {

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
    void sendSummaryEmitsMessageWithInlineKeyboard() {
        boolean ok = TelegramModelSelector.sendSummary(agent, conversation);
        assertTrue(ok, "summary send should succeed against the mock");
        assertEquals(1, server.countRequests("sendMessage"),
                "summary is one sendMessage request");
        var body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .findFirst().orElseThrow().body();
        assertTrue(body.contains("reply_markup"), "keyboard present: " + body);
        assertTrue(body.contains("Model Configuration"), "header present: " + body);
        assertTrue(body.contains("Current model:"), "current-model line present: " + body);
        assertTrue(body.contains("Select a provider"), "provider prompt present: " + body);
        // The new flow drops the "Browse providers" intermediate step — providers
        // are buttons in the initial bubble. Cancel sits in its own bottom row.
        assertFalse(body.contains("Browse providers"), "no browse-providers button: " + body);
        assertTrue(body.contains("Cancel"), "cancel button present: " + body);
    }

    // ── Browse flow ───────────────────────────────────────────────────

    @Test
    void browseCallbackEditsToProvidersList() {
        var payload = TelegramModelCallback.encodeBrowse(conversation.id);
        var cb = new InboundCallback("cbid-1", CHAT_ID, "private", "tg-user-1", 100, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        assertEquals(1, server.countRequests("answerCallbackQuery"),
                "must ack the callback");
        assertEquals(1, server.countRequests("editMessageText"),
                "must edit the message to the providers list");
        var editBody = firstRequestBody("editMessageText");
        assertTrue(editBody.contains("Model Configuration"), "header: " + editBody);
        assertTrue(editBody.contains("Select a provider"), "prompt: " + editBody);
        // Display labels — registry IDs ("openrouter", "ollama-cloud") map to
        // human-readable names ("OpenRouter", "Ollama Cloud") on the buttons.
        assertTrue(editBody.contains("OpenRouter"), "openrouter labelled: " + editBody);
        assertTrue(editBody.contains("Ollama Cloud"), "ollama-cloud labelled: " + editBody);
    }

    @Test
    void providerPageCallbackEditsToModelsList() {
        int openrouterIdx = providerIndex("openrouter");
        var payload = TelegramModelCallback.encodeProviderPage(conversation.id, openrouterIdx, 0);
        var cb = new InboundCallback("cbid-2", CHAT_ID, "private", "tg-user-1", 101, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        assertEquals(1, server.countRequests("editMessageText"));
        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("OpenRouter"), "provider label in header: " + body);
        assertTrue(body.contains("Select a model"), "model prompt: " + body);
        assertTrue(body.contains("GPT 4.1"), "model label: " + body);
    }

    // ── Select + write override ───────────────────────────────────────

    @Test
    void selectCallbackWritesOverrideAndConfirms() {
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
    void selectWithStaleProviderIndexShowsAlertAndSkipsWrite() {
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
    void callbackForDeletedConversationShowsAlert() {
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
    void malformedCallbackDataShowsGenericAlert() {
        var cb = new InboundCallback("cbid-6", CHAT_ID, "private", "tg-user-1", 105, "m:garbage");

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var ackBody = firstRequestBody("answerCallbackQuery");
        assertTrue(ackBody.contains("no longer available"),
                "generic alert for malformed: " + ackBody);
    }

    // ── Details + Back loop ───────────────────────────────────────────

    @Test
    void detailsCallbackEditsToFullMetadata() {
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
    void backCallbackReturnsToProvidersList() {
        // BACK is now an alias for BROWSE — old buttons in chat history
        // bridge to the new providers-list state instead of the dead
        // "summary with Browse providers button" UI.
        var payload = TelegramModelCallback.encodeBack(conversation.id);
        var cb = new InboundCallback("cbid-8", CHAT_ID, "private", "tg-user-1", 107, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("Model Configuration"), "header restored: " + body);
        assertTrue(body.contains("Select a provider"), "prompt restored: " + body);
    }

    @Test
    void cancelCallbackClearsKeyboardAndShowsCancelledText() {
        var payload = TelegramModelCallback.encodeCancel(conversation.id);
        var cb = new InboundCallback("cbid-9", CHAT_ID, "private", "tg-user-1", 108, payload);

        TelegramCallbackDispatcher.dispatch(BOT_TOKEN, agent, cb);

        assertEquals(1, server.countRequests("answerCallbackQuery"),
                "must ack the callback");
        var body = firstRequestBody("editMessageText");
        assertTrue(body.contains("Cancelled"), "cancellation text: " + body);
        // No keyboard on the edit — the bubble becomes a plain note.
        assertFalse(body.contains("reply_markup"),
                "keyboard should be cleared on cancel: " + body);
    }

    // ── Keyboard layout sanity ────────────────────────────────────────

    @Test
    void providersKeyboardHidesDisabledProviders() {
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
                assertFalse(btn.getText().contains("loadtest-mock"),
                        "disabled loadtest-mock provider must not appear in the keyboard: " + btn.getText());
            }
        }
        // 2-per-row grid: 2 visible providers fit in one grid row, plus the
        // bottom Cancel row = 2 total rows.
        assertEquals(2, rows.size(), "1 grid row + 1 cancel row: " + rows.size());
    }

    @Test
    void providersKeyboardHidesNormalProviderWhenOperatorDisablesIt() {
        // JCLAW-110: the enable/disable toggle extends the existing filter
        // to user-configured providers. When an operator toggles a normal
        // provider off from Settings, it disappears from /model just like
        // loadtest-mock does when idle.
        services.ConfigService.set("provider.openrouter.enabled", "false");
        llm.ProviderRegistry.refresh();

        var keyboard = TelegramModelKeyboard.providersKeyboard(conversation.id);
        for (var row : keyboard.getKeyboard()) {
            for (var btn : row) {
                assertFalse(btn.getText().contains("OpenRouter"),
                        "operator-disabled openrouter must not appear in the keyboard: " + btn.getText());
            }
        }
    }

    @Test
    void providersKeyboardShowsExplicitlyEnabledProvider() {
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
                // Custom provider IDs not in the canonical PROVIDER_LABELS map
                // fall back to the raw registry name. The button text now
                // includes a "(N)" model-count suffix, so check for substring.
                if (btn.getText().contains("loadtest-mock")) loadtestSeen = true;
            }
        }
        assertTrue(loadtestSeen, "explicitly-enabled loadtest-mock must appear");
    }

    @Test
    void modelsKeyboardPaginatesWhenMultiPage() {
        // 15 models with the new MODELS_PER_PAGE=8, 2-col grid:
        // page 0 = 8 models in 4 grid rows + 1 pagination row + 1 back/cancel
        // row = 6 rows total.
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
        assertEquals(6, keyboard.getKeyboard().size(),
                "first page has 4 model rows + pagination + back/cancel: "
                        + keyboard.getKeyboard().size());
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

    @SuppressWarnings("java:S1172") // modelId is a documentation parameter at call sites
    private static void seedProvider(String provider, String modelId, String modelJson) {
        services.ConfigService.set("provider." + provider + ".baseUrl",
                "http://127.0.0.1:9999/v1");
        services.ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        services.ConfigService.set("provider." + provider + ".models",
                "[" + modelJson + "]");
        llm.ProviderRegistry.refresh();
    }
}
