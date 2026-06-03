package tools;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import services.AgentService;
import services.EventLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileSystemTools implements ToolRegistry.Tool {

    // === Action names ===
    private static final String ACTION_READ_FILE = "readFile";
    private static final String ACTION_WRITE_FILE = "writeFile";
    private static final String ACTION_APPEND_FILE = "appendFile";
    private static final String ACTION_EDIT_FILE = "editFile";
    private static final String ACTION_EDIT_LINES = "editLines";
    private static final String ACTION_APPLY_PATCH = "applyPatch";
    private static final String ACTION_LIST_FILES = "listFiles";

    // === Argument keys (JSON field names on tool calls) ===
    private static final String ARG_ACTION = "action";
    private static final String ARG_CONTENT = "content";
    private static final String ARG_EDITS = "edits";
    private static final String ARG_OPERATIONS = "operations";
    private static final String ARG_OLD_TEXT = "oldText";
    private static final String ARG_NEW_TEXT = "newText";
    private static final String ARG_REGEX = "regex";
    private static final String ARG_START_LINE = "startLine";
    private static final String ARG_END_LINE = "endLine";
    private static final String ARG_PATCH = "patch";

    // === Line-op names ===
    private static final String OP_REPLACE = "replace";
    private static final String OP_DELETE = "delete";
    private static final String OP_INSERT = "insert";

    // === Common error message templates ===
    private static final String ERROR_PREFIX = "Error";
    private static final String ERROR_PREFIX_COLON = "Error: ";
    private static final String ERROR_FILE_NOT_FOUND = "Error: File not found: %s";
    private static final String ERROR_READING_FILE = "Error reading file: %s";

    @Override
    public String name() { return "filesystem"; }

    @Override
    public String category() { return "Files"; }

    @Override
    public String icon() { return "folder"; }

    @Override
    public String shortDescription() {
        return "Read, write, edit, list, and patch plain text files in the agent's workspace.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction(ACTION_READ_FILE,   "Read file content up to 1 MB"),
                new agents.ToolAction(ACTION_WRITE_FILE,  "Create or overwrite a file with full content"),
                new agents.ToolAction(ACTION_APPEND_FILE, "Append content to the end of a file, creating it if missing"),
                new agents.ToolAction(ACTION_EDIT_FILE,   "Apply a batch of oldText → newText replacements atomically"),
                new agents.ToolAction(ACTION_EDIT_LINES,  "Replace, insert, or delete specific 1-indexed line ranges atomically"),
                new agents.ToolAction(ACTION_APPLY_PATCH, "Apply a multi-file unified diff patch"),
                new agents.ToolAction(ACTION_LIST_FILES,  "List the contents of a directory")
        );
    }

    @Override
    public String description() {
        return """
                Read, write, edit, list, and patch plain text files in the agent's workspace. \
                Actions: \
                'readFile' reads a file (1 MB cap; use the 'documents' tool for PDF/DOCX/XLSX/PPTX). \
                'writeFile' creates or overwrites a file with its full content — use only for brand-new files or wholesale replacement. \
                'appendFile' appends content to the end of an existing file, or creates it if missing. Use this to build up large files across multiple tool calls when a single writeFile would exceed your output token budget (e.g. long markdown drafts, logs, incremental emission). The LLM picks the chunk size. \
                'editFile' is the DEFAULT for modifying an existing file: it applies a batch of {oldText, newText} replacements. Each oldText must appear exactly once in the file; include enough surrounding context to disambiguate. Optionally set regex: true on an edit entry to treat oldText as a Java regex (with $1 backreferences in newText). Edits apply atomically — if any entry fails, nothing is written. \
                'editLines' edits by 1-indexed inclusive line numbers when you already know which lines to change (e.g., after readFile). Operations array entries are {op, startLine, endLine?, content?} with op = 'replace' | 'insert' | 'delete'. Insert places content before startLine (use startLine = lineCount+1 to append). The file's native line endings (LF vs CRLF) and UTF-8 encoding are preserved. All operations validate before any are written; on success the edit is recorded in the event log. \
                'applyPatch' applies a multi-file patch in OpenClaw/unified-diff format (*** Begin Patch / *** Update File: / *** Add File: / *** Delete File: / *** Move to: / *** End of File / *** End Patch). All files are validated before any are written. \
                'listFiles' lists a directory. \
                All paths are relative to the workspace. For rich document formats use the 'documents' tool — and for large rich documents, draft the markdown here via writeFile + appendFile, then call documents.renderDocument with the source path.""";
    }

    @Override
    public String summary() {
        return "Read, write, edit, list, and patch plain text files via the 'action' parameter: readFile, writeFile, appendFile, editFile, editLines, applyPatch, listFiles.";
    }

    @Override
    public Map<String, Object> parameters() {
        // `path` is required for every action except applyPatch (which encodes paths in the patch body),
        // and `edits`/`content`/`patch` are action-specific. JSON Schema's polymorphism is awkward across
        // providers, so we keep `required` minimal and validate action-specific fields inside execute().
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.ofEntries(
                        Map.entry(ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of(ACTION_READ_FILE, ACTION_WRITE_FILE, ACTION_APPEND_FILE,
                                        ACTION_LIST_FILES, ACTION_EDIT_FILE, ACTION_EDIT_LINES, ACTION_APPLY_PATCH),
                                SchemaKeys.DESCRIPTION, "The file operation to perform")),
                        Map.entry("path", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "File or directory path relative to workspace (required for all actions except applyPatch)")),
                        Map.entry(ARG_CONTENT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Content to write (for writeFile and appendFile actions)")),
                        Map.entry(ARG_EDITS, Map.of(SchemaKeys.TYPE, SchemaKeys.ARRAY,
                                SchemaKeys.DESCRIPTION, "List of {oldText, newText, regex?} replacements for editFile action",
                                SchemaKeys.ITEMS, Map.of(
                                        SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                        SchemaKeys.PROPERTIES, Map.of(
                                                ARG_OLD_TEXT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                                                ARG_NEW_TEXT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                                                ARG_REGEX, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                                                        SchemaKeys.DESCRIPTION, "If true, treat oldText as a Java regex with $N backreferences in newText. Default false.")
                                        ),
                                        SchemaKeys.REQUIRED, List.of(ARG_OLD_TEXT, ARG_NEW_TEXT)
                                ))),
                        Map.entry(ARG_OPERATIONS, Map.of(SchemaKeys.TYPE, SchemaKeys.ARRAY,
                                SchemaKeys.DESCRIPTION, "Ordered list of line-range operations for editLines action. 1-indexed, inclusive endLine.",
                                SchemaKeys.ITEMS, Map.of(
                                        SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                        SchemaKeys.PROPERTIES, Map.of(
                                                "op", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                                        SchemaKeys.ENUM, List.of(OP_REPLACE, OP_INSERT, OP_DELETE)),
                                                ARG_START_LINE, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER, "minimum", 1,
                                                        SchemaKeys.DESCRIPTION, "1-indexed line number. For insert, content is placed before this line; use lineCount+1 to append."),
                                                ARG_END_LINE, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER, "minimum", 1,
                                                        SchemaKeys.DESCRIPTION, "Inclusive end line. Required for replace/delete; ignored for insert."),
                                                ARG_CONTENT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                                        SchemaKeys.DESCRIPTION, "New text for replace/insert. Trailing newline is added automatically if missing. Ignored for delete.")
                                        ),
                                        SchemaKeys.REQUIRED, List.of("op", ARG_START_LINE)
                                ))),
                        Map.entry(ARG_PATCH, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Patch body for applyPatch action, wrapped in *** Begin Patch / *** End Patch"))
                ),
                SchemaKeys.REQUIRED, List.of(ARG_ACTION)
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get(ARG_ACTION).getAsString();

        // applyPatch encodes its paths inside the patch body, so it does not take a top-level `path` field.
        if (ACTION_APPLY_PATCH.equals(action)) {
            var patch = args.has(ARG_PATCH) ? args.get(ARG_PATCH).getAsString() : "";
            return applyPatch(agent, patch);
        }

        var resolved = resolveTargetPath(args, agent, action);
        if (resolved.error != null) return resolved.error;

        if (isMutatingAction(action)) {
            var guardError = checkSkillCreatorReadOnly(agent, resolved.workspace, resolved.target);
            if (guardError != null) return guardError;
        }

        return dispatchAction(action, args, agent, resolved.target);
    }

    private record TargetPath(Path workspace, Path target, String error) {
        static TargetPath ok(Path workspace, Path target) { return new TargetPath(workspace, target, null); }
        static TargetPath err(String error) { return new TargetPath(null, null, error); }
    }

    private static TargetPath resolveTargetPath(JsonObject args, Agent agent, String action) {
        if (!args.has("path")) {
            return TargetPath.err("Error: action '%s' requires a 'path' field".formatted(action));
        }
        var relativePath = args.get("path").getAsString();
        var workspace = AgentService.workspacePath(agent.name);
        try {
            var target = AgentService.acquireWorkspacePath(agent.name, relativePath);
            return TargetPath.ok(workspace, target);
        } catch (SecurityException e) {
            return TargetPath.err(ERROR_PREFIX_COLON + e.getMessage());
        }
    }

    private String dispatchAction(String action, JsonObject args, Agent agent, Path target) {
        return switch (action) {
            case ACTION_READ_FILE -> readFile(target);
            case ACTION_LIST_FILES -> listFiles(target);
            case ACTION_WRITE_FILE -> withLock(target, () -> writeFile(target, stringArg(args, ARG_CONTENT)));
            case ACTION_APPEND_FILE -> withLock(target, () -> appendFile(target, stringArg(args, ARG_CONTENT)));
            case ACTION_EDIT_FILE -> withLock(target, () -> {
                if (!args.has(ARG_EDITS) || !args.get(ARG_EDITS).isJsonArray()) {
                    return "Error: editFile requires an 'edits' array";
                }
                return editFile(target, args.getAsJsonArray(ARG_EDITS));
            });
            case ACTION_EDIT_LINES -> withLock(target, () -> {
                if (!args.has(ARG_OPERATIONS) || !args.get(ARG_OPERATIONS).isJsonArray()) {
                    return "Error: editLines requires an 'operations' array";
                }
                return editLines(agent, target, args.getAsJsonArray(ARG_OPERATIONS));
            });
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private static String stringArg(JsonObject args, String name) {
        return args.has(name) ? args.get(name).getAsString() : "";
    }

    private static boolean isMutatingAction(String action) {
        return ACTION_WRITE_FILE.equals(action) || ACTION_APPEND_FILE.equals(action)
                || ACTION_EDIT_FILE.equals(action) || ACTION_EDIT_LINES.equals(action);
    }

    /**
     * Skill-creator is read-only for every agent except 'main'. Only the main agent may
     * modify the skill-creator skill itself; other agents can use it to create and refactor
     * OTHER skills but cannot alter skill-creator. Returns an error string if blocked, or
     * null if the path is OK to mutate.
     */
    private static String checkSkillCreatorReadOnly(Agent agent, Path workspace, Path target) {
        if ("main".equalsIgnoreCase(agent.name)) return null;
        var skillCreatorDir = AgentService.resolveContained(workspace, "skills/skill-creator");
        if (skillCreatorDir != null && target.startsWith(skillCreatorDir)) {
            return "Error: The 'skill-creator' skill is read-only for agent '"
                    + agent.name
                    + "'. Only the 'main' agent can modify skill-creator. "
                    + "To get an updated skill-creator, ask the user to drag skill-creator "
                    + "from the global skills registry onto this agent's card.";
        }
        return null;
    }

    // === readFile / writeFile / listFiles ===

    private static final long MAX_FILE_READ_BYTES = 1_048_576; // 1MB

    private String readFile(Path path) {
        try {
            if (!Files.exists(path)) return ERROR_FILE_NOT_FOUND.formatted(path.getFileName());
            if (Files.size(path) > MAX_FILE_READ_BYTES) {
                return "Error: File exceeds read limit (%d bytes). File size: %d bytes. "
                        .formatted(MAX_FILE_READ_BYTES, Files.size(path))
                        + "For rich document formats (PDF, DOCX, XLSX, etc.), use the 'documents' tool's readDocument action.";
            }
            return Files.readString(path);
        } catch (IOException e) {
            return ERROR_READING_FILE.formatted(e.getMessage());
        }
    }

    private String writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());

            // Deterministic version handling for skill definitions: any write to
            // workspace/{agent}/skills/{skill-name}/SKILL.md is routed through
            // SkillLoader.finalizeSkillMdWrite, which auto-bumps the patch version on
            // material changes and ignores whatever the LLM wrote in the version: field.
            String finalContent = content;
            String versionNote = "";
            if (isSkillDefinitionFile(path)) {
                var previousInfo = Files.exists(path) ? SkillLoader.parseSkillFile(path) : null;
                finalContent = SkillLoader.finalizeSkillMdWrite(path, content);
                var newInfo = SkillLoader.parseSkillContent(finalContent, path);
                if (newInfo != null) {
                    if (previousInfo == null) {
                        versionNote = " (new skill at version " + newInfo.version() + ")";
                    } else if (!previousInfo.version().equals(newInfo.version())) {
                        versionNote = " (version bumped " + previousInfo.version()
                                + " → " + newInfo.version() + ")";
                    } else {
                        versionNote = " (no material change; version " + newInfo.version() + " preserved)";
                    }
                }
            }

            Files.writeString(path, finalContent);
            return "File written successfully: " + path.getFileName() + versionNote;
        } catch (IOException e) {
            return "Error writing file: %s".formatted(e.getMessage());
        }
    }

    /**
     * Append content to the tail of an existing file, or create it if missing.
     * Primary use case is letting the LLM build up a large file across multiple
     * tool calls when a single {@code writeFile} would exceed its output token
     * budget. Skill definition files route through {@code writeFile} for their
     * version-bump pipeline, so appending to a SKILL.md is explicitly rejected —
     * an append is ambiguous against the version-management semantics and the
     * LLM should use writeFile (or editFile) for skill authoring.
     */
    private String appendFile(Path path, String content) {
        try {
            if (isSkillDefinitionFile(path)) {
                return "Error: appendFile is not supported for SKILL.md files. "
                        + "Use writeFile for a full replacement (version bumps are handled automatically) "
                        + "or editFile to patch specific sections.";
            }
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                Files.writeString(path, content,
                        java.nio.file.StandardOpenOption.APPEND);
                long size = Files.size(path);
                return "Appended %d chars to %s (total %d bytes)"
                        .formatted(content.length(), path.getFileName(), size);
            }
            Files.writeString(path, content);
            return "File created (appendFile on missing file): " + path.getFileName()
                    + " (" + content.length() + " chars)";
        } catch (IOException e) {
            return "Error appending to file: %s".formatted(e.getMessage());
        }
    }

    private String listFiles(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return "Error: Not a directory: %s".formatted(dir.getFileName());
            try (var stream = Files.list(dir)) {
                var entries = stream.map(p -> {
                    var name = p.getFileName().toString();
                    return Files.isDirectory(p) ? name + "/" : name;
                }).sorted().toList();
                return entries.isEmpty() ? "(empty directory)" : String.join("\n", entries);
            }
        } catch (IOException e) {
            return "Error listing directory: %s".formatted(e.getMessage());
        }
    }

    /**
     * True when {@code path} points at a SKILL.md directly inside a skill folder —
     * i.e., the path ends in {@code .../skills/{skillName}/SKILL.md}. Used to scope
     * the deterministic version bump logic to actual skill definition writes.
     */
    private static boolean isSkillDefinitionFile(Path path) {
        if (!"SKILL.md".equals(path.getFileName().toString())) return false;
        var parent = path.getParent();
        if (parent == null) return false;
        var grandparent = parent.getParent();
        if (grandparent == null) return false;
        return "skills".equals(grandparent.getFileName().toString());
    }

    // === editFile ===

    private static final int MAX_ERROR_SNIPPET_CHARS = 1500;

    /**
     * Apply a batch of literal or regex replacements to {@code target}. Each edit must
     * match its {@code oldText} exactly once against the running working buffer; a miss
     * or an ambiguous match aborts the whole batch, leaving the file untouched. The
     * final content is handed to {@link #writeFile} so the SKILL.md version-bump pipeline
     * fires exactly once against the fully-edited state.
     */
    private String editFile(Path target, JsonArray editsJson) {
        if (editsJson.size() == 0) {
            return "Error: editFile requires a non-empty 'edits' array";
        }
        var loaded = loadEditableFile(target);
        if (loaded.error != null) return loaded.error;

        var working = loaded.content;
        var notes = new ArrayList<String>();
        // Per-batch regex cache: reuses the compiled Pattern across edits that
        // share the same oldText. Scope is one editFile call; the map is
        // discarded when this loop returns, so no cross-batch global state.
        var regexCache = new java.util.HashMap<String, Pattern>();

        for (int i = 0; i < editsJson.size(); i++) {
            var applied = applyEditAtIndex(editsJson.get(i), working, i + 1, regexCache);
            if (applied.error != null) return applied.error;
            working = applied.result;
            if (applied.note != null) notes.add(applied.note);
        }

        if (working.equals(loaded.content)) {
            notes.add("(no material change)");
        }

        var writeResult = writeFile(target, working);
        if (writeResult.startsWith(ERROR_PREFIX)) return writeResult;

        if (notes.isEmpty()) return writeResult;
        return writeResult + " " + String.join(" ", notes);
    }

    private record LoadedFile(String content, String error) {
        static LoadedFile ok(String content) { return new LoadedFile(content, null); }
        static LoadedFile err(String error) { return new LoadedFile(null, error); }
    }

    /**
     * Validate that {@code target} exists and is within the edit-size limit, then read its
     * full content. Returns the content, or an error string already shaped for the caller's
     * return value.
     */
    private static LoadedFile loadEditableFile(Path target) {
        if (!Files.exists(target)) {
            return LoadedFile.err(ERROR_FILE_NOT_FOUND.formatted(target.getFileName()));
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return LoadedFile.err("Error reading file size: %s".formatted(e.getMessage()));
        }
        if (size > MAX_FILE_READ_BYTES) {
            return LoadedFile.err("Error: File exceeds edit size limit (%d bytes). File size: %d bytes. Consider using writeFile for wholesale replacement instead."
                    .formatted(MAX_FILE_READ_BYTES, size));
        }
        try {
            return LoadedFile.ok(Files.readString(target));
        } catch (IOException e) {
            return LoadedFile.err(ERROR_READING_FILE.formatted(e.getMessage()));
        }
    }

    private EditResult applyEditAtIndex(com.google.gson.JsonElement entry, String working, int editIndex,
                                         java.util.Map<String, Pattern> regexCache) {
        if (!entry.isJsonObject()) {
            return EditResult.err("Error: edit #%d must be an object with oldText and newText fields".formatted(editIndex));
        }
        var edit = entry.getAsJsonObject();
        if (!edit.has(ARG_OLD_TEXT) || !edit.has(ARG_NEW_TEXT)) {
            return EditResult.err("Error: edit #%d must include oldText and newText fields".formatted(editIndex));
        }
        var oldText = edit.get(ARG_OLD_TEXT).getAsString();
        var newText = edit.get(ARG_NEW_TEXT).getAsString();
        var isRegex = edit.has(ARG_REGEX) && edit.get(ARG_REGEX).getAsBoolean();
        if (oldText.isEmpty()) {
            return EditResult.err("Error: edit #%d has an empty oldText".formatted(editIndex));
        }
        return applySingleEdit(working, oldText, newText, isRegex, editIndex, regexCache);
    }

    private record EditResult(String result, String error, String note) {
        static EditResult ok(String result) { return new EditResult(result, null, null); }
        static EditResult okWithNote(String result, String note) { return new EditResult(result, null, note); }
        static EditResult err(String error) { return new EditResult(null, error, null); }
    }

    private EditResult applySingleEdit(String working, String oldText, String newText, boolean isRegex,
                                        int editIndex, java.util.Map<String, Pattern> regexCache) {
        if (isRegex) {
            return applyRegexEdit(working, oldText, newText, editIndex, regexCache);
        }
        return applyLiteralEdit(working, oldText, newText, editIndex);
    }

    private EditResult applyRegexEdit(String working, String oldText, String newText, int editIndex,
                                       java.util.Map<String, Pattern> regexCache) {
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

    private EditResult applyLiteralEdit(String working, String oldText, String newText, int editIndex) {
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

    // === editLines ===

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
    private String editLines(Agent agent, Path target, JsonArray opsJson) {
        if (opsJson.size() == 0) {
            return "Error: editLines requires a non-empty 'operations' array";
        }
        var loaded = loadEditableFile(target);
        if (loaded.error != null) return loaded.error;
        var original = loaded.content;

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

        var writeResult = writeFile(target, joined);
        if (writeResult.startsWith(ERROR_PREFIX)) return writeResult;

        var summary = "editLines: %d replace / %d insert / %d delete on %s"
                .formatted(counts.replaced, counts.inserted, counts.deleted, target.getFileName());
        EventLogger.info("Files", agent.name, null, summary);

        return "File written successfully: " + target.getFileName()
                + " (%d replace, %d insert, %d delete)".formatted(counts.replaced, counts.inserted, counts.deleted);
    }

    private record OpCounts(int replaced, int inserted, int deleted) {}

    /** Parse a single JSON line-op entry, validating shape, startLine type, and bounds. */
    private static ParsedOp parseLineOpEntry(com.google.gson.JsonElement entry, int lineCount, int index) {
        if (!entry.isJsonObject()) {
            return ParsedOp.err("Error: operation #%d must be an object".formatted(index));
        }
        var opObj = entry.getAsJsonObject();
        if (!opObj.has("op") || !opObj.has(ARG_START_LINE)) {
            return ParsedOp.err("Error: operation #%d must include 'op' and 'startLine' fields".formatted(index));
        }
        var op = opObj.get("op").getAsString();
        int startLine;
        try {
            startLine = opObj.get(ARG_START_LINE).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException _) {
            return ParsedOp.err("Error: operation #%d startLine must be an integer".formatted(index));
        }
        if (startLine < 1) {
            return ParsedOp.err("Error: operation #%d startLine must be ≥ 1 (got %d)".formatted(index, startLine));
        }
        return parseLineOp(op, startLine, opObj, lineCount, index);
    }

    /**
     * Apply the validated line ops to the mutable line list bottom-up so earlier ops don't
     * shift line indices referenced by later ones. Ties on startLine break by original array
     * order (later entries apply first).
     */
    private static OpCounts applyParsedLineOps(List<LineOp> parsed, List<String> lines) {
        var indexed = new ArrayList<int[]>();
        for (int i = 0; i < parsed.size(); i++) indexed.add(new int[]{i});
        indexed.sort((a, b) -> {
            int cmp = Integer.compare(parsed.get(b[0]).startLine(), parsed.get(a[0]).startLine());
            if (cmp != 0) return cmp;
            return Integer.compare(b[0], a[0]);
        });

        int replaced = 0;
        int inserted = 0;
        int deleted = 0;
        for (var entry : indexed) {
            switch (parsed.get(entry[0])) {
                case LineOp.Replace(var startLine, var endLine, var content) -> {
                    lines.subList(startLine - 1, endLine).clear();
                    lines.addAll(startLine - 1, splitContentLines(content));
                    replaced++;
                }
                case LineOp.Insert(var startLine, var content) -> {
                    lines.addAll(startLine - 1, splitContentLines(content));
                    inserted++;
                }
                case LineOp.Delete(var startLine, var endLine) -> {
                    lines.subList(startLine - 1, endLine).clear();
                    deleted++;
                }
            }
        }
        return new OpCounts(replaced, inserted, deleted);
    }

    private record ParsedOp(LineOp op, String error) {
        static ParsedOp ok(LineOp op) { return new ParsedOp(op, null); }
        static ParsedOp err(String error) { return new ParsedOp(null, error); }
    }

    private static ParsedOp parseLineOp(String op, int startLine, JsonObject opObj, int lineCount, int index) {
        return switch (op) {
            case OP_REPLACE -> {
                if (!opObj.has(ARG_END_LINE)) {
                    yield ParsedOp.err("Error: operation #%d (replace) requires 'endLine'".formatted(index));
                }
                if (!opObj.has(ARG_CONTENT)) {
                    yield ParsedOp.err("Error: operation #%d (replace) requires 'content'".formatted(index));
                }
                int endLine = opObj.get(ARG_END_LINE).getAsInt();
                var bounds = checkBounds(index, startLine, endLine, lineCount, OP_REPLACE);
                if (bounds != null) yield ParsedOp.err(bounds);
                yield ParsedOp.ok(new LineOp.Replace(startLine, endLine, opObj.get(ARG_CONTENT).getAsString()));
            }
            case OP_DELETE -> {
                if (!opObj.has(ARG_END_LINE)) {
                    yield ParsedOp.err("Error: operation #%d (delete) requires 'endLine'".formatted(index));
                }
                int endLine = opObj.get(ARG_END_LINE).getAsInt();
                var bounds = checkBounds(index, startLine, endLine, lineCount, OP_DELETE);
                if (bounds != null) yield ParsedOp.err(bounds);
                yield ParsedOp.ok(new LineOp.Delete(startLine, endLine));
            }
            case OP_INSERT -> {
                if (!opObj.has(ARG_CONTENT)) {
                    yield ParsedOp.err("Error: operation #%d (insert) requires 'content'".formatted(index));
                }
                // insert allows startLine == lineCount + 1 to append at the end.
                if (startLine > lineCount + 1) {
                    yield ParsedOp.err("Error: operation #%d (insert) startLine %d is beyond end of file (%d lines; max allowed %d for append)"
                            .formatted(index, startLine, lineCount, lineCount + 1));
                }
                yield ParsedOp.ok(new LineOp.Insert(startLine, opObj.get(ARG_CONTENT).getAsString()));
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

    // === applyPatch ===

    /**
     * Apply a multi-file patch in the OpenClaw/unified-diff format:
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
     * All file operations are validated atomically before any write hits disk. On
     * application IO error, best-effort rollback restores pre-edit content and removes
     * newly-created files.
     */
    private String applyPatch(Agent agent, String patchBody) {
        if (patchBody == null || patchBody.isBlank()) {
            return "Error: applyPatch requires a non-empty 'patch' field";
        }

        List<FileOp> ops;
        try {
            ops = PatchParser.parse(patchBody);
        } catch (PatchParseException e) {
            return "Error: malformed patch at line %d: %s".formatted(e.line, e.getMessage());
        }

        if (ops.isEmpty()) {
            return "Error: patch contains no file operations";
        }

        var workspace = AgentService.workspacePath(agent.name);
        var resolution = resolvePatchOps(ops, agent, workspace);
        if (resolution.error != null) return resolution.error;

        return runUnderFileLocks(resolution.resolved, () -> applyPatchLocked(resolution.resolved));
    }

    private record PatchResolution(List<ResolvedOp> resolved, String error) {
        static PatchResolution ok(List<ResolvedOp> resolved) { return new PatchResolution(resolved, null); }
        static PatchResolution err(String error) { return new PatchResolution(null, error); }
    }

    /**
     * Resolve + validate paths and enforce the skill-creator read-only guard for every op,
     * building the ResolvedOp list the application phase needs. Bails on the first error.
     */
    private static PatchResolution resolvePatchOps(List<FileOp> ops, Agent agent, Path workspace) {
        var resolved = new ArrayList<ResolvedOp>();
        for (var op : ops) {
            Path target;
            try {
                target = AgentService.acquireWorkspacePath(agent.name, op.path());
            } catch (SecurityException e) {
                return PatchResolution.err(ERROR_PREFIX_COLON + e.getMessage());
            }
            var guardError = checkSkillCreatorReadOnly(agent, workspace, target);
            if (guardError != null) return PatchResolution.err(guardError);

            // Capture the Optional once so the isPresent check and the get share
            // a single reference — addresses SonarQube S3655, which can't prove
            // u.newPath() is referentially transparent across two calls.
            var newPathOpt = (op instanceof FileOp.Update u) ? u.newPath() : java.util.Optional.<String>empty();
            Path moveTarget = null;
            if (newPathOpt.isPresent()) {
                try {
                    moveTarget = AgentService.acquireWorkspacePath(agent.name, newPathOpt.get());
                } catch (SecurityException e) {
                    return PatchResolution.err(ERROR_PREFIX_COLON + e.getMessage());
                }
                var moveGuard = checkSkillCreatorReadOnly(agent, workspace, moveTarget);
                if (moveGuard != null) return PatchResolution.err(moveGuard);
            }
            resolved.add(new ResolvedOp(op, target, moveTarget));
        }
        return PatchResolution.ok(resolved);
    }

    /**
     * Acquire the file locks for every target/moveTarget in {@code resolved} in a global
     * lexicographic order (deadlock-free across concurrent applyPatch calls on overlapping
     * file sets) and run {@code block} inside that critical section.
     */
    private static String runUnderFileLocks(List<ResolvedOp> resolved, Supplier<String> block) {
        var lockKeys = new LinkedHashSet<String>();
        for (var r : resolved) {
            lockKeys.add(lockKey(r.target));
            if (r.moveTarget != null) lockKeys.add(lockKey(r.moveTarget));
        }
        var sortedKeys = new ArrayList<>(lockKeys);
        Collections.sort(sortedKeys);

        // Resolve all locks first (allocation only, nothing acquired yet) so a
        // late OOM during ArrayList growth can't leave a held lock outside the
        // finally's reach. Then acquire in order, tracking how many we grabbed.
        // The finally only releases the prefix we actually own — addresses
        // SonarQube S2222 (lock-not-unlocked-on-all-paths) by removing the
        // window between lock() and locks.add() the previous shape had.
        var locks = new ArrayList<ReentrantLock>(sortedKeys.size());
        for (var key : sortedKeys) {
            locks.add(FILE_LOCKS.computeIfAbsent(key, k -> new ReentrantLock()));
        }
        int acquired = 0;
        try {
            while (acquired < locks.size()) {
                locks.get(acquired).lock();
                acquired++;
            }
            return block.get();
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }

    private String applyPatchLocked(List<ResolvedOp> resolved) {
        // === Phase 1: validate every op and compute the post-patch content for Add/Update ops. ===
        var plans = new ArrayList<OpPlan>();
        for (int i = 0; i < resolved.size(); i++) {
            var planned = planOp(resolved.get(i), i + 1);
            if (planned.error != null) return planned.error;
            plans.add(planned.plan);
        }

        // === Phase 2: apply. On IO failure mid-application, roll back successful writes. ===
        var committed = new ArrayList<CommittedOp>();
        try {
            for (var plan : plans) {
                var err = applyPlannedOp(plan, committed);
                if (err != null) {
                    rollback(committed);
                    return err;
                }
            }
        } catch (RuntimeException rt) {
            rollback(committed);
            throw rt;
        }

        return summarizeCommittedOps(committed);
    }

    private record PlannedOp(OpPlan plan, String error) {
        static PlannedOp ok(OpPlan plan) { return new PlannedOp(plan, null); }
        static PlannedOp err(String error) { return new PlannedOp(null, error); }
    }

    /**
     * Validate a single resolved op and compute its post-patch content (for Add/Update) or
     * snapshot of pre-edit content (for Update/Delete, used by rollback on IO error).
     */
    private PlannedOp planOp(ResolvedOp r, int opIndex) {
        return switch (r.op) {
            case FileOp.Add(var path, var content) -> {
                if (Files.exists(r.target)) {
                    yield PlannedOp.err("Error: op #%d Add File '%s' failed — file already exists".formatted(opIndex, path));
                }
                yield PlannedOp.ok(new OpPlan(r, content, null));
            }
            case FileOp.Delete(var path) -> {
                if (!Files.exists(r.target)) {
                    yield PlannedOp.err("Error: op #%d Delete File '%s' failed — file does not exist".formatted(opIndex, path));
                }
                try {
                    yield PlannedOp.ok(new OpPlan(r, null, Files.readString(r.target)));
                } catch (IOException e) {
                    yield PlannedOp.err("Error: op #%d Delete File '%s' snapshot failed — %s".formatted(opIndex, path, e.getMessage()));
                }
            }
            case FileOp.Update upd -> {
                if (!Files.exists(r.target)) {
                    yield PlannedOp.err("Error: op #%d Update File '%s' failed — file does not exist".formatted(opIndex, upd.path()));
                }
                String snapshot;
                try {
                    snapshot = Files.readString(r.target);
                } catch (IOException e) {
                    yield PlannedOp.err("Error: op #%d Update File '%s' read failed — %s".formatted(opIndex, upd.path(), e.getMessage()));
                }
                var applied = applyUpdateChunks(snapshot, upd.chunks(), upd.path(), opIndex);
                if (applied.error != null) yield PlannedOp.err(applied.error);
                yield PlannedOp.ok(new OpPlan(r, applied.result, snapshot));
            }
        };
    }

    /**
     * Apply one validated plan, appending CommittedOp markers used for rollback.
     * Returns a non-null error message if this op failed; callers must rollback before returning.
     */
    private String applyPlannedOp(OpPlan plan, List<CommittedOp> committed) {
        var r = plan.resolved;
        return switch (r.op) {
            case FileOp.Add add -> applyAddOp(r, plan, committed, add.path());
            case FileOp.Delete(var path) -> applyDeleteOp(r, plan, committed, path);
            case FileOp.Update upd -> (r.moveTarget != null)
                    ? applyUpdateMoveOp(r, plan, committed, upd)
                    : applyUpdateInPlaceOp(r, plan, committed, upd);
        };
    }

    private String applyAddOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, String path) {
        var result = writeFile(r.target, plan.newContent);
        if (result.startsWith(ERROR_PREFIX)) {
            return "Error applying Add File '%s': %s".formatted(path, result);
        }
        committed.add(new CommittedOp.Added(r.target));
        return null;
    }

    private static String applyDeleteOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, String path) {
        try {
            Files.deleteIfExists(r.target);
            committed.add(new CommittedOp.Deleted(r.target, plan.preSnapshot));
            return null;
        } catch (IOException e) {
            return "Error applying Delete File '%s': %s".formatted(path, e.getMessage());
        }
    }

    private String applyUpdateInPlaceOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, FileOp.Update upd) {
        var result = writeFile(r.target, plan.newContent);
        if (result.startsWith(ERROR_PREFIX)) {
            return "Error applying Update File '%s': %s".formatted(upd.path(), result);
        }
        committed.add(new CommittedOp.Updated(r.target, plan.preSnapshot));
        return null;
    }

    private String applyUpdateMoveOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, FileOp.Update upd) {
        // Write edited content to new path, then remove original.
        var writeResult = writeFile(r.moveTarget, plan.newContent);
        if (writeResult.startsWith(ERROR_PREFIX)) {
            return "Error applying Update+Move '%s'→'%s': %s"
                    .formatted(upd.path(), upd.newPath().orElse(""), writeResult);
        }
        committed.add(new CommittedOp.Added(r.moveTarget));
        try {
            Files.deleteIfExists(r.target);
            committed.add(new CommittedOp.Deleted(r.target, plan.preSnapshot));
            return null;
        } catch (IOException e) {
            return "Error applying Update+Move '%s'→'%s': %s"
                    .formatted(upd.path(), upd.newPath().orElse(""), e.getMessage());
        }
    }

    private static String summarizeCommittedOps(List<CommittedOp> committed) {
        int added = 0;
        int updated = 0;
        int deleted = 0;
        for (var c : committed) {
            switch (c) {
                case CommittedOp.Added _ -> added++;
                case CommittedOp.Updated _ -> updated++;
                case CommittedOp.Deleted _ -> deleted++;
            }
        }
        var paths = committed.stream().map(c -> c.path().getFileName().toString()).toList();
        return "Applied patch: %d added, %d updated, %d deleted (files: %s)"
                .formatted(added, updated, deleted, String.join(", ", paths));
    }

    private static void rollback(List<CommittedOp> committed) {
        // Replay in reverse, restoring pre-edit state best-effort. We don't surface rollback
        // errors to the caller — the primary failure already did — but we do try to avoid
        // leaving partial writes behind.
        for (int i = committed.size() - 1; i >= 0; i--) {
            var c = committed.get(i);
            try {
                switch (c) {
                    case CommittedOp.Added(var path) -> Files.deleteIfExists(path);
                    case CommittedOp.Updated(var path, var preSnapshot) -> Files.writeString(path, preSnapshot);
                    case CommittedOp.Deleted(var path, var preSnapshot) -> Files.writeString(path, preSnapshot);
                }
            } catch (IOException _) {
                // swallow — best effort.
            }
        }
    }

    /**
     * Apply a list of patch chunks to the current file content. Each chunk's non-`+` lines
     * (context + remove) form an oldText block that must appear at least once in the file;
     * if an @@ anchor is present, the search is restricted to the region after the anchor.
     * Returns the new file content or an error.
     */
    private EditResult applyUpdateChunks(String original, List<PatchChunk> chunks, String path, int opIndex) {
        var working = original;
        for (int c = 0; c < chunks.size(); c++) {
            var applied = applySingleChunk(working, chunks.get(c), path, opIndex, c + 1);
            if (applied.error != null) return applied;
            working = applied.result;
        }
        return EditResult.ok(working);
    }

    /** Apply one chunk to {@code working}, returning the new content or an error. */
    private EditResult applySingleChunk(String working, PatchChunk chunk, String path, int opIndex, int chunkIndex) {
        var oldBlock = new StringBuilder();
        var newBlock = new StringBuilder();
        for (var line : chunk.lines()) {
            appendChunkLine(line, oldBlock, newBlock);
        }
        var oldText = oldBlock.toString();
        var newText = newBlock.toString();
        if (oldText.isEmpty()) {
            return EditResult.err(("Error: op #%d Update File '%s' chunk #%d has no removal or context lines — "
                    + "a chunk must include at least one '-' or ' ' line to anchor the edit.")
                    .formatted(opIndex, path, chunkIndex));
        }

        var anchorOpt = chunk.anchor();
        int searchStart = 0;
        if (anchorOpt.isPresent() && !anchorOpt.get().isEmpty()) {
            var anchor = anchorOpt.get();
            var anchorIdx = working.indexOf(anchor);
            if (anchorIdx < 0) {
                return EditResult.err(("Error: op #%d Update File '%s' chunk #%d anchor '%s' not found in file")
                        .formatted(opIndex, path, chunkIndex, anchor));
            }
            searchStart = anchorIdx;
        }

        var hit = working.indexOf(oldText, searchStart);
        if (hit < 0) {
            return EditResult.err(("Error: op #%d Update File '%s' chunk #%d context did not match the current file content. "
                    + "Regenerate the chunk against the latest file state.").formatted(opIndex, path, chunkIndex));
        }
        // For non-anchored chunks, require uniqueness to avoid accidental mis-apply.
        if (anchorOpt.isEmpty()) {
            var second = working.indexOf(oldText, hit + oldText.length());
            if (second >= 0) {
                return EditResult.err(("Error: op #%d Update File '%s' chunk #%d context is not unique. "
                        + "Add an @@ anchor @@ line or include more context.").formatted(opIndex, path, chunkIndex));
            }
        }
        return EditResult.ok(working.substring(0, hit) + newText + working.substring(hit + oldText.length()));
    }

    private static void appendChunkLine(PatchLine line, StringBuilder oldBlock, StringBuilder newBlock) {
        switch (line) {
            case PatchLine.ContextLine(var text) -> {
                oldBlock.append(text).append('\n');
                newBlock.append(text).append('\n');
            }
            case PatchLine.RemoveLine(var text) -> oldBlock.append(text).append('\n');
            case PatchLine.AddLine(var text) -> newBlock.append(text).append('\n');
        }
    }

    // === Patch model ===

    sealed interface FileOp permits FileOp.Add, FileOp.Update, FileOp.Delete {
        String path();
        record Add(String path, String content) implements FileOp {}
        record Update(String path, Optional<String> newPath, List<PatchChunk> chunks) implements FileOp {}
        record Delete(String path) implements FileOp {}
    }

    record PatchChunk(Optional<String> anchor, List<PatchLine> lines) {}
    sealed interface PatchLine {
        String text();
        record ContextLine(String text) implements PatchLine {}
        record AddLine(String text) implements PatchLine {}
        record RemoveLine(String text) implements PatchLine {}
    }

    private record ResolvedOp(FileOp op, Path target, Path moveTarget) {}
    private record OpPlan(ResolvedOp resolved, String newContent, String preSnapshot) {}

    private sealed interface CommittedOp {
        Path path();
        record Added(Path path) implements CommittedOp {}
        record Updated(Path path, String preSnapshot) implements CommittedOp {}
        record Deleted(Path path, String preSnapshot) implements CommittedOp {}
    }

    // === Patch parser ===

    private static final class PatchParseException extends RuntimeException {
        final int line;
        PatchParseException(String message, int line) { super(message); this.line = line; }
    }

    private static final class PatchParser {
        private static final String BEGIN_PATCH = "*** Begin Patch";
        private static final String END_PATCH = "*** End Patch";
        private static final String END_OF_FILE = "*** End of File";
        private static final String ADD_FILE_PREFIX = "*** Add File:";
        private static final String DELETE_FILE_PREFIX = "*** Delete File:";
        private static final String UPDATE_FILE_PREFIX = "*** Update File:";
        private static final String MOVE_TO_PREFIX = "*** Move to:";

        static List<FileOp> parse(String body) {
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
    }

    // === Locking ===

    /**
     * Per-file reentrant locks keyed on the canonical absolute-normalized path. Ensures
     * that two concurrent tool calls on the same file serialize instead of clobbering
     * each other. The map grows monotonically with unique files seen across the JVM
     * lifetime; documented and accepted — workspace file counts are small (hundreds,
     * maybe low thousands).
     */
    private static final ConcurrentMap<String, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    private static String lockKey(Path target) {
        return target.toAbsolutePath().normalize().toString();
    }

    private String withLock(Path target, Supplier<String> block) {
        var lock = FILE_LOCKS.computeIfAbsent(lockKey(target), k -> new ReentrantLock());
        lock.lock();
        try {
            return block.get();
        } finally {
            lock.unlock();
        }
    }
}
