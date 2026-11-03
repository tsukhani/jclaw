package services.voice;

import com.k2fsa.sherpa.onnx.LibraryUtils;
import org.jspecify.annotations.Nullable;
import play.Play;
import services.EventLogger;
import utils.PinnedDownload;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

/**
 * Where sherpa-onnx's native libraries come from (JF-188). Gradle puts only the build host's native-lib jar on
 * the classpath, and the release bundle is built on Linux x64, so the bundle carries none: a bundle install
 * fetches its own platform's jar on first use, checks it against a pinned SHA-256, and points sherpa at the
 * libraries extracted from it. {@link #VERSION} must track the sherpa-onnx jar on the classpath;
 * SherpaNativeTest fails when a bump changes it.
 */
public final class SherpaNative {

    private SherpaNative() {}

    public static final String VERSION = "1.13.4";

    /** The directory sherpa's {@code LibraryUtils} loads its JNI library from, ahead of its own jar resource. */
    public static final String NATIVE_PATH = "sherpa_onnx.native.path";

    /** One platform's native-lib jar on the sherpa-onnx release, and its SHA-256. */
    public record Build(String classifier, String url, String sha256) {}

    private static Build release(String classifier, String sha256) {
        return new Build(classifier, "https://github.com/k2-fsa/sherpa-onnx/releases/download/v" + VERSION
                + "/sherpa-onnx-native-lib-" + classifier + "-v" + VERSION + ".jar", sha256);
    }

    // Hashed from the v1.13.4 release assets; osx-aarch64's matches the jar Gradle resolves. Pinned here rather
    // than fetched beside the jar, so a compromised mirror cannot vouch for its own download.
    private static final Map<String, Build> BUILDS = Map.of(
            "linux-x64", release("linux-x64", "c70e1a22507a15f3232db6e36a93812ff7e0f11ed1265f60b2fadc2de8fd5bee"),
            "linux-aarch64", release("linux-aarch64", "8b9175b32eac7ce09831a73e87da51865eec481e46d44427d5d63e388ff9e432"),
            "osx-x64", release("osx-x64", "51d2e65bdda9dc578beea6886bc5337a72d70f180091f621dfb5bbf31cf21a09"),
            "osx-aarch64", release("osx-aarch64", "5adf7ce3023808f43945f7bd81574bfcd928a4b88769cd3bca7da0ee6033215e"),
            "win-x64", release("win-x64", "d351096207fd94aab14a9656edbe3a4d1ebd70777891a9dcba7495789cf33b8b"));

    /** The jars are 8-13 MB. */
    static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

    // A plain file name directly in the platform's directory: no separator, and no leading dot, so no "..".
    private static final Pattern LIBRARY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*+");

    public enum Source { PROVIDED, BUNDLED, DOWNLOADED, MISSING, UNSUPPORTED }

    /** @param dir the extracted libraries; set only when {@code source} is DOWNLOADED */
    public record Status(Source source, @Nullable String classifier, @Nullable Path dir) {}

    public static @Nullable Build build(String classifier) {
        return BUILDS.get(classifier);
    }

    /** sherpa's own name for a platform, as {@code LibraryUtils.getOsArch} derives it; null where it throws. */
    public static @Nullable String classifier(String osName, String osArch) {
        var name = osName.toLowerCase(Locale.ENGLISH);
        String os;
        if (name.contains("mac") || name.contains("darwin")) os = "osx";
        else if (name.contains("win")) os = "win";
        else if (name.contains("nux")) os = "linux";
        else return null;
        var arch = osArch.toLowerCase(Locale.ENGLISH);
        String cpu;
        if (arch.startsWith("amd64") || arch.startsWith("x86_64")) cpu = "x64";
        else if (arch.startsWith("x86")) cpu = "x86";
        else if (arch.startsWith("aarch64") || arch.startsWith("arm64")) cpu = "aarch64";
        else if (arch.startsWith("arm")) cpu = "arm";
        else return null;
        return os + "-" + cpu;
    }

