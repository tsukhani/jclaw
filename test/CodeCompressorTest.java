import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.CodeCompressor;
import services.compression.CodeCompressor.Language;
import services.compression.ContentHash;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTimeout;

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

    @Test
    void modernJavaTakesTheAstPathNotTheRegexFallback() {
        // JCLAW-1230: at javaparser's default JAVA_11 level a record or a switch
        // expression threw and the lossy regex path silently took over.
        var java = """
                package com.example.shape;

                import java.util.List;

                public record Point(int x, int y) {

                    public String quadrant() {
                        return switch (Integer.signum(x) * 2 + Integer.signum(y)) {
                            case 3 -> "north-east";
                            case 1 -> "north-west";
                            default -> "somewhere else entirely";
                        };
                    }

                    public static Point origin(List<Integer> unused) {
                        return new Point(0, 0);
                    }
                }
                """;

        var result = compressor.compress(java, Language.JAVA);
        assertTrue(result.changed(), "body-heavy modern Java should compress");
        var out = result.content();
        // The two paths leave distinguishable markers, so the marker names the path taken.
        assertTrue(out.contains("[body:"), "AST path marker missing: " + out);
        assertFalse(out.contains("// [...]"), "regex fallback was taken: " + out);
        assertTrue(out.contains("public String quadrant()"), out);
        assertFalse(out.contains("north-east"), "switch body should be gone: " + out);
        assertFalse(result.degraded(), "the AST path succeeded, so nothing was degraded");
    }

    @Test
    void unparseableJavaReportsADegradedResult() {
        var broken = new StringBuilder("package x;\npublic class X {\n    void broken( {{{ not valid ;;;\n");
        for (int i = 0; i < 20; i++) {
            broken.append("        some garbage line ").append(i).append(" with filler text\n");
        }
        broken.append("}\n");

        var result = compressor.compress(broken.toString(), Language.JAVA);
        assertTrue(result.changed(), "the regex fallback still shrinks a body-heavy listing");
        assertTrue(result.degraded(), "a swallowed parse failure must reach the caller");
    }

    @Test
    void csharpListingTakesTheRegexPathWithoutAParseFailure() {
        var csharp = """
                using System;
                using System.Collections.Generic;

                namespace Acme.Core.Episodes
                {
                    public class EpisodeService
                    {
                        private readonly IList<int> _ids = new List<int>();

                        public int Count()
                        {
                            return _ids.Count;
                        }
                    }
                }
                """;

        var result = compressor.compress(csharp);

        assertEquals("""
                using System;
                using System.Collections.Generic;
                namespace Acme.Core.Episodes
                {
                    public class EpisodeService
                    {
                        private readonly IList<int> _ids = new List<int>();
                        public int Count()
                        {
                // [...]
                        }
                    }
                }
                """, result.content());
        assertFalse(result.degraded(), "C# is no failed Java parse");
    }

    @Test
    void csharpMembersSurviveWithTheirBodiesElided() {
        var csharp = """
                namespace Acme.Core
                {
                    public interface IEpisodes
                    {
                        Episode Find(int id);
                        List<Episode> ForSeries(int seriesId, bool monitored);
                        Dictionary<int, string> Titles(int seriesId);
                    }

                    internal sealed class Episodes : IEpisodes
                    {
                        public string Title { get; set; }

                        public Episodes(IStore store) : base(store)
                        {
                            _store = store;
                        }

                        protected override async Task<int> Save(Episode episode)
                        {
                            var saved = await _store.Write(episode);
                            Log(saved);
                            if (saved > 0)
                            {
                                return Count(saved);
                            }
                            return await Retry(episode);
                        }

                        public int Count() => _ids.Count;
                    }
                }
                """;

        var out = compressor.compress(csharp).content();

        assertEquals("""
                namespace Acme.Core
                {
                    public interface IEpisodes
                    {
                        Episode Find(int id);
                        List<Episode> ForSeries(int seriesId, bool monitored);
                        Dictionary<int, string> Titles(int seriesId);
                    }
                    internal sealed class Episodes : IEpisodes
                    {
                        public string Title { get; set; }
                        public Episodes(IStore store) : base(store)
                        {
                // [...]
                        }
                        protected override async Task<int> Save(Episode episode)
                        {
                // [...]
                            {
                // [...]
                            }
                // [...]
                        }
                        public int Count() => _ids.Count;
                    }
                }
                """, out);
    }

    @Test
    void csharpStatementsAreNotTakenForInterfaceMembers() {
        var statements = """
                using System;
                public class Runner
                {
                    public int Run()
                    {
                        return Compute(a);
                        await Save(b);
                        throw new Failure(c);
                        new Thing(d);
                        yield return Next(e);
                        store.Write(f);
                        int total = Sum(g);
                    }
                }
                """;

        var out = compressor.compress(statements).content();

        assertEquals("""
                using System;
                public class Runner
                {
                    public int Run()
                    {
                // [...]
                    }
                }
                """, out);
    }

    @Test
    void aCraftedCSharpLineCannotStallTheMemberRule() {
        var crafted = "a<" + "> b<".repeat(12_500);
        var csharp = "using System;\n" + (crafted + "\n").repeat(3) + "public class C\n{\n}\n";

        var result = assertTimeout(Duration.ofSeconds(1), () -> compressor.compress(csharp));

        assertEquals("using System;\n// [...]\npublic class C\n{\n}\n", result.content());
    }

    @Test
    void aCSharpMemberLineIsKeptUpToTheLengthLimit() {
        var shape = "public int Run(%s)";
        var atLimit = shape.formatted("p".repeat(500 - shape.length() + 2));
        var overLimit = shape.formatted("p".repeat(501 - shape.length() + 2));
        var filler = "        var total = Sum(values);\n".repeat(40);
        assertEquals(500, atLimit.length());
        assertEquals(501, overLimit.length());

        var kept = compressor.compress("using System;\n" + atLimit + "\n{\n" + filler + "}\n").content();
        var elided = compressor.compress("using System;\n" + overLimit + "\n{\n" + filler + "}\n").content();

        assertEquals("using System;\n" + atLimit + "\n{\n// [...]\n}\n", kept);
        assertEquals("using System;\n// [...]\n{\n// [...]\n}\n", elided);
    }

    @Test
    void theMemberRulesLeaveOtherLanguagesAlone() {
        var listing = """
                export class Episodes {
                  public count(): number {
                    return this.ids.length;
                  }
                }
                """;

        assertEquals("""
                export class Episodes {
                // [...]
                  }
                }
                """, compressor.compress(listing, Language.UNKNOWN).content());
    }

    @Test
    void typescriptMembersSurviveWithTheirBodiesElided() {
        var typescript = """
                import { Store } from './store';

                export class Episodes {
                  private readonly store: Store;

                  constructor(store: Store) {
                    this.store = store;
                  }

                  get size(): number {
                    return this.ids.length;
                  }

                  async save(episode: Episode): Promise<number> {
                    const saved = await this.store.write(episode);
                    return saved;
                  }

                  static create<T>(seed: T): Episodes {
                    return new Episodes(seed);
                  }
                }

                const format = (value) => {
                  return String(value);
                };

                export interface Repo {
                  find(id: number): Episode;
                }
                """;

        assertEquals("""
                import { Store } from './store';
                export class Episodes {
                  private readonly store: Store;
                  constructor(store: Store) {
                // [...]
                  }
                  get size(): number {
                // [...]
                  }
                  async save(episode: Episode): Promise<number> {
                // [...]
                  }
                  static create<T>(seed: T): Episodes {
                // [...]
                  }
                }
                const format = (value) => {
                // [...]
                };
                export interface Repo {
                  find(id: number): Episode;
                }
                """, compressor.compress(typescript, Language.JAVASCRIPT).content());
    }

    @Test
    void javascriptBlocksAndCallsAreNotTakenForMembers() {
        var statements = """
                export function run(items) {
                  if (items.length) {
                    for (const item of items) {
                      setTimeout(function () {
                        store.write(item);
                      });
                      items.forEach((other) => {
                        log(other);
                      });
                    }
                  }
                  while (pending()) {
                    tick();
                  }
                  switch (mode) {
                    default:
                      return compute(items);
                  }
                }
                """;

        assertEquals("""
                export function run(items) {
                // [...]
                    }
                  }
                // [...]
                  }
                // [...]
                  }
                }
                """, compressor.compress(statements, Language.JAVASCRIPT).content());
    }

    @Test
    void aJavascriptMemberLineIsKeptUpToTheLengthLimit() {
        var shape = "public run(%s) {";
        var atLimit = shape.formatted("p".repeat(500 - shape.length() + 2));
        var overLimit = shape.formatted("p".repeat(501 - shape.length() + 2));
        var filler = "total += compute(value);\n".repeat(40);
        assertEquals(500, atLimit.length());
        assertEquals(501, overLimit.length());

        var kept = compressor.compress("export class C {\n" + atLimit + "\n" + filler + "}\n", Language.JAVASCRIPT).content();
        var elided = compressor.compress("export class C {\n" + overLimit + "\n" + filler + "}\n", Language.JAVASCRIPT).content();

        assertEquals("export class C {\n" + atLimit + "\n// [...]\n}\n", kept);
        assertEquals("export class C {\n// [...]\n}\n", elided);
    }

    @Test
    void csharpDirectivesOutrankTheDeclarationFormCSharpSharesWithJava() {
        assertEquals(Language.CSHARP, CodeCompressor.detectLanguage("using System;\npublic class B {}"));
        assertEquals(Language.CSHARP, CodeCompressor.detectLanguage("using System.Linq;\npublic class B {}"));
        assertEquals(Language.CSHARP, CodeCompressor.detectLanguage("using static System.Math;\npublic class B {}"));
        assertEquals(Language.CSHARP, CodeCompressor.detectLanguage("namespace A.B;\npublic class C {}"));
        assertEquals(Language.CSHARP, CodeCompressor.detectLanguage("namespace A.B\n{\n    public class C {}\n}"));
        assertEquals(Language.CSHARP, CodeCompressor.detectLanguage("namespace A.B {\n    public class C {}\n}"));
    }

    @Test
    void javaWithoutACSharpDirectiveStaysJava() {
        assertEquals(Language.JAVA, CodeCompressor.detectLanguage("public class B {}"));
        assertEquals(Language.JAVA, CodeCompressor.detectLanguage("import java.util.List;\npublic class B {}"));
        assertEquals(Language.JAVA, CodeCompressor.detectLanguage("public class B {\n    // using Foo here\n}"));
        assertEquals(Language.JAVA, CodeCompressor.detectLanguage("public class B {}\nusing a wrench\nnamespace of it"));
    }

    @Test
    void anUnparseableListingWarnsOnOneLineWithoutAStackTrace() {
        var broken = "public class X {\n    void broken( {{{ not valid ;;; }}}\n    garbage line\n}";
        var logged = new CopyOnWriteArrayList<LogEvent>();
        var capture = new AbstractAppender("CodeCompressorTest", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                logged.add(event.toImmutable());
            }
        };
        capture.start();
        var logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger("play");
        logger.addAppender(capture);
        try {
            compressor.compress(broken, Language.JAVA);
        } finally {
            logger.removeAppender(capture);
            capture.stop();
        }

        // Other classes log to "play" concurrently, and this handle is unique to this input.
        var warning = "[compress] Java parse failed for content " + ContentHash.handle(broken)
                + " — using the regex fallback";
        var mine = logged.stream()
                .filter(event -> warning.equals(event.getMessage().getFormattedMessage()))
                .toList();
        assertEquals(1, mine.size());
        assertEquals(Level.WARN, mine.getFirst().getLevel());
        assertNull(mine.getFirst().getThrown());
    }

}
