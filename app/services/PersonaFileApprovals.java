package services;

import memory.MemorySafety;
import models.Agent;
import models.PersonaFileVersion;
import models.PersonaFileVersion.Source;
import org.jspecify.annotations.Nullable;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import utils.AppClock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * JF-136: which version of each persona file the prompt reads. The newest approved {@link PersonaFileVersion} of a
 * file is what the agent gets; a file on disk that differs from it waits for the operator, who approves or rejects
 * it on the agent page. An approval is recorded only where the writer can be trusted: the operator's save or
 * approval, JClaw's own template and system edits, an agent edit from a trusted turn, and the one-time approval of
 * the clean files that stood when approvals arrived. Anything else that changes the file on disk, an agent's
 * {@code exec}, a tool saving a download, an editor outside JClaw, waits.
 */
public final class PersonaFileApprovals {

    private PersonaFileApprovals() {}

    /** Versions kept per file, for undo and the operator's history. */
    static final int KEPT_VERSIONS = 20;

    /** An approved version, detached from the session so the cache can hold it. */
    public record Approved(long id, String content, String sha256, Source source, Instant approvedAt) {}

    /** What the prompt carries for one file: its approved text, and whether the file on disk waits for approval. */
    public record PromptFile(String text, boolean pending) {}

    /** How a reviewed change was settled: done, refused because the file changed since the operator looked, or no change waited. */
    public enum Outcome { DONE, STALE, NOTHING_PENDING }

    // Prompt assembly reads every persona file each turn; approvals change rarely and invalidate their own entry.
    private static final Cache<String, Optional<Approved>> approvedCache = Caches.named(
            "persona-approved",
            CacheConfig.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(1000).build());

    private static final Set<Long> seededOwners = ConcurrentHashMap.newKeySet();

    public static boolean isPersonaFile(String filename) {
        return WorkspaceFiles.PROTECTED_ROOT_FILES.contains(filename);
    }

    public static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The newest approved version of {@code filename} in the workspace {@code owner} owns, or null when none. */
    public static @Nullable Approved approved(Agent owner, String filename) {
        seed(owner);
        var key = owner.id + "/" + filename;
        var cached = approvedCache.getIfPresent(key);
        if (cached != null) return cached.orElse(null);
        var loaded = Tx.run(() -> {
            var history = PersonaFileVersion.history(owner.id, filename);
            return history.isEmpty() ? null : approvedOf(history.getFirst());
        });
        approvedCache.put(key, Optional.ofNullable(loaded));
        return loaded;
    }

    /**
     * The text of a file the prompt would read: the approved version in an agent's workspace, null when nothing was
     * approved; in a workspace no agent owns, which nothing approves, the file as it stands.
     */
    public static @Nullable String promptText(String agentName, String filename) {
        var owner = ownerOf(agentName);
        if (owner == null) return WorkspaceFiles.readWorkspaceFile(agentName, filename);
        var approved = approved(owner, filename);
        return approved == null ? null : approved.content();
    }

    /** What the prompt carries for {@code filename}, given the text on disk; a subagent reads its root's files. */
    public static PromptFile forPrompt(Agent agent, String filename, @Nullable String disk) {
        var approved = approved(WorkspaceFiles.workspaceOwner(agent), filename);
        var approvedText = approved == null ? "" : approved.content();
        return new PromptFile(approvedText, !approvedText.equals(disk == null ? "" : disk));
    }

    /** Records {@code content} as the approved version of {@code filename}; a no-op when it already is. */
    public static void approve(Agent owner, String filename, String content, Source source) {
        seed(owner);
        var sha = sha256(content);
        Tx.run(() -> {
            var history = PersonaFileVersion.history(owner.id, filename);
            if (!history.isEmpty() && history.getFirst().sha256.equals(sha)) return null;
            var version = new PersonaFileVersion();
            version.agent = Agent.findById(owner.id);
            version.filename = filename;
            version.content = content;
            version.sha256 = sha;
            version.source = source;
            version.approvedAt = AppClock.now();
            version.save();
            for (var old : history.stream().skip(KEPT_VERSIONS - 1L).toList()) old.delete();
            return null;
        });
        approvedCache.invalidate(owner.id + "/" + filename);
    }

    /** {@link #approve(Agent, String, String, Source)} for a workspace named by its agent; nothing when the agent is gone. */
    public static void approve(String agentName, String filename, String content, Source source) {
        var owner = ownerOf(agentName);
        if (owner != null) approve(owner, filename, content, source);
    }

    /**
     * Approves a file JClaw found already on disk, as the upgrade approves one: only when it has no approval yet and
     * passes the prompt-injection scan. A flagged one waits for the operator.
     */
    public static void approveFoundFile(String agentName, String filename) {
        var owner = ownerOf(agentName);
        if (owner == null || approved(owner, filename) != null) return;
        var content = textOnDisk(owner, filename);
        if (MemorySafety.injectionRule(content) == null) approve(owner, filename, content, Source.UPGRADE);
    }

