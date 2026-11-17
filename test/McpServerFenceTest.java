import mcp.McpServerFence;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.KeptSkillFiles;
import services.WorkspaceFiles;
import tools.HarnessSandbox;
import utils.SandboxHiddenRoots;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JF-396: every stdio MCP server starts behind the Main Agent's fence. Only the operator lists the files in JClaw's
 * own folders a server may read, never the key file, or lets a server run without the fence, and while either holds
 * only the operator changes how that server starts. Where no sandbox starts, the server does not start.
 */
class McpServerFenceTest extends UnitTest {

    private Path folder;
    private Path hidden;

    @BeforeEach
    void setUp() throws IOException {
        ShellSandboxSync.acquire();
        folder = KeptSkillFiles.root().resolve("jf396-" + UUID.randomUUID());
        Files.createDirectories(folder);
        hidden = folder.resolve("service-account.json");
        Files.writeString(hidden, "{\"type\":\"fake-jf396\"}\n");
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            services.SkillPromotionService.deleteRecursive(folder);
        } finally {
            ShellSandboxSync.release();
        }
    }

    private static McpServer server(String name, boolean unfenced, String... readable) {
        var row = new McpServer();
        row.name = name;
        row.transport = McpServer.Transport.STDIO;
        row.runUnfenced = unfenced;
        row.fenceReadable = readable.length == 0 ? null : String.join("\n", readable);
        return row;
    }

    private static Path keyFile() {
        return Play.applicationPath.toPath().resolve("certs/.env");
    }

    // ===== what a read list may hold =====

    @Test
    void aFileTheFenceHidesCanBeListed() {
        assertNull(McpServerFence.readableRejection(hidden.toString()));
    }

    @Test
    void aRelativePathIsRefused() {
        assertEquals("'tmp/key.json' is not an absolute path: give the file's full path.",
                McpServerFence.readableRejection("tmp/key.json"));
    }

    @Test
    void aFileOutsideJclawsOwnFoldersIsRefusedAsNeedless() throws IOException {
        var outside = Files.createTempFile("jf396-outside-", ".json");
        try {
            assertEquals(("'%s' is not in one of JClaw's own folders (data/, certs/, logs/ or the kept skill files), which"
                    + " every stdio server can already read: leave it off the list.").formatted(outside),
                    McpServerFence.readableRejection(outside.toString()));
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void theKeyFileIsRefusedWhoeverListsIt() {
        assertEquals(("'%s' holds the key every stored credential is encrypted under, so no MCP server may read it.")
                .formatted(keyFile()), McpServerFence.readableRejection(keyFile().toString()));
    }

    @Test
    void aFolderIsRefusedForTheFilesInIt() {
        assertEquals("'%s' is not a file: list each file the server reads.".formatted(folder),
                McpServerFence.readableRejection(folder.toString()));
    }

    @Test
    void aListIsStoredStrippedAndEachPathOnce() {
        assertEquals(List.of("/a/key.json", "/b/key.json"),
                McpServerFence.normalize(List.of(" /a/key.json ", "", "/a/key.json", "/b/key.json", "  ")));
    }

    // ===== who may change it =====

    @Test
    void onlyTheOperatorChangesAServersFenceSettings() {
        var refused = "runUnfenced and fenceReadable are operator-only: they loosen the fence every stdio server starts"
                + " behind, the same fence the Main Agent's own commands run behind.";
        var plain = server("plain", false);
        assertEquals(refused, McpServerFence.changeRefusal(true, plain, true, null), "the Main Agent switching it off");
        assertEquals(refused, McpServerFence.changeRefusal(true, plain, null, List.of(hidden.toString())));
        var loosened = server("loosened", true, hidden.toString());
        assertEquals(refused, McpServerFence.changeRefusal(true, loosened, false, null), "tightening is the operator's too");
        assertEquals(refused, McpServerFence.changeRefusal(true, loosened, null, List.of()));

        assertNull(McpServerFence.changeRefusal(true, loosened, true, List.of(hidden.toString())), "an unchanged echo");
        assertNull(McpServerFence.changeRefusal(true, plain, null, null), "a body without either field");
        assertNull(McpServerFence.changeRefusal(false, plain, true, List.of(hidden.toString())), "the operator");
    }

    @Test
    void onlyTheOperatorChangesHowALoosenedServerStarts() {
        assertEquals("MCP server 'chrome-devtools' runs without the fence, so only the operator may change its transport,"
                + " command, arguments or environment: any of them could run code that reads what the fence keeps from"
                + " the Main Agent.", McpServerFence.startChangeRefusal(true, server("chrome-devtools", true)));
        assertEquals("MCP server 'workspace' may read files in JClaw's own folders, so only the operator may change its"
                + " transport, command, arguments or environment: any of them could run code that reads what the fence"
                + " keeps from the Main Agent.",
                McpServerFence.startChangeRefusal(true, server("workspace", false, hidden.toString())));
        assertNull(McpServerFence.startChangeRefusal(true, server("plain", false)), "a fenced server with no list");
        assertNull(McpServerFence.startChangeRefusal(false, server("workspace", true, hidden.toString())), "the operator");
    }

    // ===== how it starts =====

    @Test
    void theFenceOpensOnlyListedFilesStillInsideItAndNeverTheKeyFile() throws IOException {
        var outside = Files.createTempFile("jf396-outside-", ".json");
        try {
            var row = server("listed", false, hidden.toString(), keyFile().toString(), outside.toString());
            assertEquals(List.of(SandboxHiddenRoots.canonical(hidden)), McpServerFence.readableFor(row),
                    "a key file or outside path stored some other way is dropped at every start");
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void aServerTheOperatorRunsUnfencedStartsAsItIs() {
        HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.unavailable("no sandbox (test)"));
        var argv = List.of("npx", "-y", "chrome-devtools-mcp");
        assertEquals(argv, McpServerFence.argv(server("chrome-devtools", true), argv));
    }

    @Test
    void whereNoSandboxStartsAFencedServerDoesNotStart() {
        HarnessSandbox.setCustomAgentOverrideForTest(HarnessSandbox.CustomAgentOverride.unavailable("no sandbox (test)"));
        var thrown = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                () -> McpServerFence.argv(server("peekaboo", false), List.of("npx", "-y", "@steipete/peekaboo")));
        assertEquals("The fence every STDIO server starts behind cannot start here: no sandbox (test). JClaw starts this"
                + " server only behind it, unless the operator switches Run without the fence on for it.", thrown.getMessage());
    }

    private static HarnessSandbox.MainFence sampleFence() {
        return new HarnessSandbox.MainFence(
                List.of(Path.of("/srv/jclaw"), Path.of("/opt/play1"), Path.of("/home/op/.gradle")),
                Path.of("/srv/jclaw/workspace"),
                List.of(Path.of("/srv/jclaw/data"), Path.of("/srv/jclaw/certs"), Path.of("/srv/jclaw/logs")),
                List.of(Path.of("/srv/jclaw/data/backups")), 4242L);
    }

    @Test
    void theMacProfileOpensAListedFileForReadingAfterItsFolderIsHidden() {
        assertEquals("""
                (version 1)
                (allow default)
                (deny file-write* (subpath "/srv/jclaw"))
                (deny file-write* (subpath "/opt/play1"))
                (deny file-write* (subpath "/home/op/.gradle"))
                (allow file-write* (subpath "/srv/jclaw/workspace"))
                (deny file-read* file-write* (subpath "/srv/jclaw/data"))
                (deny file-read* file-write* (subpath "/srv/jclaw/certs"))
                (deny file-read* file-write* (subpath "/srv/jclaw/logs"))
                (allow file-read* (subpath "/srv/jclaw/data/backups"))
                (allow file-read* (subpath "/srv/jclaw/certs/google.json"))
                (allow process-exec (literal "/bin/ps") (with no-sandbox))
                (allow process-exec (literal "/usr/bin/top") (with no-sandbox))
                (deny network-outbound file-write* file-link (regex #"/\\.java_pid4242$"))
                """, HarnessSandbox.mainFenceMacosProfile(sampleFence().withReadable(List.of(Path.of("/srv/jclaw/certs/google.json")))));
    }

    @Test
    void theLinuxArgvBindsAListedFileBackOverItsFoldersTmpfs() {
        var argv = HarnessSandbox.mainFenceLinuxArgv(List.of("uvx", "workspace-mcp"),
                sampleFence().withReadable(List.of(Path.of("/srv/jclaw/certs/google.json"))), false);
        var tmpfs = argv.indexOf("/srv/jclaw/certs");
        var bound = argv.lastIndexOf("/srv/jclaw/certs/google.json");
        assertEquals("--tmpfs", argv.get(tmpfs - 1));
        assertEquals(List.of("--ro-bind", "/srv/jclaw/certs/google.json", "/srv/jclaw/certs/google.json", "uvx", "workspace-mcp"),
                argv.subList(bound - 2, argv.size()), "bound read-only, after the tmpfs that hides its folder: " + argv);
    }

    /** On this host, a real fenced server: a hidden file is refused unless listed, and the key file even when listed. */
    @Test
    void aFencedServerReadsAHiddenFileOnlyWhenListedAndNeverTheKeyFile() throws Exception {
        var availability = HarnessSandbox.availability();
        assumeTrue(availability.available(), "no working sandbox here: " + availability.reason());
        assertEquals("refused", reads(hidden, List.of()));
        assertEquals("read", reads(hidden, McpServerFence.readableFor(server("listed", false, hidden.toString()))));
        assertEquals("read", reads(hidden, List.of(), "control, with no fence at all", false));
        assertEquals("refused", reads(keyFile(), McpServerFence.readableFor(server("keyed", false, keyFile().toString()))));
    }

    /** Whether {@code file} reads under the fence with {@code readable} opened; its content is never printed. */
    private static String reads(Path file, List<Path> readable) throws Exception {
        return reads(file, readable, "fenced", true);
    }

    private static String reads(Path file, List<Path> readable, String label, boolean fenced) throws Exception {
        var command = List.of("/bin/sh", "-c", "cat \"$0\" > /dev/null 2>&1 && echo read || echo refused", file.toString());
        var argv = fenced ? HarnessSandbox.wrapMcpServer(command, WorkspaceFiles.workspaceRoot().toFile(), readable)
                : command;
        var process = new ProcessBuilder(new ArrayList<>(argv)).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), label + ": " + out);
        return out;
    }
}
