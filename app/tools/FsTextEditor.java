package tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import tools.FsSupport.EditResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code editFile} family for {@link FileSystemTools}: a batch of literal or regex
 * {oldText → newText} replacements applied atomically against a running working buffer,
 * plus the diagnostic-snippet helpers that shape the miss/ambiguity error messages.
 */
final class FsTextEditor {

    private FsTextEditor() {}

    private static final int MAX_ERROR_SNIPPET_CHARS = 1500;

    /**
     * Apply a batch of literal or regex replacements to {@code target}. Each edit must
     * match its {@code oldText} exactly once against the running working buffer; a miss
     * or an ambiguous match aborts the whole batch, leaving the file untouched. The
     * final content is handed to {@link FsWriter#writeFile} so the SKILL.md version-bump
     * pipeline fires exactly once against the fully-edited state.
     */
    static String editFile(Path target, JsonArray editsJson) {
        if (editsJson.size() == 0) {
            return "Error: editFile requires a non-empty 'edits' array";
        }
        var loaded = FsSupport.loadEditableFile(target);
        if (loaded.error() != null) return loaded.error();

        var working = loaded.content();
        var notes = new ArrayList<String>();
        // Per-batch regex cache: reuses the compiled Pattern across edits that
        // share the same oldText. Scope is one editFile call; the map is
        // discarded when this loop returns, so no cross-batch global state.
        var regexCache = new HashMap<String, Pattern>();

        for (int i = 0; i < editsJson.size(); i++) {
            var applied = applyEditAtIndex(editsJson.get(i), working, i + 1, regexCache);
            if (applied.error() != null) return applied.error();
            working = applied.result();
            if (applied.note() != null) notes.add(applied.note());
        }

        if (working.equals(loaded.content())) {
            notes.add("(no material change)");
        }

        var writeResult = FsWriter.writeFile(target, working);
        if (writeResult.startsWith(FsSupport.ERROR_PREFIX)) return writeResult;

        if (notes.isEmpty()) return writeResult;
        return writeResult + " " + String.join(" ", notes);
    }

    private static EditResult applyEditAtIndex(JsonElement entry, String working, int editIndex,
                                         Map<String, Pattern> regexCache) {
        if (!entry.isJsonObject()) {
            return EditResult.err("Error: edit #%d must be an object with oldText and newText fields".formatted(editIndex));
        }
        var edit = entry.getAsJsonObject();
        if (!edit.has(FileSystemTools.ARG_OLD_TEXT) || !edit.has(FileSystemTools.ARG_NEW_TEXT)) {
            return EditResult.err("Error: edit #%d must include oldText and newText fields".formatted(editIndex));
        }
        var oldText = edit.get(FileSystemTools.ARG_OLD_TEXT).getAsString();
        var newText = edit.get(FileSystemTools.ARG_NEW_TEXT).getAsString();
        var isRegex = edit.has(FileSystemTools.ARG_REGEX) && edit.get(FileSystemTools.ARG_REGEX).getAsBoolean();
        if (oldText.isEmpty()) {
            return EditResult.err("Error: edit #%d has an empty oldText".formatted(editIndex));
        }
        return applySingleEdit(working, oldText, newText, isRegex, editIndex, regexCache);
    }

    private static EditResult applySingleEdit(String working, String oldText, String newText, boolean isRegex,
                                        int editIndex, Map<String, Pattern> regexCache) {
        if (isRegex) {
            return applyRegexEdit(working, oldText, newText, editIndex, regexCache);
        }
        return applyLiteralEdit(working, oldText, newText, editIndex);
    }

    private static EditResult applyRegexEdit(String working, String oldText, String newText, int editIndex,
                                       Map<String, Pattern> regexCache) {
        Pattern pattern = regexCache.get(oldText);
        if (pattern == null) {
            try {
                pattern = Pattern.compile(oldText);
            } catch (PatternSyntaxException e) {
                return EditResult.err("Error: edit #%d has an invalid regex: %s".formatted(editIndex, e.getMessage()));
            }
            regexCache.put(oldText, pattern);
        }
        var matcher = pattern.matcher(working);
        var matchStarts = new ArrayList<Integer>();
        int total = 0;
        while (matcher.find()) {
            total++;
            if (matchStarts.size() < 3) matchStarts.add(matcher.start());
        }
        if (total > 1) {
            var lines = matchStarts.stream().map(start -> lineNumberAt(working, start)).toList();
            return EditResult.err(("Error: edit #%d regex /%s/ matched %d times (expected exactly one). "
                    + "First match line numbers: %s. Tighten the regex or include more context.")
                    .formatted(editIndex, oldText, total, lines));
        }
        if (total == 0) {
            var snippet = capSnippet("regex /%s/ did not match. File begins with:\n%s"
                    .formatted(oldText, firstNLines(working, 40)));
            return EditResult.err("Error: edit #%d failed — %s".formatted(editIndex, snippet));
        }
        // replaceFirst interprets $1/$2 as backreferences and \$ as a literal $.
        return EditResult.ok(pattern.matcher(working).replaceFirst(newText));
    }

