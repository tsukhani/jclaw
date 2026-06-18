package services.compression;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Structure-preserving code compressor (JCLAW-463). Keeps the parts a reader (or
 * an LLM) needs to understand a listing — package, imports, type and method
 * signatures, annotations/decorators — and elides the bodies, where the bulk of
 * the tokens (and the redundancy) live.
 *
 * <p>Two paths, mirroring Headroom's {@code CodeStructureHandler}:
 * <ul>
 *   <li><b>Java</b> → JavaParser. Parse the source, find every method and
 *       constructor body via the AST, and replace just that span with a
 *       {@code { /* [body: N chars compressed] *&#47; }} marker. Because we edit
 *       the original string by node <em>range</em> (not by re-serializing the
 *       AST), imports and signatures stay byte-identical and formatting is
 *       preserved. Unparseable Java falls through to the regex path.</li>
 *   <li><b>Everything else</b> (Python, JS/TS, Go, Rust, unknown) → a line-based
 *       regex fallback: keep import/declaration/signature lines, collapse runs of
 *       body lines to a single comment marker.</li>
 * </ul>
 *
 * <p>Pure string→string per the {@link ContentCompressor} SPI: never inflates
 * (returns {@link CompressionResult#unchanged} when it can't shrink the input),
 * the pipeline owns token measurement and the token-level guard.
 */
public final class CodeCompressor implements ContentCompressor {

    public static final String ALGORITHM_NAME = "code-structural";

    /** Recognized source languages; {@link #UNKNOWN} routes through the generic regex fallback. */
    public enum Language { JAVA, PYTHON, JAVASCRIPT, GO, RUST, UNKNOWN }

    @Override
    public String algorithm() {
        return ALGORITHM_NAME;
    }

    @Override
    public CompressionResult compress(String content) {
        return compress(content, detectLanguage(content));
    }

    /** Explicit-language entry — the per-language test seam. */
    public CompressionResult compress(String code, Language language) {
        if (code == null || code.isBlank()) return CompressionResult.unchanged(code, ALGORITHM_NAME);

        String out = null;
        if (language == Language.JAVA) {
            try {
                out = compressJava(code);
            } catch (RuntimeException _) {
                out = null; // unparseable / partial Java — fall through to regex
            }
        }
        if (out == null) out = compressByRegex(code, language);

        // Char-level inflation guard; the pipeline re-checks at the token level.
        if (out == null || out.length() >= code.length()) {
            return CompressionResult.unchanged(code, ALGORITHM_NAME);
        }
        return CompressionResult.compressed(out, ALGORITHM_NAME);
    }

    // ---------------------------------------------------------------- language

    private static final Pattern JAVA_HINT = Pattern.compile(
            "(?m)^\\s*(?:package\\s+[\\w.]+;|import\\s+[\\w.]+;"
                    + "|(?:public|private|protected|final|abstract)\\s+(?:final\\s+|abstract\\s+)*"
                    + "(?:class|interface|enum|record)\\s)");
    private static final Pattern PYTHON_HINT = Pattern.compile(
            "(?m)^\\s*(?:def\\s+\\w+\\s*\\(|from\\s+[\\w.]+\\s+import\\s|class\\s+\\w+\\s*[:(])");
    private static final Pattern GO_HINT = Pattern.compile(
            "(?m)^\\s*(?:package\\s+\\w+\\s*$|func\\s++\\w*+\\s*+\\(|func\\s+\\(\\s*\\w)");
    private static final Pattern RUST_HINT = Pattern.compile(
            "(?m)^\\s*(?:fn\\s+\\w+\\s*[(<]|pub\\s+fn\\s|impl\\s+\\w|use\\s+\\w[\\w:]*;)");
    private static final Pattern JS_HINT = Pattern.compile(
            "(?m)^\\s*(?:function\\s+\\w+\\s*\\(|const\\s+\\w+\\s*=|export\\s+(?:default\\s+)?|import\\s++.*\\bfrom\\b)");

    public static Language detectLanguage(String code) {
        if (code == null || code.isBlank()) return Language.UNKNOWN;
        if (JAVA_HINT.matcher(code).find()) return Language.JAVA;
        if (PYTHON_HINT.matcher(code).find()) return Language.PYTHON;
        if (GO_HINT.matcher(code).find()) return Language.GO;
        if (RUST_HINT.matcher(code).find()) return Language.RUST;
        if (JS_HINT.matcher(code).find()) return Language.JAVASCRIPT;
        return Language.UNKNOWN;
    }

    // ---------------------------------------------------------------- Java AST

    /** One method/constructor body, as absolute char offsets into the source. */
    private record BodyRange(int begin, int end, int chars) {}

    private static String compressJava(String code) {
        var cu = StaticJavaParser.parse(code);
        int[] lineStarts = lineStarts(code);
        var ranges = new ArrayList<BodyRange>();
        cu.findAll(MethodDeclaration.class).forEach(m ->
                m.getBody().flatMap(BlockStmt::getRange)
                        .ifPresent(r -> ranges.add(toRange(lineStarts, r))));
        cu.findAll(ConstructorDeclaration.class).forEach(c ->
                c.getBody().getRange().ifPresent(r -> ranges.add(toRange(lineStarts, r))));
        if (ranges.isEmpty()) return null;

        // Keep only outermost bodies: a body nested inside another (a local or
        // anonymous-class method) is already inside the outer span we replace.
        ranges.sort(Comparator.comparingInt(BodyRange::begin));
        var outer = new ArrayList<BodyRange>();
        int coverEnd = -1;
        for (var r : ranges) {
            if (r.begin() > coverEnd) {
                outer.add(r);
                coverEnd = r.end();
            }
        }

        // Apply back-to-front so earlier offsets stay valid.
        outer.sort(Comparator.comparingInt(BodyRange::begin).reversed());
        var sb = new StringBuilder(code);
        for (var r : outer) {
            sb.replace(r.begin(), r.end() + 1,
                    "{ /* [body: " + r.chars() + " chars compressed] */ }");
        }
        return sb.toString();
    }

    private static int[] lineStarts(String code) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static BodyRange toRange(int[] lineStarts, Range r) {
        int begin = offset(lineStarts, r.begin);
        int end = offset(lineStarts, r.end);
        return new BodyRange(begin, end, end - begin + 1);
    }

    private static int offset(int[] lineStarts, Position p) {
        // JavaParser positions are 1-based for both line and column.
        return lineStarts[p.line - 1] + (p.column - 1);
    }

    // -------------------------------------------------------------- regex path

    // Lines worth keeping: imports/use/include, export/pub-prefixed declarations,
    // and type/function signatures (with optional access modifiers). Everything
    // else is body and gets collapsed.
    private static final Pattern KEEP = Pattern.compile(
            "^(?:"
                    + "package\\b|import\\b|from\\s+[\\w.]+\\s+import\\b|use\\s+|#include|using\\s+"
                    + "|export\\b|pub\\b|@\\w|#\\["
                    + "|(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+|abstract\\s+|async\\s+)*"
                    + "(?:class\\b|interface\\b|enum\\b|struct\\b|trait\\b|impl\\b|record\\b|type\\s+\\w"
                    + "|func\\b|fn\\b|def\\b|function\\b)"
                    + ")");

    private static String compressByRegex(String code, Language language) {
        String marker = (language == Language.PYTHON ? "# [...]" : "// [...]");
        var sb = new StringBuilder(code.length());
        boolean eliding = false;
        for (var line : code.split("\n", -1)) {
            if (isStructural(line)) {
                if (eliding) {
                    sb.append(marker).append('\n');
                    eliding = false;
                }
                sb.append(line).append('\n');
            } else if (!line.isBlank()) {
                eliding = true; // collapse this run of body lines into one marker
            }
            // blank lines inside a body run are dropped
        }
        if (eliding) sb.append(marker).append('\n');
        return sb.isEmpty() ? null : sb.toString();
    }

    private static boolean isStructural(String line) {
        var t = line.strip();
        if (t.isEmpty()) return false;
        // Lone structural punctuation: closing/opening braces of kept blocks.
        if (t.equals("}") || t.equals("};") || t.equals("{") || t.equals(")") || t.equals("),")) {
            return true;
        }
        return KEEP.matcher(t).find();
    }
}
