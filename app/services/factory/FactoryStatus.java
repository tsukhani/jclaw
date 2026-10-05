package services.factory;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jspecify.annotations.Nullable;
import play.Play;
import utils.AppClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static utils.GsonHolder.GSON;

/**
 * Read-only view of the AFK factory on this machine (JCLAW-1391): whether it is installed and can
 * run here, the harness, gateway and sandboxes as launchd and Docker report them, the harness's
 * {@code board.json}, and the tail of one story log. Every probe goes through
 * {@link FactoryProcess#run} under {@link #PROBE_TIMEOUT}; a probe that fails or times out reports
 * {@link #UNKNOWN} rather than throwing.
 */
public final class FactoryStatus {

    public static final String UNKNOWN = "unknown";
    public static final String RUNNING = "running";
    public static final String STOPPED = "stopped";
    /** The gateway container does not exist. */
    public static final String ABSENT = "absent";

    public static final int BOARD_SCHEMA = 1;
    public static final int LOG_TAIL_BYTES = 256 * 1024;
    static final long BOARD_MAX_BYTES = 4L * 1024 * 1024;
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    static final String LAUNCH_AGENT = "com.jclaw.factory";
    static final String GATEWAY = "jclaw-factory-gateway";
    static final String SANDBOX_PREFIX = "sandcastle-";

    // The factory home's credential files: never served, whatever board.json lists.
    private static final Set<String> CREDENTIAL_FILES = Set.of(".env", "jira.env", "github.env", "settings.env");
    private static final Pattern LAUNCHD_PID = Pattern.compile("\"PID\"\\s*=\\s*(\\d+);");

    public record HarnessView(String state, @Nullable Long pid) {}

    public record GatewayView(String state) {}

    /** @param upTime Docker's status with its leading "Up " removed, e.g. "12 minutes" */
    public record SandboxView(String name, @Nullable String story, String upTime) {}

    public record BoardHarness(long pid, String startedAt, @Nullable String main) {}

    /** Named as in {@code settings.env}, on the wire as in board.json. */
    public record BoardSettings(@SerializedName(FactoryHome.KEY_MAX_PARALLEL) int maxParallel,
                                @SerializedName(FactoryHome.KEY_CPUS) int cpus,
                                @SerializedName(FactoryHome.KEY_POLL_SECONDS) int pollSeconds,
                                @SerializedName(FactoryHome.KEY_MODEL) String model) {}

    /** One story as {@code .sandcastle/README.md}'s Board section defines it; state-specific fields are null otherwise. */
    public record BoardStory(String key, String summary, String source, boolean autoMerge, String state,
                             String since, @Nullable String reason, @Nullable String phase,
                             @Nullable String phaseStartedAt, @Nullable String sha, @Nullable String by,
                             List<String> logs) {}

    public record Board(int schema, String updatedAt, BoardHarness harness, @Nullable BoardSettings settings,
                        List<BoardStory> stories) {}

    /**
     * @param sandboxes   null when Docker could not be asked
     * @param board       null when {@code board.json} is absent or unreadable; {@code boardReason} says which
     */
    public record StatusView(boolean installed, boolean supported, @Nullable String reason, HarnessView harness,
                             GatewayView gateway, @Nullable List<SandboxView> sandboxes, @Nullable Board board,
                             @Nullable String boardReason) {}

    /** @param modifiedAt the file's modification time; {@code truncated} when {@code text} is only its tail */
    public record LogView(String key, String file, long size, Instant modifiedAt, boolean truncated, String text) {}

    private record Probes(boolean supported, @Nullable String reason, HarnessView harness, GatewayView gateway,
                          @Nullable List<SandboxView> sandboxes) {}

    private record BoardRead(@Nullable Board board, @Nullable String reason) {}

    private record Cached(Probes probes, Instant at) {}

    private static @Nullable Cached cached;

    private FactoryStatus() {}

    /** The status with probes no older than {@link #CACHE_TTL}; the board is read fresh. */
    public static StatusView current() {
        return assemble(cachedProbes(System.getProperty("os.name", "")));
    }

