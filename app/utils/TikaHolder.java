package utils;

import org.apache.tika.Tika;
import org.apache.tika.parser.AutoDetectParser;

/**
 * Shared Apache Tika instances for the entire application. Both Tika
 * (MIME sniffing) and AutoDetectParser (document text extraction) are
 * thread-safe for concurrent reuse, and both perform expensive
 * ServiceLoader-driven parser-registry discovery in their constructors —
 * a single process-wide instance avoids re-walking the classpath on every
 * call site.
 */
public final class TikaHolder {

    public static final Tika TIKA = new Tika();

    public static final AutoDetectParser PARSER = new AutoDetectParser();

    private TikaHolder() {}
}
