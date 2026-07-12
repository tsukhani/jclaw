import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.PasswordHasher;

class PasswordHasherTest extends UnitTest {

    @Test
    void verifyRejectsNullPlaintext() {
        // First guard in verify: plaintext == null → false.
        assertFalse(PasswordHasher.verify(null, "anything"));
    }

    @Test
    void verifyRejectsNullStoredHash() {
        // Second guard: stored == null → false.
        assertFalse(PasswordHasher.verify("password", null));
    }

    @Test
    void verifyRejectsHashWithWrongPartCount() {
        // Stored value with the wrong number of colon-delimited segments.
        assertFalse(PasswordHasher.verify("password", "only:three:parts"));
        assertFalse(PasswordHasher.verify("password", "one"));
        assertFalse(PasswordHasher.verify("password", "way:too:many:colons:here"));
    }

    @Test
    void verifyRejectsHashWithWrongPrefix() {
        // 4 parts but the leading prefix is not the expected scheme tag.
        assertFalse(PasswordHasher.verify("password",
                "wrong-prefix:100000:c2FsdA:aGFzaA"));
    }

    @Test
    void verifyRejectsHashWithUnparsableIterationCount() {
        // NumberFormatException catch path — iterations isn't an integer.
        // The actual scheme prefix is whatever PasswordHasher uses; build the
        // shape from a real hash by replacing the iteration count.
        var realHash = PasswordHasher.hash("anything");
        var parts = realHash.split(":");
        // Swap iteration count for a non-integer string.
        var bad = parts[0] + ":NaN:" + parts[2] + ":" + parts[3];
        assertFalse(PasswordHasher.verify("anything", bad));
    }

    @Test
    void verifyRejectsHashWithCorruptBase64() {
        // IllegalArgumentException catch — base64 decoder rejects the salt.
        var realHash = PasswordHasher.hash("anything");
        var parts = realHash.split(":");
        var bad = parts[0] + ":" + parts[1] + ":!!!not-base64!!!:" + parts[3];
        assertFalse(PasswordHasher.verify("anything", bad));
    }

    @Test
    void verifyAcceptsCorrectPasswordAgainstFreshHash() {
        // Happy path — sanity check that the catch arms don't hide a bug.
        var hash = PasswordHasher.hash("super-secret-pass");
        assertTrue(PasswordHasher.verify("super-secret-pass", hash));
    }

    @Test
    void verifyRejectsWrongPasswordAgainstValidHash() {
        var hash = PasswordHasher.hash("first-password");
        assertFalse(PasswordHasher.verify("second-password", hash));
    }

    // JCLAW-731: the work factor was raised from 150,000 to OWASP-2023's
    // 600,000. verify() reads the iteration count from the stored string, not
    // the constant, so a hash written at the old factor must still authenticate.
    @Test
    void freshHashUsesRaisedIterationCount() {
        var stored = PasswordHasher.hash("x");
        var iterations = Integer.parseInt(stored.split(":")[1]);
        assertEquals(600_000, iterations,
                "new hashes must use the OWASP-2023 PBKDF2-HMAC-SHA256 work factor");
    }

    @Test
    void verifyAcceptsLegacyLowerIterationHash() throws Exception {
        // Build a hash at the pre-JCLAW-731 factor (150k) using the same scheme
        // PasswordHasher stores (pbkdf2-sha256:<iters>:<b64 salt>:<b64 hash>,
        // 256-bit, unpadded base64), then prove the current code verifies it.
        var pw = "legacy-admin-secret";
        var salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        var legacyIterations = 150_000;
        var spec = new javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, legacyIterations, 256);
        var skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        var hash = skf.generateSecret(spec).getEncoded();
        var b64 = java.util.Base64.getEncoder().withoutPadding();
        var stored = "pbkdf2-sha256:%d:%s:%s".formatted(
                legacyIterations, b64.encodeToString(salt), b64.encodeToString(hash));

        assertTrue(PasswordHasher.verify(pw, stored),
                "a legacy 150k-iteration hash must still verify after the bump");
        assertFalse(PasswordHasher.verify("wrong-password", stored));
    }
}
