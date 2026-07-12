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
 * <p>Iteration count is pinned at {@link #ITERATIONS} — 600,000, the OWASP-2023
 * work factor for PBKDF2-HMAC-SHA256 (JCLAW-731 raised it from 150,000).
 * {@link #verify} reads the count from the stored string rather than the
 * constant, so hashes written at any earlier factor still authenticate — no
 * forced reset. Transparent rehash-on-login (upgrade an old hash on the next
 * successful verify) is a one-line change at the caller and remains unwired;
 * within the single-admin model a manual password reset is the upgrade path.
 *
 * <p>Why PBKDF2 instead of BCrypt: no new dependency needed, JDK ships the
 * algorithm, and the threat model is "single-user self-hosted app" — the
 * password file never crosses a network boundary, so the marginal benefit
 * of BCrypt's memory-hardness doesn't justify pulling in a library.
 */
public final class PasswordHasher {

    private PasswordHasher() {}

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hash a password for storage. Returns a self-describing string
     *  suitable for direct storage in ConfigService. */
    public static String hash(String plaintext) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
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
            throw new IllegalStateException("PBKDF2 unavailable — JDK install broken?", e);
        }
    }

}
