package controllers;

import channels.WhatsAppCloudApiProbe;
import channels.WhatsAppCobaltRunner;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.EventLogger;

import java.util.Map;
import java.util.Objects;

import static utils.GsonHolder.INSTANCE;

/**
 * CRUD API for per-agent WhatsApp bindings (JCLAW-444). Mirrors
 * {@link ApiSlackBindingsController}: each binding maps one WhatsApp presence to
 * one agent, so multiple WhatsApp bots can coexist, one per agent. Replaces the
 * pre-444 app-global {@code ChannelConfig("whatsapp")}.
 *
 * <p>A binding picks a {@link WhatsAppTransport}. {@code CLOUD_API} carries the
 * Meta Graph-API credentials (phone number id + access token, plus app secret /
 * verify token for the inbound webhook). {@code WHATSAPP_WEB} carries no
 * credentials here — it's QR-paired later (JCLAW-448) — so its create form only
 * needs an agent (and surfaces the ban-risk warning in the UI).
 *
 * <p>Credential <em>verification</em> (probing the Graph API to confirm a valid
 * WhatsApp Business number) is JCLAW-445; this controller just persists. Secrets
 * are write-only: the projection exposes presence flags, never the values.
 */
@With(AuthCheck.class)
public class ApiWhatsAppBindingsController extends Controller {

    private static final Gson gson = INSTANCE;

    private static final String KEY_TRANSPORT = "transport";
    private static final String KEY_PHONE_NUMBER_ID = "phoneNumberId";
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_APP_SECRET = "appSecret";
    private static final String KEY_VERIFY_TOKEN = "verifyToken";
    private static final String KEY_AGENT_ID = "agentId";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TEMPLATE_NAME = "templateName";
    private static final String KEY_TEMPLATE_LANGUAGE = "templateLanguage";
    private static final String KEY_DEFAULT_TARGET = "defaultTarget";

    private static final String EVENT_CATEGORY_CHANNEL = "channel";
    private static final String CHANNEL_WHATSAPP = "whatsapp";

