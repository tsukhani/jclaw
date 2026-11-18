import org.jetbrains.jetCheck.Generator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.Filenames;

class FilenamesPropertyTest extends UnitTest {

    /** A file name built from a directory prefix, a base and the extension it was given. */
    record NamedFile(String candidate, String extension) {}

    /** Directory prefixes that themselves carry dots, the case {@code extensionOf} has to ignore. */
    private static final Generator<String> DOTTED_DIRECTORY_PREFIX =
            Generator.sampledFrom("", "dir/", "a.d/", "a.d/b.e/", "C:\\a.d\\", "./", "../x.y/");

    private static final Generator<NamedFile> NAMED_FILES = Generator.from(env -> {
        var prefix = env.generate(DOTTED_DIRECTORY_PREFIX);
        var base = env.generate(PropertyInputs.letters(1, 10));
        var extension = env.generate(PropertyInputs.letters(1, 6));
        return new NamedFile(prefix + base + "." + extension, extension);
    });

    // 200 iterations: pure string work on at most about 40 chars.
    @Test
    void extensionOfRecoversTheExtensionItWasGiven() {
        PropertyInputs.check(200, NAMED_FILES, file -> assertEquals("." + file.extension(),
                Filenames.extensionOf(file.candidate()),
                () -> "extensionOf lost the extension of " + PropertyInputs.quote(file.candidate())));
    }

    // 200 iterations: the same pure string work.
    @Test
    void extensionOfNeverCrossesAPathSeparator() {
        PropertyInputs.check(200, PropertyInputs.HOSTILE_NAMES, candidate -> {
            var ext = Filenames.extensionOf(candidate);
            if (ext.isEmpty()) return;
            var shown = PropertyInputs.quote(candidate) + " -> " + PropertyInputs.quote(ext);
            assertTrue(candidate.endsWith(ext), () -> "extension is not a suffix of " + shown);
            assertEquals('.', ext.charAt(0), () -> "extension must keep its leading dot for " + shown);
            assertFalse(ext.contains("/") || ext.contains("\\"), () -> "extension crossed a path separator for " + shown);
        });
    }
}
