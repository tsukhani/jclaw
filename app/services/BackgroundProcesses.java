package services;

import models.Agent;
import org.jspecify.annotations.Nullable;
import play.Play;
import utils.AppClock;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Processes an agent's exec commands left running in the background (JCLAW-1565). A command's shell holds
 * its output file open on descriptor {@link #HELD_FD}, so each process it starts holds that file there until
 * it exits, whether or not it redirects its own output. The processes holding a workspace's output files on
 * that descriptor are therefore what its commands left running, found again after a restart, and nothing else
 * is ever signalled. An output file is one exec itself created, recorded by device and inode under
 * {@code data/}, where no command writes: a hardlink an agent plants in the folder shares its inode with a
 * file elsewhere, and a file it moves in keeps its own, so neither is recorded and whatever holds them is left
 * alone, however {@code lsof} matches by inode (JCLAW-1574). A process that closes the descriptor it inherits
 * is not found.
 */
public final class BackgroundProcesses {

    private BackgroundProcesses() {}

    /** Folder in an agent's workspace that holds each exec command's output file. */
    public static final String OUTPUT_DIR = ".exec";

    /** The descriptor exec's shell opens the output file on; a holder on any other is not the command's. */
    public static final String HELD_FD = "4";

    /** The files exec created that still exist, {@code device:inode} to path; the file under {@code data/} mirrors it. */
    private static final Map<String, Path> RECORDED = new HashMap<>();
    private static boolean recordLoaded;

    /** An output file: {@code c<conversation id>-<uuid>.log}, or {@code <uuid>.log} outside a conversation. */
    public static final Pattern OUTPUT_FILE_NAME = Pattern.compile("(?:c(\\d{1,18})-)?[0-9a-f-]{36}\\.log");

    /** Settings &gt; Shell Execution: hours a background process may run before the sweep stops it; 0 turns it off. */
    public static final String LIFETIME_KEY = "shell.backgroundLifetimeHours";
    public static final long DEFAULT_LIFETIME_HOURS = 24;

    /** A held output file past this is emptied; its writers append, so they carry on. */
    public static final long OUTPUT_FILE_CAP_BYTES = 16L * 1024 * 1024;

    /** An output file nothing holds and nothing has written to for this long is deleted. */
    static final Duration OUTPUT_RETENTION = Duration.ofDays(1);

    private static final long TERMINATE_GRACE_MS = 500;
    private static final long LSOF_TIMEOUT_SECONDS = 10;
    private static final Path LSOF = Path.of("/usr/sbin/lsof");
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase().startsWith("linux");

    /** One process left running, with the output file it holds and the TCP addresses it listens on. */
    public record Entry(long pid, Long agentId, String agentName, String outputFile, @Nullable Long conversationId,
                        String command, @Nullable Instant started, List<String> listening) {}

    private record Held(ProcessHandle process, Path file) {}

    /** What {@code agents}' exec commands left running, oldest first. */
    public static List<Entry> list(Collection<Agent> agents) {
        var entries = new ArrayList<Entry>();
        for (var agent : byWorkspace(agents).values()) {
            var held = holders(workspaceOf(agent));
            var listening = listening(held.stream().map(h -> h.process().pid()).collect(Collectors.toSet()));
            for (var h : held) {
                var info = h.process().info();
                entries.add(new Entry(h.process().pid(), agent.id, agent.name, h.file().toString(), conversationOf(h.file()),
                        info.commandLine().or(info::command).orElse("?"), info.startInstant().orElse(null),
                        listening.getOrDefault(h.process().pid(), List.of())));
            }
        }
        entries.sort(Comparator.comparing(Entry::started, Comparator.nullsLast(Comparator.naturalOrder())));
        return entries;
    }

    /** Stops process {@code pid} and its descendants if it is one {@code agent}'s commands left running. */
    public static boolean stop(Agent agent, long pid) {
        for (var h : holders(workspaceOf(agent))) {
            if (h.process().pid() == pid) {
                terminate(h.process());
                return true;
            }
        }
        return false;
    }

    /** Records {@code file}, which exec has just created, as an output file; until then nothing holding it counts. */
    public static void recordOutputFile(Path file) {
        var id = fileId(file);
        if (id == null) return;
        synchronized (RECORDED) {
            loadRecord();
            if (RECORDED.put(id, file) == null) saveRecord();
        }
    }

    /** Forgets {@code file}, which exec has deleted after a call that left nothing running. */
    public static void forgetOutputFile(Path file) {
        synchronized (RECORDED) {
            loadRecord();
            if (RECORDED.values().removeIf(file::equals)) saveRecord();
        }
    }

    private static boolean recorded(Path file) {
        var id = fileId(file);
        if (id == null) return false;
        synchronized (RECORDED) {
            loadRecord();
            var path = RECORDED.get(id);
            // ext4 gives a deleted file's inode to the next file made, so a record counts only while its own name holds it.
            return path != null && id.equals(fileId(path));
        }
    }

    /** Drops the records whose file is gone or is no longer the one recorded. */
    private static void pruneRecord() {
        synchronized (RECORDED) {
            loadRecord();
            if (dropStale()) saveRecord();
        }
    }

    private static boolean dropStale() {
        return RECORDED.entrySet().removeIf(e -> !e.getKey().equals(fileId(e.getValue())));
    }

    private static void loadRecord() {
        if (recordLoaded) return;
        recordLoaded = true;
        try {
            if (!Files.exists(recordFile())) return;
            for (var line : Files.readAllLines(recordFile(), StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) RECORDED.put(line.substring(0, tab), Path.of(line.substring(tab + 1)));
            }
            if (dropStale()) saveRecord();
        } catch (IOException e) {
            EventLogger.warn("tool", null, "exec", "Could not read %s: %s".formatted(recordFile(), e.getMessage()));
        }
    }

    private static void saveRecord() {
        var lines = RECORDED.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList();
        var tmp = recordFile().resolveSibling(recordFile().getFileName() + ".tmp");
        try {
            Files.createDirectories(recordFile().getParent());
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, recordFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            EventLogger.warn("tool", null, "exec", "Could not write %s: %s".formatted(recordFile(), e.getMessage()));
        }
    }

    private static Path recordFile() {
        // Its own file under the test runner, as the workspace root has, so a test JVM never rewrites the live one.
        var name = Play.runningInTestMode() ? "exec-outputs-test" : "exec-outputs";
        return Play.applicationPath.toPath().resolve("data").resolve(name);
    }

    /** {@code device:inode} of {@code file} itself, never of a link's target; null where it cannot be read. */
    private static @Nullable String fileId(Path file) {
        try {
            var attrs = Files.readAttributes(file, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
            return attrs.get("dev") + ":" + attrs.get("ino");
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException _) {
            return null;
        }
    }

    /** Stops everything {@code workspace}'s commands left running, before the workspace goes with its agent. */
    public static void stopAll(Path workspace) {
        for (var h : holders(workspace)) {
            EventLogger.info("tool", null, null, "Stopped background process %d: its agent was deleted"
                    .formatted(h.process().pid()));
            terminate(h.process());
        }
    }

    /** The pids of the processes, other than this JVM, holding exec output file {@code file}. */
    public static List<Long> pidsHolding(Path file) {
        try {
            return holdersOf(List.of(file.toRealPath())).stream().map(h -> h.process().pid()).toList();
        } catch (IOException _) {
            return List.of();
        }
    }

    /** Stops what commands started in one of {@code conversationIds} left running in {@code workspaces}. */
    public static void stopForConversations(Collection<Path> workspaces, Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) return;
        for (var workspace : Set.copyOf(workspaces)) {
            // Most deleted conversations never left anything running: skip lsof unless a file names one.
            if (outputFiles(workspace).stream().noneMatch(f -> startedIn(f, conversationIds))) continue;
            for (var h : holders(workspace)) {
                if (startedIn(h.file(), conversationIds)) {
                    EventLogger.info("tool", null, null, "Stopped background process %d: its conversation was deleted"
                            .formatted(h.process().pid()));
                    terminate(h.process());
                }
            }
        }
    }

    /**
     * Stops processes past their lifetime, empties held output files past {@link #OUTPUT_FILE_CAP_BYTES} and
     * deletes those nothing holds once unwritten for {@link #OUTPUT_RETENTION}.
     */
    public static void sweep(Collection<Agent> agents) {
        long hours = ConfigService.getLong(LIFETIME_KEY, DEFAULT_LIFETIME_HOURS);
        var now = AppClock.now();
        for (var agent : byWorkspace(agents).values()) {
            var workspace = workspaceOf(agent);
            var held = holders(workspace);
            var heldNames = new HashSet<Path>();
            for (var h : held) {
                heldNames.add(h.file().getFileName());
                var started = h.process().info().startInstant().orElse(null);
                if (hours > 0 && started != null && started.plus(Duration.ofHours(hours)).isBefore(now)) {
                    EventLogger.info("tool", agent.name, null, "Stopped background process %d after its %d-hour lifetime"
                            .formatted(h.process().pid(), hours));
                    terminate(h.process());
                }
            }
            boundOutputFiles(workspace, heldNames, now.minus(OUTPUT_RETENTION));
        }
        pruneRecord();
    }

    /**
     * End {@code process} and every descendant: SIGTERM the tree, give it {@link #TERMINATE_GRACE_MS} to exit,
     * then SIGKILL whatever survives. Descendants are captured first, because a child whose parent dies is
     * reparented and no longer reachable from the handle.
     */
    public static void terminate(ProcessHandle process) {
        var tree = new ArrayList<ProcessHandle>(process.descendants().toList());
        tree.add(process);
        tree.forEach(ProcessHandle::destroy);
        var exits = tree.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(exits).get(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException _) {
            // Survivors are force-killed below.
        }
        for (var h : tree) {
            if (h.isAlive()) h.destroyForcibly();
        }
    }

    private static Path workspaceOf(Agent agent) {
        return workspaceOf(agent.name);
    }

    /** The workspace an agent's exec commands run in; a subagent's is its root agent's. */
    public static Path workspaceOf(String agentName) {
        return AgentService.workspacePath(agentName).toAbsolutePath().normalize();
    }

    private static boolean startedIn(Path file, Collection<Long> conversationIds) {
        var id = conversationOf(file);
        return id != null && conversationIds.contains(id);
    }

    private static @Nullable Long conversationOf(Path file) {
        var name = OUTPUT_FILE_NAME.matcher(file.getFileName().toString());
        return name.matches() && name.group(1) != null ? Long.valueOf(name.group(1)) : null;
    }

    /** One agent per workspace, the first given: a subagent works in its root agent's workspace. */
    private static Map<Path, Agent> byWorkspace(Collection<Agent> agents) {
        var byPath = new LinkedHashMap<Path, Agent>();
        agents.forEach(agent -> byPath.putIfAbsent(workspaceOf(agent), agent));
        return byPath;
    }

    /** The output files in {@code workspace}'s {@link #OUTPUT_DIR}, by real path; none when it is not a plain folder. */
    private static List<Path> outputFiles(Path workspace) {
        var dir = workspace.resolve(OUTPUT_DIR);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var files = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(dir)) {
            var realDir = dir.toRealPath();
            for (var entry : entries) {
                if (OUTPUT_FILE_NAME.matcher(entry.getFileName().toString()).matches()
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && recorded(entry)) {
                    files.add(realDir.resolve(entry.getFileName()));
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            EventLogger.warn("tool", null, "exec", "Could not list %s: %s".formatted(dir, e.getMessage()));
            return List.of();
        }
        return files;
    }

    /**
     * The file keys of the recorded output files, each read together with the {@code device:inode} it must match,
     * so a name that now holds another file contributes nothing.
     */
    private static Set<Object> recordedKeys() {
        var keys = new HashSet<Object>();
        synchronized (RECORDED) {
            loadRecord();
            for (var e : RECORDED.entrySet()) {
                try {
                    var attrs = Files.readAttributes(e.getValue(), "unix:dev,ino,fileKey", LinkOption.NOFOLLOW_LINKS);
                    if (e.getKey().equals(attrs.get("dev") + ":" + attrs.get("ino"))) keys.add(attrs.get("fileKey"));
                } catch (IOException | UnsupportedOperationException | IllegalArgumentException _) {
                    // Gone, or no unix view here: nothing of it can be emptied.
                }
            }
        }
        return keys;
    }

    /** Every process but this JVM holding one of the output files in {@code workspace}. */
    private static List<Held> holders(Path workspace) {
        return holdersOf(outputFiles(workspace));
    }

    private static List<Held> holdersOf(List<Path> files) {
        if (files.isEmpty()) return List.of();
        var byPid = LINUX ? procHolders(Set.copyOf(files)) : lsofHolders(files);
        long self = ProcessHandle.current().pid();
        var held = new ArrayList<Held>();
        byPid.forEach((pid, file) -> {
            if (pid != self) ProcessHandle.of(pid).ifPresent(p -> held.add(new Held(p, file)));
        });
        return held;
    }

    private static Map<Long, Path> lsofHolders(List<Path> files) {
        var args = new ArrayList<String>(List.of(LSOF.toString(), "-nP", "-w", "-Fpfn", "--"));
        files.forEach(f -> args.add(f.toString()));
        var byPid = new LinkedHashMap<Long, Path>();
        long pid = -1;
        boolean onHeldFd = false;
        for (var line : lsof(args)) {
            if (line.startsWith("p")) {
                pid = Long.parseLong(line.substring(1));
                onHeldFd = false;
            } else if (line.startsWith("f")) {
                onHeldFd = HELD_FD.equals(line.substring(1));
            } else if (line.startsWith("n") && pid > 0 && onHeldFd) {
                byPid.putIfAbsent(pid, Path.of(line.substring(1)));
            }
        }
        return byPid;
    }

    /** Each pid's TCP listening addresses, as {@code host:port} with {@code *} for every interface. */
    private static Map<Long, List<String>> listening(Set<Long> pids) {
        if (pids.isEmpty()) return Map.of();
        if (LINUX) return procListening(pids);
        var csv = String.join(",", pids.stream().map(String::valueOf).toList());
        var byPid = new HashMap<Long, List<String>>();
        long pid = -1;
        for (var line : lsof(List.of(LSOF.toString(), "-nP", "-w", "-a", "-p", csv, "-iTCP", "-sTCP:LISTEN", "-Fpn"))) {
            if (line.startsWith("p")) pid = Long.parseLong(line.substring(1));
            else if (line.startsWith("n") && pid > 0) byPid.computeIfAbsent(pid, _ -> new ArrayList<>()).add(line.substring(1));
        }
        return byPid;
    }

    /** lsof's field output, or nothing; it exits 1 when nothing matched, which is not a failure here. */
    private static List<String> lsof(List<String> args) {
        try {
            var process = new ProcessBuilder(args)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .start();
            byte[] out;
            try (var in = process.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!process.waitFor(LSOF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly();
            return new String(out, StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            EventLogger.warn("tool", null, "exec", "Could not run lsof: %s".formatted(e.getMessage()));
            return List.of();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static Map<Long, Path> procHolders(Set<Path> files) {
        var byPid = new LinkedHashMap<Long, Path>();
        forEachProcFd((pid, fd, target) -> {
            if (HELD_FD.equals(fd) && files.contains(target)) byPid.putIfAbsent(pid, target);
        });
        return byPid;
    }

    private static Map<Long, List<String>> procListening(Set<Long> pids) {
        var addressByInode = new HashMap<String, String>();
        for (var table : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                addressByInode.putAll(listeningSockets(Files.readAllLines(Path.of(table))));
            } catch (IOException _) {
                // No IPv6 table on a kernel without IPv6.
            }
        }
        var byPid = new HashMap<Long, List<String>>();
        forEachProcFd((pid, _, target) -> {
            var link = target.toString();
            if (!pids.contains(pid) || !link.startsWith("socket:[")) return;
            var address = addressByInode.get(link.substring(8, link.length() - 1));
            if (address != null) byPid.computeIfAbsent(pid, _ -> new ArrayList<>()).add(address);
        });
        return byPid;
    }

    /** One open descriptor of one process under {@code /proc}: the pid, the descriptor number and what it names. */
    @FunctionalInterface
    private interface ProcFdVisitor {
        void accept(long pid, String fd, Path target);
    }

    private static void forEachProcFd(ProcFdVisitor visit) {
        try (var procs = Files.newDirectoryStream(Path.of("/proc"), p -> p.getFileName().toString().matches("\\d+"))) {
            for (var proc : procs) forEachFd(Long.parseLong(proc.getFileName().toString()), proc, visit);
        } catch (IOException | DirectoryIteratorException e) {
            EventLogger.warn("tool", null, "exec", "Could not read /proc: %s".formatted(e.getMessage()));
        }
    }

    private static void forEachFd(long pid, Path proc, ProcFdVisitor visit) {
        try (var fds = Files.newDirectoryStream(proc.resolve("fd"))) {
            for (var fd : fds) {
                var target = linkTarget(fd);
                if (target != null) visit.accept(pid, fd.getFileName().toString(), target);
            }
        } catch (IOException | DirectoryIteratorException _) {
            // Another user's process, or one that has exited.
        }
    }

    private static @Nullable Path linkTarget(Path fd) {
        try {
            return Files.readSymbolicLink(fd);
        } catch (IOException _) {
            // Closed since the listing.
            return null;
        }
    }

    /**
     * The listening sockets in a {@code /proc/net/tcp} or {@code tcp6} table, by inode, as {@code host:port}.
     * The kernel writes each address as hex words in host byte order, little-endian on every platform JClaw ships.
     */
    public static Map<String, String> listeningSockets(List<String> table) {
        var byInode = new HashMap<String, String>();
        for (var line : table) {
            var f = line.trim().split("\\s+");
            if (f.length < 10 || !"0A".equals(f[3])) continue;
            int colon = f[1].indexOf(':');
            if (colon < 0) continue;
            var host = f[1].substring(0, colon);
            int port = Integer.parseInt(f[1].substring(colon + 1), 16);
            byInode.put(f[9], hostOf(host) + ":" + port);
        }
        return byInode;
    }

    private static String hostOf(String hex) {
        var bytes = new byte[hex.length() / 2];
        // Each 32-bit word is little-endian; IPv6 is four of them in order.
        for (int word = 0; word < bytes.length / 4; word++) {
            for (int i = 0; i < 4; i++) {
                int at = (word * 4 + 3 - i) * 2;
                bytes[word * 4 + i] = (byte) Integer.parseInt(hex.substring(at, at + 2), 16);
            }
        }
        boolean any = true;
        for (var b : bytes) any &= b == 0;
        if (any) return "*";
        try {
            var address = InetAddress.getByAddress(bytes).getHostAddress();
            return bytes.length == 4 ? address : "[" + address + "]";
        } catch (UnknownHostException _) {
            return hex;
        }
    }

    /**
     * Empties held output files past the cap and deletes unheld ones unwritten since {@code cutoff}. Works
     * relative to the folder's descriptor, never follows a link, and empties only a file exec recorded, as
     * identified through that descriptor, so nothing planted or swapped into the folder is emptied: the sweep
     * runs unconfined.
     */
    private static void boundOutputFiles(Path workspace, Set<Path> held, Instant cutoff) {
        if (!Files.isDirectory(workspace.resolve(OUTPUT_DIR), LinkOption.NOFOLLOW_LINKS)) return;
        var recorded = recordedKeys();
        try (var parent = Files.newDirectoryStream(workspace)) {
            if (!(parent instanceof SecureDirectoryStream<Path> secureParent)) return;
            try (var entries = secureParent.newDirectoryStream(Path.of(OUTPUT_DIR), LinkOption.NOFOLLOW_LINKS)) {
                for (var entry : entries) {
                    var name = entry.getFileName();
                    if (!OUTPUT_FILE_NAME.matcher(name.toString()).matches()) continue;
                    var attrs = entries.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                            .readAttributes();
                    if (!attrs.isRegularFile()) continue;
                    if (held.contains(name)) {
                        // Between this read and the open, only an inode the agent can already write can be swapped in (JCLAW-1579).
                        if (attrs.size() > OUTPUT_FILE_CAP_BYTES && recorded.contains(attrs.fileKey())) {
                            try (var channel = entries.newByteChannel(name,
                                    Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                                channel.truncate(0);
                            }
                        }
                    } else if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        entries.deleteFile(name);
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            EventLogger.warn("tool", null, "exec", "Could not bound exec output in %s: %s"
                    .formatted(workspace.resolve(OUTPUT_DIR), e.getMessage()));
        }
    }
}
