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
}
