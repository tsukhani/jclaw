package memory;

import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Where a memory came from, recorded at every write (JCLAW-1318).
 *
 * <p>PROV-O mapping:
 * <table>
 *   <caption>PROV-O terms and the memory fields that carry them</caption>
 *   <tr><th>PROV-O</th><th>Memory</th></tr>
 *   <tr><td>{@code prov:Entity}</td><td>the memory</td></tr>
 *   <tr><td>{@code prov:wasAttributedTo}</td><td>{@code actor}</td></tr>
 *   <tr><td>{@code prov:wasGeneratedBy}</td><td>the capture activity, whose kind is the actor
 *       prefix (extractor, human, guest, process)</td></tr>
 *   <tr><td>{@code prov:hadPrimarySource}</td><td>{@code sourceConversationId} /
 *       {@code sourceMessageId}</td></tr>
 *   <tr><td>{@code prov:wasDerivedFrom}</td><td>{@code MemoryDerivation}</td></tr>
 *   <tr><td>{@code prov:wasInvalidatedBy}</td><td>{@code supersededAt} (supersession), with
 *       {@code prov:wasDerivedFrom} from the newer fact given by {@code supersededById}</td></tr>
 * </table>
 *
 * @param derivedFrom ids of the memories this one is derived from; non-empty exactly when
 *                    {@code authorType} is {@link MemoryAuthorType#CONSOLIDATION_DERIVED}
 * @throws IllegalArgumentException when {@code derivedFrom} and {@code authorType} disagree
 */
public record MemoryProvenance(@Nullable Long sourceConversationId, @Nullable Long sourceMessageId,
                               String actor, MemoryAuthorType authorType, List<Long> derivedFrom) {

    /** The single operator; there is no user entity to name a person from. */
    public static final String OPERATOR_ACTOR = "human:operator";

    public static final String HUMAN_ACTOR_PREFIX = "human:";

    /** A non-operator human on a channel; never {@code human:}, so it is never firm on its own. */
    public static final String GUEST_ACTOR_PREFIX = "guest:";

    static final String SUBAGENT_ACTOR_PREFIX = "process:subagent/";

    public MemoryProvenance {
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(new LinkedHashSet<>(derivedFrom));
        if (!derivedFrom.isEmpty() && authorType != MemoryAuthorType.CONSOLIDATION_DERIVED) {
            throw new IllegalArgumentException(
                    "A memory derived from other memories must be CONSOLIDATION_DERIVED, not " + authorType);
        }
        if (derivedFrom.isEmpty() && authorType == MemoryAuthorType.CONSOLIDATION_DERIVED) {
            throw new IllegalArgumentException("A CONSOLIDATION_DERIVED memory must name its inputs");
        }
    }

    public boolean derived() {
        return !derivedFrom.isEmpty();
    }

    /** Auto-capture by {@code modelId}, with no source turn yet — see {@link #withSource}. */
    public static MemoryProvenance extractor(String modelId) {
        return extractor(modelId, MemoryAuthorType.HUMAN_TURN);
    }

    /** As {@link #extractor(String)}, for a turn whose speaker is {@code authorType}. */
    public static MemoryProvenance extractor(String modelId, MemoryAuthorType authorType) {
        return new MemoryProvenance(null, null, extractorActor(modelId), authorType, List.of());
    }

    public static String extractorActor(String modelId) {
        return "extractor/" + modelId;
    }

    /** The actor string for an automated writer. */
    public static String process(String id) {
        return "process:" + id;
    }

    /** The actor string for a guest speaking on {@code channelType}. */
    public static String guest(String channelType) {
        return GUEST_ACTOR_PREFIX + channelType;
    }

    /** The actor string for a subagent, by its immutable id (JCLAW-531). */
    public static String subagent(Long agentId) {
        return SUBAGENT_ACTOR_PREFIX + agentId;
    }

    /** Whether restating an existing row under this provenance may raise its corroboration count. */
    public boolean mayCorroborate() {
        return authorType != MemoryAuthorType.GUEST_TURN && !actor.startsWith(SUBAGENT_ACTOR_PREFIX);
    }

    public MemoryProvenance withSource(@Nullable Long conversationId, @Nullable Long messageId) {
        return new MemoryProvenance(conversationId, messageId, actor, authorType, derivedFrom);
    }
}
