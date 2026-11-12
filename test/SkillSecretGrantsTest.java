import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkillConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.SkillSecrets;
import services.Tx;
import services.secrets.SecretGrant;
import services.secrets.SecretGrants;
import tools.ShellExecTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JF-324: which shell commands get a skill's secrets, and what one that does sees. A command gets a secret when it
 * names it or runs in its skill's folder, and only while it runs; its environment and output hold a placeholder,
 * never the value, and the value reaches only the secret's host.
 */
class SkillSecretGrantsTest extends UnitTest {

    private static final String VALUE = "fake-api-key-jf324-91d3";
    private static final Pattern PLACEHOLDER = Pattern.compile("jclaw-secret-[a-z2-7]{32}");

    private Agent agent;
    private Path workspace;
    private SecretProxyFixture stub;

    @BeforeEach
    void seed() throws Exception {
        stub = new SecretProxyFixture();
        var name = "jf324-" + UUID.randomUUID().toString().substring(0, 8);
        agent = AgentService.create(name, "openrouter", "gpt-4.1");
        workspace = AgentService.workspacePath(name);
        for (var skill : List.of("demo", "demo-two")) {
            Files.createDirectories(workspace.resolve("skills/" + skill));
            Files.writeString(workspace.resolve("skills/" + skill + "/SKILL.md"),
                    "---\nname: " + skill + "\ndescription: test skill\n---\n# " + skill + "\n");
        }
        Tx.run(() -> {
            SkillSecrets.put(agent, "demo", "API_KEY", VALUE, List.of(SecretProxyFixture.BOUND + ":" + stub.port()));
            SkillSecrets.put(agent, "demo", "OTHER_KEY", "fake-other-value", List.of("other.example.com"));
            SkillSecrets.put(agent, "demo-two", "TWO_KEY", "fake-two-value", List.of("two.example.com"));
        });
    }

    @AfterEach
    void cleanUp() {
        stub.close();
        AgentService.delete(Agent.findById(agent.id));
    }

    private List<String> given(String command) {
        return given(command, workspace);
    }

    private List<String> given(String command, Path workdir) {
        try (SecretGrant grant = SecretGrants.forCommand(agent, command, workdir, false)) {
            return grant == null ? List.of() : grant.names();
        }
    }

    @Test
    void aCommandGetsOnlyTheSecretsItNamesOrWhoseSkillFolderItRuns() {
        assertEquals(List.of("API_KEY"), given("curl -H \"X-Api-Key: $API_KEY\" https://api.example.com/"));
        assertEquals(List.of("API_KEY"), given("echo ${API_KEY}"));
        assertEquals(List.of(), given("echo $API_KEYS $OTHER"));
        assertEquals(List.of("API_KEY", "OTHER_KEY"), given("bash skills/demo/run.sh list"));
        assertEquals(List.of("API_KEY", "OTHER_KEY"), given("./skills/demo/run.sh"));
        assertEquals(List.of("TWO_KEY"), given("bash skills/demo-two/run.sh"));
        assertEquals(List.of("API_KEY", "OTHER_KEY"), given("ls", workspace.resolve("skills/demo")));
        assertEquals(List.of(), given("ls skills/ && cat notes/skills/demonstration.txt"));
        assertEquals(List.of(), given("echo hello"));
    }

    @Test
    void aDisabledSkillGivesNoSecret() {
        Tx.run(() -> {
            var config = new AgentSkillConfig();
            config.agent = Agent.findById(agent.id);
            config.skillName = "demo";
            config.enabled = false;
            config.save();
        });
        agents.SkillLoader.clearCache();
        assertEquals(List.of(), given("bash skills/demo/run.sh"));
        assertEquals(List.of("TWO_KEY"), given("bash skills/demo-two/run.sh"));
    }

    @Test
    void aSecretNameIsUsedOnceOnAnAgent() {
        var refused = assertThrows(SkillSecrets.Refused.class, () -> Tx.run(() ->
                SkillSecrets.put(agent, "demo-two", "API_KEY", "v", List.of("a.example.com"))));
        assertEquals("Skill 'demo' on this agent already has a secret named API_KEY. A command finds a secret by its name,"
                + " so each name is used once on an agent; that skill's commands can use it too.", refused.reason());
    }

    @Test
    void aShellCommandSeesAPlaceholderAndItsRequestReachesTheHostWithTheValue() {
        var previous = ConfigService.get("shell.allowlist", null);
        ConfigService.set("shell.allowlist", "curl,echo,printenv");
        try {
            var tool = new ShellExecTool();
            var echoed = run(tool, "echo \"$API_KEY\"");
            assertTrue(PLACEHOLDER.matcher(echoed.strip()).matches(), echoed);

            var fetched = run(tool, "curl -sS -H \"X-Api-Key: $API_KEY\" https://" + SecretProxyFixture.BOUND + ":" + stub.port()
                    + "/echo");
            assertFalse(fetched.contains(VALUE), fetched);
            assertTrue(PLACEHOLDER.matcher(fetched.lines().filter(l -> l.startsWith("x-api-key=")).findFirst().orElseThrow()
                    .substring("x-api-key=".length())).matches(), fetched);
            assertEquals(VALUE, stub.received.getFirst().header("X-Api-Key"));

            assertEquals("proxy=[]", run(tool, "echo \"proxy=[$HTTPS_PROXY]\"").strip(),
                    "a command that names no secret gets no proxy");
        } finally {
            if (previous == null) ConfigService.delete("shell.allowlist");
            else ConfigService.set("shell.allowlist", previous);
        }
    }

    @Test
    void aBackgroundCommandGetsNoSecretAndIsToldSo() {
        var previous = ConfigService.get("shell.allowlist", null);
        ConfigService.set("shell.allowlist", "echo");
        try {
            var result = JsonParser.parseString(SecretGrants.callWithUpstreamForTest(stub.upstream(), () -> new ShellExecTool()
                    .execute("{\"command\":\"echo \\\"[$API_KEY]\\\"\",\"background\":true}", agent))).getAsJsonObject();
            var output = result.get("output").getAsString();
            assertTrue(output.contains("[A background command is given no skill secrets: run it in the foreground to use"
                    + " them.]"), output);
            assertFalse(PLACEHOLDER.matcher(output).find(), output);
        } finally {
            if (previous == null) ConfigService.delete("shell.allowlist");
            else ConfigService.set("shell.allowlist", previous);
        }
    }

    private String run(ShellExecTool tool, String command) {
        var args = new com.google.gson.JsonObject();
        args.addProperty("command", command);
        var raw = SecretGrants.callWithUpstreamForTest(stub.upstream(), () -> tool.execute(args.toString(), agent));
        var result = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(0, result.get("exitCode").getAsInt(), raw);
        return result.get("output").getAsString();
    }
}
