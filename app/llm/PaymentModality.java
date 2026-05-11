package llm;

import java.util.Map;
import java.util.Set;

/**
 * Operator-paid billing shape for an LLM provider (JCLAW-280). Independent
 * of the provider's HTTP shape: two providers can share the same
 * {@link LlmProvider} subclass (Ollama Cloud and Ollama Local both use
 * {@link OllamaProvider}) and still bill differently.
 *
 * <p>Each configured provider has a set of {@linkplain #supportedFor
 * supported} modalities — what's plausible given who runs it and how — and
 * a single {@linkplain ProviderConfig#paymentModality selected} modality
 * the operator (or the default) picked. The cost dashboard reads the
 * selected modality to partition spend into per-token and subscription
 * subsections.
 *
 * <p>An empty supported-set is meaningful: it indicates the provider is
 * free at point of use (local inference, mock providers) and contributes
 * neither per-token nor subscription cost — the existing free-tier path.
 */
public enum PaymentModality {

    /** Operator pays per request, billed against the model's
     *  ${@code inputPricePerMillionTokens} / {@code outputPricePerMillionTokens}
     *  rates already in {@link ModelInfo}. */
    PER_TOKEN,

    /** Operator pays a fixed monthly fee regardless of usage. The fee is
     *  captured in {@link ProviderConfig#subscriptionMonthlyUsd}. */
    SUBSCRIPTION;

    /**
     * Canonical name → set of plausible modalities for that provider. Keys
     * are matched case-insensitively. An empty set marks the provider as
     * free-at-point-of-use (no payment modality applies).
     */
    private static final Map<String, Set<PaymentModality>> SUPPORTED = Map.ofEntries(
            Map.entry("openai", Set.of(PER_TOKEN, SUBSCRIPTION)),
            Map.entry("openrouter", Set.of(PER_TOKEN)),
            Map.entry("together", Set.of(PER_TOKEN)),
            Map.entry("ollama-cloud", Set.of(SUBSCRIPTION)),
            Map.entry("ollama-local", Set.of()),
            Map.entry("lm-studio", Set.of()),
            Map.entry("loadtest-mock", Set.of())
    );

    /**
     * Resolve the supported modalities for a configured provider name.
     * Returns an empty set when the name is unknown <i>and</i> doesn't
     * match any known prefix — the safer default since an empty set
     * preserves today's "free-tier" cost treatment rather than silently
     * inventing per-token charges for an unrecognized provider.
     */
    public static Set<PaymentModality> supportedFor(String providerName) {
        if (providerName == null) return Set.of();
        var lower = providerName.toLowerCase();
        var exact = SUPPORTED.get(lower);
        if (exact != null) return exact;
        return Set.of();
    }

    /**
     * The default modality for a provider when nothing is configured:
     * the single supported modality if there is exactly one, else
     * {@link #PER_TOKEN} as the safe default for an unconfigured
     * multi-modality provider (OpenAI ships PER_TOKEN by default; the
     * operator opts into subscription explicitly).
     */
    public static PaymentModality defaultFor(String providerName) {
        var supported = supportedFor(providerName);
        if (supported.size() == 1) return supported.iterator().next();
        return PER_TOKEN;
    }

    /** Parse a stored config value safely. Falls back to the provider's
     *  default when the stored value is null, blank, or unrecognized — the
     *  registry never refuses to load a provider over a malformed modality
     *  string. */
    public static PaymentModality parseOrDefault(String value, String providerName) {
        if (value == null || value.isBlank()) return defaultFor(providerName);
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException _) {
            return defaultFor(providerName);
        }
    }
}