    /** The status with fresh probes, as on a host whose {@code os.name} is {@code osName}. */
    public static StatusView read(String osName) {
        return assemble(probe(osName));
    }

    /** Drop the cached probes, so the next {@link #current()} asks launchd and Docker again. */
    public static synchronized void clearCache() {
        cached = null;
    }

    // Synchronized, so concurrent panel loads wait for one probe rather than each starting their own.
    private static synchronized Probes cachedProbes(String osName) {
        var now = AppClock.now();
        var c = cached;
        if (c != null && c.at().plus(CACHE_TTL).isAfter(now)) return c.probes();
        var fresh = probe(osName);
        cached = new Cached(fresh, now);
        return fresh;
    }

    private static StatusView assemble(Probes p) {
        var board = readBoard(FactoryHome.boardFile());
        return new StatusView(installed(), p.supported(), p.reason(), p.harness(), p.gateway(), p.sandboxes(),
                board.board(), board.reason());
    }

    /** The factory home exists and the checkout carries {@code .sandcastle/run.sh}. */
    public static boolean installed() {
        return Files.isDirectory(FactoryHome.home())
                && Files.isRegularFile(Play.applicationPath.toPath().resolve(".sandcastle").resolve("run.sh"));
    }

    private static Probes probe(String osName) {
        if (!osName.toLowerCase(Locale.ROOT).startsWith("mac")) {
            return new Probes(false, "The factory runs only on macOS; this host is " + osName + ".",
                    new HarnessView(STOPPED, null), new GatewayView(UNKNOWN), null);
        }
        var harness = probeHarness();
        var ps = FactoryProcess.run(List.of("docker", "ps", "-a", "--format", "{{.Names}}\t{{.State}}\t{{.Status}}"),
                PROBE_TIMEOUT);
        if (!ps.ok()) {
            return new Probes(false, dockerUnavailable(ps), harness, new GatewayView(UNKNOWN), null);
        }
        var gateway = ABSENT;
        var running = new ArrayList<String[]>();
        for (var line : ps.output().lines().toList()) {
            var cols = line.split("\t", -1);
            if (cols.length != 3) continue;
            if (cols[0].equals(GATEWAY)) gateway = cols[1].isBlank() ? UNKNOWN : cols[1];
            if (cols[0].startsWith(SANDBOX_PREFIX) && cols[1].equals(RUNNING)) running.add(cols);
        }
        var stories = storiesOf(running.stream().map(c -> c[0]).toList());
        var sandboxes = new ArrayList<SandboxView>();
        for (var cols : running) {
            sandboxes.add(new SandboxView(cols[0], stories.get(cols[0]), cols[2].replaceFirst("^Up ", "")));
        }
        return new Probes(true, null, harness, new GatewayView(gateway), sandboxes);
    }

    private static String dockerUnavailable(FactoryProcess.ExecResult ps) {
        if (ps.timedOut()) return "Docker did not answer within " + PROBE_TIMEOUT.toSeconds() + " s.";
        if (ps.exitCode() == -1) return "Docker is not installed or not on JClaw's PATH.";
        var first = ps.output().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        return "Docker is not available" + (first.isEmpty() ? "." : ": " + first);
    }

    // launchctl list exits non-zero for an agent that is not loaded, and omits PID for one loaded but not running.
    private static HarnessView probeHarness() {
        var res = FactoryProcess.run(List.of("launchctl", "list", LAUNCH_AGENT), PROBE_TIMEOUT);
        if (res.timedOut() || res.exitCode() == -1) return new HarnessView(UNKNOWN, null);
        if (res.exitCode() != 0) return new HarnessView(STOPPED, null);
        var m = LAUNCHD_PID.matcher(res.output());
        if (!m.find()) return new HarnessView(STOPPED, null);
        try {
            return new HarnessView(RUNNING, Long.parseLong(m.group(1)));
        } catch (NumberFormatException _) {
            return new HarnessView(UNKNOWN, null);
        }
    }

