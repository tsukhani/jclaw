import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import tools.HarnessSandbox;
import tools.ShellExecTool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JF-302: with the network allowed, a custom agent's {@code exec} still reaches no local service that runs
 * outside its sandbox. A socket file takes its connection only in its workspace and its own temp folder, and
 * on macOS LaunchServices starts no app for it. {@code main}'s {@code exec} makes the same attempt as the
 * control, and every server and app here is a throwaway the test makes, never a real service's.
 */
class SandboxLocalServiceTest extends UnitTest {

    private static final String AGENT = "sock-agent";
    private static final String CURL = OS.MAC.isCurrentOs() ? "/usr/bin/curl" : "curl";
    /** sun_path holds 104 bytes on macOS and 108 on Linux, the terminating NUL included. */
    private static final int LONGEST_SOCKET_PATH = 103;

    private ShellExecTool tool;
    private Agent agent;
    private final List<Path> scratch = new ArrayList<>();

    @BeforeEach
    void setup() {
        ShellSandboxSync.acquire();
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        tool = new ShellExecTool();
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
        ConfigService.set("shell.allowlist", "curl,echo,cat,open,dscacheutil,mktemp");
    }

    @AfterEach
    void teardown() {
        try {
            scratch.forEach(SandboxLocalServiceTest::deleteDir);
        } finally {
            ShellSandboxSync.release();
        }
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath(AGENT));
        deleteDir(HarnessSandbox.customAgentTempDir(AgentService.workspacePath(AGENT).toFile()));
    }

    /** A throwaway server that answers every connection with one HTTP response and counts them. */
    private static final class Server implements AutoCloseable {
        private final ServerSocketChannel channel;
        private final Path socket;
        private final AtomicInteger accepted = new AtomicInteger();

        private Server(ServerSocketChannel channel, Path socket, String body) {
            this.channel = channel;
            this.socket = socket;
            var response = ("HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
                    .formatted(body.length(), body)).getBytes(StandardCharsets.US_ASCII);
            Thread.ofPlatform().daemon().name("jf302-server").start(() -> {
                while (true) {
                    try (var client = channel.accept()) {
                        accepted.incrementAndGet();
                        client.read(ByteBuffer.allocate(4096));
                        client.write(ByteBuffer.wrap(response));
                    } catch (IOException _) {
                        return;
                    }
                }
            });
        }

        static Server atSocket(Path socket) throws IOException {
            var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.bind(UnixDomainSocketAddress.of(socket));
            } catch (IOException e) {
                channel.close();
                throw e;
            }
            return new Server(channel, socket, "answered " + socket.getFileName() + "\n");
        }

        static Server onLoopback() throws IOException {
            var channel = ServerSocketChannel.open();
            try {
                channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            } catch (IOException e) {
                channel.close();
                throw e;
            }
            return new Server(channel, null, "connected\n");
        }

        int accepted() {
            return accepted.get();
        }

        int port() throws IOException {
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }

        @Override
        public void close() throws IOException {
            channel.close();
            if (socket != null) Files.deleteIfExists(socket);
        }
    }

    /** {@code main}, unsaved: {@code Fixtures.deleteDatabase} removed its row, and it runs {@code exec} unconfined. */
    private static Agent main() {
        var main = new Agent();
        main.id = Long.MAX_VALUE;
        main.name = Agent.MAIN_AGENT_NAME;
        return main;
    }

    private JsonObject run(Agent who, String command) {
        var args = new JsonObject();
        args.addProperty("command", command);
        return JsonParser.parseString(tool.execute(args.toString(), who)).getAsJsonObject();
    }

    private static List<String> lines(JsonObject result) {
        return result.get("output").getAsString().lines().toList();
    }

    private static String connectTo(Path socket) {
        return "%s -s --max-time 5 --unix-socket '%s' http://probe/; echo \"exit=$?\"".formatted(CURL, socket);
    }

    private static List<String> answeredBy(Path socket) {
        return List.of("answered " + socket.getFileName(), "exit=0");
    }

    /** curl's exit 7 is a connection it could not open; {@code -s} keeps its message, which differs by version, out. */
    private static final List<String> REFUSED = List.of("exit=7");

    private static void assumeWorkingSandbox() {
        var availability = HarnessSandbox.availability();
        assumeTrue(availability.available(), "no working sandbox here: " + availability.reason());
    }

    private void assumeCurlInsideTheSandbox() {
        var version = run(agent, CURL + " --version");
        assumeTrue(version.get("exitCode").getAsInt() == 0, "curl does not run inside the sandbox here: " + version);
    }

    private Path scratchDir(Path parent, String prefix) throws IOException {
        var dir = Files.createDirectory(parent.resolve(prefix + UUID.randomUUID().toString().substring(0, 8)));
        scratch.add(dir);
        return dir;
    }

    /**
     * Where a container runtime keeps its control socket, each in a throwaway folder: on macOS the shared temp
     * folder (a Podman machine's) and the home folder outside the workspace (Docker Desktop's and Colima's); on
     * Linux a runtime folder under {@code /run} (Docker's and Podman's) and the home folder.
     */
    private List<Path> whereARuntimeKeepsItsSocket() throws IOException {
        var home = Path.of(System.getProperty("user.home"));
        var out = new ArrayList<Path>();
        if (OS.MAC.isCurrentOs()) {
            out.add(scratchDir(Path.of(System.getProperty("java.io.tmpdir")), "jf302-").resolve("podman.sock"));
        } else {
            var uid = new com.sun.security.auth.module.UnixSystem().getUid();
            var runtime = List.of(Path.of("/run/user/" + uid), Path.of("/run")).stream()
                    .filter(dir -> Files.isDirectory(dir) && Files.isWritable(dir)).findFirst();
            assumeTrue(runtime.isPresent(), "no folder under /run that this user can put a socket in");
            out.add(scratchDir(runtime.get(), "jf302-").resolve("docker.sock"));
        }
        out.add(scratchDir(home, "jclaw-jf302-").resolve("docker.sock"));
        return out;
    }

    /**
     * The gap: the sandbox bounds files, and a socket file outside the workspace still took a connection. Each
     * attempt is refused before it reaches the server, and main's unconfined exec then reaches that same server.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentCannotConnectToASocketWhereAContainerRuntimeKeepsIts() throws Exception {
        assumeWorkingSandbox();
        assumeCurlInsideTheSandbox();
        for (var socket : whereARuntimeKeepsItsSocket()) {
            try (var server = Server.atSocket(socket)) {
                var confined = run(agent, connectTo(socket));
                assertEquals(REFUSED, lines(confined), socket + ": " + confined);
                assertEquals(0, server.accepted(), "no connection may reach the server at " + socket);

                var control = run(main(), connectTo(socket));
                assertEquals(answeredBy(socket), lines(control),
                        "unconfined, the same connection must be answered or the test proves nothing: " + control);
                assertEquals(1, server.accepted(), socket.toString());
            }
        }
    }

    /** A socket in the workspace is the agent's own: a server it starts there, or one started for it, stays reachable. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentConnectsToASocketInItsWorkspace() throws Exception {
        assumeWorkingSandbox();
        assumeCurlInsideTheSandbox();
        var socket = AgentService.workspacePath(AGENT).toAbsolutePath().resolve("own.sock");
        assumeTrue(socket.toString().length() <= LONGEST_SOCKET_PATH, "this checkout's workspace path is too long for a socket: " + socket);
        try (var server = Server.atSocket(socket)) {
            var confined = run(agent, connectTo(socket));
            assertEquals(answeredBy(socket), lines(confined), confined.toString());
            assertEquals(1, server.accepted());
        }
    }

    /**
     * On macOS the temp folder is shared with every program the operator runs, so the run gets one of its own
     * and TMPDIR names it: a tool that keeps a socket beside its temp files still reaches it, and a temp file
     * is still made.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacACustomAgentsTempFolderIsItsOwnAndASocketInItConnects() throws Exception {
        assumeWorkingSandbox();
        var shared = Path.of(System.getProperty("java.io.tmpdir"));
        var expected = HarnessSandbox.customAgentTempDir(AgentService.workspacePath(AGENT).toFile());

        // A template under $TMPDIR: without one, macOS mktemp goes to the user's temp folder whatever TMPDIR says.
        var made = run(agent, "echo \"$TMPDIR\"; f=$(mktemp \"$TMPDIR/t.XXXXXX\") && echo \"made ${f%/*}\"");
        assertEquals(List.of(expected.toString(), "made " + expected), lines(made), made.toString());
        assertEquals(shared.toRealPath(), expected.toRealPath().getParent(), "the folder sits in this user's temp folder");
        assertNotEquals(shared.toRealPath(), expected.toRealPath());

        var args = new JsonObject();
        args.addProperty("command", "echo \"$TMPDIR\"");
        var env = new JsonObject();
        env.addProperty("TMPDIR", "/private/tmp");
        args.add("env", env);
        var passed = JsonParser.parseString(tool.execute(args.toString(), agent)).getAsJsonObject();
        assertEquals(expected.toString(), passed.get("output").getAsString(),
                "a TMPDIR the model passes must not point the run's tools back at a shared folder: " + passed);

        var socket = expected.resolve("own.sock");
        try (var server = Server.atSocket(socket)) {
            var confined = run(agent, connectTo(socket));
            assertEquals(answeredBy(socket), lines(confined), confined.toString());
            assertEquals(1, server.accepted());
        }
    }

    /**
     * macOS resolves names through mDNSResponder's socket, the one socket outside the run's folders its rule
     * keeps. This host's own Bonjour name needs no network, and resolves inside exactly as it does outside.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacANameStillResolvesInsideTheSandbox() throws Exception {
        assumeWorkingSandbox();
        var scutil = new ProcessBuilder("/usr/sbin/scutil", "--get", "LocalHostName").redirectErrorStream(true).start();
        var host = new String(scutil.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assumeTrue(scutil.waitFor(10, TimeUnit.SECONDS) && scutil.exitValue() == 0 && host.matches("[A-Za-z0-9-]+"),
                "this host has no Bonjour name: " + host);
        var lookup = "/usr/bin/dscacheutil -q host -a name %s.local".formatted(host);

        var control = new TreeSet<>(lines(run(main(), lookup)));
        assumeTrue(control.stream().anyMatch(line -> line.contains("address: ")),
                "outside the sandbox this host's own name does not resolve either: " + control);

        assertEquals(control, new TreeSet<>(lines(run(agent, lookup))));
    }

    /** An outbound connection still opens, here to a loopback server, and on Linux the resolver's file is still read. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aCustomAgentStillConnectsOutAndReadsTheResolverFile() throws Exception {
        assumeWorkingSandbox();
        assumeCurlInsideTheSandbox();
        try (var server = Server.onLoopback()) {
            var confined = run(agent, "%s -s --max-time 5 http://127.0.0.1:%d/; echo \"exit=$?\"".formatted(CURL, server.port()));
            assertEquals(List.of("connected", "exit=0"), lines(confined), confined.toString());
            assertEquals(1, server.accepted());
        }
        var resolver = Path.of("/etc/resolv.conf");
        if (OS.LINUX.isCurrentOs() && Files.isReadable(resolver)) {
            var inside = run(agent, "cat /etc/resolv.conf");
            assertEquals(Files.readString(resolver).strip(), inside.get("output").getAsString().strip(), inside.toString());
        }
    }

    /**
     * With the network denied a run reaches no socket at all, as before: the rule that keeps the workspace's
     * sockets reachable must not reopen them under deny.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacNetworkDenyStillRefusesASocketInTheWorkspace() throws Exception {
        assumeWorkingSandbox();
        var socket = AgentService.workspacePath(AGENT).toAbsolutePath().resolve("own.sock");
        assumeTrue(socket.toString().length() <= LONGEST_SOCKET_PATH, "this checkout's workspace path is too long for a socket: " + socket);
        try (var server = Server.atSocket(socket)) {
            // Set right before the run: another class's table wipe can clear a key.
            ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, "deny");
            var confined = run(agent, connectTo(socket));
            assertEquals(REFUSED, lines(confined), confined.toString());
            assertEquals(0, server.accepted());
        }
    }

    /** The rule is a custom agent's alone: main confined under shell.sandbox=true still reaches a socket outside its workspace. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void mainConfinedUnderTheKeyStillConnectsToASocketOutsideItsWorkspace() throws Exception {
        assumeWorkingSandbox();
        var socket = whereARuntimeKeepsItsSocket().getFirst();
        try (var server = Server.atSocket(socket)) {
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
            var confined = run(main(), connectTo(socket));
            assertEquals(answeredBy(socket), lines(confined), confined.toString());
            assertEquals(1, server.accepted());
        }
    }

    /**
     * LaunchServices starts an app outside the sandbox for whoever asks, so an app the run wrote would run with
     * none of its rules. The throwaway app here writes a file the sandbox cannot write; main's exec starts it
     * first, which also tells a host where LaunchServices starts nothing (no desktop session) from a refusal.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void onMacACustomAgentCannotHaveLaunchServicesStartAnApp() throws Exception {
        assumeWorkingSandbox();
        var id = UUID.randomUUID().toString().substring(0, 8);
        var marker = Path.of(System.getProperty("user.home"), "jclaw-jf302-" + id + ".txt");
        var app = AgentService.workspacePath(AGENT).toAbsolutePath().resolve("Jf302Probe" + id + ".app");
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(app.resolve("Contents/Info.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                <key>CFBundleExecutable</key><string>probe</string>
                <key>CFBundleIdentifier</key><string>com.jclaw.jf302.probe%s</string>
                <key>CFBundleName</key><string>Jf302Probe</string>
                <key>CFBundlePackageType</key><string>APPL</string>
                <key>LSUIElement</key><true/><key>LSBackgroundOnly</key><true/>
                </dict></plist>
                """.formatted(id));
        var executable = Files.writeString(app.resolve("Contents/MacOS/probe"), "#!/bin/sh\n/usr/bin/touch '%s'\n".formatted(marker));
        assertTrue(executable.toFile().setExecutable(true));
        var open = "/usr/bin/open -g -j -n '%s' 2>/dev/null; echo \"exit=$?\"".formatted(app);
        try {
            var control = run(main(), open);
            assumeTrue(lines(control).equals(List.of("exit=0")) && appearsWithin(marker, 15_000),
                    "LaunchServices starts no app for this process, so a refusal would prove nothing: " + control);
            Files.delete(marker);

            var confined = run(agent, open);
            // Waited for before either assertion, so a launch that did happen leaves no file behind the finally.
            var ran = appearsWithin(marker, 3_000);
            assertEquals(List.of("exit=1"), lines(confined), confined.toString());
            assertFalse(ran, "the app must not have run outside the sandbox");
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    private static boolean appearsWithin(Path file, long millis) throws InterruptedException {
        for (long waited = 0; waited < millis; waited += 100) {
            if (Files.exists(file)) return true;
            Thread.sleep(100);
        }
        return Files.exists(file);
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