    /**
     * Applies a JClaw edit to a persona file without approving an agent's pending change along with it: the edit
     * goes onto the approved text, which is approved, and onto the text on disk, which keeps any pending change.
     * {@code edit} gets the empty string for a file that is missing or was never approved.
     */
    public static void applySystemEdit(String agentName, String filename, UnaryOperator<String> edit) {
        var disk = WorkspaceFiles.readWorkspaceFileFresh(agentName, filename);
        var owner = ownerOf(agentName);
        if (owner == null) {
            var edited = edit.apply(disk == null ? "" : disk);
            if (!edited.equals(disk)) WorkspaceFiles.writeAgentFile(agentName, filename, edited);
            return;
        }
        var approved = approved(owner, filename);
        var approvedText = approved == null ? "" : approved.content();
        var diskText = disk == null ? "" : disk;
        var newApproved = edit.apply(approvedText);
        var newDisk = diskText.equals(approvedText) ? newApproved : edit.apply(diskText);
        if (!newDisk.equals(disk)) WorkspaceFiles.writeAgentFile(owner.name, filename, newDisk);
        approve(owner, filename, newApproved, Source.JCLAW);
    }

    /** Approves the text on disk, when it is still the text the operator reviewed ({@code seenSha256}). */
    public static Outcome approvePending(Agent owner, String filename, String seenSha256) {
        var disk = textOnDisk(owner, filename);
        if (!sha256(disk).equals(seenSha256)) return Outcome.STALE;
        var approved = approved(owner, filename);
        if (approved != null && approved.content().equals(disk)) return Outcome.NOTHING_PENDING;
        approve(owner, filename, disk, Source.OPERATOR);
        return Outcome.DONE;
    }

    /** Writes the approved version back over the text on disk, when that is still the text the operator reviewed. */
    public static Outcome rejectPending(Agent owner, String filename, String seenSha256) {
        var disk = textOnDisk(owner, filename);
        if (!sha256(disk).equals(seenSha256)) return Outcome.STALE;
        var approved = approved(owner, filename);
        var approvedText = approved == null ? "" : approved.content();
        if (approvedText.equals(disk)) return Outcome.NOTHING_PENDING;
        WorkspaceFiles.writeAgentFile(owner.name, filename, approvedText);
        return Outcome.DONE;
    }

    /**
     * Takes back an agent edit that applied on its own: the version before it is written to disk and approved
     * again, when the agent's version ({@code seenSha256}) is still the newest and still on disk.
     */
    public static Outcome undoAgentEdit(Agent owner, String filename, String seenSha256) {
        var history = Tx.run(() -> PersonaFileVersion.history(owner.id, filename).stream().limit(2)
                .map(PersonaFileApprovals::approvedOf).toList());
        if (history.size() < 2 || history.getFirst().source() != Source.AGENT) return Outcome.NOTHING_PENDING;
        var agentVersion = history.getFirst();
        if (!agentVersion.sha256().equals(seenSha256) || !textOnDisk(owner, filename).equals(agentVersion.content())) {
            return Outcome.STALE;
        }
        var previous = history.get(1).content();
        WorkspaceFiles.writeAgentFile(owner.name, filename, previous);
        approve(owner, filename, previous, Source.OPERATOR);
        return Outcome.DONE;
    }

    /** The two newest versions of a file, newest first, for the agent page's undo. */
    public static List<Approved> latestTwo(Agent owner, String filename) {
        seed(owner);
        return Tx.run(() -> PersonaFileVersion.history(owner.id, filename).stream().limit(2)
                .map(PersonaFileApprovals::approvedOf).toList());
    }

    /** The text on disk now, empty for a missing file, read past the 30-second file cache. */
    public static String textOnDisk(Agent owner, String filename) {
        var disk = WorkspaceFiles.readWorkspaceFileFresh(owner.name, filename);
        return disk == null ? "" : disk;
    }

    /**
     * Approves, once per agent, the persona files that stood before approvals arrived: each file that passes the
     * prompt-injection scan as it is. A flagged file stays unapproved and waits for the operator.
     */
    private static void seed(Agent owner) {
        if (owner.id == null || seededOwners.contains(owner.id)) return;
        synchronized (PersonaFileApprovals.class) {
            if (seededOwners.contains(owner.id)) return;
            Tx.run(() -> {
                Agent fresh = Agent.findById(owner.id);
                if (fresh == null || fresh.personaFilesSeeded) return null;
                for (var filename : WorkspaceFiles.personaFiles(fresh)) {
                    if (!PersonaFileVersion.history(fresh.id, filename).isEmpty()) continue;
                    var disk = WorkspaceFiles.readWorkspaceFileFresh(fresh.name, filename);
                    var content = disk == null ? "" : disk;
                    if (MemorySafety.injectionRule(content) != null) continue;
                    var version = new PersonaFileVersion();
                    version.agent = fresh;
                    version.filename = filename;
                    version.content = content;
                    version.sha256 = sha256(content);
                    version.source = Source.UPGRADE;
                    version.approvedAt = AppClock.now();
                    version.save();
                }
                fresh.personaFilesSeeded = true;
                fresh.save();
                return null;
            });
            seededOwners.add(owner.id);
        }
    }

    private static @Nullable Agent ownerOf(String agentName) {
        return Tx.run(() -> {
            Agent agent = Agent.findByName(agentName);
            return agent == null ? null : WorkspaceFiles.workspaceOwner(agent);
        });
    }

    private static Approved approvedOf(PersonaFileVersion version) {
        return new Approved(version.id, version.content, version.sha256, version.source, version.approvedAt);
    }
}
