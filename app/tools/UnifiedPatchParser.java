package tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure parser for the OpenClaw patch envelope format:
 *
 * <pre>
 * *** Begin Patch
 * *** Add File: path/new.txt
 * +line 1
 * +line 2
 * *** End of File
 * *** Update File: path/existing.md
 * *** Move to: path/renamed.md
 * @@ optional anchor @@
 *  context
 * -old line
 * +new line
 * *** End of File
 * *** Delete File: path/gone.txt
 * *** End Patch
 * </pre>
 *
 * This class only understands the envelope grammar and produces an ordered list of
 * {@link FileOp} describing what should happen to each file. It never touches the
 * filesystem and knows nothing about agents or workspaces — {@code FileSystemTools}
 * owns resolving paths, locking, applying chunks, and rollback.
 */
public final class UnifiedPatchParser {

    private static final String BEGIN_PATCH = "*** Begin Patch";
    private static final String END_PATCH = "*** End Patch";
    private static final String END_OF_FILE = "*** End of File";
    private static final String ADD_FILE_PREFIX = "*** Add File:";
    private static final String DELETE_FILE_PREFIX = "*** Delete File:";
    private static final String UPDATE_FILE_PREFIX = "*** Update File:";
    private static final String MOVE_TO_PREFIX = "*** Move to:";

    private UnifiedPatchParser() {}

    /** Parse a patch body into an ordered list of file operations. */
    public static List<FileOp> parse(String body) {
        // Normalize line endings so the parser has one path to worry about.
        var lines = body.replace("\r\n", "\n").split("\n", -1);
        int i = skipBlankLines(lines, 0);
        if (i >= lines.length || !lines[i].strip().equals(BEGIN_PATCH)) {
            throw new PatchParseException("missing '*** Begin Patch' header", i + 1);
        }
        i++;

        var ops = new ArrayList<FileOp>();
        while (i < lines.length) {
            var trimmed = lines[i].strip();
            if (trimmed.equals(END_PATCH)) return ops;
            if (trimmed.isEmpty()) { i++; continue; }
            if (trimmed.startsWith(ADD_FILE_PREFIX)) {
                i = parseAddFile(lines, i, trimmed, ops);
                continue;
            }
            if (trimmed.startsWith(DELETE_FILE_PREFIX)) {
                ops.add(new FileOp.Delete(trimmed.substring(DELETE_FILE_PREFIX.length()).strip()));
                i++;
                continue;
            }
            if (trimmed.startsWith(UPDATE_FILE_PREFIX)) {
                i = parseUpdateFile(lines, i, trimmed, ops);
                continue;
            }
            throw new PatchParseException("Unexpected directive: '" + trimmed + "'", i + 1);
        }
        throw new PatchParseException("missing '*** End Patch' footer", i);
    }

    private static int skipBlankLines(String[] lines, int from) {
        int i = from;
        while (i < lines.length && lines[i].isBlank()) i++;
        return i;
    }

    private static int parseAddFile(String[] lines, int start, String headerTrimmed, List<FileOp> ops) {
        var path = headerTrimmed.substring(ADD_FILE_PREFIX.length()).strip();
        int i = start + 1;
        var content = new StringBuilder();
        boolean firstLine = true;
        while (i < lines.length && !lines[i].strip().equals(END_OF_FILE)) {
            var addLine = lines[i];
            if (addLine.startsWith("+")) {
                if (!firstLine) content.append('\n');
                content.append(addLine.substring(1));
                firstLine = false;
            } else if (addLine.isEmpty()) {
                // tolerate blank lines inside Add File blocks
                if (!firstLine) content.append('\n');
                firstLine = false;
            } else {
                throw new PatchParseException(
                        "Add File body lines must start with '+' (got '" + addLine + "')", i + 1);
            }
            i++;
        }
        if (i >= lines.length) {
            throw new PatchParseException("Add File missing '*** End of File' terminator", i);
        }
        ops.add(new FileOp.Add(path, content.toString()));
        return i + 1; // consume End of File
    }

