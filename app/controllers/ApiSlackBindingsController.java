package controllers;

import channels.ChannelTransport;
import channels.SlackSocketModeRunner;
import channels.SlackWebApi;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.SlackBinding;
import play.mvc.With;
import services.EventLogger;
import utils.ApiResponses;

import static controllers.BindingKeys.EVENT_CATEGORY_CHANNEL;
import static controllers.BindingKeys.KEY_AGENT_ID;
import static controllers.BindingKeys.KEY_ENABLED;
import static controllers.BindingKeys.KEY_REPLY_TO_MODE;
import static controllers.BindingKeys.KEY_TRANSPORT;
import static controllers.BindingKeys.KEY_WEBHOOK_BASE_URL;
import static utils.GsonHolder.INSTANCE;

/**
 * CRUD API for per-agent Slack bindings (JCLAW-441). Mirrors
 * {@link ApiTelegramBindingsController}: each binding maps one Slack app
 * (bot token + signing secret, optional app token) to one agent, so multiple
 * Slack bots can coexist, one per agent. Replaces the pre-441 app-global
 * {@code ChannelConfig("slack")}.
 *
 * <p>Simpler than Telegram in two ways: Slack request authenticity comes from
 * the signing-secret HMAC (no per-URL webhook secret to generate), and the HTTP
 * (Events API) transport needs no server-side registration — the operator pastes
 * the per-binding Request URL into the Slack app dashboard. Socket Mode runner
 * reconciliation arrives with JCLAW-351.
 */
@With(AuthCheck.class)
public class ApiSlackBindingsController extends ApiBindingController {

    private static final Gson gson = INSTANCE;

    private static final String KEY_BOT_TOKEN = "botToken";
    private static final String KEY_SIGNING_SECRET = "signingSecret";
    private static final String KEY_APP_TOKEN = "appToken";
    private static final String KEY_OWNER_USER_ID = "ownerUserId";

    private static final String CHANNEL_SLACK = "slack";

    /** The fixed Slack Events API contract path; the per-binding id is appended. */
    private static final String WEBHOOK_PATH = "/api/webhooks/slack/";

    // JCLAW-682: canonical error codes for the ApiResponses envelope.

