package utils;

import com.google.gson.JsonObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The tool-surface error templates (JCLAW-1132): one factory per class of tool failure,
 * plus the two renderings a tool result needs.
 *
 * <p>Separate from {@link ErrorTemplates} because that registry maps a fixed code to fixed
 * prose, and a tool failure is never fixed prose — it carries the command, the path, the URL
 * or the exit status that makes it actionable. So these are parameterized factories rather
 * than table rows, and a <em>code</em> is a class of failure sharing one remedy, not one
 * message: {@code fs_edit_rejected} covers every malformed entry in an {@code editFile}
 * batch, because "re-send the batch with the entry corrected" is the answer to all of them.
 *
 * <p>The audience is the model, not a person. A tool error the model cannot act on is one it
 * retries verbatim, so every template here names what to check; {@code howToRetry} is null
 * only where retrying is genuinely wrong.
 */
public final class ToolErrorTemplates {

    private ToolErrorTemplates() {}

    // === codes ===
    public static final String SHELL_EMPTY_COMMAND = "shell_empty_command";
    public static final String SHELL_NOT_ALLOWED = "shell_not_allowed";
    public static final String SHELL_WORKDIR_REFUSED = "shell_workdir_refused";
    public static final String SHELL_SPAWN_FAILED = "shell_spawn_failed";
    public static final String SHELL_INTERRUPTED = "shell_interrupted";
    public static final String SHELL_EXIT_NONZERO = "shell_exit_nonzero";
    public static final String SHELL_TIMED_OUT = "shell_timed_out";

    public static final String FS_PATH_MISSING = "fs_path_missing";
    public static final String FS_PATH_REFUSED = "fs_path_refused";
    public static final String FS_READ_ONLY = "fs_read_only";
    public static final String FS_NOT_FOUND = "fs_not_found";
    public static final String FS_NOT_A_DIRECTORY = "fs_not_a_directory";
    public static final String FS_TOO_LARGE = "fs_too_large";
    public static final String FS_IO_FAILURE = "fs_io_failure";
    public static final String FS_UNKNOWN_ACTION = "fs_unknown_action";
    public static final String FS_APPEND_UNSUPPORTED = "fs_append_unsupported";
    public static final String FS_EDIT_REJECTED = "fs_edit_rejected";
    public static final String FS_LINE_OP_REJECTED = "fs_line_op_rejected";
    public static final String FS_PATCH_REJECTED = "fs_patch_rejected";

    public static final String WEB_HOST_UNRESOLVED = "web_host_unresolved";
    public static final String WEB_HOST_UNREACHABLE = "web_host_unreachable";
    public static final String WEB_TIMED_OUT = "web_timed_out";
    public static final String WEB_TLS_FAILED = "web_tls_failed";
    public static final String WEB_BLOCKED = "web_blocked";
    public static final String WEB_FETCH_FAILED = "web_fetch_failed";
    public static final String WEB_BAD_URL = "web_bad_url";

    public static final String MCP_BAD_ARGUMENTS = "mcp_bad_arguments";
    public static final String MCP_NOT_ALLOWED = "mcp_not_allowed";
    public static final String MCP_CONNECTION_FAILED = "mcp_connection_failed";
    public static final String MCP_PROTOCOL_FAILED = "mcp_protocol_failed";
    public static final String MCP_TOOL_REPORTED_ERROR = "mcp_tool_reported_error";

    // === rendering ===

    /**
     * The text the model reads.
     *
     * <p>The head is {@code "Error: "} rather than {@link ErrorRendering}'s
     * {@code "What broke: "} because {@code ToolResultVerifier.looksLikeError} classifies a
     * tool failure by that prefix — rendering the shared way would leave every converted
     * failure scoring a clean pass (JCLAW-836).
     */
    public static String render(@NonNull ErrorTemplate template) {
        var out = new StringBuilder("Error: ").append(template.whatBroke())
                .append("\n\nWhat to check: ").append(template.whatToCheck());
        if (template.hasRetry()) {
            out.append("\n\nHow to retry: ").append(template.howToRetry());
        }
        return out.toString();
    }

