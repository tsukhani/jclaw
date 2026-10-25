import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs jclaw.sh's staged-runtime functions against a scratch install ({@code <home>/jclaw},
 * {@code <home>/jre}) whose runtimes are stub {@code java} scripts, so the swap, every way it refuses,
 * and the zip branches are tested as written rather than restated.
 */
class JavaRuntimeScriptTest extends UnitTest {

    private static final List<String> FUNCTIONS = List.of("native_path", "jre_javas_in", "use_bundled_jre",
            "sha256_file", "conf_jvm_modules", "jre_status", "jre_version_norm", "jre_reported_version",
            "jre_zip_tool", "jre_install_staged", "apply_staged_jre");

    private static final String OLD_TOP = "zulu25.36.15-ca-jre25.0.4-macosx_aarch64";
    private static final String NEW_TOP = "zulu25.38.107-ca-jre25.0.5-macosx_aarch64";
    private static final String HOME_IN_ARCHIVE = "/zulu-25.jre/Contents/Home";
    private static final String TARBALL = NEW_TOP + ".tar.gz";
    private static final String ZIP = "zulu25.38.107-ca-jre25.0.5-win_x64.zip";

    @TempDir Path tmp;

    @Test
    void aGoodTarballIsSwappedInAndTheReplacedRuntimeKeptAsJrePrevious() throws Exception {
        var home = install();
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.5", true)));

        var out = run(home, Map.of(), "use_bundled_jre; apply_staged_jre; echo \"JAVA_HOME=$JAVA_HOME\"; echo \"JAVA=$(command -v java)\"");

        assertTrue(out.contains("==> Installing the Java runtime downloaded in Settings...\n"
                + "    Java runtime: upgraded 25.0.4 → 25.0.5\n"), out);
        assertEquals("upgraded 25.0.4 → 25.0.5", status(home));
        // pwd, as jclaw.sh resolves it: logical, so a symlinked temp directory keeps its name.
        var newHome = home.resolve("jre/" + NEW_TOP + HOME_IN_ARCHIVE);
        assertTrue(out.contains("JAVA_HOME=" + newHome + "\n"), out);
        assertTrue(out.contains("JAVA=" + newHome + "/bin/java\n"), "the start re-resolves to the new runtime: " + out);
        assertTrue(Files.readString(home.resolve("jre.previous/" + OLD_TOP + HOME_IN_ARCHIVE + "/bin/java")).contains("25.0.4"));
        assertEquals(List.of(NEW_TOP), listing(home.resolve("jre")));
        assertFalse(Files.exists(home.resolve("jre.staged")));
        assertFalse(Files.exists(home.resolve("jre.new")));

        // One copy: the next upgrade's replaced runtime takes jre.previous's place.
        var next = "zulu25.40.1-ca-jre25.0.6-macosx_aarch64";
        stage(home, next + ".tar.gz", "25.0.6", tarGz(runtime(next, "25.0.6", true)));
        var second = run(home, Map.of(), "apply_staged_jre");
        assertTrue(second.contains("Java runtime: upgraded 25.0.5 → 25.0.6"), second);
        assertEquals(List.of(NEW_TOP), listing(home.resolve("jre.previous")));
        assertEquals(List.of(next), listing(home.resolve("jre")));
    }

    @Test
    void aZipIsUnpackedWithUnzip() throws Exception {
        Assumptions.assumeTrue(which("unzip") != null, "this host has no unzip");
        var home = install();
        stage(home, ZIP, zip(runtime(NEW_TOP, "25.0.5", true)));

        var out = run(home, Map.of(), "apply_staged_jre");

        assertTrue(out.contains("Java runtime: upgraded 25.0.4 → 25.0.5"), out);
        assertEquals(List.of(NEW_TOP), listing(home.resolve("jre")));
        assertEquals(List.of(OLD_TOP), listing(home.resolve("jre.previous")));
    }

