package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.GitCheckout;
import services.JavaRuntimeService;
import services.RestartService;
import services.SubagentRegistry;
import services.UpgradeService;
import utils.ApiResponses;
import utils.RestartNarrator;

import java.io.IOException;
import java.util.List;

import static controllers.AgentAccess.Level.OPEN;
import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Instance-level maintenance operations — restart, upgrade and the Java runtime
 * (Settings → Maintenance).
 *
 * <p>None is gated on in-flight task or subagent runs. The moment an operator
 * most wants to reboot is usually the moment something is wedged, so a 409 while
 * a stuck task "runs" would lock them out of the one control that clears it.
 * Instead the restart and upgrade preflights report what is in flight and the UI
 * makes the operator confirm against it. The Java runtime download stops nothing,
 * so its only 409 for work in flight is its own download.
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
     * @param aotCacheEnabled    whether the next production start uses the JDK's
     *                           AOT cache (the switch jclaw.sh reads)
     * @param aotCacheStatus     jclaw.sh's line about how the last start used the
     *                           cache, or null before any start wrote one
     * @param aotCacheStatusAt   when that line was written (ISO-8601), or null
     * @param container          true inside a container, where a restart stops the
     *                           container and only its restart policy starts it again
     */
    public record RestartPreflight(boolean available, @Nullable String unavailableReason, String mode,
                                   boolean backendOnly, boolean rebuildExpected,
                                   long runningTasks, int activeSubagentRuns,
                                   boolean aotCacheEnabled, @Nullable String aotCacheStatus,
                                   @Nullable String aotCacheStatusAt, boolean container) {}

    /**
     * GET /api/system/restart — what a restart would do and what it would
     * interrupt. Drives the confirmation dialog; performs nothing.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RestartPreflight.class)))
    @AgentAccess(OPEN)
    public static void restartPreflight() {
        var plan = RestartService.plan();
        var unavailable = RestartService.unavailableReason();
        var aot = RestartService.aotCacheState();
        var aotAt = aot.lastStatusAt();

        renderJSON(GSON.toJson(new RestartPreflight(
                unavailable == null, unavailable, plan.mode(),
                plan.backendOnly(), plan.rebuildExpected(),
                TaskRun.count("status = ?1", TaskRun.Status.RUNNING),
                SubagentRegistry.activeRunIds().size(),
                aot.enabled(), aot.lastStatus(), aotAt == null ? null : aotAt.toString(),
                JavaRuntimeService.environment().container())));
    }

    /**
     * POST /api/system/aot-cache {@code {"enabled": true|false}} — whether the next production
     * start uses the JDK's AOT cache. jclaw.sh reads the switch before it launches the JVM, so
     * the running instance is unchanged until it restarts.
     */
    @AgentAccess(value = OPERATOR_ONLY, reason = "changes how the next start launches the JVM")
    public static void setAotCache() {
        var body = JsonBodyReader.readJsonBody();
        var field = body == null ? null : body.get("enabled");
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isBoolean()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field 'enabled' must be true or false.");
            return;
        }
        boolean enabled = field.getAsBoolean();
        try {
            RestartService.setAotCacheEnabled(enabled);
        } catch (IOException e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR,
                    "Could not save the AOT cache setting: " + e.getMessage());
            return;
        }
        ApiResponses.ok("enabled", enabled);
    }

    /**
     * POST /api/system/restart — hand off to the restart helper and ack.
     *
     * <p>Returns 202, not 200: the reboot has been accepted, not completed.
     * This JVM keeps serving for a couple more seconds so this very response
     * can reach the browser, then the helper stops it.
     */
    @AgentAccess(value = OPERATOR_ONLY, reason = "stops and relaunches the instance -- availability")
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
     * The body of {@code GET /api/system/restart/progress}, whichever process answers: {@code utils.RestartNarrator}
     * writes the same shape by hand while JClaw is down.
     *
     * @param source   {@code jclaw}, or {@code narrator} for the stand-in that serves the gap
     * @param state    {@code running}, {@code done}, {@code failed}, or {@code none} when no restart brought this JVM up
     * @param failure  why the restart failed, or null
     * @param steps    jclaw.sh's phases so far, in order
     * @param services what this JVM is still bringing up; null from the narrator
     */
    public record RestartProgress(String source, String state, @Nullable String failure,
                                  List<RestartNarrator.Step> steps, RestartService.@Nullable Services services) {}

    /**
     * GET /api/system/restart/progress — the phases of the restart that brought this JVM up, and the services it
     * is still connecting. The Restart and Java runtime panels poll it until they reload.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RestartProgress.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "the operator's restart panel; the narrator answers it only to the operator's session")
    public static void restartProgress() {
        var progress = RestartService.progress();
        renderJSON(GSON.toJson(new RestartProgress("jclaw", progress.state(),
                progress.failure().isEmpty() ? null : progress.failure(), progress.steps(), RestartService.services())));
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
     * @param releaseNotes       Markdown notes of {@code latestVersion}, or null
     *                           when it has none or GitHub could not be reached
     */
    public record UpgradePreflight(boolean available, @Nullable String unavailableReason,
                                   String currentVersion, @Nullable String latestVersion,
                                   boolean upgradeAvailable, String installKind,
                                   long runningTasks, int activeSubagentRuns,
                                   @Nullable String commit, @Nullable String releaseNotes) {}

    /**
     * GET /api/system/upgrade — what an upgrade would install and what it would
     * interrupt. Performs nothing.
     *
     * @param refresh bypass the cached release check (the "check again" control)
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = UpgradePreflight.class)))
    @AgentAccess(OPEN)
    public static void upgradePreflight(Boolean refresh) {
        var unavailable = UpgradeService.unavailableReason();
        var current = UpgradeService.currentVersion();
        // Resolved even when this install cannot upgrade itself: without it the panel
        // can only recite a git-pull/docker instruction, including on a checkout that
        // is already current. The hour-long cache bounds this to one GitHub call/hour.
        var release = UpgradeService.latestRelease(Boolean.TRUE.equals(refresh));
        var latest = release == null ? null : release.version();

        renderJSON(GSON.toJson(new UpgradePreflight(
                unavailable == null, unavailable, current, latest,
                UpgradeService.isNewer(latest, current), UpgradeService.installKind(),
                TaskRun.count("status = ?1", TaskRun.Status.RUNNING),
                SubagentRegistry.activeRunIds().size(),
                GitCheckout.describe(),
                release == null ? null : release.notes())));
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
    @AgentAccess(value = OPERATOR_ONLY,
            reason = "replaces the install and restarts; stepping back reopens fixed holes")
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
    @AgentAccess(OPEN)
    public static void upgradeStatus() {
        var status = UpgradeService.status();
        if (status == null) {
            response.status = 204;
            renderText("");
            return;
        }
        renderJSON(GSON.toJson(status));
    }

    /**
     * @param kind                 {@code "built-in"} for the Java the installer put beside this install,
     *                             the only one JClaw updates; {@code "external"} or {@code "container"}
     *                             otherwise
     * @param currentVersion       the running Java, {@code java.version}
     * @param vendor               {@code java.vendor}, or null when the JVM names none
     * @param latestVersion        the newest patch of the running major in Azul's catalog, or null when
     *                             the catalog could not be reached
     * @param updateAvailable      true when {@code latestVersion} is newer than {@code currentVersion}
     * @param stagedVersion        a downloaded runtime waiting for the next start, or null
     * @param downloading          true while a download runs
     * @param downloadPercent      how far it is, or null when no download runs
     * @param downloadError        why the last download failed, or null
     * @param lastStart            jclaw.sh's line about what the last start did with a staged runtime,
     *                             or null when the last start had none to install
     * @param lastStartAt          when that line was written (ISO-8601), or null
     * @param rebuildsStartupCache true when the first start on a new runtime rebuilds the AOT cache:
     *                             false on Windows, which has none, and while it is switched off in
     *                             Settings or by {@code JCLAW_AOT=off}
     */
    public record JavaRuntimePreflight(String kind, String currentVersion, @Nullable String vendor,
                                       @Nullable String latestVersion, boolean updateAvailable,
                                       @Nullable String stagedVersion, boolean downloading,
                                       @Nullable Integer downloadPercent, @Nullable String downloadError,
                                       @Nullable String lastStart, @Nullable String lastStartAt,
                                       boolean rebuildsStartupCache) {}

    /**
     * GET /api/system/java-runtime — the running Java, the newest patch of its major and any download
     * staged for the next start. Performs nothing.
     *
     * @param refresh bypass the cached catalog check (the "Check again" control)
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = JavaRuntimePreflight.class)))
    @AgentAccess(OPEN)
    public static void javaRuntime(Boolean refresh) {
        var env = JavaRuntimeService.environment();
        var latest = JavaRuntimeService.latest(Boolean.TRUE.equals(refresh));
        var latestVersion = latest == null ? null : latest.version();
        var staged = JavaRuntimeService.staged(env.home());
        var progress = JavaRuntimeService.CURRENT;
        boolean downloading = progress.active();
        var last = JavaRuntimeService.lastStart(env.app());

        renderJSON(GSON.toJson(new JavaRuntimePreflight(
                JavaRuntimeService.kind(env).label, env.javaVersion(), env.vendor(), latestVersion,
                JavaRuntimeService.isNewer(latestVersion, env.javaVersion()),
                staged == null ? null : staged.version(),
                downloading, downloading ? progress.currentPercent() : null, progress.lastError(),
                last == null ? null : last.line(), last == null ? null : last.at().toString(),
                !env.windows() && RestartService.aotCacheState().enabled()
                        && !"off".equals(System.getenv("JCLAW_AOT")))));
    }

    /**
     * POST /api/system/java-runtime — download the newest patch for the next start to install, and ack.
     * Returns 202 while the download runs in this JVM; the GET reports its progress. 409 when there is
     * nothing this install may download: another vendor's Java, a container, a download already running
     * or staged, or no newer patch.
     */
    @AgentAccess(value = OPERATOR_ONLY, reason = "downloads a Java runtime that the next start installs")
    public static void stageJavaRuntime() {
        JavaRuntimeService.Release release;
        try {
            release = JavaRuntimeService.requestStage();
        } catch (IllegalStateException e) {
            ApiResponses.error(409, ApiResponses.CONFLICT, ApiResponses.messageOf(e));
            return;
        }
        response.status = 202;
        ApiResponses.ok("version", release.version());
    }

    /**
     * DELETE /api/system/java-runtime — drop the downloaded runtime before a start installs it. 404 when
     * none is waiting, 409 while a download is still running.
     */
    @AgentAccess(value = OPERATOR_ONLY, reason = "drops a Java runtime waiting for the next start")
    public static void cancelJavaRuntime() {
        boolean removed;
        try {
            removed = JavaRuntimeService.cancel();
        } catch (IllegalStateException e) {
            ApiResponses.error(409, ApiResponses.CONFLICT, ApiResponses.messageOf(e));
            return;
        } catch (IOException e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR,
                    "Could not remove the downloaded Java runtime: " + e.getMessage());
            return;
        }
        if (!removed) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No Java runtime is waiting for the next restart.");
            return;
        }
        ApiResponses.ok();
    }
}