    /**
     * The same failure as a JSON object, for {@code ToolResult.structuredJson} — which
     * already persists to {@code message.tool_result_structured}, so carrying it there
     * needs no schema change and a transcript recorded before this simply has null.
     */
    public static String structuredJson(@NonNull ErrorTemplate template) {
        var envelope = new JsonObject();
        envelope.add("error", asJsonObject(template));
        return envelope.toString();
    }

    /** The error object on its own, for a tool whose result text is already a JSON envelope. */
    public static JsonObject asJsonObject(@NonNull ErrorTemplate template) {
        var error = new JsonObject();
        error.addProperty("code", template.code());
        error.addProperty("whatBroke", template.whatBroke());
        error.addProperty("whatToCheck", template.whatToCheck());
        if (template.hasRetry()) error.addProperty("howToRetry", template.howToRetry());
        return error;
    }

    // === shell ===

    public static ErrorTemplate shellEmptyCommand() {
        return new ErrorTemplate(SHELL_EMPTY_COMMAND,
                "command is required and must not be empty.",
                "The 'command' argument was absent, null or whitespace only.",
                "Re-send the call with the shell command line in 'command'.");
    }

    public static ErrorTemplate shellNotAllowed(String firstToken, String allowed) {
        return new ErrorTemplate(SHELL_NOT_ALLOWED,
                "Command '%s' is not in the allowed commands list. Allowed: %s".formatted(firstToken, allowed),
                "The allowlist matches the first token of the command line, by full path or basename. "
                        + "Only the operator can widen it.",
                "Use one of the allowed commands, or ask the operator to add '%s'.".formatted(firstToken));
    }

    public static ErrorTemplate shellWorkdirRefused(String detail) {
        return new ErrorTemplate(SHELL_WORKDIR_REFUSED, detail,
                "'workdir' is resolved inside the agent workspace; an absolute path or a symlink "
                        + "leading outside it is refused.",
                "Re-send with a workdir relative to the workspace, or omit it to run at the workspace root.");
    }

    public static ErrorTemplate shellSpawnFailed(String command, String detail) {
        return new ErrorTemplate(SHELL_SPAWN_FAILED,
                "The shell could not be started for `%s`: %s".formatted(command, detail),
                "This is a failure to launch the process at all, not a failure of the command — "
                        + "an unreadable working directory or an exhausted process table look like this.",
                "Retry once. If it fails the same way, the host cannot run commands right now; stop and report it.");
    }

    public static ErrorTemplate shellInterrupted(String command) {
        return new ErrorTemplate(SHELL_INTERRUPTED,
                "Execution of `%s` was interrupted before it finished.".formatted(command),
                "The turn was cancelled or the server is shutting down; the command's own state is unknown.",
                "Check whether the command had already taken effect before running it again.");
    }

    public static ErrorTemplate shellExitNonZero(String command, int exitCode) {
        return new ErrorTemplate(SHELL_EXIT_NONZERO,
                "`%s` exited with status %d.".formatted(command, exitCode),
                exitCheckAdvice(exitCode),
                "Read the 'output' field for the command's own diagnosis, fix what it names, then re-run.");
    }

    public static ErrorTemplate shellTimedOut(String command, int timeoutSeconds) {
        return new ErrorTemplate(SHELL_TIMED_OUT,
                "`%s` was killed after %d seconds and did not finish.".formatted(command, timeoutSeconds),
                "'output' holds whatever it printed before the kill. A command that waits on input "
                        + "never finishes here — nothing is attached to its stdin.",
                "Re-run with a larger 'timeout', or narrow the command so it completes inside one.");
    }

