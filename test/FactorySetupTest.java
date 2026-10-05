import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.factory.FactoryHome;
import services.factory.FactoryProcess;
import services.factory.FactorySetup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link FactorySetup}'s prerequisite mapping and credential writes, and {@link FactoryHome#writeEnvFile} (JCLAW-1393). */
class FactorySetupTest extends UnitTest {

    @TempDir
    Path home;

    private final Map<String, FactoryProcess.ExecResult> replies = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        FactoryRunnerSync.acquire();
        FactoryHome.setHomeForTest(home);
        FactoryProcess.setRunnerForTest((List<String> command, File dir, Duration timeout) -> {
            var joined = String.join(" ", command);
            return replies.entrySet().stream().filter(e -> joined.startsWith(e.getKey())).findFirst()
                    .map(Map.Entry::getValue).orElse(new FactoryProcess.ExecResult(0, "", false));
        });
    }

    @AfterEach
    void clearSeams() {
        FactoryHome.setHomeForTest(null);
        FactoryProcess.setRunnerForTest(null);
        FactoryRunnerSync.release();
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static String mode(Path file) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    // --- writeEnvFile ---

    @Test
    void writeEnvFileKeepsUnrelatedLinesReplacesInPlaceAppendsAndRemoves() throws Exception {
        var file = home.resolve("x.env");
        Files.writeString(file, "# comment\nFOO=1\nA=old\nB=gone\nBAR=2\n");
        FactoryHome.writeEnvFile(file, Map.of("A", "new", "C", "added"), Set.of("B"));
        assertEquals("# comment\nFOO=1\nA=new\nBAR=2\nC=added\n", Files.readString(file));
        try (var siblings = Files.list(home)) {
            assertEquals(List.of(file), siblings.toList(), "a temp file was left behind");
        }
    }

    @Test
    void writeEnvFileIsOwnerOnlyEvenOverALooserFile() throws Exception {
        if (!posix()) return;
        var file = home.resolve("x.env");
        Files.writeString(file, "A=1\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        FactoryHome.writeEnvFile(file, Map.of("A", "2"), Set.of());
        assertEquals("rw-------", mode(file));
        var fresh = home.resolve("y.env");
        FactoryHome.writeEnvFile(fresh, Map.of("A", "2"), Set.of());
        assertEquals("rw-------", mode(fresh));
    }

    @Test
    void writeEnvFileCreatesAMissingHomeOwnerOnly() throws Exception {
        var file = home.resolve("nested/.env");
        FactoryHome.writeEnvFile(file, Map.of("A", "1"), Set.of());
        assertEquals("A=1\n", Files.readString(file));
        if (posix()) assertEquals("rwx------", mode(file.getParent()));
    }

    // --- prerequisites ---

    private Map<String, FactorySetup.Prerequisite> prerequisites(String osName) {
        var out = new LinkedHashMap<String, FactorySetup.Prerequisite>();
        for (var p : FactorySetup.view(osName, null).prerequisites()) out.put(p.id(), p);
        return out;
    }

    private static void assertState(String state, FactorySetup.Prerequisite p) {
        assertEquals(state, p.state(), p::toString);
        if (state.equals(FactorySetup.OK)) assertEquals("", p.fix(), p::toString);
        else assertFalse(p.fix().isBlank(), p::toString);
    }

    @Test
    void allPrerequisitesOk() {
        replies.put("node", new FactoryProcess.ExecResult(0, "v24.3.0\n", false));
        var p = prerequisites("Mac OS X");
        assertEquals(List.of("macos", "docker", "node", "checkout"), List.copyOf(p.keySet()));
        assertState(FactorySetup.OK, p.get("macos"));
        assertState(FactorySetup.OK, p.get("docker"));
        assertState(FactorySetup.OK, p.get("node"));
        assertState(Files.isRegularFile(FactoryHome.installer()) ? FactorySetup.OK : FactorySetup.MISSING,
                p.get("checkout"));
    }

    @Test
    void prerequisiteFailuresMapToMissingOrUnknown() {
        assertState(FactorySetup.MISSING, prerequisites("Linux").get("macos"));

        replies.put("docker", new FactoryProcess.ExecResult(-1, "no such file", false));
        replies.put("node", new FactoryProcess.ExecResult(-1, "no such file", false));
        assertState(FactorySetup.MISSING, prerequisites("Mac OS X").get("docker"));
        assertState(FactorySetup.MISSING, prerequisites("Mac OS X").get("node"));

        replies.put("docker", new FactoryProcess.ExecResult(1, "Cannot connect", false));
        replies.put("node", new FactoryProcess.ExecResult(0, "v22.1.0\n", false));
        var p = prerequisites("Mac OS X");
        assertState(FactorySetup.MISSING, p.get("docker"));
        assertTrue(p.get("docker").fix().contains("Start Docker Desktop"), p.get("docker")::toString);
        assertState(FactorySetup.MISSING, p.get("node"));

        replies.put("docker", new FactoryProcess.ExecResult(-1, "", true));
        replies.put("node", new FactoryProcess.ExecResult(0, "garbage\n", false));
        p = prerequisites("Mac OS X");
        assertState(FactorySetup.UNKNOWN, p.get("docker"));
        assertState(FactorySetup.UNKNOWN, p.get("node"));
    }

    @Test
    void aProbeThatThrowsIsUnknown() {
        FactoryProcess.setRunnerForTest((List<String> c, File d, Duration t) -> {
            throw new IllegalStateException("boom");
        });
        var p = prerequisites("Mac OS X");
        assertState(FactorySetup.UNKNOWN, p.get("docker"));
        assertState(FactorySetup.UNKNOWN, p.get("node"));
    }

    @Test
    void nodeBoundary() {
        for (var c : List.of(List.of("v24.0.0", FactorySetup.OK), List.of("v25.1.0", FactorySetup.OK),
                List.of("v23.9.9", FactorySetup.MISSING))) {
            replies.put("node", new FactoryProcess.ExecResult(0, c.get(0) + "\n", false));
            assertEquals(c.get(1), prerequisites("Mac OS X").get("node").state(), c.get(0));
        }
    }

    @Test
    void theOsCheckAcceptsMacOsNames() {
        assertState(FactorySetup.OK, prerequisites("Mac OS X").get("macos"));
        assertState(FactorySetup.OK, prerequisites("macOS").get("macos"));
    }

    // --- credentials ---

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void valuesAreStrippedAndKeepEqualsHashAndInnerSpaces() throws Exception {
        assertNull(FactorySetup.applyCredentials(json("{\"claudeOauthToken\":\"  tok=a#b c \\t\"}")));
        assertEquals("CLAUDE_CODE_OAUTH_TOKEN=tok=a#b c\n", Files.readString(home.resolve(".env")));
        assertTrue(FactorySetup.view("Mac OS X", null).hasModelCredential());
    }

    @Test
    void aModelCredentialReplacesTheOtherKindAndKeepsOtherLines() throws Exception {
        Files.writeString(home.resolve(".env"), "FOO=1\nANTHROPIC_API_KEY=old\n");
        assertNull(FactorySetup.applyCredentials(json("{\"claudeOauthToken\":\"tok=a#b\"}")));
        assertEquals("FOO=1\nCLAUDE_CODE_OAUTH_TOKEN=tok=a#b\n", Files.readString(home.resolve(".env")));
        assertNull(FactorySetup.applyCredentials(json("{\"anthropicApiKey\":\"k\"}")));
        assertEquals("FOO=1\nANTHROPIC_API_KEY=k\n", Files.readString(home.resolve(".env")));
    }

    @Test
    void jiraFieldsMaySeparatelyBeSetAndBothAreNeededForHasJira() throws Exception {
        assertNull(FactorySetup.applyCredentials(json("{\"jiraUrl\":\"https://jira.example.com\"}")));
        assertFalse(FactorySetup.view("Mac OS X", null).hasJira());
        assertNull(FactorySetup.applyCredentials(json("{\"jiraPersonalToken\":\"p\"}")));
        assertEquals("JIRA_URL=https://jira.example.com\nJIRA_PERSONAL_TOKEN=p\n",
                Files.readString(home.resolve("jira.env")));
        assertTrue(FactorySetup.view("Mac OS X", null).hasJira());
        assertNull(FactorySetup.applyCredentials(json("{\"githubToken\":\"g\"}")));
        assertEquals("GITHUB_TOKEN=g\n", Files.readString(home.resolve("github.env")));
        assertTrue(FactorySetup.view("Mac OS X", null).hasGithub());
    }

    @Test
    void invalidBodiesAreRefusedNamingTheFieldAndWriteNothing() throws Exception {
        Files.writeString(home.resolve(".env"), "ANTHROPIC_API_KEY=keep\n");
        var before = Files.readAllBytes(home.resolve(".env"));
        var secret = "s3cr3t-value";
        for (var body : List.of(
                "{\"anthropicApiKey\":\"" + secret + "\\nX=1\"}",
                "{\"anthropicApiKey\":\"" + secret + "\\rX=1\"}",
                "{\"anthropicApiKey\":\"   \"}",
                "{\"anthropicApiKey\":\"" + secret + "\",\"claudeOauthToken\":\"" + secret + "\"}",
                "{\"githubToken\":\"" + secret + "\",\"nope\":\"" + secret + "\"}",
                "{\"githubToken\":5}",
                "{\"githubToken\":null}",
                "{}",
                "{\"githubToken\":\"" + secret + "\",\"jiraUrl\":\"ftp://" + secret + "\"}")) {
            var error = FactorySetup.applyCredentials(json(body));
            assertNotNull(error, body);
            assertFalse(error.contains(secret), error);
            assertArrayEquals(before, Files.readAllBytes(home.resolve(".env")), body);
            assertFalse(Files.exists(home.resolve("github.env")), body);
            assertFalse(Files.exists(home.resolve("jira.env")), body);
        }
        assertEquals("ANTHROPIC_API_KEY=keep\n", new String(before, StandardCharsets.UTF_8));
    }
}
