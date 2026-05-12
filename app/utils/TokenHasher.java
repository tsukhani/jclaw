package utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mint and verify API bearer tokens (JCLAW-282).
 *
 * <p>Tokens are 32 random bytes encoded as base64url, prefixed with
 * {@code jcl_} so operators and secret scanners can recognise a leaked
 * token at a glance. The on-disk representation is the SHA-256 hex of
 * the full token string; the database stores the hash, never the
 * plaintext.
 *
 * <p>Why SHA-256 (not PBKDF2 like {@link PasswordHasher}): the token
 * carries ~256 bits of entropy from a CSPRNG, so the brute-force cost
 * is already astronomical. PBKDF2's salting exists to defeat rainbow
 * tables on low-entropy human passwords — applying it here would only
 * forfeit the O(1) bearer-lookup hashes give us (each PBKDF2 row has
 * a unique salt; a deterministic-hash lookup wouldn't work). This
 * matches the design GitHub PATs and npm tokens settled on.
 *
 * <p>The {@link #prefix(String)} helper extracts a short non-secret
 * fragment ({@code jcl_xxxxxxxx}) for UI display in the token list so
 * operators can tell tokens apart without us ever leaking the secret.
 */
public final class TokenHasher {

    public static final String TOKEN_PREFIX = "jcl_";
    private static final int RANDOM_BYTES = 32;
    private static final int DISPLAY_PREFIX_LENGTH = 12; // jcl_ + 8 chars

    private TokenHasher() {}

    /** Mint a new plaintext token. Callers must hand this to the user
     *  exactly once and discard it; the database only sees {@link #hash}. */
    public static String mint() {
        var bytes = new byte[RANDOM_BYTES];
        new SecureRandom().nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the token. Deterministic — used as the DB lookup key. */
    public static String hash(String token) {
        if (token == null) throw new IllegalArgumentException("token required");
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(digest.length * 2);
            for (var b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable — JDK install broken?", e);
        }
    }

    /** Short non-secret display fragment ({@code jcl_xxxxxxxx}). Stored
     *  alongside the hash so the UI can render a recognizable label
     *  without round-tripping the secret. */
    public static String prefix(String token) {
        if (token == null) return null;
        return token.length() <= DISPLAY_PREFIX_LENGTH
                ? token
                : token.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /** Constant-time hash comparison for the bearer-resolve path.
     *  Both inputs are already hex digests, so a length-mismatch is
     *  the malformed-input case rather than a timing leak. */
    public static boolean hashesEqual(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
