import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.SubprocessEnv;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-779: the shared subprocess-environment filter. Every spawn site (the
 * {@code exec} tool, MCP stdio servers, ACP harnesses) routes through this util
 * so host secrets never reach a child process. These tests pin the predicate,
 * the filtered host-env copy, and the in-place {@code apply} — including an
 * end-to-end {@code /bin/sh} spawn proving a seeded "inherited host secret" is
 * stripped from a real child while a non-sensitive var and the operator config
 * env survive.
 */
class SubprocessEnvTest extends UnitTest {

    @Test
    void isSensitiveMatchesSecretNamesAndPrefixes() {
        assertTrue(SubprocessEnv.isSensitive("OPENAI_API_KEY"));
        assertTrue(SubprocessEnv.isSensitive("ANTHROPIC_API_KEY"));
        assertTrue(SubprocessEnv.isSensitive("AWS_ACCESS_KEY_ID"));
        assertTrue(SubprocessEnv.isSensitive("PLAY_SECRET"));
        assertTrue(SubprocessEnv.isSensitive("GITHUB_TOKEN"));
        assertTrue(SubprocessEnv.isSensitive("DB_PASSWORD"));
        assertTrue(SubprocessEnv.isSensitive("my_credential"));
    }

    @Test
    void isSensitivePassesOrdinaryVars() {
        assertFalse(SubprocessEnv.isSensitive("PATH"));
        assertFalse(SubprocessEnv.isSensitive("HOME"));
        assertFalse(SubprocessEnv.isSensitive("LANG"));
        assertFalse(SubprocessEnv.isSensitive("SHELL"));
        assertFalse(SubprocessEnv.isSensitive("USER"));
    }

    @Test
    void filteredHostEnvStripsSecretsKeepsPath() {
        var filtered = SubprocessEnv.filteredHostEnv();
        for (var key : filtered.keySet()) {
            assertFalse(SubprocessEnv.isSensitive(key),
                    "host secret leaked through filteredHostEnv: " + key);
        }
        Assumptions.assumeTrue(System.getenv("PATH") != null, "no PATH in this environment");
        assertEquals(System.getenv("PATH"), filtered.get("PATH"),
                "non-sensitive PATH must be carried through");
    }

    @Test
    void applyStripsSecretsAndLetsConfigWin() {
        var pb = new ProcessBuilder("true");
        // Simulate inherited host env: one secret, one ordinary var.
        pb.environment().put("SECRET_KEY", "host-secret");
        pb.environment().put("KEEP_ME", "host-value");

        // Operator config overrides an inherited key and adds a new one.
        SubprocessEnv.apply(pb, Map.of("KEEP_ME", "config-override", "NEW_CFG", "added"));

        var env = pb.environment();
        assertFalse(env.containsKey("SECRET_KEY"), "inherited secret must be stripped");
        assertEquals("config-override", env.get("KEEP_ME"), "operator config env must win over host");
        assertEquals("added", env.get("NEW_CFG"), "operator config env must be added");
    }

    /**
     * End-to-end proof the fix reaches a real child: seed a secret-looking var
     * onto the ProcessBuilder (exactly how {@link ProcessBuilder} presents the
     * inherited host env), run {@code apply}, spawn {@code /bin/sh}, and confirm
     * the child cannot echo the secret while PATH and the operator config env
     * ARE visible to it.
     */
    @Test
    void applyStripsSeededSecretFromRealChild() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().startsWith("windows"),
                "no POSIX /bin/sh; skipping");
        Assumptions.assumeTrue(System.getenv("PATH") != null, "no PATH in this environment");

        var pb = new ProcessBuilder("/bin/sh", "-c",
                "echo \"SECRET=[$FAKE_API_TOKEN]\"; echo \"PATHSET=[${PATH:+yes}]\"; echo \"CFG=[$MCP_CFG_VAR]\"");
        pb.redirectErrorStream(true);
        pb.environment().put("FAKE_API_TOKEN", "leak-me");   // an inherited host secret

        SubprocessEnv.apply(pb, Map.of("MCP_CFG_VAR", "cfg-value"));

        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(5, TimeUnit.SECONDS), "child must exit");

        assertTrue(out.contains("SECRET=[]"), "seeded host secret must be stripped: " + out);
        assertTrue(out.contains("PATHSET=[yes]"), "non-sensitive PATH must survive: " + out);
        assertTrue(out.contains("CFG=[cfg-value]"), "operator config env must reach the child: " + out);
    }
}
