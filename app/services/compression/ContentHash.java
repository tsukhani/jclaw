package services.compression;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 content hashing for the CCR (Compress-Cache-Retrieve) mechanism
 * (JCLAW-462). The compression pipeline stamps a short {@link #handle} into the
 * marker it leaves on a compressed message; {@code ccr_retrieve} recomputes the
 * hash over the durable {@code Message.content} in the conversation to find and
 * return the original. No separate cache table — the Message row IS the cache.
 */
public final class ContentHash {

    private ContentHash() {}

    /** Lowercase 64-char hex SHA-256 of {@code content} (UTF-8). */
    public static String sha256Hex(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every conformant JRE — unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Short retrieval handle embedded in compression markers: the first 16 hex
     * chars (64 bits) of the content hash. Collision-free across the handful of
     * compressed messages in one conversation, while keeping the marker compact.
     * {@code ccr_retrieve} matches by this prefix.
     */
    public static String handle(String content) {
        return sha256Hex(content).substring(0, 16);
    }
}
