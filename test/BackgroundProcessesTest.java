import agents.ToolContext;
import models.Agent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.BackgroundProcesses;
import services.ConfigService;
import tools.ShellExecTool;
import utils.AppClock;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

class BackgroundProcessesTest extends UnitTest {

    private static final String AGENT = "bg-process-agent";

    private ShellExecTool tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        // shell.sandbox is process-global and read on every exec.
        ShellSandboxSync.acquire();
        Fixtures.deleteDatabase();
        deleteDir(AgentService.workspacePath(AGENT));
        tool = new ShellExecTool();
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
        ConfigService.set("shell.allowlist", "echo,sleep,sh,python3");
    }

    @AfterEach
    void teardown() {
        sleepers("sleep 4751", "sleep 4752", "sleep 4753", "sleep 4754", "sleep 4755", "sleep 4756", "sleep 4757")
                .forEach(ProcessHandle::destroyForcibly);
        ConfigService.delete(BackgroundProcesses.LIFETIME_KEY);
        ShellSandboxSync.release();
    }

    @AfterAll
    static void cleanup() {
        deleteDir(AgentService.workspacePath(AGENT));
    }

    @Test
    void whatACommandLeftRunningIsListedWithTheConversationThatStartedIt() throws Exception {
        execIn(4242L, "sleep 4751 & echo started");
        var entry = awaitListed("sleep 4751");
        assertEquals(4242L, entry.conversationId());
        assertEquals(AGENT, entry.agentName());
        assertTrue(Path.of(entry.outputFile()).getFileName().toString().startsWith("c4242-"), entry.outputFile());
        assertNotNull(entry.started());
    }

    @Test
    void aProcessThatRedirectsItsOwnOutputIsStillFound() throws Exception {
        execIn(null, "sleep 4752 > /dev/null 2>&1 & echo started");
        var entry = awaitListed("sleep 4752");
        assertNull(entry.conversationId());
    }

    @Test
    void stopEndsTheProcessWithItsDescendantsAndNothingElse() throws Exception {
        execIn(1L, "sh -c 'sleep 4753; echo done' & echo started");
        awaitListed("sleep 4753");
        var shell = BackgroundProcesses.list(List.of(agent)).stream()
                .filter(e -> !e.command().endsWith("sleep 4753")).findFirst().orElseThrow();

        assertFalse(BackgroundProcesses.stop(agent, ProcessHandle.current().pid()), "never this JVM");
        assertFalse(BackgroundProcesses.stop(agent, 1), "never a process the workspace did not start");
        assertTrue(BackgroundProcesses.stop(agent, shell.pid()));
        awaitGone("sleep 4753");
    }

    @Test
    void theSweepStopsAProcessOnlyOnceItsLifetimeHasPassed() throws Exception {
        execIn(1L, "sleep 4754 & echo started");
        var started = awaitListed("sleep 4754").started();
        AppClock.runWith(Clock.fixed(started.plus(Duration.ofHours(23)), ZoneOffset.UTC),
                () -> BackgroundProcesses.sweep(List.of(agent)));
        assertEquals(1, sleepers("sleep 4754").size(), "within its 24 hours");

        ConfigService.set(BackgroundProcesses.LIFETIME_KEY, "0");
        AppClock.runWith(Clock.fixed(started.plus(Duration.ofDays(400)), ZoneOffset.UTC),
                () -> BackgroundProcesses.sweep(List.of(agent)));
        assertEquals(1, sleepers("sleep 4754").size(), "0 never stops one");

        ConfigService.delete(BackgroundProcesses.LIFETIME_KEY);
        AppClock.runWith(Clock.fixed(started.plus(Duration.ofHours(25)), ZoneOffset.UTC),
                () -> BackgroundProcesses.sweep(List.of(agent)));
        awaitGone("sleep 4754");
    }

    @Test
    void deletingAConversationStopsOnlyWhatItLeftRunning() throws Exception {
        execIn(5001L, "sleep 4755 & echo started");
        execIn(5002L, "sleep 4756 & echo started");
        awaitListed("sleep 4755");
        awaitListed("sleep 4756");

        BackgroundProcesses.stopForConversations(List.of(BackgroundProcesses.workspaceOf(AGENT)), List.of(5001L));

        awaitGone("sleep 4755");
        assertEquals(1, sleepers("sleep 4756").size(), "another conversation's process keeps running");
    }

    @Test
    void theSweepEmptiesAHeldFileOverTheCapAndDeletesOnlyStaleOnesNothingHolds() throws Exception {
        execIn(1L, "sleep 4757 & echo started");
        var held = Path.of(awaitListed("sleep 4757").outputFile());
        // Past the cap, sparse so it costs no disk.
        try (var raf = new RandomAccessFile(held.toFile(), "rw")) {
            raf.setLength(17L * 1024 * 1024);
        }
        var dir = held.getParent();
        var old = FileTime.from(Instant.now().minus(Duration.ofDays(3)));
        var stale = Files.writeString(dir.resolve(UUID.randomUUID() + ".log"), "old");
        Files.setLastModifiedTime(stale, old);
        var fresh = Files.writeString(dir.resolve(UUID.randomUUID() + ".log"), "new");
        var agentsOwn = Files.writeString(dir.resolve("notes.txt"), "mine");
        Files.setLastModifiedTime(agentsOwn, old);

        BackgroundProcesses.sweep(List.of(agent));

        assertEquals(0, Files.size(held), "a held file over the cap is emptied");
        assertEquals(1, sleepers("sleep 4757").size(), "its writer keeps running");
        assertFalse(Files.exists(stale), "a stale file nothing holds is deleted");
        assertTrue(Files.exists(fresh), "a recent one is kept");
        assertTrue(Files.exists(agentsOwn), "a file exec did not name is never touched");
    }

    @Test
    void linksPlantedInTheOutputFolderAreNeverFollowed() throws Exception {
        var dir = AgentService.workspacePath(AGENT).resolve(".exec");
        Files.createDirectories(dir);
        var stale = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(3)));
        // Past the size cap, sparse so it costs no disk.
        var target = Files.createTempFile("exec-target", ".bin");
        try (var raf = new java.io.RandomAccessFile(target.toFile(), "rw")) {
            raf.write("marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            raf.setLength(17L * 1024 * 1024);
        }
        var symlink = Files.createSymbolicLink(dir.resolve(java.util.UUID.randomUUID() + ".log"), target);
        Files.getFileAttributeView(symlink, java.nio.file.attribute.BasicFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).setTimes(stale, null, null);
        try {
            BackgroundProcesses.sweep(List.of(agent));
            assertEquals(17L * 1024 * 1024, Files.size(target), "the linked file is never emptied");
            try (var in = Files.newInputStream(target)) {
                assertEquals("marker", new String(in.readNBytes(6), java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aServerIsListedWithTheAddressItListensOn() throws Exception {
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        var text = execIn(1L, "python3 -m http.server %d --bind 127.0.0.1 & echo started".formatted(port));
        org.junit.jupiter.api.Assumptions.assumeTrue(text.contains("started"), "python3 is not on this host: " + text);
        var server = "http.server %d --bind 127.0.0.1".formatted(port);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<String> listening = List.of();
            while (listening.isEmpty() && System.nanoTime() < deadline) {
                listening = BackgroundProcesses.list(List.of(agent)).stream()
                        .filter(e -> e.command().endsWith(server)).findFirst()
                        .map(BackgroundProcesses.Entry::listening).orElse(List.of());
                if (listening.isEmpty()) Thread.sleep(100);
            }
            assertEquals(List.of("127.0.0.1:" + port), listening);
        } finally {
            sleepers(server).forEach(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void procNetTcpListeningSocketsAreReadWithTheirAddresses() {
        var v4 = List.of(
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
                "   0: 0100007F:2310 00000000:0000 0A 00000000:00000000 00:00000000 00000000   501        0 111 1",
                "   1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000   501        0 222 1",
                "   2: 0100007F:2310 0100007F:C350 01 00000000:00000000 00:00000000 00000000   501        0 333 1");
        assertEquals(java.util.Map.of("111", "127.0.0.1:8976", "222", "*:8080"),
                BackgroundProcesses.listeningSockets(v4));
        var v6 = List.of(
                "   0: 00000000000000000000000001000000:2328 00000000000000000000000000000000:0000 0A 00000000:00000000"
                        + " 00:00000000 00000000   501        0 444 1",
                "   1: 00000000000000000000000000000000:0050 00000000000000000000000000000000:0000 0A 00000000:00000000"
                        + " 00:00000000 00000000   501        0 555 1");
        assertEquals(java.util.Map.of("444", "[0:0:0:0:0:0:0:1]:9000", "555", "*:80"),
                BackgroundProcesses.listeningSockets(v6));
    }

    private String execIn(Long conversationId, String command) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("command", command);
        return ToolContext.withScope(conversationId, null, () -> tool.execute(json.toString(), agent));
    }

    private BackgroundProcesses.Entry awaitListed(String command) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var found = BackgroundProcesses.list(List.of(agent)).stream()
                    .filter(e -> e.command().endsWith(command)).findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(50);
        }
        throw new AssertionError(command + " was never listed: " + BackgroundProcesses.list(List.of(agent)));
    }

    private static void awaitGone(String command) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sleepers(command).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(sleepers(command).isEmpty(), command + " was stopped");
    }

    private static List<ProcessHandle> sleepers(String... commands) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(h -> h.info().commandLine()
                        .map(c -> Arrays.stream(commands).anyMatch(c::endsWith)).orElse(false))
                .toList();
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
