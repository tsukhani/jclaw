package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import static utils.GsonHolder.INSTANCE;
import models.ChannelConfig;
import play.mvc.Controller;
import play.mvc.With;

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

    public static void list() {
        List<ChannelConfig> configs = ChannelConfig.findAll();
        var result = configs.stream().map(ChannelView::of).toList();
        renderJSON(gson.toJson(result));
    }

    public static void get(String channelType) {
        var config = ChannelConfig.findByType(channelType);
        if (config == null) notFound();
        renderJSON(gson.toJson(ChannelView.of(config)));
    }

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
