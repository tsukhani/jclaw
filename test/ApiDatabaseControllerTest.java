import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.RestartService;
import services.database.CredentialCopies;
import services.database.DatabaseService;
import services.database.DatabaseSize;
import services.database.H2Maintenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The database endpoints (JCLAW-1165). Every handoff test installs {@code spawnerForTest}
 * first — without it a POST would stop the tree the suite is running from. The test database
 * is in-memory, so H2's online copy is exercised here only for the refusal it answers with;
 * the copy itself is covered against a file database in {@code H2MaintenanceTest}, and the
 * route's handling of what the copy's write check found through {@code setOnlineCopyForTest}.
 */
class ApiDatabaseControllerTest extends FunctionalTest {

    @TempDir
    Path tmp;

    private final ArrayList<RestartService.Plan> spawned = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        spawned.clear();
        RestartService.spawnerForTest = spawned::add;
        DatabaseService.dataDirForTest = Files.createDirectories(tmp.resolve("data"));
        DatabaseService.backupsDirForTest = Files.createDirectories(tmp.resolve("backups"));
        DatabaseService.probeForTest = () -> null;
    }

    @AfterEach
    void clearSeams() {
        RestartService.spawnerForTest = null;
        RestartService.resetHandOffForTest();
        DatabaseService.dataDirForTest = null;
        DatabaseService.backupsDirForTest = null;
        DatabaseService.probeForTest = null;
        DatabaseSize.fileStatsForTest = null;
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Http.Request loopbackWithSecret() {
        var req = newRequest();
        req.remoteAddress = "127.0.0.1";
        if (req.headers == null) {
            req.headers = new HashMap<>();
        }
        var secret = Play.configuration.getProperty("application.secret");
        req.headers.put("x-loadtest-auth", new Http.Header("x-loadtest-auth", secret));
        return req;
    }

    /** A zip that passes {@link H2Maintenance#validateBackup}: the right entry with an MVStore header. */
    private Path seedBackup(String name) throws Exception {
        var zip = DatabaseService.backupsDirForTest.resolve(name);
        try (var z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry(H2Maintenance.DATA_FILE));
            z.write("H:2,block:d4,blockSize:1000,chunk:16".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return zip;
    }

    // --- gate ---

    @Test
    void everyRouteRequiresASessionOrTheLoopbackSecret() {
        assertEquals(401, GET("/api/system/database").status.intValue());
        assertEquals(401, POST("/api/system/database/backups", "application/json", "{}").status.intValue());
        assertEquals(401, POST("/api/system/database/repair", "application/json", "{}").status.intValue());
        assertEquals(0, spawned.size());

        var req = newRequest();
        req.remoteAddress = "127.0.0.1";
        assertEquals(401, GET(req, "/api/system/database").status.intValue(), "loopback alone is not enough");
    }

    @Test
    void theLoopbackSecretOpensTheSameDoorAsTheSession() {
        var response = GET(loopbackWithSecret(), "/api/system/database");
        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("HEALTHY", json.get("verdict").getAsString());
    }

    // --- status ---

    @Test
    void statusCarriesTheStripTheBackupsAndTheCleanupGate() throws Exception {
        login();
        seedBackup("jclaw-20260909T120000Z.zip");
        Files.writeString(DatabaseService.dataDirForTest.resolve(H2Maintenance.DATA_FILE), "H:2,x");

        var json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals("HEALTHY", json.get("verdict").getAsString());
        assertTrue(json.get("reason").getAsString().contains("no read failures"));
        assertEquals(5, json.get("dataFileBytes").getAsLong());
        assertTrue(json.get("freeBytes").getAsLong() > 0);
        assertTrue(json.get("h2Version").getAsString().startsWith("2."));
        assertEquals(1, json.getAsJsonArray("backups").size());
        assertEquals("jclaw-20260909T120000Z.zip", json.getAsJsonArray("backups").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(json.get("lastBackupAgeSeconds").getAsLong() >= 0);
        assertEquals(DatabaseService.DEFAULT_RETENTION, json.get("retention").getAsInt());
        assertFalse(json.get("cleanupAvailable").getAsBoolean());
        assertTrue(json.get("cleanupUnavailableReason").getAsString().contains("No repair"));
        assertTrue(json.get("maintenanceAvailable").getAsBoolean());
    }

    @Test
    void statusSaysWhatACompactionWouldFreeAndFlagsItOnlyPastTheShareAndTheFloor() {
        login();
        DatabaseSize.fileStatsForTest = () -> new DatabaseSize.FileStats(556_109_824, 95, 78);
        var json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals(144_032_445, json.get("reclaimableBytes").getAsLong());
        assertEquals(DatabaseSize.DEFAULT_FLAG_AT_PERCENT, json.get("compactionFlagAtPercent").getAsInt());
        assertEquals(DatabaseSize.DEFAULT_FLAG_AT_MB, json.get("compactionFlagAtMb").getAsInt());
        assertFalse(json.get("compactionFlagged").getAsBoolean(), "26% unused is under half the file");

        DatabaseSize.fileStatsForTest = () -> new DatabaseSize.FileStats(1_746_000_000, 100, 21);
        json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals(1_379_340_000, json.get("reclaimableBytes").getAsLong());
        assertTrue(json.get("compactionFlagged").getAsBoolean(), "79% unused, and over 100 MB");
    }

    @Test
    void theShareAndFloorSetThroughConfigAreTheOnesTheStatusFlagsAt() {
        login();
        DatabaseSize.fileStatsForTest = () -> new DatabaseSize.FileStats(556_109_824, 95, 78);
        assertIsOk(POST("/api/config", "application/json", """
                {"key": "db.compaction.flagAtPercent", "value": "25"}
                """));
        var json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals(25, json.get("compactionFlagAtPercent").getAsInt());
        assertTrue(json.get("compactionFlagged").getAsBoolean(), "26% unused is over 25%, and 137 MB over 100 MB");

        assertIsOk(POST("/api/config", "application/json", """
                {"key": "db.compaction.flagAtMb", "value": "200"}
                """));
        json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals(200, json.get("compactionFlagAtMb").getAsInt());
        assertFalse(json.get("compactionFlagged").getAsBoolean(), "137 MB is under the 200 MB floor");

        assertEquals(403, POST("/api/config", "application/json", """
                {"key": "db.compaction.flagAtPercent", "value": "101"}
                """).status.intValue());
        json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals(25, json.get("compactionFlagAtPercent").getAsInt(), "a refused write stores nothing");
    }

    @Test
    void aDatabaseThatReportsNoFileFiguresStillAnswersTheStatus() {
        login();
        DatabaseSize.fileStatsForTest = () -> null;
        var response = GET("/api/system/database");
        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertTrue(json.get("reclaimableBytes") == null || json.get("reclaimableBytes").isJsonNull());
        assertFalse(json.get("compactionFlagged").getAsBoolean());
    }

    @Test
    void theInMemoryTestDatabaseItselfReportsNoFileFigures() {
        login();
        var json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertTrue(json.get("reclaimableBytes") == null || json.get("reclaimableBytes").isJsonNull());
    }

    @Test
    void growthIsEmptyBeforeTheFirstSample() {
        login();
        var json = JsonParser.parseString(getContent(GET("/api/system/database/growth"))).getAsJsonObject();
        assertTrue(json.get("sampledOn") == null || json.get("sampledOn").isJsonNull());
        assertEquals(0, json.getAsJsonArray("tables").size());
        assertEquals(0, json.getAsJsonArray("fileTrend").size());
    }

    @Test
    void growthComparesTheNewestSampleWithTheDayBefore() {
        login();
        var today = java.time.LocalDate.parse("2026-11-17");
        commitInFreshTx(() -> {
            seedSample(today.minusDays(1), 550_000_000L, 420_000_000L, 250_000_000L, 74_000L);
            seedSample(today, 564_826_112L, 432_430_871L, 259_326_923L, 75_226L);
            return null;
        });

        var json = JsonParser.parseString(getContent(GET("/api/system/database/growth"))).getAsJsonObject();
        assertEquals("2026-11-17", json.get("sampledOn").getAsString());
        assertEquals("2026-11-16", json.get("historySince").getAsString());
        assertEquals(564_826_112L, json.getAsJsonObject("file").get("fileBytes").getAsLong());
        assertEquals(432_430_871L - 259_326_923L, json.get("otherLiveBytes").getAsLong());
        var message = json.getAsJsonArray("tables").get(0).getAsJsonObject();
        assertEquals("MESSAGE", message.get("name").getAsString());
        assertEquals(259_326_923L, message.get("bytes").getAsLong());
        assertEquals(9_326_923L, message.getAsJsonObject("day").get("bytes").getAsLong());
        assertEquals(1_226L, message.getAsJsonObject("day").get("rows").getAsLong());
        assertTrue(message.get("week") == null || message.get("week").isJsonNull());
        assertEquals(2, message.getAsJsonArray("trend").size());
        assertEquals("2026-11-16", json.getAsJsonArray("fileTrend").get(0).getAsJsonObject().get("day").getAsString());
    }

    private static void seedSample(java.time.LocalDate day, long fileBytes, long liveBytes, long messageBytes,
                                   long messageRows) {
        var file = new models.DatabaseFileSample();
        file.sampledOn = day;
        file.takenAt = day.atTime(1, 0).toInstant(java.time.ZoneOffset.UTC);
        file.fileBytes = fileBytes;
        file.liveBytes = liveBytes;
        file.reclaimableBytes = fileBytes - liveBytes;
        file.save();
        var table = new models.DatabaseTableSample();
        table.sampledOn = day;
        table.tableName = "MESSAGE";
        table.bytes = messageBytes;
        table.rowCount = messageRows;
        table.save();
    }

    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) {
            throw new IllegalStateException(err.get());
        }
        return ref.get();
    }

    @Test
    void aFailedProbeReadsCritical() {
        login();
        DatabaseService.probeForTest = () -> "The database has been closed";
        var json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        assertEquals("CRITICAL", json.get("verdict").getAsString());
        assertTrue(json.get("reason").getAsString().contains("Repair"));
    }

    // --- backup ---

    @Test
    void anOnlineBackupTheDatabaseRefusesIsAConflictNotACrash() {
        login();
        // The suite's database is in-memory, which BACKUP TO refuses: the refusal must reach
        // the operator as H2's reason, not as a 500 with a stack trace.
        var response = POST("/api/system/database/backups", "application/json", "{}");
        assertEquals(409, response.status.intValue());
        assertTrue(getContent(response).contains("H2 refused the backup"), getContent(response));
    }

    /** POST a backup whose online copies are {@code copies}, in order, and return the response's JSON. */
    private JsonObject backUpWith(Path... copies) {
        var queue = new ArrayDeque<>(List.of(copies));
        DatabaseService.setOnlineCopyForTest(zip -> Files.copy(queue.remove(), zip));
        try {
            var response = POST("/api/system/database/backups", "application/json", "{}");
            assertEquals(201, response.status.intValue(), getContent(response));
            return JsonParser.parseString(getContent(response)).getAsJsonObject();
        } finally {
            DatabaseService.setOnlineCopyForTest(null);
        }
    }

    private Map<String, String> listedChecks() {
        var checks = new HashMap<String, String>();
        JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject().getAsJsonArray("backups")
                .forEach(b -> checks.put(b.getAsJsonObject().get("id").getAsString(),
                        b.getAsJsonObject().get("check").isJsonNull() ? "none" : b.getAsJsonObject().get("check").getAsString()));
        return checks;
    }

    @Test
    void aBackupThatPassesItsWriteCheckSaysSoAndIsListedUnmarked() throws Exception {
        login();
        var json = backUpWith(BackupFixtures.aGoodBackup(Files.createDirectories(tmp.resolve("fx"))));

        assertEquals("PASSED", json.get("check").getAsString());
        assertEquals("a copy of it kept a test write across a reopen", json.get("checkDetail").getAsString());
        assertEquals(Map.of(json.get("id").getAsString(), "none"), listedChecks(), "a pass leaves no mark");
    }

    @Test
    void aBackupThatFailsItsWriteCheckTwiceIsStillWrittenAndListedAsFailed() throws Exception {
        login();
        var torn = BackupFixtures.aCopyTakenWhileWritesLanded(Files.createDirectories(tmp.resolve("fx")));
        var json = backUpWith(torn, torn);

        assertEquals("FAILED", json.get("check").getAsString());
        assertEquals("a copy of it reopened at an older version, without the write made to it, on the first copy and"
                + " again on a second", json.get("checkDetail").getAsString());
        assertTrue(json.get("id").getAsString().endsWith(H2Maintenance.CHECK_FAILED_SUFFIX), json.get("id").getAsString());
        assertEquals(Map.of(json.get("id").getAsString(), "FAILED"), listedChecks(), "the failure is read back from the name");
    }

    @Test
    void downloadAndDeleteAddressABackupByIdOnly() throws Exception {
        login();
        var zip = seedBackup("jclaw-20260909T120000Z.zip");
        var bytes = Files.readAllBytes(zip);

        var download = GET("/api/system/database/backups/jclaw-20260909T120000Z.zip/download");
        assertIsOk(download);
        // renderBinary streams the file through response.direct; nothing lands in out.
        assertTrue(download.direct instanceof java.io.File, "download must stream the file");
        assertArrayEquals(bytes, Files.readAllBytes(((java.io.File) download.direct).toPath()));

        assertEquals(404, GET("/api/system/database/backups/..%2Fdata%2Fjclaw.mv.db/download").status.intValue());
        assertEquals(404, DELETE("/api/system/database/backups/nope.zip").status.intValue());

        assertIsOk(DELETE("/api/system/database/backups/jclaw-20260909T120000Z.zip"));
        assertFalse(Files.exists(zip));
    }

    // --- restore ---

    @Test
    void restoreRefusesWhatIsNotABackupAndSpawnsNothing() throws Exception {
        login();
        Files.writeString(DatabaseService.backupsDirForTest.resolve("text.zip"), "not a zip");

        assertEquals(404, POST("/api/system/database/restore", "application/json", "{}").status.intValue());
        assertEquals(404, POST("/api/system/database/restore?id=missing.zip", "application/json", "{}").status.intValue());
        var bogus = POST("/api/system/database/restore?id=text.zip", "application/json", "{}");
        assertEquals(400, bogus.status.intValue());
        assertTrue(getContent(bogus).contains("Not an H2 backup"), getContent(bogus));
        assertEquals(0, spawned.size());
        assertTrue(Files.exists(DatabaseService.backupsDirForTest.resolve("text.zip")), "nothing on disk changed");
    }

    @Test
    void restoreHandsOffToJclawShWithTheBackupId() throws Exception {
        login();
        seedBackup("jclaw-20260909T120000Z.zip");

        var response = POST("/api/system/database/restore?id=jclaw-20260909T120000Z.zip", "application/json", "{}");
        assertEquals(202, response.status.intValue());
        assertTrue(getContent(response).contains("jclaw-20260909T120000Z.zip"));
        assertEquals(1, spawned.size());
        var command = spawned.getFirst().command();
        assertTrue(command.contains("restore"), () -> "command: " + command);
        assertTrue(command.contains("jclaw-20260909T120000Z.zip"), () -> "command: " + command);
        assertTrue(command.contains("--yes"), () -> "command: " + command);
        assertTrue(command.getFirst().endsWith("jclaw.sh"), () -> "command: " + command);
        assertEquals(List.of("--backend-port", Play.configuration.getProperty("http.port")), command.subList(1, 3));
    }

    // --- repair and cleanup ---

    @Test
    void repairHandsOffToJclawSh() {
        login();
        var response = POST("/api/system/database/repair", "application/json", "{}");
        assertEquals(202, response.status.intValue());
        assertEquals(1, spawned.size());
        var command = spawned.getFirst().command();
        assertTrue(command.contains("repair"), () -> "command: " + command);
        assertTrue(command.contains("--yes"), () -> "command: " + command);
        assertEquals(List.of("--backend-port", Play.configuration.getProperty("http.port")), command.subList(1, 3));
    }

    @Test
    void compactHandsOffToJclawSh() {
        login();
        var response = POST("/api/system/database/compact", "application/json", "{}");
        assertEquals(202, response.status.intValue());
        assertEquals(1, spawned.size());
        var command = spawned.getFirst().command();
        assertTrue(command.contains("compact") && command.contains("--yes"), () -> "command: " + command);
        assertEquals(List.of("--backend-port", Play.configuration.getProperty("http.port")), command.subList(1, 3));
    }

    // --- the credentials' old copies (JCLAW-1455) ---

    @Test
    void statusMarksWhatHoldsCredentialPlaintextAndOffersToDeleteIt() throws Exception {
        login();
        CredentialCopies.NONE.afterPass(3, 0, Instant.now().minusSeconds(3600)).write(DatabaseService.dataDirForTest);
        seedBackup("jclaw-20261016T000000Z.zip");
        // jclaw.sh upgrade writes its copies to data/backups, which db.backup.dir (here elsewhere) does not move.
        var upgradeCopy = Files.createDirectories(DatabaseService.dataDirForTest.resolve("backups"))
                .resolve("jclaw-0.19.40-20261015T000000Z.mv.db");
        Files.writeString(upgradeCopy, "H:2,x");

        var json = JsonParser.parseString(getContent(GET("/api/system/database"))).getAsJsonObject();
        var credentials = json.getAsJsonObject("credentials");
        assertEquals("PLAINTEXT", credentials.get("due").getAsString(), json.toString());
        assertTrue(json.getAsJsonArray("backups").get(0).getAsJsonObject().get("unencrypted").getAsBoolean(),
                "a backup taken while the file still held the old plaintext holds it too");
        var copies = json.getAsJsonArray("upgradeCopies");
        assertEquals(1, copies.size());
        assertTrue(copies.get(0).getAsJsonObject().get("unencrypted").getAsBoolean());

        assertEquals(400, POST("/api/system/database/restore?id=jclaw-0.19.40-20261015T000000Z.mv.db",
                "application/json", "{}").status.intValue(), "a raw copy is no backup a restore takes");
        assertEquals(0, spawned.size());
        assertIsOk(DELETE("/api/system/database/backups/jclaw-0.19.40-20261015T000000Z.mv.db"));
        assertFalse(Files.exists(upgradeCopy), "the upgrade's raw copy is deleted by the same route");
    }

    @Test
    void cleanupIsRefusedWhileNoSuccessfulRepairHasLeftFilesBehind() {
        login();
        var response = POST("/api/system/database/repair/clean", "application/json", "{}");
        assertEquals(409, response.status.intValue());
        assertTrue(getContent(response).contains("No repair"), getContent(response));
    }
}
