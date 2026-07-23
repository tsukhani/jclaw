package controllers;

import channels.ChannelTransport;
import channels.TelegramPollingRunner;
import channels.TelegramWebhookRegistrar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jobs.TelegramCommandsRegistrationJob;
import models.Agent;
import models.TelegramBinding;
import play.mvc.With;
import services.EventLogger;
import utils.ApiResponses;
import utils.Strings;

import java.security.SecureRandom;
import java.util.Base64;

import static controllers.BindingKeys.EVENT_CATEGORY_CHANNEL;
import static controllers.BindingKeys.KEY_AGENT_ID;
import static controllers.BindingKeys.KEY_ENABLED;
import static controllers.BindingKeys.KEY_REPLY_TO_MODE;
import static controllers.BindingKeys.KEY_TRANSPORT;
import static controllers.BindingKeys.KEY_WEBHOOK_BASE_URL;
import static utils.GsonHolder.GSON;

/**
 * CRUD API for per-user Telegram bindings (JCLAW-89). Each binding maps one bot
 * token to one agent plus one Telegram user id. Mutations always reconcile the
 * {@link TelegramPollingRunner} so session state tracks DB state without a
 * process restart.
 */
@With(AuthCheck.class)
public class ApiTelegramBindingsController extends ApiBindingController {

    private static final Gson gson = GSON;

    // Sonar java:S2119 — a single shared SecureRandom for webhook-secret
    // generation. SecureRandom is thread-safe and meant to be reused; a fresh
    // instance per call needlessly re-seeds.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // JSON body keys reused across create/update parsers.
    private static final String KEY_BOT_TOKEN = "botToken";
    private static final String KEY_TELEGRAM_USER_ID = "telegramUserId";
    private static final String KEY_WEBHOOK_SECRET = "webhookSecret";
    // JCLAW-378: per-binding setting overrides (null = fall back to global config).
    private static final String KEY_ERROR_REPLY_POLICY = "errorReplyPolicy";
    private static final String KEY_NOTIFIER_COOLDOWN_MS = "notifierCooldownMs";

    // Channel identifier for this binding type.
    private static final String CHANNEL_TELEGRAM = "telegram";

