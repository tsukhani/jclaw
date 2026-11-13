import com.google.gson.JsonObject;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.WorkspaceFiles;
import services.database.DatabaseService;
import tools.FileSystemTools;
import utils.SandboxHiddenRoots;
import utils.ToolErrorTemplates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * JF-394: with its allowGlobalPaths toggle on, the Main Agent's file tool takes an absolute or ~/ path outside its
 * workspace and reaches what its shell's fence lets it reach: JClaw's own folders stay closed, the backup folder is
 * readable only, the program files are read-only, and every other agent keeps its workspace alone.
 */
class FileToolGlobalPathsTest extends UnitTest {

    private static final String KEY = "agent.main.shell.allowGlobalPaths";
    private static final String CUSTOM = "jf394-custom";

    private final FileSystemTools tool = new FileSystemTools();
    private final List<Path> cleanup = new ArrayList<>();
    private Agent main;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        ShellSandboxSync.acquire();
        main = new Agent();
        main.name = "main";
        outside = remember(SandboxHiddenRoots.canonical(Files.createTempDirectory("jf394-outside-")));
        ConfigService.set(KEY, "true");
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            ConfigService.delete(KEY);
            ConfigService.delete("agent." + CUSTOM + ".shell.allowGlobalPaths");
            for (var p : cleanup) deleteTree(p);
        } finally {
            ShellSandboxSync.release();
        }
    }

    @Test
    void withTheToggleOnMainWritesReadsEditsAndPatchesAFileOutsideItsWorkspace() throws IOException {
        var file = outside.resolve("notes.md");
        assertTrue(run(main, action("writeFile", file.toString(), "one\n")).startsWith("File written successfully"));
        assertTrue(run(main, action("appendFile", file.toString(), "two\n")).startsWith("Appended 4 chars to "),
                Files.readString(file));
        var edit = new JsonObject();
        edit.addProperty("action", "editFile");
        edit.addProperty("path", file.toString());
        var edits = new com.google.gson.JsonArray();
        var one = new JsonObject();
        one.addProperty("oldText", "two");
        one.addProperty("newText", "three");
        edits.add(one);
        edit.add("edits", edits);
        run(main, edit.toString());
        assertEquals("one\nthree\n", Files.readString(file));
        assertEquals("one\nthree\n", run(main, action("readFile", file.toString(), null)));

        var patched = outside.resolve("patched.md");
        run(main, patch("*** Begin Patch\n*** Add File: " + patched + "\n+added\n*** End of File\n*** End Patch\n"));
        assertEquals("added", Files.readString(patched));
    }

    @Test
    void aHomeRelativePathResolvesUnderTheHomeFolder() throws IOException {
        var name = "jclaw-jf394-" + UUID.randomUUID();
        var home = remember(Path.of(System.getProperty("user.home"), name));
        assertTrue(run(main, action("writeFile", "~/" + name + "/a.txt", "home")).startsWith("File written successfully"));
        assertEquals("home", Files.readString(home.resolve("a.txt")));
    }

    @Test
    void withTheToggleOffMainIsRefusedAnAbsolutePathAsBefore() {
        ConfigService.set(KEY, "false");
        var file = outside.resolve("off.md");
        var refused = run(main, action("writeFile", file.toString(), "x"));
        assertTrue(refused.startsWith("Error: "), refused);
        assertEquals(ToolErrorTemplates.FS_PATH_REFUSED, errorCode(main, action("writeFile", file.toString(), "x")));
        assertFalse(Files.exists(file));
    }

    @Test
    void aCustomAgentIsRefusedAnAbsolutePathWhateverItsOwnKeySays() {
        ConfigService.set("agent." + CUSTOM + ".shell.allowGlobalPaths", "true");
        var custom = new Agent();
        custom.name = CUSTOM;
        var file = outside.resolve("custom.md");
        assertEquals(ToolErrorTemplates.FS_PATH_REFUSED, errorCode(custom, action("writeFile", file.toString(), "x")));
        assertFalse(Files.exists(file));
    }

    @Test
    void jclawsOwnFoldersStayClosedForReadingAndWritingThroughAnyPath() throws IOException {
        for (var folder : List.of("data", "certs", "logs")) {
            var dir = Play.applicationPath.toPath().resolve(folder);
            var created = !Files.isDirectory(dir);
            Files.createDirectories(dir);
            if (created) remember(dir);
            var probe = remember(SandboxHiddenRoots.canonical(dir.resolve("jf394-probe-" + UUID.randomUUID() + ".txt")));
            Files.writeString(probe, "probe");
            var path = probe.toString();
            var refusal = ToolErrorTemplates.render(ToolErrorTemplates.fsJclawFolder(path));
            assertEquals(refusal, run(main, action("readFile", path, null)), folder);
            assertEquals(refusal, run(main, action("writeFile", path, "changed")), folder);
            var link = outside.resolve(folder + "-link.txt");
            Files.createSymbolicLink(link, probe);
            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsJclawFolder(link.toString())),
                    run(main, action("readFile", link.toString(), null)), folder + " through a link");
            assertEquals("probe", Files.readString(probe), folder);
        }
    }

    @Test
    void theBackupFolderIsReadableButNotWritable() throws IOException {
        var backups = DatabaseService.backupsDir();
        var created = !Files.isDirectory(backups);
        Files.createDirectories(backups);
        if (created) remember(backups);
        var probe = remember(SandboxHiddenRoots.canonical(backups.resolve("jf394-probe-" + UUID.randomUUID() + ".txt")));
        Files.writeString(probe, "backup");
        assertEquals("backup", run(main, action("readFile", probe.toString(), null)));
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsJclawFolder(probe.toString())),
                run(main, action("writeFile", probe.toString(), "changed")));
        assertEquals("backup", Files.readString(probe));
    }

    @Test
    void programFilesAreReadOnlyWhileTheWorkspacesInsideThemStayWritable() throws IOException {
        var conf = SandboxHiddenRoots.canonical(Play.applicationPath.toPath().resolve("conf/application.conf"));
        assertEquals(Files.readString(conf), run(main, action("readFile", conf.toString(), null)));
        var planted = remember(conf.resolveSibling("jf394-planted.txt"));
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsProgramFiles(planted.toString())),
                run(main, action("writeFile", planted.toString(), "x")));
        assertFalse(Files.exists(planted));

        var other = remember(SandboxHiddenRoots.canonical(AgentService.workspacePath(CUSTOM)));
        var note = other.resolve("note.md");
        assertTrue(run(main, action("writeFile", note.toString(), "from main")).startsWith("File written successfully"));
        assertEquals("from main", Files.readString(note));
        assertTrue(note.startsWith(SandboxHiddenRoots.canonical(WorkspaceFiles.workspaceRoot())));
    }

    @Test
    void anotherAgentsSkillCredentialsStayClosed() throws IOException {
        var other = remember(SandboxHiddenRoots.canonical(AgentService.workspacePath(CUSTOM)));
        var key = Files.createDirectories(other.resolve("skills/probe/credentials")).resolve("key.txt");
        Files.writeString(key, "kept");
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsCredentials(key.toString())),
                run(main, action("readFile", key.toString(), null)));
    }

    @Test
    void anAbsolutePathIntoItsOwnWorkspaceKeepsTheWorkspaceRules() throws IOException {
        var own = SandboxHiddenRoots.canonical(AgentService.workspacePath("main"));
        var skill = remember(own.resolve("skills/jf394-probe-" + UUID.randomUUID()));
        var key = Files.createDirectories(skill.resolve("credentials")).resolve("key.txt");
        Files.writeString(key, "kept");
        var relative = own.relativize(key).toString();
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsCredentials(key.toString())),
                run(main, action("readFile", key.toString(), null)));
        assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsCredentials(relative)),
                run(main, action("readFile", relative, null)));
    }

    private String run(Agent agent, String argsJson) {
        return tool.execute(argsJson, agent);
    }

    private String errorCode(Agent agent, String argsJson) {
        var structured = tool.executeRich(argsJson, agent).structuredJson();
        return com.google.gson.JsonParser.parseString(structured).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private static String action(String action, String path, String content) {
        var json = new JsonObject();
        json.addProperty("action", action);
        json.addProperty("path", path);
        if (content != null) json.addProperty("content", content);
        return json.toString();
    }

    private static String patch(String body) {
        var json = new JsonObject();
        json.addProperty("action", "applyPatch");
        json.addProperty("patch", body);
        return json.toString();
    }

    private Path remember(Path path) {
        cleanup.add(path);
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
