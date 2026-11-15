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
import services.KeptSkillFiles;
import services.SkillSecrets;
import services.SkillSettings;
import services.Tx;
import services.WorkspaceFiles;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Which secrets a shell command gets, and the live grants the proxy redeems (JF-324). A command gets a secret of a
 * skill enabled on the agent that owns its workspace when it names the secret ({@code $NAS_PASSWORD} or
 * {@code ${NAS_PASSWORD}}) or runs in, or names, that skill's folder ({@code skills/nas-filestation/}). Every other
 * command runs with no proxy and no placeholder.
 */
public final class SecretGrants {

    /** Every placeholder starts so, then 32 base-32 letters: 160 random bits, never derived from a value. */
    public static final String PLACEHOLDER_PREFIX = "jclaw-secret-";
    static final int PLACEHOLDER_LENGTH = PLACEHOLDER_PREFIX.length() + 32;
    static final Pattern PLACEHOLDER = Pattern.compile(Pattern.quote(PLACEHOLDER_PREFIX) + "[a-z2-7]{32}");

    private static final String BASE32 = "abcdefghijklmnopqrstuvwxyz234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, SecretGrant> LIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantLock> OWNER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Rules> RULES = new ConcurrentHashMap<>();
    /** Bumped by each {@link #reconcile}, so a grant chosen before one and opened after it can tell. */
    private static final AtomicLong RECONCILES = new AtomicLong();
    private static final ScopedValue<OkHttpClient> UPSTREAM_FOR_TEST = ScopedValue.newInstance();
    private static final ScopedValue<Runnable> CHOSEN_FOR_TEST = ScopedValue.newInstance();
    private static final ScopedValue<CaptureFaults> CAPTURE_FAULTS_FOR_TEST = ScopedValue.newInstance();

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
        if (CHOSEN_FOR_TEST.isBound()) CHOSEN_FOR_TEST.get().run();
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
     * Run {@code body} with {@code chosen} run by each grant it opens for a command, once the command's secrets are
     * read and before its grant is live, so a test can commit a change in between.
     */
    public static <T> T callWithChosenHookForTest(Runnable chosen, Supplier<T> body) {
        return ScopedValue.where(CHOSEN_FOR_TEST, chosen).call(body::get);
    }

    /**
     * What a grant runs where the secrets proxy reads its owner's capture rules for a request, and inside the
     * transaction that stores a captured value (JF-374): a test throws from one to fail that step.
     */
    record CaptureFaults(Runnable rulesRead, Runnable store) {}

