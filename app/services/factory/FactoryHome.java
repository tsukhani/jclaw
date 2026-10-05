package services.factory;

import org.jspecify.annotations.Nullable;
import play.Play;
import services.ConfigService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The AFK factory's home directory and the files the JVM reads or writes there (JCLAW-1392).
 * The validation and IO methods take an explicit {@link Path} and read no seam.
 */
public final class FactoryHome {

    public static final String HOME_KEY = "factory.home";

    public static final String KEY_MAX_PARALLEL = "FACTORY_MAX_PARALLEL";
    public static final String KEY_CPUS = "FACTORY_CPUS";
    public static final String KEY_POLL_SECONDS = "FACTORY_POLL_SECONDS";
    public static final String KEY_MODEL = "FACTORY_MODEL";

    /** The keys {@code .sandcastle/paths.ts} accepts in {@code settings.env}, with the README's defaults. */
    public static final Map<String, String> SETTINGS;

    static {
        var m = new LinkedHashMap<String, String>();
        m.put(KEY_MAX_PARALLEL, "2");
        m.put(KEY_CPUS, "6");
        m.put(KEY_POLL_SECONDS, "120");
        m.put(KEY_MODEL, "claude-opus-5-5");
        SETTINGS = java.util.Collections.unmodifiableMap(m);
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    // A newline would smuggle a second line into settings.env.
    private static final Pattern WHITESPACE = Pattern.compile("\\s");
    // Sandcastle names a worktree after its branch, "/" as "-": agent/<KEY> builds, factory/land-<KEY> re-gates.
    private static final Pattern STORY_WORKTREE =
            Pattern.compile("/\\.sandcastle/worktrees/(?:agent|factory-land)-([A-Z][A-Z0-9]*-\\d+)$");

    private static volatile @Nullable Path homeOverride;

    private FactoryHome() {}

    /** Test-only: make {@link #home()} return {@code home} (or clear with {@code null}). */
    public static void setHomeForTest(@Nullable Path home) {
        homeOverride = home;
    }

    /**
     * The Config key {@value #HOME_KEY} when set and non-blank ({@code ~} expanded), else
     * {@code FACTORY_HOME}, else {@code ~/.jclaw-factory}.
     */
    public static Path home() {
        var override = homeOverride;
        if (override != null) return override;
        var configured = ConfigService.get(HOME_KEY);
        if (configured != null && !configured.isBlank()) return expandHome(configured.strip());
        var env = System.getenv("FACTORY_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".jclaw-factory");
    }

    static Path expandHome(String path) {
        var userHome = System.getProperty("user.home");
        if (path.equals("~")) return Path.of(userHome);
        if (path.startsWith("~/")) return Path.of(userHome, path.substring(2));
        return Path.of(path);
    }

    public static Path logsDir() {
        return home().resolve("logs");
    }

    public static Path boardFile() {
        return home().resolve("board.json");
    }

    public static Path installer() {
        return Play.applicationPath.toPath().resolve(".sandcastle").resolve("install-agent.sh");
    }

    public static Path settingsFile() {
        return home().resolve("settings.env");
    }

    /** The model credential, the one file the gateway mounts. */
    public static Path modelEnvFile() {
        return home().resolve(".env");
    }

    public static Path jiraEnvFile() {
        return home().resolve("jira.env");
    }

    public static Path githubEnvFile() {
        return home().resolve("github.env");
    }

    /** The three files {@code install-agent.sh} requires: itself, {@code <home>/.env} and {@code <home>/jira.env}. */
    public static boolean installed() {
        return Files.isRegularFile(installer())
                && Files.isRegularFile(modelEnvFile())
                && Files.isRegularFile(jiraEnvFile());
    }

    /**
     * {@code settings.env} parsed with {@code paths.ts}'s grammar: lines trimmed, a line with no
     * {@code =} or starting with {@code #} skipped, the value running from the first {@code =},
     * the last occurrence winning. A missing file is empty.
     */
    public static Map<String, String> readSettings(Path file) throws IOException {
        var out = new LinkedHashMap<String, String>();
        if (!Files.isRegularFile(file)) return out;
        for (var raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            var entry = parseLine(raw);
            if (entry != null) out.put(entry[0], entry[1]);
        }
        return out;
    }

    /** A credential {@code .env} file, read with {@link #readSettings}'s grammar. */
    public static Map<String, String> readEnv(Path file) throws IOException {
        return readSettings(file);
    }

    private static String @Nullable [] parseLine(String raw) {
        var line = raw.trim();
        var eq = line.indexOf('=');
        if (eq < 0 || line.startsWith("#")) return null;
        return new String[] {line.substring(0, eq), line.substring(eq + 1)};
    }

    /**
     * Why {@code updates} cannot be written, or null when every entry is valid.
     *
     * @param dockerCpus the Docker VM's CPU count, empty when unknown
     */
    public static @Nullable String validate(Map<String, String> updates, OptionalInt dockerCpus) {
        if (updates.isEmpty()) return "No settings given; accepted keys are " + String.join(", ", SETTINGS.keySet()) + ".";
        for (var e : updates.entrySet()) {
            var key = e.getKey();
            var value = e.getValue();
            if (!SETTINGS.containsKey(key)) {
                return key + " is not a factory setting; accepted keys are " + String.join(", ", SETTINGS.keySet()) + ".";
            }
            if (KEY_MODEL.equals(key)) {
                if (value.isBlank() || WHITESPACE.matcher(value).find()) {
                    return KEY_MODEL + " must be a non-blank model id without whitespace.";
                }
                continue;
            }
            var n = positiveInt(value);
            if (n == null) return key + " must be a positive integer.";
            if (KEY_CPUS.equals(key) && dockerCpus.isPresent() && n > dockerCpus.getAsInt()) {
                return KEY_CPUS + " must be at most the Docker VM's CPU count (" + dockerCpus.getAsInt() + ").";
            }
        }
        return null;
    }

    private static @Nullable Integer positiveInt(String value) {
        if (!DIGITS.matcher(value).matches()) return null;
        try {
            var n = Integer.parseInt(value);
            return n >= 1 ? n : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Rewrite {@code file} atomically with {@code updates} applied: a key already present is
     * replaced in place (every occurrence), a new key is appended, and every other line is kept
     * in order. Synchronized, so two concurrent writes cannot lose one's update.
     */
    public static synchronized void writeSettings(Path file, Map<String, String> updates) throws IOException {
        rewrite(file, updates, Set.of(), ".settings.env.");
    }

    /**
     * {@link #writeSettings} for a credential file, also dropping every line whose key is in
     * {@code remove}. The parent is created owner-only if missing, and the file is written
     * owner-only ({@code 0600}) whatever mode it had before.
     */
    public static synchronized void writeEnvFile(Path file, Map<String, String> set, Set<String> remove)
            throws IOException {
        var dir = parentOf(file);
        if (!Files.isDirectory(dir)) {
            if (posix(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectories(dir);
        }
        rewrite(file, set, remove, "." + file.getFileName() + ".");
    }

    private static Path parentOf(Path file) {
        var dir = file.toAbsolutePath().getParent();
        if (dir == null) throw new IllegalArgumentException("no parent directory: " + file);
        return dir;
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void rewrite(Path file, Map<String, String> updates, Set<String> remove, String tmpPrefix)
            throws IOException {
        var lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new ArrayList<String>();
        var pending = new LinkedHashSet<>(updates.keySet());
        var out = new ArrayList<String>(lines.size() + pending.size());
        for (var line : lines) {
            var entry = parseLine(line);
            if (entry != null && remove.contains(entry[0])) continue;
            if (entry != null && updates.containsKey(entry[0])) {
                out.add(entry[0] + "=" + updates.get(entry[0]));
                pending.remove(entry[0]);
            } else {
                out.add(line);
            }
        }
        for (var key : pending) out.add(key + "=" + updates.get(key));

        var dir = parentOf(file);
        FileAttribute<?>[] ownerOnly = posix(dir)
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        var tmp = Files.createTempFile(dir, tmpPrefix, ".tmp", ownerOnly);
        try {
            Files.writeString(tmp, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * The story whose worktree a sandbox mounts, read from {@code docker inspect}'s mount sources
     * (one per line), or null when none is a story's: the factory's planning sandbox, say.
     */
    public static @Nullable String storyFromMounts(String mountSources) {
        for (var line : mountSources.lines().map(String::trim).toList()) {
            var m = STORY_WORKTREE.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

}
