package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.EventLogger;

import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * First-login guided-tour state. The frontend stores the in-progress step in
 * localStorage; this controller owns the server-side "have they progressed
 * far enough that we should stop auto-showing the intro dialog?" threshold.
 *
 * <p>Single Config key today (single-admin install). When per-user auth lands,
 * scope this per user — the prefix {@code onboarding.} will be reserved in
 * the Settings page's {@code MANAGED_PREFIXES} list (added in a later task)
 * so the unmanaged-keys diagnostic doesn't surface this key.
 */
@With(AuthCheck.class)
public class ApiOnboardingController extends Controller {

    private static final Gson gson = INSTANCE;

    public static final String CONFIG_KEY = "onboarding.tourMaxStep";
    private static final int TOTAL_STEPS = 6;
    private static final int AUTO_SHOW_THRESHOLD = 4;

    /** GET /api/onboarding/tour-status — returns the recorded max step,
     *  total step count, and whether the dashboard should auto-show the
     *  intro dialog. Threshold lives server-side so the rule isn't
     *  duplicated across the controller and the page. */
    public static void status() {
        var maxStep = ConfigService.getInt(CONFIG_KEY, 0);
        renderJSON(gson.toJson(Map.of(
                "maxStepReached", maxStep,
                "totalSteps", TOTAL_STEPS,
                "shouldAutoShow", maxStep < AUTO_SHOW_THRESHOLD)));
    }

    /** POST /api/onboarding/tour-progress — body {@code {"step":N}}.
     *  Upserts {@code Math.max(existing, step)} so out-of-order writes can
     *  never lower the recorded max. Validates step is in [1, TOTAL_STEPS]. */
    public static void recordProgress() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("step")) {
            badRequest();
            return;
        }
        int step;
        try {
            step = body.get("step").getAsInt();
        }
        catch (Exception _) {
            badRequest();
            return;
        }
        if (step < 1 || step > TOTAL_STEPS) {
            badRequest();
            return;
        }
        var existing = ConfigService.getInt(CONFIG_KEY, 0);
        var newMax = Math.max(existing, step);
        if (newMax != existing) {
            ConfigService.set(CONFIG_KEY, String.valueOf(newMax));
            EventLogger.info("onboarding", "Tour progressed to step %d (was %d)".formatted(newMax, existing));
        }
        renderJSON(gson.toJson(Map.of("maxStepReached", newMax)));
    }
}
