package services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Unpacks an uploaded skill zip into a staging folder, refusing what a zip can claim but a skill
 * folder must not hold: a path out of the folder, an entry listed twice, more files or more bytes
 * than any skill needs. macOS's {@code __MACOSX/} and {@code .DS_Store} are skipped.
 */
public final class SkillZip {

    private static final String SKILL_MD = "SKILL.md";
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:.*+");

    /** What one zip may unpack to; a zip can declare far more than it holds. */
    public record Limits(int maxEntries, long maxInflatedBytes) {}

    public static final Limits DEFAULT_LIMITS = new Limits(2_000, 200L * 1024 * 1024);

    private SkillZip() {}

    /**
     * Unpack {@code zip} into {@code dest} and return the skill's folder: {@code dest} itself when
     * SKILL.md sits at the top of the zip, else the zip's one top folder holding a SKILL.md, the
     * layout JClaw's export and Claude's both use.
     *
     * @throws IllegalArgumentException with the reason, for a zip that is not a skill or not safe
     */
    public static Path extract(Path zip, Path dest, Limits limits) throws IOException {
        var root = dest.toAbsolutePath().normalize();
        int entries = 0;
        long inflated = 0;
        try (var file = new ZipFile(zip.toFile())) {
            for (var entry : file.stream().toList()) {
                var name = entry.getName();
                if (entry.isDirectory() || skipped(name)) continue;
                if (++entries > limits.maxEntries()) {
                    throw new IllegalArgumentException("the zip holds more than %d files".formatted(limits.maxEntries()));
                }
                var target = target(root, name);
                Files.createDirectories(target.getParent());
                try (var in = file.getInputStream(entry);
                     var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    inflated += copy(in, out, limits.maxInflatedBytes() - inflated, limits.maxInflatedBytes());
                } catch (FileAlreadyExistsException _) {
                    throw new IllegalArgumentException("the zip lists a file twice: " + name);
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("not a readable zip: " + e.getMessage());
        }
        return skillRoot(root);
    }

    public static Path extract(Path zip, Path dest) throws IOException {
        return extract(zip, dest, DEFAULT_LIMITS);
    }

    private static boolean skipped(String name) {
        return name.startsWith("__MACOSX/") || name.equals(".DS_Store") || name.endsWith("/.DS_Store");
    }

    private static Path target(Path root, String name) {
        var escapes = name.startsWith("/") || name.contains("\\") || DRIVE.matcher(name).matches()
                || Arrays.asList(name.split("/")).contains("..");
        if (escapes) throw new IllegalArgumentException("the zip holds a path outside its folder: " + name);
        var target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("the zip holds a path outside its folder: " + name);
        }
        return target;
    }

    private static long copy(InputStream in, OutputStream out, long remaining, long limit) throws IOException {
        var buffer = new byte[64 * 1024];
        long copied = 0;
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            copied += read;
            if (copied > remaining) {
                throw new IllegalArgumentException("the zip unpacks to more than %d MB".formatted(limit / (1024 * 1024)));
            }
            out.write(buffer, 0, read);
        }
        return copied;
    }

    private static Path skillRoot(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve(SKILL_MD))) return root;
        var folders = new ArrayList<Path>();
        try (var top = Files.list(root)) {
            top.forEach(folders::add);
        }
        if (folders.size() == 1 && Files.isRegularFile(folders.getFirst().resolve(SKILL_MD))) return folders.getFirst();
        throw new IllegalArgumentException("the zip has no SKILL.md at its top or in a single top folder");
    }
}
