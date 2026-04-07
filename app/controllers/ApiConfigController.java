package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.AgentService;
import services.ConfigService;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

@With(AuthCheck.class)
public class ApiConfigController extends Controller {

    private static final Gson gson = new Gson();

    public static void list() {
        var configs = ConfigService.listAll();
        var entries = configs.stream().map(c -> {
            var map = new HashMap<String, Object>();
            map.put("key", c.key);
            map.put("value", ConfigService.maskValue(c.key, c.value));
            map.put("updatedAt", c.updatedAt.toString());
            return map;
        }).toList();

        var result = new HashMap<String, Object>();
        result.put("entries", entries);
        renderJSON(gson.toJson(result));
    }

    public static void get(String key) {
        var config = models.Config.findByKey(key);
        if (config == null) {
            notFound();
        }
        var map = new HashMap<String, Object>();
        map.put("key", config.key);
        map.put("value", ConfigService.maskValue(config.key, config.value));
        map.put("updatedAt", config.updatedAt.toString());
        renderJSON(gson.toJson(map));
    }

    public static void save() {
        var body = readJsonBody();
        if (body == null || !body.has("key") || !body.has("value")) {
            badRequest();
        }
        var key = body.get("key").getAsString();
        var value = body.get("value").getAsString();
        if (key.isBlank()) {
            badRequest();
        }
        ConfigService.set(key, value);

        if (key.startsWith("provider.")) {
            AgentService.syncEnabledStates();
        }
        if (key.startsWith("jclaw.tools.")) {
            jobs.ToolRegistrationJob.registerAll();
        }

        var map = new HashMap<String, Object>();
        map.put("key", key);
        map.put("value", ConfigService.maskValue(key, value));
        map.put("status", "ok");
        renderJSON(gson.toJson(map));
    }

    public static void delete(String key) {
        ConfigService.delete(key);

        if (key.startsWith("provider.")) {
            AgentService.syncEnabledStates();
        }

        var map = new HashMap<String, Object>();
        map.put("status", "ok");
        map.put("key", key);
        renderJSON(gson.toJson(map));
    }

    static JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
}
