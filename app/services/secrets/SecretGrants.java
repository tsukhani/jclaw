package services.secrets;

import agents.SkillLoader;
import com.google.errorprone.annotations.MustBeClosed;
import models.Agent;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import services.EventLogger;
import services.SkillSecrets;
import services.Tx;
import services.WorkspaceFiles;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Which secrets a shell command gets, and the live grants the proxy redeems (JF-324). A command gets a secret of a
 * skill enabled on the agent that owns its workspace when it names the secret ({@code $NAS_PASSWORD} or
 * {@code ${NAS_PASSWORD}}) or runs in, or names, that skill's folder ({@code skills/nas-filestation/}). Every other
 * command runs as before, with no proxy and no placeholder.
 */
public final class SecretGrants {

    /** Every placeholder starts so, then 32 base-32 letters: 160 random bits, never derived from a value. */
    static final String PLACEHOLDER_PREFIX = "jclaw-secret-";
    static final int PLACEHOLDER_LENGTH = PLACEHOLDER_PREFIX.length() + 32;
    static final Pattern PLACEHOLDER = Pattern.compile(Pattern.quote(PLACEHOLDER_PREFIX) + "[a-z2-7]{32}");

    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, SecretGrant> LIVE = new ConcurrentHashMap<>();
    private static final ScopedValue<OkHttpClient> UPSTREAM_FOR_TEST = ScopedValue.newInstance();

    private SecretGrants() {}

    /**
     * The grant for one run of {@code command} by {@code agent} in {@code workdir}, or null when the command names
     * none of the secrets its agent's enabled skills hold. A background command outlives the call that would close
     * its grant, so it gets none. The caller closes the grant when the command ends.
     */
    @MustBeClosed
    public static @Nullable SecretGrant forCommand(Agent agent, String command, Path workdir, boolean background) {
        var chosen = background ? null : choose(agent, command, workdir);
        if (chosen == null) return null;
        return open(chosen.ownerName(), chosen.secrets(), UPSTREAM_FOR_TEST.isBound() ? UPSTREAM_FOR_TEST.get() : null,
                true);
    }

    /** Whether {@link #forCommand} would give {@code command} a secret, without opening a grant. */
    public static boolean namesSecrets(Agent agent, String command, Path workdir) {
        return choose(agent, command, workdir) != null;
    }

    private record Found(String ownerName, List<SkillSecrets.Usable> secrets) {}

    private static @Nullable Found choose(Agent agent, String command, Path workdir) {
        if (agent.id == null) return null;
        @Nullable Found found = Tx.run(() -> {
            Agent current = Agent.findById(agent.id);
            if (current == null) return null;
            var owner = WorkspaceFiles.workspaceOwner(current);
            return new Found(owner.name, SkillSecrets.usable(owner));
        });
        if (found == null || found.secrets().isEmpty()) return null;

        // The owner's list, not the running agent's: a subagent runs in its owner's workspace, and its own list drops
        // a skill that asks for a tool it lacks, though the commands of that skill are what it was started to run.
        // The switched-on skills, not the offered ones: a skill still waiting for one secret keeps giving the others
        // it holds, which another skill may declare (JF-348).
        var enabled = SkillLoader.enabledSkillFolders(found.ownerName());
        var skillsRoot = AgentService.workspacePath(found.ownerName()).resolve("skills").toAbsolutePath().normalize();
        var where = workdir.toAbsolutePath().normalize();
        var chosen = new ArrayList<SkillSecrets.Usable>();
        for (var secret : found.secrets()) {
            if (!enabled.contains(secret.skill())) continue;
            if (names(command, secret.name()) || namesFolder(command, secret.skill())
                    || where.startsWith(skillsRoot.resolve(secret.skill()))) {
                chosen.add(secret);
            }
        }
        return chosen.isEmpty() ? null : new Found(found.ownerName(), chosen);
    }

    /** A grant for {@code secrets} with no agent behind it, for the proxy's own tests. */
    @MustBeClosed
    public static SecretGrant openForTest(List<SkillSecrets.Usable> secrets, @Nullable OkHttpClient upstream) {
        return open("test", secrets, upstream, false);
    }

    /**
     * Run {@code body} with every grant it opens sending its requests through {@code client}, so a test's upstream
     * stub, with its own certificate and name, stands in for the real service.
     */
    public static <T> T callWithUpstreamForTest(OkHttpClient client, Supplier<T> body) {
        return ScopedValue.where(UPSTREAM_FOR_TEST, client).call(body::get);
    }

    static @Nullable SecretGrant byLogin(String id, String token) {
        var grant = LIVE.get(id);
        return grant != null && grant.open() && grant.tokenMatches(token) ? grant : null;
    }

    static void remove(SecretGrant grant) {
        LIVE.remove(grant.id(), grant);
    }

    @MustBeClosed
    @SuppressWarnings("MustBeClosed") // made in a retry loop until its id is free, then returned for the caller to close
    private static SecretGrant open(String ownerName, List<SkillSecrets.Usable> secrets, @Nullable OkHttpClient upstream,
                                    boolean record) {
        var bindings = new ArrayList<SecretGrant.Binding>();
        Set<String> used = new HashSet<>();
        for (var s : secrets) {
            String placeholder;
            do {
                placeholder = PLACEHOLDER_PREFIX + random(32);
            } while (!used.add(placeholder));
            bindings.add(new SecretGrant.Binding(s.skill(), s.name(), s.value(), s.hosts(), placeholder));
        }
        SecretGrant grant;
        do {
            grant = new SecretGrant(random(26), random(32), ownerName, bindings, upstream);
        } while (LIVE.putIfAbsent(grant.id(), grant) != null);
        if (record) {
            EventLogger.info("skill", ownerName, null, "A command was given the secrets " + String.join(", ", grant.names()));
        }
        return grant;
    }

    private static boolean names(String command, String name) {
        return Pattern.compile("\\$\\{?" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(command).find();
    }

    private static boolean namesFolder(String command, String skill) {
        var folder = "skills/" + skill.toLowerCase(Locale.ROOT);
        var lower = command.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(folder);
        while (at >= 0) {
            int end = at + folder.length();
            boolean startsClean = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1)) && lower.charAt(at - 1) != '-';
            boolean endsClean = end == lower.length() || !isSkillNameChar(lower.charAt(end));
            if (startsClean && endsClean) return true;
            at = lower.indexOf(folder, at + 1);
        }
        return false;
    }

    private static boolean isSkillNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
    }

    private static String random(int letters) {
        var out = new StringBuilder(letters);
        for (int i = 0; i < letters; i++) out.append(BASE32.charAt(RANDOM.nextInt(BASE32.length())));
        return out.toString();
    }
}
