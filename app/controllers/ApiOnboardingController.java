package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;

import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * First-login guided-tour state. The frontend stores the in-progress step in
 * localStorage; this controller owns the server-side "have they progressed
 * far enough that we should stop auto-showing the intro dialog?" threshold.
 *
 * <p>Single Config key today (single-admin install). When per-user auth lands,
 * scope this per user — the prefix {@code onboarding.} is already reserved in
 * the Settings page's MANAGED_PREFIXES list.
 */
@With(AuthCheck.class)
public class ApiOnboardingController extends Controller {

    private static final Gson gson = INSTANCE;

    static final String CONFIG_KEY = "onboarding.tourMaxStep";
    static final int TOTAL_STEPS = 6;
    static final int AUTO_SHOW_THRESHOLD = 4;

    /** GET /api/onboarding/tour-status — returns the recorded max step,
     *  total step count, and whether the dashboard should auto-show the
     *  intro dialog. Threshold lives server-side so the rule isn't
     *  duplicated across the controller and the page. */
    public static void status() {
        var max = ConfigService.getInt(CONFIG_KEY, 0);
        renderJSON(gson.toJson(Map.of(
                "maxStepReached", max,
                "totalSteps", TOTAL_STEPS,
                "shouldAutoShow", max < AUTO_SHOW_THRESHOLD)));
    }
}
