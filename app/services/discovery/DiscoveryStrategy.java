package services.discovery;


import java.util.Map;

/**
 * One model-catalog discovery protocol. Mirrors the {@code llm.LlmProvider}
 * hierarchy: a sealed set of implementations behind a declarative provider-name
 * registry, so adding a protocol is a new subtype plus one {@link RegistryHolder}
 * entry rather than an edit to {@code ModelDiscoveryService.discover}.
 */
public sealed interface DiscoveryStrategy
        permits OllamaDiscoveryStrategy, LmStudioDiscoveryStrategy, OpenAiCompatDiscoveryStrategy {

    int DISCOVER_TIMEOUT_SECONDS = 15;

    /**
     * Fetch a provider's model catalog and normalize it to the shared model-map shape.
     *
     * @param providerName configured provider name; leaderboard config is keyed on it
     * @param baseUrl      provider base URL, already SSRF-screened by the caller
     * @param apiKey       provider credential; may be null for unauthenticated local backends
     * @return the discovered models, or a {@link DiscoveryResult.Error}
     */
    DiscoveryResult discover(String providerName, String baseUrl, String apiKey);

    /**
     * Held in a nested class (initialization-on-demand idiom) so the subtype
     * constructors don't run during this interface's own initialization — the same
     * S2390 dodge {@code LlmProvider.FactoryHolder} uses.
     */
    final class RegistryHolder {
        static final Map<String, DiscoveryStrategy> BY_NAME_SUBSTRING = Map.of(
                "ollama", new OllamaDiscoveryStrategy(),
                "lm-studio", new LmStudioDiscoveryStrategy());

        static final DiscoveryStrategy OPENAI_COMPAT = new OpenAiCompatDiscoveryStrategy();

        private RegistryHolder() {}
    }

    /**
     * Resolve the protocol for a provider name by substring match (so
     * {@code ollama-cloud} and {@code ollama-local} both land on the native Ollama
     * path), falling back to the OpenAI-compatible endpoint for anything unclaimed.
     */
    static DiscoveryStrategy forProvider(String providerName) {
        var lower = providerName == null ? "" : providerName.toLowerCase();
        for (var entry : RegistryHolder.BY_NAME_SUBSTRING.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return RegistryHolder.OPENAI_COMPAT;
    }
}
