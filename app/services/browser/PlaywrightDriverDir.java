package services.browser;

import com.google.gson.JsonParser;
import com.microsoft.playwright.impl.driver.jar.DriverJar;
import org.jspecify.annotations.Nullable;
import play.Play;
import services.EventLogger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * A persistent copy of the bundled Playwright driver for {@code playwright.cli.dir} to name. Left to
 * itself, Playwright unpacks the driver (~130 MB) into a new temp directory on each JVM's first launch
 * and removes it only through deleteOnExit, which never runs in a JVM that is force-killed.
 */
public final class PlaywrightDriverDir {

    private PlaywrightDriverDir() {}

    /** Read by Playwright once, when the JVM's first {@code Playwright.create} builds its driver. */
    public static final String CLI_DIR_PROPERTY = "playwright.cli.dir";

    private static final String MARKER = ".jclaw-driver";

    // DriverJar borrows an already-open jar FileSystem and its opener closes it, so two extractions
    // in one JVM fail with ClosedFileSystemException.
    private static final ReentrantLock EXTRACT_LOCK = new ReentrantLock();

    public static Path root() {
        return Play.applicationPath.toPath().resolve("data").resolve("playwright-driver");
    }

    /**
     * Point Playwright at the persistent copy when the driver comes from driver-bundle. Any other
     * source is left to Playwright; a failed install falls back to its temp-directory unpacking.
     */
    public static void pin() {
        if (System.getProperty(CLI_DIR_PROPERTY) != null) return;
        var status = BrowserSetup.driver();
        var platform = status.platform();
        if (status.source() != PlaywrightNode.Source.BUNDLED || platform == null) return;
        var key = key(platform);
        if (key == null) return;
        try {
            System.setProperty(CLI_DIR_PROPERTY, install(root(), key).toString());
        } catch (IOException e) {
            EventLogger.warn("tool", "Could not install the Playwright driver under %s, so it unpacks to a temp directory: %s"
                    .formatted(root(), e.getMessage()));
        }
    }

    /** Whether {@code dir} is a copy this class installed, rather than a driver an operator supplied. */
    public static boolean isManaged(Path dir) {
        return Files.isRegularFile(dir.resolve(MARKER));
    }

    /**
     * Install the driver under {@code root} as {@code key}, once, and return its directory. JVMs that
     * share {@code root} each stage a copy and rename it into place; the first rename wins.
     */
    public static Path install(Path root, String key) throws IOException {
        var target = root.resolve(key);
        if (isManaged(target)) return target;
        Files.createDirectories(root);
        var staging = Files.createTempDirectory(root, ".staging-");
        try {
            EXTRACT_LOCK.lock();
            try {
                DriverJar.installDriverTo(staging);
            } finally {
                EXTRACT_LOCK.unlock();
            }
            Files.createFile(staging.resolve(MARKER));
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (!isManaged(target)) throw e;
            }
            return target;
        } catch (URISyntaxException e) {
            throw new IOException(e);
        } finally {
            deleteTree(staging);
        }
    }

    /** Playwright's version and the platform, so an upgrade installs beside a copy a running JVM may hold. */
    public static @Nullable String key(String platform) {
        var in = ClassLoader.getSystemClassLoader().getResourceAsStream("driver/package/package.json");
        if (in == null) return null;
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().get("version").getAsString() + "-" + platform;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
