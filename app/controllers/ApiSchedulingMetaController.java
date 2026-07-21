package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import models.Task;
import play.mvc.Controller;
import play.mvc.With;
import services.DeliveryAdvisor;
import services.TaskService;
import services.TimezoneResolver;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static utils.GsonHolder.GSON;

/**
 * Scheduling-metadata API — the IANA timezone list for the task create/edit
 * form and the per-task Slack delivery-reachability advisory. Split out of
 * {@code ApiTasksController} (JCLAW-676); the URL paths
 * ({@code GET /api/timezones}, {@code GET /api/tasks/{id}/delivery-advisory})
 * are unchanged.
 *
 * <p>Single-operator scope: authenticated via {@link AuthCheck} with no
 * per-caller ownership check.
 */
@With(AuthCheck.class)
public class ApiSchedulingMetaController extends Controller {

    private static final Gson gson = GSON;

    /**
     * JCLAW-261: returns the sorted IANA timezone list for the task
     * create/edit form's dropdown. Also reports the currently-effective
     * default (Config row → application.conf → JVM default) so the UI
     * can highlight the operator's working zone without re-implementing
     * the fallback chain client-side.
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "List IANA timezone ids plus the effective task-scheduling and app default zones")
    public static void timezones() {
        var ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        ids.sort(String::compareTo);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("timezones", ids);
        // `default` = effective task-scheduling zone (tasks.defaultTimezone chain).
        // `appDefault` = effective operator wall-clock zone (app.timezone chain,
        // falling back to the server's JVM zone) — used by Settings → General.
        payload.put("default", TimezoneResolver.currentDefault().getId());
        payload.put("appDefault", TimezoneResolver.appZone().getId());
        renderJSON(gson.toJson(payload));
    }

    /**
     * JCLAW-455: preflight Slack delivery reachability for one Task. Returns
     * {@code {"advisory": "<text>"}} when the task's declared Slack channel target
     * isn't reachable (private/uninvited channel, or a public channel the bot hasn't
     * joined), else {@code {"advisory": null}}. The Tasks page fetches this lazily on
     * row-expand and renders it below the delivery value. Probes are 60 s-cached
     * server-side; 404 only if the Task is missing.
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "Preflight Slack delivery reachability advisory for one task (null when reachable / N/A)")
    public static void deliveryAdvisory(Long id) {
        Task task = TaskService.findById(id);
        if (task == null) notFound();
        var advisory = DeliveryAdvisor.advisoryFor(task.agent, task.delivery);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("advisory", advisory);
        renderJSON(gson.toJson(payload));
    }
}
