package controllers;

import channels.TelegramPollingRunner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.ChannelConfig;
import play.mvc.Controller;
import play.mvc.With;
import services.ChannelStatusService;
import services.ConfigService;
import services.EventLogger;
import utils.ApiResponses;

import java.util.List;
import java.util.Set;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

@With(AuthCheck.class)
public class ApiChannelsController extends Controller {

    private static final Gson gson = GSON;

    private record ChannelView(Long id, String channelType, JsonElement config,
                               boolean enabled, String createdAt, String updatedAt) {
        static ChannelView of(ChannelConfig c) {
            return new ChannelView(c.id, c.channelType,
                    maskConfig(c.configJson),
                    c.enabled, c.createdAt.toString(), c.updatedAt.toString());
        }
    }

    /**
     * JCLAW-780: parse the stored channel config and mask its secrets before it reaches the {@link #list()} /
     * {@link #get} responses. Sensitivity is key-driven via {@link ConfigService#isSensitive}, at any depth
     * (JF-294): a string or a number under a sensitive key is masked, and so is every one inside an object or an
     * array under it. A boolean stays, since {@code hasToken} names a flag and not a secret.
     */
    private static JsonElement maskConfig(String configJson) {
        return masked(JsonParser.parseString(configJson), false);
    }

    private static JsonElement masked(JsonElement element, boolean secret) {
        if (element.isJsonObject()) {
            var out = new JsonObject();
            for (var entry : element.getAsJsonObject().entrySet()) {
                out.add(entry.getKey(), masked(entry.getValue(), secret || ConfigService.isSensitive(entry.getKey())));
            }
            return out;
        }
        if (element.isJsonArray()) {
            var out = new JsonArray();
            for (var item : element.getAsJsonArray()) {
                out.add(masked(item, secret));
            }
            return out;
        }
        if (!secret || !element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean()
                || element.getAsString().isEmpty()) {
            return element;
        }
        return new JsonPrimitive(ConfigService.SECRET_MASK);
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChannelView.class))))
    @Operation(summary = "List all stored channel configurations")
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the channels are the instance's setup")
    public static void list() {
        RequestPrincipal.requireOperatorOrMain("read the channel configurations");
        List<ChannelConfig> configs = ChannelConfig.findAll();
        var result = configs.stream().map(ChannelView::of).toList();
        renderJSON(gson.toJson(result));
    }

    /** Dashboard summary of the channel kinds currently doing work. */
    private record ActiveChannelsResponse(int count, Set<String> channelTypes) {}

    /**
     * GET /api/channels/active — channel kinds currently doing work,
     * aggregated across the Telegram, Slack and WhatsApp bindings and
     * ChannelConfig. Dashboard-only consumer; the existing
     * {@link #list()} endpoint stays as-is for the Channels admin page
     * which legitimately wants the {@code ChannelConfig} rows directly.
     *
     * <p>Response: {@code {"count": N, "channelTypes": ["telegram", "slack", ...]}}.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ActiveChannelsResponse.class)))
    @Operation(summary = "Get the count and set of channel types currently doing work, aggregated across channel bindings and ChannelConfig")
    @AgentAccess(OPEN)
    public static void active() {
        var types = ChannelStatusService.activeChannelTypes();
        renderJSON(gson.toJson(new ActiveChannelsResponse(types.size(), types)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ChannelView.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "channel config detail may include secrets")
    public static void get(String channelType) {
        var config = ChannelConfig.findByType(channelType);
        if (config == null) notFound();
        renderJSON(gson.toJson(ChannelView.of(config)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ChannelView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ChannelConfig.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "writes channel config -- secrets / comms routing")
    public static void save(String channelType) {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            badRequest();
            throw ApiResponses.unreachable();
        }

        // Evict cache before lookup so we get a managed (attached) entity for write
        ChannelConfig.evictCache(channelType);
        var config = ChannelConfig.findByType(channelType);
        if (config == null) {
            config = new ChannelConfig();
            config.channelType = channelType;
        }

        if (body.has("config")) {
            config.configJson = gson.toJson(body.getAsJsonObject("config"));
        }
        if (body.has("enabled")) {
            config.enabled = body.get("enabled").getAsBoolean();
        }
        config.save();
        ChannelConfig.evictCache(channelType); // evict again so next read sees the update

        reconcileRunner(channelType);

        EventLogger.info("channel", null, channelType, "Channel config updated");
        renderJSON(gson.toJson(ChannelView.of(config)));
    }

    /**
     * Start/stop the channel's polling runner to match the newly-saved config.
     * Today only Telegram has a polling transport.
     */
    private static void reconcileRunner(String channelType) {
        if ("telegram".equals(channelType)) {
            TelegramPollingRunner.reconcile();
        }
    }

}