    /** Exit statuses whose meaning is fixed by POSIX shells; everything else is the command's own. */
    private static String exitCheckAdvice(int exitCode) {
        return switch (exitCode) {
            case 1 -> "Status 1 is the general failure code; what failed is in the command's own output.";
            case 2 -> "Status 2 usually means the arguments were wrong, not that the work failed.";
            case 126 -> "Status 126 means the file was found but is not executable.";
            case 127 -> "Status 127 means the command was not found on PATH.";
            case 130 -> "Status 130 means the process was interrupted.";
            default -> exitCode > 128
                    ? "A status above 128 means a signal killed the process (signal %d)."
                            .formatted(exitCode - 128)
                    : "The status is defined by the command itself; its output says what it means.";
        };
    }

    // === filesystem ===

    public static ErrorTemplate fsPathMissing(String action) {
        return new ErrorTemplate(FS_PATH_MISSING,
                "action '%s' requires a 'path' field".formatted(action),
                "Every filesystem action except applyPatch takes a workspace-relative 'path'.",
                "Re-send the call with 'path' set.");
    }

    /** {@code detail} is a guard's own message, which for a {@code SecurityException} can be absent. */
    public static ErrorTemplate fsPathRefused(@Nullable String detail) {
        return new ErrorTemplate(FS_PATH_REFUSED,
                detail == null || detail.isBlank() ? "The path is outside the agent workspace." : detail,
                "Paths are resolved inside the agent workspace; '..', an absolute path, or a symlink "
                        + "leading outside it is refused.",
                "Re-send with a path relative to the workspace root.");
    }

    public static ErrorTemplate fsReadOnly(String agentName) {
        return new ErrorTemplate(FS_READ_ONLY,
                "The 'skill-creator' skill is read-only for agent '%s'.".formatted(agentName),
                "Only the 'main' agent may modify skill-creator. Other agents can use it to author "
                        + "other skills, but not alter it. To get an updated skill-creator, ask the user "
                        + "to drag it from the global skills registry onto this agent's card.",
                null);
    }

    public static ErrorTemplate fsNotFound(String name) {
        return new ErrorTemplate(FS_NOT_FOUND,
                "File not found: %s".formatted(name),
                "The path is resolved relative to the workspace root, not to any earlier call's directory.",
                "List the directory with the listFiles action to find the real name, then retry.");
    }

    public static ErrorTemplate fsNotADirectory(String name) {
        return new ErrorTemplate(FS_NOT_A_DIRECTORY,
                "Not a directory: %s".formatted(name),
                "listFiles takes a directory; this path is a regular file or does not exist.",
                "Use the readFile action for a file, or listFiles on its parent directory.");
    }

    public static ErrorTemplate fsTooLarge(String whatBroke) {
        return new ErrorTemplate(FS_TOO_LARGE, whatBroke,
                "The cap bounds one tool result, not the file — the file itself is intact.",
                "Use the 'documents' tool's readDocument action for rich formats, or replace the file "
                        + "wholesale with writeFile instead of editing it in place.");
    }

    public static ErrorTemplate fsIoFailure(String whatBroke) {
        return new ErrorTemplate(FS_IO_FAILURE, whatBroke,
                "The path resolved but the operating system refused the operation — a directory where "
                        + "a file was expected, a permission bit, or a full disk.",
                "Check the path names a regular file, then retry once.");
    }

    public static ErrorTemplate fsUnknownAction(String action, String validActions) {
        return new ErrorTemplate(FS_UNKNOWN_ACTION,
                "Unknown action '%s'. Valid actions: %s".formatted(action, validActions),
                "The action list is closed; a name outside it is never accepted, however plausible.",
                "Re-send with one of the listed actions.");
    }

    public static ErrorTemplate fsAppendUnsupported() {
        return new ErrorTemplate(FS_APPEND_UNSUPPORTED,
                "appendFile is not supported for SKILL.md files.",
                "A skill definition's version is bumped deterministically on write, and an append "
                        + "is ambiguous against that.",
                "Use writeFile for a full replacement (version bumps are handled automatically), or "
                        + "editFile to patch specific sections.");
    }

