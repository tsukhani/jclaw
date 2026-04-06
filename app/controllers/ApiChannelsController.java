package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@With(AuthCheck.class)
public class ApiChannelsController extends Controller {

    private static final Gson gson = new Gson();

    public static void list() {
        List<ChannelConfig> configs = ChannelConfig.findAll();
        var result = configs.stream().map(ApiChannelsController::configToMap).toList();
        renderJSON(gson.toJson(result));
    }

    public static void get(String channelType) {
        var config = ChannelConfig.findByType(channelType);
        if (config == null) notFound();
        renderJSON(gson.toJson(configToMap(config)));
    }

    public static void save(String channelType) {
        var body = readJsonBody();
        if (body == null) badRequest();

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

        services.EventLogger.info("channel", null, channelType, "Channel config updated");
        renderJSON(gson.toJson(configToMap(config)));
    }

    private static Map<String, Object> configToMap(ChannelConfig c) {
        var map = new HashMap<String, Object>();
        map.put("id", c.id);
        map.put("channelType", c.channelType);
        map.put("config", JsonParser.parseString(c.configJson));
        map.put("enabled", c.enabled);
        map.put("createdAt", c.createdAt.toString());
        map.put("updatedAt", c.updatedAt.toString());
        return map;
    }

    private static com.google.gson.JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
