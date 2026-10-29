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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
        sleepers("sleep 4751", "sleep 4752", "sleep 4753", "sleep 4754", "sleep 4755", "sleep 4756", "sleep 4757",
                "sleep 4758", "sleep 4759", "sleep 4760", "sleep 4761", "sleep 4762", "sleep 4763")
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
    void aFileSwappedInAfterTheHolderCheckIsNeverEmptied() throws Exception {
        execIn(1L, "sleep 4762 & echo started");
        var held = Path.of(awaitListed("sleep 4762").outputFile());
        // The sweep has already found this name held; then a file exec never created takes it, with one link.
        var victim = Files.createTempFile(AgentService.workspacePath(AGENT), "exec-swapped", ".bin");
        try (var raf = new RandomAccessFile(victim.toFile(), "rw")) {
            raf.setLength(17L * 1024 * 1024);
        }
        Files.move(victim, held, StandardCopyOption.REPLACE_EXISTING);
        var bound = BackgroundProcesses.class.getDeclaredMethod("boundOutputFiles", Path.class, Set.class, Instant.class);
        bound.setAccessible(true);

        bound.invoke(null, BackgroundProcesses.workspaceOf(AGENT), Set.of(held.getFileName()),
                Instant.now().minus(Duration.ofDays(1)));

        assertEquals(17L * 1024 * 1024, Files.size(held), "only a file exec recorded is emptied");
    }

    @Test
    void aHardlinkPlantedInTheOutputFolderNamesNoHolder() throws Exception {
        var workspace = AgentService.workspacePath(AGENT);
        var dir = workspace.resolve(".exec");
        Files.createDirectories(dir);
        // Past the size cap, sparse, on the workspace's own volume so it can be hardlinked, and held by a process
        // that is not one of the agent's, even on the descriptor exec uses: lsof matches a file by inode, so a
        // link would otherwise report that process. Only a file exec created is recorded.
        var target = Files.createTempFile(workspace, "exec-victim", ".bin");
        try (var raf = new RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(17L * 1024 * 1024);
        }
        var holder = new ProcessBuilder("/bin/sh", "-c", "exec 4<\"$1\"; exec sleep 4759", "sh", target.toString())
                .start();
        var link = Files.createLink(dir.resolve("c1-" + UUID.randomUUID() + ".log"), target);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sleepers("sleep 4759").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals(1, sleepers("sleep 4759").size(), "the holder is running");

            assertTrue(BackgroundProcesses.list(List.of(agent)).stream().noneMatch(e -> e.command().endsWith("sleep 4759")),
                    "a process holding a planted hardlink is not listed");
            assertFalse(BackgroundProcesses.stop(agent, holder.pid()), "nor can it be stopped");
            AppClock.runWith(Clock.fixed(Instant.now().plus(Duration.ofHours(25)), ZoneOffset.UTC),
                    () -> BackgroundProcesses.sweep(List.of(agent)));
            assertEquals(17L * 1024 * 1024, Files.size(target), "the linked file is never emptied");
            assertEquals(1, sleepers("sleep 4759").size(), "the process outlives the sweep");
        } finally {
            holder.destroyForcibly();
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aFileMovedIntoTheOutputFolderNamesNoHolder() throws Exception {
        var workspace = AgentService.workspacePath(AGENT);
        var dir = workspace.resolve(".exec");
        Files.createDirectories(dir);
        // A moved file has one link and a name that fits; what tells it apart is that exec never created it.
        var victim = Files.createTempFile(workspace, "exec-moved", ".bin");
        var holder = new ProcessBuilder("/bin/sh", "-c", "exec 4<\"$1\"; exec sleep 4761", "sh", victim.toString())
                .start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sleepers("sleep 4761").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        var moved = Files.move(victim, dir.resolve("c1-" + UUID.randomUUID() + ".log"));
        try {
            assertTrue(BackgroundProcesses.list(List.of(agent)).stream().noneMatch(e -> e.command().endsWith("sleep 4761")),
                    "a process holding a moved-in file is not listed");
            assertFalse(BackgroundProcesses.stop(agent, holder.pid()), "nor can it be stopped");
        } finally {
            holder.destroyForcibly();
            Files.deleteIfExists(moved);
        }
    }

    @Test
    void aRecordWhoseFileIsGoneNamesNoHolderForTheFileGivenItsInode() throws Exception {
        var workspace = AgentService.workspacePath(AGENT);
        var dir = workspace.resolve(".exec");
        Files.createDirectories(dir);
        var victim = Files.createTempFile(workspace, "exec-reused", ".bin");
        // Moving the recorded file off its name leaves the record ext4 leaves when it gives a deleted file's inode to a new one.
        BackgroundProcesses.recordOutputFile(victim);
        var holder = new ProcessBuilder("/bin/sh", "-c", "exec 4<\"$1\"; exec sleep 4763", "sh", victim.toString())
                .start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sleepers("sleep 4763").isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        var moved = Files.move(victim, dir.resolve("c1-" + UUID.randomUUID() + ".log"));
        try {
            assertTrue(BackgroundProcesses.list(List.of(agent)).stream().noneMatch(e -> e.command().endsWith("sleep 4763")),
                    "a record counts only while its own name holds the inode");
            assertFalse(BackgroundProcesses.stop(agent, holder.pid()), "nor can it be stopped");
        } finally {
            holder.destroyForcibly();
            Files.deleteIfExists(moved);
        }
    }

    @Test
    void anAgentHardlinkingItsOwnOutputFileStaysListedAndStoppable() throws Exception {
        execIn(1L, "sleep 4760 & echo started");
        var entry = awaitListed("sleep 4760");
        var dir = Path.of(entry.outputFile()).getParent();
        // The link is the agent's attempt to hide its server; the file is exec's and its process holds it on fd 4.
        var link = Files.createLink(dir.resolve("c1-" + UUID.randomUUID() + ".log"), Path.of(entry.outputFile()));
        try {
            assertTrue(Files.readString(play.Play.applicationPath.toPath().resolve("data/exec-outputs-test"))
                    .contains(entry.outputFile()), "the record under data/ names the file exec created");
            assertTrue(BackgroundProcesses.list(List.of(agent)).stream().anyMatch(e -> e.pid() == entry.pid()),
                    "a second link to its own file hides nothing");
            assertTrue(BackgroundProcesses.stop(agent, entry.pid()), "and it can still be stopped");
            awaitGone("sleep 4760");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void deletingTheAgentStopsWhatItsCommandsLeftRunning() throws Exception {
        execIn(1L, "sleep 4758 & echo started");
        awaitListed("sleep 4758");
        AgentService.delete(agent);
        awaitGone("sleep 4758");
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
        int port = TestPorts.freeNonEphemeral();
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