    /**
     * Anything that makes an {@code editFile} batch unusable — a malformed entry, an empty
     * or ambiguous {@code oldText}, a bad regex. One code because the remedy is one thing:
     * correct the entry and re-send. Nothing was written; the file is untouched.
     */
    public static ErrorTemplate fsEditRejected(String whatBroke) {
        return new ErrorTemplate(FS_EDIT_REJECTED, whatBroke,
                "Edits apply atomically, so nothing was written and the file is unchanged. Each "
                        + "oldText must match exactly once against the file as the earlier edits left it.",
                "Re-read the file, then re-send the whole batch with the entry corrected and enough "
                        + "surrounding context to make each oldText unique.");
    }

    /** The {@code editLines} equivalent of {@link #fsEditRejected}. */
    public static ErrorTemplate fsLineOpRejected(String whatBroke) {
        return new ErrorTemplate(FS_LINE_OP_REJECTED, whatBroke,
                "Operations validate before any is applied, so nothing was written. Line numbers are "
                        + "1-indexed, endLine is inclusive, and every operation addresses the file's "
                        + "ORIGINAL coordinates rather than the state the previous ones would leave.",
                "Re-read the file to get current line numbers, then re-send the whole batch corrected.");
    }

    /** The {@code applyPatch} equivalent of {@link #fsEditRejected}. */
    public static ErrorTemplate fsPatchRejected(String whatBroke) {
        return new ErrorTemplate(FS_PATCH_REJECTED, whatBroke,
                "The whole patch is validated before any file is touched, so nothing was written. The "
                        + "body must sit between *** Begin Patch and *** End Patch, and context lines "
                        + "must match the file exactly.",
                "Re-read the files the patch touches, then re-send the corrected patch — or make the "
                        + "same change with editFile, which needs no patch grammar.");
    }

    // === web ===

    public static ErrorTemplate webBadUrl(String detail) {
        return new ErrorTemplate(WEB_BAD_URL,
                "could not parse url: %s".formatted(detail),
                "The 'url' argument is not a URL — a bare hostname or a search phrase looks like this.",
                "Re-send with an absolute URL including its scheme, e.g. https://example.com/page.");
    }

    /**
     * DNS said no such host. Split from {@link #webHostUnreachable} and {@link #webTimedOut}
     * because the three remedies differ: a name to correct, a service to wait on, a request
     * to make smaller.
     */
    public static ErrorTemplate webHostUnresolved(String url, String detail) {
        return new ErrorTemplate(WEB_HOST_UNRESOLVED,
                "The host in %s could not be resolved: %s".formatted(url, detail),
                "DNS has no record for that name — a typo, a host that no longer exists, or an "
                        + "internal name this machine cannot see.",
                "Check the spelling of the host. Do not retry the same URL; it will fail identically.");
    }

    public static ErrorTemplate webHostUnreachable(String url, String detail) {
        return new ErrorTemplate(WEB_HOST_UNREACHABLE,
                "The host serving %s resolved but refused or dropped the connection: %s".formatted(url, detail),
                "The name is right and the network reached it — nothing is listening on that port, or a "
                        + "firewall closed the connection. Unlike a timeout, the answer came back immediately.",
                "Check the scheme and port. Retrying helps only if the service is restarting; a longer "
                        + "timeout cannot.");
    }

    public static ErrorTemplate webTimedOut(String url, int seconds) {
        return new ErrorTemplate(WEB_TIMED_OUT,
                "Request timed out after %d seconds fetching %s".formatted(seconds, url),
                "The host accepted the connection and then did not answer in time — it is slow or "
                        + "overloaded, not absent. A host that was absent would have failed at once.",
                "Retry once; if it times out again, fetch a smaller or more specific page on that host.");
    }

    public static ErrorTemplate webTlsFailed(String url, String detail) {
        return new ErrorTemplate(WEB_TLS_FAILED,
                ("SSL/TLS certificate verification failed for %s: %s. The site may have an expired, "
                        + "self-signed, or invalid certificate.").formatted(url, detail),
                "Verification is not negotiable here, so this URL cannot be fetched over https as it stands.",
                "Try the site's canonical hostname, which often has a valid certificate for the same content.");
    }

