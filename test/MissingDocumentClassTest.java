import org.apache.tika.Tika;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import play.test.UnitTest;
import tools.DocumentsTool;
import utils.WebExtraction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * poi-ooxml-lite carries only the OOXML schema classes POI's own tests use, so a document that needs another
 * one fails with a NoClassDefFoundError. Both read paths report it as an unreadable document; as an Error it
 * escaped them, and ToolRegistry, which catches exceptions, let it out of the tool call too.
 */
class MissingDocumentClassTest extends UnitTest {

    private static final String MISSING = "org/openxmlformats/schemas/drawingml/x2006/chart/CTBubbleChart";
    private static final String URL = "https://example.invalid/chart.docx";

    /** A parser that fails as Tika's OOXML parser would on a schema class it cannot load. */
    private static Parser failingWith(Throwable failure) {
        return new Parser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext context) {
                return Set.of(MediaType.OCTET_STREAM);
            }

            @Override
            public void parse(TikaInputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
                    throws TikaException {
                if (failure instanceof TikaException e) throw e;
                throw (Error) failure;
            }
        };
    }

    @Test
    void aDocumentNeedingAMissingClassIsReportedAsUnreadable(@TempDir Path dir) throws Exception {
        var file = Files.write(dir.resolve("chart.docx"), new byte[]{1, 2, 3});

        assertEquals("Error parsing document: java.lang.NoClassDefFoundError: " + MISSING,
                DocumentsTool.readDocument(file, failingWith(new NoClassDefFoundError(MISSING))));
    }

    @Test
    void aDocumentTikaRejectsIsReportedAsBefore(@TempDir Path dir) throws Exception {
        var file = Files.write(dir.resolve("chart.docx"), new byte[]{1, 2, 3});

        assertEquals("Error parsing document: not an OOXML package",
                DocumentsTool.readDocument(file, failingWith(new TikaException("not an OOXML package"))));
    }

    @Test
    void aFetchedDocumentNeedingAMissingClassIsReportedAsUnreadable() {
        var tika = new Tika(new DefaultDetector(), failingWith(new NoClassDefFoundError(MISSING)));

        assertEquals("Error: could not extract text from " + URL + ": " + MISSING,
                WebExtraction.extractWithTika(tika, new byte[]{1, 2, 3}, "", URL));
    }

    @Test
    void aFetchedDocumentTikaRejectsIsReportedAsBefore() {
        var tika = new Tika(new DefaultDetector(), failingWith(new TikaException("not an OOXML package")));

        assertEquals("Error: could not extract text from " + URL + ": not an OOXML package",
                WebExtraction.extractWithTika(tika, new byte[]{1, 2, 3}, "", URL));
    }
}
