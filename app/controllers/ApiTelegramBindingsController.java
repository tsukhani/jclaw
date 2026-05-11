package controllers;

import channels.ChannelTransport;
import channels.TelegramPollingRunner;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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

    /** Flat projection the frontend consumes. {@code botToken} and
     *  {@code webhookSecret} are elided — they're secrets. {@code webhookUrl}
     *  is surfaced so the Edit UI can pre-populate it; Telegram calls it
     *  publicly so it isn't sensitive on its own. {@code cooldownUntil} is the
     *  ISO-8601 instant until which re-registration is blocked after an
     *  unregister (post-JCLAW-89 Telegram long-poll cooldown). */
    private record BindingView(Long id, Long agentId, String agentName,
                                String telegramUserId, String transport,
                                String webhookUrl, boolean hasWebhookSecret,
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
                    b.webhookUrl,
                    b.webhookSecret != null && !b.webhookSecret.isBlank(),
                    b.enabled,
                    cooldown != null ? cooldown.toString() : null,
                    b.createdAt != null ? b.createdAt.toString() : null,
                    b.updatedAt != null ? b.updatedAt.toString() : null);
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = BindingView.class))))
    public static void list() {
        var items = TelegramBinding.<TelegramBinding>findAll().stream()
                .map(BindingView::of)
                .toList();
        renderJSON(gson.toJson(items));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        String botToken = readRequiredString(body, "botToken");
        Long agentId = body.has("agentId") && !body.get("agentId").isJsonNull()
                ? body.get("agentId").getAsLong() : null;
        String telegramUserId = readRequiredString(body, "telegramUserId");

        if (botToken == null || agentId == null || telegramUserId == null) {
            error(400, "botToken, agentId, and telegramUserId are required");
        }
        if (!telegramUserId.matches("\\d+")) {
            error(400, "telegramUserId must be numeric");
        }

        Agent agent = AgentService.findById(agentId);
        if (agent == null || !agent.enabled) {
            error(400, "agentId must reference an enabled agent");
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
        binding.webhookSecret = readOptionalString(body, "webhookSecret");
        binding.webhookUrl = readOptionalString(body, "webhookUrl");
        binding.enabled = body.has("enabled") ? body.get("enabled").getAsBoolean() : true;
        binding.save();

        EventLogger.info("channel", agent.name, "telegram",
                "Binding %d created (user=%s)".formatted(binding.id, telegramUserId));

        TelegramPollingRunner.reconcile();
        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BindingView.class)))
    public static void update(Long id) {
        var binding = TelegramBinding.<TelegramBinding>findById(id);
        if (binding == null) notFound();

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        if (body.has("botToken")) {
            String newToken = body.get("botToken").getAsString();
            if (newToken != null && !newToken.isBlank() && !newToken.equals(binding.botToken)) {
                var existing = TelegramBinding.findByBotToken(newToken);
                if (existing != null && !existing.id.equals(binding.id)) {
                    error(409, "A binding with this bot token already exists");
                }
                binding.botToken = newToken;
            }
        }
        if (body.has("agentId") && !body.get("agentId").isJsonNull()) {
            Agent agent = AgentService.findById(body.get("agentId").getAsLong());
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
        if (body.has("telegramUserId")) {
            String uid = body.get("telegramUserId").getAsString();
            if (uid == null || !uid.matches("\\d+")) {
                error(400, "telegramUserId must be numeric");
            }
            binding.telegramUserId = uid;
        }
        if (body.has("transport")) {
            binding.transport = parseTransport(body, binding.transport);
        }
        if (body.has("webhookSecret")) {
            binding.webhookSecret = readOptionalString(body, "webhookSecret");
        }
        if (body.has("webhookUrl")) {
            binding.webhookUrl = readOptionalString(body, "webhookUrl");
        }
        if (body.has("enabled")) {
            binding.enabled = body.get("enabled").getAsBoolean();
        }
        binding.save();

        EventLogger.info("channel",
                binding.agent != null ? binding.agent.name : null, "telegram",
                "Binding %d updated".formatted(binding.id));

        TelegramPollingRunner.reconcile();
        renderJSON(gson.toJson(BindingView.of(binding)));
    }

    public static void delete(Long id) {
        var binding = TelegramBinding.<TelegramBinding>findById(id);
        if (binding == null) notFound();
        String agentName = binding.agent != null ? binding.agent.name : null;
        binding.delete();
        EventLogger.info("channel", agentName, "telegram",
                "Binding %d deleted".formatted(id));
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
        String raw = body.has("transport") && !body.get("transport").isJsonNull()
                ? body.get("transport").getAsString() : null;
        return ChannelTransport.parse(raw, fallback);
    }
}
