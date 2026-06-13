import it.auties.whatsapp.util.Medias;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.io.ByteArrayOutputStream;

/**
 * Verifies the {@code com.aspose.words} shim (JCLAW-451) satisfies Cobalt's REAL
 * {@code it.auties.whatsapp.util.Medias} against an actual PDF: the static link
 * resolves (no {@code NoClassDefFoundError}) and PDFBox backs page-count +
 * first-page-thumbnail correctly. This is the definitive check that the shim sits
 * where Cobalt's loader can see it — if it doesn't, these calls throw.
 */
class WhatsAppCobaltMediaShimTest extends UnitTest {

    private static byte[] samplePdf(int pages) throws Exception {
        try (var doc = new PDDocument(); var baos = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    void mediasPageCountResolvesThroughTheShim() throws Exception {
        var pdf = samplePdf(3);
        // Medias.getPagesCount -> new com.aspose.words.Document(...).getPageCount().
        // Resolves the shim or throws NoClassDefFoundError.
        assertEquals(3, Medias.getPagesCount(pdf));
    }

    @Test
    void mediasDocumentThumbnailRendersAJpegThroughTheShim() throws Exception {
        var pdf = samplePdf(1);
        byte[] thumb = Medias.getDocumentThumbnail(pdf);
        assertNotNull(thumb);
        assertTrue(thumb.length > 0, "thumbnail must have bytes");
        // JPEG magic FF D8 — proves PDFBox actually rendered an image.
        assertEquals((byte) 0xFF, thumb[0]);
        assertEquals((byte) 0xD8, thumb[1]);
    }

    @Test
    void nonPdfDocumentDegradesGracefullyWithoutThrowing() {
        // A non-PDF (PDFBox can't parse it) must not throw — single-page count, the
        // thumbnail falls back to a blank JPEG. Proves the shim never breaks the
        // document-send path for DOCX/other formats.
        var notPdf = "this is not a pdf".getBytes();
        assertEquals(1, Medias.getPagesCount(notPdf));
        assertDoesNotThrow(() -> Medias.getDocumentThumbnail(notPdf));
    }
}
