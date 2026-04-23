package utils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing via JDK-native PBKDF2 with HMAC-SHA256.
 *
 * <p>The on-disk format is a single colon-separated string so the hash fits
 * into a single {@code ConfigService} value without extra columns:
 * {@code pbkdf2-sha256:<iterations>:<base64-salt>:<base64-hash>}.
 *
 * <p>Iteration count is pinned at {@link #ITERATIONS}; bumping it requires a
 * one-time re-hash on next login (not yet implemented — single-admin scope
 * means manual password reset works as the upgrade path).
 *
 * <p>Why PBKDF2 instead of BCrypt: no new dependency needed, JDK ships the
 * algorithm, and the threat model is "single-user self-hosted app" — the
 * password file never crosses a network boundary, so the marginal benefit
 * of BCrypt's memory-hardness doesn't justify pulling in a library.
 */
public final class PasswordHasher {

    private PasswordHasher() {}

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 150_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";

    /** Hash a password for storage. Returns a self-describing string
     *  suitable for direct storage in ConfigService. */
    public static String hash(String plaintext) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(plaintext.toCharArray(), salt, ITERATIONS, HASH_BITS);
        var b64 = Base64.getEncoder().withoutPadding();
        return "%s:%d:%s:%s".formatted(
                PREFIX, ITERATIONS,
                b64.encodeToString(salt),
                b64.encodeToString(hash));
    }

    /** Constant-time verify a plaintext against a stored hash string.
     *  Returns false on any parse error rather than throwing — callers
     *  treat a malformed stored hash as "password unset". */
    public static boolean verify(String plaintext, String stored) {
        if (plaintext == null || stored == null) return false;
        var parts = stored.split(":");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            var b64 = Base64.getDecoder();
            byte[] salt = b64.decode(parts[2]);
            byte[] expected = b64.decode(parts[3]);
            byte[] actual = pbkdf2(plaintext.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        }
        catch (Exception _) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        try {
            var spec = new PBEKeySpec(password, salt, iterations, keyBits);
            var skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        }
        catch (Exception e) {
            throw new RuntimeException("PBKDF2 unavailable — JDK install broken?", e);
        }
    }

}
