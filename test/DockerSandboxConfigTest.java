import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import play.Play;
import play.test.UnitTest;
import tools.HarnessSandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JF-165: the container can start the sandbox custom agents' exec runs in. The image installs bwrap, and
 * compose replaces Docker's default seccomp profile, which refuses bwrap's namespaces, with that profile
 * plus one narrow rule, never with {@code unconfined}. JF-413: on an AppArmor host, Docker's docker-default
 * profile refuses bwrap's mounts, so compose lifts AppArmor, and only AppArmor, for the container.
 */
class DockerSandboxConfigTest extends UnitTest {

    private static Path root() {
        return Play.applicationPath.toPath();
    }

    @Test
    void theRuntimeStageInstallsBubblewrap() throws Exception {
        var dockerfile = Files.readString(root().resolve("Dockerfile"));
        var runtime = dockerfile.substring(dockerfile.indexOf("FROM ubuntu:26.04 AS runtime"));
        var packages = new ArrayList<String>();
        for (var install : runtime.split("apt-get install")) {
            for (var token : install.split("&&")[0].replace("\\\n", " ").trim().split("\\s+")) {
                packages.add(token);
            }
        }
        assertTrue(packages.contains("bubblewrap"), "runtime apt packages: " + packages);
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeShipsTheNarrowSeccompProfileAndLiftsOnlyAppArmor() throws Exception {
        var text = Files.readString(root().resolve("docker-compose.yml"));
        Map<String, Object> compose = new Yaml().load(text);
        var services = (Map<String, Object>) compose.get("services");
        var jclaw = (Map<String, Object>) services.get("jclaw");
        assertEquals(List.of("seccomp=./seccomp-bwrap.json", "apparmor=unconfined"), jclaw.get("security_opt"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("seccomp=unconfined"), "compose must never disable seccomp");
        assertFalse(jclaw.containsKey("privileged"));
        assertFalse(jclaw.containsKey("cap_add"));
    }

    @Test
    void theProfileDeniesByDefaultAndAllowsExactlyBwrapsCalls() throws Exception {
        var profile = JsonParser.parseString(Files.readString(root().resolve("seccomp-bwrap.json"))).getAsJsonObject();
        assertEquals("SCMP_ACT_ERRNO", profile.get("defaultAction").getAsString());

        var bwrapRule = new JsonArray();
        for (var name : List.of("clone", "unshare", "mount", "umount2", "pivot_root")) bwrapRule.add(name);
        var matches = new ArrayList<JsonObject>();
        for (var element : profile.getAsJsonArray("syscalls")) {
            var rule = element.getAsJsonObject();
            if (rule.getAsJsonArray("names").equals(bwrapRule)) matches.add(rule);
        }
        assertEquals(1, matches.size(), "rules naming bwrap's calls: " + matches);
        var expected = new JsonObject();
        expected.add("names", bwrapRule);
        expected.addProperty("action", "SCMP_ACT_ALLOW");
        assertEquals(expected, matches.getFirst(), "the rule carries no conditions");
    }

    /**
     * A weaker check than JClaw's probe could print "ok" on a container where custom agents' exec is still refused,
     * and the container runs as root, where bwrap needs {@code --unshare-user} (a plain probe fails there on Docker).
     */
    @Test
    void installsSandboxCheckIsTheReadinessProbe() throws Exception {
        var install = Files.readString(root().resolve("INSTALL.md"));
        var checks = install.lines().filter(l -> l.startsWith("docker compose exec jclaw")).toList();
        assertEquals(List.of("docker compose exec jclaw sh -c '" + String.join(" ", HarnessSandbox.BWRAP_PROBE_AS_ROOT)
                + " && echo sandbox ok'"), checks);
    }

    /** JF-413: the Linux host check is JClaw's own probe too, run as the account JClaw runs as. */
    @Test
    void installsLinuxSandboxCheckIsTheProbe() throws Exception {
        var install = Files.readString(root().resolve("INSTALL.md"));
        var checks = install.lines().filter(l -> l.startsWith("bwrap ")).toList();
        assertEquals(List.of(String.join(" ", HarnessSandbox.BWRAP_PROBE) + " && echo sandbox ok"), checks);
    }
}
