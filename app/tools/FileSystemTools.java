package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class FileSystemTools implements ToolRegistry.Tool {

    // === Action names ===
    private static final String ACTION_READ_FILE = "readFile";
    static final String ACTION_WRITE_FILE = "writeFile";
    static final String ACTION_APPEND_FILE = "appendFile";
    static final String ACTION_EDIT_FILE = "editFile";
    static final String ACTION_EDIT_LINES = "editLines";
    private static final String ACTION_APPLY_PATCH = "applyPatch";
    private static final String ACTION_LIST_FILES = "listFiles";

    // === Argument keys (JSON field names on tool calls) ===
    private static final String ARG_ACTION = "action";
    static final String ARG_CONTENT = "content";
    private static final String ARG_EDITS = "edits";
    private static final String ARG_OPERATIONS = "operations";
    static final String ARG_OLD_TEXT = "oldText";
    static final String ARG_NEW_TEXT = "newText";
    static final String ARG_REGEX = "regex";
    static final String ARG_START_LINE = "startLine";
    static final String ARG_END_LINE = "endLine";
    private static final String ARG_PATCH = "patch";

    // === Line-op names ===
    static final String OP_REPLACE = "replace";
    static final String OP_DELETE = "delete";
    static final String OP_INSERT = "insert";

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
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_READ_FILE,   "Read file content up to 1 MB"),
                new ToolAction(ACTION_WRITE_FILE,  "Create or overwrite a file with full content"),
                new ToolAction(ACTION_APPEND_FILE, "Append content to the end of a file, creating it if missing"),
                new ToolAction(ACTION_EDIT_FILE,   "Apply a batch of oldText → newText replacements atomically"),
                new ToolAction(ACTION_EDIT_LINES,  "Replace, insert, or delete specific 1-indexed line ranges atomically"),
                new ToolAction(ACTION_APPLY_PATCH, "Apply a multi-file unified diff patch"),
                new ToolAction(ACTION_LIST_FILES,  "List the contents of a directory")
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
            return FsPatchApplier.applyPatch(agent, patch);
        }

        var resolved = FsPaths.resolveTargetPath(args, agent, action);
        if (resolved.error() != null) return resolved.error();

        if (FsPaths.isMutatingAction(action)) {
            var guardError = FsPaths.checkSkillCreatorReadOnly(agent, resolved.workspace(), resolved.target());
            if (guardError != null) return guardError;
        }

        return dispatchAction(action, args, agent, resolved.target());
    }

    private String dispatchAction(String action, JsonObject args, Agent agent, Path target) {
        return switch (action) {
            case ACTION_READ_FILE -> FsReader.readFile(target);
            case ACTION_LIST_FILES -> FsReader.listFiles(target);
            case ACTION_WRITE_FILE -> FsLocks.withLock(target, () -> FsWriter.writeFile(target, stringArg(args, ARG_CONTENT)));
            case ACTION_APPEND_FILE -> FsLocks.withLock(target, () -> FsWriter.appendFile(target, stringArg(args, ARG_CONTENT)));
            case ACTION_EDIT_FILE -> FsLocks.withLock(target, () -> {
                if (!args.has(ARG_EDITS) || !args.get(ARG_EDITS).isJsonArray()) {
                    return "Error: editFile requires an 'edits' array";
                }
                return FsTextEditor.editFile(target, args.getAsJsonArray(ARG_EDITS));
            });
            case ACTION_EDIT_LINES -> FsLocks.withLock(target, () -> {
                if (!args.has(ARG_OPERATIONS) || !args.get(ARG_OPERATIONS).isJsonArray()) {
                    return "Error: editLines requires an 'operations' array";
                }
                return FsLineEditor.editLines(agent, target, args.getAsJsonArray(ARG_OPERATIONS));
            });
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private static String stringArg(JsonObject args, String name) {
        return args.has(name) ? args.get(name).getAsString() : "";
    }

}
