import org.junit.jupiter.api.*;
import play.test.UnitTest;
import utils.Filenames;

class UtilsFilenamesTest extends UnitTest {

    @Test
    void extensionOfPrefersFirstNonEmptyCandidate() {
        assertEquals(".wav",
                Filenames.extensionOf("harvard.wav", "voice/file_42.oga"));
    }

    @Test
    void extensionOfFallsThroughToNextCandidate() {
        assertEquals(".oga",
                Filenames.extensionOf(null, "voice/file_42.oga"));
        assertEquals(".oga",
                Filenames.extensionOf("nodotinname", "voice/file_42.oga"));
    }

    @Test
    void extensionOfReturnsEmptyWhenAllCandidatesLackExtension() {
        assertEquals("",
                Filenames.extensionOf(null, "file_no_ext"));
        assertEquals("",
                Filenames.extensionOf("nodotinname", null));
        assertEquals("",
                Filenames.extensionOf());
    }

    @Test
    void extensionOfRejectsLeadingDotHiddenFile() {
        assertEquals("",
                Filenames.extensionOf(".gitignore"));
    }

    @Test
    void extensionOfRejectsTrailingDot() {
        assertEquals("",
                Filenames.extensionOf("foo."));
    }

    @Test
    void extensionOfIgnoresDotInParentDirectory() {
        // Dot is part of a parent dir name, not the file's extension.
        assertEquals("",
                Filenames.extensionOf("foo.d/no_ext"));
    }

    @Test
    void extensionOfHandlesWindowsBackslash() {
        assertEquals(".txt",
                Filenames.extensionOf("C:\\Users\\name\\doc.txt"));
    }

    @Test
    void extensionOfNullInputSafe() {
        assertEquals("",
                Filenames.extensionOf((String[]) null));
        assertEquals("",
                Filenames.extensionOf((String) null));
    }
}
