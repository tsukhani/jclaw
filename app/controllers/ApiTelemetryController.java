package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.telemetry.OtelRuntime;

import static utils.GsonHolder.GSON;

/**
 * OpenTelemetry export state and a delivery check (JCLAW-34). The {@code otel.*} keys
 * themselves go through {@code /api/config} like every other setting; this controller
 * exposes what the runtime made of them.
 */
@With(AuthCheck.class)
public class ApiTelemetryController extends Controller {

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = OtelRuntime.Status.class)))
    @Operation(summary = "OpenTelemetry export status: enabled, endpoint, protocol, last export error")
    @ChatHidden("operator observability plumbing -- names the collector, nothing an agent needs")
    public static void status() {
        renderJSON(GSON.toJson(OtelRuntime.status()));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = OtelRuntime.TestResult.class)))
    @Operation(summary = "Emit one test span and report whether the collector confirmed it")
    @ChatHidden("emits a span to the operator's collector -- an outward side effect")
    public static void test() {
        renderJSON(GSON.toJson(OtelRuntime.sendTestSpan()));
    }
}
