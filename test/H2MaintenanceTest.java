import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.database.H2Maintenance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * The maintenance engine against real H2 files in a temp directory (JCLAW-1165). The
 * damage fixture is a good file with its last block zeroed: Recover then stages fewer rows
 * than the file held, which is the observable form of the incident this engine exists for.
 * Every test that claims a repair or a cleanup happened checks the directory, not the
 * return value alone.
 */
class H2MaintenanceTest extends UnitTest {

    private static final int BLOCK = 4096;
    private static final int NOTES = 3000;

    @TempDir
    Path tmp;

    /** A file database shaped like the incident's: an ENUM column beside a bulky table. */
    private static void buildDatabase(Path dir, int notes) throws SQLException {
        try (var c = open(dir); var s = c.createStatement()) {
            s.execute("CREATE TABLE prompt(id BIGINT PRIMARY KEY, category ENUM('CODING','WRITING','OTHER') NOT NULL, title VARCHAR(100))");
            s.execute("CREATE TABLE note(id BIGINT PRIMARY KEY, body VARCHAR(20000))");
            var body = "lorem ipsum ".repeat(1500);
            try (var p = c.prepareStatement("INSERT INTO note VALUES(?, ?)")) {
                for (int i = 0; i < notes; i++) {
                    p.setLong(1, i);
                    p.setString(2, i + " " + body);
                    p.executeUpdate();
                }
            }
            s.execute("INSERT INTO prompt VALUES(1,'WRITING','a'),(2,'OTHER','b'),(3,'CODING','c')");
            s.execute("SHUTDOWN COMPACT");
        }
    }

