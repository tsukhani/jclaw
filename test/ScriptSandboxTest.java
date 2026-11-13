import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import play.Play;
import play.test.UnitTest;
import services.browser.PlaywrightDriverDir;
import tools.HarnessSandbox;
import tools.ScriptProcess;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JF-380: a script's interpreter, started by {@link ScriptProcess} inside {@link HarnessSandbox#wrapScript}, has no
 * network, writes only to its scratch folder and reads, of JClaw's folders and the home folder, only its own
 * interpreter folder. Every probe runs twice: unconfined as the control, which shows each path and server is really
 * there, then confined. The Node binary is one already on disk, copied into a throwaway version folder under the
 * app's data/, the place the sandbox must reopen and nothing beside it.
 */
class ScriptSandboxTest extends UnitTest {

    private final List<Path> cleanup = new ArrayList<>();
    private Path scratch;

    @BeforeEach
    void setup() throws IOException {
        scratch = track(Files.createTempDirectory("jf380-scratch-"));
    }

    @AfterEach
    void teardown() throws IOException {
        for (var path : cleanup.reversed()) deleteTree(path);
    }

    /** Where no sandbox starts, the run is refused and nothing is started: no process, nothing written. */
    @Test
    void withNoWorkingSandboxNothingIsStarted() throws Exception {
        var interpreter = track(Files.createTempDirectory("jf380-node-"));
        ShellSandboxSync.acquire();
        try {
            HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.unavailable("test: no sandbox"));
            var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class, () -> ScriptProcess.start(
                    List.of("/bin/sh", "-c", "echo started > started.txt"), scratch, interpreter));
            assertEquals("test: no sandbox", e.getMessage());
        } finally {
            ShellSandboxSync.release();
        }
        try (var entries = Files.list(scratch)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    /**
     * Confined, the script reads its own interpreter and its scratch folder and nothing else of data/, certs/, logs/,
     * the home folder or ~/.ssh; writes only to its scratch folder, not to the home folder, the shared temp folders
     * or its interpreter folder; gets EPERM connecting to a loopback server; resolves no host name; and sees no
     * variable but HOME and TMPDIR.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacAScriptReadsOnlyItsInterpreterWritesOnlyItsScratchAndHasNoNetwork() throws Exception {
        var ready = HarnessSandbox.availability();
        assumeTrue(ready.available(), "no working sandbox here: " + ready.reason());
        var found = nodeOnDisk();
        assumeTrue(found != null, "no Node binary on disk: set PLAYWRIGHT_NODEJS_PATH, install the browser driver "
                + "under " + PlaywrightDriverDir.root() + ", or put node on PATH");

        var appRoot = Play.applicationPath.toPath();
        var mine = track(Files.createDirectories(appRoot.resolve("data")).resolve("jf380-" + UUID.randomUUID()));
        var interpreter = Files.createDirectories(mine.resolve("24.22.1"));
        var node = interpreter.resolve("node");
        Files.copy(found, node, StandardCopyOption.COPY_ATTRIBUTES);
        var secret = "jf380-secret-" + UUID.randomUUID();
        var reads = new LinkedHashMap<String, Path>();
        reads.put("data, beside the interpreter folder", Files.writeString(mine.resolve("sibling.txt"), secret));
        for (var dir : List.of("data", "certs", "logs")) {
            reads.put(dir, track(Files.writeString(Files.createDirectories(appRoot.resolve(dir))
                    .resolve("jf380-sentinel-" + UUID.randomUUID() + ".txt"), secret)));
        }
        var home = Path.of(System.getProperty("user.home"));
        var homeFolder = track(Files.createDirectories(home.resolve("jclaw-jf380-" + UUID.randomUUID())));
        reads.put("home", Files.writeString(homeFolder.resolve("hidden.txt"), secret));
        var lists = new LinkedHashMap<String, Path>();
        lists.put("data", appRoot.resolve("data"));
        if (Files.isDirectory(home.resolve(".ssh"))) lists.put("~/.ssh", home.resolve(".ssh"));
        var writes = new LinkedHashMap<String, Path>();
        writes.put("home", homeFolder.resolve("written.txt"));
        writes.put("/private/tmp", track(Path.of("/private/tmp", "jf380-" + UUID.randomUUID() + ".txt")));
        writes.put("the shared temp folder", track(scratch.resolveSibling(scratch.getFileName() + "-beside.txt")));
        writes.put("the interpreter folder", interpreter.resolve("written.txt"));
        var host = bonjourName();

        try (var server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            var port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            var script = probe(Map.of("reads", reads, "lists", lists, "writes", writes, "port", port,
                    "host", host == null ? "" : host, "scratchFile", scratch.resolve("note.txt")));

            var control = runUnconfined(List.of(node.toString(), "-"), script);
            var expectedControl = new LinkedHashMap<String, String>();
            expectedControl.put("interpreter", String.valueOf(Files.size(node)));
            expectedControl.put("write scratch", "wrote");
            expectedControl.put("read scratch", "read");
            reads.keySet().forEach(k -> expectedControl.put("read " + k, "read"));
            lists.keySet().forEach(k -> expectedControl.put("list " + k, "listed"));
            writes.keySet().forEach(k -> expectedControl.put("write " + k, "wrote"));
            expectedControl.put("connect", "connected");
            expectedControl.put("dns", host == null ? "skipped" : "resolved");
            expectedControl.put("env", "HOME=%s,TMPDIR=%1$s".formatted(scratch));
            assertEquals(expectedControl, control, "unconfined, every probe must reach its target or the test proves nothing");
            for (var written : writes.values()) Files.deleteIfExists(written);
            Files.delete(scratch.resolve("note.txt"));

            Process process;
            ShellSandboxSync.acquire();
            try {
                process = ScriptProcess.start(List.of(node.toString(), "-"), scratch, interpreter);
            } finally {
                ShellSandboxSync.release();
            }
            var confined = finish(process, script);
            var expected = new LinkedHashMap<>(expectedControl);
            reads.keySet().forEach(k -> expected.put("read " + k, "EPERM"));
            lists.keySet().forEach(k -> expected.put("list " + k, "EPERM"));
            writes.keySet().forEach(k -> expected.put("write " + k, "EPERM"));
            expected.put("connect", "EPERM");
            if (host != null) expected.put("dns", "ENOTFOUND");
            assertEquals(expected, confined);
            for (var written : writes.values()) assertFalse(Files.exists(written), written + " was written");
            assertEquals("x", Files.readString(scratch.resolve("note.txt")));
        }
    }

    /** Prints one JSON object: what each read, listing, write, connection and name lookup came to. */
    private static String probe(Map<String, Object> paths) {
        return """
                const fs = require('fs'), net = require('net'), dns = require('dns');
                const P = %s;
                const out = {};
                const outcome = f => { try { return f(); } catch (e) { return e.code; } };
                out['interpreter'] = String(fs.readFileSync(process.execPath).length);
                out['write scratch'] = outcome(() => { fs.writeFileSync(P.scratchFile, 'x'); return 'wrote'; });
                out['read scratch'] = outcome(() => { fs.readFileSync(P.scratchFile); return 'read'; });
                for (const [k, p] of Object.entries(P.reads)) out['read ' + k] = outcome(() => { fs.readFileSync(p); return 'read'; });
                for (const [k, p] of Object.entries(P.lists)) out['list ' + k] = outcome(() => { fs.readdirSync(p); return 'listed'; });
                for (const [k, p] of Object.entries(P.writes)) out['write ' + k] = outcome(() => { fs.writeFileSync(p, 'x'); return 'wrote'; });
                // macOS adds __CF_USER_TEXT_ENCODING to a process that loads CoreFoundation, with an empty environment too.
                out['env'] = Object.entries(process.env).filter(([k]) => k !== '__CF_USER_TEXT_ENCODING')
                        .map(([k, v]) => k + '=' + v).sort().join(',');
                const settled = () => { if ('connect' in out && 'dns' in out) console.log(JSON.stringify(out)); };
                const socket = net.connect(P.port, '127.0.0.1');
                socket.on('connect', () => { out['connect'] = 'connected'; socket.destroy(); settled(); });
                socket.on('error', e => { out['connect'] = e.code; settled(); });
                if (P.host) dns.lookup(P.host, e => { out['dns'] = e ? e.code : 'resolved'; settled(); });
                else { out['dns'] = 'skipped'; settled(); }
                """.formatted(new Gson().toJson(stringify(paths)));
    }

    private static Object stringify(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> out.put(String.valueOf(k), stringify(v)));
            return out;
        }
        return value instanceof Path ? value.toString() : value;
    }

    /** The probe run by the same argv, folder and environment, without the sandbox. */
    private Map<String, String> runUnconfined(List<String> argv, String script) throws Exception {
        var pb = new ProcessBuilder(argv).directory(scratch.toFile());
        pb.environment().clear();
        pb.environment().put("HOME", scratch.toString());
        pb.environment().put("TMPDIR", scratch.toString());
        return finish(pb.start(), script);
    }

    private static Map<String, String> finish(Process process, String script) throws Exception {
        try (var stdin = process.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script finished");
        assertEquals(0, process.exitValue(), "stdout=" + out + " stderr=" + err);
        return new Gson().fromJson(out.strip(), new TypeToken<LinkedHashMap<String, String>>() {}.getType());
    }

    /** A Node binary already on disk: PLAYWRIGHT_NODEJS_PATH, then the managed driver folder, then PATH. */
    private static Path nodeOnDisk() throws IOException {
        var candidates = new ArrayList<Path>();
        var pinned = System.getenv("PLAYWRIGHT_NODEJS_PATH");
        if (pinned != null && !pinned.isBlank()) candidates.add(Path.of(pinned));
        if (Files.isDirectory(PlaywrightDriverDir.root())) {
            try (var drivers = Files.list(PlaywrightDriverDir.root())) {
                drivers.sorted().forEach(d -> candidates.add(d.resolve("node")));
            }
        }
        for (var dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty()) candidates.add(Path.of(dir, "node"));
        }
        for (var candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toRealPath();
        }
        return null;
    }

    /** This Mac's own Bonjour name, which resolves without a network, or null when it has none. */
    private static String bonjourName() throws Exception {
        var scutil = new ProcessBuilder("/usr/sbin/scutil", "--get", "LocalHostName").redirectErrorStream(true).start();
        var name = new String(scutil.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return scutil.waitFor(10, TimeUnit.SECONDS) && scutil.exitValue() == 0 && name.matches("[A-Za-z0-9-]+")
                ? name + ".local" : null;
    }

    private Path track(Path path) {
        cleanup.add(path);
        return path;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
