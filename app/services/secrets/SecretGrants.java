package services.secrets;

import agents.SkillLoader;
import agents.ToolRegistry;
import com.google.errorprone.annotations.MustBeClosed;
import io.opentelemetry.context.Context;
import models.Agent;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import services.EventLogger;
import services.SkillSecrets;
import services.SkillSettings;
import services.Tx;
import services.WorkspaceFiles;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
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
    public static final String PLACEHOLDER_PREFIX = "jclaw-secret-";
    static final int PLACEHOLDER_LENGTH = PLACEHOLDER_PREFIX.length() + 32;
    static final Pattern PLACEHOLDER = Pattern.compile(Pattern.quote(PLACEHOLDER_PREFIX) + "[a-z2-7]{32}");

    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, SecretGrant> LIVE = new ConcurrentHashMap<>();
    /** Bumped by each {@link #reconcile}, so a grant chosen before one and opened after it can tell. */
    private static final AtomicLong RECONCILES = new AtomicLong();
    private static final ScopedValue<OkHttpClient> UPSTREAM_FOR_TEST = ScopedValue.newInstance();

    private SecretGrants() {}

    /**
     * The grant for one run of {@code command} by {@code agent} in {@code workdir}, or null when the command names
     * none of the secrets its agent's enabled skills hold. A background command outlives the call that would close
     * its grant, so it gets none. The caller closes the grant when the command ends.
     */
    @MustBeClosed
    public static @Nullable SecretGrant forCommand(Agent agent, String command, Path workdir, boolean background) {
        long seen = RECONCILES.get();
        var chosen = background ? null : choose(agent, command, workdir);
        if (chosen == null) return null;
        return open(chosen.ownerName(), chosen.secrets(), UPSTREAM_FOR_TEST.isBound() ? UPSTREAM_FOR_TEST.get() : null,
                true, seen);
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

    /**
     * The settings {@code command} is given (JF-357), as environment variables: plain values, under the rule that
     * gives a secret. A background command gets them too, since no grant has to outlive it.
     */
    public static Map<String, String> settingsFor(Agent agent, String command, Path workdir) {
        if (agent.id == null) return Map.of();
        record Owned(String ownerName, List<SkillSettings.Usable> settings) {}
        @Nullable Owned found = Tx.run(() -> {
            Agent current = Agent.findById(agent.id);
            if (current == null) return null;
            var owner = WorkspaceFiles.workspaceOwner(current);
            return new Owned(owner.name, SkillSettings.usable(owner));
        });
        if (found == null || found.settings().isEmpty()) return Map.of();
        var enabled = SkillLoader.enabledSkillFolders(found.ownerName());
        var skillsRoot = AgentService.workspacePath(found.ownerName()).resolve("skills").toAbsolutePath().normalize();
        var where = workdir.toAbsolutePath().normalize();
        var out = new LinkedHashMap<String, String>();
        for (var setting : found.settings()) {
            if (!enabled.contains(setting.skill())) continue;
            if (names(command, setting.name()) || namesFolder(command, setting.skill())
                    || where.startsWith(skillsRoot.resolve(setting.skill()))) {
                out.put(setting.name(), setting.value());
            }
        }
        return out;
    }

    /** A grant for {@code secrets} with no agent behind it, for the proxy's own tests. */
    @MustBeClosed
    public static SecretGrant openForTest(List<SkillSecrets.Usable> secrets, @Nullable OkHttpClient upstream) {
        return open("test", secrets, upstream, false, RECONCILES.get());
    }

    /**
     * Run {@code body} with every grant it opens sending its requests through {@code client}, so a test's upstream
     * stub, with its own certificate and name, stands in for the real service.
     */
    public static <T> T callWithUpstreamForTest(OkHttpClient client, Supplier<T> body) {
        return ScopedValue.where(UPSTREAM_FOR_TEST, client).call(body::get);
    }

    /**
     * Once the ambient transaction commits, clear {@code changed}'s disabled tools and {@link SkillLoader}'s cache, in
     * that order, and {@link #reconcile} the grants of its workspace owner; a change that rolls back revokes nothing.
     */
    public static void reconcileAfterCommit(Agent changed) {
        var ownerName = WorkspaceFiles.workspaceOwner(changed).name;
        Tx.afterCommit(() -> {
            ToolRegistry.invalidateDisabledToolsCache(changed);
            SkillLoader.clearCache();
            reconcile(ownerName);
        });
    }

    /**
     * Each open grant of {@code ownerName}'s workspace stops every secret a new command would no longer get from a
     * switched-on skill, with the same name, value and hosts; the command keeps running, and keeps its other secrets.
     * Run once the change has committed and {@link SkillLoader}'s cache is clear.
     */
    private static void reconcile(String ownerName) {
        RECONCILES.incrementAndGet();
        var open = LIVE.values().stream().filter(g -> g.forAgent() && g.ownerName().equals(ownerName)).toList();
        if (!open.isEmpty()) revokeWithdrawn(ownerName, open);
    }

    private record Given(String skill, String name, String value, Set<String> hosts) {
        static Given of(String skill, String name, String value, List<String> hosts) {
            return new Given(skill, name, value, Set.copyOf(hosts));
        }
    }

    private static void revokeWithdrawn(String ownerName, List<SecretGrant> grants) {
        Set<Given> given;
        try {
            given = givenNow(ownerName);
        } catch (RuntimeException e) {
            // Fail closed: a secret whose grant cannot be re-checked is no longer sent.
            given = Set.of();
            EventLogger.warn("skill", ownerName, null,
                    "Could not check the running commands' secrets after a change, so they are stopped: " + e);
        }
        var still = given;
        for (var grant : grants) {
            var stopped = grant.revokeUnless(b -> still.contains(Given.of(b.skill(), b.name(), b.value(), b.hosts())));
            if (!stopped.isEmpty()) {
                EventLogger.info("skill", ownerName, null,
                        "A running command can no longer send the secrets " + String.join(", ", stopped));
            }
        }
    }

    /**
     * What a new command of the owner's workspace could be given now, read on a thread of its own: an after-commit
     * callback still holds the finished transaction, which {@link Tx#run} would join rather than open a fresh one.
     */
    private static Set<Given> givenNow(String ownerName) {
        var read = new FutureTask<Set<Given>>(() -> {
            List<SkillSecrets.Usable> usable = Tx.run(() -> {
                var owner = Agent.findByName(ownerName);
                return owner == null ? List.<SkillSecrets.Usable>of() : SkillSecrets.usable(owner);
            });
            var enabled = SkillLoader.enabledSkillFolders(ownerName);
            var out = new HashSet<Given>();
            for (var s : usable) {
                if (enabled.contains(s.skill())) out.add(Given.of(s.skill(), s.name(), s.value(), s.hosts()));
            }
            return out;
        });
        Thread.ofVirtual().name("secret-grants-reconcile").start(Context.current().wrap(read));
        try {
            return read.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        }
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
                                    boolean forAgent, long reconcilesSeen) {
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
            grant = new SecretGrant(random(26), random(32), ownerName, forAgent, bindings, upstream);
        } while (LIVE.putIfAbsent(grant.id(), grant) != null);
        if (forAgent) {
            // A change that committed while the secrets were chosen reconciled the live grants before this one was live.
            if (RECONCILES.get() != reconcilesSeen) revokeWithdrawn(ownerName, List.of(grant));
            var held = grant.names();
            if (!held.isEmpty()) {
                EventLogger.info("skill", ownerName, null, "A command was given the secrets " + String.join(", ", held));
            }
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