    /**
     * Run {@code body} with each grant it opens running {@code rulesRead} and {@code store} as {@link CaptureFaults}
     * says. The proxy serves each request on a thread of its own, so the grant carries them there.
     */
    public static <T> T callWithCaptureFaultsForTest(Runnable rulesRead, Runnable store, Supplier<T> body) {
        return ScopedValue.where(CAPTURE_FAULTS_FOR_TEST, new CaptureFaults(rulesRead, store)).call(body::get);
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
     * Each open grant of {@code ownerName}'s workspace holds every secret only as a new command would get it from a
     * switched-on skill, with the same name, value and hosts; a renewed secret whose stored value the secrets proxy
     * took from a sign-in, its skill, name and hosts unchanged, takes that value instead (JF-374). Any other secret
     * stops, a value the operator typed included; the command keeps running, and keeps its other secrets. Run once the
     * change has committed and {@link SkillLoader}'s cache is clear.
     */
    private static void reconcile(String ownerName) {
        var lock = lockOf(ownerName);
        lock.lock();
        try {
            RECONCILES.incrementAndGet();
            var open = LIVE.values().stream().filter(g -> g.forAgent() && g.ownerName().equals(ownerName)).toList();
            if (!open.isEmpty()) recheck(ownerName, open);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Captures and reconciles of one owner's grants run one at a time, each reading the stored values under it, so
     * two sign-ins that commit close together leave every grant on the later value rather than stop it. No thread
     * waits for it holding a pooled connection, so the holder's own read always gets one: a commit's connection is back
     * in the pool before its after-commit reconcile runs ({@link Tx#afterCommit}), and the proxy and the shell tool
     * take it outside any transaction.
     */
    private static ReentrantLock lockOf(String ownerName) {
        return OWNER_LOCKS.computeIfAbsent(ownerName, _ -> new ReentrantLock());
    }

    private record Key(String skill, String name) {}

    /** A secret as a new command would be given it now; {@code renewed} when its value is one the proxy took. */
    private record Now(String value, Set<String> hosts, boolean renewed) {}

    private static void recheck(String ownerName, List<SecretGrant> grants) {
        var lock = lockOf(ownerName);
        lock.lock();
        try {
            Map<Key, Now> given;
            try {
                given = givenNow(ownerName);
            } catch (RuntimeException e) {
                // Fail closed: a secret whose grant cannot be re-checked is no longer sent.
                given = Map.of();
                EventLogger.warn("skill", ownerName, null,
                        "Could not check the running commands' secrets after a change, so they are stopped: " + e);
            }
            var now = given;
            for (var grant : grants) {
                var stopped = grant.reconcile(b -> valueNow(now, b));
                if (!stopped.isEmpty()) {
                    EventLogger.info("skill", ownerName, null,
                            "A running command can no longer send the secrets " + String.join(", ", stopped));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static @Nullable String valueNow(Map<Key, Now> given, SecretGrant.Binding b) {
        var now = given.get(new Key(b.skill(), b.name()));
        if (now == null || !now.hosts().equals(Set.copyOf(b.hosts()))) return null;
        if (now.value().equals(b.value())) return b.value();
        return now.renewed() ? now.value() : null;
    }

    /**
     * What a new command of the owner's workspace could be given now, read on a thread of its own: an after-commit
     * callback still holds the finished transaction, which {@link Tx#run} would join rather than open a fresh one.
     */
    private static Map<Key, Now> givenNow(String ownerName) {
        var read = new FutureTask<Map<Key, Now>>(() -> {
            List<SkillSecrets.Usable> usable = Tx.run(() -> {
                var owner = Agent.findByName(ownerName);
                return owner == null ? List.<SkillSecrets.Usable>of() : SkillSecrets.usable(owner);
            });
            var enabled = SkillLoader.enabledSkillFolders(ownerName);
            var out = new HashMap<Key, Now>();
            for (var s : usable) {
                if (enabled.contains(s.skill())) {
                    out.put(new Key(s.skill(), s.name()), new Now(s.value(), Set.copyOf(s.hosts()), s.renewed()));
                }
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

    private record Rules(long generation, List<SkillSecrets.Renewable> rules) {}

    /**
     * The renewed secrets of {@code grant}'s owner with a capture rule, of its switched-on skills, read on the proxy's thread,
     * which holds no transaction. Kept until the next {@link SkillLoader#clearCache}, which a secret's save, delete or
     * capture and a skill's switch each run once they commit, so a proxied request reads no row between changes. A
     * read that fails gives the last set read when that was empty, since an owner with no rule has no answer to take
     * a key from; otherwise it throws, and the request is not sent.
     */
    static List<SkillSecrets.Renewable> renewable(SecretGrant grant) {
        var ownerName = grant.ownerName();
        // Read before the rows: a change committing during the read leaves the entry stale, never wrongly fresh.
        long generation = SkillLoader.generation();
        var cached = RULES.get(ownerName);
        try {
            var faults = grant.captureFaults();
            if (faults != null) faults.rulesRead().run();
            if (cached != null && cached.generation() == generation) return cached.rules();
            List<SkillSecrets.Renewable> rules = Tx.run(() -> {
                var owner = Agent.findByName(ownerName);
                return owner == null ? List.<SkillSecrets.Renewable>of() : SkillSecrets.renewable(owner);
            });
            RULES.put(ownerName, new Rules(generation, rules));
            return rules;
        } catch (RuntimeException e) {
            if (cached != null && cached.rules().isEmpty()) return List.of();
            throw e;
        }
    }

    /** A value the proxy took from a sign-in answer, and the rule it took it under. */
    record Captured(SkillSecrets.Renewable rule, String value) {}

    /** Rolls back a capture one of whose values could not be stored. */
    private static final class NotStored extends RuntimeException {
        private final transient SkillSecrets.Renewable rule;

        NotStored(SkillSecrets.Renewable rule) {
            super(null, null, false, false);
            this.rule = rule;
        }
    }

    /**
     * Store every value in {@code captured}, which the proxy took from one sign-in answer to {@code grant}'s command
     * (JF-374), in one transaction: all or none. Once it commits, the reconcile that follows gives every open grant of
     * the grant's owner holding one of those secrets its new value. The rule whose value could not be stored, its
     * secret gone or its rule changed, when one could not; null when all were.
     */
    static SkillSecrets.@Nullable Renewable storeCaptured(SecretGrant grant, List<Captured> captured) {
        var ownerName = grant.ownerName();
        var faults = grant.captureFaults();
        var lock = lockOf(ownerName);
        lock.lock();
        try {
            return Tx.run(() -> {
                if (faults != null) faults.store().run();
                var owner = Agent.findByName(ownerName);
                for (var c : captured) {
                    var rule = c.rule();
                    if (owner == null
                            || !SkillSecrets.storeCaptured(owner, rule.skill(), rule.name(), rule.capture(), c.value())) {
                        throw new NotStored(rule);
                    }
                }
                if (owner != null) {
                    // A kept file assigning the secret holds a key older than the one just stored, as after a typed one.
                    var skillsRoot = AgentService.workspacePath(owner.name).resolve("skills");
                    for (var skill : captured.stream().map(c -> c.rule().skill()).distinct().toList()) {
                        KeptSkillFiles.deleteUsedUp(owner, skill, skillsRoot.resolve(skill), null);
                    }
                    reconcileAfterCommit(owner);
                }
                return null;
            });
        } catch (NotStored e) {
            return e.rule;
        } finally {
            lock.unlock();
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
        var faults = CAPTURE_FAULTS_FOR_TEST.isBound() ? CAPTURE_FAULTS_FOR_TEST.get() : null;
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
            grant = new SecretGrant(random(26), random(32), ownerName, forAgent, bindings, upstream, faults);
        } while (LIVE.putIfAbsent(grant.id(), grant) != null);
        if (forAgent) {
            // A change that committed while the secrets were chosen reconciled the live grants before this one was live.
            if (RECONCILES.get() != reconcilesSeen) recheck(ownerName, List.of(grant));
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
