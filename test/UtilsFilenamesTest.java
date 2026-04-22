import org.junit.jupiter.api.*;
import play.test.UnitTest;
import utils.Filenames;

public class UtilsFilenamesTest extends UnitTest {

    @Test
    public void extensionOfPrefersFirstNonEmptyCandidate() {
        assertEquals(".wav",
                Filenames.extensionOf("harvard.wav", "voice/file_42.oga"));
    }

    @Test
    public void extensionOfFallsThroughToNextCandidate() {
        assertEquals(".oga",
                Filenames.extensionOf(null, "voice/file_42.oga"));
        assertEquals(".oga",
                Filenames.extensionOf("nodotinname", "voice/file_42.oga"));
    }

    @Test
    public void extensionOfReturnsEmptyWhenAllCandidatesLackExtension() {
        assertEquals("",
                Filenames.extensionOf(null, "file_no_ext"));
        assertEquals("",
                Filenames.extensionOf("nodotinname", null));
        assertEquals("",
                Filenames.extensionOf());
    }

    @Test
    public void extensionOfRejectsLeadingDotHiddenFile() {
        assertEquals("",
                Filenames.extensionOf(".gitignore"));
    }

    @Test
    public void extensionOfRejectsTrailingDot() {
        assertEquals("",
                Filenames.extensionOf("foo."));
    }

    @Test
    public void extensionOfIgnoresDotInParentDirectory() {
        // Dot is part of a parent dir name, not the file's extension.
        assertEquals("",
                Filenames.extensionOf("foo.d/no_ext"));
    }

    @Test
    public void extensionOfHandlesWindowsBackslash() {
        assertEquals(".txt",
                Filenames.extensionOf("C:\\Users\\name\\doc.txt"));
    }

    @Test
    public void extensionOfNullInputSafe() {
        assertEquals("",
                Filenames.extensionOf((String[]) null));
        assertEquals("",
                Filenames.extensionOf((String) null));
    }
}
