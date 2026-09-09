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
import services.database.DatabaseService;
import services.database.H2Maintenance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The database endpoints (JCLAW-1165). Every handoff test installs {@code spawnerForTest}
 * first — without it a POST would stop the tree the suite is running from. The test database
 * is in-memory, so the online backup is exercised here only for the refusal H2 answers with;
 * the backup itself is covered against a file database in {@code H2MaintenanceTest}.
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
        DatabaseService.dataDirForTest = null;
        DatabaseService.backupsDirForTest = null;
        DatabaseService.probeForTest = null;
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
    }

    @Test
    void cleanupIsRefusedWhileNoSuccessfulRepairHasLeftFilesBehind() {
        login();
        var response = POST("/api/system/database/repair/clean", "application/json", "{}");
        assertEquals(409, response.status.intValue());
        assertTrue(getContent(response).contains("No repair"), getContent(response));
    }
}
