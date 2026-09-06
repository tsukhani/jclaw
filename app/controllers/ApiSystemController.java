package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.GitCheckout;
import services.RestartService;
import services.SubagentRegistry;
import services.UpgradeService;
import utils.ApiResponses;

import static utils.GsonHolder.GSON;

/**
 * Instance-level maintenance operations — restart and upgrade
 * (Settings → Restart / Upgrade).
 *
 * <p>Neither is gated on in-flight work. The moment an operator most wants to
 * reboot is usually the moment something is wedged, so a 409 while a stuck task
 * "runs" would lock them out of the one control that clears it. Instead the
 * preflights report what is in flight and the UI makes the operator confirm
 * against it.
 */
@With(AuthCheck.class)
public class ApiSystemController extends Controller {

    /**
     * @param available          whether a restart can be performed at all —
     *                           false when this install has no {@code jclaw.sh}
     *                           to hand off to
     * @param unavailableReason  why not, or null when {@code available}
     * @param mode               {@code "DEV"} or {@code "PROD"}
     * @param backendOnly        true when the Nuxt dev server will be spared
     *                           (dev mode); the browser keeps its connection
     * @param rebuildExpected    true when the restart may recompile sources and
     *                           rebuild the SPA — a source tree rather than a
     *                           packaged install. Coarse by design; see
     *                           {@link RestartService.Plan}
     * @param runningTasks       task runs in RUNNING state, which a restart
     *                           interrupts
     * @param activeSubagentRuns subagent runs live in THIS JVM, which a restart
     *                           interrupts
     */
    public record RestartPreflight(boolean available, @Nullable String unavailableReason, String mode,
                                   boolean backendOnly, boolean rebuildExpected,
                                   long runningTasks, int activeSubagentRuns) {}

    /**
     * GET /api/system/restart — what a restart would do and what it would
     * interrupt. Drives the confirmation dialog; performs nothing.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RestartPreflight.class)))
    public static void restartPreflight() {
        var plan = RestartService.plan();
        var unavailable = RestartService.unavailableReason();

        renderJSON(GSON.toJson(new RestartPreflight(
                unavailable == null, unavailable, plan.mode(),
                plan.backendOnly(), plan.rebuildExpected(),
                TaskRun.count("status = ?1", TaskRun.Status.RUNNING),
                SubagentRegistry.activeRunIds().size())));
    }

    /**
     * POST /api/system/restart — hand off to the restart helper and ack.
     *
     * <p>Returns 202, not 200: the reboot has been accepted, not completed.
     * This JVM keeps serving for a couple more seconds so this very response
     * can reach the browser, then the helper stops it.
     */
    @ChatHidden("stops and relaunches the instance -- availability")
    public static void restart() {
        // The success render stays OUT of the try. Play signals results by
        // throwing, and RenderJson is a RuntimeException — inside the try below
        // the catch-all would swallow its own 202 and answer 500 instead.
        RestartService.Plan plan;
        try {
            plan = RestartService.requestRestart();
        } catch (IllegalStateException e) {
            // Install can't be restarted (no jclaw.sh) — the app is still up,
            // so this is a plain refusal rather than a half-completed reboot.
            ApiResponses.error(409, ApiResponses.CONFLICT, ApiResponses.messageOf(e));
            return;
        } catch (Exception e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR,
                    "Failed to launch the restart helper: " + e.getMessage());
            return;
        }

        response.status = 202;
        ApiResponses.ok("mode", plan.mode(),
                "backendOnly", plan.backendOnly(),
                "rebuildExpected", plan.rebuildExpected());
    }

    /**
     * @param available          whether this install can upgrade itself — false
     *                           for a source checkout, a container, or a tree
     *                           jclaw.sh cannot replace
     * @param unavailableReason  why not, or null when {@code available}
     * @param currentVersion     the version running now
     * @param latestVersion      newest published release, or null when GitHub
     *                           could not be reached
     * @param upgradeAvailable   true when {@code latestVersion} is newer
     * @param installKind        {@code "bundle"} or {@code "dist"} — which
     *                           release asset this install upgrades from
     * @param runningTasks       task runs in RUNNING state, which the restart
     *                           at the end of the upgrade interrupts
     * @param activeSubagentRuns subagent runs live in THIS JVM, likewise
     * @param commit             short commit id of the checkout, {@code -dirty} when
     *                           the tree is modified, or null on a packaged install
     */
    public record UpgradePreflight(boolean available, @Nullable String unavailableReason,
                                   String currentVersion, @Nullable String latestVersion,
                                   boolean upgradeAvailable, String installKind,
                                   long runningTasks, int activeSubagentRuns,
                                   @Nullable String commit) {}

    /**
     * GET /api/system/upgrade — what an upgrade would install and what it would
     * interrupt. Performs nothing.
     *
     * @param refresh bypass the cached release check (the "check again" control)
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UpgradePreflight.class)))
    public static void upgradePreflight(Boolean refresh) {
        var unavailable = UpgradeService.unavailableReason();
        var current = UpgradeService.currentVersion();
        // Resolved even when this install cannot upgrade itself: without it the panel
        // can only recite a git-pull/docker instruction, including on a checkout that
        // is already current. The hour-long cache bounds this to one GitHub call/hour.
        var latest = UpgradeService.latestVersion(Boolean.TRUE.equals(refresh));

        renderJSON(GSON.toJson(new UpgradePreflight(
                unavailable == null, unavailable, current, latest,
                UpgradeService.isNewer(latest, current), UpgradeService.installKind(),
                TaskRun.count("status = ?1", TaskRun.Status.RUNNING),
                SubagentRegistry.activeRunIds().size(),
                GitCheckout.describe())));
    }

    /**
     * POST /api/system/upgrade — hand off to the upgrade helper and ack.
     *
     * <p>Returns 202 like {@link #restart()}, but the gap is far wider: the
     * helper downloads and unpacks the release before it stops anything, so
     * this JVM keeps serving for minutes after the ack. The UI tracks
     * {@link #upgradeStatus()} through that window rather than assuming the
     * instance is on its way down.
     *
     * @param version optional release to install (defaults to the newest);
     *                also the way to re-install or step back to an earlier one
     */
    @ChatHidden("replaces the install and restarts; stepping back reopens fixed holes")
    public static void upgrade(String version) {
        // Success render stays OUT of the try — RenderJson is a RuntimeException,
        // so a catch-all around it would swallow its own 202 and answer 500.
        UpgradeService.Plan plan;
        try {
            plan = UpgradeService.requestUpgrade(version);
        } catch (IllegalStateException e) {
            ApiResponses.error(409, ApiResponses.CONFLICT, ApiResponses.messageOf(e));
            return;
        } catch (IllegalArgumentException e) {
            // A refused version never reaches the helper, so reporting it as a launch
            // failure would be untrue and would let any authenticated caller drive
            // ERROR-level log writes with a value they chose (JCLAW-1020).
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, ApiResponses.messageOf(e));
            return;
        } catch (Exception e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR,
                    "Failed to launch the upgrade helper: " + e.getMessage());
            return;
        }

        response.status = 202;
        ApiResponses.ok("currentVersion", plan.currentVersion(),
                "targetVersion", plan.targetVersion(),
                "installKind", plan.installKind());
    }

    /**
     * GET /api/system/upgrade/status — the helper's last reported progress, or
     * 204 when no upgrade has ever run on this install.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UpgradeService.Status.class)))
    public static void upgradeStatus() {
        var status = UpgradeService.status();
        if (status == null) {
            response.status = 204;
            renderText("");
            return;
        }
        renderJSON(GSON.toJson(status));
    }
}
