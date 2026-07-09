package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.LoggerLevelService;
import utils.ApiResponses;

import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Per-logger log-level overrides. Backs the Logging Levels section of Settings.
 *
 * <p>Persistence and live application both go through
 * {@link ConfigService#setWithSideEffects} / {@code deleteWithSideEffects}
 * (keyed {@code logging.level.<logger>}); this controller only adds validation
 * and a clean logger/level projection so the level value never hits the generic
 * config {@code maskValue} path. See {@link LoggerLevelService}.
 */
@With(AuthCheck.class)
public class ApiLoggingController extends Controller {

    private static final Gson gson = INSTANCE;

    public record LevelEntry(String logger, String level) {}

    public record LevelsResponse(List<LevelEntry> entries, List<String> validLevels,
                                 List<String> knownLoggers) {}

    public record SaveRequest(String logger, String level) {}

    public record SaveResponse(String logger, String level, String status) {}

    public record DeleteResponse(String status, String logger) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = LevelsResponse.class)))
    @Operation(summary = "List per-logger level overrides plus the valid level names")
    public static void list() {
        var entries = LoggerLevelService.list().stream()
                .map(l -> new LevelEntry(l.logger(), l.level()))
                .toList();
        renderJSON(gson.toJson(new LevelsResponse(
                entries, LoggerLevelService.VALID_LEVELS, LoggerLevelService.knownLoggers())));
    }

    @SuppressWarnings("java:S2259") // badRequest() halts; body is non-null past the guard
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SaveRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SaveResponse.class)))
    @Operation(summary = "Add or update a per-logger level override (applies live)")
    public static void save() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("logger") || !body.has("level")) {
            badRequest();
        }
        var logger = body.get("logger").getAsString().trim();
        var level = body.get("level").getAsString().trim().toUpperCase();

        var rejection = LoggerLevelService.validate(logger, level);
        if (rejection != null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, rejection);
        }

        ConfigService.setWithSideEffects(LoggerLevelService.PREFIX + logger, level);
        renderJSON(gson.toJson(new SaveResponse(logger, level, "ok")));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DeleteResponse.class)))
    @Operation(summary = "Remove a per-logger level override (reverts to inherited level)")
    public static void delete(String logger) {
        if (logger == null || logger.isBlank()) {
            badRequest();
        }
        ConfigService.deleteWithSideEffects(LoggerLevelService.PREFIX + logger.trim());
        renderJSON(gson.toJson(new DeleteResponse("ok", logger)));
    }
}
