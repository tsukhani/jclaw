import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.CodeCompressor;
import services.compression.CodeCompressor.Language;

/**
 * JCLAW-463: CodeCompressor. Validates the Java AST path (imports, signatures and
 * annotations preserved byte-faithfully, bodies elided to a marker) and the
 * line-based regex fallback for Python, JavaScript, Go and Rust, plus graceful
 * handling of unparseable Java.
 */
class CodeCompressorTest extends UnitTest {

    private final CodeCompressor compressor = new CodeCompressor();

    @Test
    void javaPreservesImportsSignaturesAnnotationsAndElidesBodies() {
        var java = """
                package com.example.greet;

                import java.util.List;
                import java.util.stream.Collectors;

                /** Greets people. */
                @Service
                public class Greeter {
                    private final String prefix;

                    @Inject
                    public Greeter(String prefix) {
                        this.prefix = prefix;
                        if (prefix == null) throw new IllegalArgumentException("prefix");
                    }

                    @Override
                    public String greet(List<String> who) {
                        return who.stream()
                                .map(w -> prefix + ", " + w + "!")
                                .collect(Collectors.joining("\\n"));
                    }
                }
                """;

        var result = compressor.compress(java, Language.JAVA);
        assertTrue(result.changed(), "body-heavy Java should compress");
        var out = result.content();

        // Imports + package preserved verbatim.
        assertTrue(out.contains("package com.example.greet;"), out);
        assertTrue(out.contains("import java.util.List;"), out);
        assertTrue(out.contains("import java.util.stream.Collectors;"), out);
        // Signatures preserved.
        assertTrue(out.contains("public class Greeter"), out);
        assertTrue(out.contains("public Greeter(String prefix)"), out);
        assertTrue(out.contains("public String greet(List<String> who)"), out);
        // Annotations preserved.
        assertTrue(out.contains("@Service"), out);
        assertTrue(out.contains("@Inject"), out);
        assertTrue(out.contains("@Override"), out);
        // Bodies elided to a marker; the implementation detail leaked out.
        assertTrue(out.contains("[body:"), out);
        assertFalse(out.contains("IllegalArgumentException"), "constructor body should be gone: " + out);
        assertFalse(out.contains("Collectors.joining"), "method body should be gone: " + out);
    }

    @Test
    void javaAchievesAtLeastFortyPercentOnBodyHeavyListing() {
        var sb = new StringBuilder("package x;\n\npublic class Big {\n");
        for (int i = 0; i < 6; i++) {
            sb.append("    public int m").append(i).append("(int a, int b) {\n");
            for (int j = 0; j < 12; j++) {
                sb.append("        int v").append(j).append(" = a * ").append(j).append(" + b - ").append(j).append(";\n");
            }
            sb.append("        return a + b;\n    }\n");
        }
        sb.append("}\n");
        var java = sb.toString();

        var result = compressor.compress(java, Language.JAVA);
        assertTrue(result.changed());
        double ratio = 1.0 - (double) result.content().length() / java.length();
        assertTrue(ratio >= 0.40, "expected >=40% char savings, got " + Math.round(ratio * 100) + "%");
    }

    @Test
    void unparseableJavaIsHandledGracefully() {
        // Detected as Java, but won't parse — must not throw, must not inflate.
        var broken = "public class X {\n    void broken( {{{ not valid ;;; }}}\n    garbage line one\n    garbage line two\n}";
        var result = compressor.compress(broken, Language.JAVA);
        assertTrue(result.content().length() <= broken.length(),
                "graceful fallback must never inflate");
    }

    @Test
    void pythonRegexFallbackKeepsSignaturesElidesBodies() {
        var py = """
                import os
                from typing import List

                @dataclass
                class Greeter:
                    def greet(self, who: List[str]) -> str:
                        out = []
                        for w in who:
                            out.append(f"Hello, {w}!")
                        return "\\n".join(out)
                """;
        var result = compressor.compress(py, Language.PYTHON);
        assertTrue(result.changed());
        var out = result.content();
        assertTrue(out.contains("import os"), out);
        assertTrue(out.contains("from typing import List"), out);
        assertTrue(out.contains("@dataclass"), out);
        assertTrue(out.contains("class Greeter:"), out);
        assertTrue(out.contains("def greet(self, who: List[str]) -> str:"), out);
        assertTrue(out.contains("# [...]"), "python marker uses #: " + out);
        assertFalse(out.contains("out.append"), "body should be elided: " + out);
    }

    @Test
    void javascriptRegexFallbackKeepsSignaturesElidesBodies() {
        var js = """
                import { foo } from './foo';

                export function greet(who) {
                  const parts = [];
                  for (const w of who) {
                    parts.push(`Hello, ${w}!`);
                  }
                  return parts.join('\\n');
                }
                """;
        var result = compressor.compress(js, Language.JAVASCRIPT);
        assertTrue(result.changed());
        var out = result.content();
        assertTrue(out.contains("import { foo } from './foo';"), out);
        assertTrue(out.contains("export function greet(who)"), out);
        assertTrue(out.contains("// [...]"), out);
        assertFalse(out.contains("parts.push"), "body should be elided: " + out);
    }

    @Test
    void goRegexFallbackKeepsSignaturesElidesBodies() {
        var go = """
                package main

                import "fmt"

                func Greet(who []string) string {
                	out := ""
                	for _, w := range who {
                		out += fmt.Sprintf("Hello, %s!", w)
                	}
                	return out
                }
                """;
        var result = compressor.compress(go, Language.GO);
        assertTrue(result.changed());
        var out = result.content();
        assertTrue(out.contains("package main"), out);
        assertTrue(out.contains("import \"fmt\""), out);
        assertTrue(out.contains("func Greet(who []string) string"), out);
        assertFalse(out.contains("fmt.Sprintf"), "body should be elided: " + out);
    }

    @Test
    void rustRegexFallbackKeepsSignaturesElidesBodies() {
        var rust = """
                use std::fmt;

                pub fn greet(who: Vec<String>) -> String {
                    let mut out = String::new();
                    for w in who {
                        out.push_str(&format!("Hello, {}!", w));
                    }
                    out
                }
                """;
        var result = compressor.compress(rust, Language.RUST);
        assertTrue(result.changed());
        var out = result.content();
        assertTrue(out.contains("use std::fmt;"), out);
        assertTrue(out.contains("pub fn greet(who: Vec<String>) -> String"), out);
        assertFalse(out.contains("push_str"), "body should be elided: " + out);
    }

    @Test
    void detectsLanguagesFromContent() {
        assertEquals(Language.JAVA, CodeCompressor.detectLanguage("package a;\npublic class B {}"));
        assertEquals(Language.PYTHON, CodeCompressor.detectLanguage("def f(x):\n    return x"));
        assertEquals(Language.GO, CodeCompressor.detectLanguage("package main\nfunc main() {}"));
        assertEquals(Language.RUST, CodeCompressor.detectLanguage("pub fn main() {}"));
        assertEquals(Language.JAVASCRIPT, CodeCompressor.detectLanguage("export function f(x) { return x; }"));
    }

    @Test
    void blankInputIsUnchanged() {
        var result = compressor.compress("   ", Language.UNKNOWN);
        assertFalse(result.changed());
    }
}