    /** Flat projection the frontend consumes. {@code botToken} and
     *  {@code webhookSecret} are elided — they're secrets. {@code webhookBaseUrl}
     *  is the operator-editable public host; {@code effectiveWebhookUrl} is the
     *  base + fixed path (binding id) surfaced so the Edit UI can show a preview.
     *  JCLAW-784: the secret is sent to Telegram in the
     *  {@code X-Telegram-Bot-Api-Secret-Token} header, never as a URL segment, so
     *  it cannot enter this serialized body. */
    private record BindingView(Long id, Long agentId, String agentName,
                                String telegramUserId, String transport,
                                String webhookBaseUrl, boolean hasWebhookSecret,
                                String effectiveWebhookUrl,
                                boolean enabled,
                                String replyToMode, String errorReplyPolicy,
                                Long notifierCooldownMs,
                                String createdAt, String updatedAt) {
        static BindingView of(TelegramBinding b) {
            return new BindingView(b.id,
                    b.agent != null ? b.agent.id : null,
                    b.agent != null ? b.agent.name : null,
                    b.telegramUserId,
                    b.transport != null ? b.transport.name() : ChannelTransport.POLLING.name(),
                    b.webhookBaseUrl,
                    b.webhookSecret != null && !b.webhookSecret.isBlank(),
                    effectiveWebhookUrl(b),
                    b.enabled,
                    b.replyToMode, b.errorReplyPolicy, b.notifierCooldownMs,
                    b.createdAt != null ? b.createdAt.toString() : null,
                    b.updatedAt != null ? b.updatedAt.toString() : null);
        }

        /** The webhook URL preview surfaced for the Edit UI (JCLAW-338/339):
         *  {@code base + /api/webhooks/telegram/{id}} — identical to what
         *  {@link TelegramWebhookRegistrar#webhookUrl} now registers (JCLAW-784
         *  keys the route on the binding id and authenticates the secret via the
         *  {@code X-Telegram-Bot-Api-Secret-Token} header). Null unless this is a
         *  WEBHOOK binding with both a public base and a secret (unchanged). */
        private static String effectiveWebhookUrl(TelegramBinding b) {
            if (b.transport != ChannelTransport.WEBHOOK
                    || b.webhookBaseUrl == null || b.webhookBaseUrl.isBlank()
                    || b.webhookSecret == null || b.webhookSecret.isBlank()) {
                return null;
            }
            return Strings.trimTrailingSlash(b.webhookBaseUrl) + "/api/webhooks/telegram/" + b.id;
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

        String botToken = JsonBodyReader.requiredString(body, KEY_BOT_TOKEN);
        Long agentId = body.has(KEY_AGENT_ID) && !body.get(KEY_AGENT_ID).isJsonNull()
                ? body.get(KEY_AGENT_ID).getAsLong() : null;
        String telegramUserId = JsonBodyReader.requiredString(body, KEY_TELEGRAM_USER_ID);

        if (botToken == null || agentId == null || telegramUserId == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "botToken, agentId, and telegramUserId are required");
            throw new AssertionError("unreachable: error() throws");
        }
        if (!telegramUserId.matches("\\d+")) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "telegramUserId must be numeric");
        }

        Agent agent = requireEnabledAgent(agentId);
        if (TelegramBinding.findByBotToken(botToken) != null) {
            ApiResponses.error(409, "bot_token_conflict", "A binding with this bot token already exists");
        }
        // JCLAW-723: 1:1 agent<->binding privacy invariant — agent memory is
        // scoped per agent, so a second binding would share memory across users.
        rejectAgentAlreadyBound(agent, TelegramBinding::findByAgent, "Telegram", "agent_already_bound");

        var binding = new TelegramBinding();
        binding.botToken = botToken;
        binding.agent = agent;
        binding.telegramUserId = telegramUserId;
        binding.transport = BindingKeys.parseTransport(body, ChannelTransport.POLLING, ChannelTransport::parse);
        binding.webhookSecret = readOptionalString(body, KEY_WEBHOOK_SECRET);
        binding.webhookBaseUrl = readOptionalString(body, KEY_WEBHOOK_BASE_URL);
        binding.enabled = !body.has(KEY_ENABLED) || body.get(KEY_ENABLED).getAsBoolean();
        // JCLAW-378: per-binding setting overrides. On create the body keys may be
        // absent — applyBindingSettingOverrides only touches a field when its key
        // is present, so an absent key leaves the model's null (= config fallback).
        applyBindingSettingOverrides(binding, body);
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
        applyAgentUpdate(binding, body, TelegramBinding::findByAgent, "Telegram", "agent_already_bound");
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
    private static void applyBotTokenUpdate(TelegramBinding binding, JsonObject body) {
        if (!body.has(KEY_BOT_TOKEN)) return;
        String newToken = body.get(KEY_BOT_TOKEN).getAsString();
        if (newToken == null || newToken.isBlank() || newToken.equals(binding.botToken)) return;
        var existing = TelegramBinding.findByBotToken(newToken);
        if (existing != null && !existing.id.equals(binding.id)) {
            ApiResponses.error(409, "bot_token_conflict", "A binding with this bot token already exists");
        }
        binding.botToken = newToken;
    }

    @SuppressWarnings("java:S2259")
    private static void applyTelegramUserIdUpdate(TelegramBinding binding, JsonObject body) {
        if (!body.has(KEY_TELEGRAM_USER_ID)) return;
        String uid = body.get(KEY_TELEGRAM_USER_ID).getAsString();
        if (uid == null || !uid.matches("\\d+")) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "telegramUserId must be numeric");
        }
        binding.telegramUserId = uid;
    }

    private static void applyOptionalFieldUpdates(TelegramBinding binding, JsonObject body) {
        if (body.has(KEY_TRANSPORT)) {
            binding.transport = BindingKeys.parseTransport(body, binding.transport, ChannelTransport::parse);
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
        applyBindingSettingOverrides(binding, body);
    }

    /**
     * JCLAW-378: apply the three per-binding setting overrides when their keys
     * are present. A present-but-null JSON value clears the override (back to
     * config fallback); an absent key leaves the stored value untouched, so a
     * partial PUT that omits these keys never disturbs them. Enum-like values
     * are validated against the same vocabulary the consumers honour; an
     * out-of-range value 400s rather than silently persisting a no-op.
     */
    private static void applyBindingSettingOverrides(TelegramBinding binding,
                                                      JsonObject body) {
        if (body.has(KEY_REPLY_TO_MODE)) {
            String v = readOptionalString(body, KEY_REPLY_TO_MODE);
            if (v != null && !v.matches("off|first|all")) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "replyToMode must be one of: off, first, all");
            }
            binding.replyToMode = v;
        }
        if (body.has(KEY_ERROR_REPLY_POLICY)) {
            String v = readOptionalString(body, KEY_ERROR_REPLY_POLICY);
            if (v != null && !v.matches("reply|silent")) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "errorReplyPolicy must be one of: reply, silent");
            }
            binding.errorReplyPolicy = v;
        }
        if (body.has(KEY_NOTIFIER_COOLDOWN_MS)) {
            var el = body.get(KEY_NOTIFIER_COOLDOWN_MS);
            if (el.isJsonNull()) {
                binding.notifierCooldownMs = null;
            } else {
                long ms = el.getAsLong();
                if (ms <= 0) {
                    ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "notifierCooldownMs must be a positive number of milliseconds");
                }
                binding.notifierCooldownMs = ms;
            }
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
            SECURE_RANDOM.nextBytes(bytes);
            b.webhookSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    /** JCLAW-339: tear down the OLD delivery mode before standing up the new one,
     *  so a bot is never simultaneously polling and webhooked (Telegram 409s the
     *  {@code getUpdates} loop otherwise). Entering WEBHOOK: stop the poller
     *  ({@code reconcile} drops a no-longer-POLLING binding) BEFORE {@code setWebhook}.
     *  Entering POLLING: the poller's own {@code BotSession} runs {@code deleteWebhook}
     *  before its first getUpdates (JCLAW-432), so no explicit delete is issued here.
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
        // JCLAW-360: setMyCommands runs only at @OnApplicationStart otherwise, so
        // a binding created or re-enabled mid-session would get native command
        // autocomplete only after the next JVM restart. Register its menu now.
        // Synchronous like the reconcile calls above — registerOne swallows its
        // own Bot API failures, so a revoked token can't fail the save.
        TelegramCommandsRegistrationJob.registerOne(binding);
    }

    /**
     * JCLAW-362: probe a binding's health against the live Bot API. Calls
     * {@code getMe} (so a bad token surfaces as a 401 here rather than at the
     * next send) and, for WEBHOOK bindings, {@code getWebhookInfo} (so a stale
     * or errored webhook registration is visible). Returns the structured
     * {@link TelegramWebhookRegistrar.ProbeResult} either way — the HTTP status
     * stays 200 and the {@code ok} flag carries the verdict, so the UI can
     * render the failure detail rather than a bare error page.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TelegramWebhookRegistrar.ProbeResult.class)))
    // Sonar java:S2259 false positive: Play's notFound() never returns (throws
    // play.mvc.results.NotFound), so binding is non-null below. Matches the
    // identical suppression on delete().
    @SuppressWarnings("java:S2259")
    public static void test(Long id) {
        var binding = TelegramBinding.<TelegramBinding>findById(id);
        if (binding == null) notFound();
        var result = TelegramWebhookRegistrar.probe(binding);
        EventLogger.info(EVENT_CATEGORY_CHANNEL,
                binding.agent != null ? binding.agent.name : null, CHANNEL_TELEGRAM,
                "Binding %d health probe: %s".formatted(id, result.ok() ? "ok" : "error"));
        renderJSON(gson.toJson(result));
    }

    @SuppressWarnings("java:S2259")
    public static void delete(Long id) {
        var binding = TelegramBinding.<TelegramBinding>findById(id);
        if (binding == null) notFound();
        String agentName = binding.agent != null ? binding.agent.name : null;
        String botToken = binding.botToken;
        var transport = binding.transport;
        binding.delete();
        EventLogger.info(EVENT_CATEGORY_CHANNEL, agentName, CHANNEL_TELEGRAM,
                "Binding %d deleted".formatted(id));
        // JCLAW-339/JCLAW-433: drop the webhook only for a WEBHOOK binding — a
        // POLLING binding never registered one, so deleting it is a redundant call
        // that 401s (and logs an error) when the bot token was revoked alongside.
        TelegramWebhookRegistrar.onBindingDeleted(botToken, transport);
        TelegramPollingRunner.reconcile();
        ApiResponses.ok();
    }
}
