import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.database.DatabaseHealth;
import services.database.DatabaseService;
import services.database.H2Maintenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The pure parts of the database service (JCLAW-1165): what an id may name, what retention
 * keeps, when the daily schedule fires, what the config gate refuses, and when cleanup is
 * withheld. Everything touching the live directories goes through the test seams.
 */
class DatabaseServiceTest extends UnitTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearSeams() {
        DatabaseService.backupsDirForTest = null;
        DatabaseService.dataDirForTest = null;
    }

    private static void touch(Path dir, String name, Instant modified) throws Exception {
        var p = dir.resolve(name);
        Files.writeString(p, name);
        Files.setLastModifiedTime(p, FileTime.from(modified));
    }

    @Test
    void aBackupIdIsABareZipNameInsideTheBackupsDirectory() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("backups"));
        DatabaseService.backupsDirForTest = dir;
        touch(dir, "jclaw-20260909T120000Z.zip", Instant.now());
        Files.createDirectories(tmp.resolve("elsewhere"));
        touch(tmp.resolve("elsewhere"), "x.zip", Instant.now());
        touch(dir, "notes.txt", Instant.now());

        assertNotNull(DatabaseService.resolveBackup("jclaw-20260909T120000Z.zip"));
        assertNull(DatabaseService.resolveBackup("../elsewhere/x.zip"), "no traversal");
        assertNull(DatabaseService.resolveBackup("elsewhere/x.zip"), "no path segments");
        assertNull(DatabaseService.resolveBackup("notes.txt"), "only zips");
        assertNull(DatabaseService.resolveBackup("missing.zip"));
        assertNull(DatabaseService.resolveBackup(""));
    }

    @Test
    void retentionKeepsTheNewestOfItsOwnAndLeavesUploadsAlone() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("backups"));
        var base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 9; i++) {
            touch(dir, "jclaw-2026090" + i + "T000000Z.zip", base.plusSeconds(i * 86400L));
        }
        touch(dir, "upload-20260801T000000Z.zip", base.minusSeconds(86400L * 30));

        DatabaseService.prune(dir, 7);

        try (var files = Files.list(dir)) {
            var names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(8, names.size(), () -> "kept: " + names);
            assertFalse(names.contains("jclaw-20260900T000000Z.zip"));
            assertFalse(names.contains("jclaw-20260901T000000Z.zip"));
            assertTrue(names.contains("jclaw-20260908T000000Z.zip"));
            assertTrue(names.contains("upload-20260801T000000Z.zip"), "an upload is the operator's, not retention's");
        }
    }

    @Test
    void theDailyScheduleFiresOnceOnOrAfterItsTime() {
        var zone = ZoneId.of("Asia/Kuala_Lumpur");
        var today = LocalDate.of(2026, 9, 9);
        var at = (java.util.function.Function<String, ZonedDateTime>) t -> ZonedDateTime.of(today, java.time.LocalTime.parse(t), zone);

        assertFalse(DatabaseService.isDue("02:30", at.apply("02:29"), null));
        assertTrue(DatabaseService.isDue("02:30", at.apply("02:30"), null));
        assertTrue(DatabaseService.isDue("02:30", at.apply("09:00"), today.minusDays(1)), "a missed minute is caught up later in the day");
        assertFalse(DatabaseService.isDue("02:30", at.apply("09:00"), today), "once a day");
        assertFalse(DatabaseService.isDue(null, at.apply("02:30"), null));
        assertFalse(DatabaseService.isDue("25:00", at.apply("02:30"), null));
    }

    @Test
    void aRestartSeedsTodaysRunFromTheNewestBackupOnDisk() {
        // JCLAW-1195: the marker lives in memory, so a process that comes up after the scheduled
        // time must read the directory before deciding today has not run.
        var zone = ZoneId.of("Asia/Singapore");
        var now = ZonedDateTime.of(2026, 9, 11, 16, 13, 0, 0, zone);
        var backup = (java.util.function.Function<String, DatabaseService.BackupInfo>) stampUtc ->
                new DatabaseService.BackupInfo("jclaw-" + stampUtc + ".zip", 1, Instant.parse(
                        stampUtc.substring(0, 4) + "-" + stampUtc.substring(4, 6) + "-" + stampUtc.substring(6, 11)
                        + ":" + stampUtc.substring(11, 13) + ":" + stampUtc.substring(13, 15) + "Z").toString());

        var ranAt0900 = List.of(backup.apply("20260911T010054Z"));          // 09:00:54 local
        var seeded = DatabaseService.lastRunFromBackups(ranAt0900, "09:00", now);
        assertNotNull(seeded, "a backup written today at the scheduled time counts as today's run");
        assertEquals(now.toLocalDate(), seeded.toLocalDate());
        assertEquals(Instant.parse("2026-09-11T01:00:54Z"), seeded.toInstant(), "and its time is what the panel reports");
        assertNull(DatabaseService.lastRunFromBackups(List.of(backup.apply("20260910T010054Z")), "09:00", now),
                "yesterday's backup does not cover today");
        assertNull(DatabaseService.lastRunFromBackups(List.of(backup.apply("20260911T003000Z")), "09:00", now),
                "a backup before the scheduled time leaves the scheduled run to fire");
        assertNull(DatabaseService.lastRunFromBackups(List.of(), "09:00", now), "no backup: catch up");
        assertNull(DatabaseService.lastRunFromBackups(ranAt0900, null, now), "no schedule: nothing to seed");
        var upload = new DatabaseService.BackupInfo("upload.zip", 1, now.toInstant().toString());
        assertNull(DatabaseService.lastRunFromBackups(List.of(upload), "09:00", now),
                "an upload in the directory is not one of this service's runs");
        var ownWins = DatabaseService.lastRunFromBackups(List.of(upload, ranAt0900.getFirst()), "09:00", now);
        assertNotNull(ownWins, "the newest own backup decides, not the newest file");
        assertEquals(now.toLocalDate(), ownWins.toLocalDate());
    }

    @Test
    void aRestartAfterTodaysBackupDoesNotWriteAnother() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("backups"));
        DatabaseService.backupsDirForTest = dir;
        var fixed = Instant.parse("2026-09-11T12:00:00Z");
        // Written a minute before "now": the same local day in every zone the resolver can pick.
        touch(dir, "jclaw-20260911T115900Z.zip", fixed.minusSeconds(60));
        DatabaseService.resetScheduleMarkerForTest();
        services.ConfigService.set(DatabaseService.KEY_SCHEDULE, "00:00");
        try {
            assertEquals("00:00", DatabaseService.schedule(), "the schedule must be live, or the tick is not due and the test proves nothing");
            utils.AppClock.runWith(java.time.Clock.fixed(fixed, ZoneId.of("UTC")),
                    DatabaseService::runScheduledBackupIfDue);
            assertEquals(1, Files.list(dir).count(), "the restart must not add a backup");
        } finally {
            services.ConfigService.set(DatabaseService.KEY_SCHEDULE, "");
            DatabaseService.resetScheduleMarkerForTest();
        }
    }

    @Test
    void theConfigGateRefusesWhatWouldBeSilentAtRead() {
        assertNull(DatabaseService.rejectionFor(DatabaseService.KEY_RETENTION, "7"));
        assertNotNull(DatabaseService.rejectionFor(DatabaseService.KEY_RETENTION, "0"));
        assertNotNull(DatabaseService.rejectionFor(DatabaseService.KEY_RETENTION, "many"));
        assertNull(DatabaseService.rejectionFor(DatabaseService.KEY_SCHEDULE, "09:00"));
        assertNull(DatabaseService.rejectionFor(DatabaseService.KEY_SCHEDULE, ""), "empty clears the schedule");
        assertNotNull(DatabaseService.rejectionFor(DatabaseService.KEY_SCHEDULE, "9:00"));
        assertNotNull(DatabaseService.rejectionFor(DatabaseService.KEY_SCHEDULE, "24:00"));
        assertNotNull(DatabaseService.rejectionFor(DatabaseService.KEY_DIR, " "));
        assertNull(DatabaseService.rejectionFor("db.backup.unrelated", "x"));
    }

    @Test
    void anUploadIsValidatedBeforeItIsStored() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("backups"));
        DatabaseService.backupsDirForTest = dir;
        var bogus = tmp.resolve("bogus.zip");
        Files.writeString(bogus, "not a zip");
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.storeUpload(bogus));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "a refused upload leaves nothing behind");
        }

        var valid = tmp.resolve("valid.zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(valid))) {
            z.putNextEntry(new ZipEntry(H2Maintenance.DATA_FILE));
            z.write("H:2,block:d4,blockSize:1000".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        var name = DatabaseService.storeUpload(valid);
        assertTrue(name.startsWith("upload-") && name.endsWith(".zip"), name);
        assertNotNull(DatabaseService.resolveBackup(name));
    }

    @Test
    void cleanupIsWithheldUntilTheRepairSucceededAndTheDatabaseIsHealthySince() {
        var healthy = DatabaseHealth.assess(new DatabaseHealth.TraceSummary(0, 0, null), 1L, null);
        var attention = DatabaseHealth.assess(new DatabaseHealth.TraceSummary(1, 1, null), 1L, null);
        var ok = new H2Maintenance.Manifest("20260909T120000Z", true, "all good", "x", null,
                List.of(), List.of(), List.of(), true, null, 0, 0, List.of(), 0);
        var incomplete = new H2Maintenance.Manifest("20260909T120000Z", false, "1 table short", "x", null,
                List.of(), List.of(), List.of(), true, null, 0, 0, List.of(), 0);
        var after = List.of(new DatabaseHealth.CorruptionEvent(Instant.parse("2026-09-09T13:00:00Z"), "File corrupted"));
        var before = List.of(new DatabaseHealth.CorruptionEvent(Instant.parse("2026-09-09T11:00:00Z"), "File corrupted"));

        assertNotNull(DatabaseService.cleanupUnavailableReason(null, healthy, List.of()));
        assertTrue(DatabaseService.cleanupUnavailableReason(incomplete, healthy, List.of()).contains("did not recover everything"));
        assertTrue(DatabaseService.cleanupUnavailableReason(ok, attention, List.of()).contains("not healthy"));
        assertTrue(DatabaseService.cleanupUnavailableReason(ok, healthy, after).contains("since the repair"));
        assertNull(DatabaseService.cleanupUnavailableReason(ok, healthy, before), "an event before the repair does not count");
        assertNull(DatabaseService.cleanupUnavailableReason(ok, healthy, List.of()));
    }

    @Test
    void theManifestStampParsesAsAnInstant() {
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), DatabaseService.stampInstant("20260909T120000Z"));
        assertNull(DatabaseService.stampInstant("yesterday"));
    }
}
