import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import llm.PaymentModality;

class PaymentModalityTest extends UnitTest {

    @Test
    void parseOrDefaultReturnsDefaultForNullValue() {
        // Null → defaultFor branch.
        var result = PaymentModality.parseOrDefault(null, "openrouter");
        assertNotNull(result, "default modality must be non-null for known provider");
    }

    @Test
    void parseOrDefaultReturnsDefaultForBlankValue() {
        var result = PaymentModality.parseOrDefault("   ", "openrouter");
        assertNotNull(result);
    }

    @Test
    void parseOrDefaultParsesValidEnumName() {
        // Happy path: a real enum name round-trips through valueOf.
        var result = PaymentModality.parseOrDefault("PER_TOKEN", "openrouter");
        assertEquals(PaymentModality.PER_TOKEN, result);
    }

    @Test
    void parseOrDefaultIsCaseInsensitive() {
        // Implementation trims + toUpperCase.
        var result = PaymentModality.parseOrDefault(" per_token ", "openrouter");
        assertEquals(PaymentModality.PER_TOKEN, result);
    }

    @Test
    void parseOrDefaultReturnsDefaultForUnrecognizedValue() {
        // IllegalArgumentException catch path.
        var result = PaymentModality.parseOrDefault("not-a-real-modality", "openrouter");
        assertNotNull(result, "unrecognized value must fall back, not return null");
    }

    @Test
    void parseOrDefaultUsesUnknownProviderDefault() {
        // defaultFor with a provider name that has no specific default still
        // returns a sane modality (the catch-all default).
        var result = PaymentModality.parseOrDefault(null, "completely-unknown-provider");
        assertNotNull(result);
    }

    @Test
    void vllmIsLocalWithNoBillingModality() {
        // vLLM is a self-hosted/local provider — free at point of use, so it carries no payment
        // modality (mirrors ollama-local / lm-studio) and the Settings UI skips its billing row.
        assertTrue(PaymentModality.supportedFor("vllm").isEmpty(),
                "vllm must be classified local (empty modality set)");
        assertTrue(PaymentModality.supportedFor("ollama-local").isEmpty(), "sanity: ollama-local is local too");
    }
}
