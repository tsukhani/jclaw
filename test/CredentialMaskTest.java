import agents.ToolRegistry;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.CredentialMask;
import services.SkillSecrets;
import services.Tx;
import tools.FileSystemTools;
import tools.ShellExecTool;
import utils.ToolErrorTemplates;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JF-361: credential values JClaw holds are masked in tool results before the model or the transcript sees them. */
class CredentialMaskTest extends UnitTest {

    private static final String SECRET = "fake-skill-secret-9d1c4e77";
    private static final String PROVIDER_KEY = "fake-provider-key-2b8f61aa";
    private static final String STAND_IN = "jclaw-secret-abcdefghijklmnopqrstuvwxyz234567";

    private Agent agent;
    private String providerKey;

    @BeforeEach
    void seed() throws Exception {
        var name = "jf361-" + UUID.randomUUID().toString().substring(0, 8);
        agent = commitInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        var skill = AgentService.workspacePath(name).resolve("skills/demo");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: demo\ndescription: test skill\n---\n# demo\n");
        commitInFreshTx(() -> {
            var owner = Agent.<Agent>findById(agent.id);
            SkillSecrets.put(owner, "demo", "DEMO_API_KEY", SECRET, List.of("api.example.com"));
            return SkillSecrets.put(owner, "demo", "DEMO_PIN", "1234567", List.of("api.example.com"));
        });
        providerKey = "provider.jf361-" + UUID.randomUUID().toString().substring(0, 8) + ".apiKey";
        commitInFreshTx(() -> {
            ConfigService.set(providerKey, PROVIDER_KEY);
            return null;
        });
        Files.writeString(AgentService.workspacePath(name).resolve("leak.txt"),
                "copied token " + SECRET + " and key " + PROVIDER_KEY + "\n");
    }

    @AfterEach
    void cleanUp() {
        commitInFreshTx(() -> {
            ConfigService.delete(providerKey);
            AgentService.delete(Agent.findById(agent.id));
            return null;
        });
        CredentialMask.invalidate();
    }

    /**
     * {@code block} committed on a thread of its own: the test body runs inside a transaction that commits only when
     * it ends, so a write there would fire no after-commit hook and stay unseen by the mask's background read.
     */
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    @Test
    void everyCredentialValueIsReplacedByAMaskThatNamesIt() {
        assertEquals("copied token [masked: DEMO_API_KEY] and key [masked: " + providerKey + "]",
                CredentialMask.mask("copied token " + SECRET + " and key " + PROVIDER_KEY));
        assertEquals("inside:[masked: DEMO_API_KEY]:line", CredentialMask.mask("inside:" + SECRET + ":line"));
    }

    @Test
    void aShortValueAStandInAndPlainTextAreLeftAlone() {
        assertEquals("pin 1234567 stays", CredentialMask.mask("pin 1234567 stays"), "a value under 8 characters");
        assertEquals("stand-in " + STAND_IN, CredentialMask.mask("stand-in " + STAND_IN));
        assertEquals("nothing secret here", CredentialMask.mask("nothing secret here"));
    }

    @Test
    void aSecretAddedOrDeletedIsMaskedOrUnmaskedAtOnce() {
        var fresh = "fake-fresh-secret-77aa31c0";
        assertEquals(fresh, CredentialMask.mask(fresh));
        commitInFreshTx(() -> SkillSecrets.put(Agent.findById(agent.id), "demo", "FRESH_TOKEN", fresh,
                List.of("api.example.com")));
        assertEquals("[masked: FRESH_TOKEN]", CredentialMask.mask(fresh));
        commitInFreshTx(() -> SkillSecrets.delete(Agent.findById(agent.id), "demo", "FRESH_TOKEN"));
        assertEquals(fresh, CredentialMask.mask(fresh));
    }

    /** What a skill saved for itself is masked too: a session key whole, and the token values in a cookie jar. */
    @Test
    void savedStateIsMaskedAndAChangedFileCountsAtOnce() throws Exception {
        var state = AgentService.workspacePath(agent.name).resolve("skill-state/demo");
        Files.createDirectories(state);
        var key = "Zq7mN2pX9vB4kL1wR8tY5uH3jG6fD0sA2cE9iO4";
        Files.writeString(state.resolve("session-key"), key + "\n");
        var jwt = "eyJhbGciOi.fakePayload1234567890abcdef.fakeSig0987654321zyxw";
        Files.writeString(state.resolve("cookies.json"),
                "[{\"name\":\"orm-jwt\",\"value\":\"" + jwt + "\",\"domain\":\".learning.oreilly.com\"},"
                        + "{\"name\":\"orm-rt\",\"value\":\"a1b2c3d4e5f6a7b8c9d0e1f2\",\"domain\":\".learning.oreilly.com\"}]");

        assertEquals("key [masked: saved demo/session-key]", CredentialMask.mask("key " + key));
        assertEquals("jwt [masked: saved demo/cookies.json] rt [masked: saved demo/cookies.json]",
                CredentialMask.mask("jwt " + jwt + " rt a1b2c3d4e5f6a7b8c9d0e1f2"));
        assertEquals("host .learning.oreilly.com and orm-jwt", CredentialMask.mask("host .learning.oreilly.com and orm-jwt"),
                "the file's domain and names are no tokens");

        var renewed = "Pk3Lm9Qx2Wv7Bn4Rt8Ys1Uh6Jg5Fd0Ze3Ca7Io2";
        Files.writeString(state.resolve("session-key"), renewed);
        assertEquals("[masked: saved demo/session-key]", CredentialMask.mask(renewed), "a renewed key, at once");
    }

