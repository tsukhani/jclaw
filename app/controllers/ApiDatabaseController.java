package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.data.Upload;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import services.RestartService;
import services.database.DatabaseService;
import services.database.H2Maintenance;
import utils.ApiResponses;

import java.io.IOException;
import java.sql.SQLException;

import static utils.GsonHolder.GSON;

/**
 * Database backup, restore, repair and cleanup (JCLAW-1165) — the API behind both the
 * Settings → Database panel and the {@code jclaw.sh backup / db-status} subcommands.
 *
 * <p>Two callers, one gate: the operator's session, or the loopback-plus-secret gate the CLI
 * already uses for loadtest and evals, so {@code jclaw.sh backup} against a running instance
 * takes exactly the button's code path. Restore and repair answer 202 and hand off to
 * {@code jclaw.sh}, because both need the database closed and this JVM is what holds it open.
 */
public class ApiDatabaseController extends Controller {

    @Before
    static void sessionOrLoopbackSecret() {
        if (LoadtestAuthCheck.permits(Http.Request.current())) {
            return;
        }
        AuthCheck.checkAuthentication();
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DatabaseService.Status.class)))
    @Operation(summary = "Database size, free space, health verdict, backups and repair remnants")
    @ChatHidden("operator maintenance plumbing -- names files on the host")
    public static void status() {
        DatabaseService.Status status;
        try {
            status = DatabaseService.status();
        } catch (IOException e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Could not read the database state: " + e.getMessage());
            return;
        }
        renderJSON(GSON.toJson(status));
    }

    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = DatabaseService.BackupInfo.class)))
    @Operation(summary = "Back up the database now, online, into the backups directory")
    @ChatHidden("writes a copy of the whole database to disk")
    public static void backup() {
        DatabaseService.BackupInfo info;
        try {
            info = DatabaseService.backupNow();
        } catch (SQLException e) {
            // H2's own refusal — an in-memory database is not persistent, a backup dir is unwritable.
            ApiResponses.error(409, ApiResponses.CONFLICT, "H2 refused the backup: " + ApiResponses.messageOf(e));
            return;
        } catch (IOException e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Backup failed: " + e.getMessage());
            return;
        }
        response.status = 201;
        renderJSON(GSON.toJson(info));
    }

    @Operation(summary = "Download one backup")
    @ChatHidden("streams the database to the caller")
    public static void download(String id) {
        var path = DatabaseService.resolveBackup(id == null ? "" : id);
        if (path == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No such backup.");
            return;
        }
        renderBinary(path.toFile(), path.getFileName().toString());
    }

    @Operation(summary = "Delete one backup")
    @ChatHidden("deletes a database backup")
    public static void delete(String id) {
        try {
            if (!DatabaseService.deleteBackup(id == null ? "" : id)) {
                ApiResponses.error(404, ApiResponses.NOT_FOUND, "No such backup.");
                return;
            }
        } catch (IOException e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Could not delete the backup: " + e.getMessage());
            return;
        }
        ApiResponses.ok();
    }

    /**
     * POST /api/system/database/restore — from a listed backup ({@code id}) or an uploaded zip
     * ({@code file}, multipart). Validates first so a file that is not an H2 backup is refused
     * with the instance still up and nothing on disk changed; then hands off and acks 202.
     */
    @ChatHidden("replaces the database and restarts the instance -- data loss")
    public static void restore(String id, Upload file) {
        String backupId;
        if (file != null && file.asFile() != null && file.asFile().exists()) {
            try {
                backupId = DatabaseService.storeUpload(file.asFile().toPath());
            } catch (IllegalArgumentException e) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, ApiResponses.messageOf(e));
                return;
            } catch (IOException e) {
                ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Could not store the upload: " + e.getMessage());
                return;
            }
        } else {
            var path = id == null ? null : DatabaseService.resolveBackup(id);
            if (path == null) {
                ApiResponses.error(404, ApiResponses.NOT_FOUND, "No such backup.");
                return;
            }
            var check = H2Maintenance.validateBackup(path);
            if (!check.ok()) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Not an H2 backup: " + check.reason());
                return;
            }
            backupId = id;
        }
        // Success render stays OUT of the try — RenderJson is a RuntimeException, so a
        // catch-all around it would swallow its own 202 and answer 500.
        RestartService.Plan plan;
        try {
            plan = DatabaseService.requestRestore(backupId);
        } catch (IllegalStateException e) {
            ApiResponses.error(409, ApiResponses.CONFLICT, ApiResponses.messageOf(e));
            return;
        } catch (Exception e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Failed to launch the restore helper: " + e.getMessage());
            return;
        }
        response.status = 202;
        ApiResponses.ok("backup", backupId, "mode", plan.mode(), "rebuildExpected", plan.rebuildExpected());
    }

    /** POST /api/system/database/repair — hand off to {@code jclaw.sh repair} and ack 202. */
    @ChatHidden("rebuilds the database and restarts the instance -- rows on unreadable pages are lost")
    public static void repair() {
        RestartService.Plan plan;
        try {
            plan = DatabaseService.requestRepair();
        } catch (IllegalStateException e) {
            ApiResponses.error(409, ApiResponses.CONFLICT, ApiResponses.messageOf(e));
            return;
        } catch (Exception e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Failed to launch the repair helper: " + e.getMessage());
            return;
        }
        response.status = 202;
        ApiResponses.ok("mode", plan.mode(), "rebuildExpected", plan.rebuildExpected());
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = H2Maintenance.CleanResult.class)))
    @Operation(summary = "Delete the files the last successful repair left behind")
    @ChatHidden("deletes the damaged database file kept aside by a repair")
    public static void clean() {
        H2Maintenance.CleanResult result;
        try {
            result = DatabaseService.clean();
        } catch (IOException e) {
            ApiResponses.errorAndLog(e, 500, ApiResponses.INTERNAL_ERROR, "Cleanup failed: " + e.getMessage());
            return;
        }
        if (!result.ok()) {
            ApiResponses.error(409, ApiResponses.CONFLICT, result.reason());
            return;
        }
        renderJSON(GSON.toJson(result));
    }
}