    @Test
    void withoutUnzipWindowsOwnTarExeUnpacksTheZip() throws Exception {
        var home = install();
        stage(home, ZIP, zip(runtime(NEW_TOP, "25.0.5", true)));
        var system32 = Files.createDirectories(tmp.resolve("Windows/System32"));
        var jar = Path.of(System.getProperty("java.home"), "bin", "jar");
        Assumptions.assumeTrue(Files.isExecutable(jar), "the test JVM has no jar tool to stand in for tar.exe");
        // bsdtar's -xf ZIP -C DEST, done with jar, which drops the exec bit tar.exe would keep.
        executable(system32.resolve("tar.exe"), "#!/bin/sh\necho \"tar.exe $*\" >> \"$STUB_CALLS\"\n"
                + "cd \"$4\" && '" + jar + "' xf \"$2\" && chmod -R u+x .\n");

        var out = run(home, Map.of("IS_WINDOWS", "1", "SYSTEMROOT", tmp.resolve("Windows").toString(),
                "PATH", toolsWithout("unzip")), "apply_staged_jre");

        assertTrue(out.contains("Java runtime: upgraded 25.0.4 → 25.0.5"), out);
        assertEquals(List.of("tar.exe -xf " + home.resolve("jre.staged/" + ZIP) + " -C " + home.resolve("jre.new")), calls());
        assertEquals(List.of(NEW_TOP), listing(home.resolve("jre")));
    }

    @Test
    void aZipWithNothingToUnpackItKeepsTheCurrentJava() throws Exception {
        var home = install();
        stage(home, ZIP, zip(runtime(NEW_TOP, "25.0.5", true)));

        var out = run(home, Map.of("PATH", toolsWithout("unzip")), "apply_staged_jre");

        assertKept(home, out, "kept Java 25.0.4: there is no unzip or tar.exe to unpack Java 25.0.5 with");
    }

    @Test
    void aStagingThatFailsItsChecksumKeepsTheCurrentJava() throws Exception {
        var home = install();
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.5", true)));
        Files.write(home.resolve("jre.staged/" + TARBALL), "tampered".getBytes(StandardCharsets.UTF_8));

        assertKept(home, run(home, Map.of(), "apply_staged_jre"), "kept Java 25.0.4: Java 25.0.5 failed its SHA-256 check");
    }

    @Test
    void anArchiveThatWillNotUnpackKeepsTheCurrentJava() throws Exception {
        var home = install();
        stage(home, TARBALL, "not a tarball".getBytes(StandardCharsets.UTF_8));

        assertKept(home, run(home, Map.of(), "apply_staged_jre"), "kept Java 25.0.4: Java 25.0.5 could not be unpacked");
    }

    @Test
    void aRuntimeThatDoesNotReportTheStagedVersionKeepsTheCurrentJava() throws Exception {
        var home = install();
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.3", true)));
        assertKept(home, run(home, Map.of(), "apply_staged_jre"), "kept Java 25.0.4: Java 25.0.5 reported 25.0.3 when it ran");

        // Run as the start runs it: a runtime missing the module jvm.memory adds reports nothing.
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.5", false)));
        assertKept(home, run(home, Map.of(), "apply_staged_jre"), "kept Java 25.0.4: Java 25.0.5 reported no version when it ran");
    }

    @Test
    void aStagedMajorVersionIsNeverInstalled() throws Exception {
        var home = install();
        var top = "zulu26.30.1-ca-jre26.0.1-macosx_aarch64";
        stage(home, top + ".tar.gz", "26.0.1", tarGz(runtime(top, "26.0.1", true)));

        assertKept(home, run(home, Map.of(), "apply_staged_jre"),
                "kept Java 25.0.4: Java 26.0.1 is not a patch of the Java it runs on");
    }

    @Test
    void anArchiveHoldingTwoJavasIsNeverInstalled() throws Exception {
        var home = install();
        var files = runtime(NEW_TOP, "25.0.5", true);
        files.putAll(runtime("second", "25.0.5", true));
        stage(home, TARBALL, tarGz(files));

        assertKept(home, run(home, Map.of(), "apply_staged_jre"),
                "kept Java 25.0.4: Java 25.0.5 did not unpack to exactly one bin/java");
    }

    @Test
    void aManifestNamingAPathOutsideTheStagingIsRefused() throws Exception {
        var home = install();
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.5", true)));
        Files.writeString(home.resolve("jre.staged/manifest"),
                "version=25.0.5\nsha256=" + "a".repeat(64) + "\narchive=../jre/evil.tar.gz\n");

        assertKept(home, run(home, Map.of(), "apply_staged_jre"), "kept Java 25.0.4: the staged runtime's manifest is malformed");
    }

    @Test
    void anUnfinishedDownloadIsLeftAloneAndRecordsNothing() throws Exception {
        var home = install();
        Files.createDirectories(home.resolve("jre.staged"));
        Files.write(home.resolve("jre.staged/" + TARBALL + ".part"), "half".getBytes(StandardCharsets.UTF_8));

        assertEquals("", run(home, Map.of(), "apply_staged_jre"));
        assertFalse(Files.exists(home.resolve("jclaw/data/java-runtime.status")));
        assertTrue(Files.exists(home.resolve("jre.staged/" + TARBALL + ".part")));
        assertEquals(List.of(OLD_TOP), listing(home.resolve("jre")));
    }