    @Test
    void aLinkedOrLargeStateFileAddsNothing() throws Exception {
        var outside = Files.createTempFile("jf361-outside-", ".txt");
        try {
            Files.writeString(outside, "Outside0Value1Of2A3Link4Target5");
            var state = AgentService.workspacePath(agent.name).resolve("skill-state/demo");
            Files.createDirectories(state);
            Files.createSymbolicLink(state.resolve("linked"), outside);
            Files.writeString(state.resolve("big"), "Big0Token1Value2Here3".repeat(1) + " " + "x".repeat(70_000));
            assertEquals("Outside0Value1Of2A3Link4Target5", CredentialMask.mask("Outside0Value1Of2A3Link4Target5"));
            assertEquals("Big0Token1Value2Here3", CredentialMask.mask("Big0Token1Value2Here3"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * No result waits on the database: once the values are 30 s old, the next result is masked with them while a
     * background read fetches the new ones. A row saved without {@link CredentialMask#invalidate} stands in for a
     * binding token or an MCP header, which nothing invalidates.
     */
    @Test
    void oldValuesKeepMaskingWhileABackgroundReadFetchesNewOnes() throws Exception {
        var unannounced = "fake-unannounced-token-5e0b9c21";
        CredentialMask.mask("warm");
        commitInFreshTx(() -> {
            var row = new models.SkillSecret();
            row.agent = Agent.findById(agent.id);
            row.skillName = "demo";
            row.name = "UNANNOUNCED_TOKEN";
            row.value = unannounced;
            row.hosts = "api.example.com";
            row.updatedAt = utils.AppClock.now();
            return row.save();
        });
        assertEquals(unannounced, CredentialMask.mask(unannounced), "within 30 s the values are not read again");

        var later = java.time.Clock.offset(utils.AppClock.clock(), java.time.Duration.ofSeconds(31));
        utils.AppClock.runWith(later, () -> {
            assertEquals(unannounced, CredentialMask.mask(unannounced), "the first result after 30 s does not wait");
            var masked = unannounced;
            for (int i = 0; i < 100 && masked.equals(unannounced); i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                masked = CredentialMask.mask(unannounced);
            }
            assertEquals("[masked: UNANNOUNCED_TOKEN]", masked, "the background read brought the new value");
        });
    }

    /**
     * A file read shows its key masked, so writing that read back whole would save the mask over the key: such a
     * write is refused and the file keeps its key, while an edit that leaves the key's line alone goes through.
     */
    @Test
    void writingAMaskedReadBackIsRefusedAndTheKeyStays() throws Exception {
        var file = AgentService.workspacePath(agent.name).resolve("config.env");
        var original = "API_KEY=" + SECRET + "\nMODE=fast\n";
        Files.writeString(file, original);
        var refused = ToolErrorTemplates.render(ToolErrorTemplates.fsMaskInWrite("'config.env'"));
        ToolRegistrySync.withTools(List.of(new FileSystemTools()), () -> {
            var read = ToolRegistry.execute("filesystem", "{\"action\":\"readFile\",\"path\":\"config.env\"}", agent);
            assertEquals(true, read.contains("API_KEY=[masked: DEMO_API_KEY]"), read);

            var writeBack = new com.google.gson.JsonObject();
            writeBack.addProperty("action", "writeFile");
            writeBack.addProperty("path", "config.env");
            writeBack.addProperty("content", "API_KEY=[masked: DEMO_API_KEY]\nMODE=slow\n");
            assertEquals(refused, ToolRegistry.execute("filesystem", writeBack.toString(), agent));
            assertEquals(original, Files.readString(file), "the key is still in the file");

            var addingAMask = "{\"action\":\"editFile\",\"path\":\"config.env\",\"edits\":[{\"oldText\":\"MODE=fast\","
                    + "\"newText\":\"MODE=fast\\nOTHER_KEY=[redacted]\"}]}";
            assertEquals(refused, ToolRegistry.execute("filesystem", addingAMask, agent));
            var patch = "--- a/config.env\n+++ b/config.env\n@@ -1,2 +1,2 @@\n-API_KEY=x\n+API_KEY=[masked: DEMO_API_KEY]\n MODE=fast\n";
            var patchArgs = new com.google.gson.JsonObject();
            patchArgs.addProperty("action", "applyPatch");
            patchArgs.addProperty("patch", patch);
            assertEquals(ToolErrorTemplates.render(ToolErrorTemplates.fsMaskInWrite("the patched files")),
                    ToolRegistry.execute("filesystem", patchArgs.toString(), agent));
            assertEquals(original, Files.readString(file));

            var edit = "{\"action\":\"editFile\",\"path\":\"config.env\",\"edits\":[{\"oldText\":\"MODE=fast\","
                    + "\"newText\":\"MODE=slow\"}]}";
            assertEquals(false, ToolRegistry.execute("filesystem", edit, agent).startsWith(refused));
            assertEquals("API_KEY=" + SECRET + "\nMODE=slow\n", Files.readString(file), "the edit kept the key");
        });
    }

    /** A page that already holds mask text, such as one about masking, can be rewritten as it is and appended to. */
    @Test
    void aFileThatAlreadyHoldsMaskTextCanBeRewrittenAsItIs() throws Exception {
        var page = AgentService.workspacePath(agent.name).resolve("masking.md");
        var text = "A key shows as [redacted] and a stored value as [masked: NAME].\n";
        Files.writeString(page, text);
        ToolRegistrySync.withTools(List.of(new FileSystemTools()), () -> {
            var rewrite = new com.google.gson.JsonObject();
            rewrite.addProperty("action", "writeFile");
            rewrite.addProperty("path", "masking.md");
            rewrite.addProperty("content", text + "Nothing else changed.\n");
            ToolRegistry.execute("filesystem", rewrite.toString(), agent);
            assertEquals(text + "Nothing else changed.\n", Files.readString(page));
        });
    }

    /** The log masker's key shapes are masked too, unless the setting turns them off. */
    @Test
    void keyShapesAreMaskedUnlessTurnedOff() {
        var shaped = "key sk-proj-abcdefghijklmnopqrstuvwxyz0123456789 end";
        assertEquals("key [redacted] end", CredentialMask.mask(shaped));
        commitInFreshTx(() -> {
            ConfigService.set(CredentialMask.SHAPES_KEY, "false");
            return null;
        });
        try {
            assertEquals(shaped, CredentialMask.mask(shaped));
            assertEquals("[masked: DEMO_API_KEY]", CredentialMask.mask(SECRET), "exact values are masked regardless");
        } finally {
            commitInFreshTx(() -> {
                ConfigService.delete(CredentialMask.SHAPES_KEY);
                return null;
            });
        }
    }

    /** Every tool result passes the mask at the registry: shell output, a file read, and any other tool's text and JSON. */
    @Test
    void toolResultsAreMaskedWhateverToolProducedThem() throws Exception {
        var echo = new ToolRegistry.Tool() {
            public String name() { return "jf361_echo"; }
            public String description() { return "Stands in for an MCP, browser or web tool"; }
            public Map<String, Object> parameters() { return Map.of(); }
            public String execute(String args, Agent a) { return executeRich(args, a).text(); }
            @Override
            public ToolRegistry.ToolResult executeRich(String args, Agent a) {
                return new ToolRegistry.ToolResult("page says " + SECRET, "{\"field\":\"" + PROVIDER_KEY + "\"}");
            }
        };
        var thrower = new ToolRegistry.Tool() {
            public String name() { return "jf361_throw"; }
            public String description() { return "Fails with a credential in its message"; }
            public Map<String, Object> parameters() { return Map.of(); }
            public String execute(String args, Agent a) { throw new IllegalStateException("bad token " + SECRET); }
        };
        var previous = ConfigService.get("shell.allowlist", null);
        ConfigService.set("shell.allowlist", "cat");
        try {
            ToolRegistrySync.withTools(List.of(new FileSystemTools(), new ShellExecTool(), echo, thrower), () -> {
                var read = ToolRegistry.execute("filesystem", "{\"action\":\"readFile\",\"path\":\"leak.txt\"}", agent);
                assertEquals(true, read.contains("copied token [masked: DEMO_API_KEY] and key [masked: " + providerKey + "]"), read);
                assertEquals(false, read.contains(SECRET), read);

                var shell = ToolRegistry.execute("exec", "{\"command\":\"cat leak.txt\"}", agent);
                assertEquals(true, shell.contains("copied token [masked: DEMO_API_KEY] and key [masked: " + providerKey + "]"), shell);
                assertEquals(false, shell.contains(SECRET), shell);

                var rich = ToolRegistry.executeRich("jf361_echo", "{}", agent, null);
                assertEquals("page says [masked: DEMO_API_KEY]", rich.text());
                assertEquals("{\"field\":\"[masked: " + providerKey + "]\"}", rich.structuredJson());

                assertEquals("Error executing tool 'jf361_throw': bad token [masked: DEMO_API_KEY]",
                        ToolRegistry.execute("jf361_throw", "{}", agent));
            });
        } finally {
            if (previous == null) ConfigService.delete("shell.allowlist");
            else ConfigService.set("shell.allowlist", previous);
        }
    }
}
