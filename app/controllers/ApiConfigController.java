package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.InternalApiTokenService;
import services.LoadTestRunner;
import services.LoggerLevelService;
import services.PrivilegedConfig;
import utils.ApiErrorTemplates;
import utils.ApiResponses;

import java.util.List;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

@With(AuthCheck.class)
public class ApiConfigController extends Controller {

    private static final Gson gson = GSON;

    /** Config key prefix reserved for the load-test harness. Users cannot save or delete these. */
    private static final String RESERVED_KEY_PREFIX =
            "provider." + LoadTestRunner.LOADTEST_PROVIDER + ".";

    /** All {@code auth.*} config is reserved: the admin password hash
     *  ({@code auth.admin.passwordHash}) and the auto-managed internal bearer
     *  token ({@code auth.internal.*}). Auth is administered through the dedicated
     *  {@code /api/auth/*} endpoints and the Settings UI, never the generic config
     *  API -- so reserving the whole prefix closes a lockout / privilege path (e.g.
     *  an agent calling {@code DELETE /api/config/auth.admin.passwordHash} via the
     *  jclaw_api tool to wipe the password) without removing any legitimate
     *  capability. */
    private static final String AUTH_KEY_PREFIX = "auth.";

    /** Per-logger level overrides ({@code logging.level.*}) are managed solely
     *  through the dedicated {@code /api/logging/levels} endpoints and the
     *  Logging Levels section of Settings. Reserving the prefix here keeps them
     *  out of the generic config list (so they aren't double-managed) and, more
     *  importantly, off the {@code maskValue} path — a logger name containing
     *  "token"/"secret"/"key" would otherwise get its level value masked. */
    private static final String LOGGING_KEY_PREFIX = LoggerLevelService.PREFIX;


    public record ConfigEntry(String key, @Nullable String value, String updatedAt) {}

    public record ConfigListResponse(List<ConfigEntry> entries) {}

    public record ConfigSaveRequest(String key, String value) {}

    public record ConfigSaveResponse(String key, @Nullable String value, String status) {}

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
                || key.startsWith(AUTH_KEY_PREFIX)
                || key.startsWith(LOGGING_KEY_PREFIX)
                || key.startsWith(InternalApiTokenService.INTERNAL_KEY_PREFIX);
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigListResponse.class)))
    @Operation(summary = "List all config rows (sensitive values masked)")
    @AgentAccess(OPEN)
    public static void list() {
        var configs = ConfigService.listAll();
        var entries = configs.stream()
                .filter(c -> !isReservedKey(c.key()))
                .map(ApiConfigController::toView)
                .toList();
        renderJSON(gson.toJson(new ConfigListResponse(entries)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigEntry.class)))
    @Operation(summary = "Read a config value by key")
    @AgentAccess(OPEN)
    public static void get(String key) {
        if (isReservedKey(key)) notFound();
        var config = ConfigService.entry(key);
        if (config == null) {
            notFound();
            throw ApiResponses.unreachable();
        }
        renderJSON(gson.toJson(toView(config)));
    }

    /** The masked view of a row; a credential that will not decrypt reads as empty, as an unset one would. */
    private static ConfigEntry toView(ConfigService.Entry row) {
        var value = row.value();
        var shown = row.unreadable() || value == null
                ? "" : ConfigService.maskValue(row.key(), effectiveValue(row.key(), value));
        return new ConfigEntry(row.key(), shown, row.updatedAt().toString());
    }

    /**
     * The row's value once an application.conf ceiling applies, which is what takes effect. A stored
     * row can hold a looser value than the ceiling allows, and showing it would present that as live.
     */
    private static String effectiveValue(String key, String value) {
        var reconciled = PrivilegedConfig.reconcile(key, value);
        return reconciled != null ? reconciled : value;
    }


    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ConfigSaveRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigSaveResponse.class)))
    @Operation(summary = "Write a config value")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "writes the instance's own security controls -- privilege escalation")
    public static void save() {

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("key") || !body.has("value")) {
            badRequest();
            throw ApiResponses.unreachable();
        }
        var key = body.get("key").getAsString();
        var value = body.get("value").getAsString();
        if (key.isBlank()) {
            badRequest();
        }
        if (isReservedKey(key)) {
            ApiResponses.error(409, ApiResponses.RESERVED_KEY, "The config key prefix '%s' is reserved for internal use"
                    .formatted(RESERVED_KEY_PREFIX));
        }

        var rejection = ConfigService.setWithSideEffects(key, value);
        if (rejection != null) {
            ApiResponses.errorWithTemplate(403, ApiResponses.FORBIDDEN, rejection,
                    ApiErrorTemplates.configValueRejected());
        }

        renderJSON(gson.toJson(new ConfigSaveResponse(
                key, ConfigService.maskValue(key, value), "ok")));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ConfigDeleteResponse.class)))
    @Operation(summary = "Delete a config value by key")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "deletes a config row, reverting a control to its code default -- privilege escalation")
    public static void delete(String key) {

        if (isReservedKey(key)) {
            ApiResponses.error(409, ApiResponses.RESERVED_KEY, "The config key prefix '%s' is reserved for internal use"
                    .formatted(RESERVED_KEY_PREFIX));
        }
        var rejection = ConfigService.deleteWithSideEffects(key);
        if (rejection != null) {
            ApiResponses.errorWithTemplate(403, ApiResponses.FORBIDDEN, rejection,
                    ApiErrorTemplates.configValueRejected());
        }
        renderJSON(gson.toJson(new ConfigDeleteResponse("ok", key)));
    }

}
