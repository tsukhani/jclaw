package services.grapheval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Term ids derived from data, never minted (JCLAW-1370), so a Term withdrawn with its source and recreated by a
 * successor, re-extracted, or renamed as the owner keeps its id. Each is a prefix plus 16 hex of SHA-256 over the
 * parts joined by U+001F, the first part being the family.
 */
public final class TermIds {

    public static final String ARTIFACT = "Artifact";
    private static final int HEX = 16;

    private TermIds() {}

    /** A distinctive name: agent, type and canonical key. */
    public static String distinctive(long agentId, String type, String key) {
        return derived("term", "name", Long.toString(agentId), type, key);
    }

    /** A URL, path, ticket key or email: agent, {@code Artifact} and identifier key. */
    public static String identifier(long agentId, String key) {
        return derived("term", "id", Long.toString(agentId), ARTIFACT, key);
    }

    /** A name that fails the distinctive-name gate: also the memory that introduced it, so a re-pass makes no duplicate. */
    public static String shortName(long agentId, String type, String key, long memoryId) {
        return derived("term", "short", Long.toString(agentId), type, key, Long.toString(memoryId));
    }

    /** The owner's Person: agent and a fixed marker, never the name. */
    public static String owner(long agentId) {
        return derived("term", "owner", Long.toString(agentId));
    }

    /** {@code <prefix>:} plus 16 hex over {@code parts}; Mapping, Evidence-copy and Mapping-copy ids. */
    public static String derived(String prefix, String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\u001F", parts).getBytes(StandardCharsets.UTF_8));
            return prefix + ":" + HexFormat.of().formatHex(digest).substring(0, HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