    private static Connection open(Path dir) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:file:" + dir.toAbsolutePath().resolve("jclaw") + ";MODE=MYSQL", "", "");
    }

    private static long count(Path dir, String table) throws SQLException {
        try (var c = open(dir); var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> categories(Path dir) throws SQLException {
        var out = new ArrayList<String>();
        try (var c = open(dir); var s = c.createStatement(); var rs = s.executeQuery("SELECT category FROM prompt ORDER BY id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static void zero(Path file, long fromBlock, long toBlock) throws Exception {
        try (var ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.position(fromBlock * BLOCK);
            ch.write(ByteBuffer.wrap(new byte[(int) ((toBlock - fromBlock) * BLOCK)]));
        }
    }

    private static Path dataFile(Path dir) {
        return dir.resolve(H2Maintenance.DATA_FILE);
    }

    private static List<String> names(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- backup and restore ----

    @Test
    void anOnlineBackupValidatesAndRestoresRowForRow() throws Exception {
        var src = Files.createDirectories(tmp.resolve("src"));
        buildDatabase(src, 50);
        var zip = tmp.resolve("online.zip");
        try (var c = open(src)) {
            H2Maintenance.backupOnline(c, zip);
        }

        var check = H2Maintenance.validateBackup(zip);
        assertTrue(check.ok(), check.reason());
        assertTrue(check.dbBytes() > 0);

        var dst = Files.createDirectories(tmp.resolve("dst"));
        var result = H2Maintenance.restore(zip, dst);
        assertNull(result.preRestoreCopy(), "nothing to displace in an empty directory");
        assertEquals(50, count(dst, "note"));
        assertEquals(List.of("WRITING", "OTHER", "CODING"), categories(dst));
    }

    @Test
    void anOfflineBackupOfAClosedFileRestoresTheSameWay() throws Exception {
        var src = Files.createDirectories(tmp.resolve("src"));
        buildDatabase(src, 20);
        var zip = tmp.resolve("offline.zip");
        H2Maintenance.backupOffline(src, zip);
        assertTrue(H2Maintenance.validateBackup(zip).ok());

        var dst = Files.createDirectories(tmp.resolve("dst"));
        H2Maintenance.restore(zip, dst);
        assertEquals(20, count(dst, "note"));
    }

    @Test
    void restoringOverALiveFileKeepsItAsThePreRestoreCopy() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 10);
        var zip = tmp.resolve("b.zip");
        H2Maintenance.backupOffline(dir, zip);
        // Grow the live file past the backup so the displaced copy is distinguishable.
        try (var c = open(dir); var s = c.createStatement()) {
            s.execute("INSERT INTO note VALUES(999, 'after the backup')");
        }
        assertEquals(11, count(dir, "note"));

        var result = H2Maintenance.restore(zip, dir);
        assertEquals(H2Maintenance.PRE_RESTORE_FILE, result.preRestoreCopy());
        assertTrue(Files.exists(dir.resolve(H2Maintenance.PRE_RESTORE_FILE)));
        assertEquals(10, count(dir, "note"), "the restored file is the backup's, not the displaced one");
        assertFalse(Files.exists(dir.resolve(H2Maintenance.LOCK_FILE)), "a stale lock would block the next boot");
    }

    @Test
    void aFileThatIsNotABackupIsRefusedAndNothingOnDiskChanges() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 5);
        var beforeSha = H2Maintenance.sha256(dataFile(dir));
        var beforeNames = names(dir);

        var text = tmp.resolve("notes.txt");
        Files.writeString(text, "not a zip");
        assertEquals("not a zip archive", H2Maintenance.validateBackup(text).reason());

        var wrongEntry = tmp.resolve("wrong.zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(wrongEntry))) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("hello".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertTrue(H2Maintenance.validateBackup(wrongEntry).reason().contains("no jclaw.mv.db"));

        var fakeDb = tmp.resolve("fake.zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(fakeDb))) {
            z.putNextEntry(new ZipEntry(H2Maintenance.DATA_FILE));
            z.write("PK not an mvstore".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertTrue(H2Maintenance.validateBackup(fakeDb).reason().contains("not an H2 database"));

        for (var bad : List.of(text, wrongEntry, fakeDb)) {
            assertThrows(IllegalArgumentException.class, () -> H2Maintenance.restore(bad, dir));
        }
        assertEquals(beforeNames, names(dir), "a refused restore leaves no staging file behind");
        assertEquals(beforeSha, H2Maintenance.sha256(dataFile(dir)));
    }

    // ---- repair ----

    @Test
    void repairRebuildsWithEnumLabelsAndKeepsTheIntermediatesAside() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 40);

        var m = H2Maintenance.repair(dir, "20260909T120000Z");

        assertTrue(m.ok(), m.summary());
        assertTrue(m.referenceAvailable());
        assertEquals(0, m.unrecoveredRows());
        assertEquals(0, m.scriptErrors());
        assertTrue(m.failures().isEmpty(), () -> "failures: " + m.failures());
        var note = m.tables().stream().filter(t -> t.name().equals("PUBLIC.NOTE")).findFirst().orElseThrow();
        assertEquals(Long.valueOf(40), note.expected());
        assertEquals(40, note.staged());
        assertEquals(40, note.restored());

        // Recover stages ENUM values as ordinals even from an intact file; the rebuilt table holds labels.
        assertEquals(List.of("WRITING", "OTHER", "CODING"), categories(dir));
        assertEquals(List.of("PUBLIC.PROMPT"), m.enumCastTables());
        assertEquals(40, count(dir, "note"));

        // What the repair created is listed with a checksum that matches the file on disk.
        var listed = m.files().stream().map(H2Maintenance.FileEntry::name).toList();
        assertTrue(listed.contains(H2Maintenance.DATA_FILE + ".damaged-20260909T120000Z"), () -> "listed: " + listed);
        assertTrue(listed.contains("jclaw.h2.sql"), () -> "listed: " + listed);
        assertTrue(listed.contains("jclaw.mv.txt"), () -> "listed: " + listed);
        for (var f : m.files()) {
            assertEquals(f.sha256(), H2Maintenance.sha256(dir.resolve(f.name())), f.name());
            assertEquals(f.bytes(), Files.size(dir.resolve(f.name())), f.name());
        }
        assertFalse(listed.contains(H2Maintenance.DATA_FILE), "the rebuilt file is not an intermediate");
        assertTrue(m.intermediateBytes() > 0);

        var manifest = H2Maintenance.latestManifest(dir);
        assertNotNull(manifest);
        assertEquals(m.stamp(), manifest.stamp());
        assertEquals(m.files(), manifest.files());
        assertTrue(Files.exists(dir.resolve("repair-20260909T120000Z.json")));
        assertFalse(Files.exists(dir.resolve(H2Maintenance.LOCK_FILE)));
    }

    @Test
    void aTableShortOfItsReferenceCountIsNamedAndTheRepairIsNotCalledSuccessful() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 40);
        // The reference is what the damaged file reported before the repair; here it claims one row
        // more than Recover can stage, which is exactly what a rolled-back page looks like from outside.
        var reference = new H2Maintenance.Reference(java.util.Map.of("PUBLIC.NOTE", 41L, "PUBLIC.PROMPT", 3L), null);

        var m = H2Maintenance.repair(dir, "20260909T121500Z", reference);

        assertFalse(m.ok());
        var note = m.tables().stream().filter(t -> t.name().equals("PUBLIC.NOTE")).findFirst().orElseThrow();
        assertEquals(Long.valueOf(41), note.expected());
        assertEquals(40, note.restored());
        assertFalse(note.matches());
        assertTrue(m.summary().contains("1 table short: PUBLIC.NOTE 40 of 41"), m.summary());
        var prompt = m.tables().stream().filter(t -> t.name().equals("PUBLIC.PROMPT")).findFirst().orElseThrow();
        assertTrue(prompt.matches());
    }

    @Test
    void referenceCountsReadTheFileAsItIsOrSayWhyNot() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 7);
        var ref = H2Maintenance.referenceCounts(dir);
        assertNull(ref.error());
        assertEquals(Long.valueOf(7), ref.counts().get("PUBLIC.NOTE"));
        assertEquals(Long.valueOf(3), ref.counts().get("PUBLIC.PROMPT"));
        assertFalse(Files.exists(dir.resolve(H2Maintenance.LOCK_FILE)), "a read-only look leaves no lock behind");

        var broken = Files.createDirectories(tmp.resolve("broken"));
        buildDatabase(broken, 7);
        zero(dataFile(broken), 2, 4);
        var none = H2Maintenance.referenceCounts(broken);
        assertNotNull(none.error());
        assertTrue(none.error().startsWith("the damaged file would not open"), none.error());
    }

    @Test
    void repairOfADamagedFileKeepsItsReportConsistentWithTheRebuiltDatabase() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, NOTES);
        var file = dataFile(dir);
        var blocks = Files.size(file) / BLOCK;
        zero(file, blocks - 1, blocks);

        var m = H2Maintenance.repair(dir, "20260909T123000Z");

        // Which rows a zeroed block costs depends on MVStore's layout, so this pins the invariants
        // that must hold whatever it cost: every figure in the report is the rebuilt file's truth,
        // and "ok" is claimed only when every table reached its reference count.
        for (var t : m.tables()) {
            assertEquals(t.restored(), count(dir, t.name().substring("PUBLIC.".length())), t.name());
            assertTrue(t.staged() >= t.restored(), t.name());
        }
        assertEquals(m.tables().stream().allMatch(H2Maintenance.TableResult::matches)
                && m.failures().isEmpty() && m.scriptErrors() == 0, m.ok(), m.summary());
        assertEquals(List.of("WRITING", "OTHER", "CODING"), categories(dir));
        assertTrue(Files.exists(dir.resolve(H2Maintenance.DATA_FILE + ".damaged-20260909T123000Z")));
    }

    @Test
    void aFileRecoverCannotReadIsReportedIncompleteNotRepaired() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 200);
        // The first chunk: with it gone Recover cannot assemble a valid chunk set at all.
        zero(dataFile(dir), 2, 4);

        var m = H2Maintenance.repair(dir, "20260909T130000Z");

        assertFalse(m.ok());
        assertFalse(m.referenceAvailable(), "a file that will not open has no reference to offer");
        assertTrue(m.tables().isEmpty(), () -> "tables: " + m.tables());
        assertTrue(m.scriptErrors() >= 1, "Recover marks the page it could not read");
        assertTrue(m.summary().startsWith("nothing recovered"), m.summary());
        assertTrue(Files.exists(dir.resolve(H2Maintenance.DATA_FILE + ".damaged-20260909T130000Z")),
                "the damaged file is kept aside — it is the only route to another attempt");
    }

    // ---- cleanup ----

    @Test
    void cleanDeletesExactlyTheManifestFilesAndRefusesATamperedOne() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 300);
        // An undamaged file: the cleanup is gated on a successful repair, so make one deterministically.
        var m = H2Maintenance.repair(dir, "20260909T140000Z");
        assertTrue(m.ok(), m.summary());
        assertTrue(m.referenceAvailable());
        assertEquals(300, count(dir, "note"));

        // Bystanders the cleanup must never touch.
        Files.createDirectories(dir.resolve("backups"));
        Files.writeString(dir.resolve("backups").resolve("jclaw-20260901T000000Z.zip"), "zip");
        Files.writeString(dir.resolve("unrelated.txt"), "keep me");
        var liveSha = H2Maintenance.sha256(dataFile(dir));

        // Tamper with one intermediate: the cleanup refuses outright and deletes nothing.
        var dump = dir.resolve("jclaw.mv.txt");
        var original = Files.readAllBytes(dump);
        Files.write(dump, "x".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        var refused = H2Maintenance.clean(dir, m, false);
        assertFalse(refused.ok());
        assertTrue(refused.reason().contains("jclaw.mv.txt"), refused.reason());
        for (var f : m.files()) {
            assertTrue(Files.exists(dir.resolve(f.name())), f.name() + " must survive a refused cleanup");
        }
        assertTrue(Files.exists(dir.resolve("repair-20260909T140000Z.json")));

        Files.write(dump, original);
        var dry = H2Maintenance.clean(dir, m, true);
        assertTrue(dry.ok());
        assertEquals(m.files().size(), dry.deleted().size());
        assertTrue(Files.exists(dump), "a dry run deletes nothing");

        var done = H2Maintenance.clean(dir, m, false);
        assertTrue(done.ok());
        assertEquals(m.intermediateBytes(), done.reclaimedBytes());
        for (var f : m.files()) {
            assertFalse(Files.exists(dir.resolve(f.name())), f.name() + " should be gone");
        }
        assertFalse(Files.exists(dir.resolve("repair-20260909T140000Z.json")), "the manifest goes last");
        assertNull(H2Maintenance.latestManifest(dir));
        assertEquals(liveSha, H2Maintenance.sha256(dataFile(dir)), "the live file is untouched");
        assertTrue(Files.exists(dir.resolve("backups").resolve("jclaw-20260901T000000Z.zip")));
        assertTrue(Files.exists(dir.resolve("unrelated.txt")));
    }

    @Test
    void cleanRefusesTheManifestOfAnIncompleteRepair() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 5);
        var incomplete = new H2Maintenance.Manifest("s", false, "1 table short: PUBLIC.NOTE 0 of 5", "x", null,
                List.of(new H2Maintenance.FileEntry("jclaw.h2.sql", 1, "00")),
                List.of(), List.of(), true, null, 0, 0, List.of(), 0);
        Files.writeString(dir.resolve("jclaw.h2.sql"), "-- kept");
        var result = H2Maintenance.clean(dir, incomplete, false);
        assertFalse(result.ok());
        assertTrue(result.reason().contains("did not recover everything"), result.reason());
        assertTrue(Files.exists(dir.resolve("jclaw.h2.sql")), "nothing is deleted from an incomplete repair");
    }

    @Test
    void cleanRefusesAManifestNamingTheLiveFile() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 5);
        var hostile = new H2Maintenance.Manifest("s", true, "", "x", null,
                List.of(new H2Maintenance.FileEntry(H2Maintenance.DATA_FILE, 1, "00")),
                List.of(), List.of(), true, null, 0, 0, List.of(), 0);
        var result = H2Maintenance.clean(dir, hostile, false);
        assertFalse(result.ok());
        assertTrue(Files.exists(dataFile(dir)));
        var traversal = new H2Maintenance.Manifest("s", true, "", "x", null,
                List.of(new H2Maintenance.FileEntry("../escape", 1, "00")),
                List.of(), List.of(), true, null, 0, 0, List.of(), 0);
        assertFalse(H2Maintenance.clean(dir, traversal, false).ok());
    }

    // ---- parsing ----

    @Test
    void parseColumnsKeepsDeclaredOrderAndFindsEnumLabelLists() {
        var create = """
                CREATE CACHED TABLE "PUBLIC"."MCP_SERVER"(
                    "ID" BIGINT DEFAULT NEXT VALUE FOR "PUBLIC"."SYSTEM_SEQUENCE_1" NOT NULL,
                    "NAME" CHARACTER VARYING(255) NOT NULL,
                    "STATUS" ENUM('CONNECTED', 'DISCONNECTED', 'ERROR') NOT NULL,
                    "TRANSPORT" ENUM('STDIO', 'HTTP'),
                    "META" CHARACTER VARYING(2000) DEFAULT 'a,b',
                    CONSTRAINT "CONSTRAINT_9" PRIMARY KEY("ID")
                )""";
        var columns = H2Maintenance.parseColumns(create);
        assertEquals(List.of("ID", "NAME", "STATUS", "TRANSPORT", "META"),
                columns.stream().map(H2Maintenance.ColumnDef::name).toList());
        assertNull(columns.get(0).enumList());
        assertEquals("'CONNECTED', 'DISCONNECTED', 'ERROR'", columns.get(2).enumList());
        assertEquals("'STDIO', 'HTTP'", columns.get(3).enumList());
        assertNull(columns.get(4).enumList(), "a quoted comma inside a default is not a column break");
    }

    // ---- the command line jclaw.sh drives ----

    @Test
    void theCommandLineReportsAndExitsLikeATool() throws Exception {
        var out = new ByteArrayOutputStream();
        var print = new PrintStream(out, true, StandardCharsets.UTF_8);

        assertEquals(1, H2Maintenance.run(new String[0], print));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("usage:"));

        var dir = Files.createDirectories(tmp.resolve("d"));
        buildDatabase(dir, 3);
        var zip = tmp.resolve("cli.zip");
        out.reset();
        assertEquals(0, H2Maintenance.run(new String[]{"backup", dir.toString(), zip.toString()}, print));
        assertTrue(Files.exists(zip));
        assertEquals(0, H2Maintenance.run(new String[]{"validate", zip.toString()}, print));

        out.reset();
        Files.writeString(tmp.resolve("bad.zip"), "nope");
        assertEquals(1, H2Maintenance.run(new String[]{"validate", tmp.resolve("bad.zip").toString()}, print));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("invalid: not a zip archive"));

        out.reset();
        assertEquals(0, H2Maintenance.run(new String[]{"list", tmp.toString()}, print));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("cli.zip"));

        out.reset();
        assertEquals(0, H2Maintenance.run(new String[]{"status", dir.toString()}, print));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("repair remnants: none"));

        assertEquals(1, H2Maintenance.run(new String[]{"restore"}, print), "a missing argument is a usage error");
    }

    // ---- the standalone guarantee jclaw.sh relies on ----

    @Test
    void theEngineStandsAlone() {
        // jclaw.sh runs this class with the app stopped, on a classpath of the H2 and Gson jars
        // from the framework lib plus the compiled classes — nothing from the app's dependency
        // tree, and nothing from Play. A reference outside that set fails at runtime with the
        // database down, which is the worst moment to learn about it.
        ArchRule rule = classes().that().haveNameMatching("services\\.database\\.H2Maintenance(\\$.*)?")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "org.h2..", "com.google.gson..", "org.jspecify..", "services.database")
                .because("jclaw.sh runs H2Maintenance out of process on the H2 and Gson jars alone (JCLAW-1165)");
        rule.check(ArchitectureTest.APP_CLASSES);
    }
}