    public static @Nullable String hostClassifier() {
        return classifier(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** The JNI library's file name on {@code classifier}'s OS, as {@code System.mapLibraryName} gives it there. */
    public static String jniLibrary(String classifier) {
        if (classifier.startsWith("win")) return "sherpa-onnx-jni.dll";
        return classifier.startsWith("osx") ? "libsherpa-onnx-jni.dylib" : "libsherpa-onnx-jni.so";
    }

    public static Path cacheRoot() {
        return Play.applicationPath.toPath().resolve("data").resolve("sherpa-onnx-native");
    }

    /** Where a downloaded platform's libraries live: versioned, so a sherpa bump never reuses stale ones. */
    public static Path installDir(Path cacheRoot, String classifier) {
        return cacheRoot.resolve(VERSION + "-" + classifier);
    }

    /**
     * In {@code LibraryUtils.load}'s own precedence: a directory the operator named in {@link #NATIVE_PATH}, then
     * a native-lib jar on the classpath, then a download.
     */
    public static Status resolve(Path cacheRoot, @Nullable String nativePath, ClassLoader classLoader,
                                 @Nullable String classifier) {
        if (nativePath != null && !nativePath.isBlank()) return new Status(Source.PROVIDED, classifier, null);
        if (classifier == null) return new Status(Source.UNSUPPORTED, null, null);
        if (classLoader.getResource("sherpa-onnx/native/" + classifier + "/" + jniLibrary(classifier)) != null) {
            return new Status(Source.BUNDLED, classifier, null);
        }
        var dir = installDir(cacheRoot, classifier);
        if (Files.isRegularFile(dir.resolve(jniLibrary(classifier)))) return new Status(Source.DOWNLOADED, classifier, dir);
        return new Status(BUILDS.containsKey(classifier) ? Source.MISSING : Source.UNSUPPORTED, classifier, null);
    }

    /** Fetches {@code build}'s jar and {@link #install}s it. */
    public static Path download(Build build, Path cacheRoot) throws IOException {
        Files.createDirectories(cacheRoot);
        var jar = Files.createTempFile(cacheRoot, "sherpa-", ".part");
        try {
            PinnedDownload.fetch(build.url(), build.sha256(), MAX_JAR_BYTES, jar, _ -> {});
            return install(build, jar, cacheRoot);
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    /**
     * Checks {@code jar} against {@code build}'s SHA-256 and extracts the libraries in its platform's directory
     * to {@link #installDir}. Nothing reaches that directory unless every step succeeded, so a failed attempt
     * is simply retried.
     */
    @SuppressWarnings("java:S5042") // the jar matches a pinned SHA-256 before it is opened, so its expansion is known
    public static Path install(Build build, Path jar, Path cacheRoot) throws IOException {
        var actual = PinnedDownload.sha256(jar);
        if (!actual.equalsIgnoreCase(build.sha256())) {
            throw new IOException("checksum mismatch for " + build.url() + ": expected " + build.sha256() + ", got " + actual);
        }
        var prefix = "sherpa-onnx/native/" + build.classifier() + "/";
        var jni = jniLibrary(build.classifier());
        Files.createDirectories(cacheRoot);
        var staged = Files.createTempDirectory(cacheRoot, "sherpa-" + build.classifier() + "-");
        try {
            try (var zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar)))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    var name = e.getName();
                    if (e.isDirectory() || !name.startsWith(prefix)) continue;
                    var file = name.substring(prefix.length());
                    // Copied to a path chosen here, so an entry's own name can never direct a write elsewhere.
                    if (LIBRARY_NAME.matcher(file).matches()) {
                        Files.copy(zip, staged.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (!Files.isRegularFile(staged.resolve(jni))) throw new IOException(prefix + jni + " not found in " + build.url());
            var target = installDir(cacheRoot, build.classifier());
            deleteTree(target);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            deleteTree(staged);
        }
    }

    /**
     * Loads every library in {@code dir} but the JNI one, then points sherpa at {@code dir}. From a directory
     * sherpa loads only its JNI library, while its own jar path loads ONNX Runtime first on every platform; this
     * does the same. The JNI library is never loaded here: its native methods bind to the class loader that
     * loads it, which must be sherpa's. ONNX Runtime has none, so any class loader will do.
     */
    public static void activate(Path dir, String classifier) throws IOException {
        var jni = jniLibrary(classifier);
        try (Stream<Path> files = Files.list(dir)) {
            for (var library : files.filter(f -> !f.getFileName().toString().equals(jni)).sorted().toList()) {
                System.load(library.toAbsolutePath().toString());
            }
        }
        System.setProperty(NATIVE_PATH, dir.toAbsolutePath().toString());
    }

    /** Makes sherpa's native libraries loadable on this host, downloading them the first time. */
    public static synchronized void ensureLoadable() {
        var classifier = hostClassifier();
        var status = resolve(cacheRoot(), System.getProperty(NATIVE_PATH), LibraryUtils.class.getClassLoader(), classifier);
        try {
            switch (status.source()) {
                case PROVIDED, BUNDLED -> { }
                case DOWNLOADED -> activate(Objects.requireNonNull(status.dir()), Objects.requireNonNull(classifier));
                case MISSING -> {
                    var build = Objects.requireNonNull(build(Objects.requireNonNull(classifier)));
                    EventLogger.info("voice", "Downloading the sherpa-onnx native library for %s from %s"
                            .formatted(classifier, build.url()));
                    activate(download(build, cacheRoot()), classifier);
                }
                case UNSUPPORTED -> throw new IllegalStateException("sherpa-onnx has no native library for "
                        + System.getProperty("os.name") + " on " + System.getProperty("os.arch"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not provide the sherpa-onnx native library for " + classifier
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code construct}, a sherpa constructor that loads its JNI library, once the libraries are loadable.
     * A library that still fails to load surfaces as an {@link IllegalStateException}: the voice session's and
     * TtsRouter's handlers catch exceptions, and a {@link LinkageError} would skip them.
     */
    public static <T> T load(Supplier<T> construct) {
        try {
            ensureLoadable();
            return construct.get();
        } catch (LinkageError e) {
            throw new IllegalStateException("sherpa-onnx native library unavailable: " + e, e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
