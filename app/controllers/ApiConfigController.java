package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;

import java.util.HashMap;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiConfigController extends Controller {

    private static final Gson gson = INSTANCE;

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
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("key") || !body.has("value")) {
            badRequest();
        }
        var key = body.get("key").getAsString();
        var value = body.get("value").getAsString();
        if (key.isBlank()) {
            badRequest();
        }

        var rejection = ConfigService.setWithSideEffects(key, value);
        if (rejection != null) {
            error(403, rejection);
            return;
        }

        var map = new HashMap<String, Object>();
        map.put("key", key);
        map.put("value", ConfigService.maskValue(key, value));
        map.put("status", "ok");
        renderJSON(gson.toJson(map));
    }

    public static void delete(String key) {
        ConfigService.deleteWithSideEffects(key);

        var map = new HashMap<String, Object>();
        map.put("status", "ok");
        map.put("key", key);
        renderJSON(gson.toJson(map));
    }

}
