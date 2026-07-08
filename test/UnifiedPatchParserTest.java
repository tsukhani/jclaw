import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.UnifiedPatchParser;
import tools.UnifiedPatchParser.FileOp;
import tools.UnifiedPatchParser.PatchParseException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure grammar coverage for {@link UnifiedPatchParser}: the OpenClaw patch envelope
 * (*** Begin Patch / *** Add File / *** Update File / *** Delete File / *** Move to /
 * *** End of File / *** End Patch) parsed into {@link FileOp}s, with no filesystem or
 * agent involvement. Complements {@code FileSystemToolsPatchTest}, which exercises the
 * same grammar end-to-end through {@code applyPatch} plus the transactional apply phase
 * that stayed behind in {@code FileSystemTools}.
 */
class UnifiedPatchParserTest extends UnitTest {

    // (a) Add File
    @Test
    void addFileParsesPathAndJoinedContent() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Add File: new.txt
                +line 1
                +line 2
                *** End of File
                *** End Patch
                """);
        assertEquals(1, ops.size());
        var add = assertInstanceOf(FileOp.Add.class, ops.get(0));
        assertEquals("new.txt", add.path());
        assertEquals("line 1\nline 2", add.content());
    }

    // (b) Update File with one chunk
    @Test
    void updateFileWithOneChunkParsesRemoveAndAddLines() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Update File: existing.md
                -old line
                +new line
                *** End of File
                *** End Patch
                """);
        assertEquals(1, ops.size());
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        assertEquals("existing.md", upd.path());
        assertTrue(upd.newPath().isEmpty());
        assertEquals(1, upd.chunks().size());
        var chunk = upd.chunks().get(0);
        assertTrue(chunk.anchor().isEmpty());
        assertEquals(2, chunk.lines().size());
        var removed = assertInstanceOf(UnifiedPatchParser.PatchLine.RemoveLine.class, chunk.lines().get(0));
        assertEquals("old line", removed.text());
        var added = assertInstanceOf(UnifiedPatchParser.PatchLine.AddLine.class, chunk.lines().get(1));
        assertEquals("new line", added.text());
    }

    // (c) Multiple chunks in one Update, each with its own @@ anchor
    @Test
    void multipleAnchoredChunksInOneUpdateAreSplitCorrectly() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Update File: multi.txt
                @@ first anchor @@
                -a
                +A
                @@ second anchor @@
                -b
                +B
                *** End of File
                *** End Patch
                """);
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        assertEquals(2, upd.chunks().size());
        assertEquals(Optional.of("first anchor"), upd.chunks().get(0).anchor());
        assertEquals(2, upd.chunks().get(0).lines().size());
        assertEquals(Optional.of("second anchor"), upd.chunks().get(1).anchor());
        assertEquals(2, upd.chunks().get(1).lines().size());
    }

    // (d) Chunk at the very start of the Update body (no anchor, no preceding context)
    @Test
    void chunkAtStartOfUpdateBodyKeepsFirstLine() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Update File: startedit.txt
                -first old
                +first new
                 context after
                *** End of File
                *** End Patch
                """);
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        assertEquals(1, upd.chunks().size());
        var lines = upd.chunks().get(0).lines();
        assertEquals(3, lines.size());
        assertInstanceOf(UnifiedPatchParser.PatchLine.RemoveLine.class, lines.get(0));
        assertEquals("first old", lines.get(0).text());
    }

    // (e) Chunk at the very end of the Update body (flush() must not drop the last line)
    @Test
    void chunkAtEndOfUpdateBodyKeepsLastLine() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Update File: endedit.txt
                 context before
                -last old
                +last new
                *** End of File
                *** End Patch
                """);
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        var lines = upd.chunks().get(0).lines();
        assertEquals(3, lines.size());
        var last = assertInstanceOf(UnifiedPatchParser.PatchLine.AddLine.class, lines.get(2));
        assertEquals("last new", last.text());
    }

    // (f) Trailing-newline preservation: a blank raw line before "*** End of File"
    // becomes a trailing '\n' in the joined Add File content.
    @Test
    void addFileTrailingBlankLinePreservesTrailingNewline() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Add File: trailing.txt
                +alpha
                +beta

                *** End of File
                *** End Patch
                """);
        var add = assertInstanceOf(FileOp.Add.class, ops.get(0));
        assertEquals("alpha\nbeta\n", add.content());
    }

    // (g) CRLF handling: directive lines and chunk lines separated by \r\n parse
    // identically to the LF form.
    @Test
    void crlfLineEndingsAreNormalized() {
        var patch = "*** Begin Patch\r\n"
                + "*** Update File: crlf.txt\r\n"
                + " context\r\n"
                + "-old\r\n"
                + "+new\r\n"
                + "*** End of File\r\n"
                + "*** End Patch\r\n";
        var ops = UnifiedPatchParser.parse(patch);
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        var lines = upd.chunks().get(0).lines();
        assertEquals(3, lines.size());
        assertEquals("context", lines.get(0).text());
        assertEquals("old", lines.get(1).text());
        assertEquals("new", lines.get(2).text());
    }

    // (h) Missing envelope markers throw PatchParseException with a 1-based line number.
    @Test
    void missingBeginPatchHeaderThrowsAtLineOne() {
        var patch = "*** Update File: x.txt\n-old\n+new\n*** End of File\n*** End Patch\n";
        var ex = assertThrows(PatchParseException.class, () -> UnifiedPatchParser.parse(patch));
        assertEquals(1, ex.line);
        assertTrue(ex.getMessage().contains("missing '*** Begin Patch' header"), "got: " + ex.getMessage());
    }

    @Test
    void missingEndPatchFooterThrows() {
        var patch = "*** Begin Patch\n*** Delete File: ghost.txt\n";
        var ex = assertThrows(PatchParseException.class, () -> UnifiedPatchParser.parse(patch));
        assertTrue(ex.getMessage().contains("missing '*** End Patch' footer"), "got: " + ex.getMessage());
    }

    // (i) A malformed "anchor" line (starts with @@ but isn't closed) is not treated as
    // an anchor, so it must fall through to the chunk-line prefix check and fail.
    @Test
    void unclosedAnchorLineIsRejectedAsChunkLineMismatch() {
        var patch = """
                *** Begin Patch
                *** Update File: halfanchor.txt
                @@ not closed
                -x
                +y
                *** End of File
                *** End Patch
                """;
        var ex = assertThrows(PatchParseException.class, () -> UnifiedPatchParser.parse(patch));
        assertEquals(3, ex.line);
        assertTrue(ex.getMessage().contains("must start with"), "got: " + ex.getMessage());
    }

    // (j) An unrecognized top-level directive is rejected with the offending line number.
    @Test
    void unexpectedDirectiveIsRejected() {
        var patch = """
                *** Begin Patch
                *** Rename File: a.txt
                *** End Patch
                """;
        var ex = assertThrows(PatchParseException.class, () -> UnifiedPatchParser.parse(patch));
        assertEquals(2, ex.line);
        assertTrue(ex.getMessage().contains("Unexpected directive"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Rename File"), "got: " + ex.getMessage());
    }

    // === Bonus structural coverage ===

    @Test
    void deleteFileParsesBarePath() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Delete File: gone.txt
                *** End Patch
                """);
        assertEquals(1, ops.size());
        var del = assertInstanceOf(FileOp.Delete.class, ops.get(0));
        assertEquals("gone.txt", del.path());
    }

    @Test
    void updateWithMoveToCapturesNewPath() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Update File: old-name.md
                *** Move to: new-name.md
                -old
                +new
                *** End of File
                *** End Patch
                """);
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        assertEquals("old-name.md", upd.path());
        assertEquals(Optional.of("new-name.md"), upd.newPath());
    }

    @Test
    void emptyAnchorTextIsTreatedAsUnanchored() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** Update File: empty-anchor.txt
                @@ @@
                -solo line
                +SOLO LINE
                *** End of File
                *** End Patch
                """);
        var upd = assertInstanceOf(FileOp.Update.class, ops.get(0));
        assertTrue(upd.chunks().get(0).anchor().isEmpty());
    }

    @Test
    void patchWithNoOperationsParsesToEmptyList() {
        var ops = UnifiedPatchParser.parse("""
                *** Begin Patch
                *** End Patch
                """);
        assertTrue(ops.isEmpty());
    }
}
