package utils;

import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.parser.AutoDetectParser;
import play.Logger;
import play.Play;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared Apache Tika instances for the entire application. Both Tika
 * (MIME sniffing) and AutoDetectParser (document text extraction) are
 * thread-safe for concurrent reuse, and both perform expensive
 * ServiceLoader-driven parser-registry discovery in their constructors —
 * a single process-wide instance avoids re-walking the classpath on every
 * call site.
 */
public final class TikaHolder {

    /** Directory holding the tesseract binary; empty means "find it on PATH". */
    public static final String OCR_PATH_KEY = "ocr.tesseract.path";

    public static final Tika TIKA = new Tika();

    public static final AutoDetectParser PARSER = buildParser();

    private TikaHolder() {}

    /**
     * JCLAW-1107: honor {@code ocr.tesseract.path} so OCR works when tesseract is
     * installed somewhere other than PATH — the default on Windows, whose installer
     * does not add itself.
     *
     * <p>Routed through a {@link TikaConfig} because Tika 3.x moved the setter off
     * {@code TesseractOCRConfig} onto the parser instance, so a per-parse
     * {@code ParseContext} cannot carry it; the value has to be baked into the
     * parser registry. {@code setTesseractPath} normalizes the value and appends the
     * separator itself, and rejects a non-directory with a TikaConfigException — so
     * a typo fails here, loudly, rather than silently reverting to no OCR.
     *
     * <p>Read once at class-init: installing tesseract needs a restart anyway.
     */
    private static AutoDetectParser buildParser() {
        var dir = Play.configuration != null ? Play.configuration.getProperty(OCR_PATH_KEY) : null;
        if (dir == null || dir.isBlank()) return new AutoDetectParser();
        var trimmed = dir.trim();
        try {
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <properties>
                      <parsers>
                        <parser class="org.apache.tika.parser.DefaultParser"/>
                        <parser class="org.apache.tika.parser.ocr.TesseractOCRParser">
                          <params>
                            <param name="tesseractPath" type="string">%s</param>
                          </params>
                        </parser>
                      </parsers>
                    </properties>
                    """.formatted(xmlEscape(trimmed));
            var cfg = new TikaConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Logger.info("OCR: tesseract path pinned to %s", trimmed);
            return new AutoDetectParser(cfg);
        } catch (Exception e) {
            // Fall back rather than refuse to boot: every other document format still
            // parses without OCR, and the probe's WARN already tells the operator.
            Logger.warn("OCR: %s=%s could not be applied (%s); falling back to a PATH lookup",
                    OCR_PATH_KEY, trimmed, e.getMessage());
            return new AutoDetectParser();
        }
    }

    /** The path is operator-supplied and lands inside an XML element. */
    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
