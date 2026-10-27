package agents;

import memory.MemorySafety;
import models.Agent;
import models.Conversation;
import models.PersonaFileVersion.Source;
import org.jspecify.annotations.Nullable;
import services.ConversationService;
import services.PersonaFileApprovals;
import services.Tx;
import services.WorkspaceFiles;

import java.nio.file.Path;

/**
 * JF-136: what happens when the agent writes one of its persona files with the filesystem tool. From a trusted turn,
 * passing the prompt-injection scan, the text it wrote is approved at once; otherwise it waits for the operator on
 * the agent page. Writes by any other way, {@code exec} among them, are never approved here, so they wait too.
 */
public final class PersonaFileEdits {

    private PersonaFileEdits() {}

    static final String APPLIED = "%s is saved and takes effect from your next turn.";
    static final String WAITING = "%s is saved, but it waits for the operator's approval before it reaches your prompt."
            + " Do not write it again.";

    /** The persona file {@code target} is, when it sits at the root of {@code workspace}; null otherwise. */
    public static @Nullable String personaFile(Path workspace, Path target) {
        var name = target.getFileName();
        var parent = target.toAbsolutePath().normalize().getParent();
        if (name == null || parent == null || !parent.equals(workspace.toAbsolutePath().normalize())) return null;
        return PersonaFileApprovals.isPersonaFile(name.toString()) ? name.toString() : null;
    }

    /** The persona file's text on disk now, in the workspace {@code agent} reads. */
    public static String textOnDisk(Agent agent, String filename) {
        return PersonaFileApprovals.textOnDisk(WorkspaceFiles.workspaceOwner(agent), filename);
    }

    /**
     * The note for the tool's result after the agent wrote {@code filename}. The text is approved only when it is
     * exactly what the write made ({@code expected}, null when that cannot be told), when an append or edit built on
     * the approved version rather than on a change still waiting ({@code wholeFile} replaces everything), from a
     * trusted turn, and passing the scan.
     */
    public static String afterWrite(Agent agent, String filename, boolean wholeFile, String before,
                                    @Nullable String expected) {
        var owner = WorkspaceFiles.workspaceOwner(agent);
        var after = PersonaFileApprovals.textOnDisk(owner, filename);
        var approved = PersonaFileApprovals.approved(owner, filename);
        boolean builtOnApproved = wholeFile || before.equals(approved == null ? "" : approved.content());
        if (after.equals(expected) && builtOnApproved && trustedTurn(agent) && MemorySafety.injectionRule(after) == null) {
            PersonaFileApprovals.approve(owner, filename, after, Source.AGENT);
            return APPLIED.formatted(filename);
        }
        return WAITING.formatted(filename);
    }

    /**
     * Whether an edit in this turn may apply without the operator: the operator's own turn, in a chat only they
     * read, by the agent itself rather than a subagent or a task fire, in a conversation that never read outside
     * text, a memory resting on someone else's words among it. Text the operator pastes counts as theirs.
     */
    public static boolean trustedTurn(Agent agent) {
        if (agent.parentAgent != null || ToolContext.taskRunId() != null) return false;
        var conversationId = ToolContext.conversationId();
        if (conversationId == null || ConversationService.hasReadOutsideContent(conversationId)) return false;
        var channel = Tx.run(() -> {
            Conversation conversation = Conversation.findById(conversationId);
            return conversation == null ? null : new Channel(conversation.channelType, conversation.peerId);
        });
        return channel != null && DangerousActionGate.ownerReadsAlone(conversationId, channel.type(), channel.peerId());
    }

    private record Channel(String type, @Nullable String peerId) {}
}
