package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;

import java.util.List;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiConfigController extends Controller {

    private static final Gson gson = INSTANCE;

    /** Config key prefix reserved for the load-test harness. Users cannot save or delete these. */
    private static final String RESERVED_KEY_PREFIX =
            "provider." + services.LoadTestRunner.LOADTEST_PROVIDER + ".";

    public record ConfigEntry(String key, String value, String updatedAt) {}

    public record ConfigListResponse(List<ConfigEntry> entries) {}

    public record ConfigSaveRequest(String key, String value) {}

    public record ConfigSaveResponse(String key, String value, String status) {}

    public record ConfigDeleteResponse(String status, String key) {}

    /** True for any key the user must not see or mutate through the
     *  Config API: the load-test reservation plus the
     *  {@code auth.internal.*} prefix that {@link services.InternalApiTokenService}
     *  uses for the auto-managed bearer token plaintext. The latter is
     *  not just hidden but rejected outright on save/delete so an
     *  operator typing the key by mistake gets a clear error instead
     *  of breaking the in-process {@code jclaw_api} tool. */
    private static boolean isReservedKey(String key) {
        if (key == null) return false;
        return key.startsWith(RESERVED_KEY_PREFIX)
                || key.startsWith(services.InternalApiTokenService.INTERNAL_KEY_PREFIX);
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigListResponse.class)))
    public static void list() {
        var configs = ConfigService.listAll();
        var entries = configs.stream()
                .filter(c -> !isReservedKey(c.key))
                .map(c -> new ConfigEntry(
                        c.key,
                        ConfigService.maskValue(c.key, c.value),
                        c.updatedAt.toString()))
                .toList();
        renderJSON(gson.toJson(new ConfigListResponse(entries)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigEntry.class)))
    public static void get(String key) {
        if (isReservedKey(key)) notFound();
        var config = models.Config.findByKey(key);
        if (config == null) {
            notFound();
            return;
        }
        renderJSON(gson.toJson(new ConfigEntry(
                config.key,
                ConfigService.maskValue(config.key, config.value),
                config.updatedAt.toString())));
    }

    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ConfigSaveRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigSaveResponse.class)))
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
        if (isReservedKey(key)) {
            error(409, "The config key prefix '%s' is reserved for internal use"
                    .formatted(RESERVED_KEY_PREFIX));
        }

        var rejection = ConfigService.setWithSideEffects(key, value);
        if (rejection != null) {
            error(403, rejection);
            return;
        }

        renderJSON(gson.toJson(new ConfigSaveResponse(
                key, ConfigService.maskValue(key, value), "ok")));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigDeleteResponse.class)))
    public static void delete(String key) {
        if (isReservedKey(key)) {
            error(409, "The config key prefix '%s' is reserved for internal use"
                    .formatted(RESERVED_KEY_PREFIX));
        }
        ConfigService.deleteWithSideEffects(key);
        renderJSON(gson.toJson(new ConfigDeleteResponse("ok", key)));
    }

}
