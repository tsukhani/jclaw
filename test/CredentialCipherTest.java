import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import utils.CredentialCipher;
import utils.CredentialCipher.Source;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** JCLAW-1455: the cipher every credential store reaches. Secrets here are fixtures, not any instance's. */
class CredentialCipherTest extends UnitTest {

    private static final String FIELD = "config.provider.example.apiKey";
    private static final CredentialCipher ONE = CredentialCipher.of("jclaw1455-test-secret-one", null);
    private static final CredentialCipher TWO = CredentialCipher.of("jclaw1455-test-secret-two", null);

    @Test
    void aValueRoundTrips() {
        for (var plaintext : new String[] {"fake-credential", "", "fäke-crédential-✓", "x".repeat(512)}) {
            var opened = ONE.open(FIELD, ONE.encrypt(FIELD, plaintext));
            assertEquals(Source.CURRENT, opened.source(), plaintext);
            assertEquals(plaintext, opened.plaintext());
        }
    }

    @Test
    void theStoredFormIsThePrefixThenBase64OfIvCiphertextAndTag() {
        var plaintext = "fake-credential";
        var stored = ONE.encrypt(FIELD, plaintext);
        assertTrue(stored.startsWith("enc:v1:"), stored);
        assertTrue(CredentialCipher.isEncrypted(stored));
        assertFalse(stored.contains(plaintext));
        var bytes = Base64.getDecoder().decode(stored.substring("enc:v1:".length()));
        assertEquals(12 + plaintext.length() + 16, bytes.length, "a 96-bit IV, the ciphertext and a 128-bit tag");
    }

    @Test
    void everyValueGetsItsOwnIv() {
        var first = Base64.getDecoder().decode(ONE.encrypt(FIELD, "fake-credential").substring(7));
        var second = Base64.getDecoder().decode(ONE.encrypt(FIELD, "fake-credential").substring(7));
        assertFalse(Arrays.equals(Arrays.copyOf(first, 12), Arrays.copyOf(second, 12)));
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void aCiphertextMovedToAnotherFieldDoesNotOpen() {
        var stored = ONE.encrypt(FIELD, "fake-credential");
        var opened = ONE.open("config.provider.other.apiKey", stored);
        assertEquals(Source.UNREADABLE, opened.source());
        assertNull(opened.plaintext());
        assertFalse(opened.readable());
    }

    @Test
    void anotherSecretCannotOpenIt() {
        var opened = TWO.open(FIELD, ONE.encrypt(FIELD, "fake-credential"));
        assertEquals(Source.UNREADABLE, opened.source());
        assertNull(opened.plaintext());
    }

    @Test
    void thePreviousSecretOpensWhatItSealedAndTheCurrentOneSealsAnew() {
        var rotated = CredentialCipher.of("jclaw1455-test-secret-two", "jclaw1455-test-secret-one");
        assertTrue(rotated.hasPrevious());
        assertFalse(TWO.hasPrevious());

        var opened = rotated.open(FIELD, ONE.encrypt(FIELD, "fake-credential"));
        assertEquals(Source.PREVIOUS, opened.source(), "the caller re-encrypts a value only the previous secret opens");
        assertEquals("fake-credential", opened.plaintext());

        var resealed = rotated.encrypt(FIELD, "fake-credential");
        assertEquals(Source.CURRENT, TWO.open(FIELD, resealed).source(), "a new value is sealed under the current secret");
        assertEquals(Source.UNREADABLE, ONE.open(FIELD, resealed).source());
        assertEquals(Source.CURRENT, rotated.open(FIELD, resealed).source());
    }

    @Test
    void aValueWithoutThePrefixIsPlaintext() {
        var opened = ONE.open(FIELD, "sk-written-before-encryption");
        assertEquals(Source.PLAINTEXT, opened.source());
        assertEquals("sk-written-before-encryption", opened.plaintext());
        assertFalse(CredentialCipher.isEncrypted("sk-written-before-encryption"));
        assertFalse(CredentialCipher.isEncrypted(null));
    }

    @Test
    void aDamagedValueIsUnreadableRatherThanAnException() {
        var stored = ONE.encrypt(FIELD, "fake-credential");
        var bytes = Base64.getDecoder().decode(stored.substring(7));
        bytes[bytes.length - 1] ^= 1;
        var tampered = "enc:v1:" + Base64.getEncoder().encodeToString(bytes);
        var tooShort = "enc:v1:" + Base64.getEncoder().encodeToString(new byte[27]);
        for (var damaged : new String[] {tampered, tooShort, "enc:v1:", "enc:v1:not base64!", stored.substring(0, 20)}) {
            assertEquals(Source.UNREADABLE, ONE.open(FIELD, damaged).source(), damaged);
        }
        assertThrows(IllegalStateException.class, () -> ONE.open(FIELD, tampered).resolvedPlaintext());
    }

    @Test
    void theLookupIsDeterministicPerSecretAndFollowsTheRotation() {
        var lookup = ONE.lookup("fake-bot-token");
        assertEquals(lookup, ONE.lookup("fake-bot-token"));
        assertEquals(64, lookup.length());
        assertNotEquals(lookup, ONE.lookup("fake-bot-token-2"));
        assertNotEquals(lookup, TWO.lookup("fake-bot-token"));

        var rotated = CredentialCipher.of("jclaw1455-test-secret-two", "jclaw1455-test-secret-one");
        assertEquals(TWO.lookup("fake-bot-token"), rotated.lookup("fake-bot-token"));
        assertEquals(lookup, rotated.previousLookup("fake-bot-token"));
        assertNull(ONE.previousLookup("fake-bot-token"));
        assertEquals(List.of(TWO.lookup("fake-bot-token"), lookup), rotated.lookups("fake-bot-token"));
        assertEquals(List.of(lookup), ONE.lookups("fake-bot-token"));
    }

    @Test
    void valuesStoredByThisVersionStillOpen() {
        // Computed outside the class (RFC 5869 HKDF by hand, then AES-GCM with a fixed IV): a change to
        // the derivation or the format would strand every stored credential.
        var cipher = CredentialCipher.of("jclaw1455-known-answer-secret", null);
        var stored = "enc:v1:AAECAwQFBgcICQoL9qdZQ3G70l9JMyG1Gyir5ghoxF3Anv89/n0fdgxC/g==";
        assertEquals("fake-credential", cipher.open("config.provider.example.apiKey", stored).plaintext());
        assertEquals("e4d619256cf91c35dcb01c61d5ee5d98a32104b8a2d67136524250064d1c9f46",
                cipher.lookup("fake-bot-token"));
    }

    @Test
    void anEmptySecretIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CredentialCipher.of(" ", null));
    }

    @Test
    void theInstallationCipherIsDerivedOnceFromTheApplicationSecret() {
        var cipher = CredentialCipher.current();
        assertSame(cipher, CredentialCipher.current(), "the keys are not re-derived per read");
        var stored = cipher.encrypt(FIELD, "fake-credential");
        assertEquals(Source.CURRENT,
                CredentialCipher.of(Play.secretKey, null).open(FIELD, stored).source());
    }
}
