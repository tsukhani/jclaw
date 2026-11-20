package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.TaskRun;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.GitCheckout;
import services.JavaRuntimePatchCheck;
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

    private static final String READ_STATUS = "read the restart and upgrade status";
    private static final String RESTART = "restart the instance";
    static final String TASK_FIRE_REFUSAL = "A scheduled task can't restart JClaw: the restart cuts the task's own "
            + "run off before it finishes, and a run a restart cuts off does not run again. Ask for the restart in a "
            + "conversation instead.";

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
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the host's run mode and every agent's "
            + "running work")
    public static void restartPreflight() {
        RequestPrincipal.requireOperatorOrMain(READ_STATUS);
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
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-350): stops and relaunches the instance")
    public static void restart() {
        RequestPrincipal.requireOperatorOrMain(RESTART);
        var caller = RequestPrincipal.callingAgent();
        var askingConversationId = RequestPrincipal.callerConversationId();
        // A task fire runs on an unsaved conversation, so main's call from one names none.
        if (caller != null && askingConversationId == null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, TASK_FIRE_REFUSAL);
            return;
        }
        // The success render stays OUT of the try. Play signals results by
        // throwing, and RenderJson is a RuntimeException — inside the try below
        // the catch-all would swallow its own 202 and answer 500 instead.
        RestartService.Plan plan;
        try {
            plan = RestartService.requestRestart(caller == null ? "the operator" : "agent " + caller.name,
                    askingConversationId);
        } catch (IllegalStateException e) {
            // No jclaw.sh, a hand-off already under way, or a test without the stub: the app is
            // still up, so this is a plain refusal rather than a half-completed reboot.
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
                "rebuildExpected", plan.rebuildExpected(),
                "note", plan.restartNote());
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
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-350): how the restart that brought this JVM up "
            + "went; while no JVM is up, the narrator answers only the operator's session")
    public static void restartProgress() {
        RequestPrincipal.requireOperatorOrMain(READ_STATUS);
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
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the install, every agent's running "
            + "work, and a release check that dials out")
    public static void upgradePreflight(Boolean refresh) {
        RequestPrincipal.requireOperatorOrMain(READ_STATUS);
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
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the install's upgrade progress")
    public static void upgradeStatus() {
        RequestPrincipal.requireOperatorOrMain(READ_STATUS);
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
     * @param currentBuild         the running Java's Zulu build (25.38.105), or null for another vendor's
     * @param vendor               {@code java.vendor}, or null when the JVM names none
     * @param distribution         the running Java's vendor as JClaw installs it, {@code "Zulu"} or
     *                             {@code "Temurin"}, or null for another vendor's
     * @param latestVersion        the newest patch of the running major in the running vendor's catalog
     *                             (Azul's for another vendor's Java), or null when it could not be reached
     * @param latestBuild          {@code latestVersion}'s Zulu build, which alone tells a respin of the
     *                             running Java version apart, or null
     * @param releaseType          what kind of release {@code latestVersion} is, in plain words ("a security
     *                             and bug-fix release"), or null when the catalog says nothing JClaw knows
     * @param releaseNotesUrl      the notes the panel links beside {@code latestVersion}: that Temurin
     *                             release's own, or Azul's latest, since Azul links none per build; null
     *                             with no latest version
     * @param releaseNotesLabel    the link's words, which say which of the two it is
     * @param updateAvailable      true when the latest build is newer than the running one, by Java version
     *                             and then by Zulu build
     * @param stagedVersion        a downloaded runtime waiting for the next start, or null
     * @param stagedBuild          its Zulu build, or null
     * @param stagedVendor         its vendor, or null when its manifest names none
     * @param downloading          true while a download runs
     * @param downloadingVersion   what it downloads, or null
     * @param downloadingVendor    whose Java that is, or null
     * @param downloadPercent      how far it is, or null when no download runs
     * @param downloadError        why the last download failed, or null
     * @param lastStart            jclaw.sh's line about what the last start did with a staged runtime,
     *                             or null when the last start had none to install
     * @param lastStartAt          when that line was written (ISO-8601), or null
     * @param checkFailure         why the daily check last failed, to check the catalog or to download a patch,
     *                             or null when nothing failed since a run last succeeded
     * @param checkFailureAt       when it failed (ISO-8601), or null
     * @param rebuildsStartupCache true when the first start on a new runtime rebuilds the AOT cache:
     *                             false on Windows, which has none, and while it is switched off in
     *                             Settings or by {@code JCLAW_AOT=off}
     * @param switches             the switches the POST's {@code vendor} asks for: on the built-in Java, to
     *                             the other vendor's newest of its major, installed at the next start in
     *                             place of it; on a bundle install on a host Java, to either vendor's newest
     *                             Java 25, installed as the first {@code <home>/jre}; empty anywhere else,
     *                             and while a download runs or waits for the next start
     */
    public record JavaRuntimePreflight(String kind, String currentVersion, @Nullable String currentBuild,
                                       @Nullable String vendor, @Nullable String distribution,
                                       @Nullable String latestVersion,
                                       @Nullable String latestBuild, @Nullable String releaseType,
                                       @Nullable String releaseNotesUrl, @Nullable String releaseNotesLabel,
                                       boolean updateAvailable,
                                       @Nullable String stagedVersion, @Nullable String stagedBuild,
                                       @Nullable String stagedVendor, boolean downloading,
                                       @Nullable String downloadingVersion, @Nullable String downloadingVendor,
                                       @Nullable Integer downloadPercent, @Nullable String downloadError,
                                       @Nullable String lastStart, @Nullable String lastStartAt,
                                       @Nullable String checkFailure, @Nullable String checkFailureAt,
                                       boolean rebuildsStartupCache, List<JavaRuntimeSwitch> switches) {}

    /**
     * @param vendor  {@code "Zulu"} or {@code "Temurin"}, the POST's {@code vendor} for it
     * @param version the Java it installs, or null when that vendor's catalog gave none
     * @param refusal why the POST would refuse it, which the panel shows in place of the button, or null
     */
    public record JavaRuntimeSwitch(String vendor, @Nullable String version, @Nullable String refusal) {}

    /**
     * GET /api/system/java-runtime — the running Java, the newest patch of its major, the switches to
     * another vendor's Java and any download staged for the next start. Performs nothing.
     *
     * @param refresh bypass the cached catalog checks (the "Check again" control)
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = JavaRuntimePreflight.class)))
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the host's Java runtime, and a catalog "
            + "check that dials out")
    public static void javaRuntime(Boolean refresh) {
        RequestPrincipal.requireOperatorOrMain(READ_STATUS);
        boolean fresh = Boolean.TRUE.equals(refresh);
        var env = JavaRuntimeService.environment();
        var latest = JavaRuntimeService.latest(fresh);
        var latestVersion = latest == null ? null : latest.version();
        var staged = JavaRuntimeService.staged(env.home());
        var stagedVendor = staged == null ? null : staged.vendor();
        var progress = JavaRuntimeService.CURRENT;
        boolean downloading = progress.active();
        var target = downloading ? progress.target() : null;
        var last = JavaRuntimeService.lastStart(env.app());
        var checkFailure = JavaRuntimePatchCheck.lastFailure(env.app());
        var distribution = env.distribution();
        // The panel's one-second download poll lands here, so it never waits on the other vendor's catalog.
        List<JavaRuntimeSwitch> switches = downloading || staged != null ? List.of()
                : JavaRuntimeService.switches(env, fresh).stream()
                        .map(s -> new JavaRuntimeSwitch(s.vendor().label, s.version(), s.refusal())).toList();

        renderJSON(GSON.toJson(new JavaRuntimePreflight(
                JavaRuntimeService.kind(env).label, env.javaVersion(), env.build(), env.vendor(),
                distribution == null ? null : distribution.label, latestVersion,
                latest == null ? null : latest.build(),
                latest == null ? null : JavaRuntimeService.releaseTypeInWords(latest.releaseType()),
                latest == null ? null : latest.notesUrl(), latest == null ? null : latest.notesLabel(),
                JavaRuntimeService.isNewer(latest, env),
                staged == null ? null : staged.version(), staged == null ? null : staged.build(),
                stagedVendor == null ? null : stagedVendor.label, downloading,
                target == null ? null : target.version(), target == null ? null : target.vendor().label,
                downloading ? progress.currentPercent() : null, progress.lastError(),
                last == null ? null : last.line(), last == null ? null : last.at().toString(),
                checkFailure == null ? null : checkFailure.line(),
                checkFailure == null ? null : checkFailure.at().toString(),
                !env.windows() && RestartService.aotCacheState().enabled()
                        && !"off".equals(System.getenv("JCLAW_AOT")),
                switches)));
    }

    /**
     * POST /api/system/java-runtime {@code {"vendor": "Zulu"|"Temurin"}} — download a Java for the next
     * start to install, and ack. Without a vendor, or with the running one's, it is the newest patch of
     * the built-in Java; with the other, the switch to that vendor's Java. On a bundle install running a
     * Java it did not install, it is the switch to a Java of JClaw's own, Zulu unless the vendor says
     * Temurin. Returns 202 while the download runs in this JVM; the GET reports its progress. 400 for
     * another vendor. 409 when there is nothing this install may download: a source checkout, a
     * container, install.ps1's WSL route, a download already running or staged, no newer patch, a
     * vendor with no JRE for this system, or a switch that would go back to an older Java.
     */
    @AgentAccess(value = OPERATOR_ONLY, reason = "downloads a Java runtime that the next start installs")
    public static void stageJavaRuntime() {
        var body = JsonBodyReader.readJsonBody();
        var field = body == null ? null : body.get("vendor");
        JavaRuntimeService.Vendor vendor = null;
        if (field != null && !field.isJsonNull()) {
            vendor = field.isJsonPrimitive() ? JavaRuntimeService.Vendor.named(field.getAsString()) : null;
            if (vendor == null) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field 'vendor' must be \"Zulu\" or \"Temurin\".");
                return;
            }
        }
        JavaRuntimeService.Release release;
        try {
            release = JavaRuntimeService.requestStage(vendor);
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
            removed = JavaRuntimePatchCheck.cancel(JavaRuntimeService.environment(), JavaRuntimeService.CURRENT);
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
