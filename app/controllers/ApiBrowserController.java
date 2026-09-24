package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.browser.BrowserSetup;
import tools.PlaywrightBrowserTool;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * The browser tool's first-use setup: the Playwright driver's Node.js and Chromium. The chat polls
 * {@code GET} while a browser call runs, to show a progress bar during a first-use download; the
 * Settings browser panel reads the same state and offers {@code POST} to download ahead of time.
 */
@With(AuthCheck.class)
public class ApiBrowserController extends Controller {

    @Operation(summary = "Browser setup status: the driver's source, whether Chromium is installed, and any download in flight")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BrowserSetup.Snapshot.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void setup() {
        renderJSON(GSON.toJson(BrowserSetup.snapshot()));
    }

    @Operation(summary = "Download the browser driver and Chromium now rather than on first use")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BrowserSetup.Snapshot.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "downloads Node.js and Chromium — a disk and network action")
    public static void install() {
        PlaywrightBrowserTool.startBrowserSetup();
        renderJSON(GSON.toJson(BrowserSetup.snapshot()));
    }
}
