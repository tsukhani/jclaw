package services.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.h2.mvstore.MVStore;
import org.h2.tools.Backup;
import org.h2.tools.Recover;
import org.h2.util.ScriptReader;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The H2 file-database maintenance engine (JCLAW-1165): backup, restore, repair and the
 * cleanup after a repair. One implementation behind two doors — {@code DatabaseService}
 * calls it in-process, and {@code jclaw.sh} runs {@link #main} with the application
 * stopped, which is why this class depends on nothing but the JDK, H2 and Gson: those
 * three are what {@code jclaw.sh} can put on a classpath without the app's dependency
 * tree ({@code H2MaintenanceTest.theEngineStandsAlone} pins it).
 *
 * <p>Nothing here reads the clock. Every operation that names a file after a moment takes
 * the {@code stamp} from its caller, so the engine is deterministic and the app side keeps
 * to {@code AppClock}.
 */
public final class H2Maintenance {

    public static final String DB_NAME = "jclaw";
    public static final String DATA_FILE = DB_NAME + ".mv.db";
    public static final String TRACE_FILE = DB_NAME + ".trace.db";
    public static final String LOCK_FILE = DB_NAME + ".lock.db";
    /** The file a restore displaces, kept until the next successful backup. */
    public static final String PRE_RESTORE_FILE = DATA_FILE + ".pre-restore";
    /** The memory graph's directory under {@code data/}, and its prefix inside a backup zip. */
    public static final String GRAPH_DIR = "memory-graph";
    /** The graph a restore displaces, kept until the next successful backup. */
    public static final String GRAPH_PRE_RESTORE_DIR = GRAPH_DIR + ".pre-restore";
    static final String GRAPH_RESTORING_DIR = GRAPH_DIR + ".restoring";
    private static final Pattern AGENT_DIR = Pattern.compile("\\d+");
    static final String MANIFEST_PREFIX = "repair-";
    static final String MANIFEST_SUFFIX = ".json";
    /** Every MVStore file starts with this; the check that a zip entry is a database. */
    static final String MVSTORE_MAGIC = "H:2,";
    /** The app's connection mode; a rebuild in another mode would store the same DDL differently. */
    static final String MODE = "MODE=MYSQL";
    /** Recover's marker for a page it could not read; counted, never parsed further. */
    static final String RECOVER_ERROR_MARKER = "// error:";
    /** The directory under {@code data/} a write check unpacks its throwaway copy into. */
    static final String WRITE_CHECK_DIR = DB_NAME + "-write-check";
    /** The table a write check writes, in its throwaway copy only. */
    static final String WRITE_CHECK_TABLE = "JCLAW_WRITE_CHECK";
    /** The name ending of a backup that failed the write check, so every listing of it says so. */
    public static final String CHECK_FAILED_SUFFIX = ".check-failed.zip";
    /** A restore's two files beside the data file: the archive's extract, then its rewrite. */
    static final String RESTORING_DB = DB_NAME + "-restoring";
    static final String RESTORED_DB = DB_NAME + "-restored";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    // Recover writes the rows of "SCHEMA"."TABLE" into a positional VARCHAR staging table
    // O_n(C0, C1, ...) and copies them across at the end; the copy is what the ENUM cast rewrites.
    private static final Pattern COPY_STATEMENT = Pattern.compile(
            "(?is)^INSERT INTO (\"?)(\\w+)\\1\\.(\"?)(\\w+)\\3 SELECT \\* FROM (O_\\d+)$");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE (?:CACHED |MEMORY )?TABLE (?:IF NOT EXISTS )?(\"?)(\\w+)\\1\\.(\"?)(\\w+)\\3\\s*\\(");
    private static final Pattern STAGING_INSERT = Pattern.compile("(?is)^INSERT INTO (O_\\d+) VALUES");
    private static final Pattern ENUM_TYPE = Pattern.compile("(?i)\\bENUM\\s*\\(([^)]*)\\)");
    private static final Pattern CONSTRAINT_CLAUSE = Pattern.compile(
            "(?i)^(CONSTRAINT|PRIMARY|UNIQUE|FOREIGN|CHECK|INDEX|KEY)\\b");

    private H2Maintenance() {}

    // ---- results ----

    public record BackupCheck(boolean ok, String reason, long dbBytes) {}

    public enum Outcome { PASSED, FAILED, NOT_RUN }

    /** @param detail what the check saw, in words */
    public record WriteCheck(Outcome outcome, String detail) {}

    /**
     * @param restoredBytes  the rewritten data file now in place
     * @param backupBytes    the database file as the archive held it
     * @param tables         tables whose row counts the rewrite matched
     * @param preRestoreCopy the displaced file's new name, or null when there was none
     */
    public record RestoreResult(long restoredBytes, long backupBytes, int tables, @Nullable String preRestoreCopy) {}

    public record FileEntry(String name, long bytes, String sha256) {}

    /**
     * @param expected the row count read from the damaged file before the repair, or null when
     *                 it would not open — then {@code staged} is the only yardstick there is
     */
    public record TableResult(String name, @Nullable Long expected, long staged, long restored) {
        public boolean matches() {
            return expected != null ? restored == expected : staged == restored;
        }
    }

    public record Failure(String statement, String error) {}

    /**
     * What a repair created and what it found. Written as {@code data/repair-<stamp>.json};
     * the cleanup deletes exactly {@link #files()} and then the manifest itself.
     *
     * @param ok                 every table copied, every count matched, no page Recover could not read
     * @param referenceAvailable the damaged file opened read-only before the repair, so each table's
     *                           {@code expected} is a real count rather than whatever Recover surfaced.
     *                           When false, a table whose pages MVStore had already rolled back reads as
     *                           empty in the script — Recover writes no marker for it — and this report
     *                           cannot see the loss
     * @param referenceError     why no reference was available, or null
     * @param unrecoveredRows    staging rows that failed to insert — rows Recover surfaced but the rebuild refused
     * @param scriptErrors       pages Recover marked unreadable; their rows never reached the script at all
     */
    public record Manifest(String stamp, boolean ok, String summary,
                           String damagedFile, @Nullable String damagedTrace,
                           List<FileEntry> files, List<TableResult> tables, List<String> enumCastTables,
                           boolean referenceAvailable, @Nullable String referenceError,
                           long unrecoveredRows, int scriptErrors, List<Failure> failures,
                           long restoredBytes) {
        public long intermediateBytes() {
            return files.stream().mapToLong(FileEntry::bytes).sum();
        }
    }

    public record CleanResult(boolean ok, String reason, List<FileEntry> deleted, long reclaimedBytes) {}

    public record BackupFile(String name, long bytes, FileTime modified) {}

    // ---- backup ----

    /** Online backup through an open connection: H2's {@code BACKUP TO}, which needs no downtime. */
    public static void backupOnline(Connection connection, Path zip) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("BACKUP TO '" + zip.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }

    /** Backup of a closed database: the same zip {@code BACKUP TO} writes, from the file. */
    public static void backupOffline(Path dataDir, Path zip) throws SQLException, IOException {
        Backup.execute(zip.toAbsolutePath().toString(), dataDir.toAbsolutePath().toString(), DB_NAME, true);
        appendGraph(zip, dataDir);
    }

    /**
     * Add {@code dataDir}'s memory graph to {@code zip} as {@code memory-graph/<agentId>/<file>}
     * entries in sorted order, rewriting the zip through a sibling and an atomic move. Only
     * all-digit agent directories are taken, so a swap's {@code .staging} or {@code .previous}
     * never reaches a backup. A missing graph leaves the zip as it was.
     */
    public static void appendGraph(Path zip, Path dataDir) throws IOException {
        var files = graphFiles(dataDir);
        if (files.isEmpty()) {
            return;
        }
        var tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
        try {
            try (var archive = new ZipFile(zip.toFile());
                 var out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.getName().startsWith(GRAPH_DIR + "/")) continue;
                    var copy = new ZipEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    try (var in = archive.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
                for (var e : files.entrySet()) {
                    var entry = new ZipEntry(e.getKey());
                    entry.setLastModifiedTime(Files.getLastModifiedTime(e.getValue()));
                    out.putNextEntry(entry);
                    Files.copy(e.getValue(), out);
                    out.closeEntry();
                }
            }
            Files.move(tmp, zip, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static SortedMap<String, Path> graphFiles(Path dataDir) throws IOException {
        var graphRoot = dataDir.resolve(GRAPH_DIR);
        var files = new TreeMap<String, Path>();
        if (!Files.isDirectory(graphRoot)) return files;
        try (var agents = Files.list(graphRoot)) {
            for (var agentDir : agents.toList()) {
                var agent = agentDir.getFileName().toString();
                if (!AGENT_DIR.matcher(agent).matches() || !Files.isDirectory(agentDir)) continue;
                try (var inside = Files.list(agentDir)) {
                    for (var file : inside.toList()) {
                        if (Files.isRegularFile(file)) {
                            files.put(GRAPH_DIR + "/" + agent + "/" + file.getFileName(), file);
                        }
                    }
                }
            }
        }
        return files;
    }

    /** A zip holding one {@value #DATA_FILE} entry that starts like an MVStore file. */
    public static BackupCheck validateBackup(Path zip) {
        if (!Files.isRegularFile(zip)) {
            return new BackupCheck(false, "not a file: " + zip.getFileName(), 0);
        }
        try (var archive = new ZipFile(zip.toFile())) {
            var entry = databaseEntry(archive);
            if (entry == null) {
                return new BackupCheck(false, "no " + DATA_FILE + " inside the archive", 0);
            }
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                var relative = name.startsWith(GRAPH_DIR + "/") ? name.substring(GRAPH_DIR.length() + 1) : "";
                if (!relative.isEmpty() && unsafeGraphEntry(relative)) {
                    return new BackupCheck(false, "unsafe graph entry in the archive: " + name, 0);
                }
            }
            try (var in = archive.getInputStream(entry)) {
                var head = in.readNBytes(MVSTORE_MAGIC.length());
                if (!MVSTORE_MAGIC.equals(new String(head, StandardCharsets.US_ASCII))) {
                    return new BackupCheck(false, DATA_FILE + " inside the archive is not an H2 database", 0);
                }
            }
            return new BackupCheck(true, "", entry.getSize());
        } catch (ZipException _) {
            return new BackupCheck(false, "not a zip archive", 0);
        } catch (IOException e) {
            return new BackupCheck(false, "unreadable: " + e.getMessage(), 0);
        }
    }

    private static @Nullable ZipEntry databaseEntry(ZipFile archive) {
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            var name = entry.getName();
            if (name.equals(DATA_FILE) || name.endsWith("/" + DATA_FILE)) {
                return entry;
            }
        }
        return null;
    }

    /** The zips in {@code dir}, newest first. */
    public static List<BackupFile> listBackups(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".zip") && Files.isRegularFile(p))
                    .map(p -> {
                        try {
                            return new BackupFile(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted((a, b) -> b.modified().compareTo(a.modified()))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ---- the write check (JF-311) ----

    /**
     * Unpack the archive's database to a throwaway copy under {@code dataDir}, write to it, close it, open it again and
     * read the write back. A copy that {@code BACKUP TO} took while writes landed can open, take a session's writes, and
     * at the next open fall back to an older version that drops them, and rows the first open showed with them, with no
     * error anywhere; this is how that copy shows itself. The zip is never changed, and the copy goes whatever the outcome.
     */
    public static WriteCheck checkKeepsWrites(Path zip, Path dataDir) {
        var work = dataDir.resolve(WRITE_CHECK_DIR);
        try {
            deleteTree(work);
            Files.createDirectories(work);
            try (var archive = new ZipFile(zip.toFile())) {
                var entry = databaseEntry(archive);
                if (entry == null) {
                    return new WriteCheck(Outcome.FAILED, "the archive holds no " + DATA_FILE);
                }
                try (var in = archive.getInputStream(entry)) {
                    Files.copy(in, work.resolve(DATA_FILE));
                }
            }
            return keepsWrites(work);
        } catch (IOException e) {
            return new WriteCheck(Outcome.NOT_RUN, "the copy could not be unpacked: " + firstLine(e.getMessage()));
        } finally {
            try {
                deleteTree(work);
            } catch (IOException _) {
                // The next check clears the directory before it starts.
            }
        }
    }

    /** {@link #checkKeepsWrites} on a database file already on disk, through a copy of it. */
    static WriteCheck checkFileKeepsWrites(Path dbFile, Path dataDir) throws IOException {
        var work = dataDir.resolve(WRITE_CHECK_DIR);
        deleteTree(work);
        Files.createDirectories(work);
        try {
            Files.copy(dbFile, work.resolve(DATA_FILE));
            return keepsWrites(work);
        } finally {
            deleteTree(work);
        }
    }

    /** The check itself, on {@code dir}'s own database, which it changes: callers pass a copy. */
    @SuppressWarnings("java:S2115") // JClaw's H2 files have no password by design: the folder's owner alone opens them
    private static WriteCheck keepsWrites(Path dir) {
        var token = UUID.randomUUID().toString();
        // A handful of pages are read; a small cache keeps this second database cheap inside the app's JVM.
        var url = url(dir) + ";IFEXISTS=TRUE;CACHE_SIZE=4096";
        try {
            try (var c = DriverManager.getConnection(url, "", ""); var s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS " + WRITE_CHECK_TABLE + "(TOKEN VARCHAR(36))");
                try (var insert = c.prepareStatement("INSERT INTO " + WRITE_CHECK_TABLE + " VALUES(?)")) {
                    insert.setString(1, token);
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            return new WriteCheck(Outcome.FAILED, "a copy of it would not take a write: " + firstLine(e.getMessage()));
        }
        var lost = new WriteCheck(Outcome.FAILED, "a copy of it reopened at an older version, without the write made to it");
        try (var c = DriverManager.getConnection(url, "", ""); var s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' "
                    + "AND TABLE_NAME = '" + WRITE_CHECK_TABLE + "'")) {
                if (!rs.next() || rs.getLong(1) == 0) {
                    return lost;
                }
            }
            try (var find = c.prepareStatement("SELECT COUNT(*) FROM " + WRITE_CHECK_TABLE + " WHERE TOKEN = ?")) {
                find.setString(1, token);
                try (var rs = find.executeQuery()) {
                    return rs.next() && rs.getLong(1) == 1
                            ? new WriteCheck(Outcome.PASSED, "a copy of it kept a test write across a reopen")
                            : lost;
                }
            }
        } catch (SQLException e) {
            return new WriteCheck(Outcome.FAILED, "a copy of it would not reopen after a write: " + firstLine(e.getMessage()));
        }
    }

    /** Rename a backup that failed the write check to end in {@value #CHECK_FAILED_SUFFIX}; returns the new path. */
    public static Path markCheckFailed(Path zip) throws IOException {
        var name = zip.getFileName().toString();
        if (name.endsWith(CHECK_FAILED_SUFFIX)) {
            return zip;
        }
        var stem = name.endsWith(".zip") ? name.substring(0, name.length() - ".zip".length()) : name;
        return Files.move(zip, zip.resolveSibling(stem + CHECK_FAILED_SUFFIX), StandardCopyOption.ATOMIC_MOVE);
    }

    public static boolean checkFailed(String backupName) {
        return backupName.endsWith(CHECK_FAILED_SUFFIX);
    }

    // ---- restore ----

    /**
     * Replace the database with the one inside {@code zip}. The archive's file is extracted beside the data file and
     * rewritten into a fresh one ({@link #rewriteMatching}), which must hold every table's rows and keep a test write
     * across a reopen before it moves into place; the current file becomes {@value #PRE_RESTORE_FILE}. Anything that
     * fails before the move leaves the old file where it was.
     *
     * <p>The rewrite is what keeps later writes (JF-311): an extract opened as it is can take a session's writes and drop
     * them at the next open. The extract is read through read-only opens alone. On such a file a read-write session,
     * even one that ends in {@code SHUTDOWN COMPACT}, leaves the older version behind and loses rows the first open
     * showed (a spliced copy went from 1,900 rows to 1,000).
     *
     * @throws IllegalArgumentException when {@code zip} fails {@link #validateBackup}
     */
    public static RestoreResult restore(Path zip, Path dataDir) throws IOException, SQLException {
        return restore(zip, dataDir, null);
    }

    /**
     * {@link #restore(Path, Path)}, and with {@code stamp}, the time of the restore, record in {@link CredentialCopies}
     * that the data file is a fresh rewrite: whatever old copies the backup's free pages held did not come with it.
     */
    public static RestoreResult restore(Path zip, Path dataDir, @Nullable String stamp) throws IOException, SQLException {
        var check = validateBackup(zip);
        if (!check.ok()) {
            throw new IllegalArgumentException(check.reason());
        }
        var restoredAt = stamp == null ? null : stampInstant(stamp);
        var target = dataDir.resolve(DATA_FILE);
        var staging = dataDir.resolve(RESTORING_DB + ".mv.db");
        var rewritten = dataDir.resolve(RESTORED_DB + ".mv.db");
        deleteDatabaseFiles(dataDir, RESTORING_DB);
        deleteDatabaseFiles(dataDir, RESTORED_DB);
        var graphStaging = extractGraph(zip, dataDir);
        int tables;
        try {
            try (var archive = new ZipFile(zip.toFile())) {
                var entry = databaseEntry(archive);
                if (entry == null) {
                    throw new IllegalArgumentException("no " + DATA_FILE + " inside the archive");
                }
                try (var in = archive.getInputStream(entry)) {
                    Files.copy(in, staging);
                }
            }
            var counts = readOnlyCounts(staging);
            rewriteMatching(staging, rewritten, counts);
            tables = counts.size();
            var writes = checkFileKeepsWrites(rewritten, dataDir);
            if (writes.outcome() != Outcome.PASSED) {
                throw new IOException("the rewritten database did not pass the write check (" + writes.detail()
                        + "); " + DATA_FILE + " is unchanged");
            }
        } catch (IOException | SQLException | RuntimeException e) {
            deleteTree(graphStaging);
            deleteDatabaseFiles(dataDir, RESTORING_DB);
            deleteDatabaseFiles(dataDir, RESTORED_DB);
            throw e;
        }
        var backupBytes = Files.size(staging);
        String displaced = null;
        if (Files.exists(target)) {
            Files.move(target, dataDir.resolve(PRE_RESTORE_FILE), StandardCopyOption.REPLACE_EXISTING);
            displaced = PRE_RESTORE_FILE;
        }
        Files.move(rewritten, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        deleteDatabaseFiles(dataDir, RESTORING_DB);
        deleteDatabaseFiles(dataDir, RESTORED_DB);
        Files.deleteIfExists(dataDir.resolve(LOCK_FILE));
        var graph = dataDir.resolve(GRAPH_DIR);
        if (Files.exists(graph)) {
            var displacedGraph = dataDir.resolve(GRAPH_PRE_RESTORE_DIR);
            deleteTree(displacedGraph);
            Files.move(graph, displacedGraph);
        }
        Files.move(graphStaging, graph, StandardCopyOption.ATOMIC_MOVE);
        if (restoredAt != null) {
            var copies = CredentialCopies.read(dataDir);
            var next = copies.afterCompaction(restoredAt);
            if (!next.equals(copies)) next.write(dataDir);
        }
        return new RestoreResult(Files.size(target), backupBytes, tables, displaced);
    }

    /**
     * Extract the archive's {@code memory-graph/**} entries to {@value #GRAPH_RESTORING_DIR},
     * refusing an entry that names a path outside it. An archive with none yields an empty
     * directory, which is the graph that backup had.
     *
     * @throws IllegalArgumentException on a {@code ..} segment or an absolute name, with nothing left on disk
     */
    static Path extractGraph(Path zip, Path dataDir) throws IOException {
        var staging = dataDir.resolve(GRAPH_RESTORING_DIR);
        deleteTree(staging);
        Files.createDirectories(staging);
        var base = staging.toAbsolutePath().normalize();
        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                var name = entry.getName();
                if (!name.startsWith(GRAPH_DIR + "/")) continue;
                var relative = name.substring(GRAPH_DIR.length() + 1);
                if (relative.isEmpty()) continue;
                if (unsafeGraphEntry(relative)) {
                    throw new IllegalArgumentException("unsafe graph entry in the archive: " + name);
                }
                var dest = base.resolve(relative).normalize();
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                var parent = dest.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(staging);
            throw e;
        }
        return staging;
    }

    /** True when a {@code memory-graph/}-relative entry name could land outside the graph directory. */
    private static boolean unsafeGraphEntry(String relative) {
        for (var segment : relative.split("/", -1)) {
            if (segment.equals("..") || segment.contains("\\")) return true;
        }
        try {
            if (relative.startsWith("/") || Path.of(relative).isAbsolute()) return true;
            var base = Path.of(GRAPH_DIR);
            var dest = base.resolve(relative).normalize();
            return !dest.startsWith(base) || dest.equals(base);
        } catch (InvalidPathException _) {
            return true;
        }
    }

    /** Delete {@code dir} and everything under it; a no-op when it does not exist. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    // ---- repair ----

    /**
     * The 2026-09-09 recovery, made deterministic: Recover the damaged file to a script,
     * move the damaged file and its trace aside, rebuild a fresh file from the script in the
     * app's mode, replay each ENUM table's copy with the ordinal cast Recover needs, verify
     * every table's count against what was staged, compact, and write the manifest.
     *
     * <p>Nothing is moved until Recover has produced a script, so a file Recover cannot
     * read at all is left exactly where it was.
     */
    public static Manifest repair(Path dataDir, String stamp) throws IOException, SQLException {
        return repair(dataDir, stamp, referenceCounts(dataDir));
    }

    /** {@link #repair(Path, String)} against a caller-supplied reference — the seam the verdict is tested through. */
    public static Manifest repair(Path dataDir, String stamp, Reference reference) throws IOException, SQLException {
        var dataFile = dataDir.resolve(DATA_FILE);
        if (!Files.isRegularFile(dataFile)) {
            throw new IOException("no " + DATA_FILE + " in " + dataDir);
        }
        var before = listNames(dataDir);
        Recover.execute(dataDir.toAbsolutePath().toString(), DB_NAME);
        var script = dataDir.resolve(DB_NAME + ".h2.sql");
        if (!Files.isRegularFile(script)) {
            throw new IOException("Recover produced no " + script.getFileName());
        }
        var produced = new ArrayList<String>();
        for (var name : listNames(dataDir)) {
            if (!before.contains(name)) {
                produced.add(name);
            }
        }

        var damaged = DATA_FILE + ".damaged-" + stamp;
        Files.move(dataFile, dataDir.resolve(damaged));
        String damagedTrace = null;
        if (Files.exists(dataDir.resolve(TRACE_FILE))) {
            damagedTrace = TRACE_FILE + ".damaged-" + stamp;
            Files.move(dataDir.resolve(TRACE_FILE), dataDir.resolve(damagedTrace));
        }
        Files.deleteIfExists(dataDir.resolve(LOCK_FILE));

        var rebuild = new Rebuild(reference.counts());
        try (var connection = DriverManager.getConnection(url(dataDir), "", "")) {
            try (var reader = new ScriptReader(Files.newBufferedReader(script, StandardCharsets.UTF_8))) {
                reader.setSkipRemarks(true);
                String statement;
                while ((statement = reader.readStatement()) != null) {
                    rebuild.execute(connection, statement.strip());
                }
            }
            rebuild.verifyCounts(connection);
            compact(connection);
        }

        var files = new ArrayList<FileEntry>();
        files.add(entry(dataDir, damaged));
        if (damagedTrace != null) {
            files.add(entry(dataDir, damagedTrace));
        }
        for (var name : produced) {
            files.add(entry(dataDir, name));
        }
        var scriptErrors = countMarkers(script);
        var tables = rebuild.tableResults();
        var unrecovered = rebuild.unrecoveredRows();
        var ok = !tables.isEmpty() && rebuild.failures.isEmpty() && scriptErrors == 0
                && tables.stream().allMatch(TableResult::matches);
        var manifest = new Manifest(stamp, ok, summarize(tables, unrecovered, scriptErrors, rebuild.failures, reference),
                damaged, damagedTrace, List.copyOf(files), tables, List.copyOf(rebuild.enumCastTables),
                reference.error() == null, reference.error(),
                unrecovered, scriptErrors, List.copyOf(rebuild.failures), Files.size(dataFile));
        Files.writeString(dataDir.resolve(MANIFEST_PREFIX + stamp + MANIFEST_SUFFIX), GSON.toJson(manifest),
                StandardCharsets.UTF_8);
        return manifest;
    }

    /** The script-replay state: what was staged, what was copied, what the rebuild refused. */
    private static final class Rebuild {
        final Map<String, Long> reference;
        final Map<String, List<ColumnDef>> columnsByTable = new LinkedHashMap<>();

        Rebuild(Map<String, Long> reference) {
            this.reference = reference;
        }
        final Map<String, Long> stagedByStaging = new LinkedHashMap<>();
        final Map<String, String> tableByStaging = new HashMap<>();
        final Map<String, Long> restoredByTable = new LinkedHashMap<>();
        final List<String> enumCastTables = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();
        long failedStagingRows;

        void execute(Connection connection, String statement) {
            if (statement.isEmpty()) {
                return;
            }
            var create = CREATE_TABLE.matcher(statement);
            if (create.find()) {
                columnsByTable.put(create.group(2) + "." + create.group(4), parseColumns(statement));
            }
            var staging = STAGING_INSERT.matcher(statement);
            var copy = COPY_STATEMENT.matcher(statement);
            if (copy.matches()) {
                tableByStaging.put(copy.group(5), copy.group(2) + "." + copy.group(4));
            }
            try (var s = connection.createStatement()) {
                s.execute(statement);
                if (staging.find()) {
                    stagedByStaging.merge(staging.group(1), 1L, Long::sum);
                }
            } catch (SQLException e) {
                if (copy.matches() && retryWithEnumCast(connection, copy.group(2), copy.group(4), copy.group(5))) {
                    return;
                }
                if (staging.find()) {
                    failedStagingRows++;
                }
                failures.add(new Failure(head(statement), firstLine(e.getMessage())));
            }
        }

        /**
         * Recover stores an ENUM value as its 1-based ordinal in the VARCHAR staging column, and
         * the final copy refuses it (22030). The same select with each ENUM column cast through
         * INT is what the incident procedure ran by hand.
         */
        private boolean retryWithEnumCast(Connection connection, String schema, String table, String staging) {
            var columns = columnsByTable.get(schema + "." + table);
            if (columns == null || columns.stream().noneMatch(c -> c.enumList() != null)) {
                return false;
            }
            var select = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (!select.isEmpty()) {
                    select.append(", ");
                }
                var enumList = columns.get(i).enumList();
                select.append(enumList == null ? "C" + i
                        : "CAST(CAST(C" + i + " AS INT) AS ENUM(" + enumList + "))");
            }
            var sql = "INSERT INTO \"" + schema + "\".\"" + table + "\" SELECT " + select + " FROM " + staging;
            try (var s = connection.createStatement()) {
                s.execute(sql);
                enumCastTables.add(schema + "." + table);
                return true;
            } catch (SQLException _) {
                return false;
            }
        }

        void verifyCounts(Connection connection) {
            var tables = new TreeSet<String>(reference.keySet());
            tables.addAll(tableByStaging.values());
            tables.addAll(columnsByTable.keySet().stream().filter(t -> !t.startsWith("INFORMATION_SCHEMA.")).toList());
            for (var table : tables) {
                var dot = table.indexOf('.');
                var sql = "SELECT COUNT(*) FROM \"" + table.substring(0, dot) + "\".\"" + table.substring(dot + 1) + "\"";
                try (var s = connection.createStatement(); var rs = s.executeQuery(sql)) {
                    restoredByTable.put(table, rs.next() ? rs.getLong(1) : 0L);
                } catch (SQLException ex) {
                    restoredByTable.put(table, 0L);
                    failures.add(new Failure(sql, firstLine(ex.getMessage())));
                }
            }
        }

        /**
         * One row per user table, from every source that names one: the reference open, the
         * script's CREATE statements and the copies. A table the reference counted but the script
         * never copied is the silent case — MVStore rolled its map back and Recover found it
         * empty — and it surfaces here as restored 0 of expected N.
         */
        List<TableResult> tableResults() {
            var stagedByTable = new HashMap<String, Long>();
            for (var e : tableByStaging.entrySet()) {
                stagedByTable.merge(e.getValue(), stagedByStaging.getOrDefault(e.getKey(), 0L), Long::sum);
            }
            var names = new TreeSet<String>(reference.keySet());
            names.addAll(columnsByTable.keySet().stream().filter(t -> !t.startsWith("INFORMATION_SCHEMA.")).toList());
            names.addAll(stagedByTable.keySet());
            var out = new ArrayList<TableResult>();
            for (var name : names) {
                out.add(new TableResult(name, reference.get(name), stagedByTable.getOrDefault(name, 0L),
                        restoredByTable.getOrDefault(name, 0L)));
            }
            return List.copyOf(out);
        }

        long unrecoveredRows() {
            return failedStagingRows;
        }
    }

    public record ColumnDef(String name, @Nullable String enumList) {}

    /** The columns of a CREATE TABLE, in declared order, each with its ENUM label list if it has one. */
    public static List<ColumnDef> parseColumns(String createStatement) {
        var open = createStatement.indexOf('(');
        var close = createStatement.lastIndexOf(')');
        if (open < 0 || close < open) {
            return List.of();
        }
        var out = new ArrayList<ColumnDef>();
        for (var part : splitTopLevel(createStatement.substring(open + 1, close))) {
            var def = part.strip();
            if (def.isEmpty() || CONSTRAINT_CLAUSE.matcher(def).find()) {
                continue;
            }
            var name = def.startsWith("\"") ? def.substring(1, def.indexOf('"', 1)) : def.split("\\s+")[0];
            var enumType = ENUM_TYPE.matcher(def);
            out.add(new ColumnDef(name, enumType.find() ? enumType.group(1).strip() : null));
        }
        return List.copyOf(out);
    }

    /** Split on commas outside parentheses and quotes — a column list, not a CSV line. */
    private static List<String> splitTopLevel(String s) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        var depth = 0;
        char quote = 0;
        for (var c : s.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    /** Per-table counts from the damaged file, or the reason it gave none. */
    public record Reference(Map<String, Long> counts, @Nullable String error) {}

    /**
     * Count every PUBLIC table through a read-only open of the file as it is. This is the only
     * yardstick that can see rows MVStore has already rolled back: Recover reads the same
     * rolled-back state and writes nothing for them. A file that will not open gives no
     * reference, and the report says so.
     */
    public static Reference referenceCounts(Path dataDir) {
        var counts = new LinkedHashMap<String, Long>();
        var readOnly = url(dataDir) + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
        try (var connection = DriverManager.getConnection(readOnly, "", "")) {
            var names = new ArrayList<String>();
            try (var s = connection.createStatement();
                 var rs = s.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                         + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            for (var name : names) {
                try (var s = connection.createStatement();
                     var rs = s.executeQuery("SELECT COUNT(*) FROM \"PUBLIC\".\"" + name + "\"")) {
                    if (rs.next()) {
                        counts.put("PUBLIC." + name, rs.getLong(1));
                    }
                } catch (SQLException _) {
                    // A table whose own pages are damaged: no count, and the rebuild reports what it staged.
                }
            }
            return new Reference(counts, null);
        } catch (SQLException e) {
            return new Reference(counts, "the damaged file would not open: " + firstLine(e.getMessage()));
        } finally {
            try {
                Files.deleteIfExists(dataDir.resolve(LOCK_FILE));
            } catch (IOException _) {
                // Recover reads the file regardless; the rebuild deletes the lock again after moving the file.
            }
        }
    }

    private static void compact(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            s.execute("SHUTDOWN COMPACT");
        }
    }

    // ---- compaction of the old credential copies (JCLAW-1455) ----

    /** The copy {@link #compact(Path, String)} writes and checks before it replaces the data file. */
    static final String COMPACTING_DB = DB_NAME + "-compacting";

    public record CompactResult(long beforeBytes, long afterBytes, int tables) {}

    /**
     * Rewrite the closed data file with its live data alone, so the plaintext an encrypted value replaced, and the
     * previous secret's ciphertext a rotation replaced, leave the file. H2's {@code SHUTDOWN COMPACT} compresses what it
     * writes, which hides those remnants from a scan without showing them gone; this is the same rewrite uncompressed.
     * The copy is written beside the data file and moved over it in one step once every table's row count matches, so a
     * run cut short leaves the data file as it was. Then the trace is masked and {@link CredentialCopies} records it.
     *
     * @throws SQLException when the database will not open, which includes a running instance holding it
     */
    public static CompactResult compact(Path dataDir, String stamp) throws IOException, SQLException {
        var at = stampInstant(stamp);
        var dataFile = dataDir.resolve(DATA_FILE);
        if (!Files.isRegularFile(dataFile)) {
            throw new IOException("no " + DATA_FILE + " in " + dataDir);
        }
        deleteDatabaseFiles(dataDir, COMPACTING_DB);
        Map<String, Long> before;
        // A plain open, not read-only: H2 refuses it while another process holds the file, and its SHUTDOWN
        // finishes whatever an unclean stop left, so the copy starts from a closed file.
        try (var connection = DriverManager.getConnection(url(dataDir) + ";IFEXISTS=TRUE", "", "")) {
            before = tableCounts(connection);
            try (var s = connection.createStatement()) {
                s.execute("SHUTDOWN");
            }
        }
        var beforeBytes = Files.size(dataFile);
        var copy = dataDir.resolve(COMPACTING_DB + ".mv.db");
        try {
            rewriteMatching(dataFile, copy, before);
            Files.move(copy, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteDatabaseFiles(dataDir, COMPACTING_DB);
        }
        H2TraceMask.maskTraces(dataDir);
        CredentialCopies.read(dataDir).afterCompaction(at).write(dataDir);
        return new CompactResult(beforeBytes, Files.size(dataFile), before.size());
    }

    /**
     * Rewrite the closed {@code source} into a fresh {@code target} holding its live data alone, and check that every
     * table has {@code expected} rows there. {@code source} is only ever read; on a mismatch {@code target} is left for
     * the caller to delete.
     */
    private static void rewriteMatching(Path source, Path target, Map<String, Long> expected) throws IOException, SQLException {
        MVStore.compact(source.toString(), target.toString(), false, null);
        var after = readOnlyCounts(target);
        if (!after.equals(expected)) {
            throw new IOException("the rewritten copy does not match " + source.getFileName() + " (" + expected + " before, "
                    + after + " after); " + DATA_FILE + " is unchanged");
        }
    }

    /** Every PUBLIC table's row count in {@code dbFile} (a {@code <name>.mv.db}), through a read-only open. */
    @SuppressWarnings("java:S2115") // JClaw's H2 files have no password by design: the folder's owner alone opens them
    private static Map<String, Long> readOnlyCounts(Path dbFile) throws SQLException {
        var name = dbFile.getFileName().toString();
        var base = dbFile.toAbsolutePath().resolveSibling(name.substring(0, name.length() - ".mv.db".length()));
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:file:" + base + ";" + MODE + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", "", "")) {
            return tableCounts(connection);
        }
    }

    /** Remove a scratch database's files from {@code dir}: its data file, lock and trace. */
    private static void deleteDatabaseFiles(Path dir, String name) throws IOException {
        for (var suffix : List.of(".mv.db", ".lock.db", ".trace.db")) {
            Files.deleteIfExists(dir.resolve(name + suffix));
        }
    }

    @SuppressWarnings("java:S2077") // the names are the schema's own, read from INFORMATION_SCHEMA
    private static Map<String, Long> tableCounts(Connection connection) throws SQLException {
        var names = new ArrayList<String>();
        try (var s = connection.createStatement();
             var rs = s.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                     + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        var counts = new TreeMap<String, Long>();
        for (var name : names) {
            try (var s = connection.createStatement();
                 var rs = s.executeQuery("SELECT COUNT(*) FROM \"PUBLIC\".\"" + name + "\"")) {
                counts.put(name, rs.next() ? rs.getLong(1) : 0L);
            }
        }
        return counts;
    }

    /** A {@code yyyyMMdd'T'HHmmss'Z'} stamp, as {@code jclaw.sh} and the app name their files, read as an instant. */
    public static Instant stampInstant(String stamp) {
        try {
            return STAMP.parse(stamp, Instant::from);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not a yyyyMMddTHHmmssZ stamp: " + stamp, e);
        }
    }

    private static int countMarkers(Path script) throws IOException {
        var count = 0;
        try (BufferedReader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(RECOVER_ERROR_MARKER)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String summarize(List<TableResult> tables, long unrecovered, int scriptErrors,
                                    List<Failure> failures, Reference reference) {
        if (tables.isEmpty()) {
            return "nothing recovered — Recover found no readable tables"
                    + (scriptErrors > 0 ? " (" + scriptErrors + " unreadable page" + (scriptErrors == 1 ? "" : "s") + ")" : "");
        }
        var rows = tables.stream().mapToLong(TableResult::restored).sum();
        var sb = new StringBuilder(tables.size() + " tables, " + rows + " rows restored");
        var shortTables = tables.stream().filter(t -> !t.matches()).toList();
        if (!shortTables.isEmpty()) {
            sb.append("; ").append(shortTables.size()).append(" table").append(shortTables.size() == 1 ? "" : "s").append(" short: ");
            var named = new ArrayList<String>();
            for (var t : shortTables) {
                named.add(t.name() + " " + t.restored() + " of " + (t.expected() != null ? t.expected() : t.staged()));
            }
            sb.append(String.join(", ", named));
        }
        if (reference.error() != null) {
            sb.append("; no reference counts (").append(reference.error()).append(")");
        }
        if (unrecovered > 0) {
            sb.append("; ").append(unrecovered).append(" row").append(unrecovered == 1 ? "" : "s").append(" could not be inserted");
        }
        if (scriptErrors > 0) {
            sb.append("; ").append(scriptErrors).append(" unreadable page").append(scriptErrors == 1 ? "" : "s");
        }
        var other = failures.size() - unrecovered;
        if (other > 0) {
            sb.append("; ").append(other).append(" statement").append(other == 1 ? "" : "s").append(" failed");
        }
        return sb.toString();
    }

    // ---- manifests and cleanup ----

    /** Every repair manifest in {@code dataDir}, newest stamp first. */
    public static List<Path> manifests(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.filter(p -> {
                        var n = p.getFileName().toString();
                        return n.startsWith(MANIFEST_PREFIX) && n.endsWith(MANIFEST_SUFFIX);
                    })
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
        }
    }

    public static @Nullable Manifest latestManifest(Path dataDir) throws IOException {
        var all = manifests(dataDir);
        return all.isEmpty() ? null : readManifest(all.getFirst());
    }

    public static Manifest readManifest(Path manifest) throws IOException {
        var parsed = GSON.fromJson(Files.readString(manifest, StandardCharsets.UTF_8), Manifest.class);
        if (parsed == null) {
            throw new IOException("empty manifest " + manifest.getFileName());
        }
        return parsed;
    }

    /**
     * Delete what {@code manifest} lists and then the manifest itself, after every listed file
     * still matches its recorded checksum. A mismatch — or a name that is the live database,
     * its lock or its trace — refuses the whole cleanup and deletes nothing: the damaged file is
     * the only route to a second attempt, and a file the operator has touched since is not ours.
     */
    public static CleanResult clean(Path dataDir, Manifest manifest, boolean dryRun) throws IOException {
        if (!manifest.ok()) {
            return new CleanResult(false, "the repair on " + manifest.stamp() + " did not recover everything ("
                    + manifest.summary() + "); the damaged file is kept for another attempt", List.of(), 0);
        }
        var live = List.of(DATA_FILE, LOCK_FILE, TRACE_FILE, PRE_RESTORE_FILE);
        var present = new ArrayList<FileEntry>();
        for (var f : manifest.files()) {
            if (f.name().contains("/") || f.name().contains("\\") || live.contains(f.name())) {
                return new CleanResult(false, "refusing to delete " + f.name(), List.of(), 0);
            }
            var path = dataDir.resolve(f.name());
            if (!Files.exists(path)) {
                continue;
            }
            var actual = sha256(path);
            if (!actual.equals(f.sha256())) {
                return new CleanResult(false, f.name() + " has changed since the repair wrote it (checksum differs)",
                        List.of(), 0);
            }
            present.add(f);
        }
        var reclaimed = present.stream().mapToLong(FileEntry::bytes).sum();
        if (dryRun) {
            return new CleanResult(true, "", List.copyOf(present), reclaimed);
        }
        for (var f : present) {
            Files.deleteIfExists(dataDir.resolve(f.name()));
        }
        Files.deleteIfExists(dataDir.resolve(MANIFEST_PREFIX + manifest.stamp() + MANIFEST_SUFFIX));
        return new CleanResult(true, "", List.copyOf(present), reclaimed);
    }

    // ---- helpers ----

    public static String url(Path dataDir) {
        return "jdbc:h2:file:" + dataDir.toAbsolutePath().resolve(DB_NAME) + ";" + MODE;
    }

    private static TreeSet<String> listNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            var names = new TreeSet<String>();
            files.forEach(p -> names.add(p.getFileName().toString()));
            return names;
        }
    }

    private static FileEntry entry(Path dir, String name) throws IOException {
        var path = dir.resolve(name);
        return new FileEntry(name, Files.size(path), sha256(path));
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            var buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String head(String statement) {
        var oneLine = statement.replace('\n', ' ');
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 157) + "...";
    }

    private static String firstLine(@Nullable String message) {
        if (message == null) {
            return "";
        }
        var nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ---- command line, for jclaw.sh with the app stopped ----

    static final String USAGE = """
            usage: H2Maintenance <command> ...
              backup   <dataDir> <zip>            zip a closed database, then check that a copy keeps a write
              validate <zip>                      check a zip is an H2 backup
              restore  <dataDir> <zip> [stamp]    replace the database with a checked rewrite of the zip's
              repair   <dataDir> <stamp>          recover, rebuild, verify, compact; write repair-<stamp>.json
              compact  <dataDir> <stamp>          rewrite the file with its live data alone, removing old credential copies
              clean    <dataDir> [--dry-run]      delete what the latest repair manifest lists
              status   <dataDir>                  size, repair remnants and old credential copies
              list     <backupsDir> [dataDir]     backups, newest first, marking those that hold credential plaintext
            """;

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** {@link #main} without the exit, so a test can drive the same dispatch. Returns the exit code. */
    public static int run(String[] args, PrintStream out) {
        try {
            return dispatch(args, out);
        } catch (IllegalArgumentException e) {
            out.println("error: " + e.getMessage());
            return 1;
        } catch (IOException | SQLException | RuntimeException e) {
            out.println("error: " + firstLine(e.getMessage()));
            return 2;
        }
    }

    private static int dispatch(String[] args, PrintStream out) throws IOException, SQLException {
        if (args.length == 0) {
            out.print(USAGE);
            return 1;
        }
        switch (args[0]) {
            case "backup" -> {
                require(args, 3);
                var dataDir = Path.of(args[1]);
                var zip = Path.of(args[2]);
                backupOffline(dataDir, zip);
                var check = checkKeepsWrites(zip, dataDir);
                if (check.outcome() == Outcome.FAILED) {
                    zip = markCheckFailed(zip);
                }
                out.println(zip.toAbsolutePath() + " (" + human(Files.size(zip)) + ")");
                out.println(switch (check.outcome()) {
                    case PASSED -> "checked: " + check.detail();
                    case FAILED -> "check failed: " + check.detail() + ". Restoring it with 'restore' still works, because"
                            + " a restore rewrites the database first; opened directly, it drops new writes.";
                    case NOT_RUN -> "not checked: " + check.detail();
                });
                return check.outcome() == Outcome.FAILED ? 1 : 0;
            }
            case "validate" -> {
                require(args, 2);
                var check = validateBackup(Path.of(args[1]));
                out.println(check.ok() ? "ok: " + DATA_FILE + " " + human(check.dbBytes()) : "invalid: " + check.reason());
                return check.ok() ? 0 : 1;
            }
            case "restore" -> {
                require(args, 3);
                var result = restore(Path.of(args[2]), Path.of(args[1]), args.length > 3 ? args[3] : null);
                out.println("restored " + DATA_FILE + " (" + human(result.restoredBytes()) + ", rewritten from the backup's "
                        + human(result.backupBytes()) + "; " + result.tables() + (result.tables() == 1 ? " table" : " tables")
                        + " checked row for row, and a test write kept across a reopen)"
                        + (result.preRestoreCopy() == null ? "" : "; previous file kept as " + result.preRestoreCopy()));
                return 0;
            }
            case "repair" -> {
                require(args, 3);
                var manifest = repair(Path.of(args[1]), args[2]);
                printReport(manifest, out);
                return manifest.ok() ? 0 : 1;
            }
            case "compact" -> {
                require(args, 3);
                var result = compact(Path.of(args[1]), args[2]);
                out.println("compacted " + DATA_FILE + ": " + human(result.beforeBytes()) + " -> " + human(result.afterBytes())
                        + ", " + result.tables() + " tables checked row for row");
                return 0;
            }
            case "clean" -> {
                require(args, 2);
                var dataDir = Path.of(args[1]);
                var manifest = latestManifest(dataDir);
                if (manifest == null) {
                    out.println("nothing to clean: no repair manifest in " + dataDir);
                    return 0;
                }
                var dryRun = args.length > 2 && args[2].equals("--dry-run");
                var result = clean(dataDir, manifest, dryRun);
                if (!result.ok()) {
                    out.println("refused: " + result.reason());
                    return 1;
                }
                for (var f : result.deleted()) {
                    out.println("  " + (dryRun ? "would delete " : "deleted ") + f.name() + " (" + human(f.bytes()) + ")");
                }
                out.println((dryRun ? "would reclaim " : "reclaimed ") + human(result.reclaimedBytes()));
                return 0;
            }
            case "status" -> {
                require(args, 2);
                printStatus(Path.of(args[1]), out);
                return 0;
            }
            case "list" -> {
                require(args, 2);
                var dir = Path.of(args[1]);
                var backups = listBackups(dir);
                var copies = args.length > 2 ? CredentialCopies.read(Path.of(args[2])) : CredentialCopies.NONE;
                if (backups.isEmpty()) {
                    out.println("no backups");
                }
                for (var b : backups) {
                    out.println("  " + b.name() + "  " + b.modified().toString().replace("T", " ").replaceAll("\\.\\d+Z$", "Z")
                            + "  " + human(b.bytes())
                            + (copies.holdsPlaintext(CredentialCopies.takenAt(dir.resolve(b.name()))) ? "  (credentials unencrypted)" : ""));
                }
                return 0;
            }
            default -> {
                out.print(USAGE);
                return 1;
            }
        }
    }

    private static void require(String[] args, int count) {
        if (args.length < count) {
            throw new IllegalArgumentException("missing arguments for " + args[0] + "\n" + USAGE);
        }
    }

    static void printReport(Manifest m, PrintStream out) {
        out.println((m.ok() ? "repaired: " : "repair incomplete: ") + m.summary());
        for (var t : m.tables()) {
            out.println("  " + (t.matches() ? "ok   " : "SHORT") + " " + t.name() + "  " + t.restored()
                    + (t.matches() ? "" : " of " + (t.expected() != null ? t.expected() : t.staged())) + " rows"
                    + (m.enumCastTables().contains(t.name()) ? "  (enum cast)" : ""));
        }
        if (!m.referenceAvailable()) {
            out.println("  no reference counts: " + m.referenceError()
                    + " — a table MVStore had already rolled back reads as empty above");
        }
        for (var f : m.failures()) {
            out.println("  failed: " + f.error() + "\n          " + f.statement());
        }
        out.println("kept aside (" + human(m.intermediateBytes()) + "):");
        for (var f : m.files()) {
            out.println("  " + f.name() + "  " + human(f.bytes()));
        }
        out.println("rebuilt " + DATA_FILE + " is " + human(m.restoredBytes()));
    }

    static void printStatus(Path dataDir, PrintStream out) throws IOException {
        var data = dataDir.resolve(DATA_FILE);
        out.println(DATA_FILE + ": " + (Files.exists(data) ? human(Files.size(data)) : "missing"));
        var trace = dataDir.resolve(TRACE_FILE);
        if (Files.exists(trace)) {
            out.println(TRACE_FILE + ": " + human(Files.size(trace)));
        }
        if (Files.exists(dataDir.resolve(PRE_RESTORE_FILE))) {
            out.println(PRE_RESTORE_FILE + ": " + human(Files.size(dataDir.resolve(PRE_RESTORE_FILE))) + " (kept until the next backup)");
        }
        var copies = CredentialCopies.read(dataDir);
        if (copies.due() != null) {
            out.println("credentials: the data file still holds "
                    + (copies.due() == CredentialCopies.OldCopies.PLAINTEXT ? "their old plaintext" : "copies under the previous secret")
                    + " (remove it with 'compact')");
        }
        var manifests = manifests(dataDir);
        if (manifests.isEmpty()) {
            out.println("repair remnants: none");
            return;
        }
        for (var path : manifests) {
            var m = readManifest(path);
            out.println("repair " + m.stamp() + ": " + m.summary());
            out.println("  remnants " + human(m.intermediateBytes()) + " in " + m.files().size() + " files (clean with 'db-clean')");
        }
    }
}
