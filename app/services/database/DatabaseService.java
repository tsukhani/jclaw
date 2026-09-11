package services.database;

import com.google.gson.JsonParser;
import org.hibernate.JDBCException;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import play.Play;
import play.db.jpa.JPA;
import services.ConfigService;
import services.EventLogger;
import services.RestartService;
import services.TimezoneResolver;
import utils.AppClock;
import utils.DbPoolStats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The application side of database maintenance (JCLAW-1165): the health strip, online
 * backups with retention, the cleanup gate, and the handoff of restore and repair to
 * {@code jclaw.sh} — both need the database closed, and this JVM is the one holding it open.
 */
public final class DatabaseService {

    public static final String CATEGORY = "database";
    public static final String KEY_DIR = "db.backup.dir";
    public static final String KEY_RETENTION = "db.backup.retention";
    public static final String KEY_SCHEDULE = "db.backup.schedule";
    public static final int DEFAULT_RETENTION = 7;
    /** Written by {@code jclaw.sh} across a restore or repair; mirrors {@code upgrade-status.json}. */
    static final String STATUS_FILE = "logs/database-status.json";
    static final String BACKUP_PREFIX = "jclaw-";
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern BACKUP_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.zip");
    private static final Pattern SCHEDULE = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    /** Test seams: the live directories are the running install's, which no test may touch. */
    public static @Nullable Path dataDirForTest;
    public static @Nullable Path backupsDirForTest;
    /** Test seam: replaces the {@code SELECT 1} probe. */
    public static @Nullable ProbeForTest probeForTest;

    @FunctionalInterface
    public interface ProbeForTest {
        @Nullable String error();
    }

    private static volatile @Nullable LocalDate lastScheduledRun;
    private static volatile @Nullable String lastScheduledError;
    private static volatile @Nullable Instant lastScheduledAt;

    public record BackupInfo(String id, long bytes, String createdAt) {}

    /** The helper's progress across a restore or repair, or null when none has run. */
    public record LastOperation(@Nullable String op, @Nullable String phase, @Nullable String message,
                                @Nullable String startedAt, @Nullable String backup) {}

    public record Status(String verdict, String reason,
                         long dataFileBytes, long traceFileBytes, long preRestoreBytes, long intermediateBytes,
                         long freeBytes, String h2Version,
                         @Nullable Long probeMs, @Nullable DbPoolStats pool,
                         int corruption24h, int corruption7d, @Nullable String firstCorruptionAt,
                         @Nullable String lastBackupAt, @Nullable Long lastBackupAgeSeconds,
                         List<BackupInfo> backups, String backupsDir, int retention, @Nullable String schedule,
                         @Nullable String scheduledBackupAt, @Nullable String scheduledBackupError,
                         H2Maintenance.@Nullable Manifest repair,
                         boolean cleanupAvailable, @Nullable String cleanupUnavailableReason,
                         @Nullable LastOperation lastOperation,
                         boolean maintenanceAvailable, @Nullable String maintenanceUnavailableReason) {}

    private DatabaseService() {}

    // ---- where things are ----

    public static Path dataDir() {
        var override = dataDirForTest;
        return override != null ? override : Play.applicationPath.toPath().resolve("data");
    }

    public static Path backupsDir() {
        var override = backupsDirForTest;
        if (override != null) {
            return override;
        }
        var configured = ConfigService.get(KEY_DIR);
        if (configured != null && !configured.isBlank()) {
            var p = Path.of(configured.trim());
            return p.isAbsolute() ? p : Play.applicationPath.toPath().resolve(p);
        }
        return dataDir().resolve("backups");
    }

    public static int retention() {
        return Math.max(1, ConfigService.getInt(KEY_RETENTION, DEFAULT_RETENTION));
    }

    public static @Nullable String schedule() {
        var s = ConfigService.get(KEY_SCHEDULE);
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- config validation (called from ConfigService.setWithSideEffects) ----

    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        var v = value == null ? "" : value.trim();
        return switch (key) {
            case KEY_RETENTION -> {
                try {
                    yield Integer.parseInt(v) >= 1 ? null : KEY_RETENTION + " must be at least 1.";
                } catch (NumberFormatException _) {
                    yield KEY_RETENTION + " must be a whole number of backups to keep.";
                }
            }
            case KEY_SCHEDULE -> v.isEmpty() || SCHEDULE.matcher(v).matches() ? null
                    : KEY_SCHEDULE + " must be a time of day as HH:mm (24-hour), or empty for no schedule.";
            case KEY_DIR -> v.isEmpty() ? KEY_DIR + " must be a directory path." : null;
            default -> null;
        };
    }