    /** Flat projection the frontend consumes. The secrets ({@code accessToken},
     *  {@code appSecret}, {@code verifyToken}) are elided — only presence flags
     *  are surfaced. {@code phoneNumberId} and {@code defaultTarget} are
     *  identifiers (not secrets) so they are returned for display, like Slack's
     *  {@code teamId}. */
    private record BindingView(Long id, Long agentId, String agentName,
                                String transport, String phoneNumberId,
                                boolean hasAccessToken, boolean hasAppSecret,
                                boolean hasVerifyToken,
                                String verifiedName, String displayPhoneNumber,
                                String templateName, String templateLanguage,
                                String defaultTarget,
                                boolean enabled, String createdAt, String updatedAt) {
        static BindingView of(WhatsAppBinding b) {
            return new BindingView(b.id,
                    b.agent != null ? b.agent.id : null,
                    b.agent != null ? b.agent.name : null,
                    (b.transport != null ? b.transport : WhatsAppTransport.CLOUD_API).name(),
                    b.phoneNumberId,
                    b.accessToken != null && !b.accessToken.isBlank(),
                    b.appSecret != null && !b.appSecret.isBlank(),
                    b.verifyToken != null && !b.verifyToken.isBlank(),
                    b.verifiedName,
                    b.displayPhoneNumber,
                    b.templateName,
                    b.templateLanguage,
                    b.defaultTarget,
                    b.enabled,
                    b.createdAt != null ? b.createdAt.toString() : null,
                    b.updatedAt != null ? b.updatedAt.toString() : null);
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = BindingView.class))))
    public static void list() {
        var items = WhatsAppBinding.<WhatsAppBinding>findAll().stream()
                .map(BindingView::of)
                .toList();
        renderJSON(gson.toJson(items));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = WhatsAppBinding.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var transport = parseTransport(body, WhatsAppTransport.CLOUD_API);
        Long agentId = body.has(KEY_AGENT_ID) && !body.get(KEY_AGENT_ID).isJsonNull()
                ? body.get(KEY_AGENT_ID).getAsLong() : null;
        if (agentId == null) {
            error(400, "agentId is required");
            throw new AssertionError("unreachable: error() throws");
        }

        String phoneNumberId = readOptionalString(body, KEY_PHONE_NUMBER_ID);
        String accessToken = readOptionalString(body, KEY_ACCESS_TOKEN);
        // The Cloud API needs an outbound sender (phoneNumberId) + auth (accessToken)
        // at minimum; appSecret/verifyToken are required for inbound but may be
        // filled in later (save-then-complete, mirroring Slack). WhatsApp-Web has
        // no credentials here — it's QR-paired in JCLAW-448.
        if (transport == WhatsAppTransport.CLOUD_API
                && (phoneNumberId == null || accessToken == null)) {
            error(400, "The Cloud API transport requires a phone number id and an access token");
            throw new AssertionError("unreachable: error() throws");
        }

        Agent agent = AgentService.findById(agentId);
        if (agent == null || !agent.enabled) {
            error(400, "agentId must reference an enabled agent");
            throw new AssertionError("unreachable: error() throws");
        }
        if (phoneNumberId != null && WhatsAppBinding.findByPhoneNumberId(phoneNumberId) != null) {
            error(409, "A binding with this phone number id already exists");
        }
        // Agent uniqueness mirrors Slack/Telegram: agent memory is scoped per agent,
        // so binding one agent to a second WhatsApp number would share memory.
        if (WhatsAppBinding.findByAgent(agent) != null) {
            error(409, "Agent '%s' is already bound to another WhatsApp binding".formatted(agent.name));
        }

        var binding = new WhatsAppBinding();
        binding.transport = transport;
        binding.agent = agent;
        binding.phoneNumberId = phoneNumberId;
        binding.accessToken = accessToken;
        binding.appSecret = readOptionalString(body, KEY_APP_SECRET);
        binding.verifyToken = readOptionalString(body, KEY_VERIFY_TOKEN);
        binding.templateName = readOptionalString(body, KEY_TEMPLATE_NAME);
        binding.templateLanguage = readOptionalString(body, KEY_TEMPLATE_LANGUAGE);
        // JCLAW-425: Cloud-API proactive-send recipient. An identifier, not a secret.
        binding.defaultTarget = readOptionalString(body, KEY_DEFAULT_TARGET);
        binding.enabled = !body.has(KEY_ENABLED) || body.get(KEY_ENABLED).getAsBoolean();

        // JCLAW-445: verify the Cloud-API credentials against the Graph API BEFORE
        // persisting. A bad token / unverified number is rejected with 422 so the
        // operator never saves a binding that can't deliver. WHATSAPP_WEB has no
        // Cloud-API credentials to probe — it's skipped (the helper returns early).
        if (!verifyCloudApiCredentials(binding, transport, phoneNumberId, accessToken)) {
            return; // 422 already sent by the helper
        }
        binding.save();

        EventLogger.info(EVENT_CATEGORY_CHANNEL, agent.name, CHANNEL_WHATSAPP,
                "Binding %d created".formatted(binding.id));
        // JCLAW-449: open the WhatsApp-Web (Cobalt) connection now if this binding
        // uses that transport, rather than waiting for the next app boot. No-op for
        // Cloud-API bindings (reconcile only touches the WHATSAPP_WEB set).
        WhatsAppCobaltRunner.reconcile();
        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = WhatsAppBinding.class)))
    public static void update(Long id) {
        var binding = WhatsAppBinding.<WhatsAppBinding>findById(id);
        if (binding == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        // Capture the credential identity before mutation so we only re-probe when
        // phoneNumberId or accessToken actually changes (JCLAW-445) — an unrelated
        // edit (enable/disable, verifyToken) must not pay a Graph round-trip.
        String prevPhoneNumberId = binding.phoneNumberId;
        String prevAccessToken = binding.accessToken;

        applyPhoneNumberIdUpdate(binding, body);
        applyAgentUpdate(binding, body);
        applyOptionalFieldUpdates(binding, body);

        boolean credentialsChanged =
                !Objects.equals(prevPhoneNumberId, binding.phoneNumberId)
                        || !Objects.equals(prevAccessToken, binding.accessToken);
        if (credentialsChanged
                && !verifyCloudApiCredentials(binding, binding.transport,
                        binding.phoneNumberId, binding.accessToken)) {
            return; // 422 already sent by the helper
        }
        binding.save();

        EventLogger.info(EVENT_CATEGORY_CHANNEL,
                binding.agent != null ? binding.agent.name : null, CHANNEL_WHATSAPP,
                "Binding %d updated".formatted(binding.id));
        // JCLAW-449: reconcile so a transport/enabled change starts or stops the
        // WhatsApp-Web connection immediately.
        WhatsAppCobaltRunner.reconcile();
        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @SuppressWarnings("java:S2259")
    public static void delete(Long id) {
        var binding = WhatsAppBinding.<WhatsAppBinding>findById(id);
        if (binding == null) notFound();
        String agentName = binding.agent != null ? binding.agent.name : null;
        binding.delete();
        EventLogger.info(EVENT_CATEGORY_CHANNEL, agentName, CHANNEL_WHATSAPP,
                "Binding %d deleted".formatted(id));
        // JCLAW-449: close the WhatsApp-Web connection if the deleted binding had one.
        WhatsAppCobaltRunner.reconcile();
        renderJSON(gson.toJson(Map.of("status", "ok")));
    }

    // ── credential verification (JCLAW-445) ──

    /**
     * Probe the Cloud-API credentials and cache the resolved identity onto the
     * binding, or send a 422 and return {@code false} on failure. A no-op (returns
     * {@code true}) for WHATSAPP_WEB or when either credential is absent — a
     * CLOUD_API create already requires both via the upstream 400 guard, so the
     * only absent-credential path here is an update that clears one, which we let
     * through unverified (and leaves the cached identity untouched).
     *
     * <p>On success the binding's {@code verifiedName}/{@code displayPhoneNumber}
     * are populated from the probe so the projection can surface them; on failure
     * the caller must NOT save (the 422 short-circuits the request).
     */
    @SuppressWarnings("java:S2259")
    private static boolean verifyCloudApiCredentials(WhatsAppBinding binding,
                                                     WhatsAppTransport transport,
                                                     String phoneNumberId,
                                                     String accessToken) {
        if (transport != WhatsAppTransport.CLOUD_API
                || phoneNumberId == null || accessToken == null) {
            return true;
        }
        var result = WhatsAppCloudApiProbe.probe(phoneNumberId, accessToken);
        if (result instanceof WhatsAppCloudApiProbe.Verified(String name, String number)) {
            binding.verifiedName = name;
            binding.displayPhoneNumber = number;
            return true;
        }
        var reason = ((WhatsAppCloudApiProbe.Failed) result).reason();
        EventLogger.warn(EVENT_CATEGORY_CHANNEL,
                binding.agent != null ? binding.agent.name : null, CHANNEL_WHATSAPP,
                "Cloud-API credential verification failed: " + reason);
        error(422, "WhatsApp Cloud-API verification failed: " + reason);
        return false;
    }

    // ── update helpers ──

    /** phoneNumberId is an identifier (not a secret): always editable, with the
     *  same cross-binding uniqueness guard as create. */
    @SuppressWarnings("java:S2259")
    private static void applyPhoneNumberIdUpdate(WhatsAppBinding binding, JsonObject body) {
        if (!body.has(KEY_PHONE_NUMBER_ID)) return;
        String next = readOptionalString(body, KEY_PHONE_NUMBER_ID);
        if (Objects.equals(next, binding.phoneNumberId)) return;
        if (next != null) {
            var existing = WhatsAppBinding.findByPhoneNumberId(next);
            if (existing != null && !existing.id.equals(binding.id)) {
                error(409, "A binding with this phone number id already exists");
            }
        }
        binding.phoneNumberId = next;
    }

    @SuppressWarnings("java:S2259")
    private static void applyAgentUpdate(WhatsAppBinding binding, JsonObject body) {
        if (!body.has(KEY_AGENT_ID) || body.get(KEY_AGENT_ID).isJsonNull()) return;
        Agent agent = AgentService.findById(body.get(KEY_AGENT_ID).getAsLong());
        if (agent == null || !agent.enabled) {
            error(400, "agentId must reference an enabled agent");
        }
        if (binding.agent == null || !agent.id.equals(binding.agent.id)) {
            var other = WhatsAppBinding.findByAgent(agent);
            if (other != null && !other.id.equals(binding.id)) {
                error(409, "Agent '%s' is already bound to another WhatsApp binding".formatted(agent.name));
            }
        }
        binding.agent = agent;
    }

    private static void applyOptionalFieldUpdates(WhatsAppBinding binding, JsonObject body) {
        if (body.has(KEY_TRANSPORT)) binding.transport = parseTransport(body, binding.transport);
        // Secrets: a present-and-non-blank value replaces; a blank leaves the
        // stored value untouched (the form sends blank to keep existing).
        if (body.has(KEY_ACCESS_TOKEN)) {
            String v = readOptionalString(body, KEY_ACCESS_TOKEN);
            if (v != null) binding.accessToken = v;
        }
        if (body.has(KEY_APP_SECRET)) {
            String v = readOptionalString(body, KEY_APP_SECRET);
            if (v != null) binding.appSecret = v;
        }
        if (body.has(KEY_VERIFY_TOKEN)) {
            String v = readOptionalString(body, KEY_VERIFY_TOKEN);
            if (v != null) binding.verifyToken = v;
        }
        // Template name/lang are plain config, not secrets: a present key replaces
        // (including clearing to null, since blank → "no template configured").
        if (body.has(KEY_TEMPLATE_NAME)) {
            binding.templateName = readOptionalString(body, KEY_TEMPLATE_NAME);
        }
        if (body.has(KEY_TEMPLATE_LANGUAGE)) {
            binding.templateLanguage = readOptionalString(body, KEY_TEMPLATE_LANGUAGE);
        }
        // JCLAW-425: default recipient is plain config (not a secret) — a present
        // key replaces, including clearing to null (blank → "no recipient set").
        if (body.has(KEY_DEFAULT_TARGET)) {
            binding.defaultTarget = readOptionalString(body, KEY_DEFAULT_TARGET);
        }
        if (body.has(KEY_ENABLED)) binding.enabled = body.get(KEY_ENABLED).getAsBoolean();
    }

    // ── shared helpers ──

    private static String readOptionalString(JsonObject body, String key) {
        return JsonBodyReader.optString(body, key, true);
    }

    private static WhatsAppTransport parseTransport(JsonObject body, WhatsAppTransport fallback) {
        String raw = body.has(KEY_TRANSPORT) && !body.get(KEY_TRANSPORT).isJsonNull()
                ? body.get(KEY_TRANSPORT).getAsString() : null;
        return WhatsAppTransport.parse(raw, fallback);
    }
}
