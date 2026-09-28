package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import tools.scrape.ScrapeProxyCheck;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/** The scrape proxy's connection test (JCLAW-1323). The {@code web_scrape.proxy.*} keys go through {@code /api/config}. */
@With(AuthCheck.class)
public class ApiScrapeProxyController extends Controller {

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ScrapeProxyCheck.ProxyCheckResult.class)))
    @Operation(summary = "Send one request through the saved scrape proxy; returns the egress IP or the proxy's refusal")
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "spends paid proxy traffic and probes the operator's proxy settings")
    public static void test() {
        renderJSON(GSON.toJson(ScrapeProxyCheck.run()));
    }
}
