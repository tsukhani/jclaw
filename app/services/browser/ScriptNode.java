package services.browser;

import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static utils.GsonHolder.GSON;

/**
 * The Node.js a script runs on: {@link PlaywrightNode}'s pinned build, found or downloaded without
 * installing Chromium, and the arguments that start it with no file, process or worker access.
 */
public final class ScriptNode {

    private ScriptNode() {}

    public static final String NODEJS_PATH_ENV = "PLAYWRIGHT_NODEJS_PATH";

    public static final String HEAP_KEY = "script.nodeHeapMb";
    public static final int DEFAULT_HEAP_MB = 256;
    // Measured on Node 24.22.1: 8 MB still starts, 4 MB aborts before any script runs.
    public static final int MIN_HEAP_MB = 16;

    public enum Source { NODEJS_PATH, MANAGED_DRIVER, DOWNLOADED }

    public sealed interface Resolution permits Ready, Refused {}

    /** @param node the binary to start; {@link #folder()} is what a sandbox must leave readable */
    public record Ready(Path node, Source source) implements Resolution {
        public Path folder() {
            return Objects.requireNonNull(node.toAbsolutePath().getParent(), "a node binary is a file inside a folder");
        }
    }

    /** @param reason the refusal the caller returns, naming what failed */
    public record Refused(String reason) implements Resolution {}

    /**
     * Where this JVM looks for Node.
     *
     * @param driverKey the managed driver folder's name, or null when driver-bundle is not on the classpath
     * @param build the pinned download for this host, or null on a platform Node.js has no build for
     */
    public record Host(Map<String, String> env, Path driverRoot, @Nullable String driverKey, Path cacheRoot,
                       PlaywrightNode.@Nullable Build build) {

        public static Host current() {
            var platform = PlaywrightNode.hostPlatform();
            return new Host(System.getenv(), PlaywrightDriverDir.root(),
                    platform == null ? null : PlaywrightDriverDir.key(platform), BrowserSetup.cacheRoot(),
                    platform == null ? null : PlaywrightNode.build(platform));
        }
    }

    public static Resolution resolve() {
        return resolve(Host.current(), BrowserSetup.CURRENT);
    }

    /**
     * {@value #NODEJS_PATH_ENV}, then the managed driver folder (copied out of driver-bundle when it
     * is on the classpath), then the pinned download, fetched on first use and checked against its
     * SHA-256. A download reports its progress and its failure on {@code progress}, which Settings reads.
     */
    public static Resolution resolve(Host host, BrowserSetup.Progress progress) {
        var explicit = host.env().get(NODEJS_PATH_ENV);
        if (explicit != null && !explicit.isBlank()) {
            var node = Path.of(explicit);
            return Files.isRegularFile(node) ? new Ready(node, Source.NODEJS_PATH)
                    : new Refused(NODEJS_PATH_ENV + " names " + node + ", which is not a file");
        }
        var build = host.build();
        if (build == null) {
            return new Refused("Scripts run on Node.js, which has no build for this platform (%s %s)"
                    .formatted(System.getProperty("os.name"), System.getProperty("os.arch")));
        }
        var managed = managedNode(host, build.platform());
        if (managed != null) return new Ready(managed, Source.MANAGED_DRIVER);
        try {
            var node = BrowserSetup.downloadNode(build, host.cacheRoot(), progress,
                    "Downloading Node.js %s for scripts".formatted(PlaywrightNode.VERSION));
            return new Ready(node, Source.DOWNLOADED);
        } catch (IOException e) {
            progress.failed("Node.js for scripts could not be downloaded: " + e.getMessage());
            EventLogger.warn("tool", "Node.js download for scripts failed: " + e.getMessage());
            return new Refused("Node.js %s for scripts could not be downloaded (%s); retry, or download it from Settings > Browser"
                    .formatted(PlaywrightNode.VERSION, e.getMessage()));
        }
    }

    private static @Nullable Path managedNode(Host host, String platform) {
        var key = host.driverKey();
        if (key == null) return null;
        try {
            var node = PlaywrightDriverDir.install(host.driverRoot(), key).resolve(PlaywrightNode.binaryName(platform));
            return Files.isRegularFile(node) ? node : null;
        } catch (IOException e) {
            EventLogger.warn("tool", "Could not install the Playwright driver under %s for scripts, so they use the download: %s"
                    .formatted(host.driverRoot(), e.getMessage()));
            return null;
        }
    }

    /** {@value #HEAP_KEY}, or the default when it is unset or below {@link #MIN_HEAP_MB}. */
    public static int heapMb() {
        var mb = ConfigService.getInt(HEAP_KEY, DEFAULT_HEAP_MB);
        return mb >= MIN_HEAP_MB ? mb : DEFAULT_HEAP_MB;
    }

    public static List<String> command(Path node) {
        return command(node, heapMb());
    }

    /**
     * The argv that starts {@code node} with the permission model on and no {@code --allow-*} flag,
     * so the script can read or write no file and start no process or worker thread. The
     * script itself never appears here: write {@link #stdin} to the process first.
     */
    public static List<String> command(Path node, int heapMb) {
        return List.of(node.toString(), "--permission", "--max-old-space-size=" + heapMb, "--eval", LOADER);
    }

    /** The first line the started process reads: the script, as one JSON string. */
    public static byte[] stdin(String script) {
        return (GSON.toJson(script) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    // Takes the first stdin line as the script and leaves the rest of stdin, paused, to it.
    static final String LOADER = String.join("",
            "let b='';",
            "const h=c=>{",
            "b+=c;const i=b.indexOf('\\n');if(i<0)return;",
            "process.stdin.removeListener('data',h);process.stdin.pause();",
            "const rest=b.slice(i+1);if(rest)process.stdin.unshift(rest);",
            "require('node:vm').runInThisContext(JSON.parse(b.slice(0,i)),{filename:'script.js'});",
            "};",
            "process.stdin.setEncoding('utf8');process.stdin.on('data',h);");
}
