package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import static utils.GsonHolder.INSTANCE;
import models.ChannelConfig;
import play.mvc.Controller;
import play.mvc.With;
import services.ChannelStatusService;

import java.util.List;
import java.util.Map;

@With(AuthCheck.class)
public class ApiChannelsController extends Controller {

    private static final Gson gson = INSTANCE;

    private record ChannelView(Long id, String channelType, JsonElement config,
                               boolean enabled, String createdAt, String updatedAt) {
        static ChannelView of(ChannelConfig c) {
            return new ChannelView(c.id, c.channelType,
                    JsonParser.parseString(c.configJson),
                    c.enabled, c.createdAt.toString(), c.updatedAt.toString());
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ChannelConfig.class))))
    public static void list() {
        List<ChannelConfig> configs = ChannelConfig.findAll();
        var result = configs.stream().map(ChannelView::of).toList();
        renderJSON(gson.toJson(result));
    }

    /**
     * GET /api/channels/active — channel kinds currently doing work,
     * aggregated across the three sources of truth (web, telegram
     * bindings, ChannelConfig). Dashboard-only consumer; the existing
     * {@link #list()} endpoint stays as-is for the Channels admin page
     * which legitimately wants the {@code ChannelConfig} rows directly.
     *
     * <p>Response: {@code {"count": N, "channelTypes": ["telegram", "web", ...]}}.
     */
    public static void active() {
        var types = ChannelStatusService.activeChannelTypes();
        renderJSON(gson.toJson(Map.of(
                "count", types.size(),
                "channelTypes", types
        )));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ChannelConfig.class)))
    public static void get(String channelType) {
        var config = ChannelConfig.findByType(channelType);
        if (config == null) notFound();
        renderJSON(gson.toJson(ChannelView.of(config)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ChannelConfig.class)))
    public static void save(String channelType) {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

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

        services.EventLogger.info("channel", null, channelType, "Channel config updated");
        renderJSON(gson.toJson(ChannelView.of(config)));
    }

    /**
     * Start/stop the channel's polling runner to match the newly-saved config.
     * Today only Telegram has a polling transport; extend here as other channels
     * (Slack Socket Mode — JCLAW-83) land.
     */
    private static void reconcileRunner(String channelType) {
        if ("telegram".equals(channelType)) {
            channels.TelegramPollingRunner.reconcile();
        }
    }

}
