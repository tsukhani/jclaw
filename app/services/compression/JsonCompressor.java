package services.compression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/**
 * JSON SmartCrusher (JCLAW-461). Compresses a JSON tool output while keeping it
 * navigable for the LLM:
 *
 * <ul>
 *   <li><b>All object keys preserved</b> — the LLM needs to know what fields
 *       exist; only values shrink.</li>
 *   <li><b>First {@code maxArrayItemsFull} array items kept in full</b> for
 *       schema learning; the rest are elided with a count marker, EXCEPT items
 *       that look like errors/anomalies, which are always kept.</li>
 *   <li><b>Long string values truncated</b> to a head preview + char-count
 *       marker; short strings and high-entropy identifiers (UUIDs, hashes) are
 *       kept verbatim because they're navigational.</li>
 *   <li><b>Numbers, booleans, nulls preserved</b> verbatim — token-cheap and
 *       often meaningful (IDs, flags).</li>
 * </ul>
 *
 * <p>Output is compact (no pretty-printing) and is NOT required to be valid
 * JSON — the elision markers are human/LLM-readable strings. The original is
 * retained by the pipeline (CCR, JCLAW-462) so any elision is reversible.
 *
 * <p>Inflation guard: if the rewritten form isn't shorter than the input, the
 * input is returned unchanged. (The pipeline applies the authoritative
 * token-level guard on top of this cheap char-level one.)
 */
public final class JsonCompressor implements ContentCompressor {

    public static final String ALGORITHM_NAME = "json-smartcrush";

    // Defaults mirror Headroom's json_handler.
    private final int maxArrayItemsFull;
    private final int shortValueThreshold;
    private final int stringPreviewChars;

    public JsonCompressor() {
        this(3, 20, 24);
    }

    public JsonCompressor(int maxArrayItemsFull, int shortValueThreshold, int stringPreviewChars) {
        this.maxArrayItemsFull = maxArrayItemsFull;
        this.shortValueThreshold = shortValueThreshold;
        this.stringPreviewChars = stringPreviewChars;
    }

    // Compact serializer (default Gson is non-pretty). serializeNulls keeps
    // null-valued keys — dropping them would violate "preserve all keys", since
    // a present-but-null field is navigational information for the LLM.
    // Stateless and thread-safe.
    private static final Gson COMPACT = new GsonBuilder().serializeNulls().create();

    @Override
    public String algorithm() {
        return ALGORITHM_NAME;
    }

    @Override
    public CompressionResult compress(String content) {
        // JsonSpan tolerates a short non-JSON prefix (e.g. jclaw_api's
        // "HTTP 200\n" status line) and hands back the parsed body; the prefix
        // is carried through verbatim so the LLM keeps that context.
        var span = JsonSpan.find(content).orElse(null);
        if (span == null) {
            return CompressionResult.unchanged(content, ALGORITHM_NAME);
        }

        var out = span.prefix() + COMPACT.toJson(transform(span.json()));
        if (out.length() >= content.length()) {
            // Crushing didn't help (already compact / tiny) — don't inflate.
            return CompressionResult.unchanged(content, ALGORITHM_NAME);
        }
        return CompressionResult.compressed(out, ALGORITHM_NAME);
    }

    private JsonElement transform(JsonElement el) {
        if (el.isJsonObject()) return transformObject(el.getAsJsonObject());
        if (el.isJsonArray()) return transformArray(el.getAsJsonArray());
        if (el.isJsonPrimitive()) return transformPrimitive(el.getAsJsonPrimitive());
        return el; // JsonNull
    }

    private JsonObject transformObject(JsonObject obj) {
        var out = new JsonObject();
        for (var entry : obj.entrySet()) {
            out.add(entry.getKey(), transform(entry.getValue())); // preserve ALL keys
        }
        return out;
    }

    private JsonArray transformArray(JsonArray arr) {
        var out = new JsonArray();
        int elided = 0;
        for (int i = 0; i < arr.size(); i++) {
            var item = arr.get(i);
            if (i < maxArrayItemsFull || isAnomaly(item)) {
                out.add(transform(item));
            } else {
                elided++;
            }
        }
        if (elided > 0) {
            out.add(new JsonPrimitive("… +" + elided + " items elided"));
        }
        return out;
    }

    /**
     * Items that look like errors/anomalies are never elided — surfacing a
     * failure buried at index 400 is the whole point of content-awareness.
     */
    private boolean isAnomaly(JsonElement item) {
        var s = item.toString().toLowerCase(Locale.ROOT);
        return s.contains("error") || s.contains("fail")
                || s.contains("exception") || s.contains("\"warn");
    }

    private JsonElement transformPrimitive(JsonPrimitive p) {
        if (!p.isString()) {
            return p; // numbers, booleans preserved verbatim
        }
        var s = p.getAsString();
        if (s.length() <= shortValueThreshold || isHighEntropy(s)) {
            return p;
        }
        var head = s.substring(0, Math.min(stringPreviewChars, s.length()));
        var truncated = head + "…(+" + (s.length() - head.length()) + " chars)";
        // Per-value guard: only swap in the marker when it actually saves chars.
        return truncated.length() < s.length() ? new JsonPrimitive(truncated) : p;
    }

    /**
     * High-entropy identifiers — UUIDs, content hashes, opaque tokens — are
     * kept verbatim: they carry no redundancy to squeeze and the LLM may need
     * to reference them exactly. Heuristic: long, space-free, identifier-shaped.
     */
    private boolean isHighEntropy(String s) {
        if (s.length() < 16 || s.indexOf(' ') >= 0) return false;
        return s.matches("[A-Za-z0-9_\\-:./+=]{16,}");
    }
}
