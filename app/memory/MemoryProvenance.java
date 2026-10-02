package memory;

import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

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
 *       prefix (extractor, human, process)</td></tr>
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

    public MemoryProvenance {
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
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
        return new MemoryProvenance(null, null, extractorActor(modelId), MemoryAuthorType.HUMAN_TURN, List.of());
    }

    public static String extractorActor(String modelId) {
        return "extractor/" + modelId;
    }

    /** The actor string for an automated writer. */
    public static String process(String id) {
        return "process:" + id;
    }

    public MemoryProvenance withSource(@Nullable Long conversationId, @Nullable Long messageId) {
        return new MemoryProvenance(conversationId, messageId, actor, authorType, derivedFrom);
    }
}