    private static int parseUpdateFile(String[] lines, int start, String headerTrimmed, List<FileOp> ops) {
        var path = headerTrimmed.substring(UPDATE_FILE_PREFIX.length()).strip();
        int i = start + 1;
        Optional<String> newPath = Optional.empty();
        if (i < lines.length && lines[i].strip().startsWith(MOVE_TO_PREFIX)) {
            newPath = Optional.of(lines[i].strip().substring(MOVE_TO_PREFIX.length()).strip());
            i++;
        }
        var collector = new ChunkCollector();
        while (i < lines.length && !lines[i].strip().equals(END_OF_FILE)) {
            collector.feed(lines[i], i + 1);
            i++;
        }
        if (i >= lines.length) {
            throw new PatchParseException("Update File missing '*** End of File' terminator", i);
        }
        collector.flush();
        if (collector.chunks.isEmpty()) {
            throw new PatchParseException("Update File '" + path + "' has no chunks", i + 1);
        }
        ops.add(new FileOp.Update(path, newPath, collector.chunks));
        return i + 1; // consume End of File
    }

    /** Streaming collector that splits an Update File body into chunks separated by @@ anchors. */
    private static final class ChunkCollector {
        final List<PatchChunk> chunks = new ArrayList<>();
        List<PatchLine> currentLines = new ArrayList<>();
        Optional<String> currentAnchor = Optional.empty();
        boolean inChunk = false;

        void feed(String chunkLine, int lineNo) {
            if (isAnchorLine(chunkLine)) {
                closeCurrent();
                var anchorText = chunkLine.strip();
                anchorText = anchorText.substring(2, anchorText.length() - 2).strip();
                currentAnchor = anchorText.isEmpty() ? Optional.empty() : Optional.of(anchorText);
                inChunk = true;
            } else if (chunkLine.startsWith("+")) {
                ensureInChunk();
                currentLines.add(new PatchLine.AddLine(chunkLine.substring(1)));
            } else if (chunkLine.startsWith("-")) {
                ensureInChunk();
                currentLines.add(new PatchLine.RemoveLine(chunkLine.substring(1)));
            } else if (chunkLine.startsWith(" ") || chunkLine.isEmpty()) {
                ensureInChunk();
                currentLines.add(new PatchLine.ContextLine(chunkLine.isEmpty() ? "" : chunkLine.substring(1)));
            } else {
                throw new PatchParseException(
                        "Update File chunk line must start with ' ', '+', '-', or '@@' (got '" + chunkLine + "')",
                        lineNo);
            }
        }

        void flush() { closeCurrent(); }

        private void closeCurrent() {
            if (inChunk && !currentLines.isEmpty()) {
                chunks.add(new PatchChunk(currentAnchor, currentLines));
            }
            currentLines = new ArrayList<>();
        }

        private void ensureInChunk() {
            if (!inChunk) {
                inChunk = true;
                currentLines = new ArrayList<>();
                currentAnchor = Optional.empty();
            }
        }

        private static boolean isAnchorLine(String chunkLine) {
            if (!chunkLine.startsWith("@@")) return false;
            var stripped = chunkLine.strip();
            return stripped.endsWith("@@") && stripped.length() >= 4;
        }
    }

    // === Patch model ===

    public sealed interface FileOp permits FileOp.Add, FileOp.Update, FileOp.Delete {
        String path();
        record Add(String path, String content) implements FileOp {}
        record Update(String path, Optional<String> newPath, List<PatchChunk> chunks) implements FileOp {}
        record Delete(String path) implements FileOp {}
    }

    public record PatchChunk(Optional<String> anchor, List<PatchLine> lines) {}
    public sealed interface PatchLine {
        String text();
        record ContextLine(String text) implements PatchLine {}
        record AddLine(String text) implements PatchLine {}
        record RemoveLine(String text) implements PatchLine {}
    }

    /** Thrown when a patch body doesn't conform to the envelope grammar. Carries the 1-based line number. */
    public static final class PatchParseException extends RuntimeException {
        public final int line;
        public PatchParseException(String message, int line) { super(message); this.line = line; }
    }
}
