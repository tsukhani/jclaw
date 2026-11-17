package mcp;

import models.McpServer;
import org.jspecify.annotations.Nullable;
import services.WorkspaceFiles;
import tools.HarnessSandbox;
import utils.SandboxHiddenRoots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Every stdio MCP server starts behind the Main Agent's fence (JF-396): JClaw's own folders hidden, its program files
 * read-only. The operator and the Main Agent both add servers, so an unfenced server would be the Main Agent's route
 * around its shell's fence (JF-325). Two operator-only settings loosen it for one server: {@link McpServer#runUnfenced},
 * for a server whose program cannot run inside a sandbox (on macOS, Chrome's own sandbox will not start inside
 * Seatbelt, measured on chrome-devtools), and {@link McpServer#fenceReadable}, the files in JClaw's folders it may read.
 */
public final class McpServerFence {

    /** The file every stored credential is encrypted under: no server reads it, whoever lists it. */
    private static final String KEY_FILE = ".env";
    private static final String CERTS = "certs";

    private McpServerFence() {}

    /**
     * {@code argv} as the server's process is started: behind the fence with the server's read list, unless the
     * operator runs it without the fence.
     *
     * @throws HarnessSandbox.SandboxUnavailableException when no sandbox can start here; the server is then not started
     */
    public static List<String> argv(McpServer server, List<String> argv) {
        if (server.runUnfenced) return argv;
        try {
            return HarnessSandbox.wrapMcpServer(argv, WorkspaceFiles.workspaceRoot().toFile(), readableFor(server));
        } catch (HarnessSandbox.SandboxUnavailableException e) {
            throw new HarnessSandbox.SandboxUnavailableException(("The fence every STDIO server starts behind cannot start"
                    + " here: %s. JClaw starts this server only behind it, unless the operator switches Run without the"
                    + " fence on for it.").formatted(e.getMessage()));
        }
    }

    /**
     * The server's listed files the fence opens, canonical: each still in one of JClaw's hidden folders, the key file
     * never. Checked again at every start, so a listed file since replaced by a link opens nothing it should not.
     */
    public static List<Path> readableFor(McpServer server) {
        var readable = new ArrayList<Path>();
        for (var listed : server.fenceReadableFiles()) {
            var canonical = SandboxHiddenRoots.canonical(listed);
            if (!isKeyFile(canonical) && inHiddenFolder(canonical)) readable.add(canonical);
        }
        return List.copyOf(readable);
    }

    /**
     * Why a request may not change {@code server}'s fence settings to {@code unfenced} and {@code readable}, or null
     * when it may: only the operator loosens or tightens the fence, so an {@code agentOriginated} change is refused,
     * the Main Agent's included. An unchanged echo, or a field left out (null), changes nothing.
     */
    public static @Nullable String changeRefusal(boolean agentOriginated, McpServer server, @Nullable Boolean unfenced,
                                                 @Nullable List<String> readable) {
        boolean changes = unfenced != null && unfenced != server.runUnfenced
                || readable != null && !readable.equals(server.fenceReadableFiles());
        if (!changes || !agentOriginated) return null;
        return "runUnfenced and fenceReadable are operator-only: they loosen the fence every stdio server starts behind,"
                + " the same fence the Main Agent's own commands run behind.";
    }

    /**
     * Why a request may not change how {@code server} starts, its transport, command, arguments or environment, or null
     * when it may. While the server runs without the fence or may read files in JClaw's own folders, any of them could
     * run code that copies those files out, and a Google service account's key acts as any user in its domain.
     */
    public static @Nullable String startChangeRefusal(boolean agentOriginated, McpServer server) {
        if (!agentOriginated || !server.runUnfenced && server.fenceReadableFiles().isEmpty()) return null;
        return ("MCP server '%s' %s, so only the operator may change its transport, command, arguments or environment:"
                + " any of them could run code that reads what the fence keeps from the Main Agent.")
                .formatted(server.name, server.runUnfenced ? "runs without the fence" : "may read files in JClaw's own folders");
    }

    /** {@code paths} as stored: stripped, blank ones left out, each once, in the order given. */
    public static List<String> normalize(List<String> paths) {
        var out = new LinkedHashSet<String>();
        for (var p : paths) {
            if (p != null && !p.isBlank()) out.add(p.strip());
        }
        return List.copyOf(out);
    }

    /** Why {@code path} cannot be on a server's read list, or null when it can. */
    public static @Nullable String readableRejection(String path) {
        var p = Path.of(path);
        if (!p.isAbsolute()) return "'%s' is not an absolute path: give the file's full path.".formatted(path);
        var canonical = SandboxHiddenRoots.canonical(p);
        if (isKeyFile(canonical)) {
            return ("'%s' holds the key every stored credential is encrypted under, so no MCP server may read it."
                    .formatted(path));
        }
        if (!inHiddenFolder(canonical)) {
            return ("'%s' is not in one of JClaw's own folders (data/, certs/, logs/ or the kept skill files), which"
                    + " every stdio server can already read: leave it off the list.").formatted(path);
        }
        if (!Files.isRegularFile(canonical)) return "'%s' is not a file: list each file the server reads.".formatted(path);
        return null;
    }

    private static boolean inHiddenFolder(Path canonical) {
        return HarnessSandbox.MainFence.current(WorkspaceFiles.workspaceRoot().toFile()).hidden().stream()
                .anyMatch(canonical::startsWith);
    }

    private static boolean isKeyFile(Path canonical) {
        var parent = canonical.getParent();
        return canonical.getFileName() != null && canonical.getFileName().toString().equals(KEY_FILE)
                && parent != null && parent.getFileName() != null && parent.getFileName().toString().equals(CERTS)
                && SandboxHiddenRoots.current().contains(parent);
    }
}
