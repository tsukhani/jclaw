package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import play.Play;

import static utils.GsonHolder.INSTANCE;
import play.mvc.Controller;
import play.mvc.Http;
import services.EventLogger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;

public class ApiAuthController extends Controller {

    private static final Gson gson = INSTANCE;

    public static void login() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            var body = JsonParser.parseReader(reader).getAsJsonObject();

            var username = body.get("username").getAsString();
            var password = body.get("password").getAsString();

            var expectedUser = Play.configuration.getProperty("jclaw.admin.username", "admin");
            var expectedPass = Play.configuration.getProperty("jclaw.admin.password", "changeme");

            if (constantTimeEquals(expectedUser, username) && constantTimeEquals(expectedPass, password)) {
                session.put("authenticated", "true");
                session.put("username", username);
                EventLogger.info("auth", "Admin login successful");
                var result = new HashMap<String, Object>();
                result.put("status", "ok");
                result.put("username", username);
                renderJSON(gson.toJson(result));
            } else {
                EventLogger.warn("auth", "Admin login failed for username: %s".formatted(username));
                response.status = 403;
                renderJSON("{\"error\":\"Invalid credentials\"}");
            }
        } catch (Exception e) {
            badRequest();
        }
    }

    public static void logout() {
        session.clear();
        EventLogger.info("auth", "Admin logged out");
        var result = new HashMap<String, Object>();
        result.put("status", "ok");
        renderJSON(gson.toJson(result));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
