package llm.routing;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the router chose for one turn and why (JCLAW-1222).
 *
 * @param effort      how hard a reasoning model should think, before it is fitted to the chosen model's ladder
 * @param fallback    the next-best candidate on a different provider, or null when none is eligible
 * @param signals     what the classifier matched
 * @param skipped     candidates passed over, each with its reason
 * @param downshifted a prepaid provider's usage pushed this class onto the chat list
 * @param sticky      the previous turn's model was kept to preserve its prompt cache
 * @param relaxed     every candidate was filtered out, so filters were dropped rather than fail the turn
 */
public record RouteDecision(TaskClass taskClass, ReasoningEffort effort, Target primary, @Nullable Target fallback,
                            List<String> signals, List<String> skipped,
                            boolean downshifted, boolean sticky, boolean relaxed) {

    /** A concrete provider and model. */
    public record Target(String provider, String modelId) {
        public String describe() {
            return provider + "/" + modelId;
        }
    }

    public RouteDecision {
        signals = List.copyOf(signals);
        skipped = List.copyOf(skipped);
    }

    /** One line for the operator: the classifier's signals plus whatever bent the choice. */
    public String reason() {
        var sb = new StringBuilder(String.join("; ", signals));
        if (downshifted) sb.append("; downshifted by budget");
        if (sticky) sb.append("; kept previous model");
        if (relaxed) sb.append("; no candidate passed every filter");
        return sb.toString();
    }

    public String logLine() {
        var sb = new StringBuilder("%s → %s [%s]".formatted(taskClass.id(), primary.describe(), reason()));
        if (fallback != null) sb.append(" fallback: ").append(fallback.describe());
        if (!skipped.isEmpty()) sb.append(" skipped: ").append(String.join("; ", skipped));
        return sb.toString();
    }

    /**
     * The route as the chat UI and the usage record read it. {@code served} is the model that actually
     * answered, which differs from {@link #primary} once a failover has promoted the fallback.
     */
    public JsonObject toJson(Target served, boolean failover) {
        var json = new JsonObject();
        json.addProperty("class", taskClass.id());
        json.addProperty("provider", served.provider());
        json.addProperty("model", served.modelId());
        json.addProperty("reason", reason());
        if (downshifted) json.addProperty("downshifted", true);
        if (sticky) json.addProperty("sticky", true);
        if (failover) json.addProperty("failover", true);
        return json;
    }
}
