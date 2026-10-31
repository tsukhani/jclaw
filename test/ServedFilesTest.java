import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ServedFiles;

class ServedFilesTest extends UnitTest {

    @Test
    void aRasterImageIsOneABrowserCannotExecute() {
        assertTrue(ServedFiles.isRasterImage("image/png"));
        assertTrue(ServedFiles.isRasterImage("image/jpeg"));
        assertTrue(ServedFiles.isRasterImage("IMAGE/WEBP"));
    }

    @Test
    void anSvgIsNotARasterImageHoweverItIsSpelled() {
        assertFalse(ServedFiles.isRasterImage("image/svg+xml"));
        assertFalse(ServedFiles.isRasterImage("image/svg+xml-compressed"));
        assertFalse(ServedFiles.isRasterImage("IMAGE/SVG+XML"));
        assertFalse(ServedFiles.isRasterImage("image/svg+xml; charset=utf-8"));
    }

    @Test
    void anythingElseIsNotAnImage() {
        assertFalse(ServedFiles.isRasterImage("text/html"));
        assertFalse(ServedFiles.isRasterImage("audio/mpeg"));
        assertFalse(ServedFiles.isRasterImage(""));
        assertFalse(ServedFiles.isRasterImage(null));
    }
}