    /** Flat projection the frontend consumes. The secrets ({@code botToken},
     *  {@code signingSecret}, {@code appToken}) are elided; only presence flags
     *  are surfaced. {@code effectiveRequestUrl} is the full Events API Request
     *  URL to paste into the Slack app (base + path + id), shown in the Edit UI. */
    private record BindingView(Long id, Long agentId, String agentName,
                                String ownerUserId, String transport,
                                String webhookBaseUrl, String effectiveRequestUrl,
                                boolean hasSigningSecret, boolean hasAppToken,
                                String botUserId, String teamId,
                                boolean enabled, String replyToMode,
                                String createdAt, String updatedAt,
                                String deliveryScopeWarning) {
        static BindingView of(SlackBinding b) {
            return of(b, null);
        }

        /** JCLAW-458: {@code deliveryScopeWarning} is populated only on create/update (a one-off
         *  {@code conversations.list} scope probe), null on list — so listing N bindings never fans
         *  out N Slack calls. */
        static BindingView of(SlackBinding b, String deliveryScopeWarning) {
            return new BindingView(b.id,
                    b.agent != null ? b.agent.id : null,
                    b.agent != null ? b.agent.name : null,
                    b.ownerUserId,
                    b.transport != null ? b.transport.name() : ChannelTransport.HTTP.name(),
                    b.webhookBaseUrl,
                    effectiveRequestUrl(b),
                    b.signingSecret != null && !b.signingSecret.isBlank(),
                    b.appToken != null && !b.appToken.isBlank(),
                    b.botUserId, b.teamId,
                    b.enabled, b.replyToMode,
                    b.createdAt != null ? b.createdAt.toString() : null,
                    b.updatedAt != null ? b.updatedAt.toString() : null,
                    deliveryScopeWarning);
        }

        /** The Events API Request URL to register in the Slack app dashboard:
         *  {@code base + /api/webhooks/slack/{id}}. Null unless this is an HTTP
         *  binding with a public base (SOCKET bindings need no public URL). */
        private static String effectiveRequestUrl(SlackBinding b) {
            if (b.transport != ChannelTransport.HTTP
                    || b.webhookBaseUrl == null || b.webhookBaseUrl.isBlank() || b.id == null) {
                return null;
            }
            var base = b.webhookBaseUrl.endsWith("/")
                    ? b.webhookBaseUrl.substring(0, b.webhookBaseUrl.length() - 1)
                    : b.webhookBaseUrl;
            return base + WEBHOOK_PATH + b.id;
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = BindingView.class))))
    public static void list() {
        var items = SlackBinding.<SlackBinding>findAll().stream()
                .map(BindingView::of)
                .toList();
        renderJSON(gson.toJson(items));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SlackBinding.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        String botToken = JsonBodyReader.requiredString(body, KEY_BOT_TOKEN);
        var transport = BindingKeys.parseTransport(body, ChannelTransport.HTTP, ChannelTransport::parse);
        String signingSecret = readOptionalString(body, KEY_SIGNING_SECRET);
        String appToken = readOptionalString(body, KEY_APP_TOKEN);
        Long agentId = body.has(KEY_AGENT_ID) && !body.get(KEY_AGENT_ID).isJsonNull()
                ? body.get(KEY_AGENT_ID).getAsLong() : null;
        if (botToken == null || agentId == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "botToken and agentId are required");
            throw new AssertionError("unreachable: error() throws");
        }
        // JCLAW-351: transport-specific inbound credential. The Events API (HTTP) verifies
        // an HMAC, so it needs the signing secret; Socket Mode authenticates the WebSocket
        // with the app-level token (xapp-) instead, and needs no public URL.
        if (transport == ChannelTransport.SOCKET) {
            if (appToken == null || appToken.isBlank()) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Socket Mode requires an app-level token (xapp-)");
                throw new AssertionError("unreachable: error() throws");
            }
        } else if (signingSecret == null || signingSecret.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "The Events API transport requires a signing secret");
            throw new AssertionError("unreachable: error() throws");
        }

        Agent agent = requireEnabledAgent(agentId);
        if (SlackBinding.findByBotToken(botToken) != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "A binding with this bot token already exists");
        }
        // JCLAW-723: 1:1 agent<->binding privacy invariant — a second binding on
        // one agent would share its memory across Slack workspaces/owners.
        rejectAgentAlreadyBound(agent, SlackBinding::findByAgent, "Slack", ApiResponses.CONFLICT);

        var binding = new SlackBinding();
        binding.botToken = botToken;
        binding.signingSecret = signingSecret;
        binding.agent = agent;
        binding.appToken = appToken;
        binding.ownerUserId = readOptionalString(body, KEY_OWNER_USER_ID);
        requireOwnerForMain(agent, binding.ownerUserId);
        binding.transport = transport;
        binding.webhookBaseUrl = readOptionalString(body, KEY_WEBHOOK_BASE_URL);
        binding.enabled = !body.has(KEY_ENABLED) || body.get(KEY_ENABLED).getAsBoolean();
        binding.replyToMode = readOptionalString(body, KEY_REPLY_TO_MODE);
        // Best-effort identity probe: cache botUserId/teamId so the bot-loop guard
        // works immediately. A bad token still saves (the operator fixes it via
        // update + test, mirroring Telegram's save-then-test flow).
        cacheIdentity(binding);
        binding.save();

        EventLogger.info(EVENT_CATEGORY_CHANNEL, agent.name, CHANNEL_SLACK,
                "Binding %d created".formatted(binding.id));
        // JCLAW-351: open/close the Socket Mode connection if this binding is SOCKET.
        SlackSocketModeRunner.reconcile();
        // JCLAW-458: auth.test validated the token but not its scopes — warn now if name-based
        // delivery can't list channels, instead of failing silently at the first delivery.
        renderJSON(gson.toJson(BindingView.of(binding, SlackWebApi.deliveryScopeWarning(binding.botToken))));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SlackBinding.class)))
    public static void update(Long id) {
        var binding = SlackBinding.<SlackBinding>findById(id);
        if (binding == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        boolean tokenChanged = applyBotTokenUpdate(binding, body);
        applyAgentUpdate(binding, body, SlackBinding::findByAgent, "Slack", ApiResponses.CONFLICT);
        applyOptionalFieldUpdates(binding, body);
        requireOwnerForMain(binding.agent, binding.ownerUserId);
        if (tokenChanged) cacheIdentity(binding);
        binding.save();

        EventLogger.info(EVENT_CATEGORY_CHANNEL,
                binding.agent != null ? binding.agent.name : null, CHANNEL_SLACK,
                "Binding %d updated".formatted(binding.id));
        // JCLAW-351: reconcile Socket Mode (transport/app-token/enabled may have changed).
        SlackSocketModeRunner.reconcile();
        // JCLAW-458: re-check delivery scopes on update (the token may have changed).
        renderJSON(gson.toJson(BindingView.of(binding, SlackWebApi.deliveryScopeWarning(binding.botToken))));
    }

    /**
     * JCLAW-354: the main agent has full filesystem / shell access, so an owner-less
     * binding to it would let any workspace user reach it. Require an owner user id
     * for a main-agent binding (400 otherwise). Non-main bindings stay optional.
     */
    private static void requireOwnerForMain(Agent agent, String ownerUserId) {
        if (agent != null && agent.isMain() && (ownerUserId == null || ownerUserId.isBlank())) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "The main agent has full system access — set an owner user id so only you can reach it");
        }
    }

    /**
     * Health-probe a binding against the live Slack API ({@code auth.test}) so a
     * bad/revoked token surfaces here rather than at the next send. Refreshes the
     * cached {@code botUserId}/{@code teamId} on success. HTTP stays 200 and the
     * {@code ok} flag carries the verdict, mirroring Telegram's {@code test()}.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SlackWebApi.AuthTestResult.class)))
    public static void test(Long id) {
        var binding = SlackBinding.<SlackBinding>findById(id);
        if (binding == null) notFound();
        var result = SlackWebApi.authTest(binding.botToken);
        if (result.ok()) {
            binding.botUserId = result.botUserId();
            binding.teamId = result.teamId();
            binding.save();
        }
        EventLogger.info(EVENT_CATEGORY_CHANNEL,
                binding.agent != null ? binding.agent.name : null, CHANNEL_SLACK,
                "Binding %d health probe: %s".formatted(id, result.ok() ? "ok" : "error"));
        renderJSON(gson.toJson(result));
    }

    @SuppressWarnings("java:S2259")
    public static void delete(Long id) {
        var binding = SlackBinding.<SlackBinding>findById(id);
        if (binding == null) notFound();
        String agentName = binding.agent != null ? binding.agent.name : null;
        binding.delete();
        EventLogger.info(EVENT_CATEGORY_CHANNEL, agentName, CHANNEL_SLACK,
                "Binding %d deleted".formatted(id));
        // JCLAW-351: close the Socket Mode connection if the deleted binding had one.
        SlackSocketModeRunner.reconcile();
        ApiResponses.ok();
    }

    // ── update helpers ──

    @SuppressWarnings("java:S2259")
    private static boolean applyBotTokenUpdate(SlackBinding binding, JsonObject body) {
        if (!body.has(KEY_BOT_TOKEN)) return false;
        String newToken = body.get(KEY_BOT_TOKEN).getAsString();
        if (newToken == null || newToken.isBlank() || newToken.equals(binding.botToken)) return false;
        var existing = SlackBinding.findByBotToken(newToken);
        if (existing != null && !existing.id.equals(binding.id)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "A binding with this bot token already exists");
        }
        binding.botToken = newToken;
        return true;
    }

    private static void applyOptionalFieldUpdates(SlackBinding binding, JsonObject body) {
        if (body.has(KEY_SIGNING_SECRET)) {
            String v = readOptionalString(body, KEY_SIGNING_SECRET);
            if (v != null) binding.signingSecret = v;   // never null out the required secret
        }
        if (body.has(KEY_APP_TOKEN)) binding.appToken = readOptionalString(body, KEY_APP_TOKEN);
        if (body.has(KEY_OWNER_USER_ID)) binding.ownerUserId = readOptionalString(body, KEY_OWNER_USER_ID);
        if (body.has(KEY_TRANSPORT)) binding.transport = BindingKeys.parseTransport(body, binding.transport, ChannelTransport::parse);
        if (body.has(KEY_WEBHOOK_BASE_URL)) binding.webhookBaseUrl = readOptionalString(body, KEY_WEBHOOK_BASE_URL);
        if (body.has(KEY_ENABLED)) binding.enabled = body.get(KEY_ENABLED).getAsBoolean();
        if (body.has(KEY_REPLY_TO_MODE)) binding.replyToMode = readOptionalString(body, KEY_REPLY_TO_MODE);
    }

    /** Probe the bot token and cache its {@code botUserId}/{@code teamId} when ok;
     *  leaves them untouched on failure (the binding still saves). */
    private static void cacheIdentity(SlackBinding binding) {
        var probe = SlackWebApi.authTest(binding.botToken);
        if (probe.ok()) {
            binding.botUserId = probe.botUserId();
            binding.teamId = probe.teamId();
        }
    }

}
