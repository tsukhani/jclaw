import agents.ToolRegistry;
import com.google.gson.JsonObject;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ChromeBridgePolicy;
import services.ConfigService;
import tools.ChromeUploadTool;
import utils.SsrfGuard;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.google.gson.JsonParser.parseString;

class ChromeUploadToolTest extends UnitTest {

    private static final Map<String, String> HOSTS = Map.of("example.com", "93.184.216.34", "evil.com", "1.1.1.1");

    private final ChromeUploadTool tool = new ChromeUploadTool();
    private Agent agent;
    private Path workspace;

    @BeforeEach
    void saveAgent() {
        agent = AgentService.create("chrome-up-" + UUID.randomUUID().toString().substring(0, 8), "openrouter", "gpt-4.1");
        workspace = AgentService.workspacePath(agent.name);
        ConfigService.set(ChromeBridgePolicy.key(agent.name, ChromeBridgePolicy.ALLOWED_SITES), "example.com");
    }

    @AfterEach
    void cleanup() {
        AgentService.delete(agent);
        ConfigService.delete(ChromeBridgePolicy.key("main", ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL));
    }

    private static JsonObject ok(String result) {
        return ChromeBridgeSync.ok(parseString(result));
    }

    private ChromeBridgeSync.Fake onAllowedTab() {
        return new ChromeBridgeSync.Fake(msg -> msg.get("action").getAsString().equals("tab_info")
                ? ok("{\"url\":\"https://example.com/\"}") : ok("{\"done\":\"uploaded\"}"));
    }

    private String run(String path) {
        var args = "{\"tabId\":7,\"ref\":\"e5\",\"path\":\"" + path + "\"}";
        return SsrfGuard.callWithHostsForTest(HOSTS, () -> tool.execute(args, agent));
    }

    @Test
    void aWorkspaceFileIsAttachedByItsAbsolutePath() throws Exception {
        Files.writeString(workspace.resolve("report.txt"), "hello");
        var fake = onAllowedTab();
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Attached report.txt to e5.", run("report.txt"));

            var sent = fake.sent.getLast();
            assertEquals("upload", sent.get("action").getAsString());
            assertEquals("e5", sent.get("ref").getAsString());
            assertEquals(workspace.resolve("report.txt").toAbsolutePath().normalize().toString(), sent.get("path").getAsString());
        });
    }

    @Test
    void aFileInASubfolderWorksToo() throws Exception {
        Files.createDirectories(workspace.resolve("out"));
        Files.writeString(workspace.resolve("out/a.csv"), "1,2");
        ChromeBridgeSync.with(onAllowedTab(), () -> assertEquals("Attached a.csv to e5.", run("out/a.csv")));
    }

    @Test
    void aPathThatEscapesTheWorkspaceIsRefusedAndNothingIsSent() throws Exception {
        var fake = onAllowedTab();
        ChromeBridgeSync.with(fake, () -> {
            for (var path : List.of("../outside.txt", "../../etc/hosts", "/etc/hosts", "out/../../x")) {
                assertTrue(run(path).startsWith("Error: "), path);
                assertTrue(fake.sent.isEmpty(), path);
            }
        });
    }

    @Test
    void aSymlinkOutOfTheWorkspaceIsRefused() throws Exception {
        var outside = Files.createTempFile("jclaw-outside-", ".txt");
        try {
            Files.writeString(outside, "secret");
            Files.createSymbolicLink(workspace.resolve("link.txt"), outside);
            var fake = onAllowedTab();
            ChromeBridgeSync.with(fake, () -> {
                assertTrue(run("link.txt").startsWith("Error: "));
                assertTrue(fake.sent.isEmpty());
            });
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void personaFilesAtTheWorkspaceRootAreNeverUploaded() throws Exception {
        Files.writeString(workspace.resolve("SOUL.md"), "private");
        var fake = onAllowedTab();
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Error: persona files are never uploaded.", run("SOUL.md"));
            assertTrue(fake.sent.isEmpty());
        });
    }

    @Test
    void aMissingFileOrADirectoryIsNotAFile() throws Exception {
        Files.createDirectories(workspace.resolve("folder"));
        var fake = onAllowedTab();
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Error: that is not a file in your workspace.", run("missing.txt"));
            assertEquals("Error: that is not a file in your workspace.", run("folder"));
            assertTrue(fake.sent.isEmpty());
        });
    }

    @Test
    void aFileOverTwentyFiveMegabytesIsRefused() throws Exception {
        try (var f = new RandomAccessFile(workspace.resolve("big.bin").toFile(), "rw")) {
            f.setLength(25L * 1024 * 1024 + 1);
        }
        var fake = onAllowedTab();
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Error: the file is larger than 25 MB.", run("big.bin"));
            assertTrue(fake.sent.isEmpty());
        });
    }

    @Test
    void aTabOffTheAllowlistIsRefusedBeforeAnythingIsAttached() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "x");
        var fake = new ChromeBridgeSync.Fake(_ -> ok("{\"url\":\"https://evil.com/\"}"));
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Error: site not on the allowlist: evil.com", run("a.txt"));
            assertEquals(List.of("tab_info"), fake.actions());
        });
    }

    @Test
    void missingArgumentsAndBadJsonAreNamed() throws Exception {
        ChromeBridgeSync.with(ChromeBridgeSync.Fake.silent(), () -> {
            assertEquals("Error: upload needs a tabId, a ref and a path.", tool.execute("{\"tabId\":7}", agent));
            assertEquals("Error: the arguments were not valid JSON.", tool.execute("nope", agent));
        });
    }

    @Test
    void anUploadWaitsForApprovalUnlessTheMainAgentIsExempt() {
        var args = "{\"tabId\":7,\"ref\":\"e5\",\"path\":\"a.txt\"}";
        assertTrue(tool.dangerous(args, agent));

        ConfigService.set(ChromeBridgePolicy.key("main", ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL), "true");
        var main = new Agent();
        main.name = "main";
        assertFalse(tool.dangerous(args, main));
    }

    @Test
    void theRegistryRefusesItUnaskedAndFencesItToOperatorTurns() throws Exception {
        ToolRegistrySync.withCanonicalTools(() -> {
            assertNotNull(ToolRegistry.lookupTool("chrome_upload"));
            assertTrue(ToolRegistry.neverRunsUnasked("chrome_upload"));
            assertTrue(ToolRegistry.turnDisabled(agent, null, false).contains("chrome_upload"));
            assertTrue(ToolRegistry.loadDisabledTools(agent).contains("chrome_upload"));
            assertTrue(ToolRegistry.loadDisabledTools(agent).contains("chrome_script"));
        });
    }

    @Test
    void aFileInASkillsCredentialsFolderIsNeverUploadedByAnyRoute() throws Exception {
        Files.createDirectories(workspace.resolve("skills/demo/credentials"));
        Files.writeString(workspace.resolve("skills/demo/credentials/key.txt"), "secret");
        Files.createSymbolicLink(workspace.resolve("key-link.txt"), workspace.resolve("skills/demo/credentials/key.txt"));
        var fake = onAllowedTab();
        ChromeBridgeSync.with(fake, () -> {
            for (var path : List.of("skills/demo/credentials/key.txt", "SKILLS/demo/Credentials/key.txt",
                    "skills/demo/../demo/credentials/key.txt", "key-link.txt")) {
                assertEquals("Error: '" + path + "' is in a skill's credentials folder, which no agent tool opens.",
                        run(path));
                assertTrue(fake.sent.isEmpty(), path);
            }
        });
    }
}
