package tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import models.Agent;
import services.EventLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code editLines} family for {@link FileSystemTools}: parse and validate a batch of
 * 1-indexed line-range operations, apply them in a single forward pass that preserves the
 * file's native EOL and trailing-newline convention, and emit the event-log entry on success.
 */
final class FsLineEditor {

    private FsLineEditor() {}

    private sealed interface LineOp {
        int startLine();
        record Replace(int startLine, int endLine, String content) implements LineOp {}
        record Insert(int startLine, String content) implements LineOp {}
        record Delete(int startLine, int endLine) implements LineOp {}
    }

    /**
     * Edit a file by 1-indexed inclusive line numbers. All operations validate before
     * any mutation; on success the file's native line ending (LF or CRLF) and UTF-8
     * encoding are preserved. An event-log entry describing the edit is emitted on
     * successful write.
     */
    static String editLines(Agent agent, Path target, JsonArray opsJson) {
        if (opsJson.size() == 0) {
            return "Error: editLines requires a non-empty 'operations' array";
        }
        var loaded = FsSupport.loadEditableFile(target);
        if (loaded.error() != null) return loaded.error();
        var original = loaded.content();

        // Detect native line ending before splitting so we can preserve it on write.
        var nativeEol = detectLineEnding(original);
        var hadTrailingNewline = original.endsWith("\n") || original.endsWith("\r\n");
        // split with -1 keeps trailing empty segments; we drop the final empty slot
        // produced by a trailing newline so lineCount reflects authored lines.
        var lines = new ArrayList<>(List.of(original.split("\r\n|\n|\r", -1)));
        if (hadTrailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int lineCount = lines.size();

        var parsed = new ArrayList<LineOp>();
        for (int i = 0; i < opsJson.size(); i++) {
            var parsedOp = parseLineOpEntry(opsJson.get(i), lineCount, i + 1);
            if (parsedOp.error != null) return parsedOp.error;
            parsed.add(parsedOp.op);
        }

        var counts = applyParsedLineOps(parsed, lines);

        var joined = String.join(nativeEol, lines);
        // Preserve the file's trailing-newline convention: add one only if the file
        // originally had one, or if the final line is non-empty and we had content.
        if (hadTrailingNewline || (!joined.isEmpty() && !joined.endsWith(nativeEol))) {
            joined = joined + nativeEol;
        }

        var writeResult = FsWriter.writeFile(target, joined);
        if (writeResult.startsWith(FsSupport.ERROR_PREFIX)) return writeResult;

        var summary = "editLines: %d replace / %d insert / %d delete on %s"
                .formatted(counts.replaced, counts.inserted, counts.deleted, target.getFileName());
        EventLogger.info("Files", agent.name, null, summary);

        return "File written successfully: " + target.getFileName()
                + " (%d replace, %d insert, %d delete)".formatted(counts.replaced, counts.inserted, counts.deleted);
    }

    private record OpCounts(int replaced, int inserted, int deleted) {}

    /** Parse a single JSON line-op entry, validating shape, startLine type, and bounds. */
    private static ParsedOp parseLineOpEntry(JsonElement entry, int lineCount, int index) {
        if (!entry.isJsonObject()) {
            return ParsedOp.err("Error: operation #%d must be an object".formatted(index));
        }
        var opObj = entry.getAsJsonObject();
        if (!opObj.has("op") || !opObj.has(FileSystemTools.ARG_START_LINE)) {
            return ParsedOp.err("Error: operation #%d must include 'op' and 'startLine' fields".formatted(index));
        }
        var op = opObj.get("op").getAsString();
        int startLine;
        try {
            startLine = opObj.get(FileSystemTools.ARG_START_LINE).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException _) {
            return ParsedOp.err("Error: operation #%d startLine must be an integer".formatted(index));
        }
        if (startLine < 1) {
            return ParsedOp.err("Error: operation #%d startLine must be ≥ 1 (got %d)".formatted(index, startLine));
        }
        return parseLineOp(op, startLine, opObj, lineCount, index);
    }

    /**
     * Apply the validated line ops in a single forward pass: ops are sorted by their
     * (original 1-indexed) {@code startLine} so a cursor can walk the source lines once,
     * copying untouched ranges and splicing in each op's effect into a fresh list. Ops
     * reference <em>original</em> coordinates (they never see each other's edits), so this
     * is O(lines + content) rather than the O(ops × lines) array-shifting of repeated
     * {@code subList().clear()/addAll}. Ties on startLine break by original array order, so
     * inserts at the same line keep their request order. The result replaces {@code lines}
     * in place to preserve the caller's reference.
     */
    private static OpCounts applyParsedLineOps(List<LineOp> parsed, List<String> lines) {
        var ordered = new ArrayList<>(parsed);
        // Stable sort by startLine; Collections.sort is stable so equal-startLine ops
        // retain their original (request) order — matching the prior tie semantics.
        ordered.sort(Comparator.comparingInt(LineOp::startLine));

        var merged = new ArrayList<String>(lines.size());
        int cursor = 0; // 0-indexed position in the original lines not yet emitted
        int replaced = 0;
        int inserted = 0;
        int deleted = 0;
        for (var op : ordered) {
            int start = op.startLine() - 1; // 0-indexed anchor in original coordinates
            // Emit the untouched original lines before this op's anchor. Guarded so an
            // op whose start falls inside an earlier op's consumed range copies nothing.
            if (start > cursor) {
                merged.addAll(lines.subList(cursor, start));
                cursor = start;
            }
            switch (op) {
                case LineOp.Replace(_, var endLine, var content) -> {
                    merged.addAll(splitContentLines(content));
                    cursor = Math.max(cursor, endLine); // skip the replaced original lines
                    replaced++;
                }
                case LineOp.Insert(_, var content) -> {
                    merged.addAll(splitContentLines(content)); // insert before line `start`
                    inserted++;
                }
                case LineOp.Delete(_, var endLine) -> {
                    cursor = Math.max(cursor, endLine); // skip the deleted original lines
                    deleted++;
                }
            }
        }
        if (cursor < lines.size()) {
            merged.addAll(lines.subList(cursor, lines.size()));
        }
        lines.clear();
        lines.addAll(merged);
        return new OpCounts(replaced, inserted, deleted);
    }

    private record ParsedOp(LineOp op, String error) {
        static ParsedOp ok(LineOp op) { return new ParsedOp(op, null); }
        static ParsedOp err(String error) { return new ParsedOp(null, error); }
    }

    private static ParsedOp parseLineOp(String op, int startLine, JsonObject opObj, int lineCount, int index) {
        return switch (op) {
            case FileSystemTools.OP_REPLACE -> {
                if (!opObj.has(FileSystemTools.ARG_END_LINE)) {
                    yield ParsedOp.err("Error: operation #%d (replace) requires 'endLine'".formatted(index));
                }
                if (!opObj.has(FileSystemTools.ARG_CONTENT)) {
                    yield ParsedOp.err("Error: operation #%d (replace) requires 'content'".formatted(index));
                }
                int endLine = opObj.get(FileSystemTools.ARG_END_LINE).getAsInt();
                var bounds = checkBounds(index, startLine, endLine, lineCount, FileSystemTools.OP_REPLACE);
                if (bounds != null) yield ParsedOp.err(bounds);
                yield ParsedOp.ok(new LineOp.Replace(startLine, endLine, opObj.get(FileSystemTools.ARG_CONTENT).getAsString()));
            }
            case FileSystemTools.OP_DELETE -> {
                if (!opObj.has(FileSystemTools.ARG_END_LINE)) {
                    yield ParsedOp.err("Error: operation #%d (delete) requires 'endLine'".formatted(index));
                }
                int endLine = opObj.get(FileSystemTools.ARG_END_LINE).getAsInt();
                var bounds = checkBounds(index, startLine, endLine, lineCount, FileSystemTools.OP_DELETE);
                if (bounds != null) yield ParsedOp.err(bounds);
                yield ParsedOp.ok(new LineOp.Delete(startLine, endLine));
            }
            case FileSystemTools.OP_INSERT -> {
                if (!opObj.has(FileSystemTools.ARG_CONTENT)) {
                    yield ParsedOp.err("Error: operation #%d (insert) requires 'content'".formatted(index));
                }
                // insert allows startLine == lineCount + 1 to append at the end.
                if (startLine > lineCount + 1) {
                    yield ParsedOp.err("Error: operation #%d (insert) startLine %d is beyond end of file (%d lines; max allowed %d for append)"
                            .formatted(index, startLine, lineCount, lineCount + 1));
                }
                yield ParsedOp.ok(new LineOp.Insert(startLine, opObj.get(FileSystemTools.ARG_CONTENT).getAsString()));
            }
            default -> ParsedOp.err("Error: operation #%d has unknown op '%s' (expected replace, insert, or delete)"
                    .formatted(index, op));
        };
    }

    private static String checkBounds(int index, int startLine, int endLine, int lineCount, String opName) {
        if (endLine < startLine) {
            return "Error: operation #%d (%s) endLine %d < startLine %d".formatted(index, opName, endLine, startLine);
        }
        if (startLine > lineCount) {
            return "Error: operation #%d (%s) startLine %d exceeds file length (%d lines)"
                    .formatted(index, opName, startLine, lineCount);
        }
        if (endLine > lineCount) {
            return "Error: operation #%d (%s) endLine %d exceeds file length (%d lines)"
                    .formatted(index, opName, endLine, lineCount);
        }
        return null;
    }

    /** Detect the file's predominant newline sequence. Defaults to LF for empty or single-line files. */
    private static String detectLineEnding(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') crlf++;
                else lf++;
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    /** Split caller-supplied content into lines, stripping a single trailing newline
     *  so it doesn't produce an empty line once re-joined by the file's native EOL. */
    private static List<String> splitContentLines(String content) {
        if (content.isEmpty()) return List.of();
        var trimmed = content;
        if (trimmed.endsWith("\r\n")) trimmed = trimmed.substring(0, trimmed.length() - 2);
        else if (trimmed.endsWith("\n") || trimmed.endsWith("\r")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return new ArrayList<>(List.of(trimmed.split("\r\n|\n|\r", -1)));
    }
}
