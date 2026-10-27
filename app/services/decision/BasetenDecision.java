package services.decision;

import org.jspecify.annotations.Nullable;
import services.ConfigService;
import utils.CircuitBreaker;

import java.util.List;

/**
 * Baseten's hosted decision models as a decision provider (JF-133): elephant-1 and mammoth-1 answer
 * {@code POST /v1/systemone} with the body JEV takes, through {@link JevApi} under their own breaker. Like JEV, the
 * endpoint is Baseten's own and the operator sets only a key.
 */
public final class BasetenDecision {

    /** The router's classifier provider id; not {@code baseten}, which an operator may give a Baseten LLM provider. */
    public static final String PROVIDER = "baseten-decision";
    public static final String API_KEY = "decision.baseten.apiKey";
    public static final String BREAKER_NAME = "decision:baseten";
    static final String ENDPOINT = "https://inference.baseten.co/v1/systemone";

    /** A model Baseten serves: {@code id} is what its API takes, {@code name} what Baseten calls it. */
    public record Model(String id, String name) {}

    /**
     * From Baseten's decision-models page. {@code BasetenDecisionTest} holds them equal to {@code BASETEN_MODELS} in
     * {@code frontend/utils/decision-models.ts}, which also gives each one's context and size.
     */
    public static final List<Model> MODELS = List.of(
            new Model("decision-models/baseten-elephant-9b-latest", "elephant-1"),
            new Model("decision-models/baseten-mammoth-27b-latest", "mammoth-1"));

    private BasetenDecision() {}

    /** The operator's Baseten key; null when unset. */
    public static @Nullable String apiKey() {
        var key = ConfigService.get(API_KEY);
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** What Baseten calls the model with {@code id}, or {@code id} itself when it is none of {@link #MODELS}. */
    public static String name(String id) {
        return MODELS.stream().filter(m -> m.id().equals(id)).map(Model::name).findFirst().orElse(id);
    }

    /** Baseten's endpoint with {@code apiKey}, on the plain client: the address is Baseten's, never the operator's. */
    public static JevApi.Target target(String apiKey) {
        return new JevApi.Target("Baseten", "Baseten", "Baseten", ENDPOINT, apiKey, BREAKER_NAME, false, _ -> false);
    }

    /** The provider's one breaker, whatever the model. */
    public static CircuitBreaker breaker() {
        return JevApi.breaker(target(""));
    }

    /** Whether {@code id} is the ID of one of {@link #MODELS}. */
    public static boolean isModel(String id) {
        return MODELS.stream().anyMatch(m -> m.id().equals(id));
    }
}
