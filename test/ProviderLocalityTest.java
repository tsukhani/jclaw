import llm.ProviderLocality;
import memory.MemoryVectorSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

/**
 * JCLAW-939: what counts as a provider running on the operator's own machine.
 *
 * <p>This decides whether memory text is allowed to leave the machine, so the interesting
 * cases are the ones that must be refused — a hostname that merely looks local, and a
 * public address behind a locally-named provider.
 *
 * <p>JCLAW-1102 adds the operator's Remote/Local classification, so the second half asserts
 * the two halves stay separable: declaring a provider self-hosted must not reclassify its
 * address.
 */
class ProviderLocalityTest extends UnitTest {

    /** Unique to this class — config rows are shared state and the suite runs concurrently. */
    private static final String PROVIDER = "jclaw1102-vpn-probe";
    private static final String BASE_URL_KEY = "provider." + PROVIDER + ".baseUrl";
    private static final String DECLARED_KEY = "provider." + PROVIDER + ProviderLocality.DECLARED_LOCAL_SUFFIX;

    /** A Tailscale address: RFC 6598 carrier-NAT space, which no URL check can call local. */
    private static final String TAILNET_URL = "http://100.108.220.119:8080/v1";

    @AfterEach
    void clearProbeKeys() {
        ConfigService.delete(BASE_URL_KEY);
        ConfigService.delete(DECLARED_KEY);
    }

    @Test
    void loopbackAndPrivateAddressesAreLocal() {
        assertTrue(ProviderLocality.isLocalUrl("http://localhost:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://127.0.0.1:1234/v1"));
        assertTrue(ProviderLocality.isLocalUrl("http://[::1]:8080/v1"));
        assertTrue(ProviderLocality.isLocalUrl("http://192.168.1.50:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://10.0.0.4:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://172.16.5.9:11434"));
        assertTrue(ProviderLocality.isLocalUrl("http://mac-studio.local:1234/v1"));
        assertTrue(ProviderLocality.isLocalUrl("http://host.docker.internal:11434"));
    }

    @Test
    void ipv6UniqueLocalIsLocal() {
        // Java's isSiteLocalAddress only covers the deprecated fec0::/10 for v6, so
        // fc00::/7 has to be recognized separately or a ULA host reads as remote.
        assertTrue(ProviderLocality.isLocalUrl("http://[fd12:3456:789a::1]:11434"));
    }

    @Test
    void publicAddressesAreNotLocal() {
        assertFalse(ProviderLocality.isLocalUrl("https://api.openai.com/v1"));
        assertFalse(ProviderLocality.isLocalUrl("https://ollama.com/api"));
        assertFalse(ProviderLocality.isLocalUrl("http://8.8.8.8:11434"));
    }

    @Test
    void aHostnameThatMerelyLooksLocalIsRefused() {
        // The whole guarantee is that memory text stays on the machine. Hostnames are
        // never resolved here, so anything that is not local by definition or by literal
        // address has to fail closed — otherwise "localhost.attacker.com" would pass, and
        // a name that resolves to loopback today could resolve anywhere tomorrow.
        assertFalse(ProviderLocality.isLocalUrl("http://localhost.example.com/v1"));
        assertFalse(ProviderLocality.isLocalUrl("http://not-localhost/v1"));
        assertFalse(ProviderLocality.isLocalUrl("http://my-local-llm.example.com/v1"));
    }

    @Test
    void malformedAndMissingUrlsAreNotLocal() {
        assertFalse(ProviderLocality.isLocalUrl(null));
        assertFalse(ProviderLocality.isLocalUrl(""));
        assertFalse(ProviderLocality.isLocalUrl("   "));
        assertFalse(ProviderLocality.isLocalUrl("not a url"));
        assertFalse(ProviderLocality.isLocalUrl("file:///etc/passwd"));
    }

    @Test
    void carrierNatIsNotLocalByAddress() {
        // SsrfGuard refuses 100.64/10 as reaching the ISP's own equipment; the two files
        // have to agree that the range says nothing about ownership.
        assertFalse(ProviderLocality.isLocalUrl(TAILNET_URL));
        assertFalse(ProviderLocality.isLocalUrl("http://100.64.0.1:8080/v1"));
    }

    @Test
    void theDeclarationMakesAProviderReachedOverAVpnLocal() {
        ConfigService.set(BASE_URL_KEY, TAILNET_URL);
        assertFalse(ProviderLocality.isLocal(PROVIDER));

        ConfigService.set(DECLARED_KEY, "true");

        assertTrue(ProviderLocality.isLocal(PROVIDER));
        // The address is unchanged and still says nothing — isLocalUrl is a separate question.
        assertFalse(ProviderLocality.isLocalUrl(TAILNET_URL));
    }

    @Test
    void anAbsentFalseOrMalformedDeclarationReadsAsRemote() {
        ConfigService.set(BASE_URL_KEY, TAILNET_URL);
        assertFalse(ProviderLocality.isLocal(PROVIDER), "absent means remote");

        ConfigService.set(DECLARED_KEY, "false");
        assertFalse(ProviderLocality.isLocal(PROVIDER));

        // Boolean.parseBoolean maps anything unrecognized to false, so a value that slipped
        // past validation fails closed rather than opening the gate.
        ConfigService.set(DECLARED_KEY, "yes");
        assertFalse(ProviderLocality.isLocal(PROVIDER));
    }

    @Test
    void aLoopbackUrlIsNotEnoughOnItsOwn() {
        // The case this rule exists for: a cloud API behind a local proxy has a loopback
        // address and a remote model. If the address could grant locality on its own, that
        // provider would be offered for embedding and the corpus would go straight through it.
        ConfigService.set(BASE_URL_KEY, "http://localhost:3000/v1");

        assertTrue(ProviderLocality.isLocalUrl("http://localhost:3000/v1"), "the address is local");
        assertFalse(ProviderLocality.isLocal(PROVIDER), "but the operator never declared it theirs");

        ConfigService.set(DECLARED_KEY, "true");
        assertTrue(ProviderLocality.isLocal(PROVIDER));
    }

    @Test
    void theVectorProviderGateFollowsTheDeclaration() {
        ConfigService.set(BASE_URL_KEY, TAILNET_URL);

        var refused = ConfigService.setWithSideEffects(MemoryVectorSettings.KEY_PROVIDER, PROVIDER);
        assertNotNull(refused, "a remote provider must be refused — POST /api/config reaches this key directly");
        assertTrue(refused.contains("is not local"), refused);

        ConfigService.set(DECLARED_KEY, "true");

        assertNull(ConfigService.setWithSideEffects(MemoryVectorSettings.KEY_PROVIDER, PROVIDER),
                "the classification is the whole point: a self-hosted provider has to be accepted here");
    }

    @Test
    void aMalformedDeclarationIsRefusedAtTheWrite() {
        // Validated at write because the read fails closed silently: an operator who typed
        // "yes" would see embeddings keep refusing a provider they believe they declared local.
        assertNotNull(ConfigService.setWithSideEffects(DECLARED_KEY, "yes"));
        assertNull(ConfigService.setWithSideEffects(DECLARED_KEY, "true"));
        assertNull(ConfigService.setWithSideEffects(DECLARED_KEY, "FALSE"));
    }
}