    // One docker inspect for every sandbox; a sandbox it cannot name maps to nothing.
    private static Map<String, String> storiesOf(List<String> names) {
        var out = new LinkedHashMap<String, String>();
        if (names.isEmpty()) return out;
        var command = new ArrayList<>(List.of("docker", "inspect", "--format",
                "{{.Name}}{{range .Mounts}}\t{{.Source}}{{end}}"));
        command.addAll(names);
        var res = FactoryProcess.run(command, PROBE_TIMEOUT);
        if (res.timedOut() || res.exitCode() == -1) return out;
        for (var line : res.output().lines().toList()) {
            var cols = line.split("\t");
            var name = cols[0].strip().replaceFirst("^/", "");
            if (!names.contains(name)) continue;
            var story = FactoryHome.storyFromMounts(String.join("\n", List.of(cols).subList(1, cols.length)));
            if (story != null) out.put(name, story);
        }
        return out;
    }

    private static BoardRead readBoard(Path file) {
        if (!Files.isRegularFile(file)) return new BoardRead(null, "The factory has written no board.json yet.");
        String text;
        try {
            if (Files.size(file) > BOARD_MAX_BYTES) {
                return new BoardRead(null, "board.json is larger than " + BOARD_MAX_BYTES / (1024 * 1024) + " MB.");
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new BoardRead(null, "board.json could not be read: " + e.getMessage());
        }
        Board board;
        try {
            board = GSON.fromJson(text, Board.class);
        } catch (JsonParseException | IllegalStateException e) {
            return new BoardRead(null, "board.json is not valid JSON.");
        }
        if (board == null) return new BoardRead(null, "board.json is empty.");
        if (board.schema() != BOARD_SCHEMA) {
            return new BoardRead(null, "board.json has schema " + board.schema() + "; JClaw reads schema "
                    + BOARD_SCHEMA + ".");
        }
        // Gson leaves a missing array null whatever the record declares.
        var stories = board.stories();
        if (stories == null || stories.stream().anyMatch(s -> s == null || s.key() == null || s.logs() == null)) {
            return new BoardRead(null, "board.json has a story without a key or logs.");
        }
        return new BoardRead(board, null);
    }

    /**
     * The last {@link #LOG_TAIL_BYTES} of story {@code key}'s log {@code file}, or null when the
     * board lists no such story, does not list {@code file} for it, or the name would leave the
     * logs directory or name a credential file.
     */
    public static @Nullable LogView log(String key, String file) throws IOException {
        if (!safeName(file)) return null;
        var board = readBoard(FactoryHome.boardFile()).board();
        if (board == null) return null;
        var listed = board.stories().stream()
                .anyMatch(s -> s.key().equals(key) && s.logs().contains(file));
        if (!listed) return null;

        var logs = FactoryHome.logsDir();
        var path = logs.resolve(file).normalize();
        if (!logs.normalize().equals(path.getParent())) return null;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!path.toRealPath().startsWith(logs.toRealPath())) return null;

        try (SeekableByteChannel ch = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var size = ch.size();
            var from = Math.max(0, size - LOG_TAIL_BYTES);
            var buf = ByteBuffer.allocate((int) (size - from));
            ch.position(from);
            // Up to the size seen at open: a log still growing keeps its later bytes for the next read.
            while (buf.hasRemaining()) {
                if (ch.read(buf) <= 0) break;
            }
            var bytes = buf.array();
            var start = 0;
            // The cut may land inside a UTF-8 character: skip its continuation bytes.
            while (from > 0 && start < buf.position() && (bytes[start] & 0xC0) == 0x80) start++;
            var text = new String(bytes, start, buf.position() - start, StandardCharsets.UTF_8);
            var modified = Files.getLastModifiedTime(path).toInstant();
            return new LogView(key, file, size, modified, from > 0, text);
        }
    }

    /** A plain file name: no separator, no dot-segment or dotfile, not a credential file. */
    static boolean safeName(String file) {
        return !file.isEmpty() && !file.startsWith(".") && file.indexOf('/') < 0 && file.indexOf('\\') < 0
                && file.indexOf('\0') < 0 && !CREDENTIAL_FILES.contains(file);
    }
}