    // ---- backups ----

    /** A backup id is a bare zip file name inside the backups directory — never a path. */
    public static @Nullable Path resolveBackup(String id) {
        if (!BACKUP_ID.matcher(id).matches()) {
            return null;
        }
        var dir = backupsDir().toAbsolutePath().normalize();
        var path = dir.resolve(id).toAbsolutePath().normalize();
        var parent = path.getParent();
        if (parent == null || !parent.equals(dir)) {
            return null;
        }
        return Files.isRegularFile(path) ? path : null;
    }

    public static List<BackupInfo> listBackups() {
        try {
            var out = new ArrayList<BackupInfo>();
            for (var b : H2Maintenance.listBackups(backupsDir())) {
                out.add(new BackupInfo(b.name(), b.bytes(), b.modified().toInstant().toString()));
            }
            return out;
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not list backups in " + backupsDir(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Online backup through the request's own connection: H2's {@code BACKUP TO} needs no
     * downtime. On success the pre-restore copy has served its purpose and goes, and the
     * oldest backups beyond the retention count go with it.
     *
     * @throws SQLException when H2 refuses — an in-memory database is not persistent, for one
     */
    public static BackupInfo backupNow() throws SQLException, IOException {
        var dir = Files.createDirectories(backupsDir());
        var zip = dir.resolve(BACKUP_PREFIX + STAMP.format(AppClock.now()) + ".zip");
        // Through the request's own connection: the Hibernate session hands it out for the
        // duration of doWork, and a JDBC refusal comes back wrapped rather than as SQLException.
        try {
            JPA.em().unwrap(Session.class).doWork(connection -> H2Maintenance.backupOnline(connection, zip));
        } catch (JDBCException e) {
            throw e.getSQLException();
        }
        Files.deleteIfExists(dataDir().resolve(H2Maintenance.PRE_RESTORE_FILE));
        prune(dir, retention());
        EventLogger.info(CATEGORY, "Database backed up to " + zip.getFileName() + " (" + H2Maintenance.human(Files.size(zip)) + ")");
        return new BackupInfo(zip.getFileName().toString(), Files.size(zip), Files.getLastModifiedTime(zip).toInstant().toString());
    }

    /** Keep the newest {@code keep} of this service's own backups; uploads and upgrade copies are not counted. */
    public static void prune(Path dir, int keep) throws IOException {
        var ours = H2Maintenance.listBackups(dir).stream()
                .filter(b -> b.name().startsWith(BACKUP_PREFIX))
                .toList();
        for (var stale : ours.stream().skip(keep).toList()) {
            Files.deleteIfExists(dir.resolve(stale.name()));
        }
    }

    public static boolean deleteBackup(String id) throws IOException {
        var path = resolveBackup(id);
        if (path == null) {
            return false;
        }
        Files.delete(path);
        EventLogger.info(CATEGORY, "Deleted backup " + id);
        return true;
    }

    // ---- the scheduled backup ----

    /** True when {@code schedule} (HH:mm in the operator's zone) has arrived today and has not run today. */
    public static boolean isDue(@Nullable String schedule, ZonedDateTime now, @Nullable LocalDate lastRun) {
        if (schedule == null || !SCHEDULE.matcher(schedule).matches()) {
            return false;
        }
        var at = LocalTime.parse(schedule);
        var today = now.toLocalDate();
        return !today.equals(lastRun) && !now.toLocalTime().isBefore(at);
    }

    /**
     * The day a fresh process should treat as already backed up (JCLAW-1195): today, when the
     * newest of this service's own backups was written today at or after {@code schedule};
     * otherwise null. The in-memory marker dies with the process, so without this every
     * restart after the scheduled time re-ran the backup and pruned the real one.
     */
    public static @Nullable LocalDate lastRunFromBackups(List<BackupInfo> backups, @Nullable String schedule,
                                                         ZonedDateTime now) {
        if (schedule == null || !SCHEDULE.matcher(schedule).matches()) {
            return null;
        }
        var at = LocalTime.parse(schedule);
        return backups.stream()
                .filter(b -> b.id().startsWith(BACKUP_PREFIX))
                .map(b -> ZonedDateTime.ofInstant(Instant.parse(b.createdAt()), now.getZone()))
                .filter(t -> t.toLocalDate().equals(now.toLocalDate()) && !t.toLocalTime().isBefore(at))
                .map(ZonedDateTime::toLocalDate)
                .findFirst()
                .orElse(null);
    }

    /** Forget the in-process schedule marker, as a restart would. */
    public static void resetScheduleMarkerForTest() {
        lastScheduledRun = null;
        lastScheduledAt = null;
    }

    /** Called every minute by {@code DatabaseBackupScheduleJob}; runs the backup when it is due. */
    public static void runScheduledBackupIfDue() {
        var now = ZonedDateTime.ofInstant(AppClock.now(), TimezoneResolver.appZone());
        var schedule = schedule();
        if (!isDue(schedule, now, lastScheduledRun)) {
            return;
        }
        if (lastScheduledRun == null) {
            // First due tick since boot: was today's backup written before this process came up?
            var seeded = lastRunFromBackups(listBackups(), schedule, now);
            if (seeded != null) {
                lastScheduledRun = seeded;
                return;
            }
        }
        lastScheduledRun = now.toLocalDate();
        lastScheduledAt = now.toInstant();
        try {
            var info = backupNow();
            lastScheduledError = null;
            EventLogger.info(CATEGORY, "Scheduled backup wrote " + info.id());
        } catch (SQLException | IOException | RuntimeException e) {
            lastScheduledError = e.getMessage();
            EventLogger.error(CATEGORY, "Scheduled database backup failed: " + e.getMessage());
        }
    }

    // ---- restore, repair, cleanup ----

    public static RestartService.Plan requestRestore(String backupId) throws IOException {
        EventLogger.warn(CATEGORY, "Restoring the database from " + backupId + " on operator request — handing off to jclaw.sh");
        return RestartService.requestMaintenance("restore", List.of(backupId, "--yes"));
    }

    public static RestartService.Plan requestRepair() throws IOException {
        EventLogger.warn(CATEGORY, "Repairing the database on operator request — handing off to jclaw.sh");
        return RestartService.requestMaintenance("repair", List.of("--yes"));
    }

    /** Copy an uploaded backup into the backups directory under a name {@link #resolveBackup} accepts. */
    public static String storeUpload(Path uploaded) throws IOException {
        var check = H2Maintenance.validateBackup(uploaded);
        if (!check.ok()) {
            throw new IllegalArgumentException("Not an H2 backup: " + check.reason());
        }
        var dir = Files.createDirectories(backupsDir());
        var name = "upload-" + STAMP.format(AppClock.now()) + ".zip";
        Files.copy(uploaded, dir.resolve(name));
        return name;
    }

    /**
     * Why the intermediates cannot be cleaned up, or null when they can: the last repair must
     * have succeeded and the verdict must be Healthy now, with no corruption message since the
     * repair and every restored table readable. Until then the damaged file is the only route to
     * a second attempt.
     */
    public static @Nullable String cleanupUnavailableReason(H2Maintenance.@Nullable Manifest manifest,
                                                             DatabaseHealth.Assessment health,
                                                             List<DatabaseHealth.CorruptionEvent> events) {
        if (manifest == null) {
            return "No repair has left files behind.";
        }
        if (!manifest.ok()) {
            return "The last repair did not recover everything (" + manifest.summary() + "), so the damaged file is kept for another attempt.";
        }
        if (health.verdict() != DatabaseHealth.Verdict.HEALTHY) {
            return "The database is not healthy: " + health.reason();
        }
        var since = stampInstant(manifest.stamp());
        if (since != null && DatabaseHealth.anyEventSince(events, since)) {
            return "The trace file has logged a read failure since the repair.";
        }
        for (var t : manifest.tables()) {
            var dot = t.name().indexOf('.');
            var sql = "SELECT 1 FROM \"" + t.name().substring(0, dot) + "\".\"" + t.name().substring(dot + 1) + "\" LIMIT 1";
            try {
                JPA.em().createNativeQuery(sql).getResultList();
            } catch (RuntimeException e) {
                return "Table " + t.name() + " cannot be read: " + firstLine(e.getMessage());
            }
        }
        return null;
    }

    public static H2Maintenance.CleanResult clean() throws IOException {
        var manifest = H2Maintenance.latestManifest(dataDir());
        if (manifest == null) {
            return new H2Maintenance.CleanResult(false, "No repair has left files behind.", List.of(), 0);
        }
        var reason = cleanupUnavailableReason(manifest, assess(), DatabaseHealth.corruptionEvents(dataDir().resolve(H2Maintenance.TRACE_FILE)));
        if (reason != null) {
            return new H2Maintenance.CleanResult(false, reason, List.of(), 0);
        }
        var result = H2Maintenance.clean(dataDir(), manifest, false);
        if (result.ok()) {
            EventLogger.info(CATEGORY, "Removed the repair intermediates: " + result.deleted().size() + " files, "
                    + H2Maintenance.human(result.reclaimedBytes()) + " reclaimed");
        }
        return result;
    }

    public static @Nullable Instant stampInstant(String stamp) {
        try {
            return STAMP.parse(stamp, Instant::from);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    // ---- status ----

    public static DatabaseHealth.Assessment assess() throws IOException {
        var events = DatabaseHealth.corruptionEvents(dataDir().resolve(H2Maintenance.TRACE_FILE));
        var trace = DatabaseHealth.summarize(events, AppClock.now());
        Long probeMs = null;
        String probeError = null;
        var seam = probeForTest;
        if (seam != null) {
            probeError = seam.error();
            probeMs = probeError == null ? 0L : null;
        } else {
            var started = System.nanoTime();
            try {
                JPA.em().createNativeQuery("SELECT 1").getSingleResult();
                probeMs = (System.nanoTime() - started) / 1_000_000;
            } catch (RuntimeException e) {
                probeError = firstLine(e.getMessage());
            }
        }
        return DatabaseHealth.assess(trace, probeMs, probeError);
    }

    public static Status status() throws IOException {
        var data = dataDir();
        var events = DatabaseHealth.corruptionEvents(data.resolve(H2Maintenance.TRACE_FILE));
        var health = assess();
        var manifest = H2Maintenance.latestManifest(data);
        var backups = listBackups();
        var newest = backups.isEmpty() ? null : backups.getFirst();
        Long age = null;
        if (newest != null) {
            age = Duration.between(Instant.parse(newest.createdAt()), AppClock.now()).getSeconds();
        }
        var cleanupReason = cleanupUnavailableReason(manifest, health, events);
        var maintenance = RestartService.unavailableReason();
        var scheduledAt = lastScheduledAt;
        var first = health.trace().firstAt();
        return new Status(health.verdict().name(), health.reason(),
                sizeOf(data.resolve(H2Maintenance.DATA_FILE)), sizeOf(data.resolve(H2Maintenance.TRACE_FILE)),
                sizeOf(data.resolve(H2Maintenance.PRE_RESTORE_FILE)),
                manifest == null ? 0 : manifest.intermediateBytes(),
                freeBytes(data), org.h2.engine.Constants.FULL_VERSION,
                health.probeMs(), DbPoolStats.snapshot().orElse(null),
                health.trace().last24h(), health.trace().last7d(), first == null ? null : first.toString(),
                newest == null ? null : newest.createdAt(), age,
                backups, backupsDir().toString(), retention(), schedule(),
                scheduledAt == null ? null : scheduledAt.toString(), lastScheduledError,
                manifest, cleanupReason == null, cleanupReason,
                lastOperation(),
                maintenance == null, maintenance);
    }

    static long sizeOf(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException _) {
            return 0;
        }
    }

    static long freeBytes(Path dir) {
        try {
            var probe = Files.isDirectory(dir) ? dir : dir.getParent();
            return probe == null ? 0 : Files.getFileStore(probe).getUsableSpace();
        } catch (IOException _) {
            return 0;
        }
    }

    public static @Nullable LastOperation lastOperation() {
        var file = Play.applicationPath.toPath().resolve(STATUS_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return new LastOperation(str(json, "op"), str(json, "phase"), str(json, "message"),
                    str(json, "startedAt"), str(json, "backup"));
        } catch (IOException | RuntimeException _) {
            // A torn read while the helper is mid-write; the next poll gets a whole file.
            return null;
        }
    }

    private static @Nullable String str(com.google.gson.JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static String firstLine(@Nullable String message) {
        if (message == null) {
            return "";
        }
        var nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