    public static ErrorTemplate webBlocked(String detail) {
        return new ErrorTemplate(WEB_BLOCKED,
                "URL rejected by SSRF guard: %s".formatted(detail),
                "A deliberate refusal, not a network failure: the scheme or the address is one this "
                        + "instance never fetches, such as a private or loopback address.",
                null);
    }

    public static ErrorTemplate webFetchFailed(String url, String detail) {
        return new ErrorTemplate(WEB_FETCH_FAILED,
                "Fetching %s failed: %s".formatted(url, detail),
                "The request reached the transport and came back with an error the ladder could not "
                        + "get past — commonly a 4xx or 5xx from the site itself.",
                "Try a different URL on the same site, or a search to find another source for the content.");
    }

    // --- MCP (JCLAW-1132 follow-up) ---
    // The AC asks an MCP failure to say whether it was connection, protocol or tool-level.
    // That distinction already exists on the wire and was being collapsed: the invoker declares
    // `throws IOException, McpException`, where IOException is transport and McpException is a
    // JSON-RPC error, a contract violation or a timeout. These factories keep them apart, and
    // every one names the server — an operator running several cannot act on "an MCP tool failed".

    public static ErrorTemplate mcpConnectionFailed(String server, String tool, String detail) {
        return new ErrorTemplate(MCP_CONNECTION_FAILED,
                "Could not reach MCP server '%s' to run `%s`: %s".formatted(server, tool, detail),
                ("The failure was at the transport, so the server never answered — it may be "
                        + "stopped, still starting, or listening somewhere other than its configured "
                        + "address. Settings → MCP Servers → %s shows its connection state.")
                        .formatted(server),
                "Reconnect '%s' from Settings, then run the tool again.".formatted(server));
    }

    public static ErrorTemplate mcpProtocolFailed(String server, String tool, String detail) {
        return new ErrorTemplate(MCP_PROTOCOL_FAILED,
                "MCP server '%s' answered `%s` with a protocol error: %s".formatted(server, tool, detail),
                ("The server is reachable and replied, so this is not a connection problem — it "
                        + "returned a JSON-RPC error, broke the protocol contract, or took longer "
                        + "than the request timeout. A version mismatch between the server and its "
                        + "declared tool schema produces this too."),
                "Retry once in case it was a timeout; if it repeats, the server's own log is the "
                        + "thing to read.");
    }

    public static ErrorTemplate mcpToolReportedError(String server, String tool, String detail) {
        return new ErrorTemplate(MCP_TOOL_REPORTED_ERROR,
                "`%s` on MCP server '%s' ran and reported a failure: %s".formatted(tool, server, detail),
                "The call reached the tool and the tool rejected it, so the server and the "
                        + "connection are both fine — the argument values are what to look at.",
                "Correct the arguments and call it again.");
    }

    public static ErrorTemplate mcpNotAllowed(String server, String tool, String agent) {
        return new ErrorTemplate(MCP_NOT_ALLOWED,
                "`%s` on MCP server '%s' is not on the allowlist for agent '%s'."
                        .formatted(tool, server, agent),
                "A per-agent grant, not a connection problem: the server can be connected and its "
                        + "tools still ungranted, which is the default for a newly connected server.",
                "Grant the agent this server's tools in the agent editor, then call it again.");
    }

    public static ErrorTemplate mcpBadArguments(String server, String tool, String detail) {
        return new ErrorTemplate(MCP_BAD_ARGUMENTS,
                "The arguments for `%s` on MCP server '%s' were not valid JSON: %s"
                        .formatted(tool, server, detail),
                "Nothing reached the server — the call was rejected here, before the wire.",
                "Re-send the call with a JSON object for the arguments.");
    }

    /**
     * Nothing static to register. A tool failure's template is parameterised by the call that
     * produced it — the command, the exit status, the path, the URL — so the rows above are
     * factories rather than table entries, and {@link ErrorTemplates#forCode} falls back for
     * these codes by design. The method exists so this file still satisfies the registry's
     * one-file-per-surface contract (JCLAW-60) and stays visible to its merge.
     */
    static Map<String, ErrorTemplate> templates() {
        return Map.of();
    }
}
