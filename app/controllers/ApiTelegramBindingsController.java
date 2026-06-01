package controllers;

import channels.ChannelTransport;
import channels.TelegramPollingRunner;
import channels.TelegramWebhookRegistrar;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.TelegramBinding;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.EventLogger;

import static utils.GsonHolder.INSTANCE;

/**
 * CRUD API for per-user Telegram bindings (JCLAW-89). Each binding maps one bot
 * token to one agent plus one Telegram user id. Mutations always reconcile the
 * {@link TelegramPollingRunner} so session state tracks DB state without a
 * process restart.
 */
@With(AuthCheck.class)
public class ApiTelegramBindingsController extends Controller {

    private static final Gson gson = INSTANCE;

    // JSON body keys reused across create/update parsers.
    private static final String KEY_BOT_TOKEN = "botToken";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_TELEGRAM_USER_ID = "telegramUserId";
    private static final String KEY_WEBHOOK_SECRET = "webhookSecret";
    private static final String KEY_WEBHOOK_BASE_URL = "webhookBaseUrl";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TRANSPORT = "transport";

    // EventLogger category + channel identifier for this binding type.
    private static final String EVENT_CATEGORY_CHANNEL = "channel";
    private static final String CHANNEL_TELEGRAM = "telegram";

    /** Flat projection the frontend consumes. {@code botToken} and
     *  {@code webhookSecret} are elided — they're secrets. {@code webhookBaseUrl}
     *  is the operator-editable public host; {@code effectiveWebhookUrl} is the
     *  full URL to register (base + fixed path + secret), surfaced so the Edit UI
     *  can show it. {@code cooldownUntil} is the ISO-8601 instant until which
     *  re-registration is blocked after an unregister (post-JCLAW-89 Telegram
     *  long-poll cooldown). */
    private record BindingView(Long id, Long agentId, String agentName,
                                String telegramUserId, String transport,
                                String webhookBaseUrl, boolean hasWebhookSecret,
                                String effectiveWebhookUrl,
                                boolean enabled,
                                String cooldownUntil,
                                String createdAt, String updatedAt) {
        static BindingView of(TelegramBinding b) {
            var cooldown = TelegramPollingRunner.cooldownUntil(b.botToken);
            return new BindingView(b.id,
                    b.agent != null ? b.agent.id : null,
                    b.agent != null ? b.agent.name : null,
                    b.telegramUserId,
                    b.transport != null ? b.transport.name() : ChannelTransport.POLLING.name(),
                    b.webhookBaseUrl,
                    b.webhookSecret != null && !b.webhookSecret.isBlank(),
                    effectiveWebhookUrl(b),
                    b.enabled,
                    cooldown != null ? cooldown.toString() : null,
                    b.createdAt != null ? b.createdAt.toString() : null,
                    b.updatedAt != null ? b.updatedAt.toString() : null);
        }

        /** The full webhook URL to register with Telegram (JCLAW-338/339):
         *  {@code base + /api/webhooks/telegram/{id}/{secret}}. Null unless this is
         *  a WEBHOOK binding with both a public base and a secret. */
        private static String effectiveWebhookUrl(TelegramBinding b) {
            if (b.transport != ChannelTransport.WEBHOOK
                    || b.webhookBaseUrl == null || b.webhookBaseUrl.isBlank()
                    || b.webhookSecret == null || b.webhookSecret.isBlank()) {
                return null;
            }
            return TelegramWebhookRegistrar.webhookUrl(b.webhookBaseUrl, b.id, b.webhookSecret);
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = BindingView.class))))
    public static void list() {
        var items = TelegramBinding.<TelegramBinding>findAll().stream()
                .map(BindingView::of)
                .toList();
        renderJSON(gson.toJson(items));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TelegramBinding.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        String botToken = readRequiredString(body, KEY_BOT_TOKEN);
        Long agentId = body.has(KEY_AGENT_ID) && !body.get(KEY_AGENT_ID).isJsonNull()
                ? body.get(KEY_AGENT_ID).getAsLong() : null;
        String telegramUserId = readRequiredString(body, KEY_TELEGRAM_USER_ID);

        if (botToken == null || agentId == null || telegramUserId == null) {
            error(400, "botToken, agentId, and telegramUserId are required");
            throw new AssertionError("unreachable: error() throws");
        }
        if (!telegramUserId.matches("\\d+")) {
            error(400, "telegramUserId must be numeric");
        }

        Agent agent = AgentService.findById(agentId);
        if (agent == null || !agent.enabled) {
            error(400, "agentId must reference an enabled agent");
            throw new AssertionError("unreachable: error() throws");
        }
        if (TelegramBinding.findByBotToken(botToken) != null) {
            error(409, "A binding with this bot token already exists");
        }
        // Agent uniqueness: planned agent memory is scoped per agent, so
        // re-binding an agent to a second Telegram user would share memory
        // across those users. Reject before the unique constraint fires.
        if (TelegramBinding.findByAgent(agent) != null) {
            error(409, "Agent '%s' is already bound to another Telegram binding".formatted(agent.name));
        }

        var binding = new TelegramBinding();
        binding.botToken = botToken;
        binding.agent = agent;
        binding.telegramUserId = telegramUserId;
        binding.transport = parseTransport(body, ChannelTransport.POLLING);
        binding.webhookSecret = readOptionalString(body, KEY_WEBHOOK_SECRET);
        binding.webhookBaseUrl = readOptionalString(body, KEY_WEBHOOK_BASE_URL);
        binding.enabled = !body.has(KEY_ENABLED) || body.get(KEY_ENABLED).getAsBoolean();
        ensureWebhookSecret(binding);
        binding.save();

        EventLogger.info(EVENT_CATEGORY_CHANNEL, agent.name, CHANNEL_TELEGRAM,
                "Binding %d created (user=%s)".formatted(binding.id, telegramUserId));

        reconcileChannels(binding);
        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TelegramBinding.class)))
    public static void update(Long id) {
        var binding = TelegramBinding.<TelegramBinding>findById(id);
        if (binding == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        applyBotTokenUpdate(binding, body);
        applyAgentUpdate(binding, body);
        applyTelegramUserIdUpdate(binding, body);
        applyOptionalFieldUpdates(binding, body);
        ensureWebhookSecret(binding);
        binding.save();

        EventLogger.info(EVENT_CATEGORY_CHANNEL,
                binding.agent != null ? binding.agent.name : null, CHANNEL_TELEGRAM,
                "Binding %d updated".formatted(binding.id));

        reconcileChannels(binding);
        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @SuppressWarnings("java:S2259")
    private static void applyBotTokenUpdate(TelegramBinding binding, com.google.gson.JsonObject body) {
        if (!body.has(KEY_BOT_TOKEN)) return;
        String newToken = body.get(KEY_BOT_TOKEN).getAsString();
        if (newToken == null || newToken.isBlank() || newToken.equals(binding.botToken)) return;
        var existing = TelegramBinding.findByBotToken(newToken);
        if (existing != null && !existing.id.equals(binding.id)) {
            error(409, "A binding with this bot token already exists");
        }
        binding.botToken = newToken;
    }

    @SuppressWarnings("java:S2259")
    private static void applyAgentUpdate(TelegramBinding binding, com.google.gson.JsonObject body) {
        if (!body.has(KEY_AGENT_ID) || body.get(KEY_AGENT_ID).isJsonNull()) return;
        Agent agent = AgentService.findById(body.get(KEY_AGENT_ID).getAsLong());
        if (agent == null || !agent.enabled) {
            error(400, "agentId must reference an enabled agent");
        }
        if (binding.agent == null || !agent.id.equals(binding.agent.id)) {
            var other = TelegramBinding.findByAgent(agent);
            if (other != null && !other.id.equals(binding.id)) {
                error(409, "Agent '%s' is already bound to another Telegram binding".formatted(agent.name));
            }
        }
        binding.agent = agent;
    }

    @SuppressWarnings("java:S2259")
    private static void applyTelegramUserIdUpdate(TelegramBinding binding, com.google.gson.JsonObject body) {
        if (!body.has(KEY_TELEGRAM_USER_ID)) return;
        String uid = body.get(KEY_TELEGRAM_USER_ID).getAsString();
        if (uid == null || !uid.matches("\\d+")) {
            error(400, "telegramUserId must be numeric");
        }
        binding.telegramUserId = uid;
    }

    private static void applyOptionalFieldUpdates(TelegramBinding binding, com.google.gson.JsonObject body) {
        if (body.has(KEY_TRANSPORT)) {
            binding.transport = parseTransport(body, binding.transport);
        }
        if (body.has(KEY_WEBHOOK_SECRET)) {
            binding.webhookSecret = readOptionalString(body, KEY_WEBHOOK_SECRET);
        }
        if (body.has(KEY_WEBHOOK_BASE_URL)) {
            binding.webhookBaseUrl = readOptionalString(body, KEY_WEBHOOK_BASE_URL);
        }
        if (body.has(KEY_ENABLED)) {
            binding.enabled = body.get(KEY_ENABLED).getAsBoolean();
        }
    }

    /** JCLAW-339: WEBHOOK bindings always need a secret (the operator never types
     *  one — the UI auto-generates it client-side and sends it; this is the
     *  server-side safety net for API clients). Generate a Telegram-charset token
     *  (base64url) only when a webhook binding has none; it then stays stable. */
    private static void ensureWebhookSecret(TelegramBinding b) {
        if (b.transport == ChannelTransport.WEBHOOK
                && (b.webhookSecret == null || b.webhookSecret.isBlank())) {
            var bytes = new byte[32];
            new java.security.SecureRandom().nextBytes(bytes);
            b.webhookSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    /** JCLAW-339: tear down the OLD delivery mode before standing up the new one,
     *  so a bot is never simultaneously polling and webhooked (Telegram 409s the
     *  {@code getUpdates} loop otherwise). Entering WEBHOOK: stop the poller
     *  ({@code reconcile} drops a no-longer-POLLING binding) BEFORE {@code setWebhook}.
     *  Entering POLLING: {@code deleteWebhook} BEFORE the poller's first getUpdates.
     *
     *  <p>Known, harmless residual on POLLING→WEBHOOK: a single
     *  {@code BotSession} "GetUpdates 409" ERROR may be logged. When the binding
     *  was polling, a long-poll {@code getUpdates} is already in flight to
     *  Telegram (it blocks for the poll timeout). Unregistering the session does
     *  NOT abort that already-sent request — the telegrambots library exposes no
     *  hook for it — so when {@code setWebhook} activates the webhook ~1s later,
     *  Telegram terminates the pending poll with a 409. The webhook still
     *  registers and the poller is gone (no retry-storm — this ordering bounds it
     *  to one line). Eliminating it would mean draining the long-poll first (tens
     *  of seconds of save latency) or library-level request abort; both are
     *  disproportionate for an infrequent admin transport switch. */
    private static void reconcileChannels(TelegramBinding binding) {
        if (binding.transport == ChannelTransport.WEBHOOK) {
            TelegramPollingRunner.reconcile();
            TelegramWebhookRegistrar.onBindingSaved(binding);
        } else {
            TelegramWebhookRegistrar.onBindingSaved(binding);
            TelegramPollingRunner.reconcile();
        }
    }

    @SuppressWarnings("java:S2259")
    public static void delete(Long id) {
        var binding = TelegramBinding.<TelegramBinding>findById(id);
        if (binding == null) notFound();
        String agentName = binding.agent != null ? binding.agent.name : null;
        String botToken = binding.botToken;
        binding.delete();
        EventLogger.info(EVENT_CATEGORY_CHANNEL, agentName, CHANNEL_TELEGRAM,
                "Binding %d deleted".formatted(id));
        // JCLAW-339: drop any webhook so Telegram stops POSTing to a now-dead binding.
        TelegramWebhookRegistrar.onBindingDeleted(botToken);
        TelegramPollingRunner.reconcile();
        renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
    }

    // ── helpers ──

    private static String readRequiredString(com.google.gson.JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) return null;
        String s = body.get(key).getAsString();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String readOptionalString(com.google.gson.JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static ChannelTransport parseTransport(com.google.gson.JsonObject body,
                                                    ChannelTransport fallback) {
        String raw = body.has(KEY_TRANSPORT) && !body.get(KEY_TRANSPORT).isJsonNull()
                ? body.get(KEY_TRANSPORT).getAsString() : null;
        return ChannelTransport.parse(raw, fallback);
    }
}
