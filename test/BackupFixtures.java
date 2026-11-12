import services.database.H2Maintenance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Backup zips built from real H2 files, for the tests of the write check and the restore (JF-311). */
public final class BackupFixtures {

    private static final int BLOCK = 4096;

    private BackupFixtures() {}

    /**
     * A model of what BACKUP TO's copy holds when writes land inside the file while it streams: H2 reads the file
     * front to back, header first, so the copy's first blocks come from before the writes and the rest from after.
     * Freed space is reused at once and the background writer is off, so the writes land inside the file at the
     * same blocks on every run. H2 opens the result at a newer version than its header names, takes a session's
     * writes, and at the next open falls back to the header's version, dropping those writes and the rows between.
     * The zip is {@code work/torn.zip}; {@code work} must not hold one already.
     */
    public static Path aCopyTakenWhileWritesLanded(Path work) throws Exception {
        var live = Files.createDirectories(work.resolve("live"));
        var before = work.resolve("before.mv.db");
        var after = work.resolve("after.mv.db");
        try (var c = DriverManager.getConnection("jdbc:h2:file:" + live.resolve("jclaw")
                + ";MODE=MYSQL;RETENTION_TIME=0;WRITE_DELAY=0", "", ""); var s = c.createStatement()) {
            s.execute("CREATE TABLE t(id INT PRIMARY KEY, v VARCHAR(4000))");
            s.execute("INSERT INTO t SELECT X, REPEAT('a', 1 + MOD(X * 37, 300) * 2) FROM SYSTEM_RANGE(0, 3999)");
            s.execute("DELETE FROM t WHERE MOD(id, 4) <> 0");
            s.execute("CHECKPOINT SYNC");
            Files.copy(live.resolve(H2Maintenance.DATA_FILE), before);
            for (int r = 0; r < 10; r++) {
                var base = 1_000_000 + r * 10_000;
                s.execute("INSERT INTO t SELECT X + " + base + ", REPEAT('w', 900) FROM SYSTEM_RANGE(1, 150)");
                s.execute("UPDATE t SET v = REPEAT('u', 700) WHERE MOD(id, 7) = " + (r % 7));
                s.execute("DELETE FROM t WHERE id >= " + base + " AND MOD(id, 3) = 0");
                s.execute("CHECKPOINT");
            }
            s.execute("CHECKPOINT SYNC");
            Files.copy(live.resolve(H2Maintenance.DATA_FILE), after);
        }
        var zip = work.resolve("torn.zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(zip));
             var head = Files.newInputStream(before); var rest = Files.newInputStream(after)) {
            z.putNextEntry(new ZipEntry(H2Maintenance.DATA_FILE));
            z.write(head.readNBytes(4 * BLOCK));
            rest.skipNBytes(4 * BLOCK);
            rest.transferTo(z);
            z.closeEntry();
        }
        return zip;
    }

    /** An online backup of a small closed-and-reopened database, which keeps its writes: {@code work/good.zip}. */
    public static Path aGoodBackup(Path work) throws Exception {
        var src = Files.createDirectories(work.resolve("good-src"));
        var zip = work.resolve("good.zip");
        try (var c = DriverManager.getConnection("jdbc:h2:file:" + src.resolve("jclaw") + ";MODE=MYSQL", "", "");
             var s = c.createStatement()) {
            s.execute("CREATE TABLE note(id BIGINT PRIMARY KEY, body VARCHAR(200))");
            s.execute("INSERT INTO note SELECT X, 'note ' || X FROM SYSTEM_RANGE(1, 20)");
            H2Maintenance.backupOnline(c, zip);
        }
        return zip;
    }

    /** The zip's database file, unpacked as it is into {@code dir}. */
    public static Path extractDatabase(Path zip, Path dir) throws Exception {
        Files.createDirectories(dir);
        try (var z = new ZipFile(zip.toFile()); var in = z.getInputStream(z.getEntry(H2Maintenance.DATA_FILE))) {
            Files.copy(in, dir.resolve(H2Maintenance.DATA_FILE));
        }
        return dir;
    }
}