    private static EditResult applyLiteralEdit(String working, String oldText, String newText, int editIndex) {
        var count = countOccurrences(working, oldText);
        if (count == 1) {
            return EditResult.ok(working.replace(oldText, newText));
        }
        if (count > 1) {
            var lines = occurrenceLineNumbers(working, oldText, 3);
            return EditResult.err(("Error: edit #%d oldText is not unique (found %d occurrences at lines %s). "
                    + "Include more surrounding context to disambiguate.")
                    .formatted(editIndex, count, lines));
        }

        // Zero literal matches — try CRLF → LF normalization once.
        var normalizedWorking = working.replace("\r\n", "\n");
        var normalizedOld = oldText.replace("\r\n", "\n");
        var normalizedCount = countOccurrences(normalizedWorking, normalizedOld);
        if (normalizedCount == 1) {
            // The match was found only after normalizing the whole buffer, but we
            // splice newText into the ORIGINAL buffer at the matched span so that
            // CRLFs outside the edited region keep their original line endings —
            // mirroring the EOL-preservation convention of the editLines path.
            int normStart = normalizedWorking.indexOf(normalizedOld);
            int origStart = originalIndexForNormalized(working, normStart);
            int origEnd = originalIndexForNormalized(working, normStart + normalizedOld.length());
            return EditResult.okWithNote(
                    working.substring(0, origStart) + newText + working.substring(origEnd),
                    "(edit #%d matched after normalizing CRLF→LF)".formatted(editIndex));
        }
        if (normalizedCount > 1) {
            var lines = occurrenceLineNumbers(normalizedWorking, normalizedOld, 3);
            return EditResult.err(("Error: edit #%d oldText is not unique after CRLF→LF normalization "
                    + "(found %d occurrences at lines %s). Include more surrounding context.")
                    .formatted(editIndex, normalizedCount, lines));
        }

        // Still zero — produce a diagnostic snippet.
        var snippet = nearestPartialMatchSnippet(working, oldText);
        return EditResult.err(capSnippet("Error: edit #%d oldText not found in file.\n%s"
                .formatted(editIndex, snippet)));
    }

    /**
     * Map an index into the CRLF→LF-normalized form of {@code original} back to the
     * corresponding index in {@code original}. Normalization only drops the {@code \r}
     * of each {@code \r\n} pair, so we walk the original, advancing a normalized counter
     * by one per emitted char, and stop once it reaches {@code normalizedIndex}. A bare
     * {@code \r} (CR not followed by LF) is preserved by normalization and so counts as
     * one normalized char like any other.
     */
    private static int originalIndexForNormalized(String original, int normalizedIndex) {
        int norm = 0;
        int i = 0;
        while (i < original.length() && norm < normalizedIndex) {
            if (original.charAt(i) == '\r' && i + 1 < original.length() && original.charAt(i + 1) == '\n') {
                i++; // skip the \r; the following \n is the single normalized char
            }
            i++;
            norm++;
        }
        return i;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static List<Integer> occurrenceLineNumbers(String haystack, String needle, int max) {
        var out = new ArrayList<Integer>();
        int idx = 0;
        while (out.size() < max && (idx = haystack.indexOf(needle, idx)) != -1) {
            out.add(lineNumberAt(haystack, idx));
            idx += needle.length();
        }
        return out;
    }

    private static int lineNumberAt(String text, int charIndex) {
        int line = 1;
        for (int i = 0; i < charIndex && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Build a helpful snippet for the "oldText not found" error. Walks progressively
     * shorter prefixes of oldText and shows ±200 chars around the first hit. Falls back
     * to the first 40 lines of the file if no prefix matches anywhere.
     */
    private static String nearestPartialMatchSnippet(String file, String oldText) {
        int[] prefixLens = {40, 20, 10};
        for (int len : prefixLens) {
            if (oldText.length() < len) continue;
            var prefix = oldText.substring(0, len);
            var idx = file.indexOf(prefix);
            if (idx >= 0) {
                return "Nearest partial match (first %d chars of oldText) at line %d:\n%s"
                        .formatted(len, lineNumberAt(file, idx), snippetAround(file, idx, 200));
            }
        }
        // First line of oldText as a last-ditch anchor.
        var firstLine = oldText.split("\n", 2)[0];
        if (!firstLine.isEmpty() && firstLine.length() < oldText.length()) {
            var idx = file.indexOf(firstLine);
            if (idx >= 0) {
                return "Nearest partial match (first line of oldText) at line %d:\n%s"
                        .formatted(lineNumberAt(file, idx), snippetAround(file, idx, 200));
            }
        }
        return "No partial match found. File begins with:\n" + firstNLines(file, 40);
    }

    private static String snippetAround(String text, int centerChar, int radius) {
        int start = Math.max(0, centerChar - radius);
        int end = Math.min(text.length(), centerChar + radius);
        var snippet = text.substring(start, end);
        return (start > 0 ? "…" : "") + snippet + (end < text.length() ? "…" : "");
    }

    private static String firstNLines(String text, int n) {
        var split = text.split("\n", -1);
        var take = Math.min(n, split.length);
        var sb = new StringBuilder();
        for (int i = 0; i < take; i++) {
            sb.append(i + 1).append(" | ").append(split[i]);
            if (i < take - 1) sb.append("\n");
        }
        if (split.length > take) sb.append("\n… (").append(split.length - take).append(" more lines)");
        return sb.toString();
    }

    private static String capSnippet(String snippet) {
        if (snippet.length() <= MAX_ERROR_SNIPPET_CHARS) return snippet;
        return snippet.substring(0, MAX_ERROR_SNIPPET_CHARS) + "… (truncated)";
    }
}
