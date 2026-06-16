import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import services.compression.ContentType;
import services.compression.ContentTypeDetector;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JCLAW-460: content-type detection for the compression pipeline. Locks the
 * routing decisions — JSON via parse, code via anchored declarations, logs via
 * level tokens, everything else TEXT.
 */
class ContentTypeDetectorTest extends UnitTest {

    @Test
    void detectsJsonObject() {
        assertEquals(ContentType.JSON,
                ContentTypeDetector.detect("{\"id\": 1, \"name\": \"kimi\"}"));
    }

    @Test
    void detectsJsonArray() {
        assertEquals(ContentType.JSON,
                ContentTypeDetector.detect("[{\"id\": 1}, {\"id\": 2}]"));
    }

    @Test
    void detectsJsonWithLeadingWhitespace() {
        assertEquals(ContentType.JSON, ContentTypeDetector.detect("\n   {\"ok\": true}\n"));
    }

    @Test
    void detectsJsonAfterAStatusPrefix() {
        // jclaw_api shape: a "HTTP 200" status line, then the JSON body.
        var content = "HTTP 200\n[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4}]";
        assertEquals(ContentType.JSON, ContentTypeDetector.detect(content));
    }

    @Test
    void codeEndingInEmptyBracesStaysCode() {
        // "public class Bar {}" ends in {} (a valid empty JSON object) but the
        // JSON is dwarfed by the code prefix — must classify as CODE, not JSON.
        assertEquals(ContentType.CODE,
                ContentTypeDetector.detect("package com.foo;\npublic class Bar {}"));
    }

    @Test
    void malformedJsonIsNotJson() {
        // Starts with { but doesn't parse → falls through to TEXT, never JSON.
        assertEquals(ContentType.TEXT, ContentTypeDetector.detect("{not: valid, json"));
    }

    @Test
    void bareNumberIsNotJson() {
        // JsonParser would accept "42" as a primitive, but we only treat
        // objects/arrays as compressible JSON documents.
        assertEquals(ContentType.TEXT, ContentTypeDetector.detect("42"));
    }

    @ParameterizedTest(name = "code: {0}")
    @ValueSource(strings = {
            "package com.foo;\nimport java.util.List;\npublic class Bar {}",
            "import os\ndef main():\n    print('hi')",
            "import { foo } from 'bar';\nexport function baz() { return 1; }",
            "package main\nimport \"fmt\"\nfunc main() { fmt.Println(\"hi\") }",
            "use std::io;\nfn main() { println!(\"hi\"); }",
    })
    void detectsCodeAcrossLanguages(String code) {
        assertEquals(ContentType.CODE, ContentTypeDetector.detect(code));
    }

    @Test
    void detectsLogOutput() {
        var log = "2026-06-16 12:00:01 INFO  starting up\n"
                + "2026-06-16 12:00:02 ERROR connection refused";
        assertEquals(ContentType.LOG, ContentTypeDetector.detect(log));
    }

    @Test
    void logMentioningImportIsStillLog() {
        // "import" appears mid-line, not as a leading declaration, so the
        // anchored code pattern doesn't fire — the ERROR token wins.
        assertEquals(ContentType.LOG,
                ContentTypeDetector.detect("ERROR failed to import module foo"));
    }

    @Test
    void plainProseIsText() {
        assertEquals(ContentType.TEXT,
                ContentTypeDetector.detect("The quick brown fox jumps over the lazy dog."));
    }

    @Test
    void emptyAndNullAreText() {
        assertEquals(ContentType.TEXT, ContentTypeDetector.detect(""));
        assertEquals(ContentType.TEXT, ContentTypeDetector.detect("   \n  "));
        assertEquals(ContentType.TEXT, ContentTypeDetector.detect(null));
    }
}
