package services;

import models.Agent;
import org.jspecify.annotations.Nullable;
import play.Play;
import utils.AppClock;
import utils.WorkspacePathGuard;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The files an update of an agent's skill takes out of the old copy before the registry's copy replaces it (JF-352):
 * what the operator put there, which the registry never ships. They live in
 * {@code <root>/<workspace owner's id>/<skill>/<UTC time>/}, by default beneath {@code data/}, never in a workspace,
 * and {@link utils.SandboxHiddenRoots} hides the root from every sandbox and the Main Agent's fence wherever it is. No
 * method hands a kept file's content to anything but the Secrets panel's import, which stores it.
 */
public final class KeptSkillFiles {

    public static final String ROOT_PROPERTY = "jclaw.skills.keptPath";
    private static final String DEFAULT_ROOT = "data/skill-kept";
    private static final String LOG_CATEGORY = "skill";
    private static final String CREDENTIALS_DIR = "credentials";
    private static final String EXAMPLE_SUFFIX = ".example";
    private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'");
    /** A folder per update, named for its UTC time; a second update in the same millisecond takes a suffix. */
    private static final Pattern FOLDER = Pattern.compile("(\\d{8}T\\d{6}\\.\\d{3}Z)(?:-\\d{1,4})?");
    /** Four bytes for each character the store takes, so an import never reads a large file into memory. */
    public static final long MAX_IMPORT_BYTES = 4L * SkillSecrets.MAX_VALUE_CHARS;
    /** {@code [export] name=rest}; {@link #reading} says which names make it an assignment, spaces around {@code =} or not. */
    private static final Pattern ASSIGNMENT = Pattern.compile("\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)");
    /** In an unquoted value, a {@code #} that starts a word starts a comment. */
    private static final Pattern COMMENT = Pattern.compile("\\s#");
    private static final String DQ_ESCAPED = "\"\\$`";
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /** One kept file as the panel lists it: never its content. {@code id} names it to the routes. */
    public record Kept(String id, String path, long size, Instant keptAt) {}

    private KeptSkillFiles() {}

    /** Relative paths resolve against the application root, so a subprocess and the server agree whatever their cwd. */
    public static Path root() {
        var configured = Play.configuration.getProperty(ROOT_PROPERTY, DEFAULT_ROOT);
        var path = Path.of(configured.isBlank() ? DEFAULT_ROOT : configured.strip());
        return (path.isAbsolute() ? path : Play.applicationPath.toPath().resolve(path)).normalize();
    }

    /**
     * The files of {@code oldCopy} an update keeps, as slash-separated paths in order: each regular file under its
     * {@code credentials/} folder, and each file {@code X} beside an {@code X.example} in either copy, unless {@code newVersion} ships
     * the same bytes at that path. A link is never kept or followed, the old copy's own folder included.
     */
    public static List<String> select(Path oldCopy, Path newVersion) throws IOException {
        if (!Files.isDirectory(oldCopy, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var out = new TreeSet<String>();
        try (var walk = Files.walk(oldCopy)) {
            for (var file : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                var relative = oldCopy.relativize(file);
                if (!underCredentials(relative) && !besideItsExample(file, newVersion.resolve(relative.toString()))) continue;
                if (shipsTheSame(newVersion.resolve(relative.toString()), file)) continue;
                out.add(slashed(relative));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return List.copyOf(out);
    }

    // Any letter case, as the routes that mask a skill's credentials judge the folder.
    private static boolean underCredentials(Path relative) {
        return relative.getNameCount() > 1 && relative.getName(0).toString().equalsIgnoreCase(CREDENTIALS_DIR);
    }

    /** {@code shipped} is where the new version would put {@code file}, whose example may ship only there. */
    private static boolean besideItsExample(Path file, Path shipped) {
        var name = file.getFileName() + EXAMPLE_SUFFIX;
        return Files.isRegularFile(file.resolveSibling(name), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(shipped.resolveSibling(name), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean shipsTheSame(Path shipped, Path file) throws IOException {
        return Files.isRegularFile(shipped, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(shipped, file) == -1L;
    }

    private static String slashed(Path relative) {
        return relative.toString().replace(File.separatorChar, '/');
    }

    /**
     * Copy what {@link #select} picks out of {@code oldCopy} into a new folder of {@code owner}'s kept files for
     * {@code skill}, before the old copy is replaced. Returns that folder, or null when nothing is kept. A failure
     * removes what it copied, so the caller can refuse the update with the old copy untouched.
     */
    public static @Nullable Path copyOut(Agent owner, String skill, Path oldCopy, Path newVersion) throws IOException {
        var selected = select(oldCopy, newVersion);
        if (selected.isEmpty()) return null;
        var folder = newFolder(skillDir(owner, skill));
        var copied = new ArrayList<String>();
        try {
            var realOld = oldCopy.toRealPath();
            for (var path : selected) {
                var source = oldCopy.resolve(path);
                // The agent can write its copy while this reads it: a file, or a folder above it, that became a link
                // since the walk is skipped rather than followed.
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().startsWith(realOld)) {
                    continue;
                }
                var target = folder.resolve(path);
                Files.createDirectories(target.getParent());
                try (var in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(in, target);
                }
                copied.add(path);
            }
        } catch (IOException | RuntimeException e) {
            discard(folder);
            throw e;
        }
        if (copied.isEmpty()) {
            discard(folder);
            return null;
        }
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Kept %d file(s) of skill %s's old copy before the update: %s"
                .formatted(copied.size(), skill, String.join(", ", copied)));
        return folder;
    }

    private static Path newFolder(Path skillDir) throws IOException {
        Files.createDirectories(skillDir);
        var stamp = FOLDER_TIME.format(LocalDateTime.ofInstant(AppClock.now(), ZoneOffset.UTC));
        for (int n = 1; ; n++) {
            var folder = skillDir.resolve(n == 1 ? stamp : stamp + "-" + n);
            try {
                Files.createDirectory(folder);
            } catch (FileAlreadyExistsException e) {
                if (n == 9_999) throw e;
                continue;
            }
            // A kept file is often a credential: only the account JClaw runs as reads it.
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
            }
            return folder;
        }
    }

    /** Remove a folder {@link #copyOut} made, as when the swap it was made for failed. */
    public static void discard(Path folder) {
        try {
            AtomicDirSwap.deleteRecursive(folder);
            pruneEmpty(folder.getParent());
        } catch (IOException | UncheckedIOException e) {
            EventLogger.warn(LOG_CATEGORY, "Could not remove kept files at " + folder + ": " + e.getMessage());
        }
    }

    /** {@code owner}'s kept files for {@code skill}, newest update first; empty when the name is no skill name. */
    public static List<Kept> list(Agent owner, String skill) throws IOException {
        if (!WorkspacePathGuard.isOneSegment(skill)) return List.of();
        var dir = skillDir(owner, skill);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var folders = new ArrayList<Path>();
        try (var entries = Files.list(dir)) {
            entries.filter(p -> FOLDER.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(folders::add);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        folders.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        var out = new ArrayList<Kept>();
        for (var folder : folders) {
            var name = folder.getFileName().toString();
            var m = FOLDER.matcher(name);
            if (!m.matches()) continue;
            var keptAt = LocalDateTime.parse(m.group(1), FOLDER_TIME).toInstant(ZoneOffset.UTC);
            var files = new ArrayList<Kept>();
            try (var walk = Files.walk(folder)) {
                for (var file : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    var path = slashed(folder.relativize(file));
                    files.add(new Kept(encode(name + "/" + path), path, Files.size(file), keptAt));
                }
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            files.sort(Comparator.comparing(Kept::path));
            out.addAll(files);
        }
        return out;
    }

    /** Whether {@code id} has the shape of a kept file's id: it names a file in an update's folder, never a path out. */
    public static boolean isId(String id) {
        return decode(id) != null;
    }

    /** The kept file {@code id} names among {@code owner}'s for {@code skill}, or null when it names none. */
    public static @Nullable Path find(Agent owner, String skill, String id) {
        var relative = decode(id);
        if (relative == null || !WorkspacePathGuard.isOneSegment(skill)) return null;
        try {
            var file = skillDir(owner, skill).resolve(relative);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            var realRoot = root().toRealPath();
            return file.toRealPath().startsWith(realRoot) ? file : null;
        } catch (IOException _) {
            return null;
        }
    }

    /** The path a kept file had in the skill's old copy, for the operator's messages. */
    public static String pathOf(Path file) {
        var relative = root().relativize(file);
        return relative.getNameCount() > 3 ? slashed(relative.subpath(3, relative.getNameCount())) : slashed(relative);
    }

    /**
     * Store secret {@code name} of {@code skill} from kept {@code file}, as {@link #reading} reads it, and delete the
     * file once that commits if each name it assigns that the skill in {@code skillDir} declares then has a value.
     */
    public static SkillSecrets.Listing importSecret(Agent owner, String skill, Path skillDir, Path file, String name,
                                                    List<String> hosts) throws IOException {
        var text = text(file);
        var reading = reading(text, name, pathOf(file));
        var saved = SkillSecrets.put(owner, skill, name, reading.value(), hosts);
        afterImport(owner, skill, skillDir, file, reading.whole() ? null : text, "secret " + name);
        return saved;
    }

    /** As {@link #importSecret}, for setting {@code name}, which only a {@code NAME=value} line gives. */
    public static SkillSettings.Listing importSetting(Agent owner, String skill, Path skillDir, Path file, String name)
            throws IOException {
        var text = text(file);
        var reading = reading(text, name, pathOf(file));
        // A whole file is most often a key, which a setting would show in clear and hand to every command.
        if (reading.whole()) throw new SkillSecrets.Refused("A setting takes its value from a NAME=value line.");
        var saved = SkillSettings.put(owner, skill, name, reading.value());
        afterImport(owner, skill, skillDir, file, text, "setting " + name);
        return saved;
    }

    /** What a kept file's text gives one name; {@code whole} when the text has no assignment and is the value. */
    public record Reading(String value, boolean whole) {}

    /**
     * What {@code text}, the content of the kept file at {@code path}, gives {@code name}. A plain assignment is a line
     * {@code NAME=value}, {@code export} before it allowed, whose name the store takes or is {@code name} in any letter
     * case; the last for {@code name} gives the value, a quoted one up to its closing quote, an unquoted one up to a
     * {@code " #"} comment, trimmed. A value only a shell could read is refused: a quote not closed on its line, or a
     * {@code $} outside single quotes. Text with no assignment is one value, trimmed.
     */
    public static Reading reading(String text, String name, String path) {
        String found = null;
        boolean assigns = false;
        for (var line : text.split("\\R")) {
            var m = ASSIGNMENT.matcher(line);
            if (!m.matches()) continue;
            if (m.group(1).equalsIgnoreCase(name)) {
                found = m.group(2);
                assigns = true;
            } else if (SkillSecrets.NAME.matcher(m.group(1)).matches()) {
                assigns = true;
            }
        }
        if (!assigns) return new Reading(text.strip(), true);
        if (found == null) throw new SkillSecrets.Refused("The kept file %s has no line %s=….".formatted(path, name));
        return new Reading(plainValue(found, path, name), false);
    }

    private static String plainValue(String raw, String path, String name) {
        var trimmed = raw.strip();
        if (!trimmed.isEmpty() && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')) {
            char quote = trimmed.charAt(0);
            var out = new StringBuilder();
            for (int i = 1; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == quote) return out.toString();
                if (quote == '"' && c == '\\' && i + 1 < trimmed.length() && DQ_ESCAPED.indexOf(trimmed.charAt(i + 1)) >= 0) {
                    c = trimmed.charAt(++i);
                } else if (quote == '"' && c == '$') {
                    throw needsAShell(path, name);
                }
                out.append(c);
            }
            throw new SkillSecrets.Refused(("The kept file %s gives %s a quoted value with no closing quote on its line,"
                    + " which JClaw does not read: type the value instead.").formatted(path, name));
        }
        var comment = COMMENT.matcher(raw);
        var value = (comment.find() ? raw.substring(0, comment.start()) : raw).strip();
        var out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == '$') {
                c = value.charAt(++i);
            } else if (c == '$') {
                throw needsAShell(path, name);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static SkillSecrets.Refused needsAShell(String path, String name) {
        return new SkillSecrets.Refused(("The kept file %s gives %s a value with $ outside single quotes, which only a"
                + " shell would expand: type the value instead.").formatted(path, name));
    }

    private static String text(Path file) throws IOException {
        if (Files.size(file) > MAX_IMPORT_BYTES) {
            throw new SkillSecrets.Refused("The kept file %s is larger than any value JClaw stores.".formatted(pathOf(file)));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                    .toString();
        } catch (CharacterCodingException _) {
            throw new SkillSecrets.Refused("The kept file %s is not text.".formatted(pathOf(file)));
        }
        // An editor's byte-order mark would otherwise lead the stored value.
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /**
     * Delete the imported {@code file} once the store commits, unless {@code text}, its assignments (null for a file
     * that was one value), names a value the skill declares that the agent still lacks: a rolled-back write, or a value
     * still waiting, keeps it.
     */
    private static void afterImport(Agent owner, String skill, Path skillDir, Path file, @Nullable String text,
                                    String storedAs) {
        var waiting = text == null ? List.<String>of() : waiting(owner, declaredByUpperCase(skillDir), text);
        if (waiting.isEmpty()) {
            deleteAfterCommit(owner, skill, file, "Kept file %s of skill %s stored as %s and deleted"
                    .formatted(pathOf(file), skill, storedAs));
            return;
        }
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Kept file %s of skill %s stored as %s and kept for %s"
                .formatted(pathOf(file), skill, storedAs, String.join(", ", waiting)));
    }

    /**
     * After a value of {@code skill} is stored, typed or imported: delete, once that commits, each of its kept files
     * whose assignments name a value the skill declares and none the agent still lacks. {@code imported}, the file the
     * value came from if any, is left to the import.
     */
    public static void deleteUsedUp(Agent owner, String skill, Path skillDir, @Nullable Path imported) {
        List<Kept> kept;
        try {
            kept = list(owner, skill);
        } catch (IOException | UncheckedIOException e) {
            EventLogger.warn(LOG_CATEGORY, owner.name, null,
                    "Could not list the kept files of skill %s: %s".formatted(skill, e.getMessage()));
            return;
        }
        var declared = kept.isEmpty() ? Map.<String, String>of() : declaredByUpperCase(skillDir);
        if (declared.isEmpty()) return;
        for (var k : kept) {
            var file = find(owner, skill, k.id());
            if (file == null || file.equals(imported)) continue;
            String text;
            try {
                text = text(file);
            } catch (IOException | SkillSecrets.Refused _) {
                continue;
            }
            if (!declaredIn(text, declared).isEmpty() && waiting(owner, declared, text).isEmpty()) {
                deleteAfterCommit(owner, skill, file, "Kept file %s of skill %s deleted: each name in it the skill"
                        .formatted(pathOf(file), skill) + " declares has a value");
            }
        }
    }

    /** The names the skill in {@code skillDir} declares, secrets and settings, by their upper-case spelling. */
    private static Map<String, String> declaredByUpperCase(Path skillDir) {
        var out = new HashMap<String, String>();
        for (var d : SkillSecrets.declared(skillDir)) out.put(d.name().toUpperCase(Locale.ROOT), d.name());
        for (var d : SkillSecrets.declaredSettings(skillDir)) out.put(d.name().toUpperCase(Locale.ROOT), d.name());
        return out;
    }

    /** The declared names {@code text} assigns, in any letter case, each once and in order. */
    private static List<String> declaredIn(String text, Map<String, String> declared) {
        var out = new LinkedHashSet<String>();
        for (var line : text.split("\\R")) {
            var m = ASSIGNMENT.matcher(line);
            if (!m.matches()) continue;
            var name = declared.get(m.group(1).toUpperCase(Locale.ROOT));
            if (name != null) out.add(name);
        }
        return List.copyOf(out);
    }

    /** The declared names {@code text} assigns that the agent holds no value for, in order. */
    private static List<String> waiting(Agent owner, Map<String, String> declared, String text) {
        var assigned = declaredIn(text, declared);
        if (assigned.isEmpty()) return List.of();
        var provided = SkillSecrets.provided(owner);
        return assigned.stream().filter(name -> provided.holder(name) == null).toList();
    }

    private static void deleteAfterCommit(Agent owner, String skill, Path file, String done) {
        var agent = owner.name;
        var path = pathOf(file);
        Tx.afterCommit(() -> {
            try {
                Files.deleteIfExists(file);
                pruneEmpty(file.getParent());
                EventLogger.info(LOG_CATEGORY, agent, null, done);
            } catch (IOException e) {
                EventLogger.warn(LOG_CATEGORY, agent, null,
                        "Kept file %s of skill %s could not be deleted: %s".formatted(path, skill, e.getMessage()));
            }
        });
    }

    /** Delete one kept file; false when there was none. */
    public static boolean delete(Agent owner, String skill, String id) throws IOException {
        var file = find(owner, skill, id);
        if (file == null) return false;
        var path = pathOf(file);
        Files.delete(file);
        pruneEmpty(file.getParent());
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Kept file %s of skill %s deleted".formatted(path, skill));
        return true;
    }

    /** Delete every kept file of a skill the operator removes from the agent, as its secrets go with it. */
    public static void deleteForSkill(Agent owner, String skill) throws IOException {
        if (!WorkspacePathGuard.isOneSegment(skill)) return;
        var dir = skillDir(owner, skill);
        AtomicDirSwap.deleteRecursive(dir);
        pruneEmpty(dir.getParent());
    }

    /** Delete every kept file of a deleted agent. */
    public static void deleteForAgent(long agentId) throws IOException {
        AtomicDirSwap.deleteRecursive(root().resolve(String.valueOf(agentId)));
    }

    private static Path skillDir(Agent owner, String skill) throws IOException {
        if (!WorkspacePathGuard.isOneSegment(skill)) throw new IOException("'" + skill + "' is not a skill name");
        return root().resolve(String.valueOf(owner.id)).resolve(skill);
    }

    /** Remove {@code dir} and each parent above it that is left empty, up to the root itself. */
    private static void pruneEmpty(@Nullable Path dir) throws IOException {
        var root = root();
        for (var p = dir; p != null && p.startsWith(root) && !p.equals(root); p = p.getParent()) {
            if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var entries = Files.list(p)) {
                if (entries.findAny().isPresent()) return;
            }
            Files.delete(p);
        }
    }

    private static String encode(String relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code <folder>/<path>} an id encodes, or null when it is not one: no empty, dot or dot-dot segment. */
    private static @Nullable String decode(String id) {
        if (id.isEmpty() || id.length() > 4096) return null;
        String relative;
        try {
            relative = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(id)))
                    .toString();
        } catch (IllegalArgumentException | CharacterCodingException _) {
            return null;
        }
        var segments = relative.split("/", -1);
        if (segments.length < 2 || !FOLDER.matcher(segments[0]).matches()) return null;
        for (var segment : segments) {
            if (!WorkspacePathGuard.isOneSegment(segment)) return null;
        }
        return relative;
    }
}
