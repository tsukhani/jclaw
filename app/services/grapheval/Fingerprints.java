package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * The {@code <prefix>@<12 hex>} stamps the graph eval writes (JCLAW-1367, JCLAW-1368): a SHA-256 prefix over bytes, or
 * over a JSON document with its keys sorted at every level, written compact. Pure.
 */
public final class Fingerprints {

    private static final int HEX = 12;

    private Fingerprints() {}

    /** {@code e} with every object's keys sorted, recursively; arrays keep their order. */
    public static JsonElement canonical(JsonElement e) {
        if (e.isJsonObject()) {
            var sorted = new JsonObject();
            var object = e.getAsJsonObject();
            for (var key : new TreeSet<>(object.keySet())) sorted.add(key, canonical(object.get(key)));
            return sorted;
        }
        if (e.isJsonArray()) {
            var out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(canonical(x)));
            return out;
        }
        return e;
    }

    public static String hex12(String prefix, JsonElement e) {
        return hex12(prefix, canonical(e).toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String hex12(String prefix, byte[] bytes) {
        return prefix + "@" + hex12(bytes);
    }

    /** The bare 12-hex SHA-256 prefix over {@code e}'s canonical form. */
    public static String hex12(JsonElement e) {
        return hex12(canonical(e).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hex12(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest).substring(0, HEX);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
