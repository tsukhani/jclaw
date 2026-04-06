package controllers;

import play.mvc.Controller;
import com.google.gson.JsonObject;

/**
 * API controller for the Nuxt 3 frontend.
 * All endpoints are prefixed with /api/ in the routes file.
 */
public class ApiController extends Controller {

    public static void status() {
        JsonObject json = new JsonObject();
        json.addProperty("status", "ok");
        json.addProperty("application", play.Play.configuration.getProperty("application.name"));
        json.addProperty("mode", play.Play.mode.toString());
        json.addProperty("version", play.Play.version);
        renderJSON(json.toString());
    }
}