    @Test
    void aStartWithNothingStagedClearsAnOlderStatusLine() throws Exception {
        var home = install();
        Files.createDirectories(home.resolve("jclaw/data"));
        Files.writeString(home.resolve("jclaw/data/java-runtime.status"), "upgraded 25.0.3 → 25.0.4\n");

        assertEquals("", run(home, Map.of(), "apply_staged_jre"));
        assertFalse(Files.exists(home.resolve("jclaw/data/java-runtime.status")));
        assertEquals(List.of(OLD_TOP), listing(home.resolve("jre")));
    }

    @Test
    void aSwapThatCannotMoveTheNewRuntimeInPutsTheOldOneBack() throws Exception {
        var home = install();
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.5", true)));
        // Fails only the second rename, after jre/ has already moved aside to jre.previous.
        var failing = Files.createDirectories(tmp.resolve("failing-mv"));
        executable(failing.resolve("mv"), "#!/bin/sh\ncase \"$1\" in */jre.new) exit 1 ;; esac\nexec '"
                + which("mv") + "' \"$@\"\n");

        var out = run(home, Map.of("PATH", failing + File.pathSeparator + System.getenv("PATH")), "apply_staged_jre");

        assertKept(home, out, "kept Java 25.0.4: could not move Java 25.0.5 into " + home + "/jre");
    }

    @Test
    void withNoBuiltInJavaToReplaceNothingIsInstalled() throws Exception {
        var home = install();
        deleteTree(home.resolve("jre"));
        stage(home, TARBALL, tarGz(runtime(NEW_TOP, "25.0.5", true)));

        var out = run(home, Map.of(), "apply_staged_jre");

        assertTrue(out.contains("Java runtime: kept the current Java: there is no built-in Java at " + home + "/jre to replace"), out);
        assertFalse(Files.exists(home.resolve("jre")));
        assertFalse(Files.exists(home.resolve("jre.staged")));
    }

    @Test
    void trailingZeroPartsDropSoTheCatalogsVersionMatchesJavas() throws Exception {
        var home = install();
        assertEquals("25\n25.0.10\n25.0.4\n25.0.4.1\n",
                run(home, Map.of(), "jre_version_norm 25.0.0; jre_version_norm 25.0.10; jre_version_norm 25.0.4.0; jre_version_norm 25.0.4.1"));
    }

    @Test
    void theStartSwapsTheRuntimeAfterItsChecksAndBeforeTheJvmRuns() throws Exception {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        int start = script.indexOf("\ndo_start_prod() {\n");
        int portCheck = script.indexOf("    if port_answers \"$BACKEND_PORT\"; then\n", start);
        int apply = script.indexOf("    apply_staged_jre\n", start);
        int train = script.indexOf("    aot_prepare --http.port=\"$BACKEND_PORT\" \"${jvm_opts[@]}\"\n", start);
        assertTrue(start > 0 && portCheck > start && apply > portCheck && train > apply,
                "do_start_prod applies a staged runtime after the running and port checks, before the AOT cache and the JVM");
        int defined = script.indexOf("\nuse_bundled_jre() {\n");
        assertTrue(defined > 0 && script.indexOf("\nuse_bundled_jre\n") > defined, "the top level resolves the built-in JRE");
    }

    // --- helpers ---

    private void assertKept(Path home, String out, String line) throws IOException {
        assertTrue(out.contains("    Java runtime: " + line + "\n"), out);
        assertEquals(line, status(home));
        assertEquals(List.of(OLD_TOP), listing(home.resolve("jre")));
        assertTrue(Files.readString(home.resolve("jre/" + OLD_TOP + HOME_IN_ARCHIVE + "/bin/java")).contains("25.0.4"));
        assertFalse(Files.exists(home.resolve("jre.previous")));
        assertFalse(Files.exists(home.resolve("jre.new")));
        assertFalse(Files.exists(home.resolve("jre.staged")), "a staging that failed is dropped, not retried");
    }

    /** {@code <home>/jclaw} with the jvm.memory module flag, and {@code <home>/jre} on Java 25.0.4. */
    private Path install() throws IOException {
        var home = tmp.resolve("home");
        Files.createDirectories(home.resolve("jclaw/conf"));
        Files.writeString(home.resolve("jclaw/conf/application.conf"), "jvm.memory=--add-modules=jdk.incubator.vector\n");
        for (var f : runtime(OLD_TOP, "25.0.4", true).entrySet()) {
            var path = home.resolve("jre/" + f.getKey());
            Files.createDirectories(path.getParent());
            if (f.getKey().endsWith("/bin/java")) executable(path, f.getValue());
            else Files.writeString(path, f.getValue());
        }
        return home;
    }

