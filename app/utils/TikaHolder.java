package utils;

import org.apache.tika.Tika;
import org.apache.tika.config.ServiceLoader;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.DefaultParser;
import org.apache.tika.parser.Parser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    /**
     * Parsers whose backing library build.gradle.kts drops on license grounds — junrar
     * (UnRar License) and jackcess, see JCLAW-452. The parser classes still ship inside
     * tika-parser-pkg-module and tika-parser-microsoft-module, which are kept for zip/7z and
     * Office, so Tika finds them by ServiceLoader and cannot instantiate them. Tika 4 deleted
     * LoadErrorHandler, making that failure a hardcoded WARN plus stack trace, and naming them
     * here is the only way to keep it out of the log: Tika consults this list before
     * instantiating, so an unexpected load failure still warns (JCLAW-1142).
     */
    private static final List<String> LICENSE_EXCLUDED_PARSERS = List.of(
            "org.apache.tika.parser.pkg.RarParser",
            "org.apache.tika.parser.microsoft.JackcessParser");

    public static final AutoDetectParser PARSER = new AutoDetectParser(new DefaultParser(
            MediaTypeRegistry.getDefaultRegistry(), new ServiceLoader(), licenseExcludedParsers()));

    /** Built from PARSER — a bare {@code new Tika()} would walk the parser registry again. */
    public static final Tika TIKA = new Tika(new DefaultDetector(), PARSER);

    private TikaHolder() {}

    @SuppressWarnings("unchecked")
    private static Collection<Class<? extends Parser>> licenseExcludedParsers() {
        var out = new ArrayList<Class<? extends Parser>>();
        for (var name : LICENSE_EXCLUDED_PARSERS) {
            try {
                // initialize=false: running the class initializer would need the excluded jar.
                out.add((Class<? extends Parser>) Class.forName(
                        name, false, TikaHolder.class.getClassLoader()));
            } catch (ClassNotFoundException _) {
                // The whole parser module is gone, so there is nothing to suppress.
            }
        }
        return out;
    }
}
