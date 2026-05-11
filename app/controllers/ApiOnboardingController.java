package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.EventLogger;

import static utils.GsonHolder.INSTANCE;

/**
 * First-login guided-tour flag. Stores a high-water "farthest step reached"
 * counter per install — 0 means the user has never interacted with the tour
 * intro dialog, anything ≥ 1 means they have (either clicked Start/Skip on
 * the intro, or advanced into the walkthrough itself). The dashboard reads
 * this to decide whether to auto-show the intro dialog on mount; the tour
 * walkthrough itself is purely in-memory, so reloading mid-tour abandons
 * progress and the user must re-enter from the Guided Tour sidebar button.
 *
 * <p>Single Config key today (single-admin install). When per-user auth
 * lands, scope this per user — the prefix {@code onboarding.} is already
 * reserved in the Settings page's {@code MANAGED_PREFIXES} list so the
 * unmanaged-keys diagnostic doesn't surface this key.
 */
@With(AuthCheck.class)
public class ApiOnboardingController extends Controller {

    private static final Gson gson = INSTANCE;

    public static final String CONFIG_KEY = "onboarding.tourMaxStep";
    private static final int TOTAL_STEPS = 6;

    public record TourStatusResponse(int maxStepReached, int totalSteps, boolean shouldAutoShow) {}

    public record TourProgressRequest(int step) {}

    public record TourProgressResponse(int maxStepReached) {}

    /** GET /api/onboarding/tour-status — returns the recorded max step,
     *  total step count, and whether the dashboard should auto-show the
     *  intro dialog. Auto-show fires when the user has never interacted
     *  with the tour ({@code maxStep == 0}); once they click Start or Skip
     *  on the intro dialog (or advance any step), the flag flips forever. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TourStatusResponse.class)))
    public static void status() {
        var maxStep = ConfigService.getInt(CONFIG_KEY, 0);
        renderJSON(gson.toJson(new TourStatusResponse(maxStep, TOTAL_STEPS, maxStep == 0)));
    }

    /** POST /api/onboarding/tour-progress — body {@code {"step":N}}.
     *  Upserts {@code Math.max(existing, step)} so out-of-order writes can
     *  never lower the recorded max. Validates step is in [1, TOTAL_STEPS]. */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = TourProgressRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TourProgressResponse.class)))
    public static void recordProgress() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("step")) badRequest();
        int step;
        try {
            step = body.get("step").getAsInt();
        }
        catch (Exception _) {
            badRequest();
            return;
        }
        if (step < 1 || step > TOTAL_STEPS) badRequest();
        var existing = ConfigService.getInt(CONFIG_KEY, 0);
        var newMax = Math.max(existing, step);
        if (newMax != existing) {
            ConfigService.set(CONFIG_KEY, String.valueOf(newMax));
            EventLogger.info("onboarding",
                    "Tour progressed to step %s".formatted(newMax),
                    "previous=%s".formatted(existing));
        }
        renderJSON(gson.toJson(new TourProgressResponse(newMax)));
    }
}
