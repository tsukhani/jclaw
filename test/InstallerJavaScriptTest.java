import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs install.sh's {@code check_java} under {@code /bin/sh}, as {@code curl … | sh} does, with stub
 * {@code java}s for the host and a stub {@code curl} serving Azul's catalog and a stub Zulu JRE, so each
 * way an install picks the Java it runs on is tested as written: a fresh install gets the Zulu JRE in
 * {@code $JCLAW_HOME/jre} even beside a usable host Java, and the host's Java is used only when asked
 * for, where Azul has no JRE, or by an upgrade.
 */
class InstallerJavaScriptTest extends UnitTest {

    private static final List<String> VARIABLES = List.of("JCLAW_HOME", "JCLAW_NO_JRE", "JCLAW_INSTALL_JRE",
            "JCLAW_FORCE_REINSTALL", "APP_DIR", "JRE_DIR", "MIN_JAVA", "JAVA_MODULE", "AZUL_API", "TTY");
    private static final List<String> FUNCTIONS = List.of("step", "substep", "warn", "die", "check_java", "upgrading",
            "java_ok", "want_jre", "provision_jre", "verify_sha256", "http_get", "java_missing", "download");

    private static final String TOP = "zulu25.38.107-ca-jre25.0.5-linux_x64";
    private static final String TARBALL = TOP + ".tar.gz";
    private static final String CATALOG_QUERY = "https://api.azul.com/metadata/v1/zulu/packages/?java_version=25&os=linux"
            + "&arch=x64&archive_type=tar.gz&java_package_type=jre&release_status=ga&include_fields=sha256_hash&page_size=20";

    @TempDir Path tmp;
    private byte[] jre;

    record Result(int exit, String out) {}

    @Test
    void aFreshInstallBesideAUsableJava25RunsOnTheZuluJreItDownloads() throws Exception {
        var host = hostJava("25.0.3");

        var r = install(host, Map.of());

        assertEquals(new Result(0, """
                    Found Java 25 at %1$s/java, but JClaw runs on a Zulu JRE 25 of its own, which it keeps patched. \
                Auto-installing Zulu JRE 25 (set JCLAW_NO_JRE=1 to use your own Java)
                ==> Installing Zulu JRE 25 (linux/x64)
                    %2$s
                    checksum verified (sha256)
                    installed → %3$s
                    Java 25 ready (managed JRE)
                java=%3$s/bin/java
                """.formatted(host, TARBALL, managedHome())), r);
        assertEquals(List.of("curl -fsSL " + CATALOG_QUERY,
                "curl -fL --progress-bar -o " + tmp + "/dl/jclaw-jre.X/jre.tar.gz https://cdn.azul.com/zulu/bin/" + TARBALL),
                calls().stream().map(c -> c.replaceAll("jclaw-jre\\.[^/]+", "jclaw-jre.X")).toList());
    }

