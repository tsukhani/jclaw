package tools;

import org.jspecify.annotations.Nullable;

/**
 * The provider/model an {@code runtime=acp} run is pinned to instead of the harness's own
 * default — from the spawn's {@code modelProvider}/{@code modelId} args or the Settings
 * default ({@link SubagentSpawnTool#ACP_MODEL_PROVIDER_KEY}/{@link SubagentSpawnTool#ACP_MODEL_ID_KEY}).
 * A model-only override carries a null provider: the harness keeps its own endpoint and
 * credentials and only the model changes. {@code baseUrl}/{@code apiKey} are the named
 * JClaw provider's, resolved at spawn time.
 */
public record HarnessModel(@Nullable String providerName, @Nullable String baseUrl,
                           @Nullable String apiKey, String modelId) {

    /** A model-only override keeps the harness on its own endpoint. */
    public boolean hasEndpoint() {
        return providerName != null;
    }

    /** The named provider's endpoint; valid only when {@link #hasEndpoint()} and the provider resolved. */
    public String resolvedBaseUrl() {
        if (baseUrl == null) {
            throw new IllegalStateException("provider '" + providerName + "' did not resolve to an endpoint");
        }
        return baseUrl;
    }

    /** {@code provider / model}, or the bare model id for a model-only override. */
    public String label() {
        return providerName == null ? modelId : providerName + " / " + modelId;
    }
}