    private static Map<String, String> runtime(String top, String version, boolean hasModule) {
        var files = new LinkedHashMap<String, String>();
        files.put(top + HOME_IN_ARCHIVE + "/release", "JAVA_VERSION=\"" + version + "\"\n");
        files.put(top + HOME_IN_ARCHIVE + "/bin/java", """
                #!/bin/sh
                for a in "$@"; do
                    case "$a" in
                        --add-modules=*) if [ "%s" = no ]; then echo "java.lang.module.FindException: Module jdk.incubator.vector not found" >&2; exit 1; fi ;;
                    esac
                done
                echo "openjdk version \\"%s\\" 2026-10-20 LTS" >&2
                """.formatted(hasModule ? "yes" : "no", version));
        return files;
    }

    private static void stage(Path home, String archive, byte[] bytes) throws Exception {
        stage(home, archive, "25.0.5", bytes);
    }

    private static void stage(Path home, String archive, String version, byte[] bytes) throws Exception {
        var dir = home.resolve("jre.staged");
        deleteTree(dir);
        Files.createDirectories(dir);
        Files.write(dir.resolve(archive), bytes);
        Files.writeString(dir.resolve("manifest"), "version=" + version + "\nsha256=" + sha(bytes) + "\narchive=" + archive + "\n");
    }

    private static byte[] tarGz(Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            for (var f : files.entrySet()) {
                var data = f.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(f.getKey());
                entry.setSize(data.length);
                entry.setMode(f.getKey().endsWith("/bin/java") ? 0100755 : 0100644);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(bytes)) {
            for (var f : files.entrySet()) {
                var entry = new ZipArchiveEntry(f.getKey());
                entry.setUnixMode(f.getKey().endsWith("/bin/java") ? 0100755 : 0100644);
                zip.putArchiveEntry(entry);
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String run(Path home, Map<String, String> env, String commands) throws Exception {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\nSCRIPT_DIR='" + home.resolve("jclaw")
                + "'\nIS_WINDOWS=" + env.getOrDefault("IS_WINDOWS", "0") + "\n");
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append(commands).append('\n');
        var pb = new ProcessBuilder("/bin/bash", "-c", harness.toString()).redirectErrorStream(true);
        var penv = pb.environment();
        penv.remove("JCLAW_JRE_SKIP");
        penv.remove("SYSTEMROOT");
        penv.put("STUB_CALLS", tmp.resolve("calls.log").toString());
        penv.put("PATH", stubs() + File.pathSeparator + System.getenv("PATH"));
        for (var e : env.entrySet()) {
            if (e.getKey().equals("PATH")) penv.put("PATH", stubs() + File.pathSeparator + e.getValue());
            else if (!e.getKey().equals("IS_WINDOWS")) penv.put(e.getKey(), e.getValue());
        }
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the harness hung");
        assertEquals(0, p.exitValue(), () -> "the harness exited non-zero:\n" + out);
        return out;
    }

    /** cygpath for the Windows branch: Git Bash's paths are already this test's paths. */
    private Path stubs() throws IOException {
        var bin = tmp.resolve("stubs");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            executable(bin.resolve("cygpath"), "#!/bin/sh\neval \"last=\\${$#}\"\nprintf '%s\\n' \"$last\"\n");
        }
        return bin;
    }

    /** A PATH holding links to the tools these functions use, except {@code missing}. */
    private String toolsWithout(String missing) throws IOException {
        var bin = Files.createDirectories(tmp.resolve("tools"));
        for (var tool : List.of("find", "grep", "head", "sed", "mkdir", "rm", "mv", "tar", "gzip", "shasum",
                "sha256sum", "awk", "dirname", "cat", "chmod", "perl", "unzip")) {
            var found = which(tool);
            if (found != null && !tool.equals(missing) && !Files.exists(bin.resolve(tool))) {
                Files.createSymbolicLink(bin.resolve(tool), found);
            }
        }
        return bin.toString();
    }

    private static Path which(String tool) {
        for (var dir : System.getenv("PATH").split(File.pathSeparator)) {
            var candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private List<String> calls() throws IOException {
        var log = tmp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static String status(Path home) throws IOException {
        return Files.readString(home.resolve("jclaw/data/java-runtime.status")).strip();
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static Path repo() {
        return Path.of(Play.applicationPath.getAbsolutePath());
    }
}