    @Test
    void aFreshInstallOnAHostWhoseOnlyJavaIs27RunsOnTheZuluJre25() throws Exception {
        var host = hostJava("27.0.1");

        var r = install(host, Map.of());

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().startsWith("    Found Java 27 at " + host + "/java, but JClaw runs on a Zulu JRE 25 of its own"), r.out());
        assertTrue(r.out().endsWith("    Java 25 ready (managed JRE)\njava=" + managedHome() + "/bin/java\n"), r.out());
    }

    @Test
    void withJclawNoJreTheInstallRunsOnTheHostsJavaAndDownloadsNothing() throws Exception {
        var host = hostJava("25.0.3");

        assertEquals(new Result(0, "    Java 25 detected (" + host + "/java)\njava=" + host + "/java\n"),
                install(host, Map.of("JCLAW_NO_JRE", "1")));
        assertEquals(List.of(), calls());
        assertFalse(Files.exists(tmp.resolve("home/jre")));
    }

    @Test
    void withJclawNoJreAndNoUsableJavaTheInstallStopsAsBefore() throws Exception {
        var r = install(null, Map.of("JCLAW_NO_JRE", "1"));

        assertEquals(1, r.exit());
        assertTrue(r.out().startsWith("error: Java was not found on your PATH.\n\nJClaw needs a Java 25+ runtime.\n"), r.out());
        assertEquals(List.of(), calls());
    }

    @Test
    void whereAzulListsNoJreForTheSystemAFreshInstallRunsOnTheHostsJava() throws Exception {
        var host = hostJava("25.0.3");
        catalog("[]");

        var r = install(host, Map.of());

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().endsWith("==> Installing Zulu JRE 25 (linux/x64)\n"
                + "warning: no Zulu JRE 25 found for linux/x64 in the Azul catalog, so JClaw runs on Java 25 at " + host + "/java.\n"
                + "java=" + host + "/java\n"), r.out());
        assertEquals(List.of("curl -fsSL " + CATALOG_QUERY), calls());
        assertFalse(Files.exists(tmp.resolve("home/jre")));
    }

    @Test
    void onACpuAzulBuildsNoJreForAFreshInstallRunsOnTheHostsJavaWithoutAsking() throws Exception {
        var host = hostJava("25.0.3");

        assertEquals(new Result(0, "    Java 25 detected (" + host + "/java)\njava=" + host + "/java\n"),
                install(host, Map.of("ARCH", "")));
        assertEquals(List.of(), calls());
    }

    @Test
    void anUnreachableCatalogLeavesAFreshInstallOnAUsableHostJava() throws Exception {
        var host = hostJava("25.0.3");

        var r = install(host, Map.of("CATALOG_DOWN", "1"));

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().endsWith("warning: couldn't reach the Azul JRE catalog, so JClaw runs on Java 25 at " + host
                + "/java.\njava=" + host + "/java\n"), r.out());
    }

    @Test
    void withNoUsableJavaAndNoJreForTheSystemTheInstallStopsAndSaysWhy() throws Exception {
        catalog("[]");

        var r = install(null, Map.of());

        assertEquals(1, r.exit());
        assertTrue(r.out().endsWith("error: no Zulu JRE 25 found for linux/x64 in the Azul catalog — "
                + "install Java 25 yourself and re-run.\n"), r.out());
    }

    @Test
    void aDownloadThatFailsItsChecksumStopsTheInstallEvenBesideAUsableJava() throws Exception {
        var host = hostJava("25.0.3");
        catalog(catalogFor("0".repeat(64)));

        var r = install(host, Map.of());

        assertEquals(1, r.exit());
        assertTrue(r.out().endsWith("error: JRE checksum mismatch (wanted " + "0".repeat(64) + ", got "
                + sha(jreTarball()) + ") — aborting.\n"), r.out());
    }

    @Test
    void anUpgradeKeepsTheHostsJavaItRunsOn() throws Exception {
        var host = hostJava("25.0.3");
        existingInstall();

        assertEquals(new Result(0, "    Java 25 detected (" + host + "/java)\njava=" + host + "/java\n"), install(host, Map.of()));
        assertEquals(List.of(), calls());
    }

    @Test
    void anUpgradeWithNoUsableJavaStillGetsTheZuluJre() throws Exception {
        existingInstall();

        var r = install(null, Map.of());

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().startsWith("    Java was not found on your PATH. Auto-installing Zulu JRE 25"), r.out());
        assertTrue(r.out().endsWith("java=" + managedHome() + "/bin/java\n"), r.out());
    }

    @Test
    void aForcedReinstallIsAFreshInstall() throws Exception {
        var host = hostJava("25.0.3");
        existingInstall();

        var r = install(host, Map.of("JCLAW_FORCE_REINSTALL", "1"));

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().endsWith("java=" + managedHome() + "/bin/java\n"), r.out());
    }

    @Test
    void answeringNoAtTheTerminalKeepsTheHostsJava() throws Exception {
        var host = hostJava("25.0.3");
        var tty = tmp.resolve("tty");
        Files.writeString(tty, "n\n");

        var r = install(host, Map.of("JCLAW_TTY", tty.toString()));

        assertEquals(new Result(0, "    Java 25 detected (" + host + "/java)\njava=" + host + "/java\n"), r);
        assertEquals("n\n\nFound Java 25 at " + host + "/java, but JClaw runs on a Zulu JRE 25 of its own, which it keeps "
                + "patched.\nDownload a self-contained Zulu JRE 25 (~50 MB) into " + tmp.resolve("home/jre") + "? [Y/n] ",
                Files.readString(tty));
        assertEquals(List.of(), calls());
    }

    // --- helpers ---

    private Result install(Path hostJava, Map<String, String> env) throws Exception {
        var script = Files.readString(repo().resolve("install.sh"));
        var harness = new StringBuilder("set -eu\nEMERALD=''; CYAN=''; YELLOW=''; RED=''; DIM=''; BOLD=''; RESET=''\n"
                + "TMP_JRE=''\nOS=linux\nAZUL_OS=linux\nARCH=" + env.getOrDefault("ARCH", "x64") + "\n");
        for (var name : VARIABLES) {
            var m = Pattern.compile("(?m)^" + Pattern.quote(name) + "=.*\\n").matcher(script);
            assertTrue(m.find(), "install.sh no longer sets " + name);
            harness.append(m.group());
        }
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) *\\{(?: [^\\n]*\\}\\n|\\n.*?^\\}\\n)").matcher(script);
            assertTrue(m.find(), "install.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append("check_java\necho \"java=$(command -v java)\"\n");

        if (!Files.exists(tmp.resolve("catalog.json"))) catalog(catalogFor(sha(jreTarball())));
        Files.write(tmp.resolve(TARBALL), jreTarball());
        var pb = new ProcessBuilder("/bin/sh", "-c", harness.toString()).redirectErrorStream(true);
        var penv = pb.environment();
        for (var key : List.of("JAVA_HOME", "JCLAW_NO_JRE", "JCLAW_INSTALL_JRE", "JCLAW_FORCE_REINSTALL", "NO_COLOR")) {
            penv.remove(key);
        }
        penv.put("HOME", tmp.toString());
        penv.put("JCLAW_HOME", tmp.resolve("home").toString());
        penv.put("JCLAW_TTY", tmp.resolve("no-tty/tty").toString());
        penv.put("TMPDIR", Files.createDirectories(tmp.resolve("dl")).toString());
        penv.put("STUB_CALLS", tmp.resolve("calls.log").toString());
        penv.put("CATALOG", tmp.resolve("catalog.json").toString());
        penv.put("JRE_TARBALL", tmp.resolve(TARBALL).toString());
        penv.put("PATH", stubs() + (hostJava == null ? "" : File.pathSeparator + hostJava) + File.pathSeparator + tools());
        var rest = new HashMap<>(env);
        rest.remove("ARCH");
        penv.putAll(rest);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the harness hung");
        return new Result(p.exitValue(), out);
    }

    private String managedHome() {
        return tmp.resolve("home/jre/" + TOP).toString();
    }

    /** An install the installer would hand to {@code jclaw.sh upgrade}. */
    private void existingInstall() throws IOException {
        var app = Files.createDirectories(tmp.resolve("home/jclaw"));
        executable(app.resolve("jclaw.sh"), "#!/bin/sh\ndo_upgrade() { :; }\n");
    }

    private void catalog(String json) throws IOException {
        Files.writeString(tmp.resolve("catalog.json"), json);
    }

    /** Azul's catalog answer, a JavaFX build listed first as the real catalog does. */
    private static String catalogFor(String sha256) {
        return "[{\"name\":\"zulu25.38.107-ca-fx-jre25.0.5-linux_x64.tar.gz\",\"download_url\":"
                + "\"https://cdn.azul.com/zulu/bin/zulu25.38.107-ca-fx-jre25.0.5-linux_x64.tar.gz\",\"sha256_hash\":\""
                + "f".repeat(64) + "\"},\n {\"name\":\"" + TARBALL + "\",\"download_url\":\"https://cdn.azul.com/zulu/bin/"
                + TARBALL + "\",\"sha256_hash\":\"" + sha256 + "\"}]";
    }

    /** A directory holding a {@code java} that reports {@code version} and has jdk.incubator.vector. */
    private Path hostJava(String version) throws IOException {
        var bin = Files.createDirectories(tmp.resolve("host-" + version + "/bin"));
        executable(bin.resolve("java"), javaScript(version));
        return bin;
    }

    private static String javaScript(String version) {
        return """
                #!/bin/sh
                echo "openjdk version \\"%s\\" 2026-10-20 LTS" >&2
                """.formatted(version);
    }

    /** The Zulu JRE the stub CDN serves: Linux's flat layout, reporting 25.0.5. Built once, since a tar entry carries the time. */
    private byte[] jreTarball() throws IOException {
        if (jre != null) return jre;
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            for (var f : Map.of(TOP + "/release", "JAVA_VERSION=\"25.0.5\"\n", TOP + "/bin/java", javaScript("25.0.5")).entrySet()) {
                var data = f.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(f.getKey());
                entry.setSize(data.length);
                entry.setMode(f.getKey().endsWith("/bin/java") ? 0100755 : 0100644);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        jre = bytes.toByteArray();
        return jre;
    }

    /** curl for Azul's catalog and CDN only; every call is logged. */
    private Path stubs() throws IOException {
        var bin = tmp.resolve("stubs");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            executable(bin.resolve("curl"), """
                    #!/bin/sh
                    echo "curl $*" >> "$STUB_CALLS"
                    out=''; url=''
                    while [ $# -gt 0 ]; do
                        case "$1" in
                            -o) out="$2"; shift 2 ;;
                            -*) shift ;;
                            *) url="$1"; shift ;;
                        esac
                    done
                    case "$url" in
                        https://api.azul.com/*) [ -z "${CATALOG_DOWN:-}" ] || exit 7; cat "$CATALOG" ;;
                        https://cdn.azul.com/*) cp "$JRE_TARBALL" "$out" ;;
                        *) exit 22 ;;
                    esac
                    """);
        }
        return bin;
    }

    /** Links to the tools install.sh's Java check uses, and no {@code java}. */
    private String tools() throws IOException {
        var bin = tmp.resolve("tools");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            for (var tool : List.of("sed", "grep", "tr", "head", "cat", "cp", "rm", "mkdir", "mktemp", "tar", "gzip",
                    "find", "dirname", "basename", "awk", "shasum", "sha256sum", "perl")) {
                var found = which(tool);
                if (found != null) Files.createSymbolicLink(bin.resolve(tool), found);
            }
        }
        return bin.toString();
    }

    private List<String> calls() throws IOException {
        var log = tmp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static Path which(String tool) {
        for (var dir : System.getenv("PATH").split(File.pathSeparator)) {
            var candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static Path repo() {
        return Path.of(Play.applicationPath.getAbsolutePath());
    }
}
